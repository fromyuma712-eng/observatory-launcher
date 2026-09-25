package com.yuma.observatorylauncher.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import android.content.Context
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.data.AppEntry
import com.yuma.observatorylauncher.data.AppRepository
import com.yuma.observatorylauncher.data.Reading
import com.yuma.observatorylauncher.data.Usage
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens
import com.yuma.observatorylauncher.ui.theme.ObservatoryDotGrid
import com.yuma.observatorylauncher.ui.theme.ObservatoryMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState

/**
 * 全アプリへの唯一の動線。入力欄は画面最下端(親指圏)に置き、結果は下から上へ積む
 * ＝最良の候補が指に最も近い。入るときの動きは既存の rise(200ms・4dp)だけを使う。
 *
 * 二つの相を持つ。**何も打っていないときは群ごとに並べて眺める面**、
 * **打ち始めたら順位付けの一列**。眺めるのと探すのは別の作業なので、同じ形にしない。
 */
@Composable
fun CommandLine(
    visible: Boolean,
    /** 開いたらすぐ打てるようにするか（検索行を押して開いたとき）。 */
    typeOnOpen: Boolean = false,
    apps: List<AppEntry>,
    config: LayoutConfig,
    generations: Int,
    onDismiss: () -> Unit,
    onPin: (AppEntry) -> Unit,
    onCommit: (LayoutConfig) -> Unit,
    onRollback: () -> Unit,
    onProfileChanged: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val badges by rememberBadged()
    var query by remember { mutableStateOf(TextFieldValue("")) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var groupMenu by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<String?>(null) }
    // 「群を移す」で巡回させると行き先を選べない。選ばせる状態を明示的に持つ。
    var choosing by remember { mutableStateOf<String?>(null) }
    // 検索面の編集モード。群を付け替える作業は件数が多く、
    // 一件ごとに長押しし直すのは現実的でない。押しただけで操作が開くようにする。
    var editorMode by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    // 検索行を押して開いたときだけ、鍵盤を出す（下の「開いた直後に鍵盤を出さない」の例外）。
    // 掃き上げで開いたときは眺めるためなので、従来どおり出さない。
    LaunchedEffect(visible, typeOnOpen) {
        if (visible && typeOnOpen) {
            delay(120)
            runCatching { focus.requestFocus() }
        }
    }

    val clearEditing = {
        expanded = null
        groupMenu = null
        renaming = null
        choosing = null
    }

    // 開いた直後に鍵盤を出さない。眺める面が鍵盤で半分隠れては群にした意味がない。
    // 打ちたいときは入力欄に触れる——iOSのSpotlightと違い、ここは一覧と兼用の面である。
    LaunchedEffect(visible) {
        if (visible) {
            query = TextFieldValue("")
            clearEditing()
            editorMode = false
        }
    }

    val iconPx = remember(density, config.display.iconSize) {
        with(density) { config.display.iconSize.dp.roundToPx() }
    }
    // 名前の非表示はホームの見え方だけに効かせる。一覧は探す場所なので常に名称を残す。
    val listDisplay = remember(config.display) { config.display.copy(showLabels = true) }
    val privateSpace = AppRepository.privateSpace()

    // 施錠中はそもそも一覧に居ないが、表示側でも二重に断つ。
    val visibleApps = remember(apps, privateSpace.locked) {
        if (privateSpace.locked) apps.filterNot { it.isPrivate } else apps
    }
    val ordinary = remember(visibleApps) { visibleApps.filterNot { it.isPrivate } }
    val privateApps = remember(visibleApps) { visibleApps.filter { it.isPrivate } }

    val searching = query.text.isNotBlank()
    val results = remember(query.text, ordinary, context) { rank(context, ordinary, query.text) }
    val privateResults = remember(query.text, privateApps, context) { rank(context, privateApps, query.text) }
    val privateMatched = remember(query.text, privateSpace.exists) {
        privateSpace.exists && matchesPrivateSpace(query.text)
    }
    // 群の構成。宣言(groups)が最優先で、書かれていないものはOSの系統へ落ちる。
    val groups = remember(visibleApps, config.groups) { buildGroups(ordinary, config) }
    val groupOrder = remember(groups) { groups.map { it.first } }

    // 面を開いたら、出る予定のアイコンを背景で先に作る(走査中の復号を無くす)。
    LaunchedEffect(visible, iconPx, visibleApps) {
        if (visible) AppRepository.warmIcons(context, visibleApps, iconPx, IconTone.mono)
    }

    // 普段使いの重みも先に温める。読み出しは端末への問い合わせなので、
    // 一文字目を打った拍子に取りにいくと、その一打だけが目に見えて遅れる。
    var weights by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    LaunchedEffect(visible) {
        if (visible) {
            weights = withContext(Dispatchers.IO) { runCatching { Usage.frecency(context) }.getOrDefault(emptyMap()) }
        }
    }
    // よく使うのに、ホームに置いていないもの（2026-09-25）。一覧を開く理由の多くは
    // 「ホームに無いアプリを探す」ことなので、その答えを指の届く一番下に先回りして置く。
    val onHome = remember(config.sections) { config.sections.flatMap { it.items }.toSet() }
    val frequent = remember(weights, ordinary, onHome) {
        ordinary.filter { it.id !in onHome && (weights[it.packageName] ?: 0.0) > 0.0 }
            .sortedByDescending { weights[it.packageName] ?: 0.0 }
            .distinctBy { it.packageName }
            .take(FREQUENT_COUNT)
    }

    val launch: (AppEntry) -> Unit = { entry ->
        // 開くのは軽い手応えで。強い振動（LongPress）は長押しのために取っておく
        // ——同じ強さで返すと、押したのか長押ししたのかが指で分からない。
        haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
        AppRepository.launch(context, entry)
        onDismiss()
    }

    // 立ち上がりは**配置をやり直さずに**動かす。
    //
    // `slideInVertically` は動いている間ずっと**測り直し・配り直し**を起こす。
    // 面の中身が数十行あると、たった 4dp の動きのために毎フレーム全体を測り直すことになり、
    // 開く瞬間に一枚 100ms を超える回が出ていた（実測）。
    // 同じ 4dp の立ち上がりでも、描画層をずらすだけなら測り直しは起きない。
    //
    // ## せり上がって開く（2026-09-25）
    //
    // 開く身振りは「下から上へ掃く」なので、面も**下からせり上がり、バネで収まる**。
    // 以前の 4dp の浮き上がりは、動きの語彙を二つに絞っていた頃の最小限だった。
    val riseFrom = with(density) { 56.dp.toPx() }
    val rise by animateFloatAsState(
        targetValue = if (visible) 0f else riseFrom,
        animationSpec = if (visible) {
            spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
        } else {
            tween(160)
        },
        label = "rise",
    )
    // 戻る身振りの進み（0〜1）。**予測型の戻る**——指を引く間、面が縮みながら下がり、
    // 途中でやめれば元へ戻る。閉じるかどうかを、閉じる前に目で確かめられる。
    var backProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(visible) { if (!visible) backProgress = 0f }
    val backTravel = with(density) { 72.dp.toPx() }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)),
        exit = fadeOut(tween(160)),
        modifier = Modifier.graphicsLayer {
            translationY = rise + backProgress * backTravel
            val shrink = 1f - 0.08f * backProgress
            scaleX = shrink
            scaleY = shrink
            alpha = 1f - 0.10f * backProgress
        },
    ) {
        // 編集中の戻るは、まず編集を畳む。面ごと閉じるのはその次。
        val editingOpen = expanded != null || groupMenu != null || editorMode
        BackHandler(enabled = editingOpen) {
            if (expanded != null || groupMenu != null) clearEditing() else editorMode = false
        }
        PredictiveBackHandler(enabled = !editingOpen) { events ->
            try {
                events.collect { event -> backProgress = event.progress }
                onDismiss()
            } catch (cancelled: CancellationException) {
                backProgress = 0f
                throw cancelled
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(ObservatoryColors.Ground)
        ) {
            ObservatoryDotGrid(modifier = Modifier.fillMaxSize())

            // 上端まで送ったあと更に下へ掃けばホームへ戻る。
            // 行き止まりでの余った動きを閉じる合図として使う——
            // 開くのと同じ身振りで閉じられる方が、往復の手数が減る。
            // 閉じるための引きは、送るための掃きとはっきり違う量にする。
            val closeThreshold = with(density) { 140.dp.toPx() }
            val pullToClose = remember(closeThreshold) {
                object : NestedScrollConnection {
                    private var travel = 0f
                    private var pullingSince = 0L

                    override fun onPostScroll(
                        consumed: Offset,
                        available: Offset,
                        source: NestedScrollSource,
                    ): Offset {
                        // 指で引いている分だけを数える。弾いた勢いは数えない。
                        if (source != NestedScrollSource.UserInput) return Offset.Zero
                        // 一覧が動いたなら、それは送っている最中である。
                        if (consumed.y != 0f || available.y <= 0f) {
                            travel = 0f
                            pullingSince = 0L
                            return Offset.Zero
                        }
                        val now = System.currentTimeMillis()
                        if (pullingSince == 0L) pullingSince = now
                        travel += available.y
                        // **引いて留めたときだけ**閉じる。
                        // 距離だけを条件にすると、上まで送ろうと続けて掃いた最後の一回で
                        // 面が閉じてしまう(実際にそうなった)。速い掃きは短時間で終わるので、
                        // 「一定の距離」に加えて「一定の時間引き続けたこと」を要る。
                        if (travel > closeThreshold && now - pullingSince >= PULL_HOLD_MS) {
                            travel = 0f
                            pullingSince = 0L
                            onDismiss()
                        }
                        return Offset.Zero
                    }

                    override suspend fun onPostFling(
                        consumed: Velocity,
                        available: Velocity,
                    ): Velocity {
                        // 指を離したら数え直す。手を離すまでが一回の引きである。
                        travel = 0f
                        pullingSince = 0L
                        return Velocity.Zero
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                // 群の見出しが一覧の何番目にあるか（右端の索引から飛ぶため）。
                // **下の LazyColumn に並べる順と同じ数え方**をここに書く。並べ方を変えたら、ここも直す。
                val jumpTargets = remember(groups, config.groups, expanded, frequent, privateSpace, privateResults.size) {
                    var at = 0
                    val out = ArrayList<Pair<String, Int>>()
                    if (frequent.isNotEmpty()) at += 2
                    groups.forEach { (title, entries) ->
                        val isRow = config.groups.firstOrNull { it.title == title }?.row == true
                        at += if (isRow) {
                            (if (entries.any { it.id == expanded }) 1 else 0) + 1
                        } else {
                            entries.size
                        }
                        out += title to at
                        at += 1
                    }
                    if (!privateSpace.locked && privateResults.isNotEmpty()) at += 1
                    if (privateSpace.exists && !privateSpace.locked) out += "私" to at
                    out.asReversed() // 見た目の上から下の順へ
                }
                val listState = rememberLazyListState()
                val scope = rememberCoroutineScope()
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(pullToClose),
                    reverseLayout = true,
                ) {
                    // reverseLayout なので、先に宣言した item ほど下＝指に近い位置に出る。
                    if (searching) {
                        if (results.isEmpty() && !privateMatched && privateResults.isEmpty()) {
                            item(key = "empty") {
                                ObservatoryText(
                                    text = "該当なし",
                                    color = ObservatoryColors.Muted,
                                    size = ObservatoryType.Body,
                                    modifier = Modifier
                                        .heightIn(min = ObservatoryDimens.Row)
                                        .padding(horizontal = ObservatoryDimens.Gutter + 32.dp),
                                )
                            }
                        }
                        items(results, key = { "m:" + it.component.flattenToShortString() }) { e ->
                            AppRow(
                                label = e.label,
                                icon = rememberAppIcon(e, iconPx).value,
                                badged = e.packageName in badges,
                                display = listDisplay,
                                highlighted = expanded == e.id,
                                expanded = expanded == e.id,
                                onClick = {
                                    if (editorMode) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        groupMenu = null
                                        choosing = null
                                        expanded = if (expanded == e.id) null else e.id
                                    } else {
                                        launch(e)
                                    }
                                },
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    groupMenu = null
                                    choosing = null
                                    expanded = if (expanded == e.id) null else e.id
                                },
                                // 開いている行だけ組む。ここは `groupOf` が
                                // **分類の規則表を一件ずつ走らせる**ので、
                                // 毎行やると走査のたびに規則が何十回も回る。
                                actions = if (expanded != e.id) {
                                    emptyList()
                                } else groupActions(
                                    config, e, groupOf(e, config), groupOrder, choosing,
                                    onCommit = onCommit,
                                    onPin = { clearEditing(); onPin(e); onDismiss() },
                                    onInfo = {
                                        clearEditing()
                                        AppRepository.openAppInfo(context, e)
                                    },
                                    onClose = clearEditing,
                                    setChoosing = { choosing = it },
                                ),
                            )
                        }
                    } else {
                        // 眺める面。群ごとにまとめ、宣言の先頭が最下段＝指に最も近い。
                        // よく使う（ホーム以外）。**一番下＝検索行のすぐ上**に置く。
                        if (frequent.isNotEmpty()) {
                            item(key = "frequent") {
                                IconCluster(
                                    entries = frequent.map { e ->
                                        ClusterItem(
                                            key = "f:" + e.id,
                                            label = e.label,
                                            icon = rememberAppIcon(e, iconPx).value,
                                            badged = e.packageName in badges,
                                            onClick = { launch(e) },
                                            onLongClick = {
                                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                                AppRepository.openAppInfo(context, e)
                                            },
                                        )
                                    },
                                    display = listDisplay,
                                )
                            }
                            item(key = "frequent-head") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = ObservatoryDimens.Gutter)
                                        .padding(top = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    ObservatoryTag(text = Lexicon.FREQUENT, color = ObservatoryColors.EmberText)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    ObservatoryText(
                                        text = "ホーム以外でよく使う",
                                        color = ObservatoryColors.Faint,
                                        size = ObservatoryType.Note,
                                    )
                                }
                            }
                        }
                        groups.forEach { (title, entries) ->
                            val isRow = config.groups.firstOrNull { it.title == title }?.row == true
                            if (isRow) {
                                val open = entries.firstOrNull { it.id == expanded }
                                if (open != null) {
                                    item(key = "gact:$title") {
                                        ActionStrip(
                                            groupActions(
                                                config, open, title, groupOrder, choosing,
                                                onCommit = onCommit,
                                                onPin = {
                                                    clearEditing(); onPin(open); onDismiss()
                                                },
                                                onInfo = {
                                                    clearEditing()
                                                    AppRepository.openAppInfo(context, open)
                                                },
                                                onClose = clearEditing,
                                                setChoosing = { choosing = it },
                                            )
                                        )
                                    }
                                }
                                item(key = "grow:$title") {
                                    IconCluster(
                                        entries = entries.map { e ->
                                            ClusterItem(
                                                key = e.id,
                                                label = e.label,
                                                icon = rememberAppIcon(e, iconPx).value,
                                                badged = e.packageName in badges,
                                                onClick = { launch(e) },
                                                onLongClick = {
                                                    haptics.performHapticFeedback(
                                                        HapticFeedbackType.LongPress
                                                    )
                                                    groupMenu = null
                                                    choosing = null
                                                    expanded =
                                                        if (expanded == e.id) null else e.id
                                                },
                                            )
                                        },
                                        display = listDisplay,
                                    )
                                }
                            } else {
                                items(
                                    entries.asReversed(),
                                    key = { "g:$title:" + it.component.flattenToShortString() },
                                ) { e ->
                                    AppRow(
                                        label = e.label,
                                        icon = rememberAppIcon(e, iconPx).value,
                                        badged = e.packageName in badges,
                                        display = listDisplay,
                                        highlighted = expanded == e.id,
                                        expanded = expanded == e.id,
                                        onClick = {
                                            if (editorMode) {
                                                haptics.performHapticFeedback(
                                                    HapticFeedbackType.LongPress
                                                )
                                                groupMenu = null
                                                choosing = null
                                                expanded =
                                                    if (expanded == e.id) null else e.id
                                            } else {
                                                launch(e)
                                            }
                                        },
                                        onLongClick = {
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.LongPress
                                            )
                                            groupMenu = null
                                            choosing = null
                                            expanded = if (expanded == e.id) null else e.id
                                        },
                                        // **開いている行だけ組む。**
                                        // 操作列は開いたときにしか描かれないのに、
                                        // 行が現れるたびに毎回作っていた。速く投げて掃くと
                                        // 一枚のうちに何十行も新しく組み立てられ、
                                        // そのぶんの一覧と手続きの生成が組み直しの相を埋めていた。
                                        actions = if (expanded != e.id) {
                                            emptyList()
                                        } else groupActions(
                                            config, e, title, groupOrder, choosing,
                                            onCommit = onCommit,
                                            onPin = { clearEditing(); onPin(e); onDismiss() },
                                            onInfo = {
                                                clearEditing()
                                                AppRepository.openAppInfo(context, e)
                                            },
                                            onClose = clearEditing,
                                            setChoosing = { choosing = it },
                                        ),
                                    )
                                }
                            }
                            item(key = "gh:$title") {
                                GroupHeader(
                                    title = title,
                                    menuOpen = groupMenu == title,
                                    renaming = renaming == title,
                                    trailing = entries.size.toString(),
                                    onToggleMenu = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        expanded = null
                                        renaming = null
                                        groupMenu = if (groupMenu == title) null else title
                                    },
                                    onRename = { name ->
                                        clearEditing()
                                        onCommit(config.renamedGroup(title, name))
                                    },
                                    actions = buildList {
                                        add(LedgerAction("名前を変える") { renaming = title })
                                        add(
                                            LedgerAction(
                                                if (config.groups.firstOrNull {
                                                        it.title == title
                                                    }?.row == true
                                                ) "並び：横に並べる" else "並び：縦一列"
                                            ) { onCommit(config.groupRowToggled(title)) }
                                        )
                                        add(LedgerAction("この下に群を追加") {
                                            groupMenu = null
                                            onCommit(config.groupAdded(title))
                                        })
                                        add(LedgerAction("この群を削除") {
                                            groupMenu = null
                                            onCommit(config.groupRemoved(title))
                                        })
                                        if (generations > 0) {
                                            add(LedgerAction("元に戻す($generations)") {
                                                groupMenu = null
                                                onRollback()
                                            })
                                        }
                                        add(LedgerAction("閉じる", clearEditing))
                                    },
                                )
                            }
                        }
                    }

                    // プライベートは常に**一つの塊**として、面の最も上に置く。
                    //
                    // ⚠️ 行で縦に積んでいたが、**塊なら横に並べる**方が正しい
                    // （本人・2026/9/2「節の中を横表示にしたいのに縦にしかできない」）。
                    // 一つずつ独立した行ではなく、**まとめて一区画**であることが、
                    // 並びそのもので分かる。
                    if (!privateSpace.locked && privateResults.isNotEmpty()) {
                        item(key = "private-cluster") {
                            IconCluster(
                                entries = privateResults.map { e ->
                                    ClusterItem(
                                        key = e.id,
                                        label = e.label,
                                        icon = rememberAppIcon(e, iconPx).value,
                                        badged = e.packageName in badges,
                                        onClick = { launch(e) },
                                        onLongClick = {
                                            // 施錠の意味が壊れるので台帳へは固定させない。
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.LongPress,
                                            )
                                            AppRepository.openAppInfo(context, e)
                                            onDismiss()
                                        },
                                    )
                                },
                                display = listDisplay.copy(showLabels = true),
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    }
                    if (privateSpace.exists && (privateMatched || !privateSpace.locked)) {
                        item(key = "private-space") {
                            PrivateSpaceRow(
                                locked = privateSpace.locked,
                                count = privateResults.size,
                                onToggle = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    AppRepository.setPrivateSpaceLocked(
                                        context,
                                        !privateSpace.locked,
                                    )
                                    onProfileChanged()
                                },
                                onSettings = { AppRepository.openPrivateSpaceSettings(context) },
                            )
                        }
                    }
                }
                if (!searching && !editorMode && jumpTargets.size > 1) {
                    GroupIndex(
                        targets = jumpTargets,
                        onJump = { index ->
                            scope.launch {
                                // 逆向きの一覧では「先頭」は下端。見出しを上端へ置くため、
                                // 画面の高さぶん手前（負の位置）を指定する。
                                val viewport = listState.layoutInfo.viewportSize.height
                                listState.scrollToItem(index, -(viewport - with(density) { 48.dp.roundToPx() }))
                            }
                        },
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
                }

                if (editorMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = ObservatoryDimens.Gutter)
                            .padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(ObservatoryDimens.Anchor)
                                .background(ObservatoryColors.Ember)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        ObservatoryText(
                            text = "編集モード — 押すと操作が出ます（アプリは起動しません）",
                            color = ObservatoryColors.EmberText,
                            size = ObservatoryType.Note,
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .padding(horizontal = ObservatoryDimens.Gutter)
                        .fillMaxWidth()
                        .height(ObservatoryDimens.Hairline)
                        .background(ObservatoryColors.Line)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(ObservatoryDimens.Row + 8.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = PressIndication.Row,
                        ) { focus.requestFocus() }
                        .padding(horizontal = ObservatoryDimens.Gutter),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 格点。入力位置の宣言であって装飾ではないので燠火は使わない。
                    Box(
                        modifier = Modifier
                            .size(ObservatoryDimens.Anchor)
                            .background(ObservatoryColors.Line)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Box(modifier = Modifier.weight(1f)) {
                        if (query.text.isEmpty()) {
                            // **件数は書かない。** ここが入力欄であることは
                            // 開いた時点で自明で、件数は打ち始めれば消える数字である。
                            // 自明なものは、小さく薄く置いてよい（本人指示・2026/9/1）。
                            ObservatoryText(
                                text = "絞り込む",
                                color = ObservatoryColors.Faint,
                                size = ObservatoryType.Body,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            cursorBrush = SolidColor(ObservatoryColors.Ember),
                            textStyle = TextStyle(
                                color = ObservatoryColors.Paper,
                                fontSize = 17.sp,
                                fontFamily = ObservatoryFonts.Sans,
                                letterSpacing = 0.34.sp,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    (results.firstOrNull() ?: privateResults.firstOrNull())
                                        ?.let { launch(it) }
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focus),
                        )
                    }
                    EditToggle(editorMode) { editorMode = !editorMode }
                }
            }
        }
    }
}

