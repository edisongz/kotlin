// ISSUE: KT-57192
// Promise<Unit> wrongly raised NON_EXPORTABLE_TYPE

@file:OptIn(ExperimentalJsExport::class)
import kotlin.js.Promise

@JsExport
fun fooInt(p: Promise<Int>): Promise<Int>? = p

<!NON_EXPORTABLE_TYPE!>@JsExport
fun fooUnitReturn(): Promise<Unit>?<!> = null

@JsExport
fun fooUnitArgument(<!NON_EXPORTABLE_TYPE!>p: Promise<Unit><!>) {
    p.then {}
}

@JsExport
interface I<T> {
    fun bar(): T
}

@JsExport
fun fooIIntArgument(i: I<Int>) = i.bar()

@JsExport
fun fooIUnitArgument(<!NON_EXPORTABLE_TYPE!>i: I<Unit><!>) = i.bar()
