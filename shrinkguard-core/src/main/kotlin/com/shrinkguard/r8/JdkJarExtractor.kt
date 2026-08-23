package com.shrinkguard.r8

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
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
            if (hasJmodHeader(jmodFile)) {
                copyClassEntriesFromJmodStream(jmodFile, tempJar)
            } else {
                copyClassEntriesFromZipFile(jmodFile, tempJar)
            }
            if (tempJar.renameTo(outputJar)) {
                return outputJar
            }
            tempJar.copyTo(outputJar, overwrite = true)
            tempJar.delete()
            return outputJar
        } catch (e: Exception) {
            tempJar.delete()
            throw e
        }
    }

    private fun hasJmodHeader(jmodFile: File): Boolean {
        FileInputStream(jmodFile).use { input ->
            val header = ByteArray(4)
            val read = input.read(header)
            return read == 4 && header[0] == 0x4A.toByte() && header[1] == 0x4D.toByte()
        }
    }

    private fun copyClassEntriesFromZipFile(jmodFile: File, tempJar: File) {
        ZipFile(jmodFile).use { zip ->
            ZipOutputStream(FileOutputStream(tempJar)).use { out ->
                for (entry in zip.entries().asSequence()) {
                    copyClassEntry(entry.name, entry.isDirectory, zip.getInputStream(entry), out)
                }
            }
        }
    }

    private fun copyClassEntriesFromJmodStream(jmodFile: File, tempJar: File) {
        FileInputStream(jmodFile).use { raw ->
            val header = ByteArray(4)
            val read = raw.read(header)
            if (read != 4 || header[0] != 0x4A.toByte() || header[1] != 0x4D.toByte()) {
                throw IllegalArgumentException("Not a jmod file: ${jmodFile.absolutePath}")
            }
            ZipInputStream(raw).use { zip ->
                ZipOutputStream(FileOutputStream(tempJar)).use { out ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        copyClassEntry(entry.name, entry.isDirectory, zip, out)
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    private fun copyClassEntry(
        name: String,
        isDirectory: Boolean,
        input: InputStream,
        out: ZipOutputStream
    ) {
        if (isDirectory || !name.startsWith("classes/") || !name.endsWith(".class")) {
            return
        }
        val relativeName = name.removePrefix("classes/")
        out.putNextEntry(ZipEntry(relativeName))
        input.copyTo(out)
        out.closeEntry()
    }
}
