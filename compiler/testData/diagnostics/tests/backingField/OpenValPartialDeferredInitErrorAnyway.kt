// RUN_PIPELINE_TILL: FRONTEND
// DIAGNOSTICS: -DEBUG_INFO_LEAKING_THIS
// LANGUAGE:-ProhibitOpenValDeferredInitialization
open class Foo {
    <!MUST_BE_INITIALIZED_OR_BE_ABSTRACT!>open val foo: Int<!>

    init {
        if (1 != 1) {
            foo = 1
        }
    }
}

/* GENERATED_FIR_TAGS: assignment, classDeclaration, equalityExpression, ifExpression, init, integerLiteral,
propertyDeclaration */
