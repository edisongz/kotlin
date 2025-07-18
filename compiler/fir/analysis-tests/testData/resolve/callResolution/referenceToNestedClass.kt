// RUN_PIPELINE_TILL: BACKEND
// ISSUE: KT-41038

import kotlin.reflect.*
import Outer.Nested

class Param

open class Base(val param: Param)

class Outer(param: Param) : Base(param) {
    class Nested(param: Param) : Base(param)
}

fun funWithCtor(ctor: KFunction1<Param, Base>) {}

fun main() {
    funWithCtor(::Outer)
    funWithCtor(::Nested)
    funWithCtor(Outer::Nested)
    funWithCtor(Outer::Nested::invoke)
}

/* GENERATED_FIR_TAGS: callableReference, classDeclaration, functionDeclaration, nestedClass, primaryConstructor,
propertyDeclaration */
