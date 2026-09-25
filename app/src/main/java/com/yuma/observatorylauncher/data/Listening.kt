package com.yuma.observatorylauncher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * 聴いたものの記録。
 *
 * ## ⚠️ 約束の線を引き直したこと
 *
 * この体系には「**通知の中身をファイルへ書かない**」という約束がある
 * （[ObservatoryNotificationListener] に記した）。ここはその線に触れる。
 *
 * 採ったのは**全廃ではなく限定**である。書くのは `MediaSession` が持つ
 * **媒体の情報**（題・演者・アプリ・時刻・長さ）だけで、**通知の本文は書かない。**
 *
 * 守りたかったのは「通知に流れてくる私信が土に残らないこと」であって、
 * 「何を聴いたか」はそこに含まれない。
 * **約束の言葉ではなく、約束の理由に照らして線を引く。**
 *
 * ## 置き場所
 *
 * 宣言（`layout.json`）には混ぜない。宣言は利用者の持ち物で世代に残るので、
 * 勝手に増える記録を混ぜると「元に戻す」が記録の巻き戻しになる。
 */
internal object Listening {

    /** 一曲。**長さは分かれば持つ**——聴いた時間の見積りに要る。 */
    data class Play(
        val at: Long,
        val title: String,
        val artist: String,
        val app: String,
        val durationMs: Long,
        /**
         * 絵柄から採った代表色（ARGB）。取れなければ 0。
         *
         * **0 は「色が無い」ではなく「取れなかった」の印**である。
         * 帯を描く側は、塗らずに空けること。
         */
        val tint: Int = 0,
        /**
         * 私的な側（プライベートスペース）のアプリが鳴らしたものか。
         *
         * **消さずに、名を伏せる。** 数として残す価値はあるが、
         * 何を聴いたかを面へ出す筋合いは無い（[anonymised]）。
         */
        val hidden: Boolean = false,
    )

    const val LOG_NAME = "listening.json"

    /**
     * 記録してよいアプリ。**音楽を聴くためのアプリだけ**に絞る。
     *
     * > 「音楽のデータを収集するのは Apple Music と YouTube Music に絞ってください」
     * > （本人・2026/9/25）
     *
     * ## ⚠️ 絞らなかった頃に起きていたこと
     *
     * 見張りは「今いちばん上の席」を何でも記録していたので、Chrome・YouTube・
     * レコーダーまで「聴いた曲」として数えていた。**動画は一本が数時間ある**ため、
     * [spans] の頭打ち（曲の長さ）が効かず、聴取時間が膨らむ。
     *
     * 新しく書かないだけでなく、**読むときにも同じ線で落とす**（[read]）。
     * 既に記録されたものも数えから外れ、次に書くときに記録からも消える。
     */
    val SOURCES = setOf(
        "com.apple.android.music",
        "com.google.android.apps.youtube.music",
    )

    fun isSource(app: String): Boolean = app in SOURCES

    /** 記録してよいアプリの分だけ残す。 */
    fun fromSources(plays: List<Play>): List<Play> = plays.filter { isSource(it.app) }

    /** 保つ長さ。ひと月。 */
    const val KEEP_MS = 30L * 24 * 60 * 60_000

    /** 保つ件数。ひと月ぶんでもこれを超えたら古い方から捨てる。 */
    const val KEEP_COUNT = 800

    /**
     * 同じ曲の重複を弾く間合い。
     *
     * `MediaSession` は一曲の中でも何度か知らせてくる（位置・状態の変化）。
     * そのまま書くと同じ曲が並ぶので、**直前と同じ題・演者ならこの間合いの間は書かない**。
     */
    const val DEDUPE_MS = 60_000L

    /**
     * 同じ曲を「同じ一回」とみなす間合い。**曲の長さの九割**（短ければ [DEDUPE_MS]）。
     *
     * ⚠️ 一分で切っていたので、曲の途中で止めて一分後に再開すると、
     * **再開のたびに一曲増えていた**（2026/9/25 の点検）。
     * 長さの九割までは同じ一回とみなす。一曲を最後まで聴いてもう一度かけた
     * （一曲リピート）ときは、次の知らせが長さちょうどの頃に来るので、別の一回に数える。
     */
    fun sameTrackWindow(last: Play): Long =
        maxOf(DEDUPE_MS, (last.durationMs * 9) / 10)

