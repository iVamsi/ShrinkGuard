package com.shrinkguard.gradle.internal

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.api.variant.LibraryVariant
import com.shrinkguard.gradle.tasks.ShrinkCheckTask
import com.shrinkguard.gradle.tasks.ShrinkReportTask
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile

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
        val components = project.extensions.getByType(LibraryAndroidComponentsExtension::class.java)
        components.onVariants(components.selector().withBuildType("release")) { variant ->
            attachClasses(project, reportTask, variant)
            attachClasses(project, checkTask, variant)
        }

        project.tasks.named("check").configure { check ->
            check.dependsOn(checkTask)
        }
    }

    private fun attachClasses(
        project: Project,
        taskProvider: TaskProvider<*>,
        variant: LibraryVariant
    ) {
        val javaTaskName = variant.computeTaskName("compile", "JavaWithJavac")
        val kotlinTaskName = variant.computeTaskName("compile", "Kotlin")
        // ponytail: afterEvaluate because AGP 9 registers compile tasks after onVariants.
        // Move this into onVariants once those tasks exist there.
        project.afterEvaluate {
            val javaCompile = project.tasks.named(javaTaskName, JavaCompile::class.java)
            taskProvider.configure { task ->
                val classes = classesOf(task) ?: return@configure
                val classpath = classpathOf(task) ?: return@configure
                task.dependsOn(javaCompile)
                classes.from(javaCompile.map { it.destinationDirectory })
                project.tasks.findByName(kotlinTaskName)?.let { kotlinTask ->
                    task.dependsOn(kotlinTask)
                    classes.from(
                        project.tasks.named(kotlinTaskName).map { compileTask ->
                            compileTask.javaClass.methods
                                .first { it.name == "getDestinationDirectory" }
                                .invoke(compileTask)
                        }
                    )
                }
                classpath.from(project.provider { LibraryArtifactResolver.resolveClasspath(project) })
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
