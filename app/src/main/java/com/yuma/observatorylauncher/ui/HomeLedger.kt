package com.yuma.observatorylauncher.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.data.AppRepository
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens

/**
 * ホームの台帳（アプリの節と行）。
 *
 * 2026-09-25 に `HomeScreen` から切り出した（一つのファイルに三面の組み立てが詰まり、
 * 1,400 行を超えて見通しが悪くなっていたため）。**振る舞いは変えていない**——
 * 外から借りていた値を、すべて引数として受け取るようにしただけである。
 *
 * @param latestConfig 引いている最中に宣言が書き換わるので、並べ替えは常に最新を読む。
 */
@Composable
internal fun HomeLedger(
    modifier: Modifier,
    scrolls: Boolean,
    page: Int,
    config: LayoutConfig,
    latestConfig: () -> LayoutConfig,
    sections: List<Pair<String, List<HomeItem>>>,
    numbers: Map<String, Int>,
    aboveInfoTiles: List<LayoutConfig.InfoTile>,
    belowInfoTiles: List<LayoutConfig.InfoTile>,
    ui: HomeEditingState,
    generations: Int,
    iconPx: Int,
    rowHeightPx: Float,
    badges: Set<String>,
    onCommit: (LayoutConfig) -> Unit,
    onRollback: () -> Unit,
    /** 台帳の頭に置くもの（ホームの一日の帯と計器の列）。台帳と一緒に下へ寄る。 */
    lead: (@Composable () -> Unit)? = null,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    // 台帳は下寄せ。行数が画面を越えたときだけ勝手にスクロールへ移り、
    // 最下端の検索行は常に指の届く位置に残る。
    // 台帳は LazyColumn ではなく素の列にする。
    // LazyColumn は与えられた高さを必ず埋めるので、台帳の上の空白まで
    // 一覧の領域になり、そこでの長押し(編集へ入る)や掃き上げ(検索)が
    // 一覧に吸われて親へ届かない。ホームの台帳は元来小さく、
    // 遅延生成の利点より入口が塞がらないことの方が重い。
    Box(
        modifier = modifier,
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(
            modifier = if (scrolls) {
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            } else {
                Modifier.fillMaxWidth()
            },
        ) {
        lead?.invoke()
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
                                    badged = (item as? HomeItem.App)?.let { it.entry.packageName in badges } == true,
                                    fallbackLabel = if (item is HomeItem.Site) "WEB" else null,
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
                                                HapticFeedbackType.VirtualKey
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
                        // 長押しで持ち上げて引けば並べ替え。離した並びを一度だけ書く（世代も一つだけ増える）。
                        onReorder = { order ->
                            ui.clearMenus()
                            onCommit(latestConfig().sectionReordered(title, order))
                        },
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
                                            onCommit(latestConfig().movedDown(item.id))
                                            drag -= rowHeightPx
                                        } else if (drag < -rowHeightPx) {
                                            haptics.performHapticFeedback(
                                                HapticFeedbackType.LongPress
                                            )
                                            onCommit(latestConfig().movedUp(item.id))
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
                            badged = (item as? HomeItem.App)?.let { it.entry.packageName in badges } == true,
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
                                    haptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
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
        val pageIsBare = page != Dashboard.PAGE &&
            page != MusicBoard.page(config.pageCount) &&
            sections.isEmpty() &&
            aboveInfoTiles.isEmpty() &&
            belowInfoTiles.isEmpty() &&
            config.widgets.none { it.page == page }
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
