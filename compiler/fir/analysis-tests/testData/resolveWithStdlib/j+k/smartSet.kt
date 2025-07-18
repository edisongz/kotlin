// RUN_PIPELINE_TILL: BACKEND
import java.util.AbstractSet

class SmartSet<T> : AbstractSet<T>() {
    override var size: Int = 0
    override fun iterator(): MutableIterator<T> = TODO()
    override fun add(element: T): Boolean = true
    override fun clear() {}
    override fun contains(element: T): Boolean = false
}

fun foo(x: Any) {
    val s = SmartSet<Any>()
    s.add(x)
}

/* GENERATED_FIR_TAGS: classDeclaration, functionDeclaration, integerLiteral, localProperty, nullableType, operator,
override, propertyDeclaration, typeParameter */
