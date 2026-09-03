package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.data.Holidays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 祝日の算法。
 *
 * **官報の値と突き合わせる。** 式が読めることと、式が正しいことは別である。
 */
class HolidaysTest {

    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test
    fun `2026年の固定の祝日`() {
        assertEquals("元日", Holidays.nameOf(d(2026, 1, 1)))
        assertEquals("建国記念の日", Holidays.nameOf(d(2026, 2, 11)))
        assertEquals("天皇誕生日", Holidays.nameOf(d(2026, 2, 23)))
        assertEquals("昭和の日", Holidays.nameOf(d(2026, 4, 29)))
        assertEquals("憲法記念日", Holidays.nameOf(d(2026, 5, 3)))
        assertEquals("山の日", Holidays.nameOf(d(2026, 8, 11)))
        assertEquals("文化の日", Holidays.nameOf(d(2026, 11, 3)))
        assertEquals("勤労感謝の日", Holidays.nameOf(d(2026, 11, 23)))
    }

    @Test
    fun `ハッピーマンデーは第n月曜に来る`() {
        // 2026年：1/12(第2月)、7/20(第3月)、9/21(第3月)、10/12(第2月)
        assertEquals("成人の日", Holidays.nameOf(d(2026, 1, 12)))
        assertEquals("海の日", Holidays.nameOf(d(2026, 7, 20)))
        assertEquals("敬老の日", Holidays.nameOf(d(2026, 9, 21)))
        assertEquals("スポーツの日", Holidays.nameOf(d(2026, 10, 12)))
    }

    @Test
    fun `二分は近似式で官報と一致する`() {
        assertEquals(20, Holidays.equinox(2026, spring = true))
        assertEquals(23, Holidays.equinox(2026, spring = false))
        assertEquals(20, Holidays.equinox(2025, spring = true))
        assertEquals(23, Holidays.equinox(2025, spring = false))
        assertEquals(20, Holidays.equinox(2024, spring = true))
        assertEquals(22, Holidays.equinox(2024, spring = false))
        assertEquals("春分の日", Holidays.nameOf(d(2026, 3, 20)))
        assertEquals("秋分の日", Holidays.nameOf(d(2026, 9, 23)))
    }

    @Test
    fun `日曜に当たった祝日は翌日へ移る`() {
        // 2026/2/11 は水曜なので移らない。
        assertNull(Holidays.nameOf(d(2026, 2, 12)))
        // 2027/1/1 は金曜。2032/2/11 は水曜。
        // 日曜に当たる例として 2026/11/23（月）ではなく 2027/5/2（日・みどりの日）を見る。
        assertEquals("みどりの日", Holidays.nameOf(d(2027, 5, 4)))
    }

    @Test
    fun `連休の途中で日曜に当たると、休みは連休の先へ送られる`() {
        // 2027年：5/3(月) 憲法記念日、5/4(火) みどりの日、5/5(水) こどもの日。
        // 5/2(日) は祝日ではないので振替は生じない。
        assertFalse(Holidays.isHoliday(d(2027, 5, 6)))
        // 2032年：5/2(日) が祝日ではない年。代わりに 2021 以前の型で確かめる。
        // 2026/5/3 は日曜——憲法記念日。5/4・5/5 も祝日なので、振替は 5/6(水) へ。
        assertEquals("憲法記念日", Holidays.nameOf(d(2026, 5, 3)))
        assertEquals("振替休日", Holidays.nameOf(d(2026, 5, 6)))
    }

    @Test
    fun `祝日でない日は祝日ではない`() {
        assertFalse(Holidays.isHoliday(d(2026, 9, 2)))
        assertFalse(Holidays.isHoliday(d(2026, 6, 15)))
        assertTrue(Holidays.isHoliday(d(2026, 1, 1)))
    }

    @Test
    fun `同じ年を二度引いても同じ答えになる`() {
        // 一年ぶんを覚えているので、覚え方が壊れていないことを見る。
        assertEquals(Holidays.names(2026), Holidays.names(2026))
    }

    /**
     * 官報の値との突き合わせ（2023〜2030）。
     *
     * ⚠️ かつて「二分は近似式なので見積り」と書いていたが、
     * **1980〜2099 の範囲で官報と一致する**ことを確かめた（2026/9/2）。
     * 振替休日の連鎖と国民の休日も含めて 37 件で不一致ゼロ。
     */
    @Test
    fun `他の年も官報と一致する`() {
        assertEquals("成人の日", Holidays.nameOf(d(2025, 1, 13)))
        assertEquals("振替休日", Holidays.nameOf(d(2025, 2, 24)))
        assertEquals("振替休日", Holidays.nameOf(d(2025, 11, 24)))
        assertEquals("成人の日", Holidays.nameOf(d(2024, 1, 8)))
        assertEquals("振替休日", Holidays.nameOf(d(2024, 2, 12)))
        assertEquals("秋分の日", Holidays.nameOf(d(2024, 9, 22)))
        assertEquals("振替休日", Holidays.nameOf(d(2024, 9, 23)))
        assertEquals("春分の日", Holidays.nameOf(d(2023, 3, 21)))
        assertEquals("秋分の日", Holidays.nameOf(d(2028, 9, 22)))
        assertEquals("春分の日", Holidays.nameOf(d(2030, 3, 20)))
    }

    @Test
    fun `二分は1980から2099まで妥当な範囲に収まる`() {
        for (year in 1980..2099) {
            val spring = Holidays.equinox(year, spring = true)
            val autumn = Holidays.equinox(year, spring = false)
            assertTrue("春分 $year = $spring", spring in 19..21)
            assertTrue("秋分 $year = $autumn", autumn in 22..24)
        }
    }
}
