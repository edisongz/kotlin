/*
 * Copyright 2010-2024 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.lower

import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.DeclarationContainerLoweringPass
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irBlockBody
import org.jetbrains.kotlin.backend.common.lower.irIfThen
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.descriptors.*
import org.jetbrains.kotlin.backend.konan.ir.*
import org.jetbrains.kotlin.backend.konan.ir.BridgeDirection
import org.jetbrains.kotlin.backend.konan.ir.BridgeDirectionKind
import org.jetbrains.kotlin.backend.konan.ir.BridgeDirections
import org.jetbrains.kotlin.backend.konan.ir.OverriddenFunctionInfo
import org.jetbrains.kotlin.backend.konan.llvm.computeFunctionName
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrBuiltIns
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrRawFunctionReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.fromSymbolOwner
import org.jetbrains.kotlin.ir.irAttribute
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.isNullableAny
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.load.java.BuiltinMethodsWithSpecialGenericSignature
import org.jetbrains.kotlin.load.java.SpecialGenericSignatures
import org.jetbrains.kotlin.utils.addToStdlib.getOrSetIfNull

private var IrFunction.bridges: MutableMap<BridgeDirections, IrSimpleFunction>? by irAttribute(copyByDefault = false)

@OptIn(ObsoleteDescriptorBasedAPI::class)
internal fun IrFunction.getDefaultValueForOverriddenBuiltinFunction() = BuiltinMethodsWithSpecialGenericSignature.getDefaultValueForOverriddenBuiltinFunction(descriptor)

internal class BridgesSupport(val irBuiltIns: IrBuiltIns, val irFactory: IrFactory) {
    fun getBridge(overriddenFunction: OverriddenFunctionInfo): IrSimpleFunction {
        val irFunction = overriddenFunction.function
        val directions = overriddenFunction.bridgeDirections
        assert(overriddenFunction.needBridge) {
            "Function ${irFunction.render()} doesn't need a bridge to call overridden function ${overriddenFunction.overriddenFunction.render()}"
        }

        val functionBridges = irFunction::bridges.getOrSetIfNull { mutableMapOf() }
        return functionBridges.getOrPut(directions) { createBridge(irFunction, directions) }
    }

    private fun BridgeDirection.type() =
            if (this.kind == BridgeDirectionKind.NONE)
                null
            else this.erasedType ?: irBuiltIns.anyNType

    private fun createBridge(function: IrSimpleFunction, bridgeDirections: BridgeDirections): IrSimpleFunction {
        val target = function.target

        // Note: bridgeDirection.type() = null only for BridgeDirectionKind.NONE (no conversion required),
        // so in this case the type is taken from target - the function to be called.
        return irFactory.buildFun {
            startOffset = function.startOffset
            endOffset = function.endOffset
            origin = DECLARATION_ORIGIN_BRIDGE_METHOD(target)
            name = "<bridge-$bridgeDirections>${function.computeFunctionName()}".synthesizedName
            visibility = function.visibility
            modality = function.modality
            returnType = bridgeDirections.returnDirection.type() ?: target.returnType
            isSuspend = function.isSuspend
        }.apply {
            parent = function.parent
            val bridge = this

            parameters = target.parameters.map {
                it.copyTo(bridge, type = bridgeDirections.parameterDirectionAt(it.indexInParameters).type() ?: it.type)
            }

            typeParameters = function.typeParameters.map { parameter ->
                parameter.copyToWithoutSuperTypes(bridge).also { it.superTypes += parameter.superTypes }
            }
        }
    }
}
internal class WorkersBridgesBuilding(val context: Context) : DeclarationContainerLoweringPass, IrElementTransformerVoid() {
    private val bridgesPolicy = context.config.bridgesPolicy
    val symbols = context.symbols
    lateinit var runtimeJobFunction: IrSimpleFunction

    override fun lower(irDeclarationContainer: IrDeclarationContainer) {
        irDeclarationContainer.declarations.transformFlat {
            listOf(it) + buildWorkerBridges(it).also { bridges ->
                // `buildWorkerBridges` builds bridges for all declarations inside `it` and nested declarations,
                // so some bridges get incorrect parent. Fix it:
                bridges.forEach { bridge -> bridge.parent = irDeclarationContainer }
            }
        }
    }

    private fun buildWorkerBridges(declaration: IrDeclaration): List<IrFunction> {
        val bridges = mutableListOf<IrFunction>()
        declaration.transformChildrenVoid(object: IrElementTransformerVoid() {
            override fun visitClass(declaration: IrClass): IrStatement {
                // Skip nested.
                return declaration
            }

            override fun visitCall(expression: IrCall): IrExpression {
                expression.transformChildrenVoid(this)

                if (expression.symbol != symbols.executeImpl)
                    return expression

                val job = expression.arguments[3] as IrRawFunctionReference
                val jobFunction = (job.symbol as IrSimpleFunctionSymbol).owner

                if (!::runtimeJobFunction.isInitialized) {
                    val arg = jobFunction.parameters[0]
                    val startOffset = jobFunction.startOffset
                    val endOffset = jobFunction.endOffset
                    runtimeJobFunction =
                        context.irFactory.createSimpleFunction(
                                startOffset,
                                endOffset,
                                IrDeclarationOrigin.DEFINED,
                                jobFunction.name,
                                jobFunction.visibility,
                                isInline = false,
                                isExpect = false,
                                returnType = context.irBuiltIns.anyNType,
                                jobFunction.modality,
                                IrSimpleFunctionSymbolImpl(),
                                isTailrec = false,
                                isSuspend = false,
                                isOperator = false,
                                isInfix = false,
                        )

                    runtimeJobFunction.parameters +=
                        context.irFactory.createValueParameter(
                                startOffset = startOffset,
                                endOffset = endOffset,
                                origin = IrDeclarationOrigin.DEFINED,
                                kind = IrParameterKind.Regular,
                                name = arg.name,
                                type = context.irBuiltIns.anyNType,
                                isAssignable = arg.isAssignable,
                                symbol = IrValueParameterSymbolImpl(),
                                varargElementType = null,
                                isCrossinline = arg.isCrossinline,
                                isNoinline = arg.isNoinline,
                                isHidden = arg.isHidden,
                        )
                }
                val overriddenJobDescriptor = OverriddenFunctionInfo(jobFunction, runtimeJobFunction, bridgesPolicy)
                if (!overriddenJobDescriptor.needBridge) return expression

                val bridge = context.buildBridge(
                        startOffset  = job.startOffset,
                        endOffset    = job.endOffset,
                        overriddenFunction = overriddenJobDescriptor,
                        targetSymbol = jobFunction.symbol)
                bridges += bridge
                expression.arguments[3] = IrRawFunctionReferenceImpl(
                        startOffset   = job.startOffset,
                        endOffset     = job.endOffset,
                        type          = job.type,
                        symbol        = bridge.symbol
                )
                return expression
            }
        })
        return bridges
    }
}

internal class BridgesBuilding(val context: Context) : ClassLoweringPass {
    private val bridgesPolicy = context.config.bridgesPolicy

    override fun lower(irClass: IrClass) {
        val builtBridges = mutableSetOf<IrSimpleFunction>()

        for (function in irClass.simpleFunctions()) {
            val set = mutableSetOf<BridgeDirections>()
            for (overriddenFunction in function.allOverriddenFunctions) {
                val overriddenFunctionInfo = OverriddenFunctionInfo(function, overriddenFunction, bridgesPolicy)
                val bridgeDirections = overriddenFunctionInfo.bridgeDirections
                if (!bridgeDirections.allNotNeeded() && overriddenFunctionInfo.canBeCalledVirtually
                        && !overriddenFunctionInfo.inheritsBridge && set.add(bridgeDirections)) {
                    buildBridge(overriddenFunctionInfo, irClass)
                    builtBridges += function
                }
            }
        }
        irClass.transformChildrenVoid(object: IrElementTransformerVoid() {
            override fun visitClass(declaration: IrClass): IrStatement {
                // Skip nested.
                return declaration
            }

            override fun visitFunction(declaration: IrFunction): IrStatement {
                declaration.transformChildrenVoid(this)

                val body = declaration.body ?: return declaration

                val typeSafeBarrierDescription = declaration.getDefaultValueForOverriddenBuiltinFunction()
                if (typeSafeBarrierDescription == null || builtBridges.contains(declaration))
                    return declaration

                val irBuilder = context.createIrBuilder(declaration.symbol, declaration.startOffset, declaration.endOffset)
                declaration.body = irBuilder.irBlockBody(declaration) {
                    buildTypeSafeBarrier(declaration, declaration, typeSafeBarrierDescription)
                    (body as IrBlockBody).statements.forEach { +it }
                }
                return declaration
            }
        })
    }

    private fun buildBridge(overriddenFunction: OverriddenFunctionInfo, irClass: IrClass) {
        irClass.declarations.add(context.buildBridge(
                startOffset          = irClass.startOffset,
                endOffset            = irClass.endOffset,
                overriddenFunction   = overriddenFunction,
                targetSymbol         = overriddenFunction.function.symbol,
                superQualifierSymbol = irClass.symbol)
        )
    }
}

internal class DECLARATION_ORIGIN_BRIDGE_METHOD(val bridgeTarget: IrSimpleFunction) : IrDeclarationOrigin {
    override val name: String
        get() = "BRIDGE_METHOD"

    override fun toString(): String {
        return "$name(target=${bridgeTarget.symbol})"
    }
}

internal val IrSimpleFunction.bridgeTarget: IrSimpleFunction?
    get() = (origin as? DECLARATION_ORIGIN_BRIDGE_METHOD)?.bridgeTarget

private fun IrBuilderWithScope.returnIfBadType(value: IrExpression,
                                               type: IrType,
                                               returnValueOnFail: IrExpression)
        = irIfThen(irNotIs(value, type), irReturn(returnValueOnFail))

private fun IrBuilderWithScope.irConst(value: Any?) = when (value) {
    null       -> irNull()
    is Int     -> irInt(value)
    is Boolean -> if (value) irTrue() else irFalse()
    else       -> TODO()
}

private fun IrBlockBodyBuilder.buildTypeSafeBarrier(function: IrFunction,
                                                    originalFunction: IrFunction,
                                                    typeSafeBarrierDescription: SpecialGenericSignatures.TypeSafeBarrierDescription) {
    val parameters = function.nonDispatchParameters
    val originalParameters = originalFunction.nonDispatchParameters
    for (i in parameters.indices) {
        if (!typeSafeBarrierDescription.checkParameter(i))
            continue

        val type = originalParameters[i].type.eraseTypeParameters()
        // Note: erasing to single type is not entirely correct if type parameter has multiple upper bounds.
        // In this case the compiler could generate multiple type checks, one for each upper bound.
        // But let's keep it simple here for now; JVM backend doesn't do this anyway.

        if (!type.isNullableAny()) {
            // Here, we can't trust value parameter type until we check it, because of @UnsafeVariance
            // So we add implicit cast to avoid type check optimization
            +returnIfBadType(irImplicitCast(irGet(parameters[i]), context.irBuiltIns.anyNType), type,
                    if (typeSafeBarrierDescription == SpecialGenericSignatures.TypeSafeBarrierDescription.MAP_GET_OR_DEFAULT)
                        irGet(parameters[1])
                    else irConst(typeSafeBarrierDescription.defaultValue)
            )
        }
    }
}

private fun Context.buildBridge(startOffset: Int, endOffset: Int,
                                overriddenFunction: OverriddenFunctionInfo, targetSymbol: IrSimpleFunctionSymbol,
                                superQualifierSymbol: IrClassSymbol? = null): IrFunction {
    val target = targetSymbol.owner.target
    val bridge = bridgesSupport.getBridge(overriddenFunction)

    if (bridge.modality == Modality.ABSTRACT) {
        return bridge
    }

    val irBuilder = createIrBuilder(bridge.symbol, startOffset, endOffset)
    bridge.body = irBuilder.irBlockBody(bridge) {
        val typeSafeBarrierDescription = overriddenFunction.overriddenFunction.getDefaultValueForOverriddenBuiltinFunction()
        typeSafeBarrierDescription?.let { buildTypeSafeBarrier(bridge, overriddenFunction.function, it) }

        val delegatingCall = IrCallImpl.fromSymbolOwner(
                startOffset,
                endOffset,
                target.returnType,
                target.symbol,
                superQualifierSymbol = superQualifierSymbol /* Call non-virtually */
        ).apply {
            bridge.parameters.forEachIndexed { index, parameter -> arguments[index] = irGet(parameter) }
        }

        +irReturn(delegatingCall)
    }
    return bridge
}
