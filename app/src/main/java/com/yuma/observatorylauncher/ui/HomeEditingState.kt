package com.yuma.observatorylauncher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.yuma.observatorylauncher.config.LayoutConfig

/**
 * ホームが「今どう触られているか」を持つ器。
 *
 * 宣言(`layout.json`)が**持ち物**を表すのに対し、こちらは**一時の状態**を表す。
 * どの行が開いているか、編集モードに入っているか、何枚目を見ているか——
 * いずれも保存すべきでなく、画面を離れれば消えてよいものである。
 *
 * 配置の関数の中に `var` を十一個並べていた頃は、
 * 「今どこを触れば何が閉じるのか」がその関数を全部読まないと分からなかった。
 * 器にまとめると、**閉じ方の規則がこの一箇所に集まる**。
 *
 * [Stable] を宣言してよい——中身は Compose の状態であり、
 * 変化は必ず通知される。
 */
@Stable
internal class HomeEditingState {

    /** 操作列を開いている行(アプリ・サイト)の識別子。 */
    var expandedItem by mutableStateOf<String?>(null)

    /** 操作列を開いている節の名前。 */
    var sectionMenu by mutableStateOf<String?>(null)

    /** 名前を打ち替えている節。 */
    var renaming by mutableStateOf<String?>(null)

    /** 行き先の節を選ばせている行。巡回ではなく選択にするために要る。 */
    var choosing by mutableStateOf<String?>(null)

    var displayMenu by mutableStateOf(false)
    var siteEditorOpen by mutableStateOf(false)
    var widgetPickerOpen by mutableStateOf(false)
    var infoPickerOpen by mutableStateOf(false)
    var editingInfoTile by mutableStateOf<LayoutConfig.InfoTile?>(null)
    var editingSiteId by mutableStateOf<String?>(null)

    /** 編集モード。入っている間はアプリが起動せず、押すと操作が開く。 */
    var editorMode by mutableStateOf(false)

    /** 見ているページ。宣言のページ数が減ったら [clampPage] で詰める。 */
    var page by mutableIntStateOf(0)

    /** 何かしらの操作列・選択面が開いているか。 */
    val menusOpen: Boolean
        get() = expandedItem != null || sectionMenu != null || displayMenu ||
            siteEditorOpen || widgetPickerOpen || infoPickerOpen || editingInfoTile != null

    /** 触っている最中かどうか。掃きで面を離れてよいかの判断に使う。 */
    val editing: Boolean get() = menusOpen || editorMode

    /**
     * 開いているものを畳む。**編集モードからは出ない**——
     * 一つ閉じるたびに編集が終わってしまうと、まとめて直せない。
     */
    fun clearMenus() {
        expandedItem = null
        sectionMenu = null
        renaming = null
        choosing = null
        displayMenu = false
        siteEditorOpen = false
        widgetPickerOpen = false
        infoPickerOpen = false
        editingInfoTile = null
        editingSiteId = null
    }

    /** 畳んだうえで編集モードからも出る。 */
    fun clearAll() {
        clearMenus()
        editorMode = false
    }

    /** 同じ行を押したら閉じる。開いたものを閉じる道を、押した場所自身にも持たせる。 */
    fun toggleItem(id: String) {
        sectionMenu = null
        displayMenu = false
        choosing = null
        expandedItem = if (expandedItem == id) null else id
    }

    /** 同じ節を押したら閉じる。 */
    fun toggleSection(title: String) {
        expandedItem = null
        displayMenu = false
        renaming = null
        sectionMenu = if (sectionMenu == title) null else title
    }

    /** ページが減ったときに、見ている位置を範囲へ収める。 */
    fun clampPage(pageCount: Int) {
        page = page.coerceIn(0, pageCount.coerceAtLeast(1) - 1)
    }
}

@Composable
internal fun rememberHomeEditingState(): HomeEditingState = remember { HomeEditingState() }