/**
 * 行の操作列。[choosing] にその行が入っている間は、操作ではなく**移動先の群の一覧**を出す。
 * 巡回させると行き先を選べないので、選ばせる状態を挟む。
 */
private fun groupActions(
    config: LayoutConfig,
    entry: AppEntry,
    currentGroup: String,
    groupOrder: List<String>,
    choosing: String?,
    onCommit: (LayoutConfig) -> Unit,
    onPin: () -> Unit,
    onInfo: () -> Unit,
    onClose: () -> Unit,
    setChoosing: (String?) -> Unit,
): List<LedgerAction> {
    if (choosing == entry.id) {
        return buildList {
            groupOrder.forEach { name ->
                val mark = if (name == currentGroup) "・" else ""
                add(
                    LedgerAction("$mark$name") {
                        setChoosing(null)
                        onCommit(config.assignedToGroup(entry.id, name))
                    }
                )
            }
            add(LedgerAction("やめる") { setChoosing(null) })
        }
    }
    val marked = entry.packageName in config.frictionApps
    return listOf(
        LedgerAction("別の群へ移動") { setChoosing(entry.id) },
        LedgerAction("ホームに追加", onPin),
        // **摩擦は利用者が指す。** 端末の分類から推すことはしない——
        // 何が自分にとって「無意識に開いてしまうもの」かは本人にしか分からず、
        // 道具が勝手に決めると、外したくなるだけである。
        LedgerAction(if (marked) "摩擦を外す" else "摩擦を掛ける") {
            onClose()
            onCommit(config.frictionToggled(entry.packageName))
        },
        LedgerAction("アプリ情報", onInfo),
        LedgerAction("閉じる", onClose),
    )
}

