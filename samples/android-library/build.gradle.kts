plugins {
    alias(libs.plugins.android.library)
    id("io.github.ivamsi.shrinkguard")
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}

// AGP 8.x requires the Kotlin Android plugin; AGP 9+ compiles Kotlin without it.
if (providers.gradleProperty("shrinkguard.useKotlinAndroidPlugin").orNull == "true") {
    apply(plugin = "org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.android.crypto"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

shrinkGuard {
    baselineFile.set(file("shrink-report.txt"))
    ruleLint {
        failOnToxicFlags.set(true)
        failOnOverbroadRules.set(false)
    }
}
