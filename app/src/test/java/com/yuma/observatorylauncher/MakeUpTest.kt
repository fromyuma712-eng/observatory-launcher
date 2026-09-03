package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.data.Behaviour
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 出来事の取りこぼしを桶で補う計算。
 *
 * ⚠️ **これは見積りである。** ここで確かめるのは「見積りが暴れないこと」であって、
 * 見積りが当たっていることではない。**当たりは実機でしか測れない。**
 */
class MakeUpTest {

    private val hour = 60 * 60_000L

    @Test
    fun `出来事が揃っていれば何もしない`() {
        // 桶と出来事の一日合計が同じ＝取りこぼしが無い。
        assertEquals(
            2 * hour,
            Behaviour.makeUp(untilMs = 2 * hour, wholeDayMs = 5 * hour, settledMs = 5 * hour),
        )
    }

    @Test
    fun `桶の方が多ければ、その割合で補う`() {
        // 桶 6時間 ÷ 出来事 3時間 = 2倍。
        assertEquals(
            4 * hour,
            Behaviour.makeUp(untilMs = 2 * hour, wholeDayMs = 3 * hour, settledMs = 6 * hour),
        )
    }

    @Test
    fun `桶の方が少なくても、減らさない`() {
        // 桶は書き出しが遅れることがある。**下へは補正しない。**
        assertEquals(
            2 * hour,
            Behaviour.makeUp(untilMs = 2 * hour, wholeDayMs = 5 * hour, settledMs = 1 * hour),
        )
    }

    @Test
    fun `一件の記録から一日を作り出さない`() {
        // 桶 40時間 ÷ 出来事 1時間 = 40倍 になりうる。歯止めは 4倍。
        assertEquals(
            4 * hour,
            Behaviour.makeUp(untilMs = hour, wholeDayMs = hour, settledMs = 40 * hour),
        )
    }

    @Test
    fun `桶が取れなければ補わない`() {
        assertEquals(
            2 * hour,
            Behaviour.makeUp(untilMs = 2 * hour, wholeDayMs = 5 * hour, settledMs = 0L),
        )
    }

    @Test
    fun `出来事が空なら、そのまま返す`() {
        assertEquals(0L, Behaviour.makeUp(untilMs = 0L, wholeDayMs = 0L, settledMs = 5 * hour))
    }
}
