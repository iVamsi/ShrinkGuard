package com.shrinkguard.gradle

import com.shrinkguard.gradle.internal.LibraryArtifactResolver
import com.shrinkguard.gradle.tasks.ShrinkCheckTask
import com.shrinkguard.gradle.tasks.ShrinkReportTask
import org.gradle.api.Plugin
import org.gradle.api.Project

class ShrinkGuardPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val extension = project.extensions.create("shrinkGuard", ShrinkGuardExtension::class.java)
        extension.baselineFile.convention(project.layout.projectDirectory.file("shrink-report.txt"))

        val shrinkReportTask = project.tasks.register("shrinkReport", ShrinkReportTask::class.java) { task ->
            task.baselineFile.set(extension.baselineFile)
            task.failOnToxicFlags.set(extension.ruleLint.failOnToxicFlags)
            task.failOnOverbroadRules.set(extension.ruleLint.failOnOverbroadRules)
            task.allowlistRules.set(extension.ruleLint.allowlistRules)

            task.rulesFiles.setFrom(project.provider {
                if (!extension.rulesFiles.isEmpty) {
                    extension.rulesFiles.files
                } else {
                    LibraryArtifactResolver.findConsumerRules(project)
                }
            })
            task.classDirectories.setFrom(project.provider {
                LibraryArtifactResolver.findClassDirectories(project)
            })
            task.classpath.setFrom(project.provider {
                LibraryArtifactResolver.resolveClasspath(project)
            })
        }

        val shrinkCheckTask = project.tasks.register("shrinkCheck", ShrinkCheckTask::class.java) { task ->
            task.baselineFile.set(extension.baselineFile)
            task.reportOutputFile.set(project.layout.buildDirectory.file("reports/shrinkguard/shrink-report.txt"))
            task.failOnToxicFlags.set(extension.ruleLint.failOnToxicFlags)
            task.failOnOverbroadRules.set(extension.ruleLint.failOnOverbroadRules)
            task.allowlistRules.set(extension.ruleLint.allowlistRules)

            task.rulesFiles.setFrom(project.provider {
                if (!extension.rulesFiles.isEmpty) {
                    extension.rulesFiles.files
                } else {
                    LibraryArtifactResolver.findConsumerRules(project)
                }
            })
            task.classDirectories.setFrom(project.provider {
                LibraryArtifactResolver.findClassDirectories(project)
            })
            task.classpath.setFrom(project.provider {
                LibraryArtifactResolver.resolveClasspath(project)
            })
        }

        // Configure task dependencies after evaluation
        project.afterEvaluate {
            val compileTasks = project.tasks.matching { task ->
                task.name in listOf(
                    "compileKotlin",
                    "compileJava",
                    "compileReleaseJavaWithJavac",
                    "compileReleaseKotlin",
                    "compileDebugJavaWithJavac",
                    "compileDebugKotlin",
                    "classes"
                )
            }
            shrinkReportTask.configure { task -> task.dependsOn(compileTasks) }
            shrinkCheckTask.configure { task -> task.dependsOn(compileTasks) }

            if (project.tasks.findByName("check") != null) {
                project.tasks.named("check") { checkTask ->
                    checkTask.dependsOn(shrinkCheckTask)
                }
            }
        }
    }
}
