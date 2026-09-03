package com.yuma.observatorylauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.app.AlarmManager
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 起動可能なアプリ一件。
 * 同じパッケージが本体側とプライベートスペース側の双方に在りうるので、
 * 同一性は packageName ではなく (component, user) の対で持つ。
 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val component: ComponentName,
    val user: UserHandle,
    val isPrivate: Boolean,
    val category: Int = -1,
) {
    /** 一覧の key と宣言(layout.json)の照合に使う安定した識別子。 */
    val id: String get() = if (isPrivate) "private:$packageName" else packageName

    /**
     * 既定の群。規則表([Categories])が決め、宣言(groups)があればそちらが優先される。
     */
    val categoryLabel: String get() = Categories.of(this)
}

/** プライベートスペースの状態。存在しない端末では [exists] が false になる。 */
data class PrivateSpaceState(
    val exists: Boolean = false,
    val locked: Boolean = true,
    val user: UserHandle? = null,
)

/**
 * 端末のアプリ一覧を読み出す唯一の入口。
 * PackageManager ではなく LauncherApps を使う——本体以外のプロファイル
 * (プライベートスペース・仕事用)を扱えるのはこちらだけで、
 * ランチャーとして正しい土台はこちらにある。
 */
object AppRepository {

    private const val PRIVATE_PROFILE_TYPE = "android.os.usertype.profile.PRIVATE"

    @Volatile
    private var cache: List<AppEntry> = emptyList()

    @Volatile
    private var privateState: PrivateSpaceState = PrivateSpaceState()

    fun cached(): List<AppEntry> = cache

    fun privateSpace(): PrivateSpaceState = privateState

    /**
     * その package が**私的な側にしか居ない**か。
     *
     * ## なぜ「しか居ない」で判じるのか
     *
     * `MediaSession` は、どの利用者の席かを外へ教えない。
     * だから鳴っているものが私的な側のものかどうかは、直接には訊けない。
     *
     * 同じアプリが両側に入っていることはある（音楽アプリはまさにそう）。
     * その場合はどちらで鳴っているか分からないので、**私的とは決めつけない**
     * ——私的でないものを隠すより、判じられないものを普通に扱う方が、
     * 面としては素直である。
     *
     * 逆に**私的な側にしか無いアプリ**なら、鳴らしているのはそちらしかない。
     * これは確かなので、そこだけを私的として扱う。
     */
    fun isPrivateOnly(packageName: String): Boolean {
        if (packageName.isBlank()) return false
        val entries = cache.filter { it.packageName == packageName }
        if (entries.isEmpty()) return false
        return entries.all { it.isPrivate }
    }

    /**
     * 施錠状態だけを読み直す。一覧の作り直しより遥かに安いので、
     * ホームへ戻るたび・検索を開くたびに呼んでよい。
     * 施錠されていたなら、その場で手元の一覧からも取り除く。
     */
    fun refreshPrivateState(context: Context): Boolean {
        val user = privateState.user ?: return false
        val userManager = context.getSystemService(UserManager::class.java)
        val locked = runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(true)
        val changed = locked != privateState.locked
        privateState = privateState.copy(locked = locked)
        if (locked) {
            // 施錠されたら、その場で一覧から落とす。作り直しを待たない。
            cache = cache.filterNot { it.isPrivate }
        } else if (changed) {
            // **解錠されたら覚えているものを捨てる。**
            // 施錠中の一覧には私物が入っていないので、そのまま返すと
            // 解錠しても出てこない。像の控えは捨てなくてよい。
            cache = emptyList()
        }
        return changed
    }

    /**
     * 一覧の作り直し。ラベルの読み出しは端末内の全アプリ分の I/O を伴うので、
     * 呼び出しは必ずこの suspend 版を通し、主スレッドで走らせない。
     */
    suspend fun loadAsync(context: Context): List<AppEntry> =
        withContext(Dispatchers.Default) { load(context) }

    fun load(context: Context): List<AppEntry> {
        // **毎回、端末に訊き直す。**
        //
        // ここには一度「覚えているものが在るなら、そのまま返す」という近道を入れた。
        // 一覧を開くときの46msを削るためだったが、**それが施錠の取りこぼしを生んだ**——
        // 控えが居座ると、施錠されたプライベートスペースのアプリが一覧に残る。
        // 実機で残った（2026/8/31）。
        //
        // 施錠の判定はこの本体でしか行われない。**近道はその判定ごと飛ばす。**
        // 速さのために、出してはいけないものが出る余地を作ってはならない。
        // 露見しない作りは、表示側で弾くのではなく**持たないこと**で担保する、
        // というこの体系の約束（下の[private.locked]の扱い）と同じ理由である。
        //
        // 速さは別の手で取る。実際、一覧の重さの主因は作り直しではなく
        // 行ごとの操作列の事前生成だった（そちらは直してある）。
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val userManager = context.getSystemService(UserManager::class.java)
        val self = context.packageName

        val profiles: List<UserHandle> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                runCatching { launcherApps.profiles }.getOrNull() ?: listOf(Process.myUserHandle())
            } else {
                listOf(Process.myUserHandle())
            }

