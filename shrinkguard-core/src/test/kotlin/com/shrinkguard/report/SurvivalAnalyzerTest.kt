package com.shrinkguard.report

import com.shrinkguard.model.MemberInfo
import com.shrinkguard.model.MemberKind
import com.shrinkguard.model.SurvivalStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SurvivalAnalyzerTest {

    private val analyzer = SurvivalAnalyzer()

    private fun method(owner: String, name: String, descriptor: String = "()V") =
        MemberInfo(ownerClass = owner, memberName = name, descriptor = descriptor, kind = MemberKind.METHOD)

    private fun clazz(owner: String) =
        MemberInfo(ownerClass = owner, memberName = "<class>", kind = MemberKind.CLASS)

    @Test
    fun `member present in the R8 output under its own name survived unchanged`() {
        val declared = method("com.example.Money", "getCurrencyCode", "()Ljava/lang/String;")

        val result = analyzer.analyze(
            declared = listOf(declared),
            survivors = setOf(MemberKey("com.example.Money", "getCurrencyCode", "()Ljava/lang/String;")),
            mappings = emptyMap()
        )

        assertThat(result.single().survivalStatus).isEqualTo(SurvivalStatus.KEPT_UNCHANGED)
    }

    @Test
    fun `member missing from the R8 output was stripped`() {
        val result = analyzer.analyze(
            declared = listOf(method("com.example.Money", "unused")),
            survivors = emptySet(),
            mappings = emptyMap()
        )

        assertThat(result.single().survivalStatus).isEqualTo(SurvivalStatus.INLINED_OR_STRIPPED)
    }

    @Test
    fun `class present in the R8 output under an obfuscated name was renamed`() {
        val mappings = mapOf(
            "com.example.Plugin" to ClassMapping("com.example.Plugin", "a.a")
        )

        val result = analyzer.analyze(
            declared = listOf(clazz("com.example.Plugin")),
            survivors = setOf(MemberKey("a.a", "<class>", null)),
            mappings = mappings
        )

        assertThat(result.single().survivalStatus).isEqualTo(SurvivalStatus.RENAMED)
        assertThat(result.single().obfuscatedName).isEqualTo("a.a")
    }

    @Test
    fun `renamed member is found under its obfuscated name`() {
        val mappings = mapOf(
            "com.example.Money" to ClassMapping("com.example.Money", "com.example.Money").apply {
                members.add(MemberMapping("getCurrencyCode", "a", "java.lang.String", isMethod = true))
            }
        )

        val result = analyzer.analyze(
            declared = listOf(method("com.example.Money", "getCurrencyCode", "()Ljava/lang/String;")),
            survivors = setOf(MemberKey("com.example.Money", "a", "()Ljava/lang/String;")),
            mappings = mappings
        )

        assertThat(result.single().survivalStatus).isEqualTo(SurvivalStatus.RENAMED)
        assertThat(result.single().obfuscatedName).isEqualTo("a")
    }

    @Test
    fun `descriptor types are remapped before looking the member up`() {
        val mappings = mapOf(
            "com.example.Money" to ClassMapping("com.example.Money", "a.b")
        )
        val declared = method("com.example.Api", "convert", "(Lcom/example/Money;)Lcom/example/Money;")

        val result = analyzer.analyze(
            declared = listOf(declared),
            survivors = setOf(MemberKey("com.example.Api", "convert", "(La/b;)La/b;")),
            mappings = mappings
        )

        assertThat(result.single().survivalStatus).isEqualTo(SurvivalStatus.KEPT_UNCHANGED)
    }

    @Test
    fun `overloads are matched by descriptor not by name alone`() {
        val mappings = mapOf(
            "com.example.Money" to ClassMapping("com.example.Money", "com.example.Money").apply {
                members.add(
                    MemberMapping(
                        originalName = "convert",
                        obfuscatedName = "a",
                        returnType = "com.example.Money",
                        isMethod = true,
                        descriptor = "(Lcom/example/Money;)Lcom/example/Money;"
                    )
                )
                members.add(
                    MemberMapping(
                        originalName = "convert",
                        obfuscatedName = "b",
                        returnType = "com.example.Money",
                        isMethod = true,
                        descriptor = "(Lcom/example/Money;D)Lcom/example/Money;"
                    )
                )
            }
        )

        val unary = method("com.example.Money", "convert", "(Lcom/example/Money;)Lcom/example/Money;")
        val binary = method("com.example.Money", "convert", "(Lcom/example/Money;D)Lcom/example/Money;")
        val result = analyzer.analyze(
            declared = listOf(unary, binary),
            survivors = setOf(
                MemberKey("com.example.Money", "a", "(Lcom/example/Money;)Lcom/example/Money;"),
                MemberKey("com.example.Money", "b", "(Lcom/example/Money;D)Lcom/example/Money;")
            ),
            mappings = mappings
        )

        assertThat(result[0].obfuscatedName).isEqualTo("a")
        assertThat(result[1].obfuscatedName).isEqualTo("b")
    }
}
