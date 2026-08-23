package com.shrinkguard.model

data class RuleLintResult(
    val violations: List<RuleViolation> = emptyList()
) {
    val isValid: Boolean get() = violations.none { it.severity == RuleSeverity.ERROR }
    val hasErrors: Boolean get() = violations.any { it.severity == RuleSeverity.ERROR }
    val hasWarnings: Boolean get() = violations.any { it.severity == RuleSeverity.WARNING }
}
