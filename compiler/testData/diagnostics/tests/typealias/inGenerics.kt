// RUN_PIPELINE_TILL: BACKEND
// FIR_IDENTICAL
typealias MyString = String

class Container<T>(val x: T)

typealias MyStringContainer = Container<MyString?>

val ms: MyString = "MyString"

val msn: MyString? = null

val msc: MyStringContainer = Container(ms)
val msc1 = MyStringContainer(null)

/* GENERATED_FIR_TAGS: classDeclaration, nullableType, primaryConstructor, propertyDeclaration, stringLiteral,
typeAliasDeclaration, typeParameter */
