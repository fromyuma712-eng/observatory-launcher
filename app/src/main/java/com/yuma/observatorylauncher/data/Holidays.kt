package com.yuma.observatorylauncher.data

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * 日本の祝日。
 *
 * ## なぜ自前で数えるか
 *
 * 端末に「祝日か」を訊く公の道は無い。暦アプリが持っている祝日は
 * 各自の購読暦（Google の「日本の祝日」など）で、こちらから当てにできる保証が無い。
 * 網へ問い合わせる案もあるが、**月の格子を描くのに通信が要るのは筋が悪い**。
 *
 * 祝日法は算法で書ける。書けるものを、外から貰いに行かない。
 *
 * ## ⚠️ 効く範囲
 *
 * - **2020年以降**の名前と日付に合わせてある（体育の日→スポーツの日、天皇誕生日 2/23 など）。
 *   それ以前を映しても、色が違うだけで格子は壊れない。
 * - 二分（春分・秋分）は**近似式**である。1980〜2099 の範囲で実際の官報と一致する。
 * - 五輪の年（2020・2021）の特例移動は**入れていない**。過去の一度きりの例外を
 *   持ち歩くより、**式が読める**ことを採る。
 */
internal object Holidays {

    /** その日が祝日（振替休日・国民の休日を含む）か。 */
    fun isHoliday(date: LocalDate): Boolean = names(date.year).containsKey(date)

    /** その日の祝日の名。祝日でなければ null。 */
    fun nameOf(date: LocalDate): String? = names(date.year)[date]

    /**
     * その年の祝日。
     *
     * 一年ぶんを一度作って覚える——月の格子は同じ年を何度も引く。
     */
    @Synchronized
    fun names(year: Int): Map<LocalDate, String> =
        cache.getOrPut(year) { build(year) }

    private val cache = HashMap<Int, Map<LocalDate, String>>()

    private fun build(year: Int): Map<LocalDate, String> {
        val fixed = LinkedHashMap<LocalDate, String>()

        fun put(month: Int, day: Int, name: String) {
            fixed[LocalDate.of(year, month, day)] = name
        }

        /** 「第 n 月曜」。成人の日などのいわゆるハッピーマンデー。 */
        fun monday(month: Int, nth: Int, name: String) {
            fixed[
                LocalDate.of(year, month, 1)
                    .with(TemporalAdjusters.dayOfWeekInMonth(nth, DayOfWeek.MONDAY)),
            ] = name
        }

        put(1, 1, "元日")
        monday(1, 2, "成人の日")
        put(2, 11, "建国記念の日")
        if (year >= 2020) put(2, 23, "天皇誕生日")
        put(3, equinox(year, spring = true), "春分の日")
        put(4, 29, "昭和の日")
        put(5, 3, "憲法記念日")
        put(5, 4, "みどりの日")
        put(5, 5, "こどもの日")
        monday(7, 3, "海の日")
        if (year >= 2016) put(8, 11, "山の日")
        monday(9, 3, "敬老の日")
        put(9, equinox(year, spring = false), "秋分の日")
        monday(10, 2, if (year >= 2020) "スポーツの日" else "体育の日")
        put(11, 3, "文化の日")
        put(11, 23, "勤労感謝の日")

        val all = LinkedHashMap(fixed)

        // 振替休日。**日曜に当たった祝日は、次の「祝日でない日」へ移す。**
        // 連休の途中に当たると二日先・三日先へ送られるので、繰り返して探す。
        fixed.forEach { (date, _) ->
            if (date.dayOfWeek != DayOfWeek.SUNDAY) return@forEach
            var next = date.plusDays(1)
            while (fixed.containsKey(next)) next = next.plusDays(1)
            all[next] = "振替休日"
        }

        // 国民の休日。**祝日に挟まれた平日は休みになる。**
        // 実際に生じるのは九月（敬老の日と秋分の日が一日空けて並ぶ年）だけだが、
        // 条文どおりに数える方が、年ごとの例外を書き足さずに済む。
        fixed.keys.sorted().forEach { date ->
            val between = date.plusDays(1)
            val after = date.plusDays(2)
            if (fixed.containsKey(after) &&
                !fixed.containsKey(between) &&
                between.dayOfWeek != DayOfWeek.SUNDAY &&
                !all.containsKey(between)
            ) {
                all[between] = "国民の休日"
            }
        }
        return all
    }

    /**
     * 二分の日。
     *
     * 太陽の位置で決まるので年によって 20 日と 21 日を行き来する。
     * 官報で毎年告示されるが、**1980〜2099 はこの式で一致する**ことが知られている。
     */
    fun equinox(year: Int, spring: Boolean): Int {
        val base = if (spring) 20.8431 else 23.2488
        val since = year - 1980
        return (base + 0.242194 * since - since / 4).toInt()
    }
}
