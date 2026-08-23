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
}
