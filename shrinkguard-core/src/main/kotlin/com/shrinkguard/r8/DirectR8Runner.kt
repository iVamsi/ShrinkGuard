package com.shrinkguard.r8

import com.android.tools.r8.CompilationFailedException
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.OutputMode
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.origin.Origin
import java.io.File
import java.io.IOException

class DirectR8Runner : R8Runner {

    override fun runR8(request: R8ExecutionRequest): R8ExecutionResult {
        request.outputDir.mkdirs()
        val mappingFile = File(request.outputDir, "mapping.txt")
        val configFile = File(request.outputDir, "configuration.txt")
        val outputJar = File(request.outputDir, "shrunk_output.jar")
        val diagnostics = mutableListOf<String>()

        return try {
            val builder = R8Command.builder()
                .setMode(CompilationMode.RELEASE)
                .setOutput(outputJar.toPath(), OutputMode.ClassFile)
                .setProguardMapOutputPath(mappingFile.toPath())

            for (file in request.programFiles) {
                if (file.isDirectory) {
                    file.walkTopDown()
                        .filter { it.isFile && it.extension == "class" }
                        .forEach { classFile ->
                            builder.addProgramFiles(classFile.toPath())
                        }
                } else if (file.isFile) {
                    builder.addProgramFiles(file.toPath())
                }
            }

            for (file in request.libraryFiles) {
                if (file.isDirectory) {
                    file.walkTopDown()
                        .filter { it.isFile && (it.extension == "class" || it.extension == "jar" || it.extension == "jmod") }
                        .forEach { libFile ->
                            builder.addLibraryFiles(libFile.toPath())
                        }
                } else if (file.isFile) {
                    builder.addLibraryFiles(file.toPath())
                }
            }

            for (ruleFile in request.proguardRuleFiles) {
                if (ruleFile.exists()) {
                    builder.addProguardConfigurationFiles(ruleFile.toPath())
                }
            }

            val inlineRules = mutableListOf<String>()
            inlineRules.addAll(ProguardRules.toLines(request.proguardRules))
            inlineRules.add("-printconfiguration ${configFile.absolutePath}")
            if (request.isFullMode) {
                inlineRules.add("-allowaccessmodification")
            }

            builder.addProguardConfiguration(inlineRules, Origin.unknown())

            val command = builder.build()
            R8.run(command)

            R8ExecutionResult(
                mappingFile = if (mappingFile.exists()) mappingFile else null,
                configurationFile = if (configFile.exists()) configFile else null,
                outputArtifact = if (outputJar.exists()) outputJar else null,
                diagnostics = diagnostics,
                isSuccess = true
            )
        } catch (e: CompilationFailedException) {
            failedResult(e, diagnostics, mappingFile, configFile)
        } catch (e: IOException) {
            failedResult(e, diagnostics, mappingFile, configFile)
        }
    }

    private fun failedResult(
        error: Exception,
        diagnostics: MutableList<String>,
        mappingFile: File,
        configFile: File
    ): R8ExecutionResult {
        diagnostics.add("R8 execution failed: ${error.message}")
        return R8ExecutionResult(
            mappingFile = if (mappingFile.exists()) mappingFile else null,
            configurationFile = if (configFile.exists()) configFile else null,
            outputArtifact = null,
            diagnostics = diagnostics,
            isSuccess = false,
            exception = error
        )
    }
}
