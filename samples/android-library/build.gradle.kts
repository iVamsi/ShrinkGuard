plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.jvm) apply false
    id("io.github.ivamsi.shrinkguard")
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
