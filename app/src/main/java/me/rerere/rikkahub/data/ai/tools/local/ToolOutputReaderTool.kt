package me.rerere.rikkahub.data.ai.tools.local

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.files.FileFolders
import java.io.File

/** 与 GenerationLoop 共用的工具名常量（单点定义，避免两处漂移）。 */
const val TOOL_OUTPUT_READER_TOOL_NAME = "tool_output_read"

private const val MAX_READ_CHARS = 16 * 1024
private const val MAX_SEARCH_MATCHES = 50
private const val MAX_LIST_ENTRIES = 50

/**
 * 读取 / 检索「被截断的工具输出」。
 *
 * 背景：当某个工具返回的数据超过阈值时，完整内容会落盘到
 * `tool_outputs/<toolCallId>.txt`，上下文里只保留结构化预览。
 * 没有启用 workspace（即没有 shell）的助手无法用 `cat` / `grep` 取回原文，
 * 这个工具就是给它们用的：无需 shell 也能读回或搜索完整输出。
 */
fun buildToolOutputReaderTool(context: Context): Tool = Tool(
    name = TOOL_OUTPUT_READER_TOOL_NAME,
    description = """
        Read or search the full content of a previously truncated tool output.
        When a tool returns a very large result, only a preview is kept in context; the complete content is
        saved and can be accessed here.
        Actions:
          - list:   list saved outputs (id, size, time).
          - read:   read a slice of one output (requires id; optional offset).
          - search: find lines containing a keyword (requires id and query).
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("action", buildJsonObject {
                    put("type", "string")
                    put("description", "One of: list, read, search")
                })
                put("id", buildJsonObject {
                    put("type", "string")
                    put("description", "Output id from the truncation notice (e.g. call_abc). Required for read/search.")
                })
                put("offset", buildJsonObject {
                    put("type", "integer")
                    put("description", "Character offset to start reading from. Default 0. Only used by read.")
                })
                put("query", buildJsonObject {
                    put("type", "string")
                    put("description", "Keyword to search for (case-insensitive). Required for search.")
                })
            },
            required = listOf("action"),
        )
    },
    execute = { args ->
        val obj = args.jsonObject
        val action = obj.string("action") ?: "list"
        val dir = File(context.filesDir, FileFolders.TOOL_OUTPUTS)

        val text: String = when (action) {
            "list" -> {
                val files = dir.listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".txt") }
                    ?.sortedByDescending { it.lastModified() }
                    ?.take(MAX_LIST_ENTRIES)
                    .orEmpty()
                if (files.isEmpty()) {
                    // 明确告知生命周期：目录在每次 App 启动时被清空，
                    // 之前会话保存的输出已不存在，避免 AI 反复重试。
                    """{"count":0,"note":"No saved tool outputs. This storage is temporary and is cleared every time the app starts; outputs from previous sessions no longer exist. Re-run the tool to regenerate if needed."}"""
                } else {
                    buildJsonObject {
                        put("count", files.size)
                        put("outputs", buildJsonArray {
                            files.forEach { f ->
                                add(
                                    buildJsonObject {
                                        put("id", f.name.removeSuffix(".txt"))
                                        put("sizeBytes", f.length())
                                        put("modifiedAt", f.lastModified())
                                    }
                                )
                            }
                        })
                    }.toString()
                }
            }

            "read" -> {
                val id = obj.string("id")
                val file = id?.let { resolveOutputFile(dir, it) }
                when {
                    id == null -> errorJson("id is required for read")
                    file == null -> errorJson("output not found: $id")
                    else -> {
                        val offset = obj.int("offset")?.coerceAtLeast(0) ?: 0
                        // 注意：工具输出文件保存的是【完整】原始输出，可能非常大。
                        // 不能 readText() 整份载入内存再截取（大文件会浪费内存甚至 OOM），
                        // 这里用 Reader 跳过 offset 后只读需要的那一段。
                        val slice = readSlice(file, offset, MAX_READ_CHARS)
                        // 注意：file.length() 是【字节】数，与 readSlice 的【字符】语义不一致，
                        // 多字节字符（如中文）会导致 hasMore/后续 offset 计算错位。
                        // 这里统一用字符数（UTF-16 单位），与 readSlice/offset 语义对齐。
                        val totalChars = fileCharCount(file)
                        buildJsonObject {
                            put("id", id)
                            put("totalChars", totalChars)
                            put("offset", offset)
                            put("returnedChars", slice.length)
                            put("hasMore", offset + slice.length < totalChars)
                            put("text", slice)
                        }.toString()
                    }
                }
            }

            "search" -> {
                val id = obj.string("id")
                val query = obj.string("query")
                val file = id?.let { resolveOutputFile(dir, it) }
                when {
                    id == null -> errorJson("id is required for search")
                    query == null -> errorJson("query is required for search")
                    file == null -> errorJson("output not found: $id")
                    else -> {
                        val matches = file.useLines { seq ->
                            seq.withIndex()
                                .filter { (_, line) -> line.contains(query, ignoreCase = true) }
                                .take(MAX_SEARCH_MATCHES)
                                .map { (idx, line) ->
                                    buildJsonObject {
                                        put("line", idx + 1)
                                        put("text", line.take(500))
                                    }
                                }
                                .toList()
                        }
                        buildJsonObject {
                            put("id", id)
                            put("query", query)
                            put("matchCount", matches.size)
                            put("matches", buildJsonArray { matches.forEach { add(it) } })
                        }.toString()
                    }
                }
            }

            else -> errorJson("unknown action: $action")
        }

        listOf(UIMessagePart.Text(text))
    },
)

/** 只允许访问 tool_outputs 目录内的文件，防止路径穿越。 */
private fun resolveOutputFile(dir: File, id: String): File? {
    val safeId = id.substringAfterLast('/').removeSuffix(".txt")
    if (safeId.isBlank() || safeId.contains("..")) return null
    val file = File(dir, "$safeId.txt")
    return if (file.isFile) file else null
}

/** 统计文件的字符数（UTF-16 单位）。readSlice 的 offset/limit 是字符语义，totalChars 必须与其对齐。 */
private fun fileCharCount(file: File): Long = file.reader().use { reader ->
    var count = 0L
    val buf = CharArray(64 * 1024)
    while (true) {
        val n = reader.read(buf)
        if (n < 0) break
        count += n
    }
    count
}

/**
 * 流式读取文件的一段：跳过 skip 个字符后，最多取 limit 个字符。
 * 避免把整个（可能很大的）文件读进内存。
 */
private fun readSlice(file: File, skip: Int, limit: Int): String {
    if (limit <= 0) return ""
    return file.reader().use { reader ->
        var toSkip = skip.toLong()
        while (toSkip > 0) {
            val skipped = reader.skip(toSkip)
            if (skipped <= 0) break
            toSkip -= skipped
        }
        val buffer = CharArray(limit)
        var filled = 0
        while (filled < limit) {
            val read = reader.read(buffer, filled, limit - filled)
            if (read < 0) break
            filled += read
        }
        String(buffer, 0, filled)
    }
}

private fun errorJson(message: String): String =
    JsonObject(mapOf("error" to JsonPrimitive(message))).toString()

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()
