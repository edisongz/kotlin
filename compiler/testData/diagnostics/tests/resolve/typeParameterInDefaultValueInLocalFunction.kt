// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
// DIAGNOSTICS: -UNUSED_PARAMETER

fun foo() {
    fun <T> bar(x: List<T> = listOf<T>()) {}
}

fun <T> listOf(): List<T> = TODO()

/* GENERATED_FIR_TAGS: functionDeclaration, localFunction, nullableType, typeParameter */
