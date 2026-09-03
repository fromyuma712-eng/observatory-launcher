package com.yuma.observatorylauncher.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * 開かなかった記録。
 *
 * 摩擦の面を出したあと、開かずに戻った回数を数える。
 *
 * **我慢が記録に残らないなら、我慢する理由がひとつ減る。**
 * 制限した時間ではなく、**生まれた時間**の側を数えるための土台である。
 *
 * 日付ごとの数だけを持つ。どのアプリを何時に開かなかったか、まで残す必要は無い
 * ——それは記録ではなく監視になる。
 */
internal object Restraint {

    const val LOG_NAME = "restraint.json"

    /** 保つ日数。 */
    const val KEEP_DAYS = 30

    /** 日付キー（`yyyyMMdd` の整数）ごとの回数。 */
    fun parse(text: String): Map<Int, Int> = runCatching {
        val o = JSONObject(text)
        o.keys().asSequence().mapNotNull { k ->
            val day = k.toIntOrNull() ?: return@mapNotNull null
            day to o.optInt(k, 0)
        }.filter { it.second > 0 }.toMap()
    }.getOrDefault(emptyMap())

    fun format(counts: Map<Int, Int>): String {
        val o = JSONObject()
        counts.toSortedMap().forEach { (day, n) -> o.put(day.toString(), n) }
        return o.toString()
    }

    /** 一回足す。古い日は落とす。 */
    fun incremented(counts: Map<Int, Int>, day: Int): Map<Int, Int> {
        val next = counts.toMutableMap()
        next[day] = (next[day] ?: 0) + 1
        val keep = next.keys.sortedDescending().take(KEEP_DAYS).toSet()
        return next.filterKeys { it in keep }
    }

    fun dayKey(at: Long = System.currentTimeMillis()): Int {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        return c.get(Calendar.YEAR) * 10000 +
            (c.get(Calendar.MONTH) + 1) * 100 +
            c.get(Calendar.DAY_OF_MONTH)
    }

    fun read(context: Context): Map<Int, Int> {
        val file = File(context.filesDir, LOG_NAME)
        if (!file.exists()) return emptyMap()
        return parse(runCatching { file.readText() }.getOrDefault(""))
    }

    /** どのアプリだったかは受け取るが**残さない**。記録ではなく監視になるため。 */
    @Synchronized
    fun avoided(context: Context, packageName: String) {
        val next = incremented(read(context), dayKey())
        runCatching { File(context.filesDir, LOG_NAME).writeText(format(next)) }
    }

    fun today(context: Context): Int = read(context)[dayKey()] ?: 0
}
