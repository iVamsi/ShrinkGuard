package com.shrinkguard.gradle.tasks

import com.shrinkguard.api.PublicApiExtractor
import com.shrinkguard.consumer.SyntheticConsumerGenerator
import com.shrinkguard.linter.ConsumerRulesLinter
import com.shrinkguard.linter.RuleLintConfig
import com.shrinkguard.model.MemberInfo
import com.shrinkguard.model.RuleSeverity
import com.shrinkguard.r8.DirectR8Runner
import com.shrinkguard.r8.R8ExecutionRequest
import com.shrinkguard.report.MappingParser
import com.shrinkguard.report.ShrinkReportGenerator
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

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

    @get:OutputFile
    abstract val baselineFile: RegularFileProperty

    init {
        group = "verification"
        description = "Generates or updates the ShrinkGuard R8 fitness report baseline."
    }

    @TaskAction
    fun run() {
        val workingDir = File(project.layout.buildDirectory.asFile.get(), "shrinkguard/work")
        workingDir.mkdirs()

        val rulesList = rulesFiles.files.filter { it.exists() }
        val linter = ConsumerRulesLinter(
            RuleLintConfig(
                failOnToxicFlags = failOnToxicFlags.get(),
                failOnOverbroadRules = failOnOverbroadRules.get(),
                allowlistRules = allowlistRules.get()
            )
        )

        val violations = mutableListOf<com.shrinkguard.model.RuleViolation>()
        val appliedRules = mutableListOf<String>()
        for (ruleFile in rulesList) {
            val result = linter.lintFile(ruleFile)
            violations.addAll(result.violations)
            appliedRules.addAll(ruleFile.readLines().filter { it.isNotBlank() && !it.startsWith("#") })
        }

        val toxicErrors = violations.filter { it.severity == RuleSeverity.ERROR }
        if (toxicErrors.isNotEmpty() && failOnToxicFlags.get()) {
            val errorMsg = buildString {
                appendLine("ShrinkGuard found toxic ProGuard/R8 directives in consumer rules:")
                for (err in toxicErrors) {
                    appendLine(err.format())
                }
            }
            throw GradleException(errorMsg)
        }

        // 1. Extract Public API and all classes
        val extractor = PublicApiExtractor()
        val publicMembers = mutableListOf<MemberInfo>()
        val allMembers = mutableListOf<MemberInfo>()

        for (dir in classDirectories.files) {
            if (dir.exists()) {
                val extracted = extractor.extract(dir)
                publicMembers.addAll(extracted.publicMembers)
                allMembers.addAll(extracted.allMembers)
            }
        }

        // 2. Synthesize Consumer
        val consumerGen = SyntheticConsumerGenerator()
        val synthJar = File(workingDir, "synthetic-consumer.jar")
        consumerGen.generateConsumerJar(publicMembers, synthJar)

        val synthRules = buildString {
            appendLine(consumerGen.generateConsumerKeepRules())
            appendLine(consumerGen.generateSyntheticKeepRulesForPublicApi(publicMembers))
        }

        // 3. Invoke R8
        val r8Runner = DirectR8Runner()
        val r8OutputDir = File(workingDir, "r8-out")
        val programInputs = classDirectories.files.filter { it.exists() } + listOf(synthJar)
        val libraryInputs = classpath.files.filter { it.exists() }

        val r8Result = r8Runner.runR8(
            R8ExecutionRequest(
                programFiles = programInputs,
                libraryFiles = libraryInputs,
                proguardRules = listOf(synthRules),
                proguardRuleFiles = rulesList,
                outputDir = r8OutputDir,
                isFullMode = true
            )
        )

        val mappingFile = r8Result.mappingFile
        if (!r8Result.isSuccess || mappingFile == null) {
            val diag = r8Result.diagnostics.joinToString("\n")
            throw GradleException("ShrinkGuard R8 execution failed:\n$diag", r8Result.exception)
        }

        // 4. Parse mapping & generate report
        val mappingParser = MappingParser()
        val mappings = mappingParser.parse(mappingFile)

        val reportGen = ShrinkReportGenerator()
        val report = reportGen.generateReport(
            libraryName = project.name,
            publicMembers = publicMembers,
            allLibraryMembers = allMembers,
            mappings = mappings,
            appliedRules = appliedRules,
            violations = violations
        )

        val reportText = reportGen.renderReportText(report)
        val targetBaseline = baselineFile.get().asFile
        targetBaseline.parentFile?.mkdirs()
        targetBaseline.writeText(reportText)

        logger.lifecycle("ShrinkGuard: Baseline report updated at ${targetBaseline.absolutePath}")
    }
}
