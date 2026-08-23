plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.vanniktech.maven.publish)
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

dependencies {
    implementation(libs.r8)
    implementation(libs.asm)
    implementation(libs.asm.commons)
    implementation(libs.asm.tree)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral(com.vanniktech.maven.publish.SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    signAllPublications()

    coordinates(property("group") as String, "shrinkguard-core", property("version") as String)

    pom {
        name.set("ShrinkGuard Core")
        description.set("R8 fitness harness and consumer rules linter engine")
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
