// RUN_PIPELINE_TILL: FRONTEND
// DIAGNOSTICS: -UNREACHABLE_CODE
//KT-2445 Calling method with function with generic parameter causes compile-time exception
package a

fun main() {
    <!NEW_INFERENCE_NO_INFORMATION_FOR_PARAMETER!>test<!> {

    }
}

fun <R> test(callback: (R) -> Unit):Unit = callback(null!!)

/* GENERATED_FIR_TAGS: checkNotNullCall, functionDeclaration, functionalType, lambdaLiteral, nullableType, typeParameter */
