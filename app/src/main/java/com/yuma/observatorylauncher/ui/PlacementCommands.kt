package com.yuma.observatorylauncher.ui

import androidx.compose.runtime.Immutable
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.data.InfoAccess

/**
 * 置かれた物を動かすための指示の束。
 *
 * ウィジェットと情報カードは別の物だが、**置かれ方の語彙は同じ**である
 * ——外す・順を変える・幅を変える・トラスの上下を替える・ページを移す。
 * 同じ組を毎回ばらして引数に並べると、面ごとに欠けたり順序が違ったりする。
 *
 * 識別子の型だけが違うので、そこを型引数にして一つの形にしてある。
 */
@Immutable
internal data class PlacementCommands<Id>(
    val remove: (Id) -> Unit,
    val move: (Id, Int) -> Unit,
    val cycleSpan: (Id) -> Unit,
    val toggleZone: (Id) -> Unit,
    val movePage: (Id, Int) -> Unit,
)

/** ウィジェット固有の指示（高さは外部の面を借りるウィジェットにしか無い）。 */
@Immutable
internal data class WidgetCommands(
    val placement: PlacementCommands<Int>,
    val cycleHeight: (Int) -> Unit,
    val setHeight: (Int, Int) -> Unit,
)

/** 情報カード固有の指示（中身の編集と、許可の要求）。 */
@Immutable
internal data class TileCommands(
    val placement: PlacementCommands<String>,
    val edit: (LayoutConfig.InfoTile) -> Unit,
    /** 札の中身を書き換える。習慣の印のように、**面から直に付ける**ものに要る。 */
    val update: (LayoutConfig.InfoTile) -> Unit,
    val requestAccess: (InfoAccess) -> Unit,
)
