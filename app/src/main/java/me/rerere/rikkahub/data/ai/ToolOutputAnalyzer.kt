package me.rerere.rikkahub.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工具输出的结构化分析。
 *
 * 背景：超长工具输出被截断时，若只保留头尾文本，模型看不到「整体形状」——
 * 例如一个 5000 条的 JSON 数组，头尾各看几行，既不知道总量，也不知道有哪些字段。
 * 这里在截断时额外生成一份轻量摘要：条数、字段清单、类型、出现率、样例。
 *
 * 设计约束：
 * - 只读、纯函数，绝不抛异常（任何失败都退化为 null，走原来的纯文本截断）
 * - 有解析上限，避免在超大文本上做全量 JSON 解析而卡住主流程
 * - 输出长度受限，避免「摘要本身」又变成新的上下文负担
 */
internal object ToolOutputAnalyzer {

    /**
     * 参与 JSON 解析的最大字符数。
     * 取 2MB：手机内存可承受，且能覆盖绝大多数工具输出；
     * 超出则只解析前 2MB（并标注 truncated）。
     */
    private const val MAX_PARSE_CHARS = 2 * 1024 * 1024

    /** 采样条数上限（用于字段统计）。 */
    private const val MAX_SAMPLE_ITEMS = 200

    /**
     * 超大数组的降级采样上限：
     * 若整体解析失败（例如被截断成非法 JSON），退而尝试逐元素流式解析。
     */
    private const val MAX_STREAM_ELEMENTS = 500

    /** 摘要里展示的样例条数。 */
    private const val MAX_EXAMPLES = 2

    /** 单条样例截断长度。 */
    private const val MAX_EXAMPLE_CHARS = 300

