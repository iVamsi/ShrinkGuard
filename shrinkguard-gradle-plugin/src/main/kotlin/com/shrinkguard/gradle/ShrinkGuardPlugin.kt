package com.shrinkguard.gradle

import com.shrinkguard.gradle.internal.LibraryArtifactResolver
import com.shrinkguard.gradle.tasks.ShrinkCheckTask
import com.shrinkguard.gradle.tasks.ShrinkReportTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskProvider

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
        project.pluginManager.withPlugin("com.android.library") {
            val wiring = Class.forName("com.shrinkguard.gradle.internal.AndroidLibraryWiring")
            val configure = wiring.methods.first { it.name == "configure" }
            configure.invoke(null, project, shrinkReportTask, shrinkCheckTask)
        }
    }

    private fun wireCommonInputs(
        project: Project,
        extension: ShrinkGuardExtension,
        task: org.gradle.api.Task
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
        project.tasks.named("check").configure { check ->
            check.dependsOn(checkTask)
        }
    }
}