/** プライベートスペースの施錠状態と、その切り替え。状態は文字で明示し記号に頼らない。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PrivateSpaceRow(
    locked: Boolean,
    count: Int,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ObservatoryDimens.Row)
            .riseIn()
            .combinedClickable(
                interactionSource = interaction,
                indication = PressIndication.Row,
                onClick = onToggle,
                onLongClick = onSettings,
            )
            .padding(horizontal = ObservatoryDimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(ObservatoryDimens.Anchor)
                .background(if (locked) ObservatoryColors.Line else ObservatoryColors.Ember)
        )
        Spacer(modifier = Modifier.width(12.dp))
        ObservatoryText(
            text = if (count > 0) "プライベートスペース  $count" else "プライベートスペース",
            color = ObservatoryColors.Paper,
            size = ObservatoryType.Body,
        )
        Spacer(modifier = Modifier.weight(1f))
        ObservatoryText(
            text = if (locked) "施錠中 — 押して解錠" else "解錠中 — 押して施錠",
            color = ObservatoryColors.Muted,
            size = ObservatoryType.Note,
            mono = true,
        )
    }
}

/** 一件がどの群に属するか。宣言が最優先、無ければOSの系統。 */
private fun groupOf(entry: AppEntry, config: LayoutConfig): String =
    config.groups.firstOrNull { it.items.contains(entry.id) }?.title ?: entry.categoryLabel

