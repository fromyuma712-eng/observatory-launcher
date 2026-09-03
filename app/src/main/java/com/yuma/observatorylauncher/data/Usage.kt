package com.yuma.observatorylauncher.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 端末の使用状況を読む。
 *
 * 表示のためと、並べ替えのために使う。どちらも同じところから取るので一箇所に置く。
 *
 * **[UsageStatsManager.queryUsageStats] を今日の集計に使ってはならない。**
 * あれが返すのは日ごとに区切られた「桶」であり、
 *
 * - 桶の中身が書き出されるのは折を見てのことで、**今この瞬間の値ではない**
 *   （昨日のままの値が返ることがある——実際にそうなった）。
 * - 期間に**重なる**桶をすべて返すので、今日の零時から問い合わせても
 *   昨日の桶が混ざり、単純に足すと二重に数えてしまう。
 *
 * 出来事([UsageEvents])を数えれば、どちらも起きない。前面に来た時刻と離れた時刻の
 * 差を足すだけで、値は常に今の時点のものになる。
 */
internal object Usage {

    /** 今日の使用時間と、最も長かったアプリ。 */
    data class Today(
        val total: Long,
        val topPackage: String?,
        val topTime: Long,
        /**
         * 多い順の内訳。**図に載せるために持つ。**
         * 「今日 2時間52分・最長 YouTube」で足りるのは文字の札だけで、
         * 横棒の図は「何がどれだけ占めたか」を要求する。
         */
        val ranked: List<Pair<String, Long>> = emptyList(),
    )

    /**
     * 今日零時から今までの前面滞在時間を、出来事から数え直す。
     *
     * 前面に来たまま終わっている分（今まさに使っているアプリ）は、今までを足す。
     * 画面が消えた出来事では開いている分をすべて閉じる——
     * 画面を消したまま朝を迎えたアプリに、一晩分を付けないためである。
     */
    fun today(context: Context): Today? {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val start = startOfDay()
        val now = System.currentTimeMillis()
        val events = runCatching { manager.queryEvents(start, now) }.getOrNull() ?: return null

        // ⚠️ **数え方は [Behaviour.analyze] にしか置かない。**
        //
        // ここには同じ数え上げがもう一つ書いてあり、あちらと同じ罠——
        // パッケージ単位で区間を閉じる——を踏んでいた。
        // 二つあるうち片方だけを直したせいで、
        // **同じ面の中で見出しと横棒が食い違った**（2026/9/2）。
        val moments = ArrayList<Behaviour.Moment>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            moments += Behaviour.Moment(
                pkg,
                event.className.orEmpty(),
                event.eventType,
                event.timeStamp,
            )
        }
        val totals = HashMap(Behaviour.analyze(moments, now).byPackage)

