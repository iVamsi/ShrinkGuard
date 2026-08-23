package com.shrinkguard.api

import com.shrinkguard.api.fixtures.InternalType
import com.shrinkguard.api.fixtures.PublicType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class PublicApiExtractorTest {

    @Test
    fun `kotlin internal types and members are not public API`() {
        val classesDir = File(PublicType::class.java.protectionDomain.codeSource.location.toURI())
        val extracted = PublicApiExtractor().extract(classesDir)
        val fixtures = extracted.publicMembers.filter {
            it.ownerClass.startsWith("com.shrinkguard.api.fixtures")
        }

        assertThat(fixtures.map { it.ownerClass }).doesNotContain(InternalType::class.java.name)
        assertThat(fixtures.map { it.memberName }).contains("visible").doesNotContain("hidden")
    }
}
