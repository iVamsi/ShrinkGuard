package com.shrinkguard.gradle.internal

import com.android.build.gradle.LibraryExtension
import com.shrinkguard.gradle.tasks.ShrinkCheckTask
import com.shrinkguard.gradle.tasks.ShrinkReportTask
import org.gradle.api.Project
import org.gradle.api.tasks.TaskProvider

/**
 * Isolated so JVM TestKit runs never classload AGP. [ShrinkGuardPlugin] reflects into this
 * type only after `com.android.library` is applied.
 */
internal object AndroidLibraryWiring {

    @JvmStatic
    fun configure(
        project: Project,
        reportTask: TaskProvider<ShrinkReportTask>,
        checkTask: TaskProvider<ShrinkCheckTask>
    ) {
        val android = project.extensions.getByType(LibraryExtension::class.java)
        android.libraryVariants.configureEach { variant ->
            if (variant.name != "release") return@configureEach

            val javaCompile = variant.javaCompileProvider
            val capitalized = variant.name.replaceFirstChar { it.uppercase() }
            val kotlinTaskName = "compile${capitalized}Kotlin"

            reportTask.configure { task ->
                task.classDirectories.from(javaCompile.map { it.destinationDirectory })
                task.dependsOn(javaCompile)
                if (project.tasks.names.contains(kotlinTaskName)) {
                    val kotlinTask = project.tasks.named(kotlinTaskName)
                    task.dependsOn(kotlinTask)
                    task.classDirectories.from(
                        kotlinTask.map { compileTask ->
                            compileTask.javaClass.methods
                                .first { it.name == "getDestinationDirectory" }
                                .invoke(compileTask)
                        }
                    )
                }
                task.classpath.from(project.provider { LibraryArtifactResolver.resolveClasspath(project) })
            }
            checkTask.configure { task ->
                task.classDirectories.from(javaCompile.map { it.destinationDirectory })
                task.dependsOn(javaCompile)
                if (project.tasks.names.contains(kotlinTaskName)) {
                    val kotlinTask = project.tasks.named(kotlinTaskName)
                    task.dependsOn(kotlinTask)
                    task.classDirectories.from(
                        kotlinTask.map { compileTask ->
                            compileTask.javaClass.methods
                                .first { it.name == "getDestinationDirectory" }
                                .invoke(compileTask)
                        }
                    )
                }
                task.classpath.from(project.provider { LibraryArtifactResolver.resolveClasspath(project) })
            }
        }

        project.tasks.named("check").configure { check ->
            check.dependsOn(checkTask)
        }
    }
}