        // 数えるのは**アプリを使った時間**である。画面に出ていたものすべてではない。
        // 素通りに数えると、ホーム（このランチャー自身）や、入力・通知の面のような
        // 開くことのできない部品まで積み上がり、端末自身の集計より大きく出る。
        // 出来事だけでは足りない。**古い出来事は畳まれて消える**ので、
        // 朝に使ったアプリは記録が欠け、実際より短く出る（実測：Amazon 22分が 7分）。
        // 一方で桶は、今まさに使っている分が書き出されるまで載らない。
        // どちらも「少なくともこれだけは使った」という値なので、大きい方を採る。
        val settled = settledToday(manager, start)
        val merged = HashMap<String, Long>()
        for (key in totals.keys + settled.keys) {
            merged[key] = maxOf(totals[key] ?: 0L, settled[key] ?: 0L)
        }
        // 数えるのは**アプリを使った時間**である。画面に出ていたものすべてではない。
        // 素通りに数えると、ホーム（このランチャー自身）や、入力・通知の面のような
        // 開くことのできない部品まで積み上がり、端末自身の集計より大きく出る。
        val counted = merged.filterKeys { it != context.packageName && launchable(context, it) }
        val total = counted.values.sum()
        val top = counted.maxByOrNull { it.value }
        return Today(
            total = total,
            topPackage = top?.key,
            topTime = top?.value ?: 0L,
            ranked = counted.entries.sortedByDescending { it.value }.map { it.key to it.value },
        )
    }

    /**
     * 今日、何回ひらいたか。
     *
     * 端末の「デジタル ウェルビーイング」は持っているのに、どのウィジェットも出さない値である。
     * 使用時間が「どれだけ使ったか」なら、解錠回数は**どれだけ手が伸びたか**を表す。
     * 十分使っていなくても五十回触っていれば、それは別の事実である。
     *
     * 併せて、最初と最後に触った時刻を返す——一日の輪郭がこれで出る。
     */
    data class Touch(val unlocks: Int, val firstAt: Long?, val lastAt: Long?)

    /**
     * 出来事の並びから数える計算そのもの。
     *
     * `UsageEvents` は端末の中でしか作れないので、**数える所だけを切り出して**
     * 端末なしで確かめられるようにしてある。
     *
     * [events] は (種類, 時刻) の並び。時の順に並んでいることを前提にしない
     * ——端末は概ね順に返すが、拠り所にすると順序が崩れた日だけ壊れる。
     */
    fun countTouches(events: List<Pair<Int, Long>>): Touch {
        var unlocks = 0
        var first: Long? = null
        var last: Long? = null
        events.forEach { (type, at) ->
            if (type == KEYGUARD_HIDDEN) unlocks++
            if (type == KEYGUARD_HIDDEN || type == UsageEvents.Event.ACTIVITY_RESUMED) {
                if (first == null || at < first!!) first = at
                if (last == null || at > last!!) last = at
            }
        }
        return Touch(unlocks, first, last)
    }

    /**
     * そのアプリを**最後に閉じた時刻**と、**今日の使用時間**。
     *
     * 摩擦の強さはこの二つで決まる。**新しい保存は要らない**——
     * どちらも端末が既に持っている。記録を自前で増やすのは、
     * 端末に無いものを欲しがるときだけにする。
     *
     * 「閉じた」は前面から外れたこと（`ACTIVITY_PAUSED`/`STOPPED`）とする。
     * 今日まだ一度も開いていなければ null を返す。
     */
    data class Recent(val usedMs: Long, val lastClosedAt: Long?)

    /** 出来事の並びから求める計算そのもの。端末なしで確かめられるよう切り出す。 */
    fun recentOf(events: List<Pair<Int, Long>>, now: Long): Recent {
        var opened: Long? = null
        var used = 0L
        var closed: Long? = null
        events.sortedBy { it.second }.forEach { (type, at) ->
            when (type) {
                UsageEvents.Event.ACTIVITY_RESUMED -> if (opened == null) opened = at
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                -> {
                    opened?.let { used += (at - it).coerceAtLeast(0L) }
                    opened = null
                    closed = at
                }
            }
        }
        // 前面のまま終わっている＝今まさに開いている。今までを足し、閉じてはいない。
        opened?.let { used += (now - it).coerceAtLeast(0L) }
        return Recent(used, if (opened != null) null else closed)
    }

    fun recent(context: Context, packageName: String, now: Long = System.currentTimeMillis()):
        Recent {
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return Recent(0L, null)
        val stream = runCatching { manager.queryEvents(startOfDay(), now) }.getOrNull()
            ?: return Recent(0L, null)
        val collected = ArrayList<Pair<Int, Long>>()
        val event = UsageEvents.Event()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(event)
            if (event.packageName == packageName) collected += event.eventType to event.timeStamp
        }
        return recentOf(collected, now)
    }

    /** `UsageEvents.Event.KEYGUARD_HIDDEN`。API 28 で入ったので、数字で持つ。 */
    private const val KEYGUARD_HIDDEN = 18

    fun touchesToday(context: Context): Touch? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val start = startOfDay()
        val now = System.currentTimeMillis()
        val stream = runCatching { manager.queryEvents(start, now) }.getOrNull() ?: return null
        val collected = ArrayList<Pair<Int, Long>>()
        val event = UsageEvents.Event()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(event)
            collected += event.eventType to event.timeStamp
        }
        return countTouches(collected)
    }

    /**
     * 日ごとの使用時間。**七日の散らばりや推移は、日ごとの値が無いと描けない。**
     *
     * 出来事（`queryEvents`）は数日で畳まれて消えるので、過去は桶
     * （`queryUsageStats(INTERVAL_DAILY)`）から取る。桶は今日の分が遅れて載るが、
     * **今日だけは出来事から数え直した値を差し込む**ので、当日も実測に揃う。
     *
     * ⚠️ 一日ずつ問い合わせる。期間をまとめて渡すと、
     * **期間に重なる桶がすべて返る**ので、どの日の分か分からなくなる。
     */
    data class Day(val start: Long, val totalMs: Long, val byPackage: Map<String, Long>)

    fun days(context: Context, count: Int = 7): List<Day> {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val today = startOfDay()
        val out = ArrayList<Day>()
        for (back in (count - 1) downTo 0) {
            val from = today - back * DAY_MS
            val to = from + DAY_MS
            val stats = runCatching {
                manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, to - 1)
            }.getOrNull().orEmpty()
            val byPackage = HashMap<String, Long>()
            for (entry in stats) {
                // 桶の始まりがその日でなければ、別の日の分である。
                if (entry.firstTimeStamp < from || entry.firstTimeStamp >= to) continue
                val time = entry.totalTimeInForeground
                if (time > 0 && entry.packageName != context.packageName &&
                    launchable(context, entry.packageName)
                ) {
                    byPackage[entry.packageName] = (byPackage[entry.packageName] ?: 0L) + time
                }
            }
            out += Day(from, byPackage.values.sum(), byPackage)
        }
        // 今日は出来事から数え直した方が実測に近い。桶は書き出しが遅れる。
        today(context)?.let { now ->
            val at = out.indexOfFirst { it.start == today }
            if (at >= 0) {
                val merged = HashMap(out[at].byPackage)
                now.ranked.forEach { (pkg, ms) ->
                    merged[pkg] = maxOf(merged[pkg] ?: 0L, ms)
                }
                out[at] = Day(today, merged.values.sum(), merged)
            }
        }
        return out
    }

    private const val DAY_MS = 24 * 60 * 60_000L

    /**
     * 集計済みの桶から、**今日始まった分だけ**を取る。
     *
     * [UsageStatsManager.queryUsageStats] は期間に重なる桶をすべて返すので、
     * そのまま足すと昨日の分が混ざる。桶の始まりが今日であるものに限る。
     */
    /**
     * ある一日ぶんの、集計済みの桶。**その日に始まった桶だけ**を数える。
     *
     * 出来事（`queryEvents`）と桶（`queryUsageStats`）は**別の道**であり、
     * どちらも欠ける。突き合わせに使うため、日を指定して引けるようにしてある。
     */
    fun settledOn(context: Context, dayStart: Long): Map<String, Long> {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyMap()
        val stats = runCatching {
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayStart, dayStart + DAY_MS)
        }.getOrNull().orEmpty()
        val out = HashMap<String, Long>()
        for (entry in stats) {
            if (entry.firstTimeStamp < dayStart) continue
            if (entry.packageName == context.packageName) continue
            if (!launchable(context, entry.packageName)) continue
            val time = entry.totalTimeInForeground
            if (time > 0) out[entry.packageName] = (out[entry.packageName] ?: 0L) + time
        }
        return out
    }

    private fun settledToday(manager: UsageStatsManager, start: Long): Map<String, Long> {
        val stats = runCatching {
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, System.currentTimeMillis())
        }.getOrNull().orEmpty()
        val out = HashMap<String, Long>()
        for (entry in stats) {
            if (entry.firstTimeStamp < start) continue
            val time = entry.totalTimeInForeground
            if (time > 0) out[entry.packageName] = (out[entry.packageName] ?: 0L) + time
        }
        return out
    }

    /**
     * 一覧に出てくる（＝人が開ける）アプリかどうか。
     * 問い合わせは数十件で頭打ちなので、答えを覚えておいて毎分の数え直しに備える。
     */
    /** 分析側からも使う。**数えるのは人が開けるアプリだけ**という規則を一箇所に保つ。 */
    fun isLaunchable(context: Context, pkg: String): Boolean = launchable(context, pkg)

    private fun launchable(context: Context, pkg: String): Boolean =
        launchableCache.getOrPut(pkg) {
            runCatching { context.packageManager.getLaunchIntentForPackage(pkg) != null }
                .getOrDefault(false)
        }

    private val launchableCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * 覚えた答えを捨てる。**アプリの出入りがあったときに呼ぶ。**
     *
     * ランチャーはホームなので、この処理が何日も生き続ける。捨てる道が無いと、
     * 入れたばかりのアプリが「開けないもの」として使用時間から外れたまま、
     * 端末を再起動するまで戻らない。
     */
    fun forget() {
        launchableCache.clear()
        cached = null
    }

    private fun close(
        totals: HashMap<String, Long>,
        open: HashMap<String, Long>,
        pkg: String,
        at: Long,
    ) {
        val since = open.remove(pkg) ?: return
        val span = at - since
        if (span > 0) totals[pkg] = (totals[pkg] ?: 0L) + span
    }

    private fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---- 並べ替えのための重み ---------------------------------------------

    /**
     * よく使い、かつ最近使ったものほど大きい値（0〜1）。
     *
     * 検索の並べ替えは、綴りの当たり方だけでは決まらない。同じくらい当たった二つが
     * あれば、**普段開いている方**が目的である確率が高い。KISS は履歴を、Firefox は
     * frecency（頻度 × 直近さ）を使う。ここも同じ考えをとる。
     *
     * ただし自前で数を数えることはしない。端末が既に持っている使用状況を借りる——
     * こちらから開いたものだけでなく、通知から開いたものも数に入る方が実態に近い。
     * 集計の遅れは並べ替えには効かないので、ここでは桶の値で構わない。
     */
    fun frecency(context: Context): Map<String, Double> {
        val held = cached
        if (held != null && System.currentTimeMillis() - held.at < TTL) return held.weights
        val manager = context.getSystemService(UsageStatsManager::class.java)
            ?: return emptyMap()
        val now = System.currentTimeMillis()
        val stats = runCatching {
            manager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - WINDOW, now)
        }.getOrNull().orEmpty()

        val raw = HashMap<String, Double>()
        for (entry in stats) {
            val time = entry.totalTimeInForeground
            if (time <= 0) continue
            val age = (now - entry.lastTimeUsed).coerceAtLeast(0L).toDouble() / DAY
            // 半減期をおいて古いものを軽くする。使わなくなったものは自然に沈む。
            val decay = Math.pow(0.5, age / HALF_LIFE_DAYS)
            raw[entry.packageName] = (raw[entry.packageName] ?: 0.0) + time * decay
        }
        val ceiling = raw.values.maxOrNull() ?: 0.0
        val weights = if (ceiling <= 0.0) {
            emptyMap()
        } else {
            // 一位を 1 とする相対値にする。長さの単位を持ち込まないため。
            raw.mapValues { (_, value) -> value / ceiling }
        }
        cached = Snapshot(now, weights)
        return weights
    }

    private class Snapshot(val at: Long, val weights: Map<String, Double>)

    @Volatile
    private var cached: Snapshot? = null

    private val DAY = TimeUnit.DAYS.toMillis(1).toDouble()
    private val WINDOW = TimeUnit.DAYS.toMillis(30)
    private val TTL = TimeUnit.MINUTES.toMillis(10)
    private const val HALF_LIFE_DAYS = 7.0
}
