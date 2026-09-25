package com.yuma.observatorylauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import kotlin.math.abs
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.data.AppEntry
import com.yuma.observatorylauncher.data.AppRepository
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens
import com.yuma.observatorylauncher.ui.theme.ObservatoryDotGrid
import com.yuma.observatorylauncher.ui.theme.ObservatoryTruss
import java.util.UUID
import kotlin.math.ceil
import android.appwidget.AppWidgetManager

/**
 * ホーム（三面）の組み立て。入口と状態は [LauncherRoot]（LauncherRoot.kt）、
 * アプリの台帳は [HomeLedger]（HomeLedger.kt）に分けてある（2026-09-25）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeScreen(
    config: LayoutConfig,
    apps: List<AppEntry>,
    generations: Int,
    onOpenSearch: () -> Unit,
    onStartTyping: () -> Unit,
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
    // 知らせの残っているアプリ（像の右肩の印）。
    val badges by rememberBadged()

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

        // ## 面は指に追従して滑る（2026-09-25）
        //
        // 以前は指を離した瞬間に面が入れ替わっていた（動きは「rise」と標識灯の二つで打ち止め、
        // という約束があった）。本人の判断で約束を解いた——
        // > 「スマホは動いてなんぼ快適でなんぼのもんなので制限を解除します」
        //
        // 指に面が付いてくるので、**どこまで引けば移るか**が見て分かり、途中で引き返せる。
        // 隣の面は先に組んでおく（beyondViewportPageCount）——掃いた瞬間に組み始めると、
        // 計器盤のような重い面で最初の数コマが落ちる。
        val pageTotal = MusicBoard.page(config.pageCount) - Dashboard.PAGE + 1
        val pager = rememberPagerState(initialPage = ui.page - Dashboard.PAGE) { pageTotal }
        // 止まった面を「今の面」とする。移った手応えを指へ返す。
        LaunchedEffect(pager) {
            snapshotFlow { pager.settledPage }.collect { settled ->
                val next = settled + Dashboard.PAGE
                if (ui.page != next) {
                    ui.page = next
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                }
            }
        }
        // 外から面が指定されたら（ホームキー・頁の印・頁の追加）、そこへ滑らせる。
        LaunchedEffect(ui.page, pageTotal) {
            val target = (ui.page - Dashboard.PAGE).coerceIn(0, pageTotal - 1)
            if (pager.currentPage != target && !pager.isScrollInProgress) {
                pager.animateScrollToPage(target)
            }
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            beyondViewportPageCount = 1,
        ) { index ->
            // この面の番号。**面の中では `ui.page`（止まっている面）ではなくこれを読む**
            // ——滑っている間は二枚が同時に見えており、両方が自分の番号で描かねばならない。
            val page = index + Dashboard.PAGE
            // この面が画面に見えているか（[LocalPageVisible]）。少しでも覗けば「見えている」。
            val visible by remember(index) {
                derivedStateOf { abs(pager.currentPage + pager.currentPageOffsetFraction - index) < 0.999f }
            }
            CompositionLocalProvider(LocalPageVisible provides visible) {
            val sections = remember(config, homeItems, page) {
                // 空の節も残す。足した節が見えないと中身を入れられない。
                config.sections
                    .filter { it.page == page }
                    .map { s -> s.title to s.items.mapNotNull { homeItems[it] } }
            }
            // 同じ問いに答える札は、置く直前にひとつへ畳む（[InfoMerge]）。
            // 宣言は書き換えない——畳み方を変えれば、いつでも元の姿に戻る。
            // この面にホームの計器を出すか（[HomeInstrument]）。利用者の頁で、編集中でないとき。
            // 編集中は札を全部見せる——計器が受け持つ札も、並べ替え・削除できなければならない。
            val instrumentHere = page != Dashboard.PAGE &&
                page != MusicBoard.page(config.pageCount) &&
                !ui.editorMode
            val aboveInfoTiles = remember(config.infoTiles, page, ui.editorMode, instrumentHere) {
                InfoMerge.collapse(
                    config.infoTiles.filter {
                        it.page == page && it.zone == LayoutConfig.ZONE_ABOVE &&
                            !(instrumentHere && it.kind in HomeInstrument.COVERS)
                    },
                    editing = ui.editorMode,
                )
            }
            val belowInfoTiles = remember(config.infoTiles, page, ui.editorMode, instrumentHere) {
                InfoMerge.collapse(
                    config.infoTiles.filter {
                        it.page == page && it.zone == LayoutConfig.ZONE_BELOW &&
                            !(instrumentHere && it.kind in HomeInstrument.COVERS)
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

            // 編集中は縦の掃きで面を離れない。並べ替えの最中に一覧へ飛ばされると、
            // 下の方に置いたものへ手が届かなくなる。横のページ送りだけは残す。
            // 計器盤では掃き上げで一覧へ抜けない。
            //
            // あの面は**長く、下まで読む**ためのものなので、巻き終わりに達するたび
            // 一覧が開くと読むことができない。一覧へは最下端の検索行から入る。
            val leaveGestures = if (
                ui.editorMode ||
                page == Dashboard.PAGE ||
                page == MusicBoard.page(config.pageCount)
            ) {
                Modifier
            } else {
                Modifier.nestedScroll(liftToSearch)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(leaveGestures)
                    .then(
                        if (
                            ui.editorMode ||
                            page == Dashboard.PAGE ||
                            page == MusicBoard.page(config.pageCount)
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
                    .pointerInput(page) {
                        // 何も置かれていない場所の長押しで編集へ入る。Androidのホームで
                        // 最も通りの良い作法で、時計の操作列を知らなくても辿り着ける。
                        // 行や見出しは自前の長押しを持つので、そちらが先に受け取る。
                        detectTapGestures(
                            onLongPress = {
                                // 計器盤は選ばない面なので、並べ替える対象が無い。
                                // 編集へ入れると空の編集面に落ちるだけになる。
                                if (page != Dashboard.PAGE &&
                                    page != MusicBoard.page(config.pageCount)
                                ) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    ui.clearMenus()
                                    ui.editorMode = true
                                }
                            },
                            onTap = { if (ui.menusOpen) ui.clearMenus() },
                        )
                    }
            ) {
                // 上部が起こしうることを一組にまとめて渡す。
                // 一つずつ引数で配っていた頃は21個まで膨れ、足すたびに三箇所を直していた。
                val commands = remember(config, page, generations, ui.editorMode, ui.displayMenu) {
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
                        toggleIconTone = { onCommit(config.withMonoIcons(!config.display.monoIcons)) },
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
                                config.sections.lastOrNull { it.page == page }?.title ?: ""
                            onCommit(config.sectionAdded(last, page))
                        },
                        addPage = {
                            val next = config.pageAdded()
                            onCommit(next)
                            ui.page = next.pageCount - 1
                        },
                        removePage = {
                            val removed = page
                            onCommit(config.pageRemoved(removed))
                            ui.page = (removed - 1).coerceAtLeast(0)
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
                // 横向きか。**幅が高さを上回れば横**とみなす——向きの設定値ではなく実寸で見るので、
                // 分割画面で縦長の窓に入ったときも正しく縦の組み方になる。
                val landscape = isLandscape()
                // 横向きで使う巻き。頁を移ったら頭から読めるよう、頁ごとに作り直す。
                val landscapeScroll = remember(landscape) { ScrollState(0) }
                // 巻きの余りを親へ渡さない（行き止まりで検索が開かないように）。
                val keepScrollHere = remember {
                    object : NestedScrollConnection {
                        override fun onPostScroll(
                            consumed: Offset,
                            available: Offset,
                            source: NestedScrollSource,
                        ): Offset = available

                        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                            available
                    }
                }

                // ウィジェットが申告する「これ未満だと壊れる幅」を、四分割の桁へ言い換える。
                // 申告を無視して狭い桁へ入れられると、置けるのに中身が潰れる——
                // **一番分かりにくい壊れ方**なので、選べないようにする。
                val widgetManager = remember { AppWidgetManager.getInstance(context) }
                // 横向きのホームでは、ウィジェットは左半分に置かれる。
                val windowDp = windowWidth().value.toInt()
                val usableWidthDp = (if (landscape) windowDp / 2 else windowDp) -
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
                val isMusic = page == musicPage
                val isDashboard = page == Dashboard.PAGE
                val showChrome = !isMusic && !isDashboard

                // ---- 面を組む部品 -------------------------------------------------
                //
                // 縦と横で**並べ方だけ**を変える。部品の中身は一つしか書かない
                // ——向きごとに中身を書き分けると、片方だけが古くなる（§8-2）。

                val masthead: @Composable () -> Unit = {
                    // 面の頭に、12% の燠火帯と名札を置く。
                    // **三枚とも同じ位置・同じ幅**なので、どの面に居ても
                    // 「同じ道具の中に居る」ことが形で分かる。
                    val boardCount = musicPage - Dashboard.PAGE + 1
                    BoardMasthead(
                        name = when {
                            // 面の名は欧文（[Lexicon]）。標語（右）は和文のまま。
                            isDashboard -> Lexicon.DASHBOARD
                            isMusic -> Lexicon.SOUND
                            else -> Lexicon.HOME
                        },
                        index = page - Dashboard.PAGE + 1,
                        count = boardCount,
                        right = when {
                            isDashboard -> "選ばない"
                            isMusic -> "一枚"
                            else -> "選ぶ"
                        },
                    )
                }
                val header: @Composable () -> Unit = {
                    // ホームの面は計器の上段（[HomeHero]）。面の設定を開いている間と、
                    // 報せ（notice）を出している間は従来の頭に戻す——操作列と報せはそちらが持つ。
                    if (instrumentHere && !ui.displayMenu && notice == null) {
                        HomeHero(
                            refreshKey = infoPermissionEpoch,
                            onOpenClock = commands.openClock,
                            onLongPress = commands.toggleMenu,
                        )
                    } else if (!isMusic) {
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
                }
                // 編集の卓（札・ウィジェット・サイトを選ぶ面）。
                val panels: @Composable () -> Unit = {
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
                                    page = page,
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
                                    val base = if (config.sections.none { it.page == page }) {
                                        config.sectionAdded("", page)
                                    } else {
                                        config
                                    }
                                    base.siteAdded(
                                        title = title,
                                        location = location,
                                        sectionTitle = base.sections.first {
                                            it.page == page
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
                }
                val pageBody: @Composable () -> Unit = {
                    if (page == Dashboard.PAGE) {
                        // 計器盤は選ばない面なので、宣言を読まない。目録から自動で組む。
                        DashboardPage(
                            pageCount = config.pageCount,
                            // 横向きは幅が倍近くあるので、節を二列に流す。
                            columns = if (landscape) 2 else 1,
                            commands = tileCommands,
                            refreshEpoch = infoPermissionEpoch,
                            frictionApps = config.frictionApps,
                        )
                    } else if (page == MusicBoard.page(config.pageCount)) {
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
                        page = page,
                        zone = LayoutConfig.ZONE_ABOVE,
                        host = widgetHost,
                        editing = ui.editorMode,
                        commands = widgetCommands,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    // 弦材は「大きな区切り」ただ一箇所＝状態(時刻・予定・天気)と台帳の境目にのみ置く。
                    // 節ごとに反復すると§N-7が禁じる「目盛りとの機械的な並置」になる。
                    // 計器を出している面では、一日の帯（[HomeHorizon]）が区切りを兼ねるので置かない。
                    if (!instrumentHere) {
                        ObservatoryTruss(
                            modifier = Modifier
                                .padding(horizontal = ObservatoryDimens.Gutter)
                                .padding(top = 14.dp)
                        )
                    }

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
                        page = page,
                        zone = LayoutConfig.ZONE_BELOW,
                        host = widgetHost,
                        editing = ui.editorMode,
                        commands = widgetCommands,
                        modifier = Modifier.padding(top = 8.dp),
                    )

                    }
                }
                val ledger: @Composable (Modifier, Boolean) -> Unit = { ledgerModifier, ledgerScrolls ->
                    HomeLedger(
                        modifier = ledgerModifier,
                        scrolls = ledgerScrolls,
                        page = page,
                        config = config,
                        latestConfig = { liveConfig },
                        sections = sections,
                        numbers = numbers,
                        aboveInfoTiles = aboveInfoTiles,
                        belowInfoTiles = belowInfoTiles,
                        ui = ui,
                        generations = generations,
                        iconPx = iconPx,
                        rowHeightPx = rowHeightPx,
                        badges = badges,
                        onCommit = onCommit,
                        onRollback = onRollback,
                        // 縦向きのホームでは、一日の帯と計器の列を台帳の直上（親指の届く帯）に置く。
                        lead = if (instrumentHere && !landscape) {
                            { HomeHorizon(refreshKey = infoPermissionEpoch) }
                        } else {
                            null
                        },
                    )
                }
                val footer: @Composable () -> Unit = {
                    // 節点は計器盤と音の面のぶんを足して数える。
                    // 左端が計器盤、右端が音、その間が利用者の頁である。
                    //
                    // 縦向きでは印を面の外（画面）に留めるので（[PagerIndicator]）、
                    // ここには同じ高さの空きだけを残す。横向きは面ごとに左右の組みが違い、
                    // 留める位置が一つに決まらないので、面の中に置いたままにする。
                    if (landscape) {
                        PageNavigator(
                            page = page - Dashboard.PAGE,
                            count = MusicBoard.page(config.pageCount) - Dashboard.PAGE + 1,
                            onPrevious = { if (page > Dashboard.PAGE) ui.page = page - 1 },
                            onNext = {
                                if (page < MusicBoard.page(config.pageCount)) ui.page = page + 1
                            },
                        )
                    } else {
                        Spacer(modifier = Modifier.height(ObservatoryDimens.Row))
                    }
                    // 検索行はホームだけ。読むための面から画面を削らない。
                    if (showChrome) CommandBar(onOpenSearch = onOpenSearch, onStartTyping = onStartTyping)
                }

                if (!landscape) {
                    masthead()
                    header()
                    // 編集中は札や操作列で面が伸びる。時計と編集の卓は留めたまま、
                    // それより下をまとめて送れるようにする(下の方へ手が届かない状態を作らない)。
                    val editScroll = rememberScrollState()
                    Column(
                        modifier = if (ui.editorMode) {
                            Modifier.weight(1f).scrollEdge(editScroll).verticalScroll(editScroll)
                        } else {
                            Modifier.weight(1f)
                        }
                    ) {
                        panels()
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
                                    Modifier.weight(1f).scrollEdge(pageScroll).verticalScroll(pageScroll)
                                else -> Modifier
                            },
                        ) {
                            pageBody()
                        }
                        ledger(
                            if (ui.editorMode || sections.isEmpty()) {
                                // 台帳が無い頁で重みを持たせると、空の枠が残りの高さを全部取り、
                                // その上の札が押し出されて切れる。持たせない。
                                Modifier.fillMaxWidth()
                            } else {
                                Modifier.fillMaxWidth().weight(1f)
                            },
                            // 編集中は外側の巻き（editScroll）が受け持つ。
                            !ui.editorMode,
                        )
                    }
                    footer()
                } else {
                    // ## 横向き（2026/9/25）
                    //
                    // > 「横画面にした時にも使いやすくしたいので横画面でも作業できるように
                    // >  全面的に改修してください」（本人）
                    //
                    // 横向きは**高さが縦向きの半分に満たない**（Pixel 8a で約 360dp）。
                    // 縦の一列をそのまま寝かせると、時計と札だけで画面が埋まり台帳に届かない。
                    // だから面ごとに、**左右へ分けられるものは分ける。**
                    //
                    // - ホーム：左に時計と札（巻く）、右に台帳と検索行。親指の届く右側に入口を置く
                    // - 計器盤：時計ごと一枚で巻き、節は二列に流す（[DashboardPage] の columns）
                    // - 音の面：面の中で左右に分ける（[MusicBoardPage]）
                    masthead()
                    when {
                        isMusic -> {
                            Column(modifier = Modifier.weight(1f).fillMaxWidth()) { pageBody() }
                            footer()
                        }
                        isDashboard -> {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    // 名札と巻く領域の間を空ける。空けないと、巻いた中身が
                                    // 名札の行に接して詰まって見えた（実機・2026/9/25）。
                                    .padding(top = 8.dp)
                                    .scrollEdge(landscapeScroll)
                                    .verticalScroll(landscapeScroll),
                            ) {
                                // 横向きの計器盤には時計を置かない。高さが縦向きの半分しかなく、
                                // 時刻はステータスバーにもある（本人・2026-09-25「これいるのか」）。
                                pageBody()
                            }
                            footer()
                        }
                        else -> Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            // ## 横向きのホームは三列（計器を出している面）
                            //
                            // 二列では、左（時計・予定・一日の帯・計器）が高さに収まらず帯が切れ、
                            // 右（台帳）の上は空いていた（実機・2026-09-25）。
                            // **左に今（時計・次の行動）、中央に一日（帯・計器）、右に道具（台帳）**。
                            Column(
                                modifier = Modifier
                                    .weight(if (instrumentHere) 1.25f else 1f)
                                    .fillMaxHeight()
                                    .padding(top = 8.dp)
                                    .scrollEdge(landscapeScroll)
                                    // 左の巻きが行き止まっても検索を開かない。
                                    // 札を読み終えるたびに一覧が開いては読めない。
                                    .nestedScroll(keepScrollHere)
                                    .verticalScroll(landscapeScroll),
                            ) {
                                header()
                                panels()
                                pageBody()
                            }
                            if (instrumentHere) {
                                Column(
                                    modifier = Modifier.weight(1f).fillMaxHeight(),
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    HomeHorizon(refreshKey = infoPermissionEpoch)
                                }
                            }
                            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                ledger(Modifier.fillMaxWidth().weight(1f), true)
                                footer()
                            }
                        }
                    }
                }
            }
            }
        }

        if (!isLandscape()) {
            PagerIndicator(
                pager = pager,
                // 利用者の頁（ホーム）だけは下に検索行があり、印はその上に在る。
                lift = { index ->
                    val at = index + Dashboard.PAGE
                    if (at != Dashboard.PAGE && at != MusicBoard.page(config.pageCount)) {
                        CommandBarHeight
                    } else {
                        0.dp
                    }
                },
                onSelect = { index -> ui.page = index + Dashboard.PAGE },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            )
        }
    }
}
