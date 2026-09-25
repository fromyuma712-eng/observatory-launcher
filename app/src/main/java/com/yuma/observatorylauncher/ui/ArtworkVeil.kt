package com.yuma.observatorylauncher.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Canvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens

/**
 * ジャケットを伏せる。
 *
 * > 「音楽の画面を表示したいけど公共の場では見せたくないなという時に使います」
 * > （本人・2026/9/25）
 *
 * ## 決めたこと
 *
 * - **伏せるのは絵だけ。** 題・演者・操作は残す——曲を変えたり止めたりする面であることは
 *   変わらないので、それまで隠すと面の役目が無くなる。
 * - **端末に覚えさせる。** 外で伏せたまま画面を切り、次に開いたとき絵が出てしまっては
 *   意味が無い。解くのは本人が解いたときだけ。
 * - **絵の代わりに色面を置く**（[ColorField]）。はじめはぼかしたが、ぼけた絵は
 *   かえって目を引いた（本人の指摘）。
 */
internal object ArtworkVeil {
    private const val PREFS = "observatory_launcher"
    private const val KEY = "artwork_veiled"

    fun read(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun write(context: Context, veiled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY, veiled) }
    }
}

/** 伏せているかと、切り替える手。 */
internal class VeilState(initial: Boolean, private val context: Context) {
    var veiled by mutableStateOf(initial)
        private set

    fun toggle() {
        veiled = !veiled
        ArtworkVeil.write(context, veiled)
    }
}

@Composable
internal fun rememberVeil(): VeilState {
    val context = LocalContext.current
    return remember { VeilState(ArtworkVeil.read(context), context.applicationContext) }
}

/**
 * 伏せた絵の代わりの**色面**。絵から採った色（`Artwork.Loaded.stripe`）を四色に絞って
 * 上から下へ滑らかにつなぎ、この体系の点格子を重ねる。
 *
 * ## ⚠️ ぼかしをやめた理由（2026-09-25）
 *
 * > 「音楽のブラーについてですが、逆に気になるので隠し方としていい方法を探してください」（本人）
 *
 * ぼかした絵は「何かを隠している」ことを強く主張する——ぼやけた人の顔や文字の塊は、
 * 見えないぶん見たくなる。色面なら**中身は一片も残らず、しかも隠したようには見えない**。
 * 曲ごとに色が変わるので、面は曲に合わせて表情を変え続ける。
 */
@Composable
internal fun ColorField(colors: IntArray?, modifier: Modifier = Modifier) {
    val ground = ObservatoryColors.Ground
    Canvas(modifier = modifier) {
        val picked = colors?.takeIf { it.isNotEmpty() }?.let { all ->
            // ⚠️ 採った色を全部帯にしたら、細い縦縞が数十本並んでバーコードのように騒がしかった
            // （実機・2026-09-25）。**四色に絞り、明るい色を上・暗い色を下にして滑らかにつなぐ。**
            val step = (all.size / 4).coerceAtLeast(1)
            all.filterIndexed { i, _ -> i % step == 0 }.take(4)
                .sortedByDescending { c -> 0.2126f * ((c shr 16) and 0xFF) + 0.7152f * ((c shr 8) and 0xFF) + 0.0722f * (c and 0xFF) }
                .map { lerp(Color(it), ground, 0.30f) }
        }
        when {
            picked == null -> drawRect(ground)
            picked.size == 1 -> drawRect(picked.first())
            else -> drawRect(Brush.verticalGradient(picked))
        }
        // 点格子。**面の地と同じ語彙**で覆うので、「隠した」ではなく「この体系の面」に見える。
        val step = 12.dp.toPx()
        val dot = 1.5.dp.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) {
                drawRect(ObservatoryColors.Paper.copy(alpha = 0.16f), Offset(x, y), Size(dot, dot))
                x += step
            }
            y += step
        }
    }
}

/**
 * 伏せの切り替え。**音の面の頭（出力先の行）の右端に置く。**
 *
 * 格点が燠火なら伏せている。字は欧文の札（[Lexicon]）。押し所は 48dp を下回らせない。
 */
@Composable
internal fun VeilToggle(state: VeilState, modifier: Modifier = Modifier) {
    val on = state.veiled
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = PressIndication.Item,
                onClick = state::toggle,
            )
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(ObservatoryDimens.Anchor)
                .then(
                    if (on) {
                        Modifier.background(ObservatoryColors.Ember)
                    } else {
                        Modifier.border(ObservatoryDimens.Hairline, ObservatoryColors.LineStrong)
                    },
                ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        ObservatoryTag(
            // 表記は絵の状態（出ている＝ON）。伏せているときだけ燠火で知らせる。
            text = Lexicon.ART + " " + if (on) Lexicon.OFF else Lexicon.ON,
            color = if (on) ObservatoryColors.EmberText else ObservatoryColors.Muted,
        )
    }
}

