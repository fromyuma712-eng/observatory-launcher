package com.yuma.observatorylauncher.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * §N-7 弦材。用途は「大きな区切り」ただ一つで、目盛り（尺度）と機械的に並べない。
 * 着色は罫と同階調に留め、燠火を使わない。
 *
 * ## ⚠️ 斜材（ワーレン形の折れ線）をやめた（2026/9/2）
 *
 * 上下の弦材の間を斜材で結んでいた。意匠帳が書いていた姿は
 *
 * ```
 * ────────────────────────────
 *   ■        ■        ■
 * ────────────────────────────
 * ```
 *
 * であって、斜材は**実装だけが持っていた**。
 *
 * そして外部の目には、これが**無名の折れ線グラフ**に見えた
 * （2026/9/2「軸も単位も凡例もない図。天気の直下にあるので気温か降水と誤認する」）。
 *
 * 図だらけの面に、図の形をした飾りを置いてはならない。
 * **飾りは、飾りの形をしていなければならない。**
 * 仕様が書いていた姿——弦材二本と格点——へ戻す。
 */
@Composable
fun ObservatoryTruss(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(ObservatoryDimens.TrussHeight)
    ) {
        val h = size.height
        val hair = ObservatoryDimens.Hairline.toPx()
        val half = ObservatoryDimens.TrussPanel.toPx() / 2f   // 60dp: 斜材の折返し間隔
        val anchor = ObservatoryDimens.Anchor.toPx()

        // 上下の弦材
        drawRect(ObservatoryColors.Line, Offset(0f, 0f), Size(size.width, hair))
        drawRect(ObservatoryColors.Line, Offset(0f, h - hair), Size(size.width, hair))

        // 格点。**結ばない**——結ぶと折れ線になる。
        var x = half
        while (x < size.width) {
            drawRect(
                color = ObservatoryColors.Line,
                topLeft = Offset(x - anchor / 2f, (h - anchor) / 2f),
                size = Size(anchor, anchor),
            )
            x += half
        }
    }
}
