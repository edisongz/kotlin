// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
//KT-337 Can't break a line before a dot

class A() {
    fun foo() {}
}

fun test() {
    val a = A()

    a

      .foo() // Should be a valid expression

}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, localProperty, primaryConstructor, propertyDeclaration */
