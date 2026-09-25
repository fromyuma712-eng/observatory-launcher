package com.yuma.observatorylauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.config.LayoutStore
import com.yuma.observatorylauncher.data.AppEntry
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.yuma.observatorylauncher.data.Usage
import com.yuma.observatorylauncher.data.Friction
import com.yuma.observatorylauncher.data.Restraint
import com.yuma.observatorylauncher.data.AppRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

private const val PINNED_SECTION = "常用"

/**
 * ランチャー全体の唯一の入口。状態(宣言・アプリ一覧・検索の開閉)はここだけが持ち、
 * 下位のComposableは値を受け取るだけにする。
 *
 * [homeEpoch] はホーム操作のたびに進む値。ここで検索を畳み、台帳＝基底状態へ戻す。
 */
@Composable
fun LauncherRoot(homeEpoch: Int) {
    val context = LocalContext.current
    val store = remember { LayoutStore(context) }
    var config by remember { mutableStateOf(store.load()) }
    var apps by remember { mutableStateOf(AppRepository.cached()) }
    var searching by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var generations by remember { mutableIntStateOf(store.generationCount()) }

    // アプリの導入・削除とプロファイルの施錠状態を購読する。
    // ホームは常駐するので、開き直しに頼らず自分で追随する。
    // 受信時に一覧を作り直すのは主スレッドを塞ぐので、印だけ進めて実作業は背景へ回す。
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                // 画面の消灯・解錠・施錠。**一覧だけ作り直し、像は残す。**
                AppRepository.invalidateList()
                reload += 1
            }
        }
        // アプリの出入りは LauncherApps の通知で受ける。
        // ACTION_PACKAGE_* の放送は自分のプロファイル分しか届かず、
        // プライベートスペース側の増減を取りこぼす。
        val packages = AppRepository.observePackages(context) {
            AppRepository.invalidate()
            reload += 1
        }
        // 画面が消えるとプライベートスペースは施錠される。その瞬間に読み直す。
        val profiles = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_PROFILE_ACCESSIBLE)
            addAction(Intent.ACTION_PROFILE_INACCESSIBLE)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(receiver, profiles)
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { packages.close() }
        }
    }

    // ── 摩擦 ────────────────────────────────────────────────
    //
    // 起動の入口は三箇所あるので、判断は[AppRepository.gate]という
    // **通り道の一点**に置く。ここでは「止めるかどうか」だけを決め、
    // 止めるなら面を出す。強さの計算は背景で行う（利用状況の読み出しを伴う）。
    var challenge by remember { mutableStateOf<PendingLaunch?>(null) }
    var pendingEntry by remember { mutableStateOf<AppEntry?>(null) }

    DisposableEffect(config.frictionApps) {
        AppRepository.gate = gate@{ _, entry ->
            if (entry.packageName !in config.frictionApps) return@gate false
            pendingEntry = entry
            true
        }
        onDispose { AppRepository.gate = null }
    }

    // 強さは端末への問い合わせを伴うので、掴んでから背景で決める。
    LaunchedEffect(pendingEntry) {
        val entry = pendingEntry ?: return@LaunchedEffect
        val recent = withContext(Dispatchers.Default) {
            Usage.recent(context, entry.packageName)
        }
        val since = recent.lastClosedAt?.let { System.currentTimeMillis() - it }
        val level = Friction.level(recent.usedMs, since)
        if (!level.stops) {
            // 掛からない段。**待たせない**——判断が「掛けない」なら、
            // 面を出さずにそのまま開く。
            AppRepository.launchNow(context, entry)
            pendingEntry = null
        } else {
            challenge = PendingLaunch(
                entry = entry,
                level = level,
                notice = Friction.notice(entry.label, recent.usedMs, since),
            )
        }
    }

    // ホーム操作で必ず基底へ戻す。
    //
    // ⚠️ **頁も戻す。** 面が三つになってから、計器盤や音の面に居るまま
    // ホームを押しても頁が残り、「ホームを押したのにホームでない」状態になっていた。
    // 基底とは、押せば必ずそこへ帰れる場所のことである。
    LaunchedEffect(homeEpoch) {
        if (homeEpoch > 0) searching = false
    }

    // 一覧の作成は常に背景で。印(reload)とホーム操作の双方を引き金にする。
    LaunchedEffect(reload, homeEpoch) {
        apps = AppRepository.loadAsync(context)
    }

    // **像はホームに居るうちに作っておく。**
    //
    // 以前は一覧を開いた瞬間に温め始めていた。つまり復号と、利用者が最初に
    // 掃く動作とが同時に走る。詰まってはいないのに**絵だけが後から追いつく**ので、
    // 動きが重く見える（本人の見立て・2026/8/31）。
    // ホームは常駐していて、開かれるまでの間はほぼ何もしていない。そこで済ませる。
    //
    // 出た直後の数枚と競らないよう、少しだけ待ってから始める。
    val warmDensity = LocalDensity.current
    val warmPx = remember(warmDensity, config.display.iconSize) {
        with(warmDensity) { config.display.iconSize.dp.roundToPx() }
    }
    LaunchedEffect(apps, warmPx, IconTone.mono) {
        if (apps.isEmpty()) return@LaunchedEffect
        delay(500)
        AppRepository.warmIcons(context, apps, warmPx, IconTone.mono)
    }

    // 控え。世代は端末の中にしか無く、端末を替えれば失われる。
    // 外へ一枚書き出せることが、この宣言を持ち物として扱う最低条件になる。
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(4000)
            notice = null
        }
    }

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val written = runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(store.exportText().toByteArray(Charsets.UTF_8))
            } ?: error("開けない")
        }.isSuccess
        notice = if (written) "控えを書き出しました" else "書き出せませんでした"
    }

    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        val restored = text?.let { store.importText(it) }
        if (restored == null) {
            // 読めない控えで上書きしない。今の宣言はそのまま残る。
            notice = "この控えは読めません（今の設定はそのままです）"
        } else {
            config = restored
            generations = store.generationCount()
            notice = "控えから戻しました（元に戻すで取り消せます）"
        }
    }

    val commit: (LayoutConfig) -> Unit = { next ->
        store.save(next)
        config = next
        generations = store.generationCount()
    }

    // §N-6 地層。訂正は消さずに積むので、巻き戻し自体も新しい世代として残る。
    val rollback: () -> Unit = {
        store.rollback()?.let { config = it }
        generations = store.generationCount()
    }

    // 像の色の宣言を、描く側の状態へ写す（[IconTone]）。
    LaunchedEffect(config.display.monoIcons) { IconTone.mono = config.display.monoIcons }

    Box(modifier = Modifier.fillMaxSize()) {
        HomeScreen(
            homeEpoch = homeEpoch,
            config = config,
            apps = apps,
            generations = generations,
            onOpenSearch = {
                // 開く直前に施錠状態を読み直す。施錠されていれば、その場で一覧から落ちる。
                AppRepository.refreshPrivateState(context)
                reload += 1
                typing = false
                searching = true
            },
            onStartTyping = {
                AppRepository.refreshPrivateState(context)
                reload += 1
                typing = true
                searching = true
            },
            onCommit = commit,
            onRollback = rollback,
            notice = notice,
            onNotice = { notice = it },
            onExport = {
                val stamp = SimpleDateFormat("yyyyMMdd", Locale.JAPAN).format(Date())
                exportBackup.launch("observatory-layout-$stamp.json")
            },
            onImport = { importBackup.launch(arrayOf("application/json", "text/plain", "*/*")) },
        )
        challenge?.let { pending ->
            ChallengePanel(
                label = pending.entry.label,
                notice = pending.notice,
                level = pending.level,
                onPass = {
                    AppRepository.launchNow(context, pending.entry)
                    challenge = null
                    pendingEntry = null
                },
                onGiveUp = {
                    // **開かなかったことを数える。** 我慢が記録に残らないなら、
                    // 我慢する理由がひとつ減る。
                    Restraint.avoided(context, pending.entry.packageName)
                    challenge = null
                    pendingEntry = null
                },
            )
        }
        CommandLine(
            visible = searching,
            typeOnOpen = typing,
            apps = apps,
            config = config,
            generations = generations,
            onDismiss = { searching = false },
            onPin = { entry -> commit(config.pinned(entry.id, PINNED_SECTION)) },
            onCommit = commit,
            onRollback = rollback,
            onProfileChanged = { reload += 1 },
        )
    }
}
