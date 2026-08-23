package com.shrinkguard.api

import com.shrinkguard.model.MemberInfo
import com.shrinkguard.model.MemberKind
import kotlin.metadata.KmClass
import kotlin.metadata.KmPackage
import kotlin.metadata.Visibility
import kotlin.metadata.visibility
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.Metadata
import kotlin.metadata.jvm.fieldSignature
import kotlin.metadata.jvm.getterSignature
import kotlin.metadata.jvm.setterSignature
import kotlin.metadata.jvm.signature
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
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
            private var classAccess: Int = 0
            private var isPublicOrProtectedClass: Boolean = false
            private var kotlinMetadata: Metadata? = null
            private val pending = mutableListOf<MemberInfo>()

            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?
            ) {
                className = name.replace('/', '.')
                classAccess = access
                val isSynthetic = (access and Opcodes.ACC_SYNTHETIC) != 0
                val isPublic = (access and Opcodes.ACC_PUBLIC) != 0
                val isProtected = (access and Opcodes.ACC_PROTECTED) != 0
                val isInternalPackage = className.contains(".internal.") || className.endsWith(".internal")
                isPublicOrProtectedClass = !isSynthetic && (isPublic || isProtected) && !isInternalPackage
                pending.add(
                    MemberInfo(
                        ownerClass = className,
                        memberName = "<class>",
                        kind = MemberKind.CLASS,
                        accessFlags = access
                    )
                )
                super.visit(version, access, name, signature, superName, interfaces)
            }

            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                if (descriptor == "Lkotlin/Metadata;") {
                    return MetadataAnnotationReader { kotlinMetadata = it }
                }
                return super.visitAnnotation(descriptor, visible)
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
                if (!isSynthetic && !isBridge) {
                    val kind = if (name == "<init>") MemberKind.CONSTRUCTOR else MemberKind.METHOD
                    pending.add(
                        MemberInfo(
                            ownerClass = className,
                            memberName = name,
                            descriptor = descriptor,
                            kind = kind,
                            accessFlags = access
                        )
                    )
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
                if (!isSynthetic) {
                    pending.add(
                        MemberInfo(
                            ownerClass = className,
                            memberName = name,
                            descriptor = descriptor,
                            kind = MemberKind.FIELD,
                            accessFlags = access
                        )
                    )
                }
                return super.visitField(access, name, descriptor, signature, value)
            }

            override fun visitEnd() {
                val kotlinInternal = KotlinInternalMembers.from(kotlinMetadata)
                val classIsKotlinInternal = kotlinInternal.classIsInternal
                for (member in pending) {
                    allOut.add(member)
                    if (!isPublicOrProtectedClass || classIsKotlinInternal) continue
                    if (member.kind == MemberKind.CLASS) {
                        publicOut.add(member)
                        continue
                    }
                    val isPublic = (member.accessFlags and Opcodes.ACC_PUBLIC) != 0
                    val isProtected = (member.accessFlags and Opcodes.ACC_PROTECTED) != 0
                    if (!(isPublic || isProtected)) continue
                    if (kotlinInternal.isInternalMember(member.memberName, member.descriptor)) continue
                    publicOut.add(member)
                }
                super.visitEnd()
            }
        }, ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
    }
}

