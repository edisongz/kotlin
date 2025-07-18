// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
// DIAGNOSTICS: -UNUSED_PARAMETER

package kt7351

interface Node

interface Source<T> {
    fun f() : T
}
fun <T, S : Source<T>> S.woo() : T = this.f()

fun Node.append(block : Source<Int>.() -> Unit) {
}

fun crashMe(node : Node) {
    node.append {
        woo()
    }
}

/* GENERATED_FIR_TAGS: funWithExtensionReceiver, functionDeclaration, functionalType, interfaceDeclaration,
lambdaLiteral, nullableType, thisExpression, typeConstraint, typeParameter, typeWithExtension */
