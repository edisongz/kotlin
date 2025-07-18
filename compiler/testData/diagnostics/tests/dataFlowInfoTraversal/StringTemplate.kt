// RUN_PIPELINE_TILL: FRONTEND
// CHECK_TYPE

fun foo(x: Number, y: String?): String {
    val result = "abcde $x ${x as Int} ${y!!} $x $y"
    checkSubtype<Int>(<!DEBUG_INFO_SMARTCAST!>x<!>)
    checkSubtype<String>(<!DEBUG_INFO_SMARTCAST!>y<!>)
    return result
}

/* GENERATED_FIR_TAGS: asExpression, checkNotNullCall, classDeclaration, funWithExtensionReceiver, functionDeclaration,
functionalType, infix, localProperty, nullableType, propertyDeclaration, smartcast, stringLiteral, typeParameter,
typeWithExtension */
