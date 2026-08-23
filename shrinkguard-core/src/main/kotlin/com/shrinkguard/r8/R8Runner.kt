package com.shrinkguard.r8

import java.io.File

data class R8ExecutionRequest(
    val programFiles: List<File>,
    val libraryFiles: List<File>,
    val proguardRules: List<String> = emptyList(),
    val proguardRuleFiles: List<File> = emptyList(),
    val outputDir: File,
    val isFullMode: Boolean = true
)

data class R8ExecutionResult(
    val mappingFile: File?,
    val configurationFile: File?,
    val outputArtifact: File?,
    val diagnostics: List<String>,
    val isSuccess: Boolean,
    val exception: Throwable? = null
)

interface R8Runner {
    fun runR8(request: R8ExecutionRequest): R8ExecutionResult
}
