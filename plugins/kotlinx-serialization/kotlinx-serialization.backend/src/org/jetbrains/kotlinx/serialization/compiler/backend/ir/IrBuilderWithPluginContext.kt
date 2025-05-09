/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.serialization.compiler.backend.ir

import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.ObsoleteDescriptorBasedAPI
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addGetter
import org.jetbrains.kotlin.ir.builders.declarations.addProperty
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrAnonymousInitializerSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.resolve.jvm.JvmPrimitiveType
import org.jetbrains.kotlin.utils.addToStdlib.assignFrom
import org.jetbrains.kotlinx.serialization.compiler.extensions.SerializationPluginContext
import org.jetbrains.kotlinx.serialization.compiler.resolve.SerialEntityNames.KSERIALIZER_NAME_FQ


interface IrBuilderWithPluginContext {
    val compilerContext: SerializationPluginContext

    fun <F: IrFunction> addFunctionBody(function: F, bodyGen: IrBlockBodyBuilder.(F) -> Unit) {
        val parentClass = function.parent
        val startOffset = function.startOffset.takeIf { it >= 0 } ?: parentClass.startOffset
        val endOffset = function.endOffset.takeIf { it >= 0 } ?: parentClass.endOffset
        function.body = DeclarationIrBuilder(compilerContext, function.symbol, startOffset, endOffset).irBlockBody(
            startOffset,
            endOffset
        ) { bodyGen(function) }
    }

    fun IrClass.createLambdaExpression(
        type: IrType,
        bodyGen: IrBlockBodyBuilder.() -> Unit
    ): IrFunctionExpression {
        val function = compilerContext.irFactory.buildFun {
            this.startOffset = this@createLambdaExpression.startOffset
            this.endOffset = this@createLambdaExpression.endOffset
            this.returnType = type
            name = Name.identifier("<anonymous>")
            visibility = DescriptorVisibilities.LOCAL
            origin = SERIALIZATION_PLUGIN_ORIGIN
        }
        function.body = DeclarationIrBuilder(compilerContext, function.symbol, startOffset, endOffset).irBlockBody(startOffset, endOffset) {
            val expr = addAndGetLastExpression(bodyGen)
            +irReturn(expr)
        }
        function.parent = this

        val f0Type = compilerContext.irBuiltIns.functionN(0)
        val f0ParamSymbol = f0Type.typeParameters[0].symbol
        val f0IrType = f0Type.defaultType.substitute(mapOf(f0ParamSymbol to type))

        return IrFunctionExpressionImpl(
            startOffset,
            endOffset,
            f0IrType,
            function,
            IrStatementOrigin.LAMBDA
        )
    }

    fun addLazyValProperty(
        containingClass: IrClass,
        targetIrType: IrType,
        propertyName: Name,
        visibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE,
        initializerBuilder: IrBlockBodyBuilder.() -> Unit
    ): IrProperty {
        val lazyIrType = lazyType(targetIrType)

        val field = containingClass.factory.buildField {
            startOffset = containingClass.startOffset
            endOffset = containingClass.endOffset
            name = Name.identifier(propertyName.asString() + "\$delegate")
            type = lazyIrType
            origin = SERIALIZATION_PLUGIN_ORIGIN
            isFinal = true
            this.visibility = DescriptorVisibilities.PRIVATE
        }.also { it.parent = containingClass }

        containingClass.addAnonymousInit {
            val invokeLazyExpr = createLazyDelegate(targetIrType, containingClass, initializerBuilder)
            +irSetField(irGet(containingClass.thisReceiver!!), field, invokeLazyExpr)
        }


        val prop = containingClass.addProperty {
            startOffset = containingClass.startOffset
            endOffset = containingClass.endOffset
            name = propertyName
            this.visibility = visibility
            this.isVar = false
            origin = SERIALIZATION_PLUGIN_ORIGIN
        }.apply {
            field.correspondingPropertySymbol = this.symbol
            backingField = field
        }

        val getter = prop.addGetter {
            startOffset = containingClass.startOffset
            endOffset = containingClass.endOffset
            returnType = targetIrType
            origin = SERIALIZATION_PLUGIN_ORIGIN
            this.visibility = visibility
            modality = Modality.FINAL
        }

        getter.apply {
            parameters = listOf(containingClass.thisReceiver!!.copyTo(this, type = containingClass.defaultType))
            body = compilerContext.irBuiltIns.createIrBuilder(symbol, containingClass.startOffset, containingClass.endOffset).irBlockBody {
                +irReturn(
                    irInvoke(
                        compilerContext.lazyValueGetter,
                        irGetField(irGet(dispatchReceiverParameter!!), field),
                        returnTypeHint = targetIrType
                    )
                )
            }
        }
        return prop
    }

