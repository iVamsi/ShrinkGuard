package com.shrinkguard.gradle.internal

import com.shrinkguard.r8.JdkJarExtractor
import org.gradle.api.Project
import java.io.File
import java.util.Properties

object LibraryArtifactResolver {

    fun findConsumerRules(project: Project): List<File> {
        val candidates = mutableListOf(
            project.file("consumer-rules.pro"),
            project.file("src/main/resources/META-INF/proguard/rules.pro"),
            project.file("src/main/resources/META-INF/proguard/${project.name}.pro"),
        )

        val metaInfDirs = listOf(
            project.file("src/main/resources/META-INF/proguard"),
            project.file("src/jvmMain/resources/META-INF/proguard"),
            project.file("src/commonMain/resources/META-INF/proguard"),
        )
        for (dir in metaInfDirs) {
            if (dir.isDirectory) {
                dir.listFiles()
                    ?.filter { it.isFile && it.extension == "pro" }
                    ?.sortedBy { it.name }
                    ?.let { candidates.addAll(it) }
            }
        }

        val androidExt = project.extensions.findByName("android")
        if (androidExt != null) {
            runCatching {
                val defaultConfig = androidExt.javaClass.methods
                    .firstOrNull { it.name == "getDefaultConfig" && it.parameterCount == 0 }
                    ?.invoke(androidExt)
                val consumerFiles = defaultConfig?.javaClass?.methods
                    ?.firstOrNull { it.name == "getConsumerProguardFiles" && it.parameterCount == 0 }
                    ?.invoke(defaultConfig) as? Collection<*>
                consumerFiles?.filterIsInstance<File>()?.let { candidates.addAll(it) }
            }
        }

        return candidates.distinct().filter { it.exists() && it.isFile }
    }

    fun findClassDirectories(project: Project): List<File> {
        val dirs = mutableListOf<File>()
        val buildDir = project.layout.buildDirectory.asFile.get()

        // JVM & KMP JVM output dirs
        val jvmDirs = listOf(
            File(buildDir, "classes/kotlin/main"),
            File(buildDir, "classes/java/main"),
            File(buildDir, "classes/kotlin/jvm/main"),
            File(buildDir, "classes/java/jvm/main"),
        )
        for (dir in jvmDirs) {
            if (dir.exists()) dirs.add(dir)
        }

        // Android output dirs: prefer release variant over debug to avoid duplicate classes
        val androidReleaseDirs = listOf(
            File(buildDir, "tmp/kotlin-classes/release"),
            File(buildDir, "intermediates/built_in_kotlinc/release/compileReleaseKotlin/classes"),
            File(buildDir, "classes/kotlin/android/release"),
            File(buildDir, "intermediates/javac/release/compileReleaseJavaWithJavac/classes"),
        )
        val androidDebugDirs = listOf(
            File(buildDir, "tmp/kotlin-classes/debug"),
            File(buildDir, "intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes"),
            File(buildDir, "classes/kotlin/android/debug"),
            File(buildDir, "intermediates/javac/debug/compileDebugJavaWithJavac/classes"),
        )

        val existingRelease = androidReleaseDirs.filter { it.exists() }
        if (existingRelease.isNotEmpty()) {
            dirs.addAll(existingRelease)
        } else {
            dirs.addAll(androidDebugDirs.filter { it.exists() })
        }

        return dirs
    }

    @JvmOverloads
    fun resolveClasspath(project: Project, variantName: String? = null): List<File> {
        val files = mutableListOf<File>()

        val candidateConfigNames = buildList {
            if (!variantName.isNullOrBlank()) {
                add("${variantName}RuntimeClasspath")
                add("${variantName}CompileClasspath")
            }
            add("releaseRuntimeClasspath")
            add("releaseCompileClasspath")
            add("runtimeClasspath")
            add("compileClasspath")
            add("jvmRuntimeClasspath")
            add("jvmCompileClasspath")
            add("debugRuntimeClasspath")
            add("debugCompileClasspath")
        }.distinct()

        for (configName in candidateConfigNames) {
            val config = project.configurations.findByName(configName) ?: continue
            if (!config.isCanBeResolved) continue
            val resolved = runCatching {
                config.incoming.artifactView { view -> view.lenient(true) }.files.files
            }.getOrElse {
                runCatching { config.files }.getOrDefault(emptySet())
            }
            files.addAll(resolved.filter { it.exists() })
        }

        val isAndroidProject = project.plugins.hasPlugin("com.android.library") ||
            project.plugins.hasPlugin("com.android.application") ||
            project.plugins.hasPlugin("com.android.dynamic-feature") ||
            project.plugins.hasPlugin("com.android.kotlin.multiplatform.library")

        if (isAndroidProject) {
            val androidJar = findAndroidJar(project)
            if (androidJar != null && androidJar.exists()) {
                files.add(androidJar)
            }
        }

        // Kotlin and recent javac may reference JDK-only types (e.g. StringConcatFactory). R8 needs
        // those on the library classpath even when android.jar is the platform boot classpath.
        val jdkCacheDir = File(project.rootProject.layout.buildDirectory.asFile.get(), "shrinkguard/jdk-cache")
        val jdkJar = JdkJarExtractor.getJdkLibraryJar(jdkCacheDir)
        if (jdkJar != null && jdkJar.exists()) {
            files.add(jdkJar)
        }

        return files.distinct()
    }

    private fun findAndroidJar(project: Project): File? {
        val androidHome = findAndroidSdkDir(project) ?: return null
        val platformsDir = File(androidHome, "platforms")
        if (!platformsDir.isDirectory) return null

        val configuredCompileSdk = readCompileSdk(project)
        if (configuredCompileSdk != null) {
            val exactJar = File(platformsDir, "android-$configuredCompileSdk/android.jar")
            if (exactJar.isFile) {
                return exactJar
            }
        }

        val latestPlatform = platformsDir.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("android-") && File(it, "android.jar").isFile }
            ?.maxWithOrNull(
                compareBy<File> { it.name.removePrefix("android-").toIntOrNull() ?: -1 }
                    .thenBy { it.name }
            )
        return latestPlatform?.let { File(it, "android.jar") }
    }

    private fun findAndroidSdkDir(project: Project): String? {
        System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { return it }
        System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let { return it }
        (project.findProperty("sdk.dir") as? String)?.takeIf { it.isNotBlank() }?.let { return it }

        val localPropsFiles = listOf(
            File(project.rootProject.projectDir, "local.properties"),
            File(project.projectDir, "local.properties"),
        )
        for (propsFile in localPropsFiles) {
            if (propsFile.isFile) {
                val props = Properties()
                runCatching {
                    propsFile.inputStream().use { props.load(it) }
                }
                val sdkDir = props.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
                if (sdkDir != null) {
                    return sdkDir
                }
            }
        }
        return null
    }

    private fun readCompileSdk(project: Project): Int? {
        val androidExt = project.extensions.findByName("android") ?: return null
        return runCatching {
            androidExt.javaClass.methods
                .firstOrNull { it.name == "getCompileSdk" && it.parameterCount == 0 }
                ?.invoke(androidExt) as? Int
        }.getOrNull()
    }
}
