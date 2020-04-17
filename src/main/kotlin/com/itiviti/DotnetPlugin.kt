package com.itiviti

import com.itiviti.extensions.*
import com.itiviti.tasks.DotnetBuildTask
import com.itiviti.tasks.DotnetCleanTask
import com.itiviti.tasks.DotnetNugetPushTask
import com.itiviti.tasks.DotnetTestTask
import groovy.json.JsonSlurper
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.ExtensionAware
import org.gradle.api.publish.plugins.PublishingPlugin
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.reflections.Reflections
import org.reflections.scanners.ResourcesScanner
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.regex.Pattern

class DotnetPlugin: Plugin<Project> {
    companion object {
        const val TASK_GROUP = "dotnet"
    }

    override fun apply(project: Project) {
        project.plugins.apply(LifecycleBasePlugin::class.java)
        project.plugins.apply(PublishingPlugin::class.java)

        val extension = project.extensions.create("dotnet", DotnetPluginExtension::class.java, project.name)
        val extensionAware = extension as ExtensionAware
        val restoreExtension = extensionAware.extensions.create("restore", DotnetRestoreExtension::class.java)
        extensionAware.extensions.create("build", DotnetBuildExtension::class.java, project.version)
        val testExtension = extensionAware.extensions.create("test", DotnetTestExtension::class.java, project.buildDir)
        (testExtension as ExtensionAware).extensions.create("nunit", DotnetNUnitExtension::class.java, project.buildDir)
        extensionAware.extensions.create("nugetPush", DotnetNugetPushExtension::class.java)

        project.afterEvaluate {

            if (!restoreExtension.beforeBuild) {
                it.logger.lifecycle("Start restoring packages")

                if (restore(it, extension, restoreExtension) != 0) {
                    throw GradleException("dotnet restore fails")
                }

                it.logger.lifecycle("Complete restoring packages")
            }

            it.logger.lifecycle("Start parsing project")

            parseProjects(project, extension)

            if (extension.preReleaseCheck) {
                it.logger.lifecycle("Check pre-release references")

                val validated = extension.allProjects.values.all { dotnetProject ->
                    val preReleases = dotnetProject.getPackageReferences().filter { ref -> ref.version?.contains("-") == true }
                    if (preReleases.isNotEmpty()) {
                        project.logger.error("Pre-release references detected in ${dotnetProject.getProjectName()}")
                        preReleases.forEach { ref ->
                            project.logger.error("    * ${ref.name}: ${ref.version}")
                        }
                    }
                    preReleases.isEmpty()
                }
                if (!validated) {
                    throw GradleException("Aborting build due to pre-release references detected.")
                }
            }

            it.logger.lifecycle("Complete parsing project")
        }

        project.tasks.register("dotnetClean", DotnetCleanTask::class.java) {
            with(it) {
                group = TASK_GROUP
                description = "Cleans the output of a project."
                project.tasks.findByName(LifecycleBasePlugin.CLEAN_TASK_NAME)?.dependsOn(it)
            }
        }

        val dotnetBuild = project.tasks.register("dotnetBuild", DotnetBuildTask::class.java) {
            with(it) {
                group = TASK_GROUP
                description = "Builds a project and all of its dependencies."
                project.tasks.findByName(LifecycleBasePlugin.ASSEMBLE_TASK_NAME)?.dependsOn(it)
            }
        }

        project.tasks.register("dotnetTest", DotnetTestTask::class.java) {
            with(it) {
                group = TASK_GROUP
                description = ".NET test driver used to execute unit tests."
                mustRunAfter(dotnetBuild)
                project.tasks.findByName(LifecycleBasePlugin.BUILD_TASK_NAME)?.dependsOn(it)
            }
        }

        project.tasks.register("dotnetNugetPush", DotnetNugetPushTask::class.java) {
            with(it) {
                group = TASK_GROUP
                description = "Push to nuget feed."
                project.tasks.findByName(PublishingPlugin.PUBLISH_LIFECYCLE_TASK_NAME)?.dependsOn(it)
                mustRunAfter(dotnetBuild)
            }
        }
    }

    private fun restore(project: Project, extension: DotnetPluginExtension, restoreExtension: DotnetRestoreExtension): Int {
        return project.exec { exec ->
            exec.commandLine(extension.dotnetExecutable)
            exec.workingDir(extension.workingDir)
            exec.args("restore")
            if (!extension.solution.isNullOrBlank()) {
                exec.args(extension.solution!!)
            }
            exec.args("--verbosity", extension.verbosity)
            DotnetBuildTask.restoreArgs(restoreExtension, exec)
        }.exitValue
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseProjects(project: Project, extension: DotnetPluginExtension) {
        var targetFile: File?
        if (extension.solution.isNullOrBlank()) {
            // guess the solution similar to dotnet cli, i.e. searches the current working directory for a file that has a file extension that ends in either proj or sln and uses that file.
            targetFile = extension.workingDir.listFiles{ file -> file.length() > 0 && file.name.endsWith(".sln", true)}?.firstOrNull()
            if (targetFile == null) {
                targetFile = extension.workingDir.listFiles{ file -> file.length() > 0 && file.name.endsWith("proj", true)}?.firstOrNull()
            }
        } else {
            targetFile = File(extension.workingDir, extension.solution!!)
        }

        if (targetFile?.exists() != true) {
            throw GradleException("Cannot find a valid project file, please setup workingDir / solution correctly")
        }

        val tempDir = Files.createDirectories(File(project.buildDir, "tmp/dotnet").toPath())

        project.logger.info("  Extracting parser to {}", tempDir)

        val reflections = Reflections(ConfigurationBuilder().setScanners(ResourcesScanner()).setUrls(ClasspathHelper.forPackage("com.itiviti.parser")))
        reflections.getResources(Pattern.compile(".*\\.cs(proj)?")).forEach {
            Files.copy(javaClass.classLoader.getResourceAsStream(it)!!, tempDir.resolve(File(it).name), StandardCopyOption.REPLACE_EXISTING)
        }

        val arg = "{${if (!extension.platform.isNullOrBlank()) "'Platform':'${extension.platform}'," else ""}'Configuration':'${extension.configuration}'}"
        val outputStream = ByteArrayOutputStream()
        val parser = project.exec { exec ->
            exec.commandLine(extension.dotnetExecutable)
            exec.workingDir(tempDir.toFile())
            exec.args("run", "--", targetFile.absolutePath, arg)
            exec.standardOutput = outputStream
        }

        if (parser.exitValue != 0) {
            throw GradleException("Failed to parse project.")
        }

        val processOutput = outputStream.toString()
        val result = JsonSlurper().parseText(processOutput.substring(processOutput.indexOf('{'))) as MutableMap<String, Any>

        extension.allProjects = result.map { entry -> entry.key to DotnetProject(entry.value as Map<String, Any>) }.toMap()
    }
}
