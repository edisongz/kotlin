/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.resolve.calls.inference.components

import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.resolve.calls.inference.ForkPointData
import org.jetbrains.kotlin.resolve.calls.inference.components.ConstraintSystemCompletionMode.PARTIAL
import org.jetbrains.kotlin.resolve.calls.inference.hasRecursiveTypeParametersWithGivenSelfType
import org.jetbrains.kotlin.resolve.calls.inference.isRecursiveTypeParameter
import org.jetbrains.kotlin.resolve.calls.inference.model.Constraint
import org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintKind
import org.jetbrains.kotlin.resolve.calls.inference.model.DeclaredUpperBoundConstraintPosition
import org.jetbrains.kotlin.resolve.calls.inference.model.IncorporationConstraintPosition
import org.jetbrains.kotlin.resolve.calls.inference.model.VariableWithConstraints
import org.jetbrains.kotlin.resolve.calls.model.PostponedResolvedAtomMarker
import org.jetbrains.kotlin.types.model.KotlinTypeMarker
import org.jetbrains.kotlin.types.model.TypeConstructorMarker
import org.jetbrains.kotlin.types.model.TypeSystemInferenceExtensionContext
import org.jetbrains.kotlin.types.model.TypeVariableMarker

class VariableFixationFinder(
    private val trivialConstraintTypeInferenceOracle: TrivialConstraintTypeInferenceOracle,
    private val languageVersionSettings: LanguageVersionSettings,
) {
    interface Context : TypeSystemInferenceExtensionContext {
        val notFixedTypeVariables: Map<TypeConstructorMarker, VariableWithConstraints>
        val fixedTypeVariables: Map<TypeConstructorMarker, KotlinTypeMarker>
        val postponedTypeVariables: List<TypeVariableMarker>
        val constraintsFromAllForkPoints: MutableList<Pair<IncorporationConstraintPosition, ForkPointData>>
        val allTypeVariables: Map<TypeConstructorMarker, TypeVariableMarker>

        /**
         * See [org.jetbrains.kotlin.resolve.calls.inference.model.ConstraintStorage.outerSystemVariablesPrefixSize]
         */
        val outerSystemVariablesPrefixSize: Int

        val outerTypeVariables: Set<TypeConstructorMarker>?
            get() =
                when {
                    outerSystemVariablesPrefixSize > 0 -> allTypeVariables.keys.take(outerSystemVariablesPrefixSize).toSet()
                    else -> null
                }

        /**
         * If not null, that property means that we should assume temporary them all as proper types when fixating some variables.
         *
         * By default, if that property is null, we assume all `allTypeVariables` as not proper.
         *
         * Currently, that is only used for `provideDelegate` resolution, see
         * [org.jetbrains.kotlin.fir.resolve.transformers.body.resolve.FirDeclarationsResolveTransformer.fixInnerVariablesForProvideDelegateIfNeeded]
         */
        val typeVariablesThatAreCountedAsProperTypes: Set<TypeConstructorMarker>?

        fun isReified(variable: TypeVariableMarker): Boolean
    }

    class VariableForFixation(
        val variable: TypeConstructorMarker,
        private val hasProperConstraint: Boolean,
        private val hasDependencyOnOuterTypeVariable: Boolean = false,
    ) {
        val isReady: Boolean get() = hasProperConstraint && !hasDependencyOnOuterTypeVariable
    }

    fun findFirstVariableForFixation(
        c: Context,
        allTypeVariables: List<TypeConstructorMarker>,
        postponedKtPrimitives: List<PostponedResolvedAtomMarker>,
        completionMode: ConstraintSystemCompletionMode,
        topLevelType: KotlinTypeMarker,
    ): VariableForFixation? =
        c.findTypeVariableForFixation(allTypeVariables, postponedKtPrimitives, completionMode, topLevelType)

    enum class TypeVariableFixationReadiness {
        FORBIDDEN,
        WITHOUT_PROPER_ARGUMENT_CONSTRAINT, // proper constraint from arguments -- not from upper bound for type parameters
        OUTER_TYPE_VARIABLE_DEPENDENCY, // PCLA-only readiness

        // This is used for self-type-based bounds and deprioritized in 1.5+.
        // 2.2+ uses this kind of readiness for reified type parameters only, otherwise
        // READY_FOR_FIXATION_CAPTURED_UPPER is in use
        READY_FOR_FIXATION_DECLARED_UPPER_BOUND_WITH_SELF_TYPES,

        WITH_COMPLEX_DEPENDENCY, // if type variable T has constraint with non fixed type variable inside (non-top-level): T <: Foo<S>
        WITH_COMPLEX_DEPENDENCY_BUT_PROPER_EQUALITY_CONSTRAINT, // Same as before but also has a constraint T = ... not dependent on others
        ALL_CONSTRAINTS_TRIVIAL_OR_NON_PROPER, // proper trivial constraint from arguments, Nothing <: T
        RELATED_TO_ANY_OUTPUT_TYPE,
        FROM_INCORPORATION_OF_DECLARED_UPPER_BOUND,

        // We prefer LOWER T >: SomeRegularType to UPPER T <: SomeRegularType, KT-41934 is the only reason known
        READY_FOR_FIXATION_UPPER,
        READY_FOR_FIXATION_LOWER,

        // Currently used in 2.2+ ONLY for self-type based declared upper bounds
        // Captured types are difficult to manipulate, so with T <: Captured(...)
        // it's better to fix T earlier than T >: SomeRegularType / T <: SomeRegularType
        // TODO: it would be probably better to use READY_FOR_FIXATION_UPPER here and to have
        // it prioritized in comparison with READY_FOR_FIXATION_LOWER (however, KT-41934 example currently prevents it)
        READY_FOR_FIXATION_CAPTURED_UPPER_BOUND_WITH_SELF_TYPES,

        // K1 used this for reified type parameters, mainly to get discriminateNothingForReifiedParameter.kt working
        // KT-55691 lessens the need for this readiness kind in K2,
        // however K2 still needs this e.g. for reifiedToNothing.kt example.
        // TODO: consider deprioritizing Nothing in relation systems like Nothing <: T <: SomeType (see KT-76443)
        // and not using anymore this readiness kind in K2. Related issues: KT-32358 (especially kt32358_3.kt test)
        READY_FOR_FIXATION_REIFIED,
    }

    private val fixationEnhancementsIn22: Boolean
        get() = languageVersionSettings.supportsFeature(LanguageFeature.FixationEnhancementsIn22)

    private fun Context.getTypeVariableReadiness(
        variable: TypeConstructorMarker,
        dependencyProvider: TypeVariableDependencyInformationProvider,
    ): TypeVariableFixationReadiness = when {
        !notFixedTypeVariables.contains(variable) || dependencyProvider.isVariableRelatedToTopLevelType(variable) ||
                variableHasUnprocessedConstraintsInForks(variable) ->
            TypeVariableFixationReadiness.FORBIDDEN

        // Pre-2.2: might be fixed, but this condition should come earlier than the next one,
        // because self-type-based cases do not have proper constraints, though they assumed to be fixed
        // 2.2+: self-type-based upper bounds are considered captured upper bounds
        // and have higher priority as upper/lower (affects e.g. KT-74999)
        // For reified variables we keep old behavior, as captured types aren't usable for their substitutions (see KT-49838, KT-51040)
        areAllProperConstraintsSelfTypeBased(variable) -> if (!fixationEnhancementsIn22 || isReified(variable)) {
            TypeVariableFixationReadiness.READY_FOR_FIXATION_DECLARED_UPPER_BOUND_WITH_SELF_TYPES
        } else {
            TypeVariableFixationReadiness.READY_FOR_FIXATION_CAPTURED_UPPER_BOUND_WITH_SELF_TYPES
        }

        // Prevents from fixation
        !variableHasProperArgumentConstraints(variable) -> TypeVariableFixationReadiness.WITHOUT_PROPER_ARGUMENT_CONSTRAINT
        // PCLA only
        dependencyProvider.isRelatedToOuterTypeVariable(variable) -> TypeVariableFixationReadiness.OUTER_TYPE_VARIABLE_DEPENDENCY

        // All cases below do not prevent fixation but just define the priority order of a variable
        hasDependencyToOtherTypeVariables(variable) -> computeReadinessForVariableWithDependencies(variable)
        // TODO: Consider removing this kind of readiness, see KT-63032
        allConstraintsTrivialOrNonProper(variable) -> TypeVariableFixationReadiness.ALL_CONSTRAINTS_TRIVIAL_OR_NON_PROPER
        dependencyProvider.isVariableRelatedToAnyOutputType(variable) -> TypeVariableFixationReadiness.RELATED_TO_ANY_OUTPUT_TYPE
        variableHasOnlyIncorporatedConstraintsFromDeclaredUpperBound(variable) ->
            TypeVariableFixationReadiness.FROM_INCORPORATION_OF_DECLARED_UPPER_BOUND
        isReified(variable) -> TypeVariableFixationReadiness.READY_FOR_FIXATION_REIFIED

        // 1.5+ (questionable) logic: we prefer LOWER constraints to UPPER constraints, mostly because of KT-41934
        // TODO: try to reconsider (see KT-76518)
        variableHasLowerNonNothingProperConstraint(variable) -> TypeVariableFixationReadiness.READY_FOR_FIXATION_LOWER
        else -> TypeVariableFixationReadiness.READY_FOR_FIXATION_UPPER
    }

    private fun Context.variableHasUnprocessedConstraintsInForks(variableConstructor: TypeConstructorMarker): Boolean {
        if (constraintsFromAllForkPoints.isEmpty()) return false

        for ((_, forkPointData) in constraintsFromAllForkPoints) {
            for (constraints in forkPointData) {
                for ((typeVariableFromConstraint, constraint) in constraints) {
                    if (typeVariableFromConstraint.freshTypeConstructor() == variableConstructor) return true
                    if (containsTypeVariable(constraint.type, variableConstructor)) return true
                }
            }
        }

        return false
    }

    fun isTypeVariableHasProperConstraint(
        context: Context,
        typeVariable: TypeConstructorMarker,
    ): Boolean {
        return with(context) {
            val dependencyProvider = TypeVariableDependencyInformationProvider(
                notFixedTypeVariables, emptyList(), topLevelType = null, context,
                languageVersionSettings,
            )
            when (getTypeVariableReadiness(typeVariable, dependencyProvider)) {
                TypeVariableFixationReadiness.FORBIDDEN, TypeVariableFixationReadiness.WITHOUT_PROPER_ARGUMENT_CONSTRAINT -> false
                else -> true
            }
        }
    }

    private fun Context.allConstraintsTrivialOrNonProper(variable: TypeConstructorMarker): Boolean {
        return notFixedTypeVariables[variable]?.constraints?.all { constraint ->
            trivialConstraintTypeInferenceOracle.isNotInterestingConstraint(constraint) || !isProperArgumentConstraint(constraint)
        } ?: false
    }

    private fun Context.variableHasOnlyIncorporatedConstraintsFromDeclaredUpperBound(variable: TypeConstructorMarker): Boolean {
        val constraints = notFixedTypeVariables[variable]?.constraints ?: return false

        return constraints.filter { isProperArgumentConstraint(it) }.all { it.position.isFromDeclaredUpperBound }
    }

    private fun Context.findTypeVariableForFixation(
        allTypeVariables: List<TypeConstructorMarker>,
        postponedArguments: List<PostponedResolvedAtomMarker>,
        completionMode: ConstraintSystemCompletionMode,
        topLevelType: KotlinTypeMarker,
    ): VariableForFixation? {
        if (allTypeVariables.isEmpty()) return null

        val dependencyProvider = TypeVariableDependencyInformationProvider(
            notFixedTypeVariables, postponedArguments, topLevelType.takeIf { completionMode == PARTIAL }, this,
            languageVersionSettings,
        )

        val candidate =
            allTypeVariables.maxByOrNull { getTypeVariableReadiness(it, dependencyProvider) } ?: return null

        return when (getTypeVariableReadiness(candidate, dependencyProvider)) {
            TypeVariableFixationReadiness.FORBIDDEN -> null
            TypeVariableFixationReadiness.WITHOUT_PROPER_ARGUMENT_CONSTRAINT -> VariableForFixation(candidate, false)
            TypeVariableFixationReadiness.OUTER_TYPE_VARIABLE_DEPENDENCY ->
                VariableForFixation(candidate, hasProperConstraint = true, hasDependencyOnOuterTypeVariable = true)

            else -> VariableForFixation(candidate, true)
        }
    }

    private fun Context.hasDependencyToOtherTypeVariables(typeVariable: TypeConstructorMarker): Boolean {
        for (constraint in notFixedTypeVariables[typeVariable]?.constraints ?: return false) {
            val dependencyPresenceCondition = { type: KotlinTypeMarker ->
                type.typeConstructor() != typeVariable && notFixedTypeVariables.containsKey(type.typeConstructor())
            }
            if (constraint.type.lowerBoundIfFlexible().argumentsCount() != 0 && constraint.type.contains(dependencyPresenceCondition))
                return true
        }
        return false
    }

    private fun Context.computeReadinessForVariableWithDependencies(typeVariable: TypeConstructorMarker): TypeVariableFixationReadiness {
        return if (!fixationEnhancementsIn22 || !hasProperArgumentConstraint(typeVariable)) {
            TypeVariableFixationReadiness.WITH_COMPLEX_DEPENDENCY
        } else {
            TypeVariableFixationReadiness.WITH_COMPLEX_DEPENDENCY_BUT_PROPER_EQUALITY_CONSTRAINT
        }
    }

    private fun Context.hasProperArgumentConstraint(typeVariable: TypeConstructorMarker): Boolean {
        val constraints = notFixedTypeVariables[typeVariable]?.constraints ?: return false
        return constraints.any { it.kind == ConstraintKind.EQUALITY && isProperArgumentConstraint(it) }
    }

    private fun Context.variableHasProperArgumentConstraints(variable: TypeConstructorMarker): Boolean {
        val constraints = notFixedTypeVariables[variable]?.constraints ?: return false
        // temporary hack to fail calls which contain callable references resolved though OI with uninferred type parameters
        val areThereConstraintsWithUninferredTypeParameter = constraints.any { c -> c.type.contains { it.isUninferredParameter() } }
        return constraints.any { isProperArgumentConstraint(it) } && !areThereConstraintsWithUninferredTypeParameter
    }

    private fun Context.isProperArgumentConstraint(c: Constraint) =
        isProperType(c.type)
                && c.position.initialConstraint.position !is DeclaredUpperBoundConstraintPosition<*>
                && !c.isNullabilityConstraint

    private fun Context.isProperType(type: KotlinTypeMarker): Boolean =
        isProperTypeForFixation(type, notFixedTypeVariables.keys) { t -> !t.contains { isNotFixedRelevantVariable(it) } }

    private fun Context.isNotFixedRelevantVariable(it: KotlinTypeMarker): Boolean {
        val key = it.typeConstructor()
        if (!notFixedTypeVariables.containsKey(key)) return false
        if (typeVariablesThatAreCountedAsProperTypes?.contains(key) == true) return false
        return true
    }

    private fun Context.isReified(variable: TypeConstructorMarker): Boolean =
        notFixedTypeVariables[variable]?.typeVariable?.let { isReified(it) } ?: false

    private fun Context.variableHasLowerNonNothingProperConstraint(variable: TypeConstructorMarker): Boolean {
        val constraints = notFixedTypeVariables[variable]?.constraints ?: return false

        return constraints.any {
            it.kind.isLower() && isProperArgumentConstraint(it) && !it.type.typeConstructor().isNothingConstructor()
        }
    }

    private fun Context.isSelfTypeConstraint(constraint: Constraint): Boolean {
        val typeConstructor = constraint.type.typeConstructor()
        return constraint.position.from is DeclaredUpperBoundConstraintPosition<*>
                && (hasRecursiveTypeParametersWithGivenSelfType(typeConstructor) || isRecursiveTypeParameter(typeConstructor))
    }

    private fun Context.areAllProperConstraintsSelfTypeBased(variable: TypeConstructorMarker): Boolean {
        val constraints = notFixedTypeVariables[variable]?.constraints?.takeIf { it.isNotEmpty() } ?: return false

        var hasSelfTypeConstraint = false
        var hasOtherProperConstraint = false

        for (constraint in constraints) {
            if (isSelfTypeConstraint(constraint)) {
                hasSelfTypeConstraint = true
            }
            if (isProperArgumentConstraint(constraint)) {
                hasOtherProperConstraint = true
            }
            if (hasSelfTypeConstraint && hasOtherProperConstraint) break
        }

        return hasSelfTypeConstraint && !hasOtherProperConstraint
    }
}

