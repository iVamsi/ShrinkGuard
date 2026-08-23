package com.shrinkguard.report

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
