package com.yuma.observatorylauncher

import android.app.usage.UsageEvents
import com.yuma.observatorylauncher.data.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 解錠回数の試験。
 *
 * `UsageEvents` は端末の中でしか作れないので、**数える所だけを切り出して**確かめる。
 * 数え違いは例外にならず、ただ違う数字が出るだけなので、ここで留めるしかない。
 */
class TouchTest {

    private val t0 = 1_756_000_000_000L
    private val unlock = 18 // UsageEvents.Event.KEYGUARD_HIDDEN
    private val resumed = UsageEvents.Event.ACTIVITY_RESUMED

    @Test
    fun unlocksAreCounted() {
        val touch = Usage.countTouches(
            listOf(
                unlock to t0,
                resumed to t0 + 1_000,
                unlock to t0 + 60_000,
                unlock to t0 + 120_000,
            ),
        )
        assertEquals(3, touch.unlocks)
    }

    /** 画面に出ただけの出来事は解錠に数えない。 */
    @Test
    fun resumingAnAppIsNotAnUnlock() {
        val touch = Usage.countTouches(listOf(resumed to t0, resumed to t0 + 1_000))
        assertEquals(0, touch.unlocks)
    }

    /** 最初と最後は、解錠と前面化の両方から取る。 */
    @Test
    fun theOutlineOfTheDayComesFromBothKinds() {
        val touch = Usage.countTouches(
            listOf(
                resumed to t0 + 5_000,
                unlock to t0,
                resumed to t0 + 90_000,
            ),
        )
        assertEquals(t0, touch.firstAt)
        assertEquals(t0 + 90_000, touch.lastAt)
    }

    /** **順に並んでいることを前提にしない。** 端末が前後させた日だけ壊れる、を避ける。 */
    @Test
    fun theOrderOfTheInputDoesNotMatter() {
        val forward = Usage.countTouches(listOf(unlock to t0, unlock to t0 + 60_000))
        val backward = Usage.countTouches(listOf(unlock to t0 + 60_000, unlock to t0))
        assertEquals(forward, backward)
    }

    /** 何も無い日は 0 回で、時刻は持たない——0時0分と書かない。 */
    @Test
    fun anUntouchedDayHasNoTimes() {
        val touch = Usage.countTouches(emptyList())
        assertEquals(0, touch.unlocks)
        assertNull(touch.firstAt)
        assertNull(touch.lastAt)
    }

    /** 知らない種類の出来事は数にも輪郭にも入れない。 */
    @Test
    fun unknownEventsAreIgnored() {
        val touch = Usage.countTouches(listOf(99 to t0, 100 to t0 + 1_000))
        assertEquals(0, touch.unlocks)
        assertNull(touch.firstAt)
    }

    // ── 摩擦のための直近の様子 ─────────────────────────────

    private val paused = UsageEvents.Event.ACTIVITY_PAUSED

    /** 開いて閉じた分が使用時間になり、閉じた時刻が残る。 */
    @Test
    fun openAndCloseGivesUsageAndACloseTime() {
        val r = Usage.recentOf(
            listOf(resumed to t0, paused to t0 + 300_000),
            now = t0 + 900_000,
        )
        assertEquals(300_000, r.usedMs)
        assertEquals(t0 + 300_000, r.lastClosedAt)
    }

    /** **今まさに開いているなら「閉じた時刻」は無い。** 再入場の判定に使ってはならない。 */
    @Test
    fun anAppStillOpenHasNoCloseTime() {
        val r = Usage.recentOf(listOf(resumed to t0), now = t0 + 120_000)
        assertEquals(120_000, r.usedMs)
        assertNull(r.lastClosedAt)
    }

    /** 何度も開き閉じした分は足し合わさり、最後に閉じた時刻が残る。 */
    @Test
    fun repeatedSessionsAccumulate() {
        val r = Usage.recentOf(
            listOf(
                resumed to t0, paused to t0 + 60_000,
                resumed to t0 + 600_000, paused to t0 + 720_000,
            ),
            now = t0 + 900_000,
        )
        assertEquals(180_000, r.usedMs)
        assertEquals(t0 + 720_000, r.lastClosedAt)
    }

    /** 出来事が前後していても結果は変わらない。 */
    @Test
    fun theOrderOfEventsDoesNotMatterForRecent() {
        val forward = Usage.recentOf(
            listOf(resumed to t0, paused to t0 + 60_000), now = t0 + 60_000,
        )
        val shuffled = Usage.recentOf(
            listOf(paused to t0 + 60_000, resumed to t0), now = t0 + 60_000,
        )
        assertEquals(forward, shuffled)
    }

    /** 今日まだ触っていなければ、使用 0・閉じた時刻なし。 */
    @Test
    fun anUntouchedAppHasNothing() {
        val r = Usage.recentOf(emptyList(), now = t0)
        assertEquals(0L, r.usedMs)
        assertNull(r.lastClosedAt)
    }
}
