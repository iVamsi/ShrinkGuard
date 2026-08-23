package com.shrinkguard.r8

import com.android.tools.r8.CompilationMode
import com.android.tools.r8.OutputMode
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.origin.Origin
import java.io.File

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

            // Add program files (library bytecode + synthetic consumer)
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

            // Add library/classpath files (JDK jmods, Android SDK android.jar, upstream dependencies)
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

            // Add ProGuard rule files
            for (ruleFile in request.proguardRuleFiles) {
                if (ruleFile.exists()) {
                    builder.addProguardConfigurationFiles(ruleFile.toPath())
                }
            }

            // Add inline ProGuard rules
            val inlineRules = mutableListOf<String>()
            inlineRules.addAll(request.proguardRules)
            inlineRules.add("-printconfiguration ${configFile.absolutePath}")
            // Add suppress warnings for non-fatal unresolved library references during fitness check
            inlineRules.add("-dontwarn")

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
        } catch (t: Throwable) {
            diagnostics.add("R8 execution failed: ${t.message}")
            R8ExecutionResult(
                mappingFile = if (mappingFile.exists()) mappingFile else null,
                configurationFile = if (configFile.exists()) configFile else null,
                outputArtifact = null,
                diagnostics = diagnostics,
                isSuccess = false,
                exception = t
            )
        }
    }
}
