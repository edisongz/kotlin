// RUN_PIPELINE_TILL: FRONTEND
// DIAGNOSTICS: -UNUSED_PARAMETER
// LANGUAGE: -ProhibitProtectedConstructorCallFromPublicInline

class SomeContainer {
    protected class Limit

    protected fun makeLimit(): Limit = TODO()

    public inline fun foo(f: () -> Unit) {
        <!PROTECTED_CONSTRUCTOR_CALL_FROM_PUBLIC_INLINE!>Limit<!>()
        <!PROTECTED_CALL_FROM_PUBLIC_INLINE_ERROR!>makeLimit<!>()
    }
}

open class A protected constructor() {
    inline fun foo(f: () -> Unit) {
        <!PROTECTED_CONSTRUCTOR_CALL_FROM_PUBLIC_INLINE!>A<!>()
    }
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, functionalType, inline, nestedClass, primaryConstructor */
