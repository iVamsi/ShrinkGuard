package com.shrinkguard.r8

import com.android.tools.r8.CompilationFailedException
import com.android.tools.r8.CompilationMode
import com.android.tools.r8.DirectoryClassFileProvider
import com.android.tools.r8.OutputMode
import com.android.tools.r8.R8
import com.android.tools.r8.R8Command
import com.android.tools.r8.origin.Origin
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

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

            val androidPlatform = usesAndroidBootClasspath(request.libraryFiles)
            if (androidPlatform) {
                builder.setAndroidPlatformBuild(true)
                // Class-file output (used for survival analysis) cannot take --min-api, so Kotlin's
                // invokedynamic string concat is satisfied via java.base on the library classpath
                // alongside android.jar (see LibraryArtifactResolver.resolveClasspath).
            }

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

            val programPaths = request.programFiles.map { it.canonicalPath }.toSet()
            val aarExtractDir = File(request.outputDir, "aar-libs")
            val jmodExtractDir = File(request.outputDir, "jmod-libs")
            // Keep jdk-base-stubs.jar last so android.jar and library dependencies take precedence
            // for shared platform types while JDK stubs supply StringConcatFactory, etc.
            val orderedLibraryFiles = request.libraryFiles
                .filter { it.canonicalPath !in programPaths }
                .distinctBy { it.canonicalPath }
                .sortedBy { if (it.name == "jdk-base-stubs.jar") 1 else 0 }

            for (file in orderedLibraryFiles) {
                if (file.isDirectory) {
                    val hasClassFiles = file.walkTopDown().any { it.isFile && it.extension.equals("class", ignoreCase = true) }
                    if (hasClassFiles) {
                        builder.addLibraryResourceProvider(DirectoryClassFileProvider.fromDirectory(file.toPath()))
                    }
                    file.walkTopDown()
                        .filter { it.isFile && !it.extension.equals("class", ignoreCase = true) }
                        .forEach { libFile ->
                            addSupportedLibraryFile(builder, libFile, aarExtractDir, jmodExtractDir)
                        }
                } else if (file.isFile) {
                    addSupportedLibraryFile(builder, file, aarExtractDir, jmodExtractDir)
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

    private fun usesAndroidBootClasspath(libraryFiles: List<File>): Boolean =
        libraryFiles.any { it.isFile && it.name == "android.jar" }

    private fun addSupportedLibraryFile(
        builder: R8Command.Builder,
        file: File,
        aarExtractDir: File,
        jmodExtractDir: File,
    ) {
        when (file.extension.lowercase()) {
            "jar", "zip" -> if (file.length() > 0L) {
                builder.addLibraryFiles(file.toPath())
            }
            "aar" -> {
                for (jar in extractClassesFromAar(file, aarExtractDir)) {
                    builder.addLibraryFiles(jar.toPath())
                }
            }
            "jmod" -> {
                jmodExtractDir.mkdirs()
                val outJar = File(jmodExtractDir, "${file.nameWithoutExtension}.jar")
                val extracted = JdkJarExtractor.extractJmodToJar(file, outJar)
                if (extracted.length() > 0L) {
                    builder.addLibraryFiles(extracted.toPath())
                }
            }
        }
    }

    private fun extractClassesFromAar(aarFile: File, extractDir: File): List<File> {
        extractDir.mkdirs()
        val extracted = mutableListOf<File>()
        val hash = aarFile.absolutePath.hashCode().toUInt().toString(16)
        val prefix = "${aarFile.nameWithoutExtension}-$hash"
        ZipFile(aarFile).use { zip ->
            for (entry in zip.entries().asSequence()) {
                val isClassesJar = entry.name == "classes.jar"
                val isBundledLibJar = entry.name.startsWith("libs/") && entry.name.endsWith(".jar")
                if (!entry.isDirectory && (isClassesJar || isBundledLibJar)) {
                    val safeName = entry.name.replace('/', '_')
                    val outJar = File(extractDir, "$prefix-$safeName")
                    if (!outJar.exists() || outJar.length() == 0L) {
                        zip.getInputStream(entry).use { input ->
                            outJar.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                    }
                    if (outJar.length() > 0L) {
                        extracted.add(outJar)
                    }
                }
            }
        }
        return extracted
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
