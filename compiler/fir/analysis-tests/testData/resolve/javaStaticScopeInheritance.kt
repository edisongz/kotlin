// RUN_PIPELINE_TILL: BACKEND
// FILE: A.java

class A {
    public static final A VALUE = new A();
}

// FILE: B.java

class B extends A {
    public static final B VALUE = new B();
}

// FILE: main.kt

fun main() {
    val b = B.VALUE // <- should be B
}

/* GENERATED_FIR_TAGS: flexibleType, functionDeclaration, javaProperty, localProperty, propertyDeclaration */
