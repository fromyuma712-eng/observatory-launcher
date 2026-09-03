package com.yuma.observatorylauncher.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/*
 * 組版の入口。画面側で書体や字間を直書きせず、必ずここを通す。
 * 一箇所に置いてあるので、可読性の調整はこのファイルだけで完結する。
 */

/**
 * 大きさの階梯。
 *
 * ## ⚠️ なぜ作り直したか
 *
 * 数えたら **9〜18sp に十段階が密集し、168 箇所のうち 146 が 16sp 未満**だった。
 * しかも太さの変化が一つも無かった。
 * その結果「どの数字も同じ重さに見え、一目で読めない」という状態になっていた
 * （本人の指摘・2026/9/1）。
 *
 * 直し方は**大きくすることではない**。大きくすると、たいてい野暮になる。
 * 効かせるのは**対比**である——大きさ・太さ・色の三つを同時に離す。
 *
 * ## 段は五つだけ
 *
 * | 役 | 大きさ | 太さ | 使い所 |
 * | --- | --- | --- | --- |
 * | `Hero` | 34sp | SemiBold | **札に一つだけ。** その札が答える問いの答え |
 * | `Value` | 22sp | Medium | 副次の数値。主値と並べるとき |
 * | `Body` | 15sp | Regular | 読ませる文 |
 * | `Note` | 12sp | Regular | 添え・単位つきの説明 |
 * | `Label` | 10sp | Regular | 名札・目盛りの数字 |
 *
 * 中間（13/14/16/17/18/20）を**わざと空けてある**。
 * 隣り合う段が近いと、階層ではなく濁りになる。
 *
 * ## 太さの切れ目は Apple の階梯に合わせた
 *
 * 〜19sp は Regular、20〜33sp は Medium、34sp 以上は SemiBold。
 * 大きな字を Regular のまま置くと締まらず、
 * 小さな字を太くすると潰れる、という経験則に沿った切れ目である。
 *
 * ## 字間は大きさで向きが変わる
 *
 * 小さい字は**開き**（読みやすさのため）、大きい字は**締める**（塊として見せるため）。
 * 全段を +0.02 で通していたので、34sp の数字が間延びしていた。
 */
object ObservatoryType {
    val Hero = 34.sp
    val Value = 22.sp
    val Body = 15.sp
    val Note = 12.sp
    val Label = 10.sp
}

/** 大きさから太さを決める。呼び出し側で指定があればそちらを採る。 */
private fun weightFor(size: TextUnit): FontWeight = when {
    size.value >= 34f -> FontWeight.SemiBold
    size.value >= 20f -> FontWeight.Medium
    else -> FontWeight.Normal
}

/** 大きさから字間を決める。**小さい字は開き、大きい字は締める。** */
private fun trackingFor(size: TextUnit): Float = when {
    size.value >= 34f -> -0.02f
    size.value >= 20f -> -0.01f
    size.value <= 12f -> 0.04f
    else -> 0.02f
}

/**
 * 組版規定(§D)を一箇所に閉じ込める。画面側で letterSpacing や font を直書きしない。
 *
 * [tracking] と [weight] は既定で大きさから決まる。
 * **決め打ちにするのは、規定から外れる理由があるときだけ**にすること。
 */
@Composable
fun ObservatoryText(
    text: String,
    color: Color,
    size: TextUnit,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    tracking: Float? = null,
    weight: FontWeight? = null,
    maxLines: Int = 1,
    center: Boolean = false,
) {
    Text(
        text = text,
        color = color,
        fontSize = size,
        fontWeight = weight ?: weightFor(size),
        fontFamily = if (mono) FontFamily.Monospace else FontFamily.SansSerif,
        letterSpacing = (size.value * (tracking ?: trackingFor(size))).sp,
        maxLines = maxLines,
        // 入りきらないものは折り返さず省略記号で切る。長い曲名のような
        // 「たまたま長い値」に面の高さを譲ると、他の情報が押し出される。
        overflow = TextOverflow.Ellipsis,
        textAlign = if (center) TextAlign.Center else null,
        modifier = modifier,
    )
}
