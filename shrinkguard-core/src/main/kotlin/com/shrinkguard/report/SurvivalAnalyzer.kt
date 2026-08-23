package com.shrinkguard.report

import com.shrinkguard.model.MemberInfo
import com.shrinkguard.model.MemberKind
import com.shrinkguard.model.SurvivalStatus

/** Identity of a class or member as it appears in a compiled artifact. */
data class MemberKey(
    val ownerClass: String,
    val memberName: String,
    val descriptor: String?
)

fun MemberInfo.toKey(): MemberKey = MemberKey(ownerClass, memberName, descriptor)

/**
 * Decides what R8 did to each declared member by looking it up in R8's output, which is the only
 * artifact that actually says what survived. The mapping file supplies new names, not survival:
 * R8 emits member lines for renamed members, so treating an absent line as a removal reports
 * every kept-and-unrenamed member as stripped.
 */
class SurvivalAnalyzer {

    fun analyze(
        declared: List<MemberInfo>,
        survivors: Set<MemberKey>,
        mappings: Map<String, ClassMapping>
    ): List<MemberInfo> {
        val classRenames = mappings.values
            .filter { it.isRenamed }
            .associate { it.originalName to it.obfuscatedName }

        return declared.map { member -> analyzeMember(member, survivors, mappings, classRenames) }
    }

    private fun analyzeMember(
        member: MemberInfo,
        survivors: Set<MemberKey>,
        mappings: Map<String, ClassMapping>,
        classRenames: Map<String, String>
    ): MemberInfo {
        val classMapping = mappings[member.ownerClass]
        val mappedOwner = classMapping?.obfuscatedName ?: member.ownerClass

        if (member.kind == MemberKind.CLASS) {
            val present = MemberKey(mappedOwner, member.memberName, null) in survivors
            return when {
                !present -> member.copy(survivalStatus = SurvivalStatus.INLINED_OR_STRIPPED)
                mappedOwner != member.ownerClass -> member.copy(
                    survivalStatus = SurvivalStatus.RENAMED,
                    obfuscatedName = mappedOwner
                )
                else -> member.copy(survivalStatus = SurvivalStatus.KEPT_UNCHANGED)
            }
        }

        val memberRename = classMapping?.members
            ?.firstOrNull { mapping ->
                mapping.originalName == member.memberName &&
                    (mapping.descriptor == null || mapping.descriptor == member.descriptor)
            }
            ?.obfuscatedName
        val mappedName = memberRename ?: member.memberName
        val mappedDescriptor = member.descriptor?.let { remapDescriptor(it, classRenames) }

        val present = MemberKey(mappedOwner, mappedName, mappedDescriptor) in survivors
        val renamed = mappedName != member.memberName || mappedOwner != member.ownerClass

        return when {
            !present -> member.copy(survivalStatus = SurvivalStatus.INLINED_OR_STRIPPED)
            renamed -> member.copy(
                survivalStatus = SurvivalStatus.RENAMED,
                obfuscatedName = if (memberRename != null) mappedName else mappedOwner
            )
            else -> member.copy(survivalStatus = SurvivalStatus.KEPT_UNCHANGED)
        }
    }

    /**
     * Rewrites object types inside a descriptor using the class renames, so that a member whose
     * signature mentions a renamed type can still be matched in the output.
     */
    private fun remapDescriptor(descriptor: String, classRenames: Map<String, String>): String {
        if (classRenames.isEmpty() || 'L' !in descriptor) return descriptor

        val out = StringBuilder(descriptor.length)
        var index = 0
        while (index < descriptor.length) {
            val char = descriptor[index]
            if (char != 'L') {
                out.append(char)
                index++
                continue
            }
            val end = descriptor.indexOf(';', index)
            if (end == -1) {
                out.append(descriptor, index, descriptor.length)
                break
            }
            val internalName = descriptor.substring(index + 1, end)
            val dotted = internalName.replace('/', '.')
            val mapped = classRenames[dotted]?.replace('.', '/') ?: internalName
            out.append('L').append(mapped).append(';')
            index = end + 1
        }
        return out.toString()
    }
}
