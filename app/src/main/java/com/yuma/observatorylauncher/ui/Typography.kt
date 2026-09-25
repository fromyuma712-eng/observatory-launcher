package com.yuma.observatorylauncher.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.yuma.observatorylauncher.R
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors

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
 * | `Body` | 16sp | Regular | 読ませる文 |
 * | `Note` | 14sp | Regular | 添え・単位つきの説明 |
 * | `Label` | 12sp | Regular | 名札・目盛りの数字 |
 *
 * 中間（13/15/17/18/20）を**わざと空けてある**。
 * 隣り合う段が近いと、階層ではなく濁りになる。
 *
 * ## ⚠️ 下の三段を上げた（2026/9/25）
 *
 * > 「スマホの画面が汚れてぼやけたり、私がとても若干乱視なので
 * >  小さい文字を稀にうまく識別できなくなります」（本人）
 *
 * 旧: Body 15 / Note 12 / Label 10。**10sp は、にじんだ画面と乱視の目では字形が崩れる。**
 * 読めない字は、洗練ではなく欠陥である。下限を 12sp に上げ、上の二段は据え置いた
 * ——大きな数字まで膨らませると面が野暮になり、階層の対比も消える。
 * 濃さも同時に上げてある（`ObservatoryColors.Faint` の注記）。
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
    val Body = 16.sp
    val Note = 14.sp
    val Label = 12.sp
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
/**
 * 書体。**欧文と数字は IBM Plex、和文は端末の Noto Sans CJK**（2026-09-25）。
 *
 * ## ⚠️ 端末の既定書体をやめた理由
 *
 * 以前は `FontFamily.SansSerif`（Roboto）と `FontFamily.Monospace`（Droid Sans Mono）だった。
 * とくに等幅の Droid Sans Mono は字形が素朴で、目盛り・札・時計のすべてに出ていたので、
 * **面の印象の半分をこの書体が決めていた。**
 *
 * IBM Plex を選んだのは、計器と製図の書体として作られており（「観測所」の性格に合う）、
 * Sans と Mono が一つの設計で揃うから。**Plex Sans の数字は既定で等幅**（全桁 600/1000）なので、
 * 大きな数字は Sans で組んでも「数字は等幅」の約束が守られる。
 *
 * 和字は同梱しない——Plex Sans JP は一書体で数 MB ある。Android は字ごとに書体を
 * 引き継ぐので、和字は端末の Noto Sans CJK で出る。ライセンスは OFL（`third_party/IBM_Plex_OFL.txt`）。
 */
@OptIn(ExperimentalTextApi::class)
object ObservatoryFonts {
    private fun sans(weight: Int) = Font(
        resId = R.font.plex_sans,
        weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
    )

    val Sans = FontFamily(sans(300), sans(400), sans(500), sans(600), sans(700))

    /**
     * 細身（字幅 85）。**像の下の名だけ**に使う（2026-09-25）。
     *
     * 72dp の枡に 12sp の名を置くと「Audio Con…」「NinjaMust…」と切れた。Plex Sans は
     * 字幅の軸（75〜100）を持つので、字を小さくせずに一字か二字ぶん多く入る。和字は端末の書体のまま。
     */
    val SansCondensed = FontFamily(
        Font(
            resId = R.font.plex_sans,
            weight = FontWeight.Normal,
            variationSettings = FontVariation.Settings(FontVariation.weight(400), FontVariation.width(85f)),
        ),
    )

    val Mono = FontFamily(
        Font(R.font.plex_mono_regular, FontWeight.Normal),
        Font(R.font.plex_mono_medium, FontWeight.Medium),
    )
}

/**
 * 組版規定を文字の様式として返す。**描く字も、測る字も、ここから取る。**
 *
 * ⚠️ 図の目盛りは、字の幅を「10sp の一字＝6.6dp」と見積もって軸の幅を決めていた。
 * 「分」のような漢字が混じると見積りより広く、「1500…」と切れた（2026/9/25）。
 * 字を大きくすればすべての見積りが外れる。**測る側も同じ様式を使えば、外れようがない。**
 */
fun observatoryStyle(
    size: TextUnit,
    mono: Boolean = false,
    tracking: Float? = null,
    weight: FontWeight? = null,
    center: Boolean = false,
    condensed: Boolean = false,
): TextStyle = TextStyle(
    fontSize = size,
    fontWeight = if (condensed) FontWeight.Normal else weight ?: weightFor(size),
    // 等幅は小さな字（目盛り・札）だけ。大きな数字は Sans で組む——Plex Sans の数字は
    // 既定で等幅なので桁は揃い、Mono より字形が締まる（[ObservatoryFonts]）。
    fontFamily = when {
        condensed -> ObservatoryFonts.SansCondensed
        mono && size.value < 20f -> ObservatoryFonts.Mono
        else -> ObservatoryFonts.Sans
    },
    letterSpacing = (size.value * (tracking ?: trackingFor(size))).sp,
    textAlign = if (center) TextAlign.Center else TextAlign.Unspecified,
)

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
    /** 細身の書体（[ObservatoryFonts.SansCondensed]）。像の下の名だけに使う。 */
    condensed: Boolean = false,
) {
    Text(
        text = text,
        color = color,
        style = observatoryStyle(size, mono, tracking, weight, center, condensed),
        maxLines = maxLines,
        // 入りきらないものは折り返さず省略記号で切る。長い曲名のような
        // 「たまたま長い値」に面の高さを譲ると、他の情報が押し出される。
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * 欧文の札。**枠の名にだけ使う**（面・節・小見出し。[Lexicon] の表）。
 *
 * 大文字・等幅・字間 +0.14。Label の段に置き、読ませずに**形で覚えさせる**。
 * 字間を開けるのは、大文字だけの並びを詰めると一塊の模様になり、にじんだ画面で潰れるから。
 */
@Composable
fun ObservatoryTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = ObservatoryColors.Faint,
) {
    ObservatoryText(
        text = text.uppercase(),
        color = color,
        size = ObservatoryType.Label,
        modifier = modifier,
        mono = true,
        tracking = 0.14f,
    )
}
