// RUN_PIPELINE_TILL: FRONTEND
// ISSUE: KT-53740
// CHECK_TYPE_WITH_EXACT

fun test() {
    val buildee = parallelInOutBuild(
        {
            setInProjectedTypeVariable(TargetType())
        },
        {
            consumeDifferentType(<!ARGUMENT_TYPE_MISMATCH!>getOutProjectedTypeVariable()<!>)
        }
    )
    // exact type equality check — turns unexpected compile-time behavior into red code
    // considered to be non-user-reproducible code for the purposes of these tests
    checkExactType<Buildee<TargetType>>(buildee)
}




class TargetType
class DifferentType

fun consumeDifferentType(value: DifferentType) {}

class Buildee<TV> {
    fun setTypeVariable(value: TV) { storage = value }
    fun getTypeVariable(): TV = storage
    private var storage: TV = null!!
}

class OutBuildee<out OTV>(private val buildee: Buildee<out OTV>) {
    fun getOutProjectedTypeVariable(): OTV = buildee.getTypeVariable()
}

class InBuildee<in ITV>(private val buildee: Buildee<in ITV>) {
    fun setInProjectedTypeVariable(value: ITV) { buildee.setTypeVariable(value) }
}

fun <PTV> parallelInOutBuild(
    inProjectedInstructions: InBuildee<PTV>.(PTV) -> Unit,
    outProjectedInstructions: OutBuildee<PTV>.(PTV) -> Unit
): Buildee<PTV> {
    return null!!
}

/* GENERATED_FIR_TAGS: assignment, capturedType, checkNotNullCall, classDeclaration, functionDeclaration, functionalType,
in, inProjection, lambdaLiteral, localProperty, nullableType, out, outProjection, primaryConstructor,
propertyDeclaration, stringLiteral, typeParameter, typeWithExtension */
