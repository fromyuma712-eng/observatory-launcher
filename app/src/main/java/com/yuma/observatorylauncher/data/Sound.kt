package com.yuma.observatorylauncher.data

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * 音の出口。
 *
 * **どこから鳴るか**は、音の面で最初に知りたいことである。
 * 出力先が分からないまま再生を押すのは、いつでも少し怖い
 * ——電車の中でスピーカーから鳴る、という失敗はそこから起きる。
 *
 * 追加の許可は要らない。`AudioManager` は誰でも問い合わせられる。
 */
internal object Sound {

    /** 今の出力先を人が読む形で。分からなければ空。 */
    fun route(context: Context): String {
        val manager = context.getSystemService(AudioManager::class.java) ?: return ""
        val devices = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        }.getOrNull().orEmpty()

        // **外に出ているものを優先する。** 端末のスピーカーは常に候補に居るので、
        // 先に見つけた方を採ると、イヤホンを挿していても「スピーカー」と出る。
        val external = devices.firstOrNull { it.type in EXTERNAL }
        val device = external ?: devices.firstOrNull { it.type in INTERNAL }
        val kind = when (device?.type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
            -> "Bluetooth"

            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            -> "有線"

            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            -> "USB"

            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "本体のスピーカー"
            else -> return ""
        }
        // 機器の名前が取れるなら添える。「Bluetooth」だけでは、どれに繋がっているか分からない。
        val name = device.productName?.toString()?.takeIf {
            it.isNotBlank() && !it.equals(android.os.Build.MODEL, ignoreCase = true)
        }
        return if (name == null) kind else "$kind  $name"
    }

    private val EXTERNAL = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
    )

    private val INTERNAL = setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

    /**
     * 音楽を再生するアプリのパッケージ名。
     *
     * ⚠️ **`ApplicationInfo.CATEGORY_AUDIO` では広すぎる。**
     * イヤホンの設定アプリやカラオケの予約アプリまで「音」に入り、
     * 音の面に並んでしまった（本人の指摘・2026/9/1）。
     *
     * ⚠️ **`CATEGORY_APP_MUSIC` だけでは狭すぎる。**
     * 実機ではそれを申告していたのは一つだけで、Apple Music が落ちた。
     *
     * そこで**役割で選ぶ**。音楽を鳴らし続けるアプリは
     * `FOREGROUND_SERVICE_MEDIA_PLAYBACK` を要る——画面を閉じても鳴らすには
     * それが要るからである。**何に関するアプリかではなく、何をするアプリかで選ぶ。**
     *
     * 実機での切れ味（2026/9/1）:
     * Apple Music ○ / YT Music ○ / Technics Audio Connect × / DOMリモコン × / カラオケ予約 ×
     */
    fun players(context: Context, candidates: List<String>): Set<String> {
        val packages = context.packageManager
        val declared = runCatching {
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN)
                .addCategory(android.content.Intent.CATEGORY_APP_MUSIC)
            packages.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toSet()
        }.getOrDefault(emptySet())

        val byRole = candidates.filter { name ->
            runCatching {
                packages.getPackageInfo(
                    name,
                    android.content.pm.PackageManager.GET_PERMISSIONS,
                ).requestedPermissions?.any { it == MEDIA_PLAYBACK } == true
            }.getOrDefault(false)
        }
        // ⚠️ **名乗らないアプリを取りこぼす。**
        // 上の二つは「自分は音楽アプリだ」と名乗っている印を見ているだけで、
        // 名乗らずに鳴らすアプリ（動画・ラジオ・語学など）は入らない。
        //
        // 実際に席を持って鳴らした実績があるなら、それが何よりの証拠である。
        // **名乗りより、やったことを採る。**
        val byHistory = runCatching {
            Listening.read(context).map { it.app }.filter { it.isNotBlank() }.toSet()
        }.getOrDefault(emptySet())

        return declared + byRole + byHistory
    }

    private const val MEDIA_PLAYBACK = "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"
}
