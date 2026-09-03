package com.yuma.observatorylauncher.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.WebShortcut
import com.yuma.observatorylauncher.data.AppEntry
import com.yuma.observatorylauncher.data.AppRepository
/*
 * ホームの台帳に載るもの（アプリ／サイト）と、その扱い。
 * 画面の組み立てからは切り離す——載せる物が増えてもレイアウトの読みやすさに響かせない。
 */

/** ホーム台帳だけが扱う共通項目。アプリ一覧側の型へサイトを混ぜない。 */
internal sealed interface HomeItem {
    val id: String
    val label: String

    data class App(val entry: AppEntry) : HomeItem {
        override val id: String get() = entry.id
        override val label: String get() = entry.label
    }

    data class Site(val site: LayoutConfig.Site) : HomeItem {
        override val id: String get() = site.id
        override val label: String get() = site.title
    }
}

/**
 * 行の操作列。[choosing] にその行が入っている間は、操作ではなく**移動先の節の一覧**を出す。
 * 巡回させると行き先を選べないので、選ばせる状態を挟む。
 */
internal fun rowActions(
    config: LayoutConfig,
    entry: HomeItem,
    choosing: String?,
    context: android.content.Context,
    onCommit: (LayoutConfig) -> Unit,
    closeMenu: () -> Unit,
    shortcuts: List<AppRepository.AppShortcut> = emptyList(),
    onShortcut: (AppRepository.AppShortcut) -> Unit = {},
    onEditSite: (LayoutConfig.Site) -> Unit,
    setChoosing: (String?) -> Unit,
): List<LedgerAction> {
    if (choosing == entry.id) {
        val here = config.sections.firstOrNull { it.items.contains(entry.id) }?.title
        return buildList {
            config.sections.forEach { section ->
                val mark = if (section.title == here) "・" else ""
                val page = if (config.pageCount > 1) "ページ${section.page + 1}／" else ""
                add(
                    LedgerAction("$mark$page${section.title}") {
                        setChoosing(null)
                        onCommit(config.pinned(entry.id, section.title))
                    }
                )
            }
            add(LedgerAction("やめる") { setChoosing(null) })
        }
    }
    return buildList {
        shortcuts.forEach { shortcut ->
            add(LedgerAction(shortcut.label) { onShortcut(shortcut) })
        }
        add(LedgerAction("上へ移動") { onCommit(config.movedUp(entry.id)) })
        add(LedgerAction("下へ移動") { onCommit(config.movedDown(entry.id)) })
        add(LedgerAction("別の節へ移動") { setChoosing(entry.id) })
        when (entry) {
            is HomeItem.App -> {
                add(LedgerAction("ホームから外す") {
                    closeMenu()
                    onCommit(config.without(entry.id))
                })
                add(LedgerAction("アプリ情報") {
                    closeMenu()
                    AppRepository.openAppInfo(context, entry.entry)
                })
            }

            is HomeItem.Site -> {
                add(LedgerAction("サイト・HTMLを編集") { onEditSite(entry.site) })
                add(LedgerAction("このサイトを削除") {
                    closeMenu()
                    onCommit(config.siteRemoved(entry.id))
                })
            }
        }
        // 操作列だけを畳み、編集モードは「完了」まで維持する。
        add(LedgerAction("閉じる", closeMenu))
    }
}

@Composable
internal fun rememberHomeIcon(item: HomeItem, sizePx: Int): ImageBitmap? =
    when (item) {
        is HomeItem.App -> rememberAppIcon(item.entry, sizePx).value
        is HomeItem.Site -> null
    }

internal fun openHomeItem(context: Context, item: HomeItem): Boolean =
    when (item) {
        is HomeItem.App -> AppRepository.launch(context, item.entry)
        is HomeItem.Site -> WebShortcut.open(context, item.site)
    }
