package com.shrinkguard.linter

import com.shrinkguard.model.RuleLintResult
import com.shrinkguard.model.RuleSeverity
import com.shrinkguard.model.RuleViolation
import java.io.File

data class RuleLintConfig(
    val failOnToxicFlags: Boolean = true,
    val failOnOverbroadRules: Boolean = false,
    val allowlistRules: Set<String> = emptySet()
)

class ConsumerRulesLinter(
    private val config: RuleLintConfig = RuleLintConfig()
) {

    companion object {
        private val TOXIC_GLOBAL_FLAGS = mapOf(
            "-dontobfuscate" to "Forces the consuming application to disable all obfuscation.",
            "-dontoptimize" to "Forces the consuming application to disable all R8 optimizations.",
            "-dontshrink" to "Forces the consuming application to disable all tree shaking (dead code elimination).",
            "-repackageclasses" to "Forces class repackaging on the entire consuming application.",
            "-flattenpackagehierarchy" to "Forces package hierarchy flattening across the entire consuming application.",
            "-optimizations" to "Overrides optimization passes globally for the entire application.",
            "-target" to "Overrides target bytecode version globally.",
            "-overloadaggressively" to "Enables aggressive member overloading globally.",
            "-useuniqueclassmembernames" to "Enforces unique member names globally."
        )

        private val OVERBROAD_PATTERNS = listOf(
            Regex("""-keep\b[^{]*\b\*\*\s*\{\s*\*\s*;\s*\}"""),
            Regex("""-keep\b[^{]*\bclass\s+\*\*\s*$"""),
            Regex("""-keep\b[^{]*\binterface\s+\*\*\s*$"""),
            Regex("""-keep\b[^{]*\bclass\s+[a-zA-Z0-9_.]+\.\*\*\s*\{\s*\*\s*;\s*\}"""),
            Regex("""-keep\b[^{]*\bclass\s+\*\s*\{\s*\*\s*;\s*\}"""),
            Regex("""-keepclassmembers\b[^{]*\*\*\s*\{\s*\*\s*;\s*\}""")
        )
    }

    fun lintFile(file: File): RuleLintResult {
        if (!file.exists()) {
            return RuleLintResult(emptyList())
        }
        val lines = file.readLines()
        return lintContent(lines, file.name)
    }

    fun lintContent(rules: String, sourceFileName: String? = null): RuleLintResult {
        return lintContent(rules.lines(), sourceFileName)
    }

    fun lintContent(lines: List<String>, sourceFileName: String? = null): RuleLintResult {
        val violations = mutableListOf<RuleViolation>()
        var currentRule = StringBuilder()
        var ruleStartLine = 1

        for ((index, rawLine) in lines.withIndex()) {
            val lineNumber = index + 1
            val trimmedLine = rawLine.substringBefore('#').trim()

            if (trimmedLine.isEmpty()) {
                continue
            }

            if (currentRule.isEmpty()) {
                ruleStartLine = lineNumber
            } else {
                currentRule.append(" ")
            }
            currentRule.append(trimmedLine)

            // ProGuard rule ends at newline unless escaped with '\' or inside '{ ... }'
            if (!isRuleIncomplete(currentRule.toString())) {
                val fullRule = currentRule.toString().trim()
                checkRule(fullRule, ruleStartLine, sourceFileName, violations)
                currentRule.clear()
            }
        }

        if (currentRule.isNotEmpty()) {
            val fullRule = currentRule.toString().trim()
            checkRule(fullRule, ruleStartLine, sourceFileName, violations)
        }

        return RuleLintResult(violations)
    }

    private fun isRuleIncomplete(rule: String): Boolean {
        val openBraces = rule.count { it == '{' }
        val closeBraces = rule.count { it == '}' }
        return openBraces > closeBraces || rule.endsWith('\\')
    }

    private fun checkRule(
        rule: String,
        lineNumber: Int,
        sourceFileName: String?,
        violations: MutableList<RuleViolation>
    ) {
        if (isAllowlisted(rule)) {
            return
        }

        // 1. Check for toxic global flags
        for ((flag, reason) in TOXIC_GLOBAL_FLAGS) {
            if (rule.startsWith(flag) || rule.split(Regex("\\s+")).firstOrNull() == flag) {
                val severity = if (config.failOnToxicFlags) RuleSeverity.ERROR else RuleSeverity.WARNING
                violations.add(
                    RuleViolation(
                        rule = rule,
                        lineNumber = lineNumber,
                        sourceFile = sourceFileName,
                        severity = severity,
                        message = "Forbidden global directive '$flag' in consumer rules. $reason",
                        recommendation = "Remove '$flag'. Library consumer rules must only contain rules scoped to the library itself."
                    )
                )
                return
            }
        }

        // 2. Check for over-broad wildcard keeps
        for (pattern in OVERBROAD_PATTERNS) {
            if (pattern.containsMatchIn(rule)) {
                val severity = if (config.failOnOverbroadRules) RuleSeverity.ERROR else RuleSeverity.WARNING
                violations.add(
                    RuleViolation(
                        rule = rule,
                        lineNumber = lineNumber,
                        sourceFile = sourceFileName,
                        severity = severity,
                        message = "Over-broad keep rule detected. This keeps entire packages/classes and bloats consuming apps.",
                        recommendation = "Narrow this rule using '-keepclassmembers' for specific fields/methods, or target only classes implementing a specific interface or annotation (e.g., @Keep)."
                    )
                )
                return
            }
        }
    }
    private fun isAllowlisted(rule: String): Boolean {
        val normalized = normalizeRule(rule)
        return config.allowlistRules.any { normalizeRule(it) == normalized }
    }

    private fun normalizeRule(rule: String): String = rule.trim().replace(Regex("\\s+"), " ")
}
