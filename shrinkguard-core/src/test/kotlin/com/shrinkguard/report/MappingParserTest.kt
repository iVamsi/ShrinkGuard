package com.shrinkguard.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MappingParserTest {

    private val parser = MappingParser()

    @Test
    fun `parses mapping lines with renamed and kept classes`() {
        val mappingContent = listOf(
            "# compiler: R8",
            "com.example.PublicClass -> com.example.PublicClass:",
            "    1:2:void doWork(java.lang.String) -> doWork",
            "    3:4:int calculate(int,int) -> a",
            "com.example.InternalHelper -> com.example.a:",
            "    java.lang.String cacheKey -> b"
        )

        val result = parser.parseContent(mappingContent)

        assertEquals(2, result.size)

        val publicClass = result["com.example.PublicClass"]!!
        assertFalse(publicClass.isRenamed)
        assertEquals(2, publicClass.members.size)
        assertEquals("doWork", publicClass.members[0].obfuscatedName)
        assertEquals("a", publicClass.members[1].obfuscatedName)

        val internalClass = result["com.example.InternalHelper"]!!
        assertTrue(internalClass.isRenamed)
        assertEquals("com.example.a", internalClass.obfuscatedName)
        assertEquals("b", internalClass.members[0].obfuscatedName)
    }
}

class MappingParserLineNumberTest {

    private val parser = MappingParser()

    @Test
    fun `parses a method line carrying line number ranges`() {
        val mapping = parser.parseContent(
            listOf(
                "com.example.plugin.PluginLoader -> a.a:",
                "    1:1:void <init>():0:0 -> <init>",
                "    1:1:java.lang.Object load(java.lang.String):0:0 -> a"
            )
        )

        val members = mapping.getValue("com.example.plugin.PluginLoader").members
        assertThat(members.map { it.originalName }).containsExactly("<init>", "load")
        assertThat(members.single { it.originalName == "load" }.obfuscatedName).isEqualTo("a")
    }

    @Test
    fun `ignores inline frame suffixes rather than inventing empty members`() {
        val mapping = parser.parseContent(
            listOf(
                "com.example.Api -> com.example.Api:",
                "    3:7:java.lang.String helper(int):18:22 -> b"
            )
        )

        assertThat(mapping.getValue("com.example.Api").members.map { it.originalName })
            .containsExactly("helper")
    }

    @Test
    fun `records a JVM descriptor so overloads stay distinct`() {
        val mapping = parser.parseContent(
            listOf(
                "com.example.Money -> com.example.Money:",
                "    1:2:com.example.Money convert(com.example.Money) -> a",
                "    3:4:com.example.Money convert(com.example.Money, double) -> b"
            )
        )

        val members = mapping.getValue("com.example.Money").members
        assertThat(members).hasSize(2)
        assertThat(members[0].descriptor).isEqualTo("(Lcom/example/Money;)Lcom/example/Money;")
        assertThat(members[1].descriptor).isEqualTo("(Lcom/example/Money;D)Lcom/example/Money;")
        assertThat(members[0].obfuscatedName).isEqualTo("a")
        assertThat(members[1].obfuscatedName).isEqualTo("b")
    }
}
