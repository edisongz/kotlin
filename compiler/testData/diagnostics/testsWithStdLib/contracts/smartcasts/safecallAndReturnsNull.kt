// RUN_PIPELINE_TILL: FRONTEND
// FIR_IDENTICAL
// LANGUAGE: +AllowContractsForCustomFunctions +UseReturnsEffect
// OPT_IN: kotlin.contracts.ExperimentalContracts

import kotlin.contracts.*

fun Any.nullWhenString(): Any? {
    contract {
        returns(null) implies (this@nullWhenString is String)
    }
    return if (this is String) null else this
}

fun test(x: Int?) {
    if (x?.nullWhenString() == null) {
        x.<!UNRESOLVED_REFERENCE!>length<!>
    }
}

/* GENERATED_FIR_TAGS: contractConditionalEffect, contracts, equalityExpression, funWithExtensionReceiver,
functionDeclaration, ifExpression, isExpression, lambdaLiteral, nullableType, safeCall, smartcast, thisExpression */
