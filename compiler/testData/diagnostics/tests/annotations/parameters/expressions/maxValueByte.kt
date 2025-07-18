// RUN_PIPELINE_TILL: FRONTEND
package test

annotation class Ann(
        val p1: Byte,
        val p2: Byte,
        val p3: Int,
        val p4: Int,
        val p5: Byte
)

@Ann(
    p1 = <!TYPE_MISMATCH!>java.lang.Byte.MAX_VALUE + 1<!>,
    p2 = <!INTEGER_OPERATOR_RESOLVE_WILL_CHANGE!>1 + 1<!>,
    p3 = java.lang.Byte.MAX_VALUE + 1,
    p4 = 1.toByte() + 1.toByte(),
    p5 = <!TYPE_MISMATCH!>1.toByte() + 1.toByte()<!>
) class MyClass

// EXPECTED: @Ann(p1 = 128, p2 = 2.toByte(), p3 = 128, p4 = 2, p5 = 2)

/* GENERATED_FIR_TAGS: additiveExpression, annotationDeclaration, classDeclaration, integerLiteral, javaProperty,
primaryConstructor, propertyDeclaration */
