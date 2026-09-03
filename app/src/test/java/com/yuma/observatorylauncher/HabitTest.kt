package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.data.Habit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * 習慣の記録の試験。
 *
 * ここは**持ち物そのもの**である。表示が崩れても直せるが、記録が壊れれば
 * 付けた日が失われる。書いて読んで同じに戻ること（往復）と、
 * 付けたものが外せること（逆操作）を見張る。
 */
class HabitTest {

    @Test
    fun itSurvivesTheRoundTrip() {
        val text = "2026-08:1,3,5;2026-09:2"
        assertEquals(text, Habit.format(Habit.parse(text)))
    }

    /** 付けたら外せる。「ある動作を足したら、その逆が行えるか」の点検。 */
    @Test
    fun aMarkCanBeTakenBackOff() {
        val day = LocalDate.of(2026, 8, 30)
        val on = Habit.toggled("", day)
        assertTrue(Habit.isDone(on, day))
        val off = Habit.toggled(on, day)
        assertFalse(Habit.isDone(off, day))
        assertEquals("", off)
    }

    /** 他の日を巻き込まない。 */
    @Test
    fun togglingOneDayLeavesTheOthers() {
        var text = ""
        listOf(3, 5, 8).forEach { text = Habit.toggled(text, LocalDate.of(2026, 8, it)) }
        text = Habit.toggled(text, LocalDate.of(2026, 8, 5))
        assertEquals("2026-08:3,8", text)
    }

    /** 月も日も並べ替えて書く。控えの差分が散らからないように。 */
    @Test
    fun itIsWrittenInOrder() {
        var text = ""
        listOf(
            LocalDate.of(2026, 9, 2),
            LocalDate.of(2026, 8, 9),
            LocalDate.of(2026, 8, 1),
        ).forEach { text = Habit.toggled(text, it) }
        assertEquals("2026-08:1,9;2026-09:2", text)
    }

    /** 壊れた並びは捨てるが、読める分は残す。一箇所の傷で全部を失わない。 */
    @Test
    fun brokenRowsAreDroppedWithoutLosingTheRest() {
        val text = "こわれた;2026-08:1,x,3;;2026-13:5;2026-09:99;2026-09:4"
        val record = Habit.parse(text)
        assertEquals(setOf(1, 3), record[YearMonth.of(2026, 8)])
        assertEquals(setOf(4), record[YearMonth.of(2026, 9)])
        // 月の日数を超える日は捨てる。
        assertFalse(record.containsKey(YearMonth.of(2026, 13 % 12)))
    }

    /** 格子が読める形に直せる。 */
    @Test
    fun itBecomesMarksForTheGrid() {
        val marks = Habit.marksFor("2026-08:1,30", YearMonth.of(2026, 8))
        assertEquals(2, marks.size)
        assertEquals(1, marks[LocalDate.of(2026, 8, 30)]?.marked)
        assertEquals(1, marks[LocalDate.of(2026, 8, 30)]?.total)
    }

    /** 続いている日数を数える。 */
    @Test
    fun theStreakCounts() {
        val today = LocalDate.of(2026, 8, 30)
        assertEquals(3, Habit.streak("2026-08:28,29,30", today))
        assertEquals(0, Habit.streak("2026-08:27,28", today))
    }

    /**
     * **今日がまだでも、途切れとは見ない。**
     * 日付が変わった直後に「連続 0 日」と出るのは、事実ではあっても酷である。
     */
    @Test
    fun todayBeingUnmarkedDoesNotBreakTheStreak() {
        val today = LocalDate.of(2026, 8, 30)
        assertEquals(2, Habit.streak("2026-08:28,29", today))
    }

    /** 月を跨いで続く。 */
    @Test
    fun theStreakCrossesMonths() {
        val today = LocalDate.of(2026, 9, 2)
        assertEquals(4, Habit.streak("2026-08:30,31;2026-09:1,2", today))
    }
}
