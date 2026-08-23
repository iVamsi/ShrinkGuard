package com.shrinkguard.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ShrinkGuardPluginTest {

    @TempDir
    lateinit var testProjectDir: File

    private lateinit var buildFile: File
    private lateinit var settingsFile: File

    @BeforeEach
    fun setup() {
        settingsFile = File(testProjectDir, "settings.gradle.kts")
        settingsFile.writeText("""
            rootProject.name = "test-library"
        """.trimIndent())

        buildFile = File(testProjectDir, "build.gradle.kts")
    }

    @Test
    fun `shrinkReport task creates baseline and shrinkCheck passes`() {
        buildFile.writeText("""
            plugins {
                kotlin("jvm") version "2.1.0"
                id("io.github.ivamsi.shrinkguard")
            }

            repositories {
                mavenCentral()
                google()
            }
        """.trimIndent())

        val srcDir = File(testProjectDir, "src/main/kotlin/com/example")
        srcDir.mkdirs()
        File(srcDir, "Calculator.kt").writeText("""
            package com.example

            class Calculator {
                fun add(a: Int, b: Int): Int = a + b
            }
        """.trimIndent())

        // Run shrinkReport
        val reportResult = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments("shrinkReport", "--stacktrace")
            .build()

        assertEquals(TaskOutcome.SUCCESS, reportResult.task(":shrinkReport")?.outcome)

        val baseline = File(testProjectDir, "shrink-report.txt")
        assertTrue(baseline.exists(), "shrink-report.txt should be generated")
        assertTrue(baseline.readText().contains("Calculator"))

        // Run shrinkCheck
        val checkResult = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments("shrinkCheck")
            .build()

        assertEquals(TaskOutcome.SUCCESS, checkResult.task(":shrinkCheck")?.outcome)
    }

    @Test
    fun `shrinkCheck fails when toxic dontobfuscate rule is in consumer rules`() {
        buildFile.writeText("""
            plugins {
                kotlin("jvm") version "2.1.0"
                id("io.github.ivamsi.shrinkguard")
            }

            repositories {
                mavenCentral()
                google()
            }
        """.trimIndent())

        val srcDir = File(testProjectDir, "src/main/kotlin/com/example")
        srcDir.mkdirs()
        File(srcDir, "Greeter.kt").writeText("""
            package com.example

            class Greeter {
                fun greet(): String = "Hello"
            }
        """.trimIndent())

        val consumerRules = File(testProjectDir, "consumer-rules.pro")
        consumerRules.writeText("""
            # Toxic rule
            -dontobfuscate
        """.trimIndent())

        val result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments("shrinkCheck", "--info")
            .buildAndFail()

        assertTrue(
            result.output.contains("toxic ProGuard/R8 directives") ||
            result.output.contains("-dontobfuscate") ||
            result.output.contains("Forbidden global directive"),
            "Expected toxic rule failure but got:\n${result.output}"
        )
    }

    @Test
    fun `shrinkCheck detects baseline drift when public API is modified`() {
        buildFile.writeText("""
            plugins {
                kotlin("jvm") version "2.1.0"
                id("io.github.ivamsi.shrinkguard")
            }

            repositories {
                mavenCentral()
                google()
            }
        """.trimIndent())

        val srcDir = File(testProjectDir, "src/main/kotlin/com/example")
        srcDir.mkdirs()
        val sourceFile = File(srcDir, "Service.kt")
        sourceFile.writeText("""
            package com.example

            class Service {
                fun execute(): String = "v1"
            }
        """.trimIndent())

        // 1. Generate initial baseline
        GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments("shrinkReport")
            .build()

        // 2. Add new method to public API without regenerating baseline
        sourceFile.writeText("""
            package com.example

            class Service {
                fun execute(): String = "v1"
                fun executeAsync(): String = "v2"
            }
        """.trimIndent())

        // 3. shrinkCheck should fail with drift diff
        val driftResult = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments("shrinkCheck")
            .buildAndFail()

        assertTrue(
            driftResult.output.contains("report drifted from committed baseline") ||
            driftResult.output.contains("Diff between baseline and actual R8 output"),
            "Expected drift error but got:\n${driftResult.output}"
        )

        // 4. Running shrinkReport updates baseline, then shrinkCheck succeeds
        GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments("shrinkReport")
            .build()

        val checkAfterUpdate = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withPluginClasspath()
            .withArguments("shrinkCheck")
            .build()

        assertEquals(TaskOutcome.SUCCESS, checkAfterUpdate.task(":shrinkCheck")?.outcome)
    }
}
