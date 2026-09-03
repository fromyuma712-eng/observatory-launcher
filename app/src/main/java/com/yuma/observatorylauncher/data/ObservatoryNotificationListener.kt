package com.yuma.observatorylauncher.data

import android.app.Notification
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * OSの「通知へのアクセス」を利用者が明示的に許可した場合だけ動く。
 *
 * **通知の本文はファイルへ保存しない。** ホーム表示に必要な現在値だけをメモリに持つ。
 *
 * ## 例外は一つだけ——聴いたものの記録
 *
 * `MediaSession` が持つ**媒体の情報**（題・演者・アプリ・時刻・長さ）だけは
 * [Listening] へ書き出す。通知の本文は書かない。
 * 守りたかったのは「通知に流れてくる私信が土に残らないこと」であって、
 * 「何を聴いたか」はそこに含まれない（本人承認・2026/9/1）。
 *
 * ここで記録するのは、**この面が開いていなくても鳴っているから**である。
 * 画面の側で拾うと、音の面を見ていた時間ぶんしか記録が残らない。
 */
class ObservatoryNotificationListener : NotificationListenerService() {

    private var watching: MediaController? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    private val playbackCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = capture(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            // 鳴り始めた瞬間も拾う。曲が変わらないまま再開したときが該当する。
            if (state?.state == PlaybackState.STATE_PLAYING) capture(watching?.metadata)
        }
        override fun onSessionDestroyed() = attach()
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { attach() }

    override fun onListenerConnected() {
        startWatchingMedia()
        refresh()
    }

    override fun onListenerDisconnected() {
        stopWatchingMedia()
    }

    private fun startWatchingMedia() {
        val manager = getSystemService(MediaSessionManager::class.java) ?: return
        val me = ComponentName(this, ObservatoryNotificationListener::class.java)
        runCatching { manager.addOnActiveSessionsChangedListener(sessionsChanged, me, handler) }
        attach()
    }

    private fun stopWatchingMedia() {
        val manager = getSystemService(MediaSessionManager::class.java)
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsChanged) }
        watching?.unregisterCallback(playbackCallback)
        watching = null
    }

    /** 今いちばん上の席へ乗り換える。席の順は platform が既に決めている。 */
    private fun attach() {
        val next = runCatching { Music.controller(this) }.getOrNull()
        if (next?.sessionToken == watching?.sessionToken) return
        watching?.unregisterCallback(playbackCallback)
        watching = next
        watching?.registerCallback(playbackCallback, handler)
        capture(next?.metadata)
    }

    /** 題と演者だけを取り、記録へ渡す。**通知の本文はここを通らない。** */
    private fun capture(metadata: MediaMetadata?) {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (title.isBlank()) return
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val app = watching?.packageName.orEmpty()
        val duration = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: -1L
        // 絵柄の色は**直に入っているときだけ**採る。
        // ⚠️ ここで在り処（URI）を辿って外へ出てはならない——
        // **見張りは軽くなければならない**し、曲が変わるたびに通信するのは筋が悪い。
        // 在り処しか無い曲は 0 のまま置き、面が絵を読み込んだときに書き戻す
        // （[Listening.paint]）。
        val tint = runCatching {
            val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            bitmap?.let { Artwork.dominant(it) } ?: 0
        }.getOrDefault(0)
        runCatching {
            Listening.record(
                this,
                Listening.Play(
                    at = System.currentTimeMillis(),
                    title = title,
                    artist = artist,
                    app = app,
                    durationMs = duration,
                    tint = tint,
                    // 私的な側にしか居ないアプリなら、鳴らしているのはそちらしかない。
                    hidden = AppRepository.isPrivateOnly(app),
                ),
            )
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = refresh()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = refresh()

    private fun refresh() {
        val active = runCatching { activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
            .filterNot { it.packageName == packageName }
        val media = active.firstOrNull { it.notification.category == Notification.CATEGORY_TRANSPORT }
        snapshot = NotificationSnapshot(
            count = active.size,
            mediaTitle = media?.notification?.extras
                ?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            mediaText = media?.notification?.extras
                ?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
        )
    }

    companion object {
        @Volatile
        var snapshot: NotificationSnapshot = NotificationSnapshot()
            private set
    }
}

data class NotificationSnapshot(
    val count: Int = 0,
    val mediaTitle: String = "",
    val mediaText: String = "",
)
