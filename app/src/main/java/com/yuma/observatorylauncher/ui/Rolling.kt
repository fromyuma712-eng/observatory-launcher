package com.yuma.observatorylauncher.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit

/**
 * 桁ごとに回る数字。**替わった桁だけが、下から上へ回る**（オドメーター）。
 *
 * ## ⚠️ 二度目の作り直し（2026-09-25）
 *
 * 一度目は「:ss」を丸ごと一つの塊として 220ms で入れ替えた。コロンまで動き、
 * 替わらない十の位も毎秒揺れ、しかも新旧が重なって**「:10」と「:11」が二重に見えた**。
 * 本人の指摘「秒数はもっとスムーズに」。
 *
 * - 替わった桁だけを動かす（十の位は十秒に一度しか動かない）。
 * - 桁の枠で切り抜く——新旧が同じ場所に重ならない。
 * - 動きは 380ms・緩急つき（入りは速く、収まりは静かに）。一秒のうち約四割を動き、残りは止まる。
 */
@Composable
fun RollingText(
    text: String,
    color: Color,
    size: TextUnit,
    modifier: Modifier = Modifier,
    weight: FontWeight? = null,
    tracking: Float? = null,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.Bottom) {
        text.forEachIndexed { index, ch ->
            if (ch.isDigit()) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        (
                            slideInVertically(tween(ROLL_MS, easing = FastOutSlowInEasing)) { it } +
                                fadeIn(tween(ROLL_MS))
                            ) togetherWith (
                            slideOutVertically(tween(ROLL_MS, easing = FastOutSlowInEasing)) { -it } +
                                fadeOut(tween(ROLL_MS * 2 / 3))
                            ) using SizeTransform(clip = true)
                    },
                    modifier = Modifier.clipToBounds(),
                    label = "digit$index",
                ) { shown ->
                    ObservatoryText(shown.toString(), color, size, mono = true, weight = weight, tracking = tracking)
                }
            } else {
                ObservatoryText(ch.toString(), color, size, mono = true, weight = weight, tracking = tracking)
            }
        }
    }
}

private const val ROLL_MS = 380
