package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.ui.FiveNumber
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 五数要約の試験。
 *
 * 箱ひげは**四分位の取り方で見た目が変わる**。取り方を決めたら、
 * その決め方どおりに動くことをここで固定しておかないと、
 * 後で「なんとなく箱が広い」という形でしか気付けない。
 */
class FiveNumberTest {

    private fun of(vararg v: Float) = FiveNumber.of(v.toList())!!

    /** 奇数個。中央値は真ん中の一つで、四分位はそれを**含まない**半分から取る。 */
    @Test
    fun anOddSampleExcludesTheMedianFromTheHalves() {
        // 1 2 3 4 5 → 下位 [1,2] 上位 [4,5]
        val f = of(1f, 2f, 3f, 4f, 5f)
        assertEquals(1f, f.min, 0f)
        assertEquals(1.5f, f.q1, 0f)
        assertEquals(3f, f.median, 0f)
        assertEquals(4.5f, f.q3, 0f)
        assertEquals(5f, f.max, 0f)
    }

    /** 偶数個。中央値は真ん中二つの平均。 */
    @Test
    fun anEvenSampleAveragesTheMiddleTwo() {
        // 1 2 3 4 → 下位 [1,2] 上位 [3,4]
        val f = of(1f, 2f, 3f, 4f)
        assertEquals(1.5f, f.q1, 0f)
        assertEquals(2.5f, f.median, 0f)
        assertEquals(3.5f, f.q3, 0f)
    }

    /** 並んでいない入力でも同じ結果になる。**並べ替えは中でやる。** */
    @Test
    fun theInputNeedNotBeSorted() {
        assertEquals(of(1f, 2f, 3f, 4f, 5f), of(4f, 1f, 5f, 3f, 2f))
    }

    /** 一つしか無ければ、五つとも同じ値。**箱が潰れるのが正しい。** */
    @Test
    fun aSingleValueCollapsesTheBox() {
        val f = of(7f)
        assertEquals(7f, f.min, 0f)
        assertEquals(7f, f.q1, 0f)
        assertEquals(7f, f.median, 0f)
        assertEquals(7f, f.q3, 0f)
        assertEquals(7f, f.max, 0f)
    }

    /** 全部同じでも潰れるだけで、壊れない。 */
    @Test
    fun anIdenticalSampleIsFlat() {
        val f = of(3f, 3f, 3f, 3f)
        assertEquals(3f, f.q1, 0f)
        assertEquals(3f, f.q3, 0f)
    }

    /** 空なら null。**無い標本に箱を描かない。** */
    @Test
    fun anEmptySampleHasNoSummary() {
        assertNull(FiveNumber.of(emptyList()))
    }

    /** 七日ぶんの現実的な値で、箱がひげの内側に収まる。 */
    @Test
    fun theBoxSitsInsideTheWhiskers() {
        val f = of(12f, 95f, 31f, 22f, 44f, 8f, 60f)
        org.junit.Assert.assertTrue(f.min <= f.q1)
        org.junit.Assert.assertTrue(f.q1 <= f.median)
        org.junit.Assert.assertTrue(f.median <= f.q3)
        org.junit.Assert.assertTrue(f.q3 <= f.max)
    }
}
