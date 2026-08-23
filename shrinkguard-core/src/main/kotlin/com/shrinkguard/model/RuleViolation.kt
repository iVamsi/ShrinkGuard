package com.shrinkguard.model

data class RuleViolation(
    val rule: String,
    val lineNumber: Int? = null,
    val sourceFile: String? = null,
    val severity: RuleSeverity = RuleSeverity.ERROR,
    val message: String,
    val recommendation: String? = null
) {
    fun format(): String {
        val location = when {
            sourceFile != null && lineNumber != null -> "$sourceFile:$lineNumber"
            sourceFile != null -> sourceFile
            lineNumber != null -> "line $lineNumber"
            else -> "rules"
        }
        val prefix = when (severity) {
            RuleSeverity.ERROR -> "ERROR"
            RuleSeverity.WARNING -> "WARNING"
            RuleSeverity.INFO -> "INFO"
        }
        val builder = StringBuilder()
        builder.append("[$prefix] ($location): $message\n")
        builder.append("  Rule: $rule\n")
        if (recommendation != null) {
            builder.append("  Hint: $recommendation\n")
        }
        return builder.toString()
    }
}
