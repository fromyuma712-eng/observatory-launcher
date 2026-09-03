package com.yuma.observatorylauncher.data

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.Calendar

/**
 * 使い方の分析。
 *
 * ## なぜ「合計」だけでは足りないか
 *
 * > 「今は使った時間が目に見えていない。徹底的に分析して表示してくれたら、
 * > 次回から気を付けるんじゃないか」（本人・2026/9/1）
 *
 * 合計時間は、多いか少ないかは言うが、**なぜそうなったか**を言わない。
 * 行動が変わるのは、たいてい次の三つのどれかが見えたときである。
 *
 * - **開いた回数。** 「三十分」より「四十二回」の方が効く。時間は流れるが、回数は数えた分だけ自分の指の動きである。
 * - **一続きの長さ。** 五分を六回と、三十分を一回は、同じ三十分でも意味が違う。
 * - **時間帯。** 「夜中に一時間」は、日中の一時間と重さが違う。
 *
 * ## 出来事から組む
 *
 * 桶（`queryUsageStats`）は日ごとの合計しか持たない。
 * 上の三つはすべて**出来事の時刻**が要るので、`queryEvents` から組む。
 *
 * ⚠️ **出来事は数日で畳まれて消える。** 遡れる範囲は端末任せなので、
 * 「七日ぶん」と決め打ちにせず、**取れた範囲を返して呼び出し側に判じさせる**。
 */
internal object Behaviour {

    /** 一続きの使用。 */
    data class Session(val packageName: String, val from: Long, val to: Long) {
        val lengthMs: Long get() = (to - from).coerceAtLeast(0L)
    }

    data class Analysis(
        val sessions: List<Session>,
        val totalMs: Long,
        /** アプリごとに開いた回数。 */
        val opens: Map<String, Int>,
        /** アプリごとの合計。 */
        val byPackage: Map<String, Long>,
        /** 時間帯ごとの合計（0時〜23時の 24 個）。 */
        val hourly: List<Long>,
        val longest: Session?,
        /** 22時〜翌5時の分。**夜の時間は昼の時間と重さが違う。** */
        val lateMs: Long,
    ) {
        val opensTotal: Int get() = opens.values.sum()

        /** 一続きの平均。回数が 0 なら 0——**0 で割らない。** */
        val averageMs: Long get() = if (sessions.isEmpty()) 0L else totalMs / sessions.size
    }

    /**
     * 出来事の並びから組む計算そのもの。端末なしで確かめられるよう切り出す。
     *
     * [events] は (パッケージ, 種類, 時刻)。並び順は前提にしない。
     *
     * 前面に来たまま終わっているものは [now] までを数える。
     * 画面が消えた出来事では開いているものをすべて閉じる
     * ——画面を消したまま朝を迎えたアプリに一晩ぶんを付けないためである。
     */
    /**
     * 出来事の一つ。
     *
     * ⚠️ **画面（`className`）まで持つ。** 下の解説のとおり、
     * パッケージだけで追うと別の画面の終わりで巻き込み事故が起きる。
     */
    data class Moment(
        val packageName: String,
        val className: String,
        val type: Int,
        val at: Long,
    )

    /**
     * 出来事の並びから、使った区間を組み立てる。
     *
     * ## ⚠️ パッケージ単位で追ってはならない（2026/9/2 是正）
     *
     * ここは「あるパッケージが前に出た／退いた」だけを見て区間を作っていた。
     * ところが Android が知らせてくるのは**画面（activity）単位**であり、
     * 一つのアプリが複数の画面を持つと、次のように交錯する。
     *
     * ```
     * 11:09:18 RESUMED chatgpt/ChatGptDeeplinkActivity
     * 11:09:18 PAUSED  chatgpt/ChatGptDeeplinkActivity
     * 11:09:18 RESUMED chatgpt/MainActivity        ← 本体が開いた
     * 11:09:19 STOPPED chatgpt/ChatGptDeeplinkActivity  ← ★ 別の画面の終わり
     * 12:03:57 PAUSED  chatgpt/MainActivity        ← 本当の終わり
     * ```
     *
     * パッケージ単位だと ★ が本体の区間を閉じてしまい、
     * **54分39秒の使用が「1秒」になっていた**（実機・2026/9/2）。
     * その結果、同じ面の中で見出しが 5分・横棒が 54分という食い違いが出た。
     *
     * だから画面ごとに追い、**最後にパッケージごとへ畳む**
     * ——重なった区間は一つにまとめる（同じアプリの二画面を二重に数えない）。
     *
     * @param now まだ閉じていない区間をここで打ち切る。
     */
    fun analyze(events: List<Moment>, now: Long): Analysis {
        val open = HashMap<Pair<String, String>, Long>()
        val raw = ArrayList<Triple<String, Long, Long>>()

        fun close(key: Pair<String, String>, at: Long) {
            val from = open.remove(key) ?: return
            if (at > from) raw += Triple(key.first, from, at)
        }

        events.sortedBy { it.at }.forEach { moment ->
            val key = moment.packageName to moment.className
            when (moment.type) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    if (!open.containsKey(key)) open[key] = moment.at

                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                -> close(key, moment.at)

                SCREEN_OFF, KEYGUARD_SHOWN ->
                    open.keys.toList().forEach { close(it, moment.at) }
            }
        }
        open.keys.toList().forEach { close(it, now) }

