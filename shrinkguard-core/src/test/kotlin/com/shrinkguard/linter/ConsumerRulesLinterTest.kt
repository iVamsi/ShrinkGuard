package com.shrinkguard.linter

import com.shrinkguard.model.RuleSeverity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsumerRulesLinterTest {

    private val linter = ConsumerRulesLinter()

    @Test
    fun `flags toxic dontobfuscate rule as error`() {
        val rules = """
            # Keep my reflection target
            -keep class com.example.MyModel { *; }
            -dontobfuscate
        """.trimIndent()

        val result = linter.lintContent(rules, "consumer-rules.pro")

        assertTrue(result.hasErrors)
        assertEquals(1, result.violations.count { it.severity == RuleSeverity.ERROR })
        assertEquals("-dontobfuscate", result.violations.first { it.severity == RuleSeverity.ERROR }.rule)
    }

    @Test
    fun `flags toxic dontoptimize rule as error`() {
        val rules = "-dontoptimize"
        val result = linter.lintContent(rules)

        assertTrue(result.hasErrors)
        assertEquals(RuleSeverity.ERROR, result.violations.first().severity)
    }

    @Test
    fun `flags overbroad keep wildcard as warning by default`() {
        val rules = "-keep class com.example.** { *; }"
        val result = linter.lintContent(rules)

        assertFalse(result.hasErrors)
        assertTrue(result.hasWarnings)
        assertEquals(RuleSeverity.WARNING, result.violations.first().severity)
    }

    @Test
    fun `passes specific valid keep rule`() {
        val rules = """
            # Valid narrow keep rule
            -keepclassmembers class com.example.DataModel {
                private java.lang.String id;
                public <init>();
            }
        """.trimIndent()

        val result = linter.lintContent(rules)

        assertFalse(result.hasErrors)
        assertFalse(result.hasWarnings)
        assertTrue(result.isValid)
    }

    @Test
    fun `allowlist matches a full rule not a substring`() {
        val linter = ConsumerRulesLinter(
            RuleLintConfig(allowlistRules = setOf("keep"))
        )
        val result = linter.lintContent("-keep class com.example.** { *; }")

        assertTrue(result.hasWarnings)
    }

    @Test
    fun `allowlist skips the exact rule`() {
        val rule = "-keep class com.example.** { *; }"
        val linter = ConsumerRulesLinter(
            RuleLintConfig(allowlistRules = setOf(rule))
        )
        val result = linter.lintContent(rule)

        assertFalse(result.hasErrors)
        assertFalse(result.hasWarnings)
    }

    @Test
    fun `flags keep of every class with every member`() {
        val result = linter.lintContent("-keep class * { *; }")

        assertTrue(result.hasWarnings)
    }

    @Test
    fun `flags keepclassmembers on a star package`() {
        val result = linter.lintContent("-keepclassmembers class com.example.** { *; }")

        assertTrue(result.hasWarnings)
    }
}
