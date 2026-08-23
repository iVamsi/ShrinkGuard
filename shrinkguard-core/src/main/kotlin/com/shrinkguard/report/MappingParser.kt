package com.shrinkguard.report

import java.io.File

data class MemberMapping(
    val originalName: String,
    val obfuscatedName: String,
    val returnType: String? = null,
    val isMethod: Boolean = true,
    val descriptor: String? = null
)

data class ClassMapping(
    val originalName: String,
    val obfuscatedName: String,
    val members: MutableList<MemberMapping> = mutableListOf()
) {
    val isRenamed: Boolean get() = originalName != obfuscatedName
}

class MappingParser {

    private companion object {
        val LINE_RANGE_PREFIX = Regex("""^\d+:\d+:""")
    }

    fun parse(mappingFile: File): Map<String, ClassMapping> {
        if (!mappingFile.exists()) return emptyMap()
        return parseContent(mappingFile.readLines())
    }

    fun parseContent(lines: List<String>): Map<String, ClassMapping> {
        val result = LinkedHashMap<String, ClassMapping>()
        var currentClass: ClassMapping? = null

        for (rawLine in lines) {
            if (rawLine.startsWith("#") || rawLine.isBlank()) continue

            if (!rawLine.startsWith(" ")) {
                val colonIdx = rawLine.indexOf(':')
                val cleanLine = if (colonIdx != -1) rawLine.substring(0, colonIdx) else rawLine
                val parts = cleanLine.split("->").map { it.trim() }
                if (parts.size == 2) {
                    val original = parts[0]
                    val obfuscated = parts[1]
                    val mapping = ClassMapping(original, obfuscated)
                    result[original] = mapping
                    currentClass = mapping
                }
            } else {
                val trimmed = rawLine.trim()
                val parts = trimmed.split("->").map { it.trim() }
                if (parts.size == 2 && currentClass != null) {
                    val originalPart = parts[0]
                    val obfuscatedName = parts[1]

                    if (originalPart.contains("(") && originalPart.contains(")")) {
                        val withoutLineRange = originalPart.replaceFirst(LINE_RANGE_PREFIX, "")
                        val signaturePart = withoutLineRange.substringBeforeLast(")") + ")"
                        val tokens = signaturePart.trim().split(" ", limit = 2)
                        val returnType = tokens.firstOrNull() ?: ""
                        val afterReturn = tokens.getOrElse(1) { "" }
                        val methodName = afterReturn.substringBefore("(")
                        if (methodName.isEmpty()) continue
                        val argsPart = afterReturn.substringAfter("(", missingDelimiterValue = "")
                            .substringBeforeLast(")")
                        currentClass.members.add(
                            MemberMapping(
                                originalName = methodName,
                                obfuscatedName = obfuscatedName,
                                returnType = returnType,
                                isMethod = true,
                                descriptor = toJvmMethodDescriptor(returnType, argsPart)
                            )
                        )
                    } else {
                        val tokens = originalPart.split(" ")
                        val fieldType = tokens.firstOrNull() ?: ""
                        val fieldName = tokens.drop(1).joinToString(" ")
                        currentClass.members.add(
                            MemberMapping(
                                originalName = fieldName,
                                obfuscatedName = obfuscatedName,
                                returnType = fieldType,
                                isMethod = false,
                                descriptor = toJvmTypeDescriptor(fieldType)
                            )
                        )
                    }
                }
            }
        }
        return result
    }

    private fun toJvmMethodDescriptor(returnType: String, argsPart: String): String {
        val args = if (argsPart.isBlank()) {
            emptyList()
        } else {
            argsPart.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }
        return buildString {
            append('(')
            args.forEach { append(toJvmTypeDescriptor(it)) }
            append(')')
            append(toJvmTypeDescriptor(returnType))
        }
    }

    private fun toJvmTypeDescriptor(proguardType: String): String {
        var type = proguardType.trim()
        var arrayDims = 0
        while (type.endsWith("[]")) {
            arrayDims++
            type = type.removeSuffix("[]").trim()
        }
        val base = when (type) {
            "void" -> "V"
            "boolean" -> "Z"
            "byte" -> "B"
            "char" -> "C"
            "short" -> "S"
            "int" -> "I"
            "long" -> "J"
            "float" -> "F"
            "double" -> "D"
            else -> "L${type.replace('.', '/')};"
        }
        return "[".repeat(arrayDims) + base
    }
}
