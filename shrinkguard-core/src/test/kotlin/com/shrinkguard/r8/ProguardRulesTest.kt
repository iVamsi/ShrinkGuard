package com.shrinkguard.r8

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProguardRulesTest {

    @Test
    fun `splits multiline keep blocks into one R8 line each`() {
        val block = """
            -keep class com.shrinkguard.synthetic.SyntheticConsumer {
                public static <methods>;
            }
        """.trimIndent()

        assertThat(ProguardRules.toLines(listOf(block, "-dontoptimize")))
            .containsExactly(
                "-keep class com.shrinkguard.synthetic.SyntheticConsumer {",
                "    public static <methods>;",
                "}",
                "-dontoptimize"
            )
    }

    @Test
    fun `drops comments and blank lines`() {
        val lines = ProguardRules.toLines(
            listOf(
                "# header\n\n-keep class foo.Bar { *; }\n"
            )
        )

        assertThat(lines).containsExactly("-keep class foo.Bar { *; }")
    }
}
