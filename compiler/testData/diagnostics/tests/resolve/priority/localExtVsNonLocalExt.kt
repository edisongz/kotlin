// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
// CHECK_TYPE
// DIAGNOSTICS: -UNUSED_EXPRESSION -UNUSED_PARAMETER -UNUSED_VARIABLE


class A

fun A.foo() = this

fun test(a: A) {
    fun A.foo() = 3

    a.foo() checkType { _<Int>() }
    with(a) {
        foo() checkType { _<Int>() }
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, funWithExtensionReceiver, functionDeclaration, functionalType, infix,
integerLiteral, lambdaLiteral, localFunction, nullableType, thisExpression, typeParameter, typeWithExtension */
