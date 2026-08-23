package com.shrinkguard.api

import com.shrinkguard.model.MemberInfo
import com.shrinkguard.model.MemberKind
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.Opcodes
import java.io.File
import java.io.InputStream
import java.util.jar.JarFile

data class ExtractedMembers(
    val publicMembers: List<MemberInfo>,
    val allMembers: List<MemberInfo>
)

class PublicApiExtractor {

    fun extract(classesDirOrJar: File): ExtractedMembers {
        val all = mutableListOf<MemberInfo>()
        val publicOnly = mutableListOf<MemberInfo>()

        if (classesDirOrJar.isDirectory) {
            classesDirOrJar.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { classFile ->
                    classFile.inputStream().use { stream ->
                        extractFromClassStream(stream, all, publicOnly)
                    }
                }
        } else if (classesDirOrJar.isFile && classesDirOrJar.extension == "jar") {
            JarFile(classesDirOrJar).use { jar ->
                val entries = jar.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && entry.name.endsWith(".class")) {
                        jar.getInputStream(entry).use { stream ->
                            extractFromClassStream(stream, all, publicOnly)
                        }
                    }
                }
            }
        }

        return ExtractedMembers(publicOnly.sorted(), all.sorted())
    }

    fun extractFromDirectory(classesDir: File): List<MemberInfo> {
        return extract(classesDir).publicMembers
    }

    fun extractAllFromDirectory(classesDir: File): List<MemberInfo> {
        return extract(classesDir).allMembers
    }

    private fun extractFromClassStream(
        stream: InputStream,
        allOut: MutableList<MemberInfo>,
        publicOut: MutableList<MemberInfo>
    ) {
        val reader = ClassReader(stream)
        reader.accept(object : ClassVisitor(Opcodes.ASM9) {
            private var className: String = ""
            private var isPublicOrProtectedClass: Boolean = false
            private var isInternalPackage: Boolean = false

            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?
            ) {
                className = name.replace('/', '.')
                val isSynthetic = (access and Opcodes.ACC_SYNTHETIC) != 0
                val isPublic = (access and Opcodes.ACC_PUBLIC) != 0
                val isProtected = (access and Opcodes.ACC_PROTECTED) != 0
                isInternalPackage = className.contains(".internal.") || className.endsWith(".internal")

                isPublicOrProtectedClass = !isSynthetic && (isPublic || isProtected) && !isInternalPackage

                val classMember = MemberInfo(
                    ownerClass = className,
                    memberName = "<class>",
                    kind = MemberKind.CLASS,
                    accessFlags = access
                )
                allOut.add(classMember)
                if (isPublicOrProtectedClass) {
                    publicOut.add(classMember)
                }

                super.visit(version, access, name, signature, superName, interfaces)
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?
            ): MethodVisitor? {
                val isSynthetic = (access and Opcodes.ACC_SYNTHETIC) != 0
                val isBridge = (access and Opcodes.ACC_BRIDGE) != 0
                val isPublic = (access and Opcodes.ACC_PUBLIC) != 0
                val isProtected = (access and Opcodes.ACC_PROTECTED) != 0

                if (!isSynthetic && !isBridge) {
                    val kind = if (name == "<init>") MemberKind.CONSTRUCTOR else MemberKind.METHOD
                    val methodMember = MemberInfo(
                        ownerClass = className,
                        memberName = name,
                        descriptor = descriptor,
                        kind = kind,
                        accessFlags = access
                    )
                    allOut.add(methodMember)
                    if (isPublicOrProtectedClass && (isPublic || isProtected)) {
                        publicOut.add(methodMember)
                    }
                }
                return super.visitMethod(access, name, descriptor, signature, exceptions)
            }

            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?
            ): FieldVisitor? {
                val isSynthetic = (access and Opcodes.ACC_SYNTHETIC) != 0
                val isPublic = (access and Opcodes.ACC_PUBLIC) != 0
                val isProtected = (access and Opcodes.ACC_PROTECTED) != 0

                if (!isSynthetic) {
                    val fieldMember = MemberInfo(
                        ownerClass = className,
                        memberName = name,
                        descriptor = descriptor,
                        kind = MemberKind.FIELD,
                        accessFlags = access
                    )
                    allOut.add(fieldMember)
                    if (isPublicOrProtectedClass && (isPublic || isProtected)) {
                        publicOut.add(fieldMember)
                    }
                }
                return super.visitField(access, name, descriptor, signature, value)
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    }
}
