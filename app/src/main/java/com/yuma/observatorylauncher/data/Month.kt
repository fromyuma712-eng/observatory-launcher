package com.yuma.observatorylauncher.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * 月の格子。**日付の並べ方だけ**を受け持ち、描き方も読み取り方も持たない。
 *
 * ここを端末から切り離してあるのは、月替わり・閏年・週の跨ぎといった
 * 間違えやすいところを**実機を使わずに確かめる**ためである。
 */
internal data class MonthView(
    val month: YearMonth,
    val days: List<Day>,
) {
    /**
     * その日の予定の内訳。**数だけでなく、どうなっているかを持つ。**
     *
     * 「三件ある」より「一件は終わり、一件は今まさに、一件はこれから」の方が、
     * 一目で読む面には効く。色を分けるための元がここに要る。
     */
    data class Marks(
        /** 済んだもの。 */
        val done: Int = 0,
        /** 今まさに進んでいるもの。 */
        val running: Int = 0,
        /** これから始まるもの。 */
        val ahead: Int = 0,
        /** 利用者が自分で付けた印（習慣）。時の前後では決まらない。 */
        val marked: Int = 0,
    ) {
        val total: Int get() = done + running + ahead + marked

        /**
         * 描く順に並べた印。**過ぎたものから並べる**（時の順）。
         *
         * [limit] を超えたときは**古い方から捨てる**——
         * 過ぎたものより、これからのものを見せる方が役に立つ。
         */
        fun ordered(limit: Int): List<State> {
            val all = List(done) { State.DONE } +
                List(running) { State.RUNNING } +
                List(ahead) { State.AHEAD } +
                List(marked) { State.MARKED }
            return if (all.size <= limit) all else all.takeLast(limit)
        }

        companion object {
            val NONE = Marks()
        }
    }

    /** 印の状態。色はこれで決まる。 */
    enum class State {
        /** 済んだ予定。沈める。 */
        DONE,

        /** 今まさに進んでいる予定。この面で燠火を許す数少ない一つ。 */
        RUNNING,

        /** これから始まる予定。 */
        AHEAD,

        /** 利用者が自分で付けた印。**やったという事実**なので、はっきり見せる。 */
        MARKED,
    }

    /**
     * 一枡。[inMonth] が偽なら前後の月からはみ出した日で、薄く描く。
     */
    data class Day(
        val date: LocalDate,
        val inMonth: Boolean,
        val marks: Marks = Marks.NONE,
    ) {
        val count: Int get() = marks.total
    }

    /** 週ごとに七枡ずつ切り出す。 */
    fun weeks(): List<List<Day>> = days.chunked(WEEK)

    companion object {
        const val WEEK = 7

        /**
         * **常に六週（四十二枡）で組む。**
         *
         * 月によって五週で足りることもあるが、枡の数を変えると格子の高さが変わり、
         * 月を送るたびに下の内容が跳ねる。**高さが変わらないことの方が大事**なので、
         * 足りない分は翌月の頭で埋める。
         */
        const val ROWS = 6

        /**
         * [anchor] の含まれる月の格子を組む。週は日曜始まり（暦の表記に合わせる）。
         *
         * [marks] に無い日は予定なしとして扱う。
         */
        fun of(anchor: LocalDate, marks: Map<LocalDate, Marks> = emptyMap()): MonthView {
            val month = YearMonth.from(anchor)
            val first = month.atDay(1)
            // 日曜を週の頭にするので、日曜からの隔たりだけ前へ戻る。
            val lead = first.dayOfWeek.sundayIndex()
            val start = first.minusDays(lead.toLong())
            val days = (0 until WEEK * ROWS).map { at ->
                val date = start.plusDays(at.toLong())
                Day(
                    date = date,
                    inMonth = YearMonth.from(date) == month,
                    marks = marks[date] ?: Marks.NONE,
                )
            }
            return MonthView(month, days)
        }

        /**
         * 一件の予定が、ある日にどう見えるかを決める。
         *
         * **過去の日は済、未来の日はこれから、今日だけが時刻で決まる。**
         * 三日間の旅行なら、昨日は済・今日は進行中・明日はこれから、と読める。
         * 日ごとに判じるので、跨ぐ予定でも各日が正しい顔になる。
         *
         * 「完了したタスク」は暦の提供者が持っていない——`CalendarContract` に
         * 済み・未済の別は無い。**終わった時刻を過ぎたかどうかで代える。**
         * できることとできないことを混ぜないため、ここに明記しておく。
         */
        fun stateOf(
            day: LocalDate,
            today: LocalDate,
            beginsAt: Long,
            endsAt: Long,
            now: Long,
        ): State = when {
            day.isBefore(today) -> State.DONE
            day.isAfter(today) -> State.AHEAD
            // 今日の分だけは時刻で判じる。終わりが今より前なら済。
            endsAt <= now -> State.DONE
            beginsAt <= now -> State.RUNNING
            else -> State.AHEAD
        }

        /** 日曜を 0 とした曜日の番号。`DayOfWeek` は月曜が 1 なので、そのままでは使えない。 */
        private fun DayOfWeek.sundayIndex(): Int = value % WEEK

        /** 見出しに出す曜日。格子と同じ日曜始まりで並べる。 */
        val HEADINGS: List<String> = listOf("日", "月", "火", "水", "木", "金", "土")
    }
}
