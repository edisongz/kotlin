// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
typealias ReadonlyArray<T> = Array<T>

fun printAll(vararg values: Any?) {}

fun main() {
    var a: ReadonlyArray<String> = arrayOf("a")
    printAll(values = a)
}

/* GENERATED_FIR_TAGS: functionDeclaration, localProperty, nullableType, propertyDeclaration, stringLiteral,
typeAliasDeclaration, typeAliasDeclarationWithTypeParameter, typeParameter, vararg */
