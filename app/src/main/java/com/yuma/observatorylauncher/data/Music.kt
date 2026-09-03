package com.yuma.observatorylauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.content.res.ResourcesCompat

/**
 * 今鳴っているもの。
 *
 * 通知の文字（題と副題）だけを見ていた頃は、曲名と演者しか出せなかった。
 * **`MediaSession` を見ると、位置・長さ・止まっているかまで分かり、操作もできる。**
 * 通知の読み取りを既に許してもらっているので、追加の解錠は要らない。
 *
 * **記録は残さない。** 通知の中身をファイルへ書かない、というこの体系の約束
 * （[ObservatoryNotificationListener] に記した）をここでも守る。
 * 出すのは今の値だけで、履歴は持たない。
 */
internal object Music {

    /** 今の一曲。鳴っているものが無ければ null。 */
    data class Playing(
        val title: String,
        val artist: String,
        val app: String,
        /** 鳴らしているアプリ。曲名を押したときの行き先に使う。 */
        val packageName: String,
        /** 経過。分からなければ負。 */
        val position: Long,
        /** 全体。分からなければ負。 */
        val duration: Long,
        val playing: Boolean,
    ) {
        /** 目盛りに使う割合。長さが分からなければ null——**目盛りを嘘にしない**。 */
        val progress: Float?
            get() = if (duration > 0 && position >= 0) {
                (position.toFloat() / duration).coerceIn(0f, 1f)
            } else {
                null
            }
    }

    /**
     * 今の再生位置。
     *
     * `PlaybackState.getPosition()` は**その状態が作られた時点の値**であって、今の値ではない。
     * そのまま出すと、針が止まって見える。最後に更新された時刻からの経過を、
     * 再生の速さを掛けて足す。**止まっているときは足さない。**
     *
     * ## ⚠️ 時間の軸が違う
     *
     * **`lastPositionUpdateTime` は `SystemClock.elapsedRealtime()` の軸である。**
     * 壁時計（`System.currentTimeMillis`）を渡すと、桁がまるで違うので
     * 経過が数十年になり、位置が暴走する。**例外は出ない**——
     * `3:37` の曲に `487654:39:20` と表示され、その一行が横へ広がって、
     * 隣の操作の位置までずれる（実機でそうなった）。
     *
     * [elapsed] には必ず `SystemClock.elapsedRealtime()` を渡すこと。
     */
    fun positionAt(state: PlaybackState?, elapsed: Long): Long {
        if (state == null) return -1
        return advance(
            base = state.position,
            updatedAt = state.lastPositionUpdateTime,
            speed = state.playbackSpeed,
            playing = state.state == PlaybackState.STATE_PLAYING,
            elapsed = elapsed,
        )
    }

    /**
     * 位置を今へ進める計算そのもの。
     *
     * `PlaybackState` は端末の中でしか組み立てられないので、
     * **計算だけを切り出して、端末なしで確かめられるようにしてある。**
     * 時間の軸を取り違えるという間違いは、ここで押さえる。
     */
    fun advance(base: Long, updatedAt: Long, speed: Float, playing: Boolean, elapsed: Long): Long {
        if (base < 0) return -1
        if (!playing) return base
        val since = elapsed - updatedAt
        if (since <= 0) return base
        val rate = if (speed > 0f) speed else 1f
        return base + (since * rate).toLong()
    }

