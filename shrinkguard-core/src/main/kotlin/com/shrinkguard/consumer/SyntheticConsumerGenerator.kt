package com.shrinkguard.consumer

import com.shrinkguard.model.MemberInfo
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class SyntheticConsumerGenerator {

    companion object {
        const val SYNTHETIC_CLASS_INTERNAL = "com/shrinkguard/synthetic/SyntheticConsumer"
        const val SYNTHETIC_CLASS_NAME = "com.shrinkguard.synthetic.SyntheticConsumer"
    }

    fun generateConsumerJar(publicMembers: List<MemberInfo>, outputFile: File): File {
        outputFile.parentFile?.mkdirs()
        val classBytes = generateConsumerClassBytes(publicMembers)

        JarOutputStream(FileOutputStream(outputFile)).use { jarOut ->
            val entry = JarEntry("$SYNTHETIC_CLASS_INTERNAL.class")
            jarOut.putNextEntry(entry)
            jarOut.write(classBytes)
            jarOut.closeEntry()
        }
        return outputFile
    }

    fun generateConsumerKeepRules(): String {
        return """
            # ShrinkGuard Synthetic Consumer Entry Point
            -keep class $SYNTHETIC_CLASS_NAME {
                public static void main(java.lang.String[]);
            }
        """.trimIndent()
    }

    fun generateSyntheticKeepRulesForPublicApi(publicMembers: List<MemberInfo>): String {
        val classNames = publicMembers.map { it.ownerClass }.distinct()
        val sb = StringBuilder()
        sb.append("# ShrinkGuard Public API Entry Points (Simulating App Consumer Calls)\n")
        for (className in classNames) {
            sb.append("-keep class $className {\n")
            sb.append("    public *;\n")
            sb.append("    protected *;\n")
            sb.append("}\n")
        }
        return sb.toString()
    }

    fun generateConsumerClassBytes(publicMembers: List<MemberInfo>): ByteArray {
        val cw = ClassWriter(ClassWriter.COMPUTE_FRAMES or ClassWriter.COMPUTE_MAXS)
        cw.visit(
            Opcodes.V17,
            Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL,
            SYNTHETIC_CLASS_INTERNAL,
            null,
            "java/lang/Object",
            null
        )

        // Default constructor <init>()
        val initMv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        initMv.visitCode()
        initMv.visitVarInsn(Opcodes.ALOAD, 0)
        initMv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        initMv.visitInsn(Opcodes.RETURN)
        initMv.visitMaxs(1, 1)
        initMv.visitEnd()

        // public static void main(String[] args)
        val mainMv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "main",
            "([Ljava/lang/String;)V",
            null,
            null
        )
        mainMv.visitCode()

        // Emit synthetic calls to public API members
        val distinctClasses = publicMembers.map { it.ownerClass }.distinct()
        for (className in distinctClasses) {
            val internalName = className.replace('.', '/')
            mainMv.visitLdcInsn(Type.getObjectType(internalName))
            mainMv.visitInsn(Opcodes.POP)
        }

        mainMv.visitInsn(Opcodes.RETURN)
        mainMv.visitMaxs(2, 1)
        mainMv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }
}
