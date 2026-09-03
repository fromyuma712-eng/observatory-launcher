package com.yuma.observatorylauncher.data

import java.time.LocalDate
import java.time.YearMonth

/**
 * 習慣の記録。**やった日だけを覚える。**
 *
 * 月の格子と同じ器で見せるが、読む先が違う——暦は端末が持っているものを借り、
 * 習慣は利用者が自分で付ける。源が違い、相（格子）が同じ、という設計の見本になる。
 *
 * ## どこに置くか
 *
 * 宣言（`layout.json`）の札の `value` に、そのまま文字で置く。別の蔵を建てない。
 * これで**世代と「元に戻す」がそのまま効く**——付け間違えた印を戻せる。
 * 記録用の蔵を別に建てると、そこだけ履歴の外に出てしまう。
 *
 * ## どう書くか
 *
 * ```
 * 2026-08:1,3,5,8;2026-09:2,4
 * ```
 *
 * 月ごとに、やった日を並べる。**人が読める形にしておく**——
 * 宣言は控えとして書き出され、必要なら手で直す。詰めた数字の列にすると、
 * 壊れたときに何が起きたか分からなくなる。
 */
internal object Habit {

    private const val MONTHS = ';'
    private const val DAYS = ':'
    private const val EACH = ','

    /** 記録を読む。壊れた並びは黙って捨てる——一箇所の傷で全部を失う方が困る。 */
    fun parse(text: String): Map<YearMonth, Set<Int>> {
        val out = LinkedHashMap<YearMonth, MutableSet<Int>>()
        text.split(MONTHS).forEach { chunk ->
            val row = chunk.trim()
            if (row.isEmpty()) return@forEach
            val at = row.indexOf(DAYS)
            if (at <= 0) return@forEach
            val month = runCatching { YearMonth.parse(row.substring(0, at).trim()) }.getOrNull()
                ?: return@forEach
            val days = row.substring(at + 1).split(EACH)
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it in 1..month.lengthOfMonth() }
            if (days.isNotEmpty()) out.getOrPut(month) { linkedSetOf() }.addAll(days)
        }
        return out
    }

    /**
     * 記録を書く。**月も日も並べ替えて書く**——
     * 付けた順のままだと、控えの差分が毎回散らかって読めない。
     */
    fun format(record: Map<YearMonth, Set<Int>>): String = record
        .filterValues { it.isNotEmpty() }
        .toSortedMap()
        .entries
        .joinToString(MONTHS.toString()) { (month, days) ->
            "$month$DAYS${days.sorted().joinToString(EACH.toString())}"
        }

    /** その日に印が付いているか。 */
    fun isDone(text: String, date: LocalDate): Boolean =
        parse(text)[YearMonth.from(date)]?.contains(date.dayOfMonth) == true

    /** 印を付ける／外す。付いていれば外し、無ければ付ける。 */
    fun toggled(text: String, date: LocalDate): String {
        val record = parse(text).mapValues { it.value.toMutableSet() }.toMutableMap()
        val month = YearMonth.from(date)
        val days = record.getOrPut(month) { linkedSetOf() }
        if (!days.remove(date.dayOfMonth)) days.add(date.dayOfMonth)
        return format(record)
    }

    /** その月の記録を、格子が読める形へ。 */
    fun marksFor(text: String, month: YearMonth): Map<LocalDate, MonthView.Marks> =
        parse(text)[month].orEmpty().associate { day ->
            month.atDay(day) to MonthView.Marks(marked = 1)
        }

    /**
     * 今日から遡って、途切れずに続いている日数。
     *
     * **今日がまだでも途切れとは見ない。** 日付が変わった直後に
     * 「連続 0 日」と出るのは、事実ではあっても酷である。昨日まで続いていれば数える。
     */
    fun streak(text: String, today: LocalDate): Int {
        val record = parse(text)
        fun done(date: LocalDate) =
            record[YearMonth.from(date)]?.contains(date.dayOfMonth) == true

        var day = if (done(today)) today else today.minusDays(1)
        var count = 0
        while (done(day)) {
            count++
            day = day.minusDays(1)
        }
        return count
    }
}
