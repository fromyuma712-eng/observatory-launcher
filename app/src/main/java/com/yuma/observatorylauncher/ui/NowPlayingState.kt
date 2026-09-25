package com.yuma.observatorylauncher.ui

import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.yuma.observatorylauncher.data.Music
import com.yuma.observatorylauncher.data.ObservatoryNotificationListener
import kotlinx.coroutines.delay

/**
 * 今鳴っているものを、**生きたまま**持つ。
 *
 * ## ⚠️ なぜ括り出したか
 *
 * この仕掛けは長く `MusicTile` の中だけに書いてあった。
 * 音の面に新しい大きな表示（[NowPlayingPanel]）を作ったとき、そちらは
 * `produceState` で一度読むだけだったので、**針が動かず、
 * 再生と停止の印も変わらなかった**（実機・2026/9/1）。
 *
 * 同じことを二度書けば、片方だけが古いままになる。
 * **生きた値の作り方は一箇所に置き、見せ方だけを面ごとに変える。**
 *
 * ## 仕掛け
 *
 * - 席の出入りは `addOnActiveSessionsChangedListener`、
 *   席の中の変化は `MediaController.Callback` が知らせてくる。
 *   **問い合わせ続けない**——押してから絵が変わるまでの間が空く。
 * - 針は手元で進める。位置は「最後に知らせてきた時刻＋経過」で出せるので、
 *   席へ訊き直す必要が無い。鳴っている間だけ、しかも面が見えている間だけ動かす。
 * - 押した直後は**見かけを先に返す**（[NowPlaying.toggle]）。
 *   本物の知らせが来たら捨てる。これが無いと、指を離してから絵が変わるまでの間が空く。
 */
internal class NowPlaying(
    val current: Music.Playing?,
    /**
     * 席から知らせが来るたびに増える番号。
     *
     * ⚠️ **題と演者だけを合図にすると、絵柄を取り逃がす。**
     * `MediaSession` は一曲ぶんの情報を**二度に分けて**渡してくることがある
     * ——先に題と演者、少し遅れて絵の在り処。
     * 題で合図を取っていると二度目の知らせで何も動かず、
     * **絵の無いまま次の曲まで居座る**（実機・2026/9/1）。
     *
     * 「中身が変わった」ことそのものを番号で持ち、これを合図に取り直す。
     */
    val revision: Int,
    val toggle: () -> Unit,
    val previous: () -> Unit,
    val next: () -> Unit,
    /** 針を掴めるか。**席が名乗っていなければ掴ませない。** */
    val canSeek: Boolean,
    /** 掴んで放したときの送り先。 */
    val seekTo: (Long) -> Unit,
    /** 席自身が出している操作（評価・シャッフル・リピート等）。 */
    val marks: List<Music.Mark>,
    val sendMark: (String) -> Unit,
)

@Composable
internal fun rememberNowPlaying(refreshKey: Int = 0): NowPlaying {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current

    var seat by remember { mutableStateOf<MediaController?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var pretend by remember { mutableStateOf<Boolean?>(null) }
    // 席を取り直す手を、購読の外からも呼べるように持っておく。
    var attachRef by remember { mutableStateOf<(() -> Unit)?>(null) }

    // ⚠️ **面へ戻るたびに席を取り直す。**
    //
    // アプリを閉じても再生の面が残っていた（本人の指摘・2026/9/2）。
    // 席の出入りは `addOnActiveSessionsChangedListener` が知らせてくるが、
    // **面が止まっている間の知らせは受け取れない**——
    // 戻ってきたとき、既に居ない席を握ったままになる。
    //
    // 入り組んだ回復を書かず、単純に**組み直す**。購読の付け外しは安い。
    var resumes by remember { mutableIntStateOf(0) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { resumes++ }
    }

    DisposableEffect(owner, refreshKey, resumes) {
        val handler = Handler(Looper.getMainLooper())
        val manager = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, ObservatoryNotificationListener::class.java)

        var watching: MediaController? = null
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                pretend = null
                revision++
            }

            override fun onMetadataChanged(metadata: MediaMetadata?) {
                pretend = null
                revision++
            }

            override fun onSessionDestroyed() {
                pretend = null
                // ⚠️ **番号を進めるだけでは足りない。**
                // 握っている席は消えた席のままなので、読み直しても同じ値が返る。
                // **席そのものを取り直す。**
                attachRef?.invoke()
                revision++
            }
        }

        fun attach() {
            val next = Music.controller(context)
            if (next?.sessionToken == watching?.sessionToken && next != null) return
            watching?.unregisterCallback(callback)
            watching = next
            watching?.registerCallback(callback, handler)
            seat = next
            pretend = null
            revision++
        }

        attachRef = { attach() }
        val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { attach() }
        runCatching {
            manager?.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler)
        }
        attach()

        onDispose {
            attachRef = null
            runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
            watching?.unregisterCallback(callback)
        }
    }

    var tick by remember { mutableIntStateOf(0) }
    val live = seat?.playbackState?.state == PlaybackState.STATE_PLAYING
    LaunchedEffect(owner, live, revision) {
        if (!live) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(500L)
                tick++
            }
        }
    }

    val playing = remember(seat, revision, tick, pretend) {
        seat?.takeIf { Music.alive(it) }
            ?.let { Music.read(context, it) }
            ?.let { if (pretend != null) it.copy(playing = pretend!!) else it }
    }

    val controls = remember(seat, revision) {
        seat?.let { Music.canSeek(it) to Music.marks(it) }
    }

    return NowPlaying(
        current = playing,
        revision = revision,
        toggle = {
            pretend = !(playing?.playing ?: false)
            Music.toggle(context)
        },
        previous = { Music.previous(context) },
        next = { Music.next(context) },
        canSeek = controls?.first ?: false,
        seekTo = { Music.seekTo(context, it) },
        marks = controls?.second.orEmpty(),
        // 席の操作は**見かけを先に返さない。**
        // 繰り返しは三段（切・全曲・1曲）で、回し方はアプリが決めており、
        // **次に何になるかをこちらが知らない**以上、先回りして描けば嘘になる。
        sendMark = { Music.sendCustom(context, it) },
    )
}
