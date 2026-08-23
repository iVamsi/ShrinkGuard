package com.shrinkguard

import com.shrinkguard.api.PublicApiExtractor
import com.shrinkguard.consumer.SyntheticConsumerGenerator
import com.shrinkguard.model.MemberInfo
import com.shrinkguard.model.RuleViolation
import com.shrinkguard.r8.DirectR8Runner
import com.shrinkguard.r8.R8ExecutionRequest
import com.shrinkguard.r8.R8Runner
import com.shrinkguard.report.MappingParser
import com.shrinkguard.report.ShrinkReportGenerator
import com.shrinkguard.report.toKey
import java.io.File

class ShrinkAnalysisException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Runs the fitness pipeline: read the library's API, build a synthetic consumer that uses it, run
 * R8, then compare the shrunk output against what went in. Both tasks share this so the baseline
 * and the check can never be produced by different code.
 */
class ShrinkAnalysis(private val runner: R8Runner = DirectR8Runner()) {

    fun analyze(
        libraryName: String,
        classDirectories: List<File>,
        classpath: List<File>,
        rulesFiles: List<File>,
        workingDir: File,
        appliedRules: List<String>,
        violations: List<RuleViolation>
    ): String {
        workingDir.mkdirs()

        val extractor = PublicApiExtractor()
        val publicMembers = mutableListOf<MemberInfo>()
        val allMembers = mutableListOf<MemberInfo>()
        for (dir in classDirectories.filter { it.exists() }) {
            val extracted = extractor.extract(dir)
            publicMembers.addAll(extracted.publicMembers)
            allMembers.addAll(extracted.allMembers)
        }

        if (allMembers.isEmpty()) {
            throw ShrinkAnalysisException(
                "ShrinkGuard found no compiled classes to analyse in:\n" +
                    classDirectories.joinToString("\n") { "  $it" } +
                    "\nAn empty analysis would report every member as stripped, so this is treated " +
                    "as a failure. Check that the library compiled before this task ran."
            )
        }

        val consumerGenerator = SyntheticConsumerGenerator()
        val syntheticJar = File(workingDir, "synthetic-consumer.jar")
        consumerGenerator.generateConsumerJar(publicMembers, syntheticJar)

        val r8Result = runner.runR8(
            R8ExecutionRequest(
                programFiles = classDirectories.filter { it.exists() } + listOf(syntheticJar),
                libraryFiles = classpath.filter { it.exists() },
                // Only the synthetic entry point is kept. Everything else has to earn its place,
                // either by being reachable from it or by a rule the library ships.
                proguardRules = listOf(
                    consumerGenerator.generateConsumerKeepRules(),
                    // Measure shrinking and obfuscation, which keep rules govern. Inlining is not
                    // something a library's consumer rules can influence, and a single synthetic
                    // call site makes every method look inlinable, which is noise in the report.
                    "-dontoptimize"
                ),
                proguardRuleFiles = rulesFiles,
                outputDir = File(workingDir, "r8-out"),
                isFullMode = true
            )
        )

        val outputArtifact = r8Result.outputArtifact
        if (!r8Result.isSuccess || outputArtifact == null) {
            throw ShrinkAnalysisException(
                "ShrinkGuard R8 execution failed:\n" + r8Result.diagnostics.joinToString("\n"),
                r8Result.exception
            )
        }

        val survivors = extractor.extract(outputArtifact).allMembers.map { it.toKey() }.toSet()
        val mappings = r8Result.mappingFile?.let { MappingParser().parse(it) } ?: emptyMap()

        val report = ShrinkReportGenerator().generateReport(
            libraryName = libraryName,
            publicMembers = publicMembers,
            allLibraryMembers = allMembers,
            survivors = survivors,
            mappings = mappings,
            appliedRules = appliedRules,
            violations = violations
        )
        return ShrinkReportGenerator().renderReportText(report)
    }
}
