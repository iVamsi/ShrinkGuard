package com.shrinkguard.r8

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

object JdkJarExtractor {

    fun getJdkLibraryJar(cacheDir: File): File? {
        val cached = File(cacheDir, "jdk-base-stubs.jar")
        if (cached.exists() && cached.length() > 0) {
            return cached
        }

        val javaHome = System.getProperty("java.home") ?: return null
        val rtJar = File(javaHome, "lib/rt.jar")
        if (rtJar.exists()) {
            return rtJar
        }

        val jmodFile = File(javaHome, "jmods/java.base.jmod")
        if (!jmodFile.exists()) {
            return null
        }

        return extractJmodToJar(jmodFile, cached)
    }

    fun extractJmodToJar(jmodFile: File, outputJar: File): File {
        if (outputJar.exists() && outputJar.length() > 0) {
            return outputJar
        }
        outputJar.parentFile?.mkdirs()
        val tempJar = File(outputJar.parentFile, "${outputJar.name}.tmp")

        try {
            ZipFile(jmodFile).use { zip ->
                ZipOutputStream(FileOutputStream(tempJar)).use { out ->
                    for (entry in zip.entries().asSequence()) {
                        if (!entry.isDirectory && entry.name.startsWith("classes/") && entry.name.endsWith(".class")) {
                            val relativeName = entry.name.removePrefix("classes/")
                            out.putNextEntry(ZipEntry(relativeName))
                            zip.getInputStream(entry).copyTo(out)
                            out.closeEntry()
                        }
                    }
                }
            }
            if (tempJar.renameTo(outputJar)) {
                return outputJar
            } else {
                tempJar.copyTo(outputJar, overwrite = true)
                tempJar.delete()
                return outputJar
            }
        } catch (e: Exception) {
            tempJar.delete()
            throw e
        }
    }
}
