/*
 * Copyright 2010-2025 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.analysis.api.impl.base.projectStructure

import com.intellij.openapi.project.Project
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.analysis.api.KaPlatformInterface
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScope
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaResolutionScopeProvider
import org.jetbrains.kotlin.analysis.api.platform.projectStructure.KaGlobalSearchScopeMerger
import org.jetbrains.kotlin.analysis.api.projectStructure.KaBuiltinsModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaLibrarySourceModule
import org.jetbrains.kotlin.analysis.api.projectStructure.KaModule
import org.jetbrains.kotlin.analysis.decompiler.psi.BuiltinsVirtualFileProvider

class KaBaseResolutionScopeProvider : KaResolutionScopeProvider {
    override fun getResolutionScope(module: KaModule): KaResolutionScope {
        val moduleWithDependentScopes = getModuleAndDependenciesContentScopes(module)
        return KaBaseResolutionScope(
            module,
            KaGlobalSearchScopeMerger.getInstance(module.project)
                .union(moduleWithDependentScopes)
        )
    }

    @OptIn(KaPlatformInterface::class)
    private fun getModuleAndDependenciesContentScopes(module: KaModule): List<GlobalSearchScope> {
        val modules = buildSet {
            add(module)
            addAll(module.directRegularDependencies)
            addAll(module.directFriendDependencies)
            addAll(module.transitiveDependsOnDependencies)
            if (module is KaLibrarySourceModule) {
                add(module.binaryLibrary)
            }
        }

        return buildList {
            modules.mapTo(this) { it.contentScope }
            if (modules.none { it is KaBuiltinsModule }) {
                // `KaBuiltinsModule` is a module containing builtins declarations for the target platform.
                // It is never a dependency of any `KaModule`,
                // builtins are registered on a symbol provider level during session creation.
                // That's why its scope has to be added manually here.
                add(createBuiltinsScope(module.project))
            }
        }
    }

    private fun createBuiltinsScope(project: Project): GlobalSearchScope {
        return BuiltinsVirtualFileProvider.getInstance().createBuiltinsScope(project)
    }
}
