package com.shrinkguard.report

import java.io.File

data class DiffResult(
    val hasDifferences: Boolean,
    val diffText: String,
    val addedLines: List<String> = emptyList(),
    val removedLines: List<String> = emptyList()
)

class ShrinkReportDiff {

    fun diffFiles(baselineFile: File, generatedFile: File): DiffResult {
        val baselineLines = if (baselineFile.exists()) baselineFile.readLines() else emptyList()
        val generatedLines = if (generatedFile.exists()) generatedFile.readLines() else emptyList()
        return diffLines(baselineLines, generatedLines, baselineFile.name, generatedFile.name)
    }

    fun diffLines(
        baselineLines: List<String>,
        generatedLines: List<String>,
        baselineName: String = "baseline",
        generatedName: String = "generated"
    ): DiffResult {
        if (baselineLines == generatedLines) {
            return DiffResult(hasDifferences = false, diffText = "")
        }

        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val diffSb = StringBuilder()

        diffSb.append("--- $baselineName\n")
        diffSb.append("+++ $generatedName\n")

        // Simple Myers/LCS diff or line-by-line comparison
        val lcs = computeLcs(baselineLines, generatedLines)
        var i = 0
        var j = 0

        for (match in lcs) {
            while (i < match.first) {
                diffSb.append("- ${baselineLines[i]}\n")
                removed.add(baselineLines[i])
                i++
            }
            while (j < match.second) {
                diffSb.append("+ ${generatedLines[j]}\n")
                added.add(generatedLines[j])
                j++
            }
            diffSb.append("  ${baselineLines[i]}\n")
            i++
            j++
        }

        while (i < baselineLines.size) {
            diffSb.append("- ${baselineLines[i]}\n")
            removed.add(baselineLines[i])
            i++
        }
        while (j < generatedLines.size) {
            diffSb.append("+ ${generatedLines[j]}\n")
            added.add(generatedLines[j])
            j++
        }

        return DiffResult(
            hasDifferences = true,
            diffText = diffSb.toString(),
            addedLines = added,
            removedLines = removed
        )
    }

    private fun computeLcs(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val m = a.size
        val n = b.size
        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0 until m) {
            for (j in 0 until n) {
                dp[i + 1][j + 1] = if (a[i] == b[j]) {
                    dp[i][j] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val matches = mutableListOf<Pair<Int, Int>>()
        var i = m
        var j = n
        while (i > 0 && j > 0) {
            if (a[i - 1] == b[j - 1]) {
                matches.add(Pair(i - 1, j - 1))
                i--
                j--
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                i--
            } else {
                j--
            }
        }
        matches.reverse()
        return matches
    }
}
