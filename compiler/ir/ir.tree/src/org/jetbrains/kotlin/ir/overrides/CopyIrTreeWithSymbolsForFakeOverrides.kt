/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.ir.overrides

import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.symbols.IrClassifierSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueParameterSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeImpl
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.AbstractTypeRemapper
import org.jetbrains.kotlin.ir.util.TypeRemapper
import org.jetbrains.kotlin.ir.util.copyAnnotations
import org.jetbrains.kotlin.ir.util.findAnnotation
import org.jetbrains.kotlin.name.StandardClassIds.Annotations.EnhancedNullability
import org.jetbrains.kotlin.name.StandardClassIds.Annotations.FlexibleNullability
import org.jetbrains.kotlin.utils.addIfNotNull
import org.jetbrains.kotlin.utils.memoryOptimizedMap

class CopyIrTreeWithSymbolsForFakeOverrides(
    private val overridableMember: IrOverridableMember,
    private val substitution: Map<IrTypeParameterSymbol, IrType>,
    private val parentClass: IrClass,
) {
    fun copy(): IrOverridableMember {
        val typeParameters = HashMap<IrTypeParameterSymbol, IrTypeParameterSymbol>()
        val valueParameters = HashMap<IrValueParameterSymbol, IrValueParameterSymbol>()

        val copier = FakeOverrideCopier(
            valueParameters,
            typeParameters,
            FakeOverrideTypeRemapper(typeParameters, substitution),
            parentClass,
        )

        return when (overridableMember) {
            is IrSimpleFunction -> copier.copySimpleFunction(overridableMember)
            is IrProperty -> copier.copyProperty(overridableMember)
        }
    }

    private class FakeOverrideTypeRemapper(
        val typeParameters: Map<IrTypeParameterSymbol, IrTypeParameterSymbol>,
        val substitution: Map<IrTypeParameterSymbol, IrType>
    ) : AbstractTypeRemapper() {
        private fun IrType.mergeTypeAnnotations(other: IrType): IrType {
            // Flexible types are represented as a type annotation in IR, so we need to keep it when substituting type during override.
            // Note that it's incorrect to merge _all_ type annotations though, because for a Collection subclass:
            //
            //     abstract class Z : Collection<Int>
            //
            // `Z.contains` should have the signature `(Int) -> Boolean`, NOT `(@UnsafeVariance Int) -> Boolean` which would occur if we
            // copied all type annotations.
            return addAnnotations(buildList {
                for (fqName in TYPE_ANNOTATIONS_TO_MERGE) {
                    addIfNotNull(other.annotations.findAnnotation(fqName))
                }
            })
        }

        override fun remapTypeOrNull(type: IrType): IrType? {
            if (type !is IrSimpleType) return null

            return when (val substitutedType = substitution[type.classifier]) {
                is IrDynamicType -> substitutedType
                is IrSimpleType -> substitutedType.mergeNullability(type).mergeTypeAnnotations(type)
                else -> {
                    val newClassifier = remapClassifier(type.classifier)
                    val newTypeArguments = remapTypeArguments(type.arguments)
                    if (newClassifier == type.classifier && newTypeArguments == null) return null
                    IrSimpleTypeImpl(
                        newClassifier,
                        type.nullability,
                        newTypeArguments ?: type.arguments,
                        type.annotations,
                    )
                }
            }
        }

        private fun remapClassifier(classifier: IrClassifierSymbol): IrClassifierSymbol =
            if (classifier is IrTypeParameterSymbol)
                typeParameters.getOrElse(classifier) { classifier }
            else
                classifier
    }

    private companion object {
        // TODO (KT-64715): RawTypeAnnotation, FlexibleMutability, RawType, FlexibleArrayElementVariance?
        val TYPE_ANNOTATIONS_TO_MERGE = listOf(
            FlexibleNullability.asSingleFqName(),
            EnhancedNullability.asSingleFqName(),
        )
    }
}
