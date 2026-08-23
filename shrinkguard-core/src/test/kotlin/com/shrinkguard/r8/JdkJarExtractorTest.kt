package com.shrinkguard.r8

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class JdkJarExtractorTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `reads a jmod after skipping the JM header`() {
        val classBytes = byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte())
        val zipBytes = ByteArrayOutputStream().use { raw ->
            ZipOutputStream(raw).use { zip ->
                zip.putNextEntry(ZipEntry("classes/java/lang/Object.class"))
                zip.write(classBytes)
                zip.closeEntry()
            }
            raw.toByteArray()
        }
        val jmod = File(tempDir, "java.base.jmod")
        jmod.writeBytes(byteArrayOf(0x4A, 0x4D, 0x01, 0x00) + zipBytes)

        val jar = JdkJarExtractor.extractJmodToJar(jmod, File(tempDir, "jdk-base-stubs.jar"))

        assertThat(jar).exists()
        java.util.zip.ZipFile(jar).use { zip ->
            assertThat(zip.getEntry("java/lang/Object.class")).isNotNull()
        }
    }
}
