package com.shrinkguard.consumer

import com.shrinkguard.model.MemberInfo
import com.shrinkguard.model.MemberKind
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type
import java.io.File
import java.io.FileOutputStream
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/**
 * Builds a class that uses the library the way an application would: it constructs its types,
 * calls its methods, and reads its fields. R8 then decides for itself what to keep, which is what
 * makes the library's own consumer rules observable. The generated code is never executed, so it
 * passes null and zero for every argument.
 */
class SyntheticConsumerGenerator {

    companion object {
        const val SYNTHETIC_CLASS_INTERNAL = "com/shrinkguard/synthetic/SyntheticConsumer"
        const val SYNTHETIC_CLASS_NAME = "com.shrinkguard.synthetic.SyntheticConsumer"

        /** A JVM method may hold 64 KB of bytecode; chunking keeps large APIs well clear of it. */
        private const val MEMBERS_PER_METHOD = 200
    }

    fun generateConsumerJar(publicMembers: List<MemberInfo>, outputFile: File): File {
        outputFile.parentFile?.mkdirs()
        val classBytes = generateConsumerClassBytes(publicMembers)

        JarOutputStream(FileOutputStream(outputFile)).use { jarOut ->
            jarOut.putNextEntry(JarEntry("$SYNTHETIC_CLASS_INTERNAL.class"))
            jarOut.write(classBytes)
            jarOut.closeEntry()
        }
        return outputFile
    }

