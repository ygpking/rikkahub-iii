package me.rerere.rikkahub.data.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolOutputAnalyzer 回归测试。
 *
 * 重点覆盖 isStrictJsonNumber 的边界（经由 internal summarize() 间接验证）：
 * lenient JSON 解析会把 "0x10" / "010" / "1f" 这类 Kotlin/十六进制风格字面量
 * 收成 JsonPrimitive，若无严格判定它们会被 elementType 误判为 "number"。
 */
class ToolOutputAnalyzerTest {

    @Test
    fun `hex literal is not a json number`() {
        val summary = ToolOutputAnalyzer.summarize("[0x10, 0x20]")
        assertTrue("summary should mention element types", summary?.contains("Element types:") == true)
        assertTrue(summary!!.contains("string=2"))
        assertFalse(summary.contains("number=2"))
    }

    @Test
    fun `leading zero literal is not a json number`() {
        val summary = ToolOutputAnalyzer.summarize("[010, 007]")
        assertTrue(summary!!.contains("string=2"))
        assertFalse(summary.contains("number=2"))
    }

    @Test
    fun `kotlin suffix literal is not a json number`() {
        val summary = ToolOutputAnalyzer.summarize("[1f, 2d]")
        assertTrue(summary!!.contains("string=2"))
        assertFalse(summary.contains("number=2"))
    }

    @Test
    fun `invalid exponent is not a json number`() {
        val summary = ToolOutputAnalyzer.summarize("[1e+, 1e]")
        assertTrue(summary!!.contains("string=2"))
        assertFalse(summary.contains("number=2"))
    }

    @Test
    fun `valid json numbers are recognized`() {
        val summary = ToolOutputAnalyzer.summarize("[1, 2.5, -3e2, 0, -0.5, 1E+2]")
        assertTrue(summary!!.contains("number=6"))
        assertFalse(summary.contains("string=6"))
    }

    @Test
    fun `quoted strings are never numbers`() {
        val summary = ToolOutputAnalyzer.summarize("""["1.5", "true", "0x10"]""")
        assertTrue(summary!!.contains("string=3"))
        assertFalse(summary.contains("number"))
        assertFalse(summary.contains("boolean"))
    }

    @Test
    fun `object field types are described`() {
        val summary = ToolOutputAnalyzer.summarize("""{"a":1,"b":"x","c":[1,2],"d":null}""")
        assertTrue(summary!!.contains("Shape: JSON object"))
        assertTrue(summary.contains("a: number"))
        assertTrue(summary.contains("b: string"))
        assertTrue(summary.contains("c: array"))
        assertTrue(summary.contains("d: null"))
    }

    @Test
    fun `plain text returns null`() {
        assertNull(ToolOutputAnalyzer.summarize("just some plain text output, not json at all"))
    }

    @Test
    fun `boolean is not number`() {
        val summary = ToolOutputAnalyzer.summarize("[true, false]")
        assertTrue(summary!!.contains("boolean=2"))
        assertFalse(summary.contains("number"))
    }
}
