package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.data.MonthView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月の格子の試験。
 *
 * 日付は間違えやすいわりに、間違えても例外にならない——一日ずれた暦が
 * 平然と表示されるだけである。**実機を使わずに確かめられる形**にしてあるので、
 * 月替わり・閏年・週の跨ぎをここで押さえる。
 */
class MonthViewTest {

    /** 枡の数は月によらず四十二。変わると格子の高さが跳ねる。 */
    @Test
    fun everyMonthIsSixWeeks() {
        listOf(
            LocalDate.of(2026, 2, 1),  // 短い月
            LocalDate.of(2024, 2, 1),  // 閏年の二月
            LocalDate.of(2026, 8, 30), // 六週に跨る月
            LocalDate.of(2026, 12, 1),
        ).forEach { anchor ->
            val view = MonthView.of(anchor)
            assertEquals(anchor.toString(), 42, view.days.size)
            assertEquals(anchor.toString(), 6, view.weeks().size)
            view.weeks().forEach { assertEquals(7, it.size) }
        }
    }

    /** 格子の左端は必ず日曜。ここがずれると曜日の見出しと中身が一列ずれる。 */
    @Test
    fun everyRowStartsOnSunday() {
        (1..12).forEach { month ->
            val view = MonthView.of(LocalDate.of(2026, month, 1))
            view.weeks().forEach { week ->
                assertEquals(DayOfWeek.SUNDAY, week.first().date.dayOfWeek)
                assertEquals(DayOfWeek.SATURDAY, week.last().date.dayOfWeek)
            }
        }
    }

    /** 日付は隙間なく連続する。埋め草も本物の日付である。 */
    @Test
    fun theDatesRunWithoutGaps() {
        val view = MonthView.of(LocalDate.of(2026, 8, 30))
        view.days.zipWithNext { a, b ->
            assertEquals(a.date.plusDays(1), b.date)
        }
    }

    /** その月の日だけが濃く、前後の月からの埋め草は薄い。 */
    @Test
    fun onlyThisMonthIsMarkedAsInside() {
        val view = MonthView.of(LocalDate.of(2026, 8, 1))
        val inside = view.days.filter { it.inMonth }
        assertEquals(31, inside.size)
        assertEquals(LocalDate.of(2026, 8, 1), inside.first().date)
        assertEquals(LocalDate.of(2026, 8, 31), inside.last().date)
        assertTrue(view.days.none { it.inMonth && YearMonth.from(it.date).monthValue != 8 })
    }

    /** 一日が日曜の月は、前月からの埋め草が無い。 */
    @Test
    fun aMonthStartingOnSundayHasNoLeadingFiller() {
        // 2026年3月1日は日曜。
        val view = MonthView.of(LocalDate.of(2026, 3, 1))
        assertEquals(DayOfWeek.SUNDAY, LocalDate.of(2026, 3, 1).dayOfWeek)
        assertEquals(LocalDate.of(2026, 3, 1), view.days.first().date)
        assertTrue(view.days.first().inMonth)
    }

    /** 予定の数は日付で引く。無い日は 0 で、例外にはしない。 */
    @Test
    fun theCountsAreAttachedByDate() {
        val busy = LocalDate.of(2026, 8, 30)
        val view = MonthView.of(busy, mapOf(busy to MonthView.Marks(done = 1, ahead = 2)))
        assertEquals(3, view.days.first { it.date == busy }.count)
        assertEquals(0, view.days.first { it.date == busy.plusDays(1) }.count)
    }

    /** 見出しの曜日は七つで、格子の並びと同じ順である。 */
    @Test
    fun theHeadingsMatchTheGrid() {
        assertEquals(7, MonthView.HEADINGS.size)
        assertEquals("日", MonthView.HEADINGS.first())
        assertEquals("土", MonthView.HEADINGS.last())
    }

    // ── 予定の顔（済・進行中・これから） ─────────────────────────

