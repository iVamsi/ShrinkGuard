package com.shrinkguard.gradle.internal

import com.shrinkguard.r8.JdkJarExtractor
import org.gradle.api.Project
import java.io.File

object LibraryArtifactResolver {

    fun findConsumerRules(project: Project): List<File> {
        val candidates = listOf(
            project.file("consumer-rules.pro"),
            project.file("src/main/resources/META-INF/proguard/rules.pro"),
            project.file("src/main/resources/META-INF/proguard/${project.name}.pro")
        )
        return candidates.filter { it.exists() }
    }

    fun findClassDirectories(project: Project): List<File> {
        val dirs = mutableListOf<File>()

        // JVM output dirs
        val kotlinClasses = File(project.layout.buildDirectory.asFile.get(), "classes/kotlin/main")
        val javaClasses = File(project.layout.buildDirectory.asFile.get(), "classes/java/main")
        if (kotlinClasses.exists()) dirs.add(kotlinClasses)
        if (javaClasses.exists()) dirs.add(javaClasses)

        // Android output dirs: prefer release variant over debug to avoid duplicate classes
        val androidReleaseKotlin = File(project.layout.buildDirectory.asFile.get(), "tmp/kotlin-classes/release")
        val androidReleaseJava = File(project.layout.buildDirectory.asFile.get(), "intermediates/javac/release/compileReleaseJavaWithJavac/classes")
        val androidDebugKotlin = File(project.layout.buildDirectory.asFile.get(), "tmp/kotlin-classes/debug")
        val androidDebugJava = File(project.layout.buildDirectory.asFile.get(), "intermediates/javac/debug/compileDebugJavaWithJavac/classes")

        val hasRelease = androidReleaseKotlin.exists() || androidReleaseJava.exists()
        if (hasRelease) {
            if (androidReleaseKotlin.exists()) dirs.add(androidReleaseKotlin)
            if (androidReleaseJava.exists()) dirs.add(androidReleaseJava)
        } else {
            if (androidDebugKotlin.exists()) dirs.add(androidDebugKotlin)
            if (androidDebugJava.exists()) dirs.add(androidDebugJava)
        }

        return dirs
    }

    fun resolveClasspath(project: Project): List<File> {
        val files = mutableListOf<File>()

        // Check runtimeClasspath / compileClasspath
        val runtimeConfig = project.configurations.findByName("releaseRuntimeClasspath")
            ?: project.configurations.findByName("runtimeClasspath")
            ?: project.configurations.findByName("debugRuntimeClasspath")
            ?: project.configurations.findByName("compileClasspath")

        if (runtimeConfig != null && runtimeConfig.isCanBeResolved) {
            files.addAll(runtimeConfig.files.filter { it.exists() })
        }

        val isAndroidLibrary = project.plugins.hasPlugin("com.android.library") ||
            project.plugins.hasPlugin("com.android.application")

        var hasAndroidJar = false
        if (isAndroidLibrary) {
            val androidHome = System.getenv("ANDROID_HOME")
                ?: System.getenv("ANDROID_SDK_ROOT")
                ?: project.findProperty("sdk.dir") as? String
            if (androidHome != null) {
                val platformsDir = File(androidHome, "platforms")
                if (platformsDir.exists()) {
                    val latestPlatform = platformsDir.listFiles()
                        ?.filter { it.isDirectory && it.name.startsWith("android-") }
                        ?.maxByOrNull { it.name }
                    if (latestPlatform != null) {
                        val androidJar = File(latestPlatform, "android.jar")
                        if (androidJar.exists()) {
                            files.add(androidJar)
                            hasAndroidJar = true
                        }
                    }
                }
            }
        }

        if (!hasAndroidJar) {
            val jdkCacheDir = File(project.rootProject.layout.buildDirectory.asFile.get(), "shrinkguard/jdk-cache")
            val jdkJar = JdkJarExtractor.getJdkLibraryJar(jdkCacheDir)
            if (jdkJar != null && jdkJar.exists()) {
                files.add(jdkJar)
            }
        }

        return files
    }
}
