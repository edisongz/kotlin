// RUN_PIPELINE_TILL: FRONTEND
// ISSUE: KT-66186
// WITH_STDLIB
// FULL_JDK

fun test() {
    val list = listOf("asd", "sda")
    list.stream()
        .filter(String::isNotEmpty)
        .<!NEW_INFERENCE_NO_INFORMATION_FOR_PARAMETER!>collect<!>(<!UNRESOLVED_REFERENCE!>FakeClass<!>.<!DEBUG_INFO_MISSING_UNRESOLVED!>toList<!>())
}

/* GENERATED_FIR_TAGS: callableReference, flexibleType, functionDeclaration, inProjection, localProperty,
propertyDeclaration, samConversion, stringLiteral */
