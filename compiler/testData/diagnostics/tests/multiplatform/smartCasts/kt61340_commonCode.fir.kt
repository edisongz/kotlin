// IGNORE_FIR_DIAGNOSTICS
// RUN_PIPELINE_TILL: FRONTEND
// DIAGNOSTICS: -DEBUG_INFO_SMARTCAST
// MODULE: m1-common
// FILE: common.kt

expect val foo: Any

expect class Bar() {
    val bus: Any
}

fun common() {
    if (foo is String) <!SMARTCAST_IMPOSSIBLE!>foo<!>.length

    val bar = Bar()
    if (bar.bus is String) <!SMARTCAST_IMPOSSIBLE!>bar.bus<!>.length
}

// MODULE: m1-jvm()()(m1-common)
// FILE: jvm.kt
actual val foo: Any = 2

actual class Bar actual constructor() {
    actual val bus: Any
        get() = "bus"
}

/* GENERATED_FIR_TAGS: actual, classDeclaration, expect, functionDeclaration, getter, ifExpression, integerLiteral,
isExpression, localProperty, primaryConstructor, propertyDeclaration, smartcast, stringLiteral */
