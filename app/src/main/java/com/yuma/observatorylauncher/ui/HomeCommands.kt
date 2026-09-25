package com.yuma.observatorylauncher.ui

import androidx.compose.runtime.Immutable

/**
 * ホームの上部が発せられる指示の束。
 *
 * これらは一つずつ配るべきものではない。**同じ一組が常に一緒に運ばれる**からで、
 * 実際、引数として並べていた頃は見出しの引数が21個まで膨れ、
 * 一つ足すたびに三箇所（定義・呼び出し・受け渡し）を直していた。
 * 束にすれば足すのは一箇所で済み、どこで誰が何を起こせるのかも一目で分かる。
 *
 * すべて不変の関数参照なので [Immutable] を宣言してよい——
 * Compose はこれを見て、束が変わらないかぎり組み直しを省ける。
 */
@Immutable
internal data class HomeCommands(
    val openClock: () -> Unit,
    val toggleEditor: () -> Unit,
    val toggleMenu: () -> Unit,
    val cycleIconSize: () -> Unit,
    val toggleLabels: () -> Unit,
    /** 像を単色⇔原色に切り替える。 */
    val toggleIconTone: () -> Unit = {},
    val addInfo: () -> Unit,
    val addWidget: () -> Unit,
    val addSite: () -> Unit,
    val addSection: () -> Unit,
    val addPage: () -> Unit,
    val removePage: () -> Unit,
    val rollback: () -> Unit,
    val exportBackup: () -> Unit,
    val importBackup: () -> Unit,
    val close: () -> Unit,
)