    /**
     * 時間を `分:秒` で。一時間を超えるものは `時:分:秒`。
     * **秒は必ず二桁**——桁が揺れると、数字が跳ねて読みにくい。
     */
    fun clock(millis: Long): String {
        if (millis < 0) return "--:--"
        val total = millis / 1000
        val seconds = total % 60
        val minutes = (total / 60) % 60
        val hours = total / 3600
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private fun listener(context: Context) =
        ComponentName(context, ObservatoryNotificationListener::class.java)

    /** 今ある席。**プラットフォームが既に優先順に並べている**（index 0 が最上位）。 */
    fun sessions(context: Context): List<MediaController> {
        if (!InfoSources.hasNotificationAccess(context)) return emptyList()
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return emptyList()
        return runCatching { manager.getActiveSessions(listener(context)) }.getOrNull().orEmpty()
    }

    /** 席の見立て。**選ぶのに要る分だけ**を持つ。 */
    data class Seat(
        /** 出せるもの（題か演者）があるか。 */
        val hasContent: Boolean,
        /** 今その席が鳴っているか。 */
        val playing: Boolean,
        /**
         * 私的な側（プライベートスペース）のアプリの席か。
         *
         * **音の面には出さない。** あの面は絵柄も題も大きく出す面なので、
         * 伏せる工夫をしても伏せきれない（本人指示・2026/9/2）。
         */
        val hidden: Boolean = false,
    )

    /**
     * 何番目の席を採るか。
     *
     * ## 経緯——一度戻して、もう一度入れた
     *
     * 昔は「鳴っているものを優先」という自前の順を作っていた。
     * これは**誤りだったので戻した**——`getActiveSessions` は既に重要度の順で返り
     * （index 0 が最上位）、自前で並べ替えると電話の席を音楽より上に置いてしまう。
     *
     * ⚠️ ところが実機で **YouTube Music（再生中）と Apple Music（停止中・題あり）が
     * 同時に生きている**ことが分かった（2026/9/1）。
     * 「出せるものがある最初の席」だけでは、止まっている方を操りかねない。
     *
     * そこで、**戻したときの失敗を繰り返さない形**で入れ直す。
     *
     * - 並べ替えない。**候補を「出せるものがある席」に限ってから**、鳴っている席を選ぶ。
     * - 電話の席は題も演者も持たないので、この時点で既に候補から外れている
     *   ——**昔の失敗はここで塞がれる。**
     * - 鳴っている席が無ければ、platform の順の先頭に戻る。
     *
     * `null` は「出せるものが無い」。
     */
    fun chooseIndex(seats: List<Seat>): Int? {
        val candidates = seats.withIndex().filter { it.value.hasContent && !it.value.hidden }
        if (candidates.isEmpty()) return null
        return (candidates.firstOrNull { it.value.playing } ?: candidates.first()).index
    }

    fun controller(context: Context): MediaController? {
        val all = sessions(context)
        val at = chooseIndex(all.map { seatOf(it) }) ?: return null
        return all[at]
    }

    private fun hasContent(controller: MediaController): Boolean {
        val metadata = controller.metadata ?: return false
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        return title.isNotBlank() || artist.isNotBlank()
    }

    private fun seatOf(controller: MediaController): Seat = Seat(
        hasContent = hasContent(controller),
        playing = runCatching { controller.playbackState?.state }.getOrNull() ==
            PlaybackState.STATE_PLAYING,
        hidden = AppRepository.isPrivateOnly(
            runCatching { controller.packageName }.getOrDefault(""),
        ),
    )

    fun current(context: Context, elapsed: Long = SystemClock.elapsedRealtime()): Playing? =
        controller(context)?.let { read(context, it, elapsed) }

    /**
     * 一つの席を読む。位置は[positionAt]で今の値へ直す。
     * [elapsed] は `SystemClock.elapsedRealtime()`——壁時計ではない。
     */
    fun read(
        context: Context,
        controller: MediaController,
        elapsed: Long = SystemClock.elapsedRealtime(),
    ): Playing? {
        val metadata = controller.metadata
        val state = controller.playbackState
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        if (title.isBlank() && artist.isBlank()) return null
        val app = runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(controller.packageName, 0)).toString()
        }.getOrDefault(controller.packageName)
        return Playing(
            title = title,
            artist = artist,
            app = app,
            packageName = controller.packageName,
            position = positionAt(state, elapsed),
            duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1,
            playing = state?.state == PlaybackState.STATE_PLAYING,
        )
    }

    // ---- 操作 -------------------------------------------------------------

    /** 押されたことが伝わらないと困るので、通ったかどうかを返す。 */
    private fun command(context: Context, action: MediaController.TransportControls.() -> Unit) =
        runCatching { controller(context)?.transportControls?.action() != null }.getOrDefault(false)

    fun toggle(context: Context): Boolean {
        val controller = controller(context) ?: return false
        return runCatching {
            if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
                controller.transportControls.pause()
            } else {
                controller.transportControls.play()
            }
            true
        }.getOrDefault(false)
    }

    /**
     * 鳴らしているアプリを前に出す。
     *
     * **まず `sessionActivity` を使う。** これはアプリ自身が
     * 「今鳴っている画面」として登録した入口で、AOSP のメディア操作も同じ所へ送る。
     * 起動口から入り直すと、アプリによっては最初の画面に戻ってしまい、
     * 「今の曲を見に行く」という意図と合わない。
     *
     * 登録が無いアプリのために、通常の起動口へ落とす道を残す。
     */
    fun openPlayer(context: Context): Boolean {
        val controller = controller(context) ?: return false
        controller.sessionActivity?.let { pending ->
            if (runCatching { pending.send() }.isSuccess) return true
        }
        val intent = runCatching {
            context.packageManager.getLaunchIntentForPackage(controller.packageName)
        }.getOrNull() ?: return false
        return runCatching {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        }.getOrDefault(false)
    }

    fun next(context: Context) = command(context) { skipToNext() }

    fun previous(context: Context) = command(context) { skipToPrevious() }
    /**
     * 席から**追加で読み取れるもの**。
     *
     * 題と演者だけが `MediaSession` の中身ではない。
     * 席は列（次にかかるもの）・盤の名・年・速さを持っている。
     *
     * > 「再生している下側のところに、再生している音から得られる情報で何か表示して欲しい」
     * > （本人・2026/9/1）
     *
     * ⚠️ **繰り返しと混ぜは framework の `MediaController` からは取れない。**
     * `repeatMode` / `shuffleMode` は AndroidX の `MediaControllerCompat` 側の持ち物で、
     * OS の `android.media.session.MediaController` には無い。
     * **在ると思って書いたら通らなかった**——取れるものだけを並べる。
     *
     * ⚠️ **取れないものは持たない（null）。**
     * アプリによって埋めている鍵が違うので、
     * 「無い」を空文字や 0 で埋めると、面に嘘の欄が並ぶ。
     */
    data class Detail(
        /** 次にかかるもの。列を持たないアプリでは null。 */
        val next: String?,
        /** 列の長さ。列が無ければ null。 */
        val queueSize: Int?,
        /** 盤の名。 */
        val album: String?,
        /** 年。 */
        val year: String?,
        /** 速さ。等速なら null——**当たり前の値を欄にしない**。 */
        val speed: Float?,
    ) {
        val isEmpty: Boolean
            get() = next == null && queueSize == null && album == null &&
                year == null && speed == null
    }

    fun detail(controller: MediaController): Detail {
        val metadata: MediaMetadata? = runCatching { controller.metadata }.getOrNull()
        val state: PlaybackState? = runCatching { controller.playbackState }.getOrNull()
        val queue = runCatching { controller.queue }.getOrNull().orEmpty()

        // 列は「今かかっているものの次」を探す。
        // 席によっては列の先頭が今の曲ではないので、id で今の位置を突き止める。
        val next: String? = runCatching {
            if (queue.isEmpty()) return@runCatching null
            val activeId = state?.activeQueueItemId
            val at = queue.indexOfFirst { it.queueId == activeId }
            val following = if (at >= 0) queue.getOrNull(at + 1) else queue.getOrNull(0)
            following?.description?.title?.toString()?.takeIf { it.isNotBlank() }
        }.getOrNull()

        fun text(key: String): String? =
            runCatching { metadata?.getString(key) }.getOrNull()?.takeIf { it.isNotBlank() }

        // 年は `YEAR`(数)と `DATE`(文字)のどちらかに入る。**両方見る。**
        val year: String? = runCatching { metadata?.getLong(MediaMetadata.METADATA_KEY_YEAR) }
            .getOrNull()?.takeIf { it > 0L }?.toString()
            ?: text(MediaMetadata.METADATA_KEY_DATE)
                ?.take(4)?.takeIf { it.length == 4 && it.all(Char::isDigit) }

        val speed: Float? = state?.playbackSpeed
            ?.takeIf { it > 0f && kotlin.math.abs(it - 1f) > 0.01f }

        return Detail(
            next = next,
            queueSize = queue.size.takeIf { it > 1 },
            album = text(MediaMetadata.METADATA_KEY_ALBUM),
            year = year,
            speed = speed,
        )
    }

    // ---- 掴む・評価する・混ぜる ---------------------------------------------

    /**
     * 針を掴んで動かせるか。
     *
     * ⚠️ **出せる／出せないは席が名乗る。** 勝手に掴ませてはならない。
     * 実機の YouTube Music は `ACTION_SEEK_TO` を名乗っている（2026/9/1 実測）が、
     * 名乗らない席で掴ませると、指は動くのに音は動かない——
     * **動いたように見えて動かないのが、最も悪い**。
     */
    fun canSeek(controller: MediaController): Boolean =
        ((runCatching { controller.playbackState?.actions }.getOrNull() ?: 0L) and
            PlaybackState.ACTION_SEEK_TO) != 0L

    fun seekTo(context: Context, millis: Long): Boolean =
        command(context) { seekTo(millis.coerceAtLeast(0L)) }

    /** 入／切／読めない。**読めないものを「切」と書かない。** */
    enum class Switch { ON, OFF, UNKNOWN }

    /**
     * 席が出している切替の操作一つ。
     *
     * @param id 送るときの合言葉（`CustomAction.getAction()`）。表示名ではない。
     * @param label 「シャッフル」のような短い名。
     * @param state 「（オフ）」から読んだ状態。読めなければ [Switch.UNKNOWN]。
     * @param stateText 括弧の中の言葉そのもの。読めた通りに出す。
     */
    data class Mark(
        val id: String,
        val label: String,
        val state: Switch,
        val stateText: String?,
        /** 今その操作が効いている（＝押すと解ける）と読めたか。 */
        val hot: Boolean,
        /** **アプリ自身が持っている記号**の資源番号。0 なら無い。 */
        val icon: Int,
        /** その資源が住んでいるアプリ。記号を起こすのに要る。 */
        val fromPackage: String,
    )

    /**
     * 席の操作の記号を起こす。
     *
     * ## なぜ自前の記号を描かないか
     *
     * > 「お気に入り、シャッフル、リピートをそれぞれ記号に置き換えてください」（本人・2026/9/2）
     *
     * 記号を自分で用意すると、**どの操作にどの記号かを言葉から当てる**ことになる。
     * 言葉は端末の設定でもアプリでも変わるので、当てそこねる。
     *
     * `CustomAction` は**アプリ自身の記号**を連れている（通知に出しているものと同じ）。
     * 借りれば、当てる必要が無い。**アプリが「これはシャッフルだ」と言っている記号**を使う。
     *
     * ⚠️ 記号は相手のアプリの資源なので、こちらの色は乗っていない。
     * 面の側で染める前提で、**形だけ**を受け取る。
     */
    fun markIcon(context: Context, mark: Mark, sizePx: Int): Bitmap? {
        if (mark.icon == 0 || mark.fromPackage.isBlank() || sizePx <= 0) return null
        return runCatching {
            val resources = context.packageManager.getResourcesForApplication(mark.fromPackage)
            val drawable = ResourcesCompat.getDrawable(resources, mark.icon, null) ?: return null
            val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            bitmap
        }.getOrNull()
    }

    /**
     * 名前を「名」と「状態」に割る。
     *
     * ## ⚠️ これは規約ではない
     *
     * `MediaSession` に「今シャッフルか」を訊く道は framework 側に無い。
     * ところが実機の YouTube Music は、custom action の**表示名に状態を書いている**
     * ——「シャッフル（オフ）」「リピート（オフ）」（2026/9/1 実測）。
     *
     * これは**当てにしてよい値ではなく、読めたら使う値**である。
     * 読めなければ [Switch.UNKNOWN] を返し、面は状態を主張しない。
     * **読めなかったものを既定値で描くのが、最も危うい。**
     */
    /** 「名（状態）」を割るための型。全角・半角どちらの括弧も受ける。 */
    private val TOGGLE_LABEL = Regex("""^(.*?)[（(]([^）)]*)[）)]\s*$""")

    fun splitLabel(name: String): Triple<String, Switch, String?> {
        val match = TOGGLE_LABEL.find(name.trim())
            ?: return Triple(name.trim(), Switch.UNKNOWN, null)
        val label = match.groupValues[1].trim()
        val inner = match.groupValues[2].trim()
        val state = when (inner.lowercase()) {
            "オン", "on", "有効" -> Switch.ON
            "オフ", "off", "無効" -> Switch.OFF
            else -> Switch.UNKNOWN
        }
        return Triple(label.ifBlank { name.trim() }, state, inner.ifBlank { null })
    }

    /** 面に出す席の操作の数。**アプリ自身が並べた順の先頭から。** */
    const val MARKS_SHOWN = 3

    /**
     * 「高評価を取り消す」のように、**今それが効いている**ことを語る言い回し。
     *
     * ⚠️ 規約ではない。実機の YouTube Music が、評価を付けると操作の名前を
     * 「高く評価」から「高評価を取り消す」へ変えるのを読んでいるだけである
     * （2026/9/1 実測）。読めなければ何も主張しない。
     */
    private val UNDO_LIKE = Regex("取り消|取消|Undo|Remove", RegexOption.IGNORE_CASE)

    /**
     * 席が出している操作を拾う。
     *
     * ## ⚠️ 型のある道より、席自身の操作の方が良かった
     *
     * はじめは評価だけ `setRating`（型のある道）で送っていた。
     * 実機で Apple Music を試して考えを変えた。
     *
     * | | YouTube Music | Apple Music |
     * | --- | --- | --- |
     * | `rating type` | 2（親指式） | **0（名乗らない）** |
     * | metadata の評価 | **書かない** | 書かない |
     * | custom action | 高く評価／シャッフル（オフ）／リピート（オフ） | お気に入り／シャッフル／リピート |
     *
     * 型のある道は **Apple Music では何も出せず**、YouTube Music でも
     * 状態は結局 custom action の名前からしか読めなかった。
     * **席自身の操作を出す方が、広く効いて、しかも正直である。**
     *
     * ## 何を出すか
     *
     * **アプリが並べた順の先頭から三つ。** 語の一覧（「シャッフル」等）で選り分けない
     * ——言葉は端末の設定で変わるし、アプリごとに違う。
     * **アプリは自分の通知に出す順で並べている**ので、その順を信じる。
     *
     * 実測でも、両アプリとも先頭三つがちょうど要るものだった。
     */
    fun marks(controller: MediaController): List<Mark> {
        val actions = runCatching { controller.playbackState?.customActions }
            .getOrNull().orEmpty()
        return actions.mapNotNull { action ->
            val name = action.name?.toString().orEmpty()
            val id = action.action.orEmpty()
            if (name.isBlank() || id.isBlank()) return@mapNotNull null
            val (label, state, text) = splitLabel(name)
            Mark(
                id = id,
                label = label,
                state = state,
                stateText = text,
                // 「高評価を取り消す」のように**解く言い回し**なら、今は効いている。
                hot = state == Switch.ON || UNDO_LIKE.containsMatchIn(name),
                icon = runCatching { action.icon }.getOrDefault(0),
                fromPackage = runCatching { controller.packageName }.getOrDefault(""),
            )
        }.take(MARKS_SHOWN)
    }

    fun sendCustom(context: Context, id: String): Boolean =
        command(context) { sendCustomAction(id, null) }

    /**
     * 壁時計の時刻を `HH:mm` で。
     *
     * ⚠️ 同じ整形が音の面と音の板の二箇所に書いてあった。
     * **同じことを二度書けば、片方だけが古いままになる**ので、ここへ寄せた。
     * [clock] は長さ（経過・全体）、こちらは時刻——**別の量なので別の名にする**。
     */
    fun timeOfDay(at: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.JAPAN)
            .format(java.util.Date(at))

    /**
     * その席がまだ生きているか。
     *
     * ## ⚠️ 消えた席は、消えたことを自分から言わない
     *
     * アプリを閉じても再生の面が残っていた（本人の指摘・2026/9/2）。
     * 握っている `MediaController` は、席が消えた後も**最後の値を返し続ける**。
     * 読み直しても同じものが返るので、面は何も変わらない。
     *
     * 席の一覧に今も居るかを確かめる。**居なければ、読まない。**
     */
    fun alive(controller: MediaController): Boolean {
        val state = runCatching { controller.playbackState?.state }.getOrNull()
        // 何も鳴らしていない状態（`STATE_NONE`）は、席が畳まれた後の姿でもある。
        return state != null && state != PlaybackState.STATE_NONE
    }

}
