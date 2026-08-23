package com.shrinkguard.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Fitness semantics: the report must describe what R8 actually did, and removing a keep rule
 * that a reflection target depends on must fail the check.
 */
class ShrinkGuardFitnessTest {

    @TempDir
    lateinit var testProjectDir: File

    private val buildScript = """
        plugins {
            kotlin("jvm") version "2.1.0"
            id("io.github.ivamsi.shrinkguard")
        }

        repositories {
            mavenCentral()
            google()
        }
    """.trimIndent()

    @BeforeEach
    fun setup() {
        File(testProjectDir, "settings.gradle.kts").writeText("""rootProject.name = "test-library"""")
        File(testProjectDir, "build.gradle.kts").writeText(buildScript)

        val srcDir = File(testProjectDir, "src/main/kotlin/com/example/plugin")
        srcDir.mkdirs()
        File(srcDir, "ReflectivePlugin.kt").writeText("""
            package com.example.plugin

            class ReflectivePlugin {
                fun run(): String = "ran"
            }
        """.trimIndent())
        File(srcDir, "PluginLoader.kt").writeText("""
            package com.example.plugin

            class PluginLoader {
                fun load(className: String): Any =
                    Class.forName(className).getDeclaredConstructor().newInstance()
            }
        """.trimIndent())

        File(testProjectDir, "consumer-rules.pro").writeText("""
            -keep class com.example.plugin.ReflectivePlugin {
                <init>();
                public *;
            }
        """.trimIndent())
    }

    private fun runner(vararg args: String) = GradleRunner.create()
        .withProjectDir(testProjectDir)
        .withPluginClasspath()
        .withArguments(*args)

    @Test
    fun `public members that R8 keeps are reported as surviving`() {
        runner("shrinkReport").build()

        val baseline = File(testProjectDir, "shrink-report.txt").readText()

        // PluginLoader.load survives R8, renamed. Reporting it as removed was the original defect.
        val loadLine = baseline.lines().single { it.contains("PluginLoader#load") }
        assertFalse(
            loadLine.contains("<removed>"),
            "load() is present in R8's output but the report says it was removed:\n$baseline"
        )

        // ReflectivePlugin is protected by a keep rule, so its members keep their names.
        assertTrue(
            baseline.substringAfter("## Kept Public API Surface").substringBefore("##").contains("method run"),
            "Expected run() under the kept public API surface:\n$baseline"
        )
    }

    @Test
    fun `removing the keep rule for a reflection target changes the fitness analysis`() {
        runner("shrinkReport").build()
        val withRule = analysisOf(File(testProjectDir, "shrink-report.txt").readText())

        File(testProjectDir, "consumer-rules.pro").delete()
        runner("shrinkReport").build()
        val withoutRule = analysisOf(File(testProjectDir, "shrink-report.txt").readText())

        assertNotEquals(
            withRule,
            withoutRule,
            "Deleting the only keep rule protecting ReflectivePlugin changed nothing in the " +
                "analysis. R8 is free to rename it once the rule is gone, so the report must " +
                "react.\n--- with rule ---\n$withRule\n--- without rule ---\n$withoutRule"
        )
    }

    /** The report minus the section that merely echoes the rules file back. */
    private fun analysisOf(report: String): String =
        report.substringBefore("## Applied Consumer Rules").trim()
}