        var private = PrivateSpaceState()
        val entries = ArrayList<AppEntry>()

        profiles.forEach { user ->
            val isPrivate = isPrivateProfile(launcherApps, user)
            if (isPrivate) {
                val locked = runCatching { userManager.isQuietModeEnabled(user) }.getOrDefault(true)
                private = PrivateSpaceState(exists = true, locked = locked, user = user)
            }
            // 施錠中のプロファイルは getActivityList が空を返す。例外にはしない。
            val activities = runCatching { launcherApps.getActivityList(null, user) }
                .getOrDefault(emptyList())
            activities.forEach { info ->
                if (info.applicationInfo.packageName == self) return@forEach
                entries += AppEntry(
                    packageName = info.applicationInfo.packageName,
                    label = info.label.toString(),
                    component = info.componentName,
                    user = user,
                    isPrivate = isPrivate,
                    category = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        info.applicationInfo.category
                    } else {
                        -1
                    },
                )
            }
        }

        privateState = private
        // 施錠中の分は保持しない。表示側で弾く作りにすると、弾き漏らした経路が
        // そのまま露見に繋がる——持たないことで担保する。
        if (private.exists && private.locked) {
            entries.removeAll { it.isPrivate }
        }
        cache = entries
            .distinctBy { it.component.flattenToString() + "|" + it.user.hashCode() }
            .sortedBy { it.label.lowercase() }
        return cache
    }

    private fun isPrivateProfile(launcherApps: LauncherApps, user: UserHandle): Boolean {
        if (user == Process.myUserHandle()) return false
        if (Build.VERSION.SDK_INT < 35) return false
        return runCatching {
            launcherApps.getLauncherUserInfo(user)?.userType == PRIVATE_PROFILE_TYPE
        }.getOrDefault(false)
    }

    /**
     * 起動を横取りする関門。
     *
     * 摩擦（[Friction]）はここで掛かる。**起動の入口は三箇所ある**ので、
     * 三箇所すべてに同じ判断を書くのではなく、通り道の一点に置く
     * ——書き漏らした経路が素通りになるのを防ぐため。
     *
     * ⚠️ これは画面側が差し込む可変の状態である。普段なら避けたい形だが、
     * 起動の呼び出し口へ引数を足して回るより、
     * **通り道が一つであることを保つ方**を採った。
     * 真を返したら、起動は行われない（画面側が問いを出す）。
     */
    @Volatile
    var gate: ((Context, AppEntry) -> Boolean)? = null

    fun launch(context: Context, entry: AppEntry): Boolean {
        if (gate?.invoke(context, entry) == true) return false
        return launchNow(context, entry)
    }

    /** 関門を通さずに起動する。問いに答えた後は、こちらを通る。 */
    fun launchNow(context: Context, entry: AppEntry): Boolean = runCatching {
        context.getSystemService(LauncherApps::class.java)
            .startMainActivity(entry.component, entry.user, null, null)
        true
    }.getOrElse {
        // 端末側の事情で拒まれたときだけ従来の経路へ落とす(本体プロファイルのみ有効)。
        val intent = context.packageManager.getLaunchIntentForPackage(entry.packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.isSuccess
    }

    fun openAppInfo(context: Context, entry: AppEntry) {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        val opened = runCatching {
            launcherApps.startAppDetailsActivity(entry.component, entry.user, null, null)
            true
        }.getOrDefault(false)
        if (opened) return
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", entry.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    /**
     * プライベートスペースの施錠・解錠。解錠は端末の認証を伴い、OS側が画面を出す。
     * 権限が無い端末では設定画面へ委ねる(黙って何も起きない状態にしない)。
     */
    fun setPrivateSpaceLocked(context: Context, locked: Boolean) {
        val user = privateState.user ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            openPrivateSpaceSettings(context)
            return
        }
        val userManager = context.getSystemService(UserManager::class.java)
        val ok = runCatching { userManager.requestQuietModeEnabled(locked, user) }.isSuccess
        if (!ok) openPrivateSpaceSettings(context)
    }

    /**
     * プライベートスペースの設定画面へ送る。OSの提供物(Intent か IntentSender)が
     * 版によって異なるため、型を決め打ちせず受け取ってから分岐する。
     */
    fun openPrivateSpaceSettings(context: Context) {
        val provided: Any? = if (Build.VERSION.SDK_INT >= 36) {
            runCatching {
                context.getSystemService(LauncherApps::class.java).privateSpaceSettingsIntent
            }.getOrNull()
        } else {
            null
        }

        val opened = runCatching {
            when (provided) {
                is Intent -> {
                    context.startActivity(provided.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    true
                }

                is IntentSender -> {
                    context.startIntentSender(provided, null, 0, 0, 0)
                    true
                }

                else -> false
            }
        }.getOrDefault(false)
        if (opened) return

        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // 背景スレッドから触るので並行の器を使う。
    /** 時計アプリを開く。アラーム画面を持つ端末ではそちらへ入る。 */
    fun openClock(context: Context) {
        val candidates = listOf(
            Intent(AlarmClock.ACTION_SHOW_ALARMS),
            context.packageManager.getLaunchIntentForPackage("com.google.android.deskclock"),
        )
        candidates.filterNotNull().forEach { intent ->
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(intent) }.isSuccess) return
        }
    }

    /**
     * 暦のアプリを、指定の時刻を映した状態で開く。
     *
     * `content://com.android.calendar/time/<ミリ秒>` は
     * 「この日を出せ」という約束で、標準の暦アプリはこれを解する。
     * 解さないアプリのために**アプリそのものを開く二段目**を置く
     * ——押して何も起きないのが一番困る。
     *
     * ⚠️ この道は月の格子（`MonthTile`）にも書いてあったので、こちらへ寄せた。
     * **同じことを二度書けば、片方だけが古いままになる。**
     */
    fun openCalendar(context: Context, at: Long = System.currentTimeMillis()) {
        val uri = CalendarContract.CONTENT_URI.buildUpon()
            .appendPath("time")
            .appendPath(at.toString())
            .build()
        val opened = runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
        if (opened) return
        runCatching {
            context.packageManager.getLaunchIntentForPackage("com.google.android.calendar")
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { context.startActivity(it) }
        }
    }

    /**
     * 次に鳴るアラーム一件。OSが保持している値をそのまま読む(権限は要らない)。
     * 当日なら時刻だけ、翌日以降は曜日を添える——いつ鳴るかが分からないと意味が無い。
     */
    fun nextAlarm(context: Context): String? {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return null
        val info = runCatching { manager.nextAlarmClock }.getOrNull() ?: return null
        val at = Calendar.getInstance().apply { timeInMillis = info.triggerTime }
        val today = Calendar.getInstance()
        val sameDay = at.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            at.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        val pattern = if (sameDay) "HH:mm" else "EEE HH:mm"
        return SimpleDateFormat(pattern, Locale.JAPAN).format(at.time)
    }

    /**
     * アプリの出入りを見張る。**放送(ACTION_PACKAGE_*)ではなく LauncherApps の通知を使う**
     * ——放送は自分のプロファイル分しか届かず、プライベートスペース側の増減を取りこぼす。
     * ランチャーに用意された正規の口はこちら。
     */
    fun observePackages(context: Context, onChanged: () -> Unit): AutoCloseable {
        val launcherApps = context.getSystemService(LauncherApps::class.java)
        // アプリの出入りは[Usage]の覚え書きも古くする。ここで一緒に捨てる。
        val onChanged = { Usage.forget(); onChanged() }
        val callback = object : LauncherApps.Callback() {
            override fun onPackageRemoved(packageName: String?, user: UserHandle?) = onChanged()
            override fun onPackageAdded(packageName: String?, user: UserHandle?) = onChanged()
            override fun onPackageChanged(packageName: String?, user: UserHandle?) = onChanged()
            override fun onPackagesAvailable(
                names: Array<out String>?,
                user: UserHandle?,
                replacing: Boolean,
            ) = onChanged()

            override fun onPackagesUnavailable(
                names: Array<out String>?,
                user: UserHandle?,
                replacing: Boolean,
            ) = onChanged()

            override fun onShortcutsChanged(
                packageName: String,
                shortcuts: MutableList<android.content.pm.ShortcutInfo>,
                user: UserHandle,
            ) = onChanged()
        }
        runCatching { launcherApps.registerCallback(callback) }
        return AutoCloseable { runCatching { launcherApps.unregisterCallback(callback) } }
    }

    /** アプリが備える近道(長押しで出るあれ)。既定のホームだけが読める。 */
    data class AppShortcut(
        val id: String,
        val label: String,
        val packageName: String,
        val user: UserHandle,
    )

    /**
     * 近道の一覧。既定のホームでなければ取得できないので、例外は握って空を返す。
     * 出す数は絞る——長押しの操作列は「動かす・外す」も並ぶ面であり、占領させない。
     */
    suspend fun shortcuts(
        context: Context,
        packageName: String,
        user: UserHandle,
        limit: Int = 4,
    ): List<AppShortcut> =
        withContext(Dispatchers.Default) {
            runCatching {
                val launcherApps = context.getSystemService(LauncherApps::class.java)
                val query = LauncherApps.ShortcutQuery()
                    .setPackage(packageName)
                    .setQueryFlags(
                        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                            LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                    )
                launcherApps.getShortcuts(query, user)
                    .orEmpty()
                    .filter { it.isEnabled }
                    .sortedBy { it.rank }
                    .take(limit)
                    .map { info ->
                        AppShortcut(
                            id = info.id,
                            label = (info.shortLabel ?: info.longLabel ?: info.id).toString(),
                            packageName = info.getPackage(),
                            user = info.userHandle,
                        )
                    }
            }.getOrDefault(emptyList())
        }

    fun startShortcut(context: Context, shortcut: AppShortcut) {
        runCatching {
            context.getSystemService(LauncherApps::class.java)
                .startShortcut(shortcut.packageName, shortcut.id, null, null, shortcut.user)
        }
    }

    private val iconCache = ConcurrentHashMap<String, ImageBitmap>()

    private fun iconKey(entry: AppEntry, sizePx: Int): String =
        entry.component.flattenToShortString() + "|" + entry.user.hashCode() + "|" + sizePx

    /** 既に作ってあるものだけを返す。描画の最中に復号を始めないための入口。 */
    fun cachedIcon(entry: AppEntry, sizePx: Int): ImageBitmap? = iconCache[iconKey(entry, sizePx)]

    fun icon(context: Context, entry: AppEntry, sizePx: Int): ImageBitmap? {
        val key = iconKey(entry, sizePx)
        iconCache[key]?.let { return it }
        val made =
            runCatching {
                val launcherApps = context.getSystemService(LauncherApps::class.java)
                val activity = launcherApps.getActivityList(entry.packageName, entry.user)
                    .firstOrNull { it.componentName == entry.component }
                val density = context.resources.displayMetrics.densityDpi
                val drawable: Drawable = activity?.getBadgedIcon(density)
                    ?: context.packageManager.getApplicationIcon(entry.packageName)
                drawable.toImageBitmap(sizePx)
            }.getOrNull()
        if (made != null) iconCache[key] = made
        return made
    }

    /**
     * 一覧に出る分のアイコンを先に作っておく。
     * 弾いて流している最中に復号を始めると、その回数だけ描画が詰まる——
     * 面を開いた時点で背景に寄せてしまえば、走査中はキャッシュに当たるだけになる。
     */
    suspend fun warmIcons(context: Context, entries: List<AppEntry>, sizePx: Int) {
        withContext(Dispatchers.Default) {
            entries.forEach { entry ->
                if (!isActive) return@withContext
                if (cachedIcon(entry, sizePx) == null) icon(context, entry, sizePx)
            }
        }
    }

    /**
     * 一覧だけ作り直させる。**像は捨てない。**
     *
     * ⚠️ 以前は画面消灯・解錠・プロファイルの施錠でも[invalidate]を呼んでいた。
     * つまり**端末を持ち上げるたびに、復号済みの像を全部捨てていた**。
     * その直後に一覧を開くと百件余りをその場で復号することになり、
     * 「掃いた後から絵が遅れて出てくる」形で現れる。
     *
     * アプリの絵は画面が消えても変わらない。捨てる理由が無い。
     */
    fun invalidateList() {
        cache = emptyList()
    }

    /** アプリ自体が入れ替わったとき。**そのときだけ**像も捨てる。 */
    fun invalidate() {
        iconCache.clear()
        cache = emptyList()
    }

    private fun Drawable.toImageBitmap(sizePx: Int): ImageBitmap {
        if (this is BitmapDrawable && bitmap != null) {
            return Bitmap.createScaledBitmap(bitmap, sizePx, sizePx, true).asImageBitmap()
        }
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        setBounds(0, 0, sizePx, sizePx)
        draw(canvas)
        return bmp.asImageBitmap()
    }
}
