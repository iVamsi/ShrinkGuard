package com.shrinkguard.model

enum class MemberKind {
    CLASS,
    METHOD,
    FIELD,
    CONSTRUCTOR
}

enum class SurvivalStatus {
    KEPT_UNCHANGED,
    RENAMED,
    INLINED_OR_STRIPPED,
    KEPT_BY_CONSUMER_RULE
}

data class MemberInfo(
    val ownerClass: String,
    val memberName: String,
    val descriptor: String? = null,
    val kind: MemberKind = MemberKind.METHOD,
    val accessFlags: Int = 0,
    val survivalStatus: SurvivalStatus = SurvivalStatus.KEPT_UNCHANGED,
    val obfuscatedName: String? = null
) : Comparable<MemberInfo> {
    override fun compareTo(other: MemberInfo): Int {
        val classCmp = ownerClass.compareTo(other.ownerClass)
        if (classCmp != 0) return classCmp
        val nameCmp = memberName.compareTo(other.memberName)
        if (nameCmp != 0) return nameCmp
        return (descriptor ?: "").compareTo(other.descriptor ?: "")
    }

    fun formattedSignature(): String {
        return when (kind) {
            MemberKind.CLASS -> ownerClass
            MemberKind.FIELD -> "$ownerClass -> $memberName${if (descriptor != null) ": $descriptor" else ""}"
            MemberKind.METHOD, MemberKind.CONSTRUCTOR -> "$ownerClass#$memberName${descriptor ?: "()"}"
        }
    }
}

data class ShrinkReport(
    val libraryName: String,
    val publicMembersSurviving: List<MemberInfo>,
    val publicMembersRenamedOrInlined: List<MemberInfo>,
    val internalMembersKept: List<MemberInfo>,
    val deadCodeStripped: List<MemberInfo>,
    val appliedConsumerRules: List<String>,
    val ruleViolations: List<RuleViolation> = emptyList()
)