    private val today = LocalDate.of(2026, 8, 30)
    private val noon = 1_756_522_800_000L // 2026-08-30 12:00 JST 相当の任意の基準

    /** 過ぎた日は、時刻を見るまでもなく済。 */
    @Test
    fun pastDaysAreDoneRegardlessOfTime() {
        val state = MonthView.stateOf(
            day = today.minusDays(1), today = today,
            beginsAt = noon + 999_999, endsAt = noon + 9_999_999, now = noon,
        )
        assertEquals(MonthView.State.DONE, state)
    }

    /** 先の日は、時刻を見るまでもなくこれから。 */
    @Test
    fun futureDaysAreAheadRegardlessOfTime() {
        val state = MonthView.stateOf(
            day = today.plusDays(1), today = today,
            beginsAt = noon - 9_999_999, endsAt = noon - 999_999, now = noon,
        )
        assertEquals(MonthView.State.AHEAD, state)
    }

    /** 今日だけが時刻で決まる。終わり・最中・これからの三つに分かれる。 */
    @Test
    fun todayIsDecidedByTheClock() {
        fun at(begin: Long, end: Long) =
            MonthView.stateOf(today, today, noon + begin, noon + end, noon)

        assertEquals(MonthView.State.DONE, at(-7_200_000, -3_600_000))   // 一時間前に終わった
        assertEquals(MonthView.State.RUNNING, at(-3_600_000, 3_600_000)) // 今まさに
        assertEquals(MonthView.State.AHEAD, at(3_600_000, 7_200_000))    // 一時間後から
    }

    /** 終わりの時刻ちょうどは「済」。境目でどちらに倒すかを決めておく。 */
    @Test
    fun theEndInstantCountsAsDone() {
        assertEquals(
            MonthView.State.DONE,
            MonthView.stateOf(today, today, noon - 3_600_000, noon, noon),
        )
        // 始まりの時刻ちょうどは「今まさに」。
        assertEquals(
            MonthView.State.RUNNING,
            MonthView.stateOf(today, today, noon, noon + 3_600_000, noon),
        )
    }

    /** 跨ぐ予定は、日ごとに違う顔になる。三日間の旅行がそう読める。 */
    @Test
    fun aMultiDayEventWearsADifferentFaceEachDay() {
        val begin = noon - 86_400_000
        val end = noon + 86_400_000
        assertEquals(
            MonthView.State.DONE,
            MonthView.stateOf(today.minusDays(1), today, begin, end, noon),
        )
        assertEquals(
            MonthView.State.RUNNING,
            MonthView.stateOf(today, today, begin, end, noon),
        )
        assertEquals(
            MonthView.State.AHEAD,
            MonthView.stateOf(today.plusDays(1), today, begin, end, noon),
        )
    }

    /** 印は時の順に並ぶ。 */
    @Test
    fun theMarksAreOrderedByTime() {
        val marks = MonthView.Marks(done = 1, running = 1, ahead = 1)
        assertEquals(
            listOf(MonthView.State.DONE, MonthView.State.RUNNING, MonthView.State.AHEAD),
            marks.ordered(3),
        )
        assertEquals(3, marks.total)
    }

    /** 溢れたら**古い方から捨てる**。過ぎたものより、これからのものを見せる。 */
    @Test
    fun whenThereAreTooManyThePastIsDroppedFirst() {
        val marks = MonthView.Marks(done = 3, running = 1, ahead = 2)
        val shown = marks.ordered(3)
        assertEquals(3, shown.size)
        assertTrue(shown.toString(), MonthView.State.RUNNING in shown)
        assertEquals(2, shown.count { it == MonthView.State.AHEAD })
        assertEquals(0, shown.count { it == MonthView.State.DONE })
    }

    /** 予定の無い日は印も無い。 */
    @Test
    fun anEmptyDayHasNoMarks() {
        assertEquals(0, MonthView.Marks.NONE.total)
        assertTrue(MonthView.Marks.NONE.ordered(3).isEmpty())
    }
}
