package com.shrinkguard.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ShrinkReportDiffTest {

    private val diff = ShrinkReportDiff()

    @Test
    fun `identical lines produce no diff`() {
        val lines = listOf("Line 1", "Line 2", "Line 3")
        val result = diff.diffLines(lines, lines)

        assertFalse(result.hasDifferences)
        assertTrue(result.diffText.isEmpty())
    }

    @Test
    fun `detects additions and removals`() {
        val baseline = listOf("Line 1", "Line 2", "Line 3")
        val generated = listOf("Line 1", "Line 2 (modified)", "Line 3", "Line 4")

        val result = diff.diffLines(baseline, generated)

        assertTrue(result.hasDifferences)
        assertTrue(result.removedLines.contains("Line 2"))
        assertTrue(result.addedLines.contains("Line 2 (modified)"))
        assertTrue(result.addedLines.contains("Line 4"))
    }
}
