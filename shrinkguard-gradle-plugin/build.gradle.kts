plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-gradle-plugin`
    alias(libs.plugins.vanniktech.maven.publish)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

gradlePlugin {
    plugins {
        create("shrinkguard") {
            id = "io.github.ivamsi.shrinkguard"
            implementationClass = "com.shrinkguard.gradle.ShrinkGuardPlugin"
            displayName = "ShrinkGuard Gradle Plugin"
            description = "R8 fitness harness and consumer rules linter for Kotlin/Android libraries"
        }
    }
}

dependencies {
    implementation(project(":shrinkguard-core"))
    implementation(libs.r8)
    implementation(libs.asm)
    compileOnly(libs.agp)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates(property("group") as String, "shrinkguard-gradle-plugin", property("version") as String)

    pom {
        name.set("ShrinkGuard Gradle Plugin")
        description.set("R8 fitness harness and consumer rules linter for Kotlin/Android libraries")
        url.set("https://github.com/iVamsi/ShrinkGuard")
        inceptionYear.set("2026")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                id.set("ivamsi")
                name.set("Vamsi Vaddavalli")
                url.set("https://github.com/iVamsi")
            }
        }

        scm {
            url.set("https://github.com/iVamsi/ShrinkGuard")
            connection.set("scm:git:git://github.com/iVamsi/ShrinkGuard.git")
            developerConnection.set("scm:git:ssh://git@github.com/iVamsi/ShrinkGuard.git")
        }
    }
}