    /** 摘要总长度上限。 */
    private const val MAX_SUMMARY_CHARS = 1600

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        allowTrailingComma = true
    }

    /**
     * 生成结构化摘要；无法识别时返回 null。
     *
     * 支持三种形态：
     * 1. 单个 JSON 值（对象 / 数组）
     * 2. JSON Lines（每行一个 JSON 对象）
     * 3. 其余情况返回 null
     */
    fun summarize(text: String): String? = runCatching {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return@runCatching null

        // JSON Lines 优先判断：逐行都是对象，但整体不是合法 JSON
        val looksLikeJson = trimmed.startsWith("{") || trimmed.startsWith("[")
        if (looksLikeJson) {
            parseSingleJson(trimmed)?.let { return@runCatching it }
        }
        parseJsonLines(trimmed)
    }.getOrNull()

    // ---------- 单个 JSON ----------

    private fun parseSingleJson(text: String): String? {
        val slice = text.take(MAX_PARSE_CHARS)
        val truncated = slice.length < text.length
        val element = runCatching { json.parseToJsonElement(slice) }.getOrNull()

        if (element is JsonArray) return describeArray(element, truncated)
        if (element is JsonObject) return describeObject(element)

        // 整体解析失败时的降级：可能是超大数组（被 MAX_PARSE_CHARS 截断而成为非法 JSON）。
        // 用括号配平扫描提取若干完整元素做统计，不要求整体文本是合法 JSON。
        return scanArrayElements(slice)
    }

    /**
     * 括号配平扫描：从以 '[' 开头的文本里提取完整的顶层元素。
     * 正确跳过字符串字面量（含转义），因此不会把字符串内的括号误当结构符号。
     * 最多提取 [MAX_STREAM_ELEMENTS] 个元素。
     */
    private fun scanArrayElements(text: String): String? {
        val start = text.indexOf('[')
        if (start < 0) return null

        val items = ArrayList<String>(64)
        var depth = 0
        var inString = false
        var escaped = false
        var itemStart = -1

        var i = start
        while (i < text.length && items.size < MAX_STREAM_ELEMENTS) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> {
                        inString = true
                        if (depth == 1 && itemStart < 0) itemStart = i
                    }
                    '[', '{' -> {
                        if (depth == 1 && itemStart < 0) itemStart = i
                        depth++
                    }
                    ']', '}' -> {
                        depth--
                        if (depth == 1 && itemStart >= 0) {
                            val raw = text.substring(itemStart, i + 1).trim()
                            if (raw.isNotEmpty()) items.add(raw)
                            itemStart = -1
                        } else if (depth == 0 && itemStart >= 0) {
                            // 数组闭合前，最后一个「标量元素」（数字/字符串/布尔/裸字面量）
                            // 不是以 '}' 收尾的，必须在遇到 ']' 时补收，否则会丢掉末尾元素。
                            val raw = text.substring(itemStart, i).trim()
                            if (raw.isNotEmpty()) items.add(raw)
                            itemStart = -1
                            break
                        } else if (depth <= 0) {
                            break
                        }
                    }
                    ',' -> {
                        if (depth == 1) {
                            if (itemStart >= 0) {
                                val raw = text.substring(itemStart, i).trim()
                                if (raw.isNotEmpty()) items.add(raw)
                            }
                            itemStart = -1
                        }
                    }
                    else -> {
                        if (depth == 1 && itemStart < 0 && !c.isWhitespace()) itemStart = i
                    }
                }
            }
            i++
        }

        if (items.size < 2) return null

        val objects = items.mapNotNull { raw ->
            runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        }

        return buildString {
            appendLine("Shape: JSON array (scanned, overall text may be truncated)")
            appendLine("Total elements available: ${items.size}${if (items.size >= MAX_STREAM_ELEMENTS) "+ (capped at $MAX_STREAM_ELEMENTS)" else ""}")
            appendLine("Note: the field table below is based on a sample, not the full array.")
            if (objects.isNotEmpty() && objects.size >= items.size * 0.8) {
                val sample = objects.take(MAX_SAMPLE_ITEMS)
                val stats = fieldStats(sample)
                if (stats.isNotEmpty()) {
                    appendLine("Fields (name | types | present in sampled items):")
                    stats.take(40).forEach { f ->
                        appendLine("  - ${f.name} | ${f.types.joinToString(",")} | ${f.present}/${sample.size}")
                    }
                    if (stats.size > 40) appendLine("  ... and ${stats.size - 40} more fields")
                }
            } else {
                val counts = LinkedHashMap<String, Int>()
                items.forEach { raw ->
                    val t = runCatching { elementType(json.parseToJsonElement(raw)) }.getOrDefault("unknown")
                    counts[t] = (counts[t] ?: 0) + 1
                }
                appendLine("Element types: " + counts.entries.joinToString(", ") { "${it.key}=${it.value}" })
            }
            appendLine("Example element(s):")
            items.take(MAX_EXAMPLES).forEachIndexed { idx, raw ->
                appendLine("  [$idx] " + raw.take(MAX_EXAMPLE_CHARS))
            }
        }
    }

    private fun describeArray(array: JsonArray, truncated: Boolean): String = buildString {
        appendLine("Shape: JSON array")
        appendLine("Items: ${array.size}${if (truncated) "+ (parsed from first ${MAX_PARSE_CHARS / 1024}KB, total longer)" else ""}")

        val objects = array.filterIsInstance<JsonObject>()
        if (objects.isEmpty()) {
            // 标量数组 / 嵌套数组：给类型分布
            appendLine("Element types: ${elementTypeSummary(array)}")
            appendExamples(array)
            return@buildString
        }

        if (objects.size < array.size) {
            appendLine("Note: array mixes objects with other element types (${array.size - objects.size} non-object)")
        }
        appendLine("Element object count: ${objects.size}")

        val sample = objects.take(MAX_SAMPLE_ITEMS)
        val stats = fieldStats(sample)
        if (stats.isNotEmpty()) {
            appendLine("Fields (name | types | present in):")
            stats.take(40).forEach { f ->
                appendLine("  - ${f.name} | ${f.types.joinToString(",")} | ${f.present}/${sample.size}")
            }
            if (stats.size > 40) appendLine("  ... and ${stats.size - 40} more fields")
        }
        appendExamples(array)
    }

    private fun describeObject(obj: JsonObject): String = buildString {
        appendLine("Shape: JSON object")
        appendLine("Top-level keys: ${obj.size}")
        obj.entries.take(40).forEach { (k, v) ->
            val typeName = elementType(v)
            val extra = when (v) {
                is JsonArray -> " (len=${v.size})"
                is JsonObject -> " (keys=${v.size})"
                is JsonPrimitive -> {
                    val c = v.content
                    if (c.length in 1..40) " = ${c.take(40)}" else ""
                }
                else -> ""
            }
            appendLine("  - $k: $typeName$extra")
        }
        if (obj.size > 40) appendLine("  ... and ${obj.size - 40} more keys")
    }

    // ---------- JSON Lines ----------

    private fun parseJsonLines(text: String): String? {
        val lines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .take(MAX_SAMPLE_ITEMS)
            .toList()

        if (lines.size < 2) return null
        // 要求绝大多数行都是 JSON 对象，避免把普通文本误判成 JSONL
        val parsed = lines.mapNotNull { line ->
            runCatching { json.parseToJsonElement(line) }.getOrNull() as? JsonObject
        }
        if (parsed.size < lines.size * 0.8 || parsed.isEmpty()) return null

        return buildString {
            appendLine("Shape: JSON Lines (one JSON object per line)")
            appendLine("Parsed lines: ${parsed.size} (sampled)")
            val stats = fieldStats(parsed)
            if (stats.isNotEmpty()) {
                appendLine("Fields (name | types | present in):")
                stats.take(30).forEach { f ->
                    appendLine("  - ${f.name} | ${f.types.joinToString(",")} | ${f.present}/${parsed.size}")
                }
                if (stats.size > 30) appendLine("  ... and ${stats.size - 30} more fields")
            }
            appendLine("Example line:")
            appendLine("  " + parsed.first().toString().take(MAX_EXAMPLE_CHARS))
        }
    }

    // ---------- 辅助 ----------

    private data class FieldStat(
        val name: String,
        val types: Set<String>,
        val present: Int,
    )

    private fun fieldStats(objects: List<JsonObject>): List<FieldStat> {
        data class Acc(var present: Int, val types: MutableSet<String>)
        val map = LinkedHashMap<String, Acc>()
        objects.forEach { obj ->
            obj.forEach { (key, value) ->
                val acc = map.getOrPut(key) { Acc(0, mutableSetOf()) }
                acc.present += 1
                acc.types += elementType(value)
            }
        }
        return map.entries
            .map { (name, acc) -> FieldStat(name, acc.types, acc.present) }
            .sortedWith(compareByDescending<FieldStat> { it.present }.thenBy { it.name })
    }

    private fun elementType(element: JsonElement?): String = when (element) {
        null -> "null"
        is JsonNull -> "null"
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonPrimitive -> when {
            element.isString -> "string"
            element.content == "true" || element.content == "false" -> "boolean"
            element.content.toDoubleOrNull() != null -> "number"
            else -> "string"
        }
        else -> "unknown"
    }

    private fun elementTypeSummary(array: JsonArray): String {
        val counts = LinkedHashMap<String, Int>()
        // 只统计前 1000 个，避免大数组遍历开销
        array.take(1000).forEach { e ->
            val t = elementType(e)
            counts[t] = (counts[t] ?: 0) + 1
        }
        return counts.entries.joinToString(", ") { "${it.key}=${it.value}" }
    }

    private fun StringBuilder.appendExamples(array: JsonArray) {
        if (array.isEmpty()) return
        appendLine("Example item(s):")
        array.take(MAX_EXAMPLES).forEachIndexed { idx, item ->
            appendLine("  [$idx] " + item.toString().take(MAX_EXAMPLE_CHARS))
        }
    }

    /** 把分析结果裁剪到上限，防止摘要本身过长。 */
    fun clamp(summary: String): String =
        if (summary.length <= MAX_SUMMARY_CHARS) summary
        else summary.take(MAX_SUMMARY_CHARS) + "\n  ...(analysis truncated)"
}
