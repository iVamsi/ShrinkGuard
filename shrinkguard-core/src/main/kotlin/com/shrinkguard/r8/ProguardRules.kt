package com.shrinkguard.r8

/**
 * R8 treats each [com.android.tools.r8.R8Command.Builder.addProguardConfiguration] list
 * element as one line. A multiline keep block passed as a single string is therefore one
 * illegal rule. Split first, then drop comments and blanks.
 */
object ProguardRules {

    fun toLines(rules: List<String>): List<String> {
        return rules.flatMap { it.split('\n') }
            .map { it.trimEnd() }
            .filter { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() && !trimmed.startsWith("#")
            }
    }
}
