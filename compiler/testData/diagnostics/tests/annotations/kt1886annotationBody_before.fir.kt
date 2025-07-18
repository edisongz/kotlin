// RUN_PIPELINE_TILL: FRONTEND
// LANGUAGE: -NestedClassesInAnnotations

annotation class Annotation2() {
    public <!ANNOTATION_CLASS_MEMBER!>val s: String<!> = ""
}

annotation class Annotation3() {
    public <!ANNOTATION_CLASS_MEMBER!>fun foo()<!> {}
}

annotation class Annotation4() {
    class Foo() {}
}

annotation class Annotation5() {
    companion object {}
}

annotation class Annotation6() {
    <!ANNOTATION_CLASS_MEMBER!>init<!> {}
}

annotation class Annotation1() {}

annotation class Annotation7(val name: String) {}

annotation class Annotation8(<!VAR_ANNOTATION_PARAMETER!>var<!> name: String = "") {}

annotation class Annotation9(val name: String)

annotation class Annotation10

/* GENERATED_FIR_TAGS: annotationDeclaration, classDeclaration, companionObject, functionDeclaration, init, nestedClass,
objectDeclaration, primaryConstructor, propertyDeclaration, stringLiteral */