    fun IrElement.createAnnotationCallWithoutArgs(annotationSymbol: IrClassSymbol): IrConstructorCall {
        val annotationCtor = annotationSymbol.constructors.single { it.owner.isPrimary }
        val annotationType = annotationSymbol.defaultType

        return IrConstructorCallImpl.fromSymbolOwner(startOffset, endOffset, annotationType, annotationCtor)
    }

    fun IrClass.addValPropertyWithJvmField(
        type: IrType,
        name: Name,
        visibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE,
        initializerBuilder: IrBlockBodyBuilder.() -> Unit
    ): IrProperty {
        return generateSimplePropertyWithBackingField(name, type, this, visibility).apply {
            val field = backingField!!
            addAnonymousInit {
                val resultExpression = addAndGetLastExpression(initializerBuilder)
                +irSetField(irGet(thisReceiver!!), field, resultExpression)
            }
            field.annotations += createAnnotationCallWithoutArgs(compilerContext.jvmFieldClassSymbol)
        }
    }

    fun IrClass.addValPropertyWithJvmFieldInitializer(
        type: IrType,
        name: Name,
        visibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE,
        initializer: IrBuilderWithScope.() -> IrExpression
    ): IrProperty {
        return generateSimplePropertyWithBackingField(name, type, this, visibility).apply {
            val field = backingField!!

            val builder = DeclarationIrBuilder(
                compilerContext,
                field.symbol,
                field.startOffset,
                field.endOffset
            )
            field.initializer = factory.createExpressionBody(builder.initializer())
            field.annotations += createAnnotationCallWithoutArgs(compilerContext.jvmFieldClassSymbol)
        }
    }

    /**
     * Add all statements to the builder, except the last one.
     * The last statement should be an expression, it will return as a result
     */
    private fun IrStatementsBuilder<*>.addAndGetLastExpression(blockBuilder: IrBlockBodyBuilder.() -> Unit): IrExpression {
        val irBlockBody = irBlockBody(startOffset, endOffset, blockBuilder)
        irBlockBody.statements.dropLast(1).forEach { +it }
        return irBlockBody.statements.last() as? IrExpression
            ?: error("Last statement in property initializer builder is not an a expression")
    }

    fun IrBuilderWithScope.irInvoke(
        callee: IrFunctionSymbol,
        vararg arguments: IrExpression,
        returnTypeHint: IrType? = null,
    ): IrFunctionAccessExpression {
        val call = irCall(callee, type = returnTypeHint ?: callee.owner.returnType)
        call.arguments.assignFrom(arguments.toList())
        return call
    }

    fun IrBuilderWithScope.irInvoke(
        callee: IrFunctionSymbol,
        arguments: List<IrExpression>,
        typeArguments: List<IrType?> = emptyList(),
        returnTypeHint: IrType? = null,
    ): IrFunctionAccessExpression {
        val call = irCall(callee, type = returnTypeHint ?: callee.owner.returnType)
        call.arguments.assignFrom(arguments.toList())
        call.typeArguments.assignFrom(typeArguments)
        return call
    }

    fun IrBuilderWithScope.createArrayOfExpression(
        arrayElementType: IrType,
        arrayElements: List<IrExpression>
    ): IrExpression {

        val arrayType = compilerContext.irBuiltIns.arrayClass.typeWith(arrayElementType)
        val arg0 = IrVarargImpl(startOffset, endOffset, arrayType, arrayElementType, arrayElements)
        val typeArguments = listOf(arrayElementType)

        return irCall(compilerContext.irBuiltIns.arrayOf, arrayType, typeArguments = typeArguments).apply {
            arguments[0] = arg0
        }
    }

    fun IrBuilderWithScope.createIntArrayOfExpression(arrayElements: List<IrExpression>): IrExpression {
        val elementType = compilerContext.irBuiltIns.intType
        val arrayType = compilerContext.intArrayOfFunctionSymbol.owner.returnType
        val arg0 = IrVarargImpl(startOffset, endOffset, arrayType, elementType, arrayElements)
        return irCall(compilerContext.intArrayOfFunctionSymbol, arrayType).apply {
            arguments[0] = arg0
        }
    }