/**
 * 群の構成。宣言された群を宣言順に置き、宣言に無い系統をその後ろへ足す。
 * 中身が空でも宣言された群は残す——見えない群には何も入れられない。
 */
private fun buildGroups(
    apps: List<AppEntry>,
    config: LayoutConfig,
): List<Pair<String, List<AppEntry>>> {
    val assigned = HashMap<String, MutableList<AppEntry>>()
    apps.forEach { entry ->
        assigned.getOrPut(groupOf(entry, config)) { mutableListOf() }.add(entry)
    }
    val declared = config.groups.map { it.title }
    val rest = assigned.keys.filterNot { declared.contains(it) }.sorted()
    return (declared + rest).map { title ->
        title to (assigned[title]?.sortedBy { it.label.lowercase() } ?: emptyList())
    }
}

/**
 * 並べ替えの規則。照合そのものは[Reading]が持つ。ここが決めるのは三つ。
 *
 * 1. **厳密に当てる段を先に尽くす。** 打ち間違いを許すのは、厳密な段で
 *    ほとんど残らなかったときだけにする。Algolia や Typesense も同じ順で、
 *    許しを常時効かせると絞るという目的そのものを失う。
 * 2. **普段使うものを少しだけ持ち上げる。** 同じくらい当たった二つなら、
 *    開いている方が目的である確率が高い(KISS の履歴、Firefox の frecency)。
 *    持ち上げ幅は当たり方の差を覆さない大きさに抑える。
 * 3. 同点は短い名前を先にする。短い方が目的である確率が高い。
 */
