package com.yuma.observatorylauncher.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens

/**
 * 横向きか。**幅が高さを上回れば横**とみなす。
 *
 * 向きの設定値（`Configuration.orientation`）ではなく**窓の実寸**で見る。
 * 分割画面で縦長の窓に入ったときは、端末が横でも縦の組み方が正しい。
 *
 * 判定はここにしか書かない——面ごとに書くと、一枚だけ別の答えを出す（2026/9/25）。
 */
@Composable
internal fun isLandscape(): Boolean {
    val size = LocalWindowInfo.current.containerSize
    return size.width > size.height
}

/** 窓の幅（dp）。 */
@Composable
internal fun windowWidth(): Dp =
    with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }

/**
 * 巻いた中身が、留め置いた頭（時計）の下へ潜る境目に細い罫を引く。**巻いたときだけ。**
 *
 * 罫が無いと、潜りかけの字の上半分が日付のすぐ下に残り、
 * **二つの字が重なって見えた**（2026/9/25・計器盤）。境目が見えれば、切れているのだと分かる。
 *
 * `verticalScroll` より**前**に置くこと。後ろに置くと中身と一緒に巻かれて上へ消える。
 */
internal fun Modifier.scrollEdge(state: ScrollState): Modifier = drawWithContent {
    drawContent()
    if (state.value > 0) {
        val inset = ObservatoryDimens.Gutter.toPx()
        drawRect(
            ObservatoryColors.Line,
            topLeft = Offset(inset, 0f),
            size = Size((size.width - inset * 2).coerceAtLeast(0f), 1.dp.toPx()),
        )
    }
}
