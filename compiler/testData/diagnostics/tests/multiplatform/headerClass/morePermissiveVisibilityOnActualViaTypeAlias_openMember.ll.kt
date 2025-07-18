// IGNORE_FIR_DIAGNOSTICS
// RUN_PIPELINE_TILL: FIR2IR
// DIAGNOSTICS: -UNUSED_PARAMETER
// MODULE: m1-common
// FILE: common.kt

expect open class Container {
    internal open fun internalFun()
}

// MODULE: m2-jvm()()(m1-common)

// FILE: foo/Foo.java

package foo;

public class Foo {
    public void internalFun() {}
}

// FILE: jvm.kt

actual typealias <!EXPECT_ACTUAL_INCOMPATIBLE_CLASS_SCOPE!>Container<!> = foo.Foo

/* GENERATED_FIR_TAGS: actual, classDeclaration, expect, functionDeclaration, javaType, typeAliasDeclaration */