    // ---- 読み書き（純粋な部分は端末なしで確かめられる） ----------------------

    fun parse(text: String): List<Play> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val at = o.optLong("t", 0L)
            if (at <= 0L) return@mapNotNull null
            val title = o.optString("n")
            if (title.isBlank()) return@mapNotNull null
            Play(
                at = at,
                title = title,
                artist = o.optString("a"),
                app = o.optString("p"),
                durationMs = o.optLong("d", -1L),
                tint = o.optInt("c", 0),
                hidden = o.optBoolean("x", false),
            )
        }
    }.getOrDefault(emptyList())

    fun format(plays: List<Play>): String {
        val array = JSONArray()
        plays.forEach { p ->
            array.put(
                JSONObject().apply {
                    put("t", p.at)
                    put("n", p.title)
                    if (p.artist.isNotBlank()) put("a", p.artist)
                    if (p.app.isNotBlank()) put("p", p.app)
                    if (p.durationMs > 0) put("d", p.durationMs)
                    if (p.tint != 0) put("c", p.tint)
                    if (p.hidden) put("x", true)
                },
            )
        }
        return array.toString()
    }

    /**
     * 一曲を足す。並びは常に時の順で、古いものと溢れた分は落とす。
     *
     * 直前と同じ曲が[DEDUPE_MS]の内に来たら足さない。
     */
    fun appended(existing: List<Play>, play: Play): List<Play> {
        val last = existing.lastOrNull()
        if (last != null &&
            last.title == play.title &&
            last.artist == play.artist &&
            play.at - last.at < sameTrackWindow(last)
        ) {
            return existing
        }
        val cleaned = existing.filter { it.at <= play.at && play.at - it.at <= KEEP_MS }
        return (cleaned + play).takeLast(KEEP_COUNT)
    }

    /**
     * ある期間の集計。
     *
     * **聴いた時間は見積りである。** 端末は「いつ次の曲へ移ったか」しか教えないので、
     * 一曲ぶんの時間は「次の曲までの間隔」と「曲の長さ」の**短い方**とする。
     * 途中で止めた分は短く、放置した分は曲の長さで頭打ちになる。
     *
     * ⚠️ 最後の一曲は次が無いので、長さが分かればそれを、
     * 分からなければ[ASSUMED_MS]を当てる。**分からないものを 0 にしない**
     * ——0 にすると、今かけている曲だけが集計から消える。
     */
    data class Summary(
        val plays: Int,
        val listenedMs: Long,
        val topArtist: String,
        val topArtistMs: Long,
    )

    /** 長さの分からない曲に当てる時間。おおよその一曲。 */
    const val ASSUMED_MS = 3 * 60_000L

    /**
     * 一曲ずつの「聴いた長さ」。
     *
     * ⚠️ 数え方は [summarize] の中だけに書いてあったが、帯を描く側も同じ数えが要る。
     * **同じことを二度書けば、片方だけが古いままになる**ので外へ出した。
     */
    fun spans(plays: List<Play>, until: Long): List<Pair<Play, Long>> =
        plays.mapIndexed { i, p ->
            val next = plays.getOrNull(i + 1)?.at ?: until
            val gap = (next - p.at).coerceAtLeast(0L)
            val cap = if (p.durationMs > 0) p.durationMs else ASSUMED_MS
            p to minOf(gap.takeIf { it > 0 } ?: cap, cap)
        }

    fun summarize(plays: List<Play>, until: Long): Summary {
        if (plays.isEmpty()) return Summary(0, 0L, "", 0L)
        val spans = spans(plays, until)
        val byArtist = spans
            .filter { it.first.artist.isNotBlank() }
            .groupBy { it.first.artist }
            .mapValues { entry -> entry.value.sumOf { it.second } }
        val top = byArtist.maxByOrNull { it.value }
        return Summary(
            plays = plays.size,
            listenedMs = spans.sumOf { it.second },
            topArtist = top?.key.orEmpty(),
            topArtistMs = top?.value ?: 0L,
        )
    }

    /**
     * 今週と前週。
     *
     * **前週比があって初めて、今週の数字が意味を持つ。**
     * 「6時間12分」だけでは多いか少ないか判じられない。
     *
     * ⚠️ 記録がひと月しか無いので、前週が空なら比を出さない。
     * **根拠の薄い比較は出さない方がよい。**
     */
    data class Week(
        val listenedMs: Long,
        val plays: Int,
        val artists: Int,
        /** 前週比（百分率）。前週の記録が無ければ null。 */
        val changePercent: Int?,
    )

    fun week(plays: List<Play>, now: Long = System.currentTimeMillis()): Week {
        val weekMs = 7L * 24 * 60 * 60_000
        val thisWeek = plays.filter { now - it.at <= weekMs }
        val lastWeek = plays.filter { now - it.at in (weekMs + 1)..(2 * weekMs) }
        val a = summarize(thisWeek, now)
        val b = summarize(lastWeek, now - weekMs)
        return Week(
            listenedMs = a.listenedMs,
            plays = a.plays,
            artists = thisWeek.map { it.artist }.filter { it.isNotBlank() }.distinct().size,
            changePercent = if (b.listenedMs <= 0L) {
                null
            } else {
                (((a.listenedMs - b.listenedMs) * 100) / b.listenedMs).toInt()
            },
        )
    }

    /** 私的な記録に付ける名。**中身の代わりに、一つの名で束ねる。** */
    const val HIDDEN_NAME = "非公開"

    /**
     * 私的な記録の名を伏せる。
     *
     * ## ⚠️ 消すのではなく、伏せる
     *
     * > 「プライベートスペースの再生した情報は匿名加工して情報のダッシュボード面に
     * >  乗るようにして下さい。プライベートのやつは適当な名前を付けて
     * >  ひとまとめにするなどしてください」（本人・2026/9/2）
     *
     * 消すと「今日どれだけ聴いたか」が嘘になる。
     * 名だけを一つに束ねれば、**数は正しいまま、中身は出ない**。
     *
     * 色も落とす——絵柄の色は、どの曲かを指し示してしまう。
     */
    fun anonymised(plays: List<Play>): List<Play> = plays.map { play ->
        if (!play.hidden) {
            play
        } else {
            play.copy(title = HIDDEN_NAME, artist = HIDDEN_NAME, app = "", tint = 0)
        }
    }

    /** 私的な記録を落とす。**音の面はこちらを使う**——あの面は本人の面である。 */
    fun visible(plays: List<Play>): List<Play> = plays.filterNot { it.hidden }

    /** 今日ぶんだけ取り出す。 */
    fun today(plays: List<Play>, now: Long = System.currentTimeMillis()): List<Play> {
        val start = startOfDay(now)
        return plays.filter { it.at >= start }
    }

    // ---- 端末側 -------------------------------------------------------------

    /** 記録を読む。**記録してよいアプリ以外はここで落とす**（[SOURCES]）。 */
    fun read(context: Context): List<Play> {
        val file = File(context.filesDir, LOG_NAME)
        if (!file.exists()) return emptyList()
        return fromSources(parse(runCatching { file.readText() }.getOrDefault("")))
    }

    private fun write(context: Context, plays: List<Play>) {
        runCatching { File(context.filesDir, LOG_NAME).writeText(format(plays)) }
    }

    /** 一曲を記録する。書き込みは失敗しても黙って諦める——面を壊す理由にならない。 */
    @Synchronized
    fun record(context: Context, play: Play) {
        if (play.title.isBlank() || !isSource(play.app)) return
        val next = appended(read(context), play)
        write(context, next)
    }

    /**
     * 直近の一件へ、後から色を書き足す。
     *
     * ## ⚠️ なぜ「後から」なのか
     *
     * 記録を書くのは通知の見張り（[ObservatoryNotificationListener]）である。
     * そこで絵柄を取りに外へ出るわけにはいかない
     * ——**見張りは軽くなければならない**し、鳴るたびに通信するのは筋が悪い。
     *
     * だから見張りは metadata に絵が直に入っているときだけ色を書き、
     * 入っていなければ 0 のままにする。
     * 面が絵柄を読み込んだとき（そこでは既に取りに出ている）、
     * **同じ絵から採った色をここへ書き戻す。**
     *
     * 面を開いていない間にかかった曲は色を持たないことがある。
     * それでよい——**取れなかったものを、取れたことにしない。**
     */
    @Synchronized
    fun paint(context: Context, title: String, artist: String, tint: Int) {
        if (title.isBlank() || tint == 0) return
        val plays = read(context)
        val at = plays.indexOfLast { it.title == title && it.artist == artist }
        if (at < 0) return
        if (plays[at].tint == tint) return
        write(context, plays.toMutableList().also { it[at] = it[at].copy(tint = tint) })
    }

    /** 記録を消す。利用者が「もう残さない」と決めたときの出口を必ず持たせる。 */
    fun clear(context: Context) {
        runCatching { File(context.filesDir, LOG_NAME).delete() }
    }
    // ---- 図のための集計 -----------------------------------------------------
    //
    // ⚠️ **数え方は [spans] にしかない。** ここは束ね方を変えるだけで、
    // 「一曲をどれだけ聴いたか」の判断は増やさない。

    /** 一日ぶんの束。 */
    data class Day(val label: String, val listenedMs: Long)

    /**
     * 直近 [days] 日の、日ごとの聴取。**今日を最後に置く。**
     *
     * 記録の無い日は 0 で埋める——**抜くと日付の間隔が嘘になる。**
     */
    fun byDay(plays: List<Play>, days: Int, now: Long = System.currentTimeMillis()): List<Day> {
        val start = startOfDay(now)
        val dayMs = 24L * 60 * 60_000
        val spans = spans(plays, now)
        return (days - 1 downTo 0).map { back ->
            val from = start - back * dayMs
            val to = from + dayMs
            Day(
                label = java.text.SimpleDateFormat("d", java.util.Locale.JAPAN)
                    .format(java.util.Date(from)),
                listenedMs = spans.filter { it.first.at in from until to }.sumOf { it.second },
            )
        }
    }

    /**
     * 時刻ごとの聴取（記録の全期間）。24 の枡。
     *
     * **一日の形**を見るためのもので、日付は畳む。
     * 「夜に増える」「朝は聴かない」は、日をまたいで初めて形になる。
     */
    fun byHour(plays: List<Play>, now: Long = System.currentTimeMillis()): LongArray {
        val out = LongArray(24)
        val calendar = java.util.Calendar.getInstance()
        spans(plays, now).forEach { (play, span) ->
            calendar.timeInMillis = play.at
            val hour = calendar.get(java.util.Calendar.HOUR_OF_DAY)
            out[hour] += span
        }
        return out
    }

    /**
     * 直近 [days] 日ぶんだけ取り出す。
     *
     * ⚠️ **隣り合う図が別の期間を指さないための道具である。**
     * 「七日の推移」の隣に、ひと月ぶんから数えた「よく聴く演者」を並べていた
     * （2026/9/2 の点検で発見）。
     */
    fun within(plays: List<Play>, days: Int, now: Long = System.currentTimeMillis()): List<Play> {
        val from = startOfDay(now) - (days - 1) * 24L * 60 * 60_000
        return plays.filter { it.at >= from }
    }

    /** 演者ごとの聴取。長い順。 */
    fun byArtist(
        plays: List<Play>,
        now: Long = System.currentTimeMillis(),
    ): List<Pair<String, Long>> =
        spans(plays, now)
            .filter { it.first.artist.isNotBlank() }
            .groupBy { it.first.artist }
            .map { entry -> entry.key to entry.value.sumOf { it.second } }
            .sortedByDescending { it.second }

    private fun startOfDay(at: Long): Long = Calendar.getInstance().apply {
        timeInMillis = at
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

}
