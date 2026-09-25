package com.yuma.observatorylauncher.data

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class InfoValue(
    val headline: String,
    val lines: List<String> = emptyList(),
    val access: InfoAccess = InfoAccess.NONE,
)

enum class InfoAccess {
    NONE,
    CALENDAR,
    LOCATION,
    NOTIFICATIONS,
    USAGE,
    BLUETOOTH,
    HEALTH,
}

/** 端末内だけで完結する情報源。個人情報をファイルや外部へ送らない。 */
object InfoSources {

    fun battery(context: Context, detailed: Boolean): InfoValue {
        val state = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = state?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = state?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = state?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val stateLabel = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充電中"
            BatteryManager.BATTERY_STATUS_FULL -> "充電完了"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放電中"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "給電中"
            else -> "状態不明"
        }
        if (!detailed) {
            return InfoValue(
                headline = if (percent >= 0) "バッテリー $percent%" else "バッテリー取得不可",
                lines = listOf(stateLabel),
            )
        }
        val temperature = state?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it >= 0 }?.let { "温度 %.1f℃".format(it / 10.0) }
        val manager = context.getSystemService(BatteryManager::class.java)
        val remaining = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager?.computeChargeTimeRemaining()?.takeIf { it > 0 }
        } else {
            null
        }
        return InfoValue(
            headline = if (percent >= 0) "$percent%  $stateLabel" else stateLabel,
            lines = buildList {
                temperature?.let(::add)
                remaining?.let {
                    add("充電完了まで約${TimeUnit.MILLISECONDS.toMinutes(it)}分")
                }
            },
        )
    }

    fun device(context: Context): InfoValue {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
        val network = when {
            capabilities == null -> "オフライン"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "モバイル通信"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "有線通信"
            else -> "接続中"
        }
        val airplane = runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON) == 1
        }.getOrDefault(false)
        return InfoValue(
            headline = network,
            // **普段どおりの値は出さない**——「機内モード：切」は何も答えない。入っているときだけ書く。
            // 空き容量は計器盤の「容量」の札と重なるので、ここでは書かない（2026-09-25）。
            lines = listOfNotNull(
                if (airplane) "機内モード：入" else null,
            ),
        )
    }

    /**
     * 空き容量を含めない端末状態。
     *
     * 計器盤には保存領域の札が別に在り、そちらは空き・全体・内訳まで出す。
     * **同じ値を二度書くと、どちらを見ればよいのか分からなくなる。**
     */
    fun deviceBrief(context: Context): InfoValue {
        val full = device(context)
        return full.copy(lines = full.lines.filterNot { it.startsWith("空き容量") })
    }

    /**
     * 日付から「9月1日 火曜日」を抜いたもの。
     *
     * 面の一番上に時計と日付が常に出ている。**同じ日付を二度書く必要は無い。**
     * 週番号と年内日数は時計が持たないので、そちらだけを残す。
     */
    fun dateBrief(now: Long = System.currentTimeMillis()): InfoValue {
        val full = dateFacts(now)
        val rest = full.lines
        return if (rest.isEmpty()) full else InfoValue(rest.first(), rest.drop(1))
    }

    fun dateFacts(now: Long = System.currentTimeMillis()): InfoValue {
        val calendar = Calendar.getInstance(Locale.JAPAN).apply { timeInMillis = now }
        val week = calendar.get(Calendar.WEEK_OF_YEAR)
        val day = calendar.get(Calendar.DAY_OF_YEAR)
        return InfoValue(
            headline = SimpleDateFormat("M月d日 EEEE", Locale.JAPAN).format(Date(now)),
            lines = listOf("第${week}週", "今年の${day}日目"),
        )
    }

    fun sound(context: Context): InfoValue {
        val audio = context.getSystemService(AudioManager::class.java)
        val ringer = when (audio?.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "サイレント"
            AudioManager.RINGER_MODE_VIBRATE -> "バイブレーション"
            AudioManager.RINGER_MODE_NORMAL -> "着信音あり"
            else -> "状態不明"
        }
        val notification = context.getSystemService(NotificationManager::class.java)
        val interruption = when (notification?.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_NONE -> "通知：遮断"
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> "通知：アラームのみ"
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "通知：優先のみ"
            else -> "通知：通常"
        }
        // 通知が普段どおり（通常）なら書かない。絞っているときだけ書く（2026-09-25）。
        return InfoValue(ringer, listOfNotNull(interruption.takeIf { it != "通知：通常" }))
    }

    fun notifications(context: Context): InfoValue {
        if (!hasNotificationAccess(context)) {
            return InfoValue("通知へのアクセスが必要", access = InfoAccess.NOTIFICATIONS)
        }
        val snapshot = ObservatoryNotificationListener.snapshot
        return InfoValue("未読通知 ${snapshot.count}件")
    }

    fun media(context: Context): InfoValue {
        if (!hasNotificationAccess(context)) {
            return InfoValue("再生情報には通知へのアクセスが必要", access = InfoAccess.NOTIFICATIONS)
        }
        val snapshot = ObservatoryNotificationListener.snapshot
        if (snapshot.mediaTitle.isBlank() && snapshot.mediaText.isBlank()) {
            return InfoValue("再生中のメディアなし")
        }
        return InfoValue(
            headline = snapshot.mediaTitle.ifBlank { "再生中" },
            lines = listOfNotNull(snapshot.mediaText.takeIf { it.isNotBlank() }),
        )
    }

    fun usage(context: Context): InfoValue {
        if (!hasUsageAccess(context)) {
            return InfoValue("使用状況へのアクセスが必要", access = InfoAccess.USAGE)
        }
        // 集計済みの桶ではなく出来事から数える。理由は[Usage]に記した——
        // 桶は書き出しが遅れ、昨日の値のまま返ることがある。
        val today = Usage.today(context) ?: return InfoValue("使用状況を読めません")
        val topLabel = today.topPackage?.takeIf { today.topTime > 0 }?.let { pkg ->
            runCatching {
                val app = context.packageManager.getApplicationInfo(pkg, 0)
                context.packageManager.getApplicationLabel(app).toString()
            }.getOrNull()
        }
        return InfoValue(
            headline = "今日 ${formatDuration(today.total)}",
            lines = listOfNotNull(topLabel?.let { "最長 $it" }),
        )
    }

    fun bluetooth(context: Context): InfoValue {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return InfoValue("Bluetoothの許可が必要", access = InfoAccess.BLUETOOTH)
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return InfoValue("Bluetooth非対応")
        return InfoValue(
            headline = if (adapter.isEnabled) "Bluetooth：入" else "Bluetooth：切",
            // ⚠️ 「登録済み 6台」だけを出していたので、**6台が繋がっていると読めた**
            // （外部評価・2026/9/2）。日常で要るのは登録数ではなく、
            // **今どこから音が出ているか**である。
            lines = listOf(
                (Sound.route(context).takeIf { it.isNotBlank() } ?: "接続なし") +
                    "  ∧  登録 ${adapter.bondedDevices.size}台",
            ),
        )
    }

    /**
     * 歩数・睡眠・心拍。読めない理由は**利用者が動かせる形**で返す。
     *
     * 「使えない」と「許可されていない」は別物である。前者は端末の事情で
     * 押しても何も変わらないが、後者は押せば変わる。同じ文言にしてはならない。
     */
    suspend fun health(context: Context): InfoValue {
        when (Health.availability(context)) {
            Health.Availability.UNAVAILABLE ->
                return InfoValue("Health Connectを利用できません")

            Health.Availability.UPDATE_REQUIRED ->
                return InfoValue("Health Connectの更新が必要")

            Health.Availability.READY -> Unit
        }
        if (!Health.granted(context).containsAll(Health.PERMISSIONS)) {
            return InfoValue("歩数・睡眠・心拍の許可が必要", access = InfoAccess.HEALTH)
        }
        val reading = Health.today(context)
            ?: return InfoValue("健康記録を読めません", access = InfoAccess.HEALTH)
        if (reading.isEmpty) {
            // 許可はあるが、書き込む側が何も置いていない。
            // 「許可が必要」に戻すと、押しても直らない堂々巡りになる。
            return InfoValue("今日の記録はまだありません")
        }
        return InfoValue(
            headline = reading.steps?.let { "%,d歩".format(it) } ?: "歩数の記録なし",
            lines = buildList {
                reading.sleep?.let {
                    add("睡眠 ${it.toHours()}時間${it.toMinutes() % 60}分")
                }
                reading.heartRate?.let { add("心拍 ${it}bpm") }
            },
        )
    }

    fun hasNotificationAccess(context: Context): Boolean {
        val component = ComponentName(context, ObservatoryNotificationListener::class.java)
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == component }
    }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun formatDuration(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val hours = minutes / 60
        val rest = minutes % 60
        return if (hours > 0) "${hours}時間${rest}分" else "${rest}分"
    }
}