private fun rank(context: Context, apps: List<AppEntry>, query: String): List<AppEntry> {
    if (Reading.forms(query).none { it.isNotEmpty() }) return apps.take(MAX_RESULTS)
    val weights = runCatching { Usage.frecency(context) }.getOrNull().orEmpty()

    // 打たれた語は段ごとに一度だけ組み立てる。名前の件数だけ作り直すと打鍵が重くなる。
    fun pass(tolerance: Int): List<Pair<AppEntry, Int>> {
        val matcher = Reading.matcher(query, tolerance)
        return apps.mapNotNull { entry ->
            val score = matcher.score(entry.label) ?: return@mapNotNull null
            val habit = (weights[entry.packageName] ?: 0.0) * HABIT_WEIGHT
            entry to (score + habit.toInt() - entry.label.length)
        }
    }

    val strict = pass(0).sortedByDescending { it.second }
    if (strict.size >= TOLERANCE_FLOOR) {
        return strict.map { it.first }.take(MAX_RESULTS)
    }
    val allowance = Reading.allowance(query.length)
    if (allowance <= 0) return strict.map { it.first }.take(MAX_RESULTS)

    // 寛容な段は**厳密に当たったものの下に足すだけ**である。混ぜて並べ直すと、
    // 打ち間違いとして拾った名前が、正しく当たった名前を追い越しうる
    // （`かいいん` で `X` が `会員CLUB` の上に来た。骨格が一字違い）。
    val held = strict.map { it.first.id }.toSet()
    val extra = pass(allowance)
        .filterNot { it.first.id in held }
        .sortedByDescending { it.second }
    return (strict + extra).map { it.first }.take(MAX_RESULTS)
}