    fun IrClass.addAnonymousInit(body: IrBlockBodyBuilder.() -> Unit) {
        val anonymousInit = this.run {
            val symbol = IrAnonymousInitializerSymbolImpl(symbol)
            this.factory.createAnonymousInitializer(startOffset, endOffset, SERIALIZATION_PLUGIN_ORIGIN, symbol).also {
                it.parent = this
                declarations.add(it)
            }
        }

        anonymousInit.buildWithScope { initIrBody ->
            initIrBody.body =
                DeclarationIrBuilder(
                    compilerContext,
                    initIrBody.symbol,
                    initIrBody.startOffset,
                    initIrBody.endOffset
                ).irBlockBody(body = body)
        }
    }

    fun IrBuilderWithScope.irBinOp(name: Name, lhs: IrExpression, rhs: IrExpression): IrExpression {
        val classFqName = (lhs.type as IrSimpleType).classOrNull!!.owner.fqNameWhenAvailable!!
        val symbol = compilerContext.referenceFunctions(CallableId(ClassId.topLevel(classFqName), name)).single()
        return irInvoke(symbol, lhs, rhs)
    }

    fun IrBuilderWithScope.irGetObject(irObject: IrClass) =
        IrGetObjectValueImpl(
            startOffset,
            endOffset,
            irObject.defaultType,
            irObject.symbol
        )

    fun <T : IrDeclaration> T.buildWithScope(builder: (T) -> Unit): T =
        also { irDeclaration ->
            @OptIn(ObsoleteDescriptorBasedAPI::class)
            compilerContext.symbolTable.withReferenceScope(irDeclaration) {
                builder(irDeclaration)
            }
        }

    class BranchBuilder(
        val irWhen: IrWhen,
        context: IrGeneratorContext,
        scope: Scope,
        startOffset: Int,
        endOffset: Int
    ) : IrBuilderWithScope(context, scope, startOffset, endOffset) {
        operator fun IrBranch.unaryPlus() {
            irWhen.branches.add(this)
        }
    }

    fun IrBuilderWithScope.irWhen(typeHint: IrType? = null, block: BranchBuilder.() -> Unit): IrWhen {
        val whenExpr = IrWhenImpl(startOffset, endOffset, typeHint ?: compilerContext.irBuiltIns.unitType)
        val builder = BranchBuilder(whenExpr, context, scope, startOffset, endOffset)
        builder.block()
        return whenExpr
    }

    fun BranchBuilder.elseBranch(result: IrExpression): IrElseBranch =
        IrElseBranchImpl(
            IrConstImpl.boolean(result.startOffset, result.endOffset, compilerContext.irBuiltIns.booleanType, true),
            result
        )

    fun IrBuilderWithScope.setProperty(receiver: IrExpression, property: IrProperty, value: IrExpression): IrExpression {
        return if (property.setter != null)
            irSet(property.setter!!.returnType, receiver, property.setter!!.symbol, value)
        else
            irSetField(receiver, property.backingField!!, value)
    }

    fun IrBuilderWithScope.generateAnySuperConstructorCall(toBuilder: IrBlockBodyBuilder) {
        val anyConstructor = compilerContext.irBuiltIns.anyClass.owner.declarations.single { it is IrConstructor } as IrConstructor
        with(toBuilder) {
            +IrDelegatingConstructorCallImpl.fromSymbolOwner(
                startOffset, endOffset,
                compilerContext.irBuiltIns.unitType,
                anyConstructor.symbol
            )
        }
    }

    fun generateSimplePropertyWithBackingField(
        propertyName: Name,
        propertyType: IrType,
        propertyParent: IrClass,
        visibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE
    ): IrProperty = generatePropertyMissingParts(null, propertyName, propertyType, propertyParent, visibility)

    fun generatePropertyMissingParts(
        property: IrProperty?,
        propertyName: Name,
        propertyType: IrType,
        propertyParent: IrClass,
        visibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE
    ): IrProperty {
        val field = property?.backingField ?: propertyParent.factory.buildField {
            startOffset = propertyParent.startOffset
            endOffset = propertyParent.endOffset
            name = propertyName
            type = propertyType
            origin = SERIALIZATION_PLUGIN_ORIGIN
            isFinal = true
            this.visibility = DescriptorVisibilities.PRIVATE
        }.also { it.parent = propertyParent }

        val prop = property ?: propertyParent.addProperty {
            startOffset = propertyParent.startOffset
            endOffset = propertyParent.endOffset
            name = propertyName
            this.isVar = false
            origin = SERIALIZATION_PLUGIN_ORIGIN
        }

        prop.apply {
            field.correspondingPropertySymbol = this.symbol
            backingField = field
        }

        val getter = prop.getter ?: prop.addGetter {
            startOffset = propertyParent.startOffset
            endOffset = propertyParent.endOffset
            returnType = propertyType
            origin = SERIALIZATION_PLUGIN_ORIGIN
            this.visibility = visibility
            modality = Modality.FINAL
        }

        getter.apply {
            if (parameters.isEmpty())
                parameters = listOf(propertyParent.thisReceiver!!.copyTo(this, type = propertyParent.defaultType))
            if (body == null)
                body = compilerContext.irBuiltIns.createIrBuilder(symbol, propertyParent.startOffset, propertyParent.endOffset).irBlockBody {
                        +irReturn(irGetField(irGet(dispatchReceiverParameter!!), field))
                    }
        }
        return prop
    }

