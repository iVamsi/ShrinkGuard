package com.shrinkguard.gradle.internal

import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.api.variant.Variant
import com.shrinkguard.gradle.tasks.ShrinkCheckTask
import com.shrinkguard.gradle.tasks.ShrinkReportTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile

/**
 * Isolated so JVM TestKit runs never classload AGP. [ShrinkGuardPlugin] reflects into this
 * type only after an Android plugin (`com.android.library`, `com.android.application`, etc.) is applied.
 */
internal object AndroidLibraryWiring {

    @JvmStatic
    fun configure(
        project: Project,
        reportTask: TaskProvider<ShrinkReportTask>,
        checkTask: TaskProvider<ShrinkCheckTask>
    ) {
        val components = project.extensions.findByType(AndroidComponentsExtension::class.java) ?: return
        val variants = mutableListOf<Variant>()
        components.onVariants(components.selector().all()) { variant ->
            variants.add(variant)
        }

        // afterEvaluate because AGP 9 registers compile tasks after onVariants, and selecting a
        // single variant here avoids duplicate classes when product flavors are configured.
        project.afterEvaluate {
            val selectedVariant = variants.firstOrNull { it.buildType == "release" }
                ?: variants.firstOrNull()
                ?: return@afterEvaluate
            attachVariant(project, reportTask, selectedVariant)
            attachVariant(project, checkTask, selectedVariant)
        }

        project.tasks.matching { it.name == "check" }.configureEach { check ->
            check.dependsOn(checkTask)
        }
    }

    private fun attachVariant(
        project: Project,
        taskProvider: TaskProvider<*>,
        variant: Variant
    ) {
        val javaTaskName = variant.computeTaskName("compile", "JavaWithJavac")
        val kotlinTaskNames = listOf(
            variant.computeTaskName("compile", "Kotlin"),
            variant.computeTaskName("compile", "KotlinAndroid"),
        )

        taskProvider.configure { task ->
            val classes = classesOf(task) ?: return@configure
            val classpath = classpathOf(task) ?: return@configure

            if (javaTaskName in project.tasks.names) {
                val javaCompile = project.tasks.named(javaTaskName, JavaCompile::class.java)
                task.dependsOn(javaCompile)
                classes.from(javaCompile.map { it.destinationDirectory })
                classpath.setFrom(
                    javaCompile.map { it.classpath },
                    project.provider { LibraryArtifactResolver.resolveClasspath(project, variant.name) },
                )
            } else {
                classpath.setFrom(
                    project.provider { LibraryArtifactResolver.resolveClasspath(project, variant.name) }
                )
            }

            for (kotlinTaskName in kotlinTaskNames) {
                if (kotlinTaskName in project.tasks.names) {
                    val kotlinTask = project.tasks.named(kotlinTaskName)
                    task.dependsOn(kotlinTask)
                    classes.from(
                        kotlinTask.map { compileTask ->
                            compileTask.javaClass.methods
                                .firstOrNull { it.name == "getDestinationDirectory" && it.parameterCount == 0 }
                                ?.invoke(compileTask)
                                ?: project.files()
                        }
                    )
                }
            }
        }
    }

    private fun classesOf(task: Task): ConfigurableFileCollection? = when (task) {
        is ShrinkReportTask -> task.classDirectories
        is ShrinkCheckTask -> task.classDirectories
        else -> null
    }

    private fun classpathOf(task: Task): ConfigurableFileCollection? = when (task) {
        is ShrinkReportTask -> task.classpath
        is ShrinkCheckTask -> task.classpath
        else -> null
    }
}
