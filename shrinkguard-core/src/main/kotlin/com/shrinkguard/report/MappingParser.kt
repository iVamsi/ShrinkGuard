package com.shrinkguard.report

import java.io.File

data class MemberMapping(
    val originalName: String,
    val obfuscatedName: String,
    val returnType: String? = null,
    val isMethod: Boolean = true
)

data class ClassMapping(
    val originalName: String,
    val obfuscatedName: String,
    val members: MutableList<MemberMapping> = mutableListOf()
) {
    val isRenamed: Boolean get() = originalName != obfuscatedName
}

class MappingParser {

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
                // Class line: "com.example.Foo -> a:" or "com.example.Foo -> com.example.Foo:"
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
                // Member line
                val trimmed = rawLine.trim()
                val parts = trimmed.split("->").map { it.trim() }
                if (parts.size == 2 && currentClass != null) {
                    val originalPart = parts[0]
                    val obfuscatedName = parts[1]

                    if (originalPart.contains("(") && originalPart.contains(")")) {
                        // Method: e.g. "1:5:void doWork(java.lang.String)" or "void doWork(int)"
                        val signaturePart = originalPart.substringAfterLast(":")
                        val tokens = signaturePart.trim().split(" ")
                        val returnType = tokens.firstOrNull() ?: ""
                        val methodNameWithArgs = tokens.drop(1).joinToString(" ")
                        val methodName = methodNameWithArgs.substringBefore("(")
                        currentClass.members.add(
                            MemberMapping(
                                originalName = methodName,
                                obfuscatedName = obfuscatedName,
                                returnType = returnType,
                                isMethod = true
                            )
                        )
                    } else {
                        // Field: e.g. "java.lang.String tag"
                        val tokens = originalPart.split(" ")
                        val fieldType = tokens.firstOrNull() ?: ""
                        val fieldName = tokens.drop(1).joinToString(" ")
                        currentClass.members.add(
                            MemberMapping(
                                originalName = fieldName,
                                obfuscatedName = obfuscatedName,
                                returnType = fieldType,
                                isMethod = false
                            )
                        )
                    }
                }
            }
        }
        return result
    }
}
