plugins {
    alias(libs.plugins.kotlin.jvm)
    id("io.github.ivamsi.shrinkguard")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

shrinkGuard {
    baselineFile.set(file("shrink-report.txt"))
    ruleLint {
        failOnToxicFlags.set(true)
        failOnOverbroadRules.set(false)
    }
}
