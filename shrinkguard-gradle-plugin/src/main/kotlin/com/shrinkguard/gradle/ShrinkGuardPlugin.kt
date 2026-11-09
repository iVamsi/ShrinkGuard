package com.shrinkguard.gradle

import com.shrinkguard.gradle.internal.LibraryArtifactResolver
import com.shrinkguard.gradle.tasks.ShrinkCheckTask
import com.shrinkguard.gradle.tasks.ShrinkReportTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.plugins.AppliedPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider
import java.util.concurrent.atomic.AtomicBoolean

class ShrinkGuardPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("shrinkGuard", ShrinkGuardExtension::class.java)
        extension.baselineFile.convention(project.layout.projectDirectory.file("shrink-report.txt"))

        val shrinkReportTask = project.tasks.register("shrinkReport", ShrinkReportTask::class.java) { task ->
            task.baselineFile.set(extension.baselineFile)
            task.libraryName.set(project.name)
            task.workingDirectory.set(project.layout.buildDirectory.dir("shrinkguard/work"))
            task.failOnToxicFlags.set(extension.ruleLint.failOnToxicFlags)
            task.failOnOverbroadRules.set(extension.ruleLint.failOnOverbroadRules)
            task.allowlistRules.set(extension.ruleLint.allowlistRules)
            wireCommonInputs(project, extension, task)
        }

        val shrinkCheckTask = project.tasks.register("shrinkCheck", ShrinkCheckTask::class.java) { task ->
            task.mustRunAfter(shrinkReportTask)
            task.baselineFile.set(extension.baselineFile)
            task.reportOutputFile.set(project.layout.buildDirectory.file("reports/shrinkguard/shrink-report.txt"))
            task.libraryName.set(project.name)
            task.workingDirectory.set(project.layout.buildDirectory.dir("shrinkguard/check-work"))
            task.failOnToxicFlags.set(extension.ruleLint.failOnToxicFlags)
            task.failOnOverbroadRules.set(extension.ruleLint.failOnOverbroadRules)
            task.allowlistRules.set(extension.ruleLint.allowlistRules)
            wireCommonInputs(project, extension, task)
        }

        project.pluginManager.withPlugin("java") {
            wireJava(project, shrinkReportTask, shrinkCheckTask)
        }

        val androidWired = AtomicBoolean(false)
        val wireAndroid = { _: AppliedPlugin ->
            if (androidWired.compareAndSet(false, true)) {
                val wiring = Class.forName("com.shrinkguard.gradle.internal.AndroidLibraryWiring")
                val configure = wiring.methods.first { it.name == "configure" }
                configure.invoke(null, project, shrinkReportTask, shrinkCheckTask)
            }
        }
        project.pluginManager.withPlugin("com.android.library", wireAndroid)
        project.pluginManager.withPlugin("com.android.application", wireAndroid)
        project.pluginManager.withPlugin("com.android.dynamic-feature", wireAndroid)

        val kmpWired = AtomicBoolean(false)
        val wireKmp = { _: AppliedPlugin ->
            if (kmpWired.compareAndSet(false, true)) {
                wireKotlinMultiplatform(project, shrinkReportTask, shrinkCheckTask)
            }
        }
        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform", wireKmp)
        project.pluginManager.withPlugin("com.android.kotlin.multiplatform.library", wireKmp)
    }

    private fun wireCommonInputs(
        project: Project,
        extension: ShrinkGuardExtension,
        task: Task
    ) {
        when (task) {
            is ShrinkReportTask -> {
                task.rulesFiles.setFrom(project.provider {
                    if (!extension.rulesFiles.isEmpty) {
                        extension.rulesFiles.files
                    } else {
                        LibraryArtifactResolver.findConsumerRules(project)
                    }
                })
                task.classpath.setFrom(project.provider {
                    LibraryArtifactResolver.resolveClasspath(project)
                })
            }
            is ShrinkCheckTask -> {
                task.rulesFiles.setFrom(project.provider {
                    if (!extension.rulesFiles.isEmpty) {
                        extension.rulesFiles.files
                    } else {
                        LibraryArtifactResolver.findConsumerRules(project)
                    }
                })
                task.classpath.setFrom(project.provider {
                    LibraryArtifactResolver.resolveClasspath(project)
                })
            }
        }
    }

    private fun wireJava(
        project: Project,
        reportTask: TaskProvider<ShrinkReportTask>,
        checkTask: TaskProvider<ShrinkCheckTask>
    ) {
        val java = project.extensions.getByType(JavaPluginExtension::class.java)
        val main = java.sourceSets.getByName(SourceSet.MAIN_SOURCE_SET_NAME)
        reportTask.configure { task ->
            task.classDirectories.setFrom(main.output.classesDirs)
            task.dependsOn(main.classesTaskName)
        }
        checkTask.configure { task ->
            task.classDirectories.setFrom(main.output.classesDirs)
            task.dependsOn(main.classesTaskName)
        }
        project.tasks.matching { it.name == "check" }.configureEach { check ->
            check.dependsOn(checkTask)
        }
    }

    private fun wireKotlinMultiplatform(
        project: Project,
        reportTask: TaskProvider<ShrinkReportTask>,
        checkTask: TaskProvider<ShrinkCheckTask>
    ) {
        project.afterEvaluate {
            val handledByJavaOrAndroid = project.plugins.hasPlugin("java") ||
                project.plugins.hasPlugin("com.android.library") ||
                project.plugins.hasPlugin("com.android.application") ||
                project.plugins.hasPlugin("com.android.dynamic-feature")
            if (handledByJavaOrAndroid) return@afterEvaluate

            val compileTaskNames = when {
                "compileKotlinJvm" in project.tasks.names -> listOf("compileKotlinJvm", "compileJvmMainJava")
                "compileAndroidMain" in project.tasks.names -> listOf("compileAndroidMain")
                else -> emptyList()
            }.filter { it in project.tasks.names }

            if (compileTaskNames.isEmpty()) return@afterEvaluate

            for (taskProvider in listOf(reportTask, checkTask)) {
                taskProvider.configure { task ->
                    val classes = when (task) {
                        is ShrinkReportTask -> task.classDirectories
                        is ShrinkCheckTask -> task.classDirectories
                        else -> null
                    } ?: return@configure

                    for (compileTaskName in compileTaskNames) {
                        val compileTaskProvider = project.tasks.named(compileTaskName)
                        task.dependsOn(compileTaskProvider)
                        classes.from(
                            compileTaskProvider.map { compileTask ->
                                compileTask.javaClass.methods
                                    .firstOrNull { it.name == "getDestinationDirectory" && it.parameterCount == 0 }
                                    ?.invoke(compileTask)
                                    ?: project.files()
                            }
                        )
                    }
                }
            }

            project.tasks.matching { it.name == "check" }.configureEach { check ->
                check.dependsOn(checkTask)
            }
        }
    }
}