    fun createClassReference(classType: IrType, startOffset: Int, endOffset: Int): IrClassReference {
        return IrClassReferenceImpl(
            startOffset,
            endOffset,
            compilerContext.irBuiltIns.kClassClass.starProjectedType,
            classType.classifierOrFail,
            classType
        )
    }

    fun IrBuilderWithScope.classReference(classSymbol: IrClassSymbol): IrClassReference =
        createClassReference(classSymbol.starProjectedType, startOffset, endOffset)

    fun collectSerialInfoAnnotations(irClass: IrClass): List<IrConstructorCall> {
        if (!(irClass.isInterface || irClass.hasSerializableOrMetaAnnotation())) return emptyList()
        val annotationByFq: MutableMap<FqName, List<IrConstructorCall>> =
            irClass.annotations.groupBy { it.symbol.owner.parentAsClass.fqNameWhenAvailable!! }.toMutableMap()
        for (clazz in irClass.getAllSuperclasses()) {
            val annotations = clazz.annotations
                .mapNotNull {
                    val parent = it.symbol.owner.parentAsClass
                    if (parent.isInheritableSerialInfoAnnotation) parent.fqNameWhenAvailable!! to it else null
                }
            annotations.forEach { (fqname, call) ->
                if (fqname !in annotationByFq) {
                    annotationByFq[fqname] = listOf(call)
                } else {
                    // SerializationPluginDeclarationChecker already reported inconsistency
                    // InheritableSerialInfo annotations can not be repeatable
                }
            }
        }
        return annotationByFq.values.toList().flatten()
    }

    fun IrBuilderWithScope.copyAnnotationsFrom(annotations: List<IrConstructorCall>): List<IrExpression> =
        annotations.filter { it.symbol.owner.parentAsClass.isSerialInfoAnnotation }.map { it.deepCopyWithoutPatchingParents() }

    fun kSerializerType(serializableType: IrType): IrSimpleType {
        val kSerializerClass = compilerContext.kSerializerClass
            ?: error("Serializer class '$KSERIALIZER_NAME_FQ' not found. Check that the kotlinx.serialization runtime is connected correctly")
        return kSerializerClass.typeWith(serializableType)
    }

    fun lazyType(returnType: IrType): IrType {
        return compilerContext.lazyClass.defaultType.substitute(mapOf(compilerContext.lazyClass.typeParameters[0].symbol to returnType))
    }

    fun IrBuilderWithScope.createLazyDelegate(
        returnType: IrType,
        containingClass: IrClass,
        initializerBuilder: IrBlockBodyBuilder.() -> Unit,
    ): IrExpression {
        val lazyIrType = lazyType(returnType)

        val enumElement = IrGetEnumValueImpl(
            startOffset,
            endOffset,
            compilerContext.lazyModeClass.defaultType,
            compilerContext.lazyModePublicationEnumEntry.symbol
        )
        val lambdaExpression = containingClass.createLambdaExpression(returnType, initializerBuilder)
        return irInvoke(compilerContext.lazyFunctionSymbol, listOf(enumElement, lambdaExpression), listOf(returnType), lazyIrType)
    }

    @OptIn(ObsoleteDescriptorBasedAPI::class)
    fun IrBuilderWithScope.wrapperClassReference(classType: IrType): IrClassReference {
        if (compilerContext.platform.isJvm()) {
            // "Byte::class" -> "java.lang.Byte::class"
//          TODO: get rid of descriptor
            val wrapperFqName =
                KotlinBuiltIns.getPrimitiveType(classType.classOrNull!!.descriptor)?.let(JvmPrimitiveType::get)?.wrapperFqName
            if (wrapperFqName != null) {
                val wrapperClass = compilerContext.referenceClass(ClassId.topLevel(wrapperFqName))
                    ?: error("Primitive wrapper class for $classType not found: $wrapperFqName")
                return createClassReference(wrapperClass.defaultType, startOffset, endOffset)
            }
        }
        return createClassReference(classType, startOffset, endOffset)
    }

    fun IrClass.getSuperClassOrAny(): IrClass = getSuperClassNotAny() ?: compilerContext.irBuiltIns.anyClass.owner
}
