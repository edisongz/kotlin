/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */
@file:Suppress("DEPRECATION")

package org.jetbrains.kotlin.gradle.targets.native.tasks.artifact

import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.attributes.Usage
import org.jetbrains.kotlin.gradle.dsl.*
import org.jetbrains.kotlin.gradle.internal.attributes.setAttributeTo
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.PropertiesProvider.Companion.kotlinPropertiesProvider
import org.jetbrains.kotlin.gradle.plugin.attributes.KlibPackaging
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinUsages
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.utils.lowerCamelCaseName
import org.jetbrains.kotlin.gradle.utils.maybeCreateResolvable
import org.jetbrains.kotlin.gradle.utils.named
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.jetbrains.kotlin.konan.target.presetName

abstract class KotlinArtifactConfigImpl(
    override val artifactName: String
) : KotlinArtifactConfig {
    override val modules = mutableSetOf<Any>()
    override fun setModules(vararg project: Any) {
        modules.clear()
        modules.addAll(project)
    }

    override fun addModule(project: Any) {
        modules.add(project)
    }

    protected open fun validate() {
        check(modules.isNotEmpty()) {
            "Native artifact '$artifactName' wasn't configured because it requires at least one module for linking"
        }
    }
}

abstract class KotlinNativeArtifactConfigImpl(artifactName: String) : KotlinArtifactConfigImpl(artifactName), KotlinNativeArtifactConfig {
    override var modes: Set<NativeBuildType> = NativeBuildType.DEFAULT_BUILD_TYPES
    override fun modes(vararg modes: NativeBuildType) {
        this.modes = modes.toSet()
    }

    override var isStatic: Boolean = false
    override var linkerOptions: List<String> = emptyList()

    internal var toolOptionsConfigure: KotlinCommonCompilerToolOptions.() -> Unit = {}
    override fun toolOptions(configure: Action<KotlinCommonCompilerToolOptions>) {
        toolOptionsConfigure = configure::execute
    }

    @Suppress("DEPRECATION_ERROR")
    internal var kotlinOptionsFn: KotlinCommonToolOptions.() -> Unit = {}

    @Deprecated(
        message = KOTLIN_OPTIONS_AS_TOOLS_DEPRECATION_MESSAGE,
        level = DeprecationLevel.ERROR,
    )
    override fun kotlinOptions(
        @Suppress("DEPRECATION_ERROR") fn: Action<KotlinCommonToolOptions>
    ) {
        kotlinOptionsFn = fn::execute
    }

    internal val binaryOptions: MutableMap<String, String> = mutableMapOf()
    override fun binaryOption(name: String, value: String) {
        binaryOptions[name] = value
    }

    override fun validate() {
        super.validate()
        check(modes.isNotEmpty()) {
            "Native artifact '$artifactName' wasn't configured because it requires at least one build type in modes"
        }
    }
}

internal fun Project.registerLibsDependencies(target: KonanTarget, artifactName: String, deps: Set<Any>): String {
    val librariesConfigurationName = lowerCamelCaseName(target.presetName, artifactName, "linkLibrary")
    configurations.maybeCreateResolvable(librariesConfigurationName).apply {
        isVisible = false
        isTransitive = true
        configureAttributesFor(project, target)
    }
    deps.forEach { dependencies.add(librariesConfigurationName, it) }
    return librariesConfigurationName
}

internal fun Project.registerExportDependencies(target: KonanTarget, artifactName: String, deps: Set<Any>): String {
    val exportConfigurationName = lowerCamelCaseName(target.presetName, artifactName, "linkExport")
    configurations.maybeCreateResolvable(exportConfigurationName).apply {
        isVisible = false
        isTransitive = false
        configureAttributesFor(project, target)
    }
    deps.forEach { dependencies.add(exportConfigurationName, it) }
    return exportConfigurationName
}

private fun Configuration.configureAttributesFor(project: Project, target: KonanTarget) = with(project) {
    attributes.attribute(KotlinPlatformType.attribute, KotlinPlatformType.native)
    attributes.attribute(KotlinNativeTarget.konanTargetAttribute, target.name)
    attributes.attribute(Usage.USAGE_ATTRIBUTE, project.objects.named(KotlinUsages.KOTLIN_API))
    if (kotlinPropertiesProvider.useNonPackedKlibs) {
        KlibPackaging.setAttributeTo(project, attributes, false)
    }
}