// RUN_PIPELINE_TILL: FRONTEND
// RENDER_DIAGNOSTICS_FULL_TEXT
fun foo(z: (Int) -> String) {
    foo <!TYPE_MISMATCH!>{<!TYPE_MISMATCH!><!>}<!>
}

/* GENERATED_FIR_TAGS: functionDeclaration, functionalType, lambdaLiteral */
