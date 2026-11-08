package com.shrinkguard.r8

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import java.io.File

/**
 * Kotlin targets JVM 9+ string concatenation with invokedynamic; R8 needs android.jar as a platform
 * build plus java.base stubs when shrinking to class files.
 */
class DirectR8RunnerAndroidTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `resolves Kotlin indy string concat when android jar and jdk stubs are on the library classpath`() {
        val androidHome = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            androidHome != null,
            "ANDROID_HOME required to locate android.jar"
        )
        val platforms = File(androidHome, "platforms")
        val androidJar = platforms.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("android-") }
            ?.maxByOrNull { it.name }
            ?.let { File(it, "android.jar") }
        org.junit.jupiter.api.Assumptions.assumeTrue(
            androidJar != null && androidJar.exists(),
            "android.jar not found under $platforms"
        )

        val programDir = File(tempDir, "program").also { it.mkdirs() }
        writeIndyStringConcatClass(programDir)

        val jdkJar = JdkJarExtractor.getJdkLibraryJar(File(tempDir, "jdk-cache"))
            ?: error("Could not extract JDK library jar")

        val runner = DirectR8Runner()
        val outputDir = File(tempDir, "r8-out")
        val result = runner.runR8(
            R8ExecutionRequest(
                programFiles = listOf(programDir),
                libraryFiles = listOfNotNull(androidJar, jdkJar),
                proguardRules = listOf("-keep class IndyConcat { *; }"),
                outputDir = outputDir,
            )
        )

        assertThat(result.isSuccess).isTrue()
        assertThat(result.outputArtifact).exists()
    }

    private fun writeIndyStringConcatClass(outputDir: File) {
        val writer = ClassWriter(ClassWriter.COMPUTE_FRAMES)
        writer.visit(
            Opcodes.V11,
            Opcodes.ACC_PUBLIC,
            "IndyConcat",
            null,
            "java/lang/Object",
            null
        )
        val concatHandle = Handle(
            Opcodes.H_INVOKESTATIC,
            "java/lang/invoke/StringConcatFactory",
            "makeConcatWithConstants",
            "(Ljava/lang/invoke/MethodHandles\$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;",
            false
        )
        val greet = writer.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "greet",
            "(Ljava/lang/String;)Ljava/lang/String;",
            null,
            null
        )
        greet.visitCode()
        greet.visitLdcInsn("hi \u0001")
        greet.visitVarInsn(Opcodes.ALOAD, 0)
        greet.visitInvokeDynamicInsn(
            "makeConcatWithConstants",
            "(Ljava/lang/String;)Ljava/lang/String;",
            concatHandle,
            "hi \u0001"
        )
        greet.visitInsn(Opcodes.ARETURN)
        greet.visitMaxs(0, 0)
        greet.visitEnd()
        writer.visitEnd()

        val outFile = File(outputDir, "IndyConcat.class")
        outFile.writeBytes(writer.toByteArray())
    }
}
