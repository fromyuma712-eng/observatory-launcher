package com.yuma.observatorylauncher.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.config.LayoutStore
import com.yuma.observatorylauncher.data.AppEntry
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.yuma.observatorylauncher.data.Usage
import com.yuma.observatorylauncher.data.Friction
import com.yuma.observatorylauncher.data.Restraint
import com.yuma.observatorylauncher.data.AppRepository
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens
import com.yuma.observatorylauncher.ui.theme.ObservatoryDotGrid
import com.yuma.observatorylauncher.ui.theme.ObservatoryTruss
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlin.math.ceil
import androidx.compose.ui.platform.LocalConfiguration
import android.appwidget.AppWidgetManager

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
    LaunchedEffect(apps, warmPx) {
        if (apps.isEmpty()) return@LaunchedEffect
        delay(500)
        AppRepository.warmIcons(context, apps, warmPx)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeScreen(
    config: LayoutConfig,
    apps: List<AppEntry>,
    generations: Int,
    onOpenSearch: () -> Unit,
    onCommit: (LayoutConfig) -> Unit,
    onRollback: () -> Unit,
    notice: String?,
    // 押しても何も起きなかったことを利用者へ伝える口。
    // 報せの実体は[LauncherRoot]が持つので、ここでは立てるだけにする。
    onNotice: (String) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    homeEpoch: Int,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val ui = rememberHomeEditingState()

    LaunchedEffect(config.pageCount) {
        ui.clampPage(config.pageCount)
    }

    // ⚠️ **ホーム操作では頁も基底へ戻す。**
    // 面が三つになってから、計器盤や音の面に居るままホームを押しても頁が残り、
    // 「ホームを押したのにホームでない」状態になっていた。
    // 基底とは、押せば必ずそこへ帰れる場所のことである。
    LaunchedEffect(homeEpoch) {
        if (homeEpoch > 0) ui.page = 0
    }

    // 開いたものは必ず閉じられること。戻る操作は、まず開いている操作列を畳み、
    // それも無ければ編集モードから出る。
    BackHandler(enabled = ui.editing) {
        if (ui.menusOpen) ui.clearMenus() else ui.editorMode = false
    }

    // 引いている最中に宣言が書き換わる。掴んだ瞬間の値を握り続けると
    // 二回目の入れ替えが元の並びに対して行われてしまうので、常に最新を読む。
    val liveConfig by rememberUpdatedState(config)
    val rowHeightPx = with(density) {
        maxOf(ObservatoryDimens.Row, config.display.iconSize.dp + 16.dp).toPx()
    }

    val iconPx = remember(density, config.display.iconSize) {
        with(density) { config.display.iconSize.dp.roundToPx() }
    }

    val widgetHost = rememberWidgetHost()
    val bindWidget = rememberWidgetBinder(widgetHost) { id ->
        ui.displayMenu = false
        ui.widgetPickerOpen = false
        onCommit(config.widgetAdded(id, page = ui.page))
    }

    var infoPermissionEpoch by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            // 通知・使用状況・Health Connectの設定画面から戻った直後に再読込する。
            if (event == Lifecycle.Event.ON_RESUME) infoPermissionEpoch += 1
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 二度断った許可は、以後 OS が画面を出さずに即座に拒否を返す。
    // 何も起きないように見えるので、**そのときは設定画面への道を出す**。
    val permissionResult: (Map<String, Boolean>) -> Unit = { granted ->
        infoPermissionEpoch += 1
        if (granted.isNotEmpty() && granted.values.none { it }) {
            onNotice(
                "許可されませんでした。二度断ると画面が出ません——" +
                    "長押しの「アプリ情報」から許可してください",
            )
        }
    }
    val requestInfoPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
        permissionResult,
    )
    val requestBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissionResult(mapOf(Manifest.permission.BLUETOOTH_CONNECT to granted)) }

    // Health Connect は**自前の許可画面**を持つ。Android の権限ダイアログではないので、
    // 通常の RequestPermission では出せない。専用の受け渡しを使う。
    val requestHealth = rememberLauncherForActivityResult(
        androidx.health.connect.client.PermissionController
            .createRequestPermissionResultContract(),
    ) { granted ->
        infoPermissionEpoch += 1
        if (!granted.containsAll(com.yuma.observatorylauncher.data.Health.PERMISSIONS)) {
            onNotice("歩数・睡眠・心拍のうち、許可されなかった種類があります")
        }
    }

    val requestInfoAccess: (com.yuma.observatorylauncher.data.InfoAccess) -> Unit = { access ->
        // 画面を開けたかどうかを必ず持ち帰る。
        // **押しても何も起きないのが最も分からない状態**なので、開けなければそう言う。
        fun open(intent: Intent): Boolean =
            runCatching { context.startActivity(intent) }.isSuccess

        val opened = when (access) {
            com.yuma.observatorylauncher.data.InfoAccess.NONE -> true
            com.yuma.observatorylauncher.data.InfoAccess.CALENDAR -> {
                requestInfoPermissions.launch(arrayOf(Manifest.permission.READ_CALENDAR))
                true
            }
            com.yuma.observatorylauncher.data.InfoAccess.LOCATION -> {
                requestInfoPermissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
                true
            }
            com.yuma.observatorylauncher.data.InfoAccess.NOTIFICATIONS ->
                open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))

            com.yuma.observatorylauncher.data.InfoAccess.USAGE ->
                open(
                    Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )

            com.yuma.observatorylauncher.data.InfoAccess.BLUETOOTH ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    requestBluetooth.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    true
                } else {
                    false
                }

            com.yuma.observatorylauncher.data.InfoAccess.HEALTH -> {
                // 以前はここで設定画面を開こうとしていたが、Health Connect の画面は
                // 入口(LAUNCHER)を持たず `<queries>` の宣言に掛からないため、
                // AppsFilter に遮られて**開けたつもりで何も起きなかった**。
                // 許可は Health Connect 自身の受け渡しで求める。
                requestHealth.launch(com.yuma.observatorylauncher.data.Health.PERMISSIONS)
                true
            }
        }
        if (!opened) onNotice("この端末では設定画面を開けませんでした")
    }

    // 宣言に在っても未導入のものは黙って落とす(端末を跨いでも同じ宣言が使える)。
    // プライベートスペースの分は台帳に載せない——施錠の意味が失われるため。
    val installed = remember(apps) { apps.filterNot { it.isPrivate }.associateBy { it.id } }
    val homeItems = remember(config.sites, installed) {
        buildMap<String, HomeItem> {
            installed.forEach { (id, entry) -> put(id, HomeItem.App(entry)) }
            config.sites.forEach { site -> put(site.id, HomeItem.Site(site)) }
        }
    }
    val sections = remember(config, homeItems, ui.page) {
        // 空の節も残す。足した節が見えないと中身を入れられない。
        config.sections
            .filter { it.page == ui.page }
            .map { s -> s.title to s.items.mapNotNull { homeItems[it] } }
    }
    // 同じ問いに答える札は、置く直前にひとつへ畳む（[InfoMerge]）。
    // 宣言は書き換えない——畳み方を変えれば、いつでも元の姿に戻る。
    val aboveInfoTiles = remember(config.infoTiles, ui.page, ui.editorMode) {
        InfoMerge.collapse(
            config.infoTiles.filter {
                it.page == ui.page && it.zone == LayoutConfig.ZONE_ABOVE
            },
            editing = ui.editorMode,
        )
    }
    val belowInfoTiles = remember(config.infoTiles, ui.page, ui.editorMode) {
        InfoMerge.collapse(
            config.infoTiles.filter {
                it.page == ui.page && it.zone == LayoutConfig.ZONE_BELOW
            },
            editing = ui.editorMode,
        )
    }
    // 通し番号は台帳全体で一続き。行の位置が身体に残るよう、節を跨いでも振り直さない。
    val numbers = remember(sections) {
        sections.flatMap { it.second }
            .mapIndexed { i, entry -> entry.id to i + 1 }
            .toMap()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 地。編集中はここを触れば畳む(逃げ道を常に面の上に置く)。
        ObservatoryDotGrid(
            modifier = Modifier
                .fillMaxSize()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { if (ui.menusOpen) ui.clearMenus() },
                    // 地の長押しで編集へ入る。Androidのホームで最も通りの良い作法で、
                    // 時計の操作列を知らなくても辿り着ける。
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        ui.clearMenus()
                        ui.editorMode = true
                    },
                )
        )

        // 下から上へ掃けば検索。台帳(LazyColumn)は縦の掃きを自分で消費してしまうので、
        // 素朴な pointerInput では親まで届かない。使い切られなかった分だけを
        // nestedScroll で受け取る——台帳がスクロールできる状態なら一覧の操作が優先され、
        // 行き止まり(または画面に収まっている)ときだけ検索が開く。
        val swipeThreshold = with(density) { 40.dp.toPx() }
        val pageThreshold = with(density) { 64.dp.toPx() }
        val liftToSearch = remember(swipeThreshold) {
            object : NestedScrollConnection {
                private var travel = 0f
                override fun onPostScroll(
                    consumed: Offset,
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (available.y > 0f) travel = 0f else travel += available.y
                    if (travel < -swipeThreshold) {
                        travel = 0f
                        onOpenSearch()
                    }
                    return Offset.Zero
                }
            }
        }

        // 編集中は縦の掃きで面を離れない。並べ替えの最中に一覧へ飛ばされると、
        // 下の方に置いたものへ手が届かなくなる。横のページ送りだけは残す。
        // 計器盤では掃き上げで一覧へ抜けない。
        //
        // あの面は**長く、下まで読む**ためのものなので、巻き終わりに達するたび
        // 一覧が開くと読むことができない。一覧へは最下端の検索行から入る。
        val leaveGestures = if (
            ui.editorMode ||
            ui.page == Dashboard.PAGE ||
            ui.page == MusicBoard.page(config.pageCount)
        ) {
            Modifier
        } else {
            Modifier.nestedScroll(liftToSearch)
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(leaveGestures)
                .pointerInput(config.pageCount, ui.page) {
                    var travel = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { travel = 0f },
                        onDragEnd = {
                            when {
                                // 右端は音の面。利用者の頁の一枚先に在る。
                                travel < -pageThreshold &&
                                    ui.page < MusicBoard.page(config.pageCount) ->
                                    ui.page += 1
                                // 計器盤は利用者の頁の**左**に在る。0 番に据えると
                                // 利用者の頁が全部ずれるので、負の側へ一枚だけ伸ばす。
                                travel > pageThreshold && ui.page > Dashboard.PAGE ->
                                    ui.page -= 1
                            }
                        },
                    ) { _, amount -> travel += amount }
                }
                .then(
                    if (
                        ui.editorMode ||
                        ui.page == Dashboard.PAGE ||
                        ui.page == MusicBoard.page(config.pageCount)
                    ) {
                        Modifier
                    } else {
                        Modifier.pointerInput(Unit) {
                            var travel = 0f
                            detectVerticalDragGestures(
                                onDragStart = { travel = 0f },
                                onDragEnd = { if (travel < -swipeThreshold) onOpenSearch() },
                            ) { _, amount -> travel += amount }
                        }
                    }
                )
                .pointerInput(Unit) {
                    // 何も置かれていない場所の長押しで編集へ入る。Androidのホームで
                    // 最も通りの良い作法で、時計の操作列を知らなくても辿り着ける。
                    // 行や見出しは自前の長押しを持つので、そちらが先に受け取る。
                    detectTapGestures(
                        onLongPress = {
                            // 計器盤は選ばない面なので、並べ替える対象が無い。
                            // 編集へ入れると空の編集面に落ちるだけになる。
                            if (ui.page != Dashboard.PAGE &&
                                ui.page != MusicBoard.page(config.pageCount)
                            ) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                ui.clearMenus()
                                ui.editorMode = true
                            }
                        },
                        onTap = { if (ui.menusOpen) ui.clearMenus() },
                    )
                }
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // 上部が起こしうることを一組にまとめて渡す。
            // 一つずつ引数で配っていた頃は21個まで膨れ、足すたびに三箇所を直していた。
            val commands = remember(config, ui.page, generations, ui.editorMode, ui.displayMenu) {
                HomeCommands(
                    openClock = { AppRepository.openClock(context) },
                    toggleEditor = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        ui.clearMenus()
                        ui.editorMode = !ui.editorMode
                    },
                    toggleMenu = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        ui.expandedItem = null
                        ui.sectionMenu = null
                        ui.displayMenu = !ui.displayMenu
                    },
                    cycleIconSize = {
                        val sizes = LayoutConfig.ICON_SIZES
                        val next = sizes[(sizes.indexOf(config.display.iconSize) + 1) % sizes.size]
                        onCommit(config.withIconSize(next))
                    },
                    toggleLabels = { onCommit(config.withLabels(!config.display.showLabels)) },
                    addInfo = {
                        ui.displayMenu = false
                        ui.infoPickerOpen = true
                        ui.editorMode = true
                    },
                    addWidget = {
                        ui.displayMenu = false
                        ui.widgetPickerOpen = true
                        ui.editorMode = true
                    },
                    addSite = {
                        ui.clearMenus()
                        ui.editingSiteId = null
                        ui.siteEditorOpen = true
                        ui.editorMode = true
                    },
                    addSection = {
                        val last =
                            config.sections.lastOrNull { it.page == ui.page }?.title ?: ""
                        onCommit(config.sectionAdded(last, ui.page))
                    },
                    addPage = {
                        val next = config.pageAdded()
                        onCommit(next)
                        ui.page = next.pageCount - 1
                    },
                    removePage = {
                        val page = ui.page
                        onCommit(config.pageRemoved(page))
                        ui.page = (page - 1).coerceAtLeast(0)
                    },
                    rollback = onRollback,
                    exportBackup = onExport,
                    importBackup = onImport,
                    close = ui::clearAll,
                )
            }

            // 置かれた物を動かす指示は、面ごとにばらさず一組で渡す。
            val tileCommands = remember(config) {
                TileCommands(
                    placement = PlacementCommands(
                        remove = { id -> onCommit(config.infoTileRemoved(id)) },
                        move = { id, delta -> onCommit(config.infoTileMoved(id, delta)) },
                        cycleSpan = { id -> onCommit(config.infoTileSpanCycled(id)) },
                        toggleZone = { id -> onCommit(config.infoTileZoneToggled(id)) },
                        movePage = { id, delta -> onCommit(config.infoTilePageMoved(id, delta)) },
                    ),
                    edit = { ui.editingInfoTile = it },
                    update = { onCommit(config.infoTileUpdated(it)) },
                    requestAccess = requestInfoAccess,
                )
            }
            // ウィジェットが申告する「これ未満だと壊れる幅」を、四分割の桁へ言い換える。
            // 申告を無視して狭い桁へ入れられると、置けるのに中身が潰れる——
            // **一番分かりにくい壊れ方**なので、選べないようにする。
            val widgetManager = remember { AppWidgetManager.getInstance(context) }
            val usableWidthDp = LocalConfiguration.current.screenWidthDp -
                (ObservatoryDimens.Gutter.value * 2).toInt()
            val minSpanOf: (Int) -> Int = { id ->
                val info = runCatching { widgetManager.getAppWidgetInfo(id) }.getOrNull()
                if (info == null || usableWidthDp <= 0) {
                    1
                } else {
                    val needDp = with(density) {
                        maxOf(info.minResizeWidth, info.minWidth).toDp().value
                    }
                    val column = usableWidthDp.toFloat() / LayoutConfig.WIDGET_MAX_SPAN
                    ceil(needDp / column).toInt().coerceIn(1, LayoutConfig.WIDGET_MAX_SPAN)
                }
            }
            val widgetCommands = remember(config, minSpanOf) {
                WidgetCommands(
                    placement = PlacementCommands(
                        remove = { id ->
                            widgetHost.deleteAppWidgetId(id)
                            onCommit(config.widgetRemoved(id))
                        },
                        move = { id, delta -> onCommit(config.widgetMoved(id, delta)) },
                        cycleSpan = { id -> onCommit(config.widgetSpanCycled(id, minSpanOf(id))) },
                        toggleZone = { id -> onCommit(config.widgetZoneToggled(id)) },
                        movePage = { id, delta -> onCommit(config.widgetPageMoved(id, delta)) },
                    ),
                    cycleHeight = { id -> onCommit(config.widgetHeightCycled(id)) },
                    setHeight = { id, dp -> onCommit(config.widgetHeightSet(id, dp)) },
                )
            }

            // 面ごとの枠の出し分け。
            //
            // - **ホームだけが入口を持つ。** 検索行も掃き上げも、ホームにあれば足りる。
            //   計器盤と音の面は読むための面なので、入口を置くと画面が削られるだけである。
            // - **音の面は一枚で完結させる。** 時計も置かない。
            //   一枚の絵として成り立つことを、この面の第一の条件にする（本人指示・2026/9/1）。
            val musicPage = MusicBoard.page(config.pageCount)
            val isMusic = ui.page == musicPage
            val isDashboard = ui.page == Dashboard.PAGE
            val showChrome = !isMusic && !isDashboard

            // 面の頭に、12% の燠火帯と名札を置く。
            // **三枚とも同じ位置・同じ幅**なので、どの面に居ても
            // 「同じ道具の中に居る」ことが形で分かる。
            val boardCount = musicPage - Dashboard.PAGE + 1
            BoardMasthead(
                name = when {
                    isDashboard -> "計器盤"
                    isMusic -> "音"
                    else -> "ホーム"
                },
                index = ui.page - Dashboard.PAGE + 1,
                count = boardCount,
                right = when {
                    isDashboard -> "選ばない"
                    isMusic -> "一枚"
                    else -> "選ぶ"
                },
            )

            if (!isMusic) {
                Header(
                    display = config.display,
                    editorMode = ui.editorMode,
                    menuOpen = ui.displayMenu,
                    generations = generations,
                    pageCount = config.pageCount,
                    notice = notice,
                    commands = commands,
                )
            }

            // 編集中は札や操作列で面が伸びる。時計と編集の卓は留めたまま、
            // それより下をまとめて送れるようにする(下の方へ手が届かない状態を作らない)。
            val editScroll = rememberScrollState()
            Column(
                modifier = if (ui.editorMode) {
                    Modifier.weight(1f).verticalScroll(editScroll)
                } else {
                    Modifier.weight(1f)
                }
            ) {
            if (ui.widgetPickerOpen) {
                WidgetProviderPicker(
                    onSelect = { info ->
                        ui.widgetPickerOpen = false
                        bindWidget(info)
                    },
                    onClose = { ui.widgetPickerOpen = false },
                )
            }
            if (ui.infoPickerOpen) {
                InfoTilePicker(
                    onSelect = { item ->
                        val tile = LayoutConfig.InfoTile(
                            id = "info:${UUID.randomUUID()}",
                            kind = item.kind,
                            page = ui.page,
                            zone = LayoutConfig.ZONE_ABOVE,
                            span = if (
                                item.kind in setOf(
                                    LayoutConfig.INFO_ALARM,
                                    LayoutConfig.INFO_BATTERY,
                                    LayoutConfig.INFO_DATE,
                                    LayoutConfig.INFO_SOUND,
                                )
                            ) 2 else 4,
                        )
                        ui.infoPickerOpen = false
                        if (item.needsEditor) {
                            ui.editingInfoTile = tile
                        } else {
                            onCommit(config.infoTileAdded(tile))
                        }
                    },
                    onClose = { ui.infoPickerOpen = false },
                )
            }
            ui.editingInfoTile?.let { tile ->
                InfoTileEditor(
                    tile = tile,
                    onSave = { edited ->
                        val next = if (config.infoTiles.any { it.id == edited.id }) {
                            config.infoTileUpdated(edited)
                        } else {
                            config.infoTileAdded(edited)
                        }
                        ui.editingInfoTile = null
                        onCommit(next)
                    },
                    onClose = { ui.editingInfoTile = null },
                )
            }
            if (ui.siteEditorOpen) {
                val existing = config.sites.firstOrNull { it.id == ui.editingSiteId }
                SiteEditorPanel(
                    existing = existing,
                    onSave = { title, location ->
                        val next = if (existing == null) {
                            val base = if (config.sections.none { it.page == ui.page }) {
                                config.sectionAdded("", ui.page)
                            } else {
                                config
                            }
                            base.siteAdded(
                                title = title,
                                location = location,
                                sectionTitle = base.sections.first {
                                    it.page == ui.page
                                }.title,
                            )
                        } else {
                            config.siteUpdated(existing.id, title, location)
                        }
                        ui.clearMenus()
                        onCommit(next)
                    },
                    onClose = ui::clearMenus,
                )
            }
            // **札と弦材と持ち物を、ひとまとめにして巻く。**
            //
            // ⚠️ 巻きは頁の列（＝掃き上げ＝検索の受け口と同じ節点）に置いても効かない。
            // 同じ節点に載る指の受け口は先に書いたものから順に見るので、
            // 順序を入れ替えても検索が先に取り、頁は一切巻けなかった（実機で二度確認）。
            // **子に置けば、親より先に受ける。**
            //
            // ⚠️ さらに、**巻くのは上側の札だけでは足りない。**
            // 上だけに重みを与えると、下側の札が自然な高さを先に取り、
            // 残りに上が押し込められる。月の格子が一週分に潰れた（実機・2026/9/1）。
            // 一つの巻きの中へ全部を入れて、はじめて上下が対等になる。
            //
            // 台帳のある頁は台帳自身が巻くので足さない
            // ——同じ向きの巻きを入れ子にすると、どちらが受けるかが不定になる。
            val pageScroll = rememberScrollState()
            Column(
                modifier = when {
                    // 音の面は**巻かない**。入る分しか置かないという制約が、
                    // この面の質を保っている（`MusicBoard` の解説を参照）。
                    isMusic -> Modifier.weight(1f)
                    !ui.editorMode && sections.isEmpty() ->
                        Modifier.weight(1f).verticalScroll(pageScroll)
                    else -> Modifier
                },
            ) {
            if (ui.page == Dashboard.PAGE) {
                // 計器盤は選ばない面なので、宣言を読まない。目録から自動で組む。
                DashboardPage(
                    pageCount = config.pageCount,
                    commands = tileCommands,
                    refreshEpoch = infoPermissionEpoch,
                    frictionApps = config.frictionApps,
                )
            } else if (ui.page == MusicBoard.page(config.pageCount)) {
                // 音の面。札の仕組みを通さず、音のためだけに手で組む。
                MusicBoardPage(display = config.display, refreshKey = infoPermissionEpoch)
            } else {
            InfoTilePanel(
                tiles = aboveInfoTiles,
                pageCount = config.pageCount,
                editing = ui.editorMode,
                commands = tileCommands,
                refreshEpoch = infoPermissionEpoch,
                frictionApps = config.frictionApps,
            )

            WidgetPanel(
                config = config,
                page = ui.page,
                zone = LayoutConfig.ZONE_ABOVE,
                host = widgetHost,
                editing = ui.editorMode,
                commands = widgetCommands,
                modifier = Modifier.padding(top = 8.dp),
            )

            // 弦材は「大きな区切り」ただ一箇所＝状態(時刻・予定・天気)と台帳の境目にのみ置く。
            // 節ごとに反復すると§N-7が禁じる「目盛りとの機械的な並置」になる。
            ObservatoryTruss(
                modifier = Modifier
                    .padding(horizontal = ObservatoryDimens.Gutter)
                    .padding(top = 14.dp)
            )

            InfoTilePanel(
                tiles = belowInfoTiles,
                pageCount = config.pageCount,
                editing = ui.editorMode,
                commands = tileCommands,
                refreshEpoch = infoPermissionEpoch,
                modifier = Modifier.padding(top = 8.dp),
            )

            // 弦材と台帳の間はもともと空いていた帯。ここにウィジェットを置く。
            WidgetPanel(
                config = config,
                page = ui.page,
                zone = LayoutConfig.ZONE_BELOW,
                host = widgetHost,
                editing = ui.editorMode,
                commands = widgetCommands,
                modifier = Modifier.padding(top = 8.dp),
            )

            }
            }

            // 台帳は下寄せ。行数が画面を越えたときだけ勝手にスクロールへ移り、
            // 最下端の検索行は常に指の届く位置に残る。
            // 台帳は LazyColumn ではなく素の列にする。
            // LazyColumn は与えられた高さを必ず埋めるので、台帳の上の空白まで
            // 一覧の領域になり、そこでの長押し(編集へ入る)や掃き上げ(検索)が
            // 一覧に吸われて親へ届かない。ホームの台帳は元来小さく、
            // 遅延生成の利点より入口が塞がらないことの方が重い。
            Box(
                modifier = if (ui.editorMode || sections.isEmpty()) {
                    // 台帳が無い頁で重みを持たせると、空の枠が残りの高さを全部取り、
                    // その上の札が押し出されて切れる。持たせない。
                    Modifier.fillMaxWidth()
                } else {
                    Modifier.fillMaxWidth().weight(1f)
                },
                contentAlignment = Alignment.BottomStart,
            ) {
                Column(
                    modifier = if (ui.editorMode) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    },
                ) {
                sections.forEach { (title, entries) ->
                    key(title) {
                        val isRow = config.sections.firstOrNull { it.title == title }?.row == true
                        GroupHeader(
                            title = title,
                            menuOpen = ui.sectionMenu == title,
                            renaming = ui.renaming == title,
                            onToggleMenu = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                ui.toggleSection(title)
                            },
                            onRename = { name ->
                                ui.clearMenus()
                                onCommit(config.renamedSection(title, name))
                            },
                            trailing = if (ui.editorMode) {
                                if (isRow) "横並び" else "縦一列"
                            } else {
                                null
                            },
                            actions = buildList {
                                add(LedgerAction("名前を変える") { ui.renaming = title })
                                add(
                                    LedgerAction(
                                        if (isRow) {
                                            "縦一列に戻す"
                                        } else {
                                            "この節のアプリを横並びにする"
                                        }
                                    ) { onCommit(config.sectionRowToggled(title)) }
                                )
                                add(LedgerAction("この下に節を追加") {
                                    ui.sectionMenu = null
                                    onCommit(config.sectionAdded(title))
                                })
                                add(LedgerAction("この節を削除") {
                                    ui.sectionMenu = null
                                    onCommit(config.sectionRemoved(title))
                                })
                                if (generations > 0) {
                                    add(LedgerAction("元に戻す($generations)") {
                                        ui.sectionMenu = null
                                        onRollback()
                                    })
                                }
                                add(LedgerAction("閉じる", ui::clearMenus))
                            },
                        )
                    }
                    val isRow = config.sections.firstOrNull { it.title == title }?.row == true
                    if (isRow) {
                        key("row:$title") {
                            IconCluster(
                                entries = entries.map { item ->
                                    key(item.id) {
                                        ClusterItem(
                                            key = item.id,
                                            label = item.label,
                                            icon = rememberHomeIcon(item, iconPx),
                                            fallbackLabel = if (item is HomeItem.Site) "WEB" else null,
                                            onMove = if (ui.editorMode) {
                                                { delta ->
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.LongPress
                                                    )
                                                    onCommit(
                                                        if (delta > 0) {
                                                            liveConfig.movedDown(item.id)
                                                        } else {
                                                            liveConfig.movedUp(item.id)
                                                        }
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                            onClick = {
                                                if (ui.editorMode) {
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.LongPress
                                                    )
                                                    ui.sectionMenu = null
                                                    ui.displayMenu = false
                                                    ui.choosing = null
                                                    ui.expandedItem =
                                                        if (ui.expandedItem == item.id) null else item.id
                                                } else if (ui.menusOpen) {
                                                    ui.clearMenus()
                                                } else {
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.LongPress
                                                    )
                                                    openHomeItem(context, item)
                                                }
                                            },
                                            onLongClick = {
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.LongPress
                                                )
                                                ui.toggleItem(item.id)
                                            },
                                        )
                                    }
                                },
                                display = config.display,
                            )
                        }
                        val open = entries.firstOrNull { it.id == ui.expandedItem }
                        if (open != null) {
                            key("rowact:$title") {
                                val openApp = open as? HomeItem.App
                                val openShortcuts = rememberShortcuts(
                                    packageName = openApp?.entry?.packageName,
                                    user = openApp?.entry?.user,
                                    enabled = !ui.editorMode,
                                )
                                ActionStrip(
                                    rowActions(
                                        config, open, ui.choosing, context,
                                        onCommit, ui::clearMenus,
                                        shortcuts = openShortcuts,
                                        onShortcut = { shortcut ->
                                            ui.clearMenus()
                                            AppRepository.startShortcut(context, shortcut)
                                        },
                                        onEditSite = { site ->
                                            ui.clearMenus()
                                            ui.editingSiteId = site.id
                                            ui.siteEditorOpen = true
                                            ui.editorMode = true
                                        },
                                    ) { ui.choosing = it }
                                )
                            }
                        }
                    } else {
                        entries.forEach { item ->
                            key(item.id) {
                                val number = numbers[item.id] ?: 0
                                // 編集中は長押ししてから引くと、行そのものが指について動く。
                                // 一行分を越えるたびに入れ替え、越えた分だけ見かけを戻す
                                // ——「上へ」「下へ」を何度も押さずに済む。
                                // アプリが備える近道(長押しで出るあれ)。編集中は出さない
                                // ——並べ替えの最中に別の面へ飛ぶ入口を混ぜない。
                                val app = item as? HomeItem.App
                                val shortcuts = rememberShortcuts(
                                    packageName = app?.entry?.packageName,
                                    user = app?.entry?.user,
                                    enabled = ui.expandedItem == item.id && !ui.editorMode,
                                )
                                var drag by remember(item.id) { mutableFloatStateOf(0f) }
                                val dragModifier = if (ui.editorMode) {
                                    Modifier
                                        .graphicsLayer { translationY = drag }
                                        .pointerInput(item.id) {
                                            detectDragGestures(
                                                onDragEnd = { drag = 0f },
                                                onDragCancel = { drag = 0f },
                                            ) { change, amount ->
                                                change.consume()
                                                drag += amount.y
                                                if (drag > rowHeightPx) {
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.LongPress
                                                    )
                                                    onCommit(liveConfig.movedDown(item.id))
                                                    drag -= rowHeightPx
                                                } else if (drag < -rowHeightPx) {
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.LongPress
                                                    )
                                                    onCommit(liveConfig.movedUp(item.id))
                                                    drag += rowHeightPx
                                                }
                                            }
                                        }
                                } else {
                                    Modifier
                                }
                                AppRow(
                                    dragModifier = dragModifier,
                                    label = item.label,
                                    icon = rememberHomeIcon(item, iconPx),
                                    fallbackLabel = if (item is HomeItem.Site) "WEB" else null,
                                    display = config.display,
                                    number = number,
                                    index = (number - 1).coerceAtLeast(0),
                                    highlighted = ui.expandedItem == item.id,
                                    expanded = ui.expandedItem == item.id,
                                    onClick = {
                                        if (ui.editorMode) {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            ui.toggleItem(item.id)
                                        } else if (ui.menusOpen) {
                                            ui.clearMenus()
                                        } else {
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                            openHomeItem(context, item)
                                        }
                                    },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        ui.toggleItem(item.id)
                                    },
                                    // 開いている行だけ組む。台帳は十数行だが、
                                    // 一覧と同じ理由で、描くもの以外は作らない。
                                    actions = if (ui.expandedItem != item.id) {
                                        emptyList()
                                    } else rowActions(
                                        config, item, ui.choosing, context, onCommit, ui::clearMenus,
                                        shortcuts = shortcuts,
                                        onShortcut = { shortcut ->
                                            ui.clearMenus()
                                            AppRepository.startShortcut(context, shortcut)
                                        },
                                        onEditSite = { site ->
                                            ui.clearMenus()
                                            ui.editingSiteId = site.id
                                            ui.siteEditorOpen = true
                                            ui.editorMode = true
                                        },
                                    ) { ui.choosing = it },
                                )
                            }
                        }
                    }
                }
                // **この頁に何も無いときだけ**出す。節が無いだけで出していたので、
                // 情報の札しか置いていない頁で「台帳は空」と言い続けていた。
                // 案内は行き先が要るときにだけ出るべきで、
                // 既に何かが見えている面で出すと、ただの誤りになる。
                // 計器盤は宣言を読まないので、宣言の上では常に空に見える。
                // だが面には情報が並んでいるし、そもそもアプリを足す場所ではない。
                val pageIsBare = ui.page != Dashboard.PAGE &&
                    ui.page != MusicBoard.page(config.pageCount) &&
                    sections.isEmpty() &&
                    aboveInfoTiles.isEmpty() &&
                    belowInfoTiles.isEmpty() &&
                    config.widgets.none { it.page == ui.page }
                if (pageIsBare) {
                    key("empty") {
                        ObservatoryText(
                            text = "台帳は空。検索からアプリを追加するか、編集からサイトを追加する。",
                            color = ObservatoryColors.Muted,
                            size = ObservatoryType.Body,
                            modifier = Modifier
                                .heightIn(min = ObservatoryDimens.Row)
                                .padding(horizontal = ObservatoryDimens.Gutter),
                        )
                    }
                }
            }
            }

            }

            // 節点は計器盤と音の面のぶんを足して数える。
            // 左端が計器盤、右端が音、その間が利用者の頁である。
            PageNavigator(
                page = ui.page - Dashboard.PAGE,
                count = MusicBoard.page(config.pageCount) - Dashboard.PAGE + 1,
                onPrevious = { if (ui.page > Dashboard.PAGE) ui.page -= 1 },
                onNext = {
                    if (ui.page < MusicBoard.page(config.pageCount)) ui.page += 1
                },
            )
            // 検索行はホームだけ。読むための面から画面を削らない。
            if (showChrome) CommandBar(onOpenSearch = onOpenSearch)
        }
    }
}
