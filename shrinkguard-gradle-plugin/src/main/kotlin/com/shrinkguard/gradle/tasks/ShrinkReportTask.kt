package com.shrinkguard.gradle.tasks

import com.shrinkguard.ShrinkAnalysis
import com.shrinkguard.ShrinkAnalysisException
import com.shrinkguard.linter.ConsumerRulesLinter
import com.shrinkguard.linter.RuleLintConfig
import com.shrinkguard.model.RuleSeverity
import com.shrinkguard.model.RuleViolation
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class ShrinkReportTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classDirectories: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val classpath: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rulesFiles: ConfigurableFileCollection

    @get:Input
    abstract val failOnToxicFlags: Property<Boolean>

    @get:Input
    abstract val failOnOverbroadRules: Property<Boolean>

    @get:Input
    abstract val allowlistRules: SetProperty<String>

    @get:Input
    abstract val libraryName: Property<String>

    @get:Internal
    abstract val workingDirectory: DirectoryProperty

    @get:OutputFile
    abstract val baselineFile: RegularFileProperty

    init {
        group = "verification"
        description = "Generates or updates the ShrinkGuard R8 fitness report baseline."
    }

    @TaskAction
    fun run() {
        val rulesList = rulesFiles.files.filter { it.exists() }
        val linter = ConsumerRulesLinter(
            RuleLintConfig(
                failOnToxicFlags = failOnToxicFlags.get(),
                failOnOverbroadRules = failOnOverbroadRules.get(),
                allowlistRules = allowlistRules.get()
            )
        )

        val violations = mutableListOf<RuleViolation>()
        val appliedRules = mutableListOf<String>()
        for (ruleFile in rulesList) {
            violations.addAll(linter.lintFile(ruleFile).violations)
            appliedRules.addAll(ruleFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") })
        }

        val toxicErrors = violations.filter { it.severity == RuleSeverity.ERROR }
        if (toxicErrors.isNotEmpty() && failOnToxicFlags.get()) {
            throw GradleException(
                buildString {
                    appendLine("ShrinkGuard found toxic ProGuard/R8 directives in consumer rules:")
                    for (error in toxicErrors) appendLine(error.format())
                }
            )
        }

        val reportText = try {
            ShrinkAnalysis().analyze(
                libraryName = libraryName.get(),
                classDirectories = classDirectories.files.toList(),
                classpath = classpath.files.toList(),
                rulesFiles = rulesList,
                workingDir = workingDirectory.get().asFile,
                appliedRules = appliedRules,
                violations = violations
            )
        } catch (e: ShrinkAnalysisException) {
            throw GradleException(e.message ?: "ShrinkGuard analysis failed", e.cause)
        }

        val targetBaseline = baselineFile.get().asFile
        targetBaseline.parentFile?.mkdirs()
        targetBaseline.writeText(reportText)

        logger.lifecycle("ShrinkGuard: Baseline report updated at ${targetBaseline.absolutePath}")
    }
}
