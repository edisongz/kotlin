/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
@file:Suppress("DEPRECATION")

package org.jetbrains.kotlin.gradle.targets.native.tasks.artifact

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.ExtensionAware
import org.gradle.language.base.plugins.LifecycleBasePlugin
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.plugin.mpp.BITCODE_EMBEDDING_DEPRECATION_MESSAGE
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeOutputKind
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.AppleTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFrameworkTaskHolder
import org.jetbrains.kotlin.gradle.tasks.FrameworkDescriptor
import org.jetbrains.kotlin.gradle.tasks.dependsOn
import org.jetbrains.kotlin.gradle.tasks.registerTask
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName
import org.jetbrains.kotlin.konan.target.KonanTarget
import javax.inject.Inject

abstract class KotlinNativeXCFrameworkConfigImpl @Inject constructor(artifactName: String) :
    KotlinNativeArtifactConfigImpl(artifactName), KotlinNativeXCFrameworkConfig {
    override var targets: Set<KonanTarget> = emptySet()
    override fun targets(vararg targets: KonanTarget) {
        this.targets = targets.toSet()
    }

    @Suppress("DEPRECATION_ERROR")
    @Deprecated(BITCODE_EMBEDDING_DEPRECATION_MESSAGE, level = DeprecationLevel.ERROR)
    override var embedBitcode: org.jetbrains.kotlin.gradle.plugin.mpp.BitcodeEmbeddingMode? = null

    override fun validate() {
        super.validate()
        val kind = NativeOutputKind.FRAMEWORK
        check(targets.isNotEmpty()) {
            "Native artifact '$artifactName' wasn't configured because it requires at least one target"
        }
        val wrongTarget = targets.firstOrNull { !kind.availableFor(it) }
        check(wrongTarget == null) {
            "Native artifact '$artifactName' wasn't configured because ${kind.description} is not available for ${wrongTarget!!.visibleName}"
        }
    }

    override fun createArtifact(extensions: ExtensionAware): KotlinNativeXCFrameworkImpl {
        validate()
        return KotlinNativeXCFrameworkImpl(
            artifactName = artifactName,
            modules = modules,
            modes = modes,
            isStatic = isStatic,
            linkerOptions = linkerOptions,
            kotlinOptionsFn = kotlinOptionsFn,
            toolOptionsConfigure = toolOptionsConfigure,
            binaryOptions = binaryOptions,
            targets = targets,
            extensions = extensions
        )
    }
}

@Deprecated(KotlinArtifactsExtension.KOTLIN_NATIVE_ARTIFACTS_DEPRECATION)
class KotlinNativeXCFrameworkImpl(
    override val artifactName: String,
    override val modules: Set<Any>,
    override val modes: Set<NativeBuildType>,
    override val isStatic: Boolean,
    override val linkerOptions: List<String>,
    @Suppress("DEPRECATION_ERROR")
    @Deprecated(
        message = "Please migrate to toolOptionsConfigure DSL. More details are here: https://kotl.in/u1r8ln",
        level = DeprecationLevel.ERROR,
    )
    override val kotlinOptionsFn: KotlinCommonToolOptions.() -> Unit,
    override val toolOptionsConfigure: KotlinCommonCompilerToolOptions.() -> Unit,
    override val binaryOptions: Map<String, String>,
    override val targets: Set<KonanTarget>,
    @Suppress("DEPRECATION_ERROR")
    @Deprecated(BITCODE_EMBEDDING_DEPRECATION_MESSAGE, level = DeprecationLevel.ERROR)
    override val embedBitcode: org.jetbrains.kotlin.gradle.plugin.mpp.BitcodeEmbeddingMode? = null,
    extensions: ExtensionAware
) : KotlinNativeXCFramework, ExtensionAware by extensions {
    override fun getName() = lowerCamelCaseName(artifactName, "XCFramework")
    override val taskName = lowerCamelCaseName("assemble", name)
    override val outDir: String
        get() = "out/xcframework"

    override fun registerAssembleTask(project: Project) {
        val parentTask = project.registerTask<Task>(taskName) {
            it.group = "build"
            it.description = "Assemble all types of registered '$artifactName' XCFramework"
        }
        project.tasks.named(LifecycleBasePlugin.ASSEMBLE_TASK_NAME).dependsOn(parentTask)

        modes.forEach { buildType ->
            val holder = XCFrameworkTaskHolder.create(project, artifactName, buildType).also {
                parentTask.dependsOn(it.task)
            }

            val nameSuffix = "ForXCF"
            val frameworkDescriptors: List<FrameworkDescriptor> = targets.map { target ->
                val librariesConfigurationName = project.registerLibsDependencies(target, artifactName + nameSuffix, modules)
                val exportConfigurationName = project.registerExportDependencies(target, artifactName + nameSuffix, modules)
                val targetTask = registerLinkFrameworkTask(
                    project = project,
                    name = artifactName,
                    target = target,
                    buildType = buildType,
                    librariesConfigurationName = librariesConfigurationName,
                    exportConfigurationName = exportConfigurationName,
                    outDirName = "${artifactName}XCFrameworkTemp",
                    taskNameSuffix = nameSuffix
                )
                holder.task.dependsOn(targetTask)
                val frameworkFileProvider = targetTask.flatMap { it.outputFile }
                val descriptor = FrameworkDescriptor(frameworkFileProvider.get(), isStatic, target)

                val group = AppleTarget.values().firstOrNull { it.targets.contains(target) }
                holder.fatTasks[group]?.configure { fatTask ->
                    fatTask.baseName = artifactName
                    fatTask.fromFrameworkDescriptors(listOf(descriptor))
                    fatTask.dependsOn(targetTask)
                }
                descriptor
            }
            holder.task.configure {
                it.fromFrameworkDescriptors(frameworkDescriptors)
                it.outputDir = project.layout.buildDirectory.dir(outDir).get().asFile
            }
        }
    }
}