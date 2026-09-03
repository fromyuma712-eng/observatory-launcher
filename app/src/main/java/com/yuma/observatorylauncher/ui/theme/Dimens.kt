package com.yuma.observatorylauncher.ui.theme

import androidx.compose.ui.unit.dp

/**
 * observatory.css の寸法トークン(px)を dp へ1:1移植したもの。
 * 角丸は仕様上ゼロ(このオブジェクトに角丸トークンは存在しない)。
 */
object ObservatoryDimens {
    val Hairline = 1.dp
    val DotGrid = 24.dp        // 背景ドットグリッドの間隔
    val Anchor = 5.dp          // 格点(トラスのアンカレイジ)
    val TrussHeight = 30.dp    // §N-7 弦材の上下間隔(u)
    val TrussPanel = 120.dp    // §N-7 格間(長刻み40の3倍)
    val Row = 46.dp            // 台帳一行の高さ(親指で押せる最小限)
    val Gutter = 24.dp         // 画面左右の余白。ドットグリッドと同値
}