/**
 * Returns `false` for fixed type variables types even if `isProper(type) == true`
 * Thus allowing only non-TVs types to be used for fixation on top level.
 * While this limitation is important, it doesn't really limit final results because when we have a constraint like T <: E or E <: T
 * and we're going to fix T into E, we assume that if E has some other constraints, they are being incorporated to T, so we would choose
 * them instead of E itself.
 */
inline fun TypeSystemInferenceExtensionContext.isProperTypeForFixation(
    type: KotlinTypeMarker,
    notFixedTypeVariables: Set<TypeConstructorMarker>,
    isProper: (KotlinTypeMarker) -> Boolean
): Boolean {
    // We don't allow fixing T into any top-level TV type, like T := F or T := F & Any
    // Even if F is considered as a proper by `isProper` (e.g., it belongs to an outer CS)
    // But at the same time, we don't forbid fixing into T := MutableList<F>
    // Exception: semi-fixing to other type variables is allowed during overload resolution by lambda return type
    if (!allowSemiFixationToOtherTypeVariables && type.typeConstructor() in notFixedTypeVariables) {
        return false
    }
    return isProper(type) && extractProjectionsForAllCapturedTypes(type).all(isProper)
}

fun TypeSystemInferenceExtensionContext.extractProjectionsForAllCapturedTypes(baseType: KotlinTypeMarker): Set<KotlinTypeMarker> {
    if (baseType.isFlexible()) {
        val flexibleType = baseType.asFlexibleType()!!
        return buildSet {
            addAll(extractProjectionsForAllCapturedTypes(flexibleType.lowerBound()))
            addAll(extractProjectionsForAllCapturedTypes(flexibleType.upperBound()))
        }
    }
    val simpleBaseType = baseType.asRigidType()?.asCapturedTypeUnwrappingDnn()

    return buildSet {
        val projectionType = if (simpleBaseType != null) {
            val argumentType = simpleBaseType.typeConstructorProjection().getType() ?: return@buildSet
            argumentType.also(::add)
        } else baseType
        val argumentsCount = projectionType.argumentsCount().takeIf { it != 0 } ?: return@buildSet

        for (i in 0 until argumentsCount) {
            val argumentType = projectionType.getArgument(i).getType() ?: continue
            addAll(extractProjectionsForAllCapturedTypes(argumentType))
        }
    }
}

fun TypeSystemInferenceExtensionContext.containsTypeVariable(type: KotlinTypeMarker, typeVariable: TypeConstructorMarker): Boolean {
    if (type.contains { it.typeConstructor().unwrapStubTypeVariableConstructor() == typeVariable }) return true

    val typeProjections = extractProjectionsForAllCapturedTypes(type)

    return typeProjections.any { typeProjectionsType ->
        typeProjectionsType.contains { it.typeConstructor().unwrapStubTypeVariableConstructor() == typeVariable }
    }
}
