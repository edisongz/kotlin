// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
@Target(AnnotationTarget.TYPE) annotation class base

<!WRONG_ANNOTATION_TARGET!>@base<!> annotation class derived

<!WRONG_ANNOTATION_TARGET!>@base<!> class correct(<!WRONG_ANNOTATION_TARGET!>@base<!> val x: @base Int) {
    <!WRONG_ANNOTATION_TARGET!>@base<!> constructor(): this(0)
}

<!WRONG_ANNOTATION_TARGET!>@base<!> enum class My <!WRONG_ANNOTATION_TARGET!>@base<!> constructor() {
    <!WRONG_ANNOTATION_TARGET!>@base<!> FIRST,
    <!WRONG_ANNOTATION_TARGET!>@base<!> SECOND
}

<!WRONG_ANNOTATION_TARGET!>@base<!> fun foo(<!WRONG_ANNOTATION_TARGET!>@base<!> y: @base Int): Int {
    <!WRONG_ANNOTATION_TARGET!>@base<!> fun bar(<!WRONG_ANNOTATION_TARGET!>@base<!> z: @base Int) = z + 1
    <!WRONG_ANNOTATION_TARGET!>@base<!> val local = bar(y)
    return local
}

<!WRONG_ANNOTATION_TARGET!>@base<!> val z = 0

/* GENERATED_FIR_TAGS: additiveExpression, annotationDeclaration, classDeclaration, enumDeclaration, enumEntry,
functionDeclaration, integerLiteral, localFunction, localProperty, primaryConstructor, propertyDeclaration,
secondaryConstructor */
