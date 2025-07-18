// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
// CHECK_TYPE

// FILE: foo/Base.java

package foo;

public interface Base<T> {}

// FILE: foo/HS.java

package foo;

public class HS<T> extends Base<T> {}

// FILE: k.kt

import foo.*;

fun <T, C: Base<T>> convert(src: HS<T>, dest: C): C = throw Exception("$src $dest")

fun test(l: HS<Int>) {
    //todo should be inferred
    val r = convert(l, HS())
    r checkType { _<HS<Int>>() }
}

/* GENERATED_FIR_TAGS: classDeclaration, flexibleType, funWithExtensionReceiver, functionDeclaration, functionalType,
infix, javaFunction, javaType, lambdaLiteral, localProperty, nullableType, propertyDeclaration, stringLiteral,
typeConstraint, typeParameter, typeWithExtension */
