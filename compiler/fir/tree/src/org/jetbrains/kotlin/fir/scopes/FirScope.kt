/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.scopes

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.substitution.ConeSubstitutor
import org.jetbrains.kotlin.fir.symbols.impl.*
import org.jetbrains.kotlin.name.Name

@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
annotation class DelicateScopeAPI

abstract class FirScope {
    open fun processClassifiersByNameWithSubstitution(
        name: Name,
        processor: (FirClassifierSymbol<*>, ConeSubstitutor) -> Unit
    ) {
    }

    open fun processFunctionsByName(
        name: Name,
        processor: (FirNamedFunctionSymbol) -> Unit
    ) {
    }

    open fun processPropertiesByName(
        name: Name,
        processor: (FirVariableSymbol<*>) -> Unit
    ) {
    }

    open fun processDeclaredConstructors(
        processor: (FirConstructorSymbol) -> Unit
    ) {
    }

    open fun mayContainName(name: Name): Boolean = true

    open val scopeOwnerLookupNames: List<String> get() = emptyList()

    /**
     * This function creates a copy of the scope in case if this scope is session-dependant
     * It shouldn't be used anywhere except Analysis API, as in the compiler there cannot be
     *   a situation when session `A` may observe the session-dependent scope from session `B`
     *
     * @return null if the scope is session-independent, otherwise the created copy
     *
     * Note that this function doesn't check that [newSession] is actually different from the one stored in scope
     */
    @DelicateScopeAPI
    abstract fun withReplacedSessionOrNull(newSession: FirSession, newScopeSession: ScopeSession): FirScope?
}

fun FirScope.getSingleClassifier(name: Name): FirClassifierSymbol<*>? = mutableSetOf<FirClassifierSymbol<*>>().apply {
    processClassifiersByName(name, this::add)
}.singleOrNull()

fun FirScope.getClassifiers(name: Name): List<FirClassifierSymbol<*>> = mutableListOf<FirClassifierSymbol<*>>().apply {
    processClassifiersByName(name, this::add)
}

fun FirScope.getFunctions(name: Name): List<FirNamedFunctionSymbol> = mutableListOf<FirNamedFunctionSymbol>().apply {
    processFunctionsByName(name, this::add)
}

fun FirScope.getProperties(name: Name): List<FirVariableSymbol<*>> = mutableListOf<FirVariableSymbol<*>>().apply {
    processPropertiesByName(name, this::add)
}

fun FirScope.getDeclaredConstructors(): List<FirConstructorSymbol> = mutableListOf<FirConstructorSymbol>().apply {
    processDeclaredConstructors(this::add)
}

@ScopeFunctionRequiresPrewarm
fun FirTypeScope.processOverriddenFunctionsAndSelf(
    functionSymbol: FirNamedFunctionSymbol,
    processor: (FirNamedFunctionSymbol) -> ProcessorAction
): ProcessorAction {
    if (!processor(functionSymbol)) return ProcessorAction.STOP

    return processOverriddenFunctions(functionSymbol, processor = processor)
}

@ScopeFunctionRequiresPrewarm
fun List<FirTypeScope>.processOverriddenPropertiesAndSelf(
    propertySymbol: FirPropertySymbol,
    processor: (FirPropertySymbol) -> ProcessorAction
) {
    if (!processor(propertySymbol)) return
    processOverriddenProperties(propertySymbol, processor)
}

enum class ProcessorAction {
    STOP,
    NEXT,
    NONE;

    operator fun not(): Boolean {
        return when (this) {
            STOP -> true
            NEXT -> false
            NONE -> false
        }
    }

    fun stop(): Boolean = this == STOP
    fun next(): Boolean = this != STOP

    operator fun plus(other: ProcessorAction): ProcessorAction {
        if (this == NEXT || other == NEXT) return NEXT
        return this
    }
}

@Suppress("NOTHING_TO_INLINE")
inline fun FirScope.processClassifiersByName(
    name: Name,
    noinline processor: (FirClassifierSymbol<*>) -> Unit
) {
    processClassifiersByNameWithSubstitution(name) { symbol, _ -> processor(symbol) }
}