/** 照合前の正規化。実体は[Reading]に置き、規則を二箇所に持たない。 */
private fun normalize(text: String): String = Reading.normalize(text)

/**
 * プライベートスペースを検索語で引き当てる。
 * 「ぷら」は正規化で「プラ」になるので、片仮名の綴りに前方一致すれば当たる。
 */
private fun matchesPrivateSpace(query: String): Boolean {
    val q = normalize(query)
    // とびとび一致まで許すと一文字でも当たってしまう。前方一致だけを見る。
    if (q.length < 2) return false
    return PRIVATE_SPACE_KEYS.any { key -> key.split(' ').any { it.startsWith(q) } }
}

private val PRIVATE_SPACE_KEYS = listOf(
    "プライベートスペース",
    "private space",
    "privatespace",
)

/** 引いて留める時間。これ未満は「送るための掃き」とみなす。 */
private const val PULL_HOLD_MS = 320L

private const val MAX_RESULTS = 24

/** 厳密な段でこれを下回ったときだけ、打ち間違いを許す段へ落とす。 */
private const val TOLERANCE_FLOOR = 3

/** 普段使いの持ち上げ幅。当たり方の点は数千の桁なので、順を覆さない大きさである。 */
private const val HABIT_WEIGHT = 400.0

/** 検索面の編集の入切。入力欄の右端に常設し、探しに来ないと見つからない場所へ置かない。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EditToggle(editing: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .heightIn(min = ObservatoryDimens.Row)
            .combinedClickable(
                interactionSource = interaction,
                indication = PressIndication.Row,
                onClick = onToggle,
            )
            .padding(start = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        ObservatoryText(
            text = if (editing) "完了" else "編集",
            color = if (editing) ObservatoryColors.Paper else ObservatoryColors.Line,
            size = ObservatoryType.Body,
            tracking = 0.1f,
        )
    }
}

private const val FREQUENT_COUNT = 5

/**
 * 右端の索引。**群の頭文字を縦に並べ、触れた群へ飛ぶ。** なぞれば群をまたぐたびに軽く震える。
 *
 * 群が十五を超え、端から端まで掃いて探すのが遠かった（2026-09-25）。
 * 余白（24dp）の中に収まる大きさにして、像の押し所を塞がない。
 */
@Composable
private fun GroupIndex(targets: List<Pair<String, Int>>, onJump: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    var current by remember { mutableStateOf<Int?>(null) }
    val rowHeight = 22.dp
    val density = LocalDensity.current
    fun pick(y: Float) {
        val i = (y / with(density) { rowHeight.toPx() }).toInt().coerceIn(0, targets.lastIndex)
        if (i != current) {
            current = i
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            onJump(targets[i].second)
        }
    }
    Column(
        modifier = modifier
            .width(ObservatoryDimens.Gutter)
            .pointerInput(targets) {
                detectTapGestures(onPress = { p ->
                    pick(p.y)
                    tryAwaitRelease()
                    current = null
                })
            }
            .pointerInput(targets) {
                detectVerticalDragGestures(
                    onDragStart = { p -> pick(p.y) },
                    onDragEnd = { current = null },
                    onDragCancel = { current = null },
                ) { change, _ ->
                    change.consume()
                    pick(change.position.y)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        targets.forEachIndexed { i, (title, _) ->
            Box(modifier = Modifier.height(rowHeight), contentAlignment = Alignment.Center) {
                ObservatoryText(
                    text = title.take(1),
                    color = if (current == i) ObservatoryColors.EmberText else ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                )
            }
        }
    }
}