        val sessions = mergeByPackage(raw)

        // ⚠️ **ひらいた回数は、畳んだあとの区間の数で数える。**
        //
        // 出来事を直に数えると、入口の画面から本体へ移る一瞬（同じ時刻の
        // 閉じ→開き）が二回に見える。畳んだ区間なら、それは一続きの一回である。
        val opens = sessions.groupingBy { it.packageName }.eachCount()

        val hourly = LongArray(24)
        var late = 0L
        sessions.forEach { s ->
            spread(s.from, s.to, hourly)
            late += overlapWithNight(s.from, s.to)
        }

        return Analysis(
            sessions = sessions.sortedBy { it.from },
            totalMs = sessions.sumOf { it.lengthMs },
            opens = opens,
            byPackage = sessions.groupBy { it.packageName }
                .mapValues { e -> e.value.sumOf { it.lengthMs } },
            hourly = hourly.toList(),
            longest = sessions.maxByOrNull { it.lengthMs },
            lateMs = late,
        )
    }

    /**
     * 画面ごとの区間を、パッケージごとに畳む。
     *
     * **重なりは一つにまとめる。** 入口の画面と本体が一瞬重なるのは普通のことで、
     * そのまま足すと同じ時間を二度数える。
     */
    private fun mergeByPackage(raw: List<Triple<String, Long, Long>>): List<Session> {
        val out = ArrayList<Session>()
        raw.groupBy { it.first }.forEach { (pkg, spans) ->
            var from = -1L
            var to = -1L
            spans.sortedBy { it.second }.forEach { (_, s, e) ->
                if (from < 0) {
                    from = s
                    to = e
                } else if (s <= to) {
                    if (e > to) to = e
                } else {
                    out += Session(pkg, from, to)
                    from = s
                    to = e
                }
            }
            if (from >= 0) out += Session(pkg, from, to)
        }
        return out
    }

    private fun spread(from: Long, to: Long, into: LongArray) {
        var at = from
        val c = Calendar.getInstance()
        while (at < to) {
            c.timeInMillis = at
            val hour = c.get(Calendar.HOUR_OF_DAY)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            val nextHour = c.timeInMillis + 3_600_000L
            val end = minOf(to, nextHour)
            into[hour] += (end - at)
            at = end
        }
    }

    /** 22時〜翌5時と重なる分。 */
    private fun overlapWithNight(from: Long, to: Long): Long {
        var total = 0L
        var at = from
        val c = Calendar.getInstance()
        while (at < to) {
            c.timeInMillis = at
            val hour = c.get(Calendar.HOUR_OF_DAY)
            c.set(Calendar.MINUTE, 0)
            c.set(Calendar.SECOND, 0)
            c.set(Calendar.MILLISECOND, 0)
            val nextHour = c.timeInMillis + 3_600_000L
            val end = minOf(to, nextHour)
            if (hour >= 22 || hour < 5) total += (end - at)
            at = end
        }
        return total
    }

    /** `UsageEvents.Event.SCREEN_NON_INTERACTIVE`。 */
    private const val SCREEN_OFF = 16

    /** `UsageEvents.Event.KEYGUARD_SHOWN`。 */
    private const val KEYGUARD_SHOWN = 17

    // ---- 端末側 -------------------------------------------------------------

    /**
     * ある期間の分析。
     *
     * 一覧に出ないもの（入力や通知の面など）は数えない
     * ——素通りに数えると、端末自身の集計より大きく出る。
     */
    fun between(context: Context, from: Long, to: Long): Analysis? {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val stream = runCatching { manager.queryEvents(from, to) }.getOrNull() ?: return null
        val self = context.packageName
        val collected = ArrayList<Moment>()
        val event = UsageEvents.Event()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(event)
            val pkg = event.packageName ?: continue
            if (pkg == self) continue
            collected += Moment(pkg, event.className.orEmpty(), event.eventType, event.timeStamp)
        }
        val counted = collected.filter { Usage.isLaunchable(context, it.packageName) }
        return analyze(counted, to)
    }

    fun today(context: Context, now: Long = System.currentTimeMillis()): Analysis? =
        between(context, startOfDay(now), now)

    /**
     * 今日の歩調。
     *
     * **合計より、この一つが行動を変える。**
     * 「今日 1時間20分」だけでは多いか少ないか判じられないが、
     * 「ふだんのこの時刻より +32分」なら、その場で分かる。
     *
     * ⚠️ **比べるのは「同じ時刻まで」である。**
     * 一日の合計と今日の途中経過を比べると、朝はいつでも「少ない」ことになり、
     * 数字が何も言わなくなる。過去の日も、今日と同じだけ経った時点で切る。
     *
     * ⚠️ 比べる相手に**今日を入れない**。入れると、良い日でも平均がそちらへ寄って差が縮む。
     */
    data class Pace(val todayMs: Long, val usualMs: Long, val days: Int) {
        val differenceMs: Long get() = todayMs - usualMs
        /** 比べる相手が足りなければ、差を出さない。**根拠の薄い比較は出さない方がよい。** */
        val comparable: Boolean get() = days >= 2
    }

    /**
     * 一日ぶんの出来事から、[untilMs]（その日の 0 時からの経過）までの合計を出す。
     * 純粋な計算なので端末なしで確かめられる。
     */
    fun untilOf(
        events: List<Moment>,
        dayStart: Long,
        untilMs: Long,
    ): Long = analyze(
        events.filter { it.at <= dayStart + untilMs },
        now = dayStart + untilMs,
    ).totalMs

    /**
     * 今日ここまでの使用と、ふだんの同時刻。
     *
     * ## ⚠️ 見出しと横棒が食い違っていた（2026/9/2 是正）
     *
     * 同じ面の中で**横棒は ChatGPT 54分、見出しは 5分**と出ていた。
     *
     * ⚠️ **最初の見立ては誤りだった。** 「端末が出来事を捨てている」と考えたが、
     * `dumpsys` で確かめると出来事は全部残っていた。
     * 本当の原因は [analyze] が**アプリ単位で区間を閉じていた**ことで、
     * 入口の画面の終わりが本体の区間を閉じ、54分が 1秒になっていた。
     * そちらを直したので、出来事からの数えも正しくなっている。
     *
     * それでも今日の値は [Usage.today] から採る。
     * **出来事と桶（`queryUsageStats`）は別の道で、どちらも欠けうる。**
     * 同じ量を数える札が二つある以上、**源を一つに揃える**方が確かである。
     *
     * ## 過去の日をどう揃えるか
     *
     * 桶は**一日ぶんの合計**しか持たないので、「この時刻まで」で切れない。
     * そこで過去の日は、出来事から出した「この時刻まで」に、
     * その日の**欠け具合**（桶 ÷ 出来事の一日合計）を掛けて補う。
     *
     * ⚠️ 出来事が全部残っていれば、この倍率は 1 に近づき何もしない。
     * 効くのは**古い日で出来事が本当に薄いとき**だけである。
     *
     * ⚠️ これは**見積りである**。欠けた出来事が、残った出来事と同じように
     * 一日へ散っていると仮定している。だから画面には「約」を付けて出す。
     * 補正の倍率は歯止めを掛ける——桁の違う数字を、それらしい顔で出さない。
     */
    fun pace(context: Context, now: Long = System.currentTimeMillis()): Pace? {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return null
        val todayStart = startOfDay(now)
        val elapsed = now - todayStart
        val self = context.packageName

        /** その日の出来事を、[untilMs] までと一日ぶんの二通りで合計する。 */
        fun events(dayStart: Long, untilMs: Long): Pair<Long, Long>? {
            val stream = runCatching {
                manager.queryEvents(dayStart, dayStart + maxOf(untilMs, DAY_MS))
            }.getOrNull() ?: return null
            val collected = ArrayList<Moment>()
            val event = UsageEvents.Event()
            while (stream.hasNextEvent()) {
                stream.getNextEvent(event)
                val pkg = event.packageName ?: continue
                if (pkg == self) continue
                collected += Moment(
                    pkg,
                    event.className.orEmpty(),
                    event.eventType,
                    event.timeStamp,
                )
            }
            if (collected.isEmpty()) return null
            val counted = collected.filter { Usage.isLaunchable(context, it.packageName) }
            return untilOf(counted, dayStart, untilMs) to untilOf(counted, dayStart, DAY_MS)
        }

        // 今日は、桶と出来事の**大きい方**（[Usage.today] と同じ源・同じ数え方）。
        val today = Usage.today(context)?.total ?: 0L

        val past = (1..6).mapNotNull { back ->
            val dayStart = todayStart - back * DAY_MS
            val settled = Usage.settledOn(context, dayStart).values.sum()
            val pair = events(dayStart, elapsed)
            when {
                // 出来事が残っている日：この時刻までを、その日の欠け具合で補う。
                pair != null && pair.second > 0L ->
                    makeUp(untilMs = pair.first, wholeDayMs = pair.second, settledMs = settled)
                // 出来事が丸ごと消えている日は、**数えられなかったものとして外す**。
                // 一日の合計を時刻で按分すると、朝型か夜型かで大きく外れる。
                else -> null
            }
        }
        val usual = if (past.isEmpty()) 0L else past.sum() / past.size
        return Pace(today, usual, past.size)
    }

    /**
     * 出来事の取りこぼしを、桶の合計で補う。
     *
     * 桶（`queryUsageStats`）は**一日ぶんの合計**しか持たないので「この時刻まで」で切れない。
     * そこで、出来事から出した「この時刻まで」に、その日の欠け具合を掛ける。
     *
     * ```
     * 補正後 = この時刻まで × (桶の一日合計 ÷ 出来事の一日合計)
     * ```
     *
     * ⚠️ **これは見積りである。** 欠けた出来事が、残った出来事と同じように
     * 一日へ散っていると仮定している。だから画面には「約」を付けて出す。
     *
     * ⚠️ **出来事が揃っていれば倍率は 1 になり、何もしない。**
     * 効くのは、古い日で出来事が本当に薄いときだけである。
     *
     * 倍率には歯止めを掛ける——**一件の記録から一日を作り出さない。**
     */
    fun makeUp(untilMs: Long, wholeDayMs: Long, settledMs: Long): Long {
        if (wholeDayMs <= 0L) return untilMs
        val factor = if (settledMs > 0L) {
            (settledMs.toDouble() / wholeDayMs).coerceIn(1.0, MAX_MAKEUP)
        } else {
            1.0
        }
        return (untilMs * factor).toLong()
    }

    /**
     * 欠けを補う倍率の上限。
     *
     * 出来事がほとんど残っていない日に素直な比を掛けると、
     * **一件の記録から一日を作り出す**ことになる。そこまではしない。
     */
    private const val MAX_MAKEUP = 4.0

    /**
     * 出来事がどこまで遡れるか。
     *
     * ⚠️ **端末任せなので決め打ちにしない。** 「七日ぶん」と書いて六日しか無ければ、
     * 平均が静かに狂う。取れた範囲を返し、呼び出し側に判じさせる。
     */
    fun reach(context: Context, want: Int = 7, now: Long = System.currentTimeMillis()): Int {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return 0
        for (days in want downTo 1) {
            val from = startOfDay(now) - (days - 1) * DAY_MS
            val stream = runCatching { manager.queryEvents(from, from + DAY_MS) }.getOrNull()
            if (stream?.hasNextEvent() == true) return days
        }
        return 1
    }

    private const val DAY_MS = 24 * 60 * 60_000L

    private fun startOfDay(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
