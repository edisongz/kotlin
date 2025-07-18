// RUN_PIPELINE_TILL: FRONTEND
// LANGUAGE: -SuspendConversion
// CHECK_TYPE
// DIAGNOSTICS: -UNUSED_PARAMETER -ASSIGNED_BUT_NEVER_ACCESSED_VARIABLE -UNUSED_VALUE -UNUSED_VARIABLE

fun builder(c: suspend () -> Int) = 1
fun <T> genericBuilder(c: suspend () -> T): T = null!!
fun unitBuilder(c: suspend () -> Unit) = 1
fun emptyBuilder(c: suspend () -> Unit) = 1

fun <T> manyArgumentsBuilder(
        c1: suspend () -> Unit,
        c2: suspend () -> T,
        c3: suspend () -> Int
):T = null!!

fun severalParamsInLambda(c: suspend (String, Int) -> Unit) {}

fun foo() {
    builder({ 1 })
    builder { 1 }

    val x = { 1 }
    builder(x)
    builder({1} <!UNCHECKED_CAST!>as (suspend () -> Int)<!>)

    var i: Int = 1
    i = genericBuilder({ 1 })
    i = genericBuilder { 1 }
    genericBuilder { 1 }
    genericBuilder<Int> { 1 }
    genericBuilder<Int> { <!RETURN_TYPE_MISMATCH!>""<!> }

    val y = { 1 }
    genericBuilder(y)

    unitBuilder {}
    unitBuilder { 1 }
    unitBuilder({})
    unitBuilder({ 1 })

    manyArgumentsBuilder({}, { "" }) { 1 }

    val s: String = manyArgumentsBuilder({}, { "" }) { 1 }

    manyArgumentsBuilder<String>({}, { "" }, { 1 })
    manyArgumentsBuilder<String>({}, { <!RETURN_TYPE_MISMATCH!>1<!> }, { 2 })

    severalParamsInLambda { x, y ->
        x checkType { _<String>() }
        y checkType { _<Int>() }
    }
}

/* GENERATED_FIR_TAGS: asExpression, assignment, checkNotNullCall, classDeclaration, funWithExtensionReceiver,
functionDeclaration, functionalType, infix, integerLiteral, lambdaLiteral, localProperty, nullableType,
propertyDeclaration, stringLiteral, suspend, typeParameter, typeWithExtension */
