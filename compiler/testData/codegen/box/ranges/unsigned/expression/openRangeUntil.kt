// Auto-generated by org.jetbrains.kotlin.generators.tests.GenerateRangesCodegenTestData. DO NOT EDIT!
// LANGUAGE: +RangeUntilOperator
// WITH_STDLIB



@OptIn(ExperimentalStdlibApi::class)
fun box(): String {
    val list1 = ArrayList<UInt>()
    val range1 = 1u..<5u
    for (i in range1) {
        list1.add(i)
        if (list1.size > 23) break
    }
    if (list1 != listOf<UInt>(1u, 2u, 3u, 4u)) {
        return "Wrong elements for 1u..<5u: $list1"
    }

    val list2 = ArrayList<UInt>()
    val range2 = 1u.toUByte()..<5u.toUByte()
    for (i in range2) {
        list2.add(i)
        if (list2.size > 23) break
    }
    if (list2 != listOf<UInt>(1u, 2u, 3u, 4u)) {
        return "Wrong elements for 1u.toUByte()..<5u.toUByte(): $list2"
    }

    val list3 = ArrayList<UInt>()
    val range3 = 1u.toUShort()..<5u.toUShort()
    for (i in range3) {
        list3.add(i)
        if (list3.size > 23) break
    }
    if (list3 != listOf<UInt>(1u, 2u, 3u, 4u)) {
        return "Wrong elements for 1u.toUShort()..<5u.toUShort(): $list3"
    }

    val list4 = ArrayList<ULong>()
    val range4 = 1uL..<5uL
    for (i in range4) {
        list4.add(i)
        if (list4.size > 23) break
    }
    if (list4 != listOf<ULong>(1u, 2u, 3u, 4u)) {
        return "Wrong elements for 1uL..<5uL: $list4"
    }

    return "OK"
}