    fun generateConsumerKeepRules(): String {
        // Every synthetic method is an entry point. R8 then treats their arguments as unknown
        // values, the way it treats an application's own code, instead of proving the receiver
        // null and deleting the call along with the member it was meant to exercise.
        return """
            # ShrinkGuard Synthetic Consumer Entry Point
            -keep class $SYNTHETIC_CLASS_NAME {
                public static <methods>;
            }
        """.trimIndent()
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

        val initMv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null)
        initMv.visitCode()
        initMv.visitVarInsn(Opcodes.ALOAD, 0)
        initMv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false)
        initMv.visitInsn(Opcodes.RETURN)
        initMv.visitMaxs(0, 0)
        initMv.visitEnd()

        val interfaceOwners = publicMembers
            .filter { it.kind == MemberKind.CLASS && (it.accessFlags and Opcodes.ACC_INTERFACE) != 0 }
            .map { it.ownerClass }
            .toSet()
        val instantiableOwners = publicMembers
            .filter { it.kind == MemberKind.CLASS }
            .filter { (it.accessFlags and (Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT)) == 0 }
            .map { it.ownerClass }
            .toSet()

        val chunks = publicMembers.chunked(MEMBERS_PER_METHOD)
        chunks.forEachIndexed { index, chunk ->
            val mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
                "use$index",
                "(Ljava/lang/Object;)V",
                null,
                null
            )
            mv.visitCode()

            // Receivers are derived from the incoming argument, whose value R8 cannot know.
            val receiverSlots = mutableMapOf<String, Int>()
            var nextSlot = 1
            for (owner in chunk.filter { it.needsReceiver() }.map { it.ownerClass }.distinct()) {
                mv.visitVarInsn(Opcodes.ALOAD, 0)
                mv.visitTypeInsn(Opcodes.CHECKCAST, owner.replace('.', '/'))
                mv.visitVarInsn(Opcodes.ASTORE, nextSlot)
                receiverSlots[owner] = nextSlot
                nextSlot++
            }

            for (member in chunk) {
                emitUse(mv, member, interfaceOwners, instantiableOwners, receiverSlots)
            }
            mv.visitInsn(Opcodes.RETURN)
            mv.visitMaxs(0, 0)
            mv.visitEnd()
        }

        val mainMv = cw.visitMethod(
            Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC,
            "main",
            "([Ljava/lang/String;)V",
            null,
            null
        )
        mainMv.visitCode()
        chunks.indices.forEach { index ->
            mainMv.visitInsn(Opcodes.ACONST_NULL)
            mainMv.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                SYNTHETIC_CLASS_INTERNAL,
                "use$index",
                "(Ljava/lang/Object;)V",
                false
            )
        }
        mainMv.visitInsn(Opcodes.RETURN)
        mainMv.visitMaxs(0, 0)
        mainMv.visitEnd()

        cw.visitEnd()
        return cw.toByteArray()
    }

    private fun MemberInfo.needsReceiver(): Boolean =
        (kind == MemberKind.METHOD || kind == MemberKind.FIELD) &&
            (accessFlags and Opcodes.ACC_STATIC) == 0

    private fun emitUse(
        mv: MethodVisitor,
        member: MemberInfo,
        interfaceOwners: Set<String>,
        instantiableOwners: Set<String>,
        receiverSlots: Map<String, Int>
    ) {
        val owner = member.ownerClass.replace('.', '/')
        val isStatic = (member.accessFlags and Opcodes.ACC_STATIC) != 0
        val isInterface = member.ownerClass in interfaceOwners

        when (member.kind) {
            MemberKind.CLASS -> {
                // A bare class reference, so that a type with no usable members is still referenced.
                mv.visitLdcInsn(Type.getObjectType(owner))
                mv.visitInsn(Opcodes.POP)
            }

            MemberKind.CONSTRUCTOR -> {
                val descriptor = member.descriptor ?: "()V"
                if (member.ownerClass !in instantiableOwners) return
                mv.visitTypeInsn(Opcodes.NEW, owner)
                // INVOKESPECIAL consumes the reference, so duplicate it to leave one to discard.
                mv.visitInsn(Opcodes.DUP)
                pushDefaultArguments(mv, descriptor)
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "<init>", descriptor, false)
                mv.visitInsn(Opcodes.POP)
            }

            MemberKind.METHOD -> {
                val descriptor = member.descriptor ?: return
                if (!isStatic) {
                    val slot = receiverSlots[member.ownerClass] ?: return
                    mv.visitVarInsn(Opcodes.ALOAD, slot)
                }
                pushDefaultArguments(mv, descriptor)
                val opcode = when {
                    isStatic -> Opcodes.INVOKESTATIC
                    isInterface -> Opcodes.INVOKEINTERFACE
                    else -> Opcodes.INVOKEVIRTUAL
                }
                mv.visitMethodInsn(opcode, owner, member.memberName, descriptor, isInterface)
                popReturnValue(mv, Type.getReturnType(descriptor))
            }

            MemberKind.FIELD -> {
                val descriptor = member.descriptor ?: return
                if (isStatic) {
                    mv.visitFieldInsn(Opcodes.GETSTATIC, owner, member.memberName, descriptor)
                } else {
                    val slot = receiverSlots[member.ownerClass] ?: return
                    mv.visitVarInsn(Opcodes.ALOAD, slot)
                    mv.visitFieldInsn(Opcodes.GETFIELD, owner, member.memberName, descriptor)
                }
                popReturnValue(mv, Type.getType(descriptor))
            }
        }
    }

    private fun pushDefaultArguments(mv: MethodVisitor, descriptor: String) {
        for (argument in Type.getArgumentTypes(descriptor)) {
            when (argument.sort) {
                Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> mv.visitInsn(Opcodes.ICONST_0)
                Type.LONG -> mv.visitInsn(Opcodes.LCONST_0)
                Type.FLOAT -> mv.visitInsn(Opcodes.FCONST_0)
                Type.DOUBLE -> mv.visitInsn(Opcodes.DCONST_0)
                else -> mv.visitInsn(Opcodes.ACONST_NULL)
            }
        }
    }

    private fun popReturnValue(mv: MethodVisitor, returnType: Type) {
        when (returnType.size) {
            0 -> Unit
            2 -> mv.visitInsn(Opcodes.POP2)
            else -> mv.visitInsn(Opcodes.POP)
        }
    }
}
