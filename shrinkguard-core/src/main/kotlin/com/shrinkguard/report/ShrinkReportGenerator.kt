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
        mappings: Map<String, ClassMapping>,
        appliedRules: List<String>,
        violations: List<RuleViolation> = emptyList()
    ): ShrinkReport {
        val survivingPublic = mutableListOf<MemberInfo>()
        val renamedOrInlinedPublic = mutableListOf<MemberInfo>()
        val internalKept = mutableListOf<MemberInfo>()
        val stripped = mutableListOf<MemberInfo>()

        val publicSignatures = publicMembers.map { it.formattedSignature() }.toSet()

        for (member in publicMembers) {
            val classMapping = mappings[member.ownerClass]
            if (classMapping != null) {
                if (member.kind == MemberKind.CLASS) {
                    if (!classMapping.isRenamed) {
                        survivingPublic.add(member.copy(survivalStatus = SurvivalStatus.KEPT_UNCHANGED))
                    } else {
                        renamedOrInlinedPublic.add(
                            member.copy(
                                survivalStatus = SurvivalStatus.RENAMED,
                                obfuscatedName = classMapping.obfuscatedName
                            )
                        )
                    }
                } else {
                    val matchingMember = classMapping.members.find { it.originalName == member.memberName }
                    if (matchingMember != null) {
                        if (matchingMember.originalName == matchingMember.obfuscatedName && !classMapping.isRenamed) {
                            survivingPublic.add(member.copy(survivalStatus = SurvivalStatus.KEPT_UNCHANGED))
                        } else {
                            renamedOrInlinedPublic.add(
                                member.copy(
                                    survivalStatus = SurvivalStatus.RENAMED,
                                    obfuscatedName = matchingMember.obfuscatedName
                                )
                            )
                        }
                    } else {
                        renamedOrInlinedPublic.add(
                            member.copy(
                                survivalStatus = SurvivalStatus.INLINED_OR_STRIPPED,
                                obfuscatedName = "<inlined/stripped>"
                            )
                        )
                    }
                }
            } else {
                renamedOrInlinedPublic.add(
                    member.copy(
                        survivalStatus = SurvivalStatus.INLINED_OR_STRIPPED,
                        obfuscatedName = "<inlined/stripped>"
                    )
                )
            }
        }

        // Internal members check
        val internalMembers = allLibraryMembers.filter { it.formattedSignature() !in publicSignatures }
        for (member in internalMembers) {
            val classMapping = mappings[member.ownerClass]
            if (classMapping != null) {
                if (member.kind == MemberKind.CLASS) {
                    internalKept.add(
                        member.copy(
                            survivalStatus = SurvivalStatus.KEPT_BY_CONSUMER_RULE,
                            obfuscatedName = classMapping.obfuscatedName
                        )
                    )
                } else {
                    val matchingMember = classMapping.members.find { it.originalName == member.memberName }
                    if (matchingMember != null) {
                        internalKept.add(
                            member.copy(
                                survivalStatus = SurvivalStatus.KEPT_BY_CONSUMER_RULE,
                                obfuscatedName = matchingMember.obfuscatedName
                            )
                        )
                    } else {
                        stripped.add(member.copy(survivalStatus = SurvivalStatus.INLINED_OR_STRIPPED))
                    }
                }
            } else {
                stripped.add(member.copy(survivalStatus = SurvivalStatus.INLINED_OR_STRIPPED))
            }
        }

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
            String.format("%.1f", (report.publicMembersSurviving.size.toDouble() / totalPublic) * 100.0)
        } else {
            "100.0"
        }

        sb.append("## Summary\n")
        sb.append("Public API members: $totalPublic\n")
        sb.append("Public API surviving unchanged: ${report.publicMembersSurviving.size} ($survivalPercent%)\n")
        sb.append("Public API renamed/inlined: ${report.publicMembersRenamedOrInlined.size}\n")
        sb.append("Internal members kept by consumer rules: ${report.internalMembersKept.size}\n")
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
            sb.append("## Renamed / Inlined Public Members\n")
            for (member in report.publicMembersRenamedOrInlined) {
                sb.append("${member.formattedSignature()} -> ${member.obfuscatedName ?: "<inlined>"}\n")
            }
            sb.append("\n")
        }

        if (report.internalMembersKept.isNotEmpty()) {
            sb.append("## Internal Members Kept by Consumer Rules\n")
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
