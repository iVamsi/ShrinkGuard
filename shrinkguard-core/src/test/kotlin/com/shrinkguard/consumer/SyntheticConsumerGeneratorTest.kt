package com.shrinkguard.consumer

import com.shrinkguard.model.MemberInfo
import com.shrinkguard.model.MemberKind
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class SyntheticConsumerGeneratorTest {

    private val generator = SyntheticConsumerGenerator()

    @Test
    fun `generates valid synthetic consumer bytecode`() {
        val members = listOf(
            MemberInfo("com.example.Calculator", "<class>", kind = MemberKind.CLASS),
            MemberInfo("com.example.Calculator", "add", "(II)I", kind = MemberKind.METHOD)
        )

        val bytes = generator.generateConsumerClassBytes(members)
        assertNotNull(bytes)
        assertTrue(bytes.isNotEmpty())

        val tempJar = File.createTempFile("shrinkguard-test-consumer", ".jar")
        tempJar.deleteOnExit()

        val generatedJar = generator.generateConsumerJar(members, tempJar)
        assertTrue(generatedJar.exists())
        assertTrue(generatedJar.length() > 0)
    }

    @Test
    fun `generates keep rules for synthetic consumer`() {
        val rules = generator.generateConsumerKeepRules()
        assertTrue(rules.contains("com.shrinkguard.synthetic.SyntheticConsumer"))
        assertTrue(rules.contains("main(java.lang.String[])"))
    }
}
