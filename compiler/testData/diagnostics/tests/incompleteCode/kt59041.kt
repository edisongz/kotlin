// RUN_PIPELINE_TILL: FRONTEND
// DIAGNOSTICS: -UNUSED_VARIABLE

fun main() {
     val list = <!UNRESOLVED_REFERENCE!>mutable<!> <!DEBUG_INFO_MISSING_UNRESOLVED!>ListOf<!><!SYNTAX!><<!><!DEBUG_INFO_MISSING_UNRESOLVED!>Int<!><!SYNTAX!>><!>(1) {}
}

/* GENERATED_FIR_TAGS: functionDeclaration, integerLiteral, lambdaLiteral, localProperty, propertyDeclaration */
