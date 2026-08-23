package com.shrinkguard.report

import com.shrinkguard.model.MemberInfo
import com.shrinkguard.model.MemberKind
import com.shrinkguard.model.RuleViolation
import com.shrinkguard.model.ShrinkReport
import com.shrinkguard.model.SurvivalStatus
import java.io.File

class ShrinkReportGenerator {

    fun generateReport(
        libraryName: String,
        publicMembers: List<MemberInfo>,
        allLibraryMembers: List<MemberInfo>,
        survivors: Set<MemberKey>,
        mappings: Map<String, ClassMapping>,
        appliedRules: List<String>,
        violations: List<RuleViolation> = emptyList()
    ): ShrinkReport {
        val analyzer = SurvivalAnalyzer()

        val analysedPublic = analyzer.analyze(publicMembers, survivors, mappings)
        val survivingPublic = analysedPublic.filter { it.survivalStatus == SurvivalStatus.KEPT_UNCHANGED }
        val renamedOrInlinedPublic = analysedPublic.filter { it.survivalStatus != SurvivalStatus.KEPT_UNCHANGED }

        val publicSignatures = publicMembers.map { it.formattedSignature() }.toSet()
        val internalMembers = allLibraryMembers.filter { it.formattedSignature() !in publicSignatures }
        val analysedInternal = analyzer.analyze(internalMembers, survivors, mappings)
        val internalKept = analysedInternal.filter { it.survivalStatus != SurvivalStatus.INLINED_OR_STRIPPED }
        val stripped = analysedInternal.filter { it.survivalStatus == SurvivalStatus.INLINED_OR_STRIPPED }

        return ShrinkReport(
            libraryName = libraryName,
            publicMembersSurviving = survivingPublic.sorted(),
            publicMembersRenamedOrInlined = renamedOrInlinedPublic.sorted(),
            internalMembersKept = internalKept.sorted(),
            deadCodeStripped = stripped.sorted(),
            appliedConsumerRules = appliedRules.map { it.trim() }.filter { it.isNotBlank() },
            ruleViolations = violations
        )
    }

    fun renderReportText(report: ShrinkReport): String {
        val sb = StringBuilder()
        sb.append("# ShrinkGuard R8 Report\n")
        sb.append("# Library: ${report.libraryName}\n\n")

        val totalPublic = report.publicMembersSurviving.size + report.publicMembersRenamedOrInlined.size
        val survivalPercent = if (totalPublic > 0) {
            String.format(java.util.Locale.ROOT, "%.1f", (report.publicMembersSurviving.size.toDouble() / totalPublic) * 100.0)
        } else {
            "100.0"
        }

        sb.append("## Summary\n")
        sb.append("Public API members: $totalPublic\n")
        sb.append("Public API surviving unchanged: ${report.publicMembersSurviving.size} ($survivalPercent%)\n")
        // Renaming is ordinary R8 behaviour. Removal is the outcome that breaks reflection.
        val renamed = report.publicMembersRenamedOrInlined.count { it.survivalStatus == SurvivalStatus.RENAMED }
        val removed = report.publicMembersRenamedOrInlined.count { it.survivalStatus == SurvivalStatus.INLINED_OR_STRIPPED }
        sb.append("Public API renamed: $renamed\n")
        sb.append("Public API removed: $removed\n")
        sb.append("Internal members retained: ${report.internalMembersKept.size}\n")
        sb.append("Dead code members stripped: ${report.deadCodeStripped.size}\n")
        sb.append("Rule violations: ${report.ruleViolations.size}\n\n")

        if (report.ruleViolations.isNotEmpty()) {
            sb.append("## Rule Violations\n")
            for (violation in report.ruleViolations) {
                sb.append(violation.format())
            }
            sb.append("\n")
        }

        sb.append("## Kept Public API Surface\n")
        renderGroupedMembers(report.publicMembersSurviving, sb)
        sb.append("\n")

        if (report.publicMembersRenamedOrInlined.isNotEmpty()) {
            sb.append("## Renamed or Removed Public Members\n")
            for (member in report.publicMembersRenamedOrInlined) {
                val fate = when (member.survivalStatus) {
                    SurvivalStatus.INLINED_OR_STRIPPED -> "<removed>"
                    else -> member.obfuscatedName ?: "<renamed>"
                }
                sb.append("${member.formattedSignature()} -> $fate\n")
            }
            sb.append("\n")
        }

        if (report.internalMembersKept.isNotEmpty()) {
            sb.append("## Internal Members Retained\n")
            renderGroupedMembers(report.internalMembersKept, sb)
            sb.append("\n")
        }

        if (report.appliedConsumerRules.isNotEmpty()) {
            sb.append("## Applied Consumer Rules\n")
            for (rule in report.appliedConsumerRules) {
                sb.append(rule).append("\n")
            }
        }

        return sb.toString()
    }

    private fun renderGroupedMembers(members: List<MemberInfo>, sb: StringBuilder) {
        val grouped = members.groupBy { it.ownerClass }
        for ((className, classMembers) in grouped) {
            val nonClassMembers = classMembers.filter { it.kind != MemberKind.CLASS }
            if (nonClassMembers.isEmpty()) {
                sb.append("class $className\n")
            } else {
                sb.append("class $className {\n")
                for (member in nonClassMembers) {
                    val descriptorStr = if (member.descriptor != null) " ${member.descriptor}" else ""
                    val prefix = when (member.kind) {
                        MemberKind.CONSTRUCTOR -> "constructor"
                        MemberKind.FIELD -> "field"
                        MemberKind.METHOD -> "method"
                        MemberKind.CLASS -> "class"
                    }
                    sb.append("    $prefix ${member.memberName}$descriptorStr\n")
                }
                sb.append("}\n")
            }
        }
    }
}
