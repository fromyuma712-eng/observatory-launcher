package com.yuma.observatorylauncher

import android.app.usage.UsageEvents
import com.yuma.observatorylauncher.data.Behaviour
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 使い方の分析の試験。
 *
 * 数え違いは例外にならず、**ただ違う数字が出るだけ**である。
 * しかもこの数字は行動を変えるために出すものなので、間違っていたら害の方が大きい。
 * 境目を一つずつ押さえる。
 */
class BehaviourTest {

    private val resumed = UsageEvents.Event.ACTIVITY_RESUMED
    private val paused = UsageEvents.Event.ACTIVITY_PAUSED
    private val stopped = UsageEvents.Event.ACTIVITY_STOPPED
    private val screenOff = 16

    /** その日の 0 時。時間帯の試験は端末の時間帯に依るので、暦から作る。 */
    private fun at(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(Calendar.YEAR, 2026)
        set(Calendar.MONTH, Calendar.SEPTEMBER)
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ── 一続きと回数 ───────────────────────────────────────

    /** 開いて閉じたら一続き。**回数は開いた数**で数える。 */
    @Test
    fun anOpenAndCloseMakesOneSession() {
        val a = Behaviour.analyze(
            listOf(
                Behaviour.Moment("x", "", resumed, at(10)),
                Behaviour.Moment("x", "", paused, at(10, 30)),
            ),
            now = at(12),
        )
        assertEquals(1, a.sessions.size)
        assertEquals(30 * 60_000L, a.totalMs)
        assertEquals(1, a.opens["x"])
    }

    /** **五分を六回と、三十分を一回は違う。** 合計が同じでも回数で分かれる。 */
    @Test
    fun sixShortVisitsDifferFromOneLongOne() {
        val many = Behaviour.analyze(
            (0 until 6).flatMap {
                listOf(
                    Behaviour.Moment("x", "", resumed, at(10, it * 10)),
                    Behaviour.Moment("x", "", paused, at(10, it * 10 + 5)),
                )
            },
            now = at(12),
        )
        val once = Behaviour.analyze(
            listOf(Behaviour.Moment("x", "", resumed, at(10)), Behaviour.Moment("x", "", paused, at(10, 30))),
            now = at(12),
        )
        assertEquals(many.totalMs, once.totalMs)
        assertEquals(6, many.opensTotal)
        assertEquals(1, once.opensTotal)
        assertTrue(many.averageMs < once.averageMs)
    }

    /** 前面のまま終わっていれば、今までを数える。 */
    @Test
    fun anAppStillOpenCountsUpToNow() {
        val a = Behaviour.analyze(listOf(Behaviour.Moment("x", "", resumed, at(10))), now = at(10, 20))
        assertEquals(20 * 60_000L, a.totalMs)
    }

    /** **画面が消えたら全部閉じる。** 消したまま朝を迎えたアプリに一晩を付けない。 */
    @Test
    fun theScreenGoingOffClosesEverything() {
        val a = Behaviour.analyze(
            listOf(
                Behaviour.Moment("x", "", resumed, at(23)),
                Behaviour.Moment("y", "", resumed, at(23, 10)),
                Behaviour.Moment("", "", screenOff, at(23, 30)),
            ),
            now = at(9) + 24 * 60 * 60_000L,
        )
        assertEquals(2, a.sessions.size)
        assertEquals(30 * 60_000L + 20 * 60_000L, a.totalMs)
    }

    /** 同じアプリの重複した開始は一度だけ数える。 */
    @Test
    fun repeatedResumesWithoutAPauseCountOnce() {
        val a = Behaviour.analyze(
            listOf(
                Behaviour.Moment("x", "", resumed, at(10)),
                Behaviour.Moment("x", "", resumed, at(10, 1)),
                Behaviour.Moment("x", "", paused, at(10, 5)),
            ),
            now = at(11),
        )
        assertEquals(1, a.opensTotal)
        assertEquals(5 * 60_000L, a.totalMs)
    }

    /** 並び順は前提にしない。 */
    @Test
    fun theOrderOfEventsDoesNotMatter() {
        val forward = Behaviour.analyze(
            listOf(Behaviour.Moment("x", "", resumed, at(10)), Behaviour.Moment("x", "", paused, at(10, 30))),
            now = at(11),
        )
        val shuffled = Behaviour.analyze(
            listOf(Behaviour.Moment("x", "", paused, at(10, 30)), Behaviour.Moment("x", "", resumed, at(10))),
            now = at(11),
        )
        assertEquals(forward.totalMs, shuffled.totalMs)
    }

    // ── 時間帯 ─────────────────────────────────────────────

    /** **跨いだ分は跨いだ先へも配る。** 始まりの枠へ全部入れない。 */
    @Test
    fun aSessionIsSpreadAcrossTheHoursItCovers() {
        val a = Behaviour.analyze(
            listOf(Behaviour.Moment("x", "", resumed, at(10, 30)), Behaviour.Moment("x", "", paused, at(12, 15))),
            now = at(13),
        )
        assertEquals(30 * 60_000L, a.hourly[10])
        assertEquals(60 * 60_000L, a.hourly[11])
        assertEquals(15 * 60_000L, a.hourly[12])
        assertEquals(0L, a.hourly[9])
    }

    /** 夜（22時〜翌5時）は別に数える。**夜の時間は昼と重さが違う。** */
    @Test
    fun theNightIsCountedSeparately() {
        val a = Behaviour.analyze(
            listOf(Behaviour.Moment("x", "", resumed, at(21, 30)), Behaviour.Moment("x", "", paused, at(23, 0))),
            now = at(23, 30),
        )
        // 21:30-22:00 は昼、22:00-23:00 が夜。
        assertEquals(60 * 60_000L, a.lateMs)
    }

    @Test
    fun daytimeIsNotCountedAsNight() {
        val a = Behaviour.analyze(
            listOf(Behaviour.Moment("x", "", resumed, at(13)), Behaviour.Moment("x", "", paused, at(14))),
            now = at(15),
        )
        assertEquals(0L, a.lateMs)
    }

    // ── まとめ ─────────────────────────────────────────────

    /** 最も長い一続きを拾う。 */
    @Test
    fun theLongestSessionIsFound() {
        val a = Behaviour.analyze(
            listOf(
                Behaviour.Moment("x", "", resumed, at(9)), Behaviour.Moment("x", "", paused, at(9, 10)),
                Behaviour.Moment("y", "", resumed, at(10)), Behaviour.Moment("y", "", paused, at(11)),
                Behaviour.Moment("z", "", resumed, at(12)), Behaviour.Moment("z", "", paused, at(12, 20)),
            ),
            now = at(13),
        )
        assertEquals("y", a.longest?.packageName)
        assertEquals(60 * 60_000L, a.longest?.lengthMs)
    }

    /** アプリごとの合計が出る。 */
    @Test
    fun timeIsSummedPerApp() {
        val a = Behaviour.analyze(
            listOf(
                Behaviour.Moment("x", "", resumed, at(9)), Behaviour.Moment("x", "", paused, at(9, 10)),
                Behaviour.Moment("x", "", resumed, at(11)), Behaviour.Moment("x", "", paused, at(11, 20)),
                Behaviour.Moment("y", "", resumed, at(10)), Behaviour.Moment("y", "", paused, at(10, 5)),
            ),
            now = at(13),
        )
        assertEquals(30 * 60_000L, a.byPackage["x"])
        assertEquals(5 * 60_000L, a.byPackage["y"])
        assertEquals(2, a.opens["x"])
    }

    /** 何も無い日は 0。**0 で割らない。** */
    @Test
    fun anEmptyDayIsAllZero() {
        val a = Behaviour.analyze(emptyList(), now = at(12))
        assertEquals(0L, a.totalMs)
        assertEquals(0, a.opensTotal)
        assertEquals(0L, a.averageMs)
        assertNull(a.longest)
        assertEquals(24, a.hourly.size)
        assertTrue(a.hourly.all { it == 0L })
    }

    /**
     * ⚠️ **実機で起きた取りこぼしの再現**（2026/9/2）。
     *
     * ChatGPT を 11:09:18 から 12:03:57 まで（54分39秒）使った。
     * 端末の日次集計にも `totalTimeUsed="54:40"` と出ている。
     * ところが出来事は**画面ごと**に来る。
     *
     * ```
     * 11:09:18 RESUMED chatgpt/ChatGptDeeplinkActivity
     * 11:09:18 PAUSED  chatgpt/ChatGptDeeplinkActivity
     * 11:09:18 RESUMED chatgpt/MainActivity
     * 11:09:19 STOPPED chatgpt/ChatGptDeeplinkActivity  ← 別の画面の終わり
     * 12:03:57 PAUSED  chatgpt/MainActivity
     * ```
     *
     * パッケージ単位で区間を閉じていたので、★ が本体の区間を閉じ、
     * **54分39秒が 1秒になっていた。**
     */
    @Test
    fun `入口の画面が閉じても、本体の区間は閉じない`() {
        val deeplink = "com.openai.chatgpt.ChatGptDeeplinkActivity"
        val main = "com.openai.chatgpt.MainActivity"
        val t0 = at(11, 9)
        val a = Behaviour.analyze(
            listOf(
                Behaviour.Moment("chatgpt", deeplink, resumed, t0),
                Behaviour.Moment("chatgpt", deeplink, paused, t0),
                Behaviour.Moment("chatgpt", main, resumed, t0),
                Behaviour.Moment("chatgpt", deeplink, stopped, t0 + 1_000),
                Behaviour.Moment("chatgpt", main, paused, t0 + 54 * 60_000 + 39_000),
            ),
            now = at(14, 0),
        )
        assertEquals(54 * 60_000L + 39_000L, a.byPackage["chatgpt"])
        // ひらいた回数は**アプリ単位**。入口から本体へ移っただけで二回にしない。
        assertEquals(1, a.opens["chatgpt"])
    }

    /** 同じアプリの二つの画面が重なっても、**同じ時間を二度数えない**。 */
    @Test
    fun `重なった区間は畳む`() {
        val a = Behaviour.analyze(
            listOf(
                Behaviour.Moment("x", "A", resumed, at(10, 0)),
                Behaviour.Moment("x", "B", resumed, at(10, 5)),
                Behaviour.Moment("x", "A", paused, at(10, 10)),
                Behaviour.Moment("x", "B", paused, at(10, 20)),
            ),
            now = at(11, 0),
        )
        // 10:00〜10:20 の 20分。足し合わせて 25分にしない。
        assertEquals(20 * 60_000L, a.byPackage["x"])
        assertEquals(1, a.opens["x"])
    }

    /** 離れた区間は畳まない。 */
    @Test
    fun `離れた区間は別々に数える`() {
        val a = Behaviour.analyze(
            listOf(
                Behaviour.Moment("x", "A", resumed, at(10, 0)),
                Behaviour.Moment("x", "A", paused, at(10, 10)),
                Behaviour.Moment("x", "A", resumed, at(11, 0)),
                Behaviour.Moment("x", "A", paused, at(11, 5)),
            ),
            now = at(12, 0),
        )
        assertEquals(15 * 60_000L, a.byPackage["x"])
        assertEquals(2, a.opens["x"])
        assertEquals(2, a.sessions.size)
    }
}
