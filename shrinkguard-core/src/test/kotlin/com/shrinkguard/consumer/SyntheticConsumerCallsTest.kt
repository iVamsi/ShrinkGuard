package com.shrinkguard.consumer

import com.shrinkguard.model.MemberInfo
import com.shrinkguard.model.MemberKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.TypeInsnNode

/**
 * The synthetic consumer stands in for an application that uses the library. It has to *call* the
 * public API: a class-constant reference keeps the class but tells R8 nothing about its members.
 */
class SyntheticConsumerCallsTest {

    private val generator = SyntheticConsumerGenerator()

    private fun instructionsOf(members: List<MemberInfo>): List<Any> {
        val node = ClassNode()
        ClassReader(generator.generateConsumerClassBytes(members)).accept(node, 0)
        return node.methods.flatMap { it.instructions.toList() }
    }

    private fun calls(members: List<MemberInfo>): List<MethodInsnNode> =
        instructionsOf(members).filterIsInstance<MethodInsnNode>()

    @Test
    fun `calls every public instance method`() {
        val members = listOf(
            MemberInfo("com.example.Api", "<class>", kind = MemberKind.CLASS, accessFlags = Opcodes.ACC_PUBLIC),
            MemberInfo("com.example.Api", "greet", "(Ljava/lang/String;)Ljava/lang/String;", MemberKind.METHOD, Opcodes.ACC_PUBLIC)
        )

        assertThat(calls(members))
            .anyMatch { it.opcode == Opcodes.INVOKEVIRTUAL && it.owner == "com/example/Api" && it.name == "greet" }
    }

    @Test
    fun `instantiates classes through their public constructors`() {
        val members = listOf(
            MemberInfo("com.example.Api", "<class>", kind = MemberKind.CLASS, accessFlags = Opcodes.ACC_PUBLIC),
            MemberInfo("com.example.Api", "<init>", "(I)V", MemberKind.CONSTRUCTOR, Opcodes.ACC_PUBLIC)
        )

        assertThat(instructionsOf(members).filterIsInstance<TypeInsnNode>())
            .anyMatch { it.opcode == Opcodes.NEW && it.desc == "com/example/Api" }
        assertThat(calls(members))
            .anyMatch { it.opcode == Opcodes.INVOKESPECIAL && it.owner == "com/example/Api" && it.name == "<init>" }
    }

    @Test
    fun `calls public static methods statically`() {
        val members = listOf(
            MemberInfo("com.example.Api", "<class>", kind = MemberKind.CLASS, accessFlags = Opcodes.ACC_PUBLIC),
            MemberInfo("com.example.Api", "create", "()Lcom/example/Api;", MemberKind.METHOD, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC)
        )

        assertThat(calls(members))
            .anyMatch { it.opcode == Opcodes.INVOKESTATIC && it.name == "create" }
    }

    @Test
    fun `never instantiates an interface and calls its methods through invokeinterface`() {
        val members = listOf(
            MemberInfo("com.example.Listener", "<class>", kind = MemberKind.CLASS, accessFlags = Opcodes.ACC_PUBLIC or Opcodes.ACC_INTERFACE or Opcodes.ACC_ABSTRACT),
            MemberInfo("com.example.Listener", "onEvent", "()V", MemberKind.METHOD, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT)
        )

        assertThat(instructionsOf(members).filterIsInstance<TypeInsnNode>())
            .noneMatch { it.opcode == Opcodes.NEW && it.desc == "com/example/Listener" }
        assertThat(calls(members))
            .anyMatch { it.opcode == Opcodes.INVOKEINTERFACE && it.owner == "com/example/Listener" }
    }

    @Test
    fun `spreads large APIs across helper methods to stay under the method size limit`() {
        val members = buildList {
            add(MemberInfo("com.example.Big", "<class>", kind = MemberKind.CLASS, accessFlags = Opcodes.ACC_PUBLIC))
            repeat(2_000) { index ->
                add(MemberInfo("com.example.Big", "method$index", "()V", MemberKind.METHOD, Opcodes.ACC_PUBLIC))
            }
        }

        val node = ClassNode()
        ClassReader(generator.generateConsumerClassBytes(members)).accept(node, 0)

        assertThat(node.methods.map { it.name }).contains("main")
        assertThat(node.methods).hasSizeGreaterThan(3)
    }
}

/**
 * R8 rejects malformed bytecode, so the generator has to produce a class that verifies. Checking
 * instruction presence alone misses stack errors such as popping a value the call already consumed.
 */
class SyntheticConsumerVerificationTest {

    @Test
    fun `generated class passes bytecode verification`() {
        val members = listOf(
            MemberInfo("com.example.Api", "<class>", kind = MemberKind.CLASS, accessFlags = Opcodes.ACC_PUBLIC),
            MemberInfo("com.example.Api", "<init>", "(ILjava/lang/String;)V", MemberKind.CONSTRUCTOR, Opcodes.ACC_PUBLIC),
            MemberInfo("com.example.Api", "greet", "(Ljava/lang/String;)Ljava/lang/String;", MemberKind.METHOD, Opcodes.ACC_PUBLIC),
            MemberInfo("com.example.Api", "count", "()J", MemberKind.METHOD, Opcodes.ACC_PUBLIC),
            MemberInfo("com.example.Api", "create", "()Lcom/example/Api;", MemberKind.METHOD, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC),
            MemberInfo("com.example.Api", "VERSION", "Ljava/lang/String;", MemberKind.FIELD, Opcodes.ACC_PUBLIC or Opcodes.ACC_STATIC),
            MemberInfo("com.example.Api", "size", "I", MemberKind.FIELD, Opcodes.ACC_PUBLIC)
        )

        val bytes = SyntheticConsumerGenerator().generateConsumerClassBytes(members)

        val output = java.io.StringWriter()
        org.objectweb.asm.util.CheckClassAdapter.verify(
            ClassReader(bytes),
            /* loader = */ null,
            /* printResults = */ false,
            java.io.PrintWriter(output)
        )

        assertThat(output.toString())
            .withFailMessage("bytecode verification reported:\n%s", output.toString())
            .isEmpty()
    }
}