private class MetadataAnnotationReader(
    private val onValue: (Metadata) -> Unit
) : AnnotationVisitor(Opcodes.ASM9) {
    private var kind: Int = 1
    private var metadataVersion: IntArray = intArrayOf()
    private var data1: Array<String> = emptyArray()
    private var data2: Array<String> = emptyArray()
    private var extraString: String = ""
    private var packageName: String = ""
    private var extraInt: Int = 0

    override fun visit(name: String?, value: Any?) {
        when (name) {
            "k" -> kind = value as Int
            "mv" -> metadataVersion = value as IntArray
            "xi" -> extraInt = value as Int
            "xs" -> extraString = value as String
            "pn" -> packageName = value as String
        }
    }

    override fun visitArray(name: String?): AnnotationVisitor {
        val ints = mutableListOf<Int>()
        val strings = mutableListOf<String>()
        return object : AnnotationVisitor(Opcodes.ASM9) {
            override fun visit(ignored: String?, value: Any?) {
                when (value) {
                    is Int -> ints.add(value)
                    is String -> strings.add(value)
                }
            }

            override fun visitEnd() {
                when (name) {
                    "mv" -> metadataVersion = ints.toIntArray()
                    "d1" -> data1 = strings.toTypedArray()
                    "d2" -> data2 = strings.toTypedArray()
                }
            }
        }
    }

    override fun visitEnd() {
        onValue(
            Metadata(
                kind = kind,
                metadataVersion = metadataVersion,
                data1 = data1,
                data2 = data2,
                extraString = extraString,
                packageName = packageName,
                extraInt = extraInt
            )
        )
    }
}

private class KotlinInternalMembers(
    val classIsInternal: Boolean,
    private val internalKeys: Set<Pair<String, String?>>
) {
    fun isInternalMember(name: String, descriptor: String?): Boolean {
        return (name to descriptor) in internalKeys || (name to null) in internalKeys
    }

    companion object {
        fun from(header: Metadata?): KotlinInternalMembers {
            if (header == null) return KotlinInternalMembers(false, emptySet())
            return try {
                when (val metadata = KotlinClassMetadata.readLenient(header)) {
                    is KotlinClassMetadata.Class -> fromClass(metadata.kmClass)
                    is KotlinClassMetadata.FileFacade -> fromPackage(metadata.kmPackage)
                    is KotlinClassMetadata.MultiFileClassPart -> fromPackage(metadata.kmPackage)
                    else -> KotlinInternalMembers(false, emptySet())
                }
            } catch (_: Throwable) {
                KotlinInternalMembers(false, emptySet())
            }
        }

        private fun fromClass(kmClass: KmClass): KotlinInternalMembers {
            val keys = mutableSetOf<Pair<String, String?>>()
            collectFunctions(kmClass.functions, keys)
            collectConstructors(kmClass.constructors, keys)
            collectProperties(kmClass.properties, keys)
            return KotlinInternalMembers(
                classIsInternal = kmClass.visibility == Visibility.INTERNAL,
                internalKeys = keys
            )
        }

        private fun fromPackage(kmPackage: KmPackage): KotlinInternalMembers {
            val keys = mutableSetOf<Pair<String, String?>>()
            collectFunctions(kmPackage.functions, keys)
            collectProperties(kmPackage.properties, keys)
            return KotlinInternalMembers(false, keys)
        }

        private fun collectFunctions(
            functions: List<kotlin.metadata.KmFunction>,
            keys: MutableSet<Pair<String, String?>>
        ) {
            for (function in functions) {
                if (function.visibility != Visibility.INTERNAL) continue
                val signature = function.signature
                if (signature != null) {
                    keys.add(signature.name to signature.descriptor)
                } else {
                    keys.add(function.name to null)
                }
            }
        }

        private fun collectConstructors(
            constructors: List<kotlin.metadata.KmConstructor>,
            keys: MutableSet<Pair<String, String?>>
        ) {
            for (constructor in constructors) {
                if (constructor.visibility != Visibility.INTERNAL) continue
                val signature = constructor.signature
                if (signature != null) {
                    keys.add(signature.name to signature.descriptor)
                } else {
                    keys.add("<init>" to null)
                }
            }
        }

        private fun collectProperties(
            properties: List<kotlin.metadata.KmProperty>,
            keys: MutableSet<Pair<String, String?>>
        ) {
            for (property in properties) {
                if (property.visibility != Visibility.INTERNAL) continue
                property.getterSignature?.let { keys.add(it.name to it.descriptor) }
                property.setterSignature?.let { keys.add(it.name to it.descriptor) }
                property.fieldSignature?.let { keys.add(it.name to it.descriptor) }
                keys.add(property.name to null)
            }
        }
    }
}
