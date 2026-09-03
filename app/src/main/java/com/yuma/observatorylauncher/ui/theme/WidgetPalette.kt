package com.yuma.observatorylauncher.ui.theme

import android.R
import android.graphics.Color
import android.util.SparseIntArray

/**
 * 置いたウィジェットへ渡す配色。
 *
 * **ウィジェットの中身は提供元アプリが描くので、こちらでは触れない。**
 * `RemoteViews` は向こうの process で組み立てられ、こちらは大きさを与えて
 * 置くだけである。字も罫も塗りも、向こうの持ち物である。
 *
 * ただし Android 12 以降、**動的配色の色だけはホストが差し替えられる**
 * （[android.appwidget.AppWidgetHostView.setColorResources]）。
 * 端末の壁紙から作られる配色を、こちらの配色に置き換えて渡せる。
 *
 * だから「02 を適用する」は、正確には**02 の色を動的配色として渡す**ことになる。
 * 効くのは動的配色を使って作られたウィジェットだけで、
 * 色を直に書いているものは変わらない。予定の帯のように
 * **利用者が決めた色**（暦ごとの色）も変わらない——変えてはならない。
 *
 * ## 効かない相手がいる（実測）
 *
 * **Google カレンダーの月表示には効かなかった。**
 * 渡す前と後で面の色を測ると、どちらも `#2F3133` で一片も動かない。
 * ここで差し替えられるのは Android 12 の色調表（accent1〜3・neutral1〜2 の
 * 六十五色）だけで、Android 14 で入った `system_surface_*` のような
 * **役割の色は対象外**である。あちらはそちらで組まれていると見てよい。
 *
 * それでも渡し続けるのは、Glance で組まれたウィジェットのように
 * 色調表を使うものには効くからで、渡す費用はほぼ無い。
 * **ただし「渡せば 02 になる」とは思わないこと。**
 * 面ごと 02 にしたければ、借りるのをやめて**自分で描く**しかない。
 *
 * ## 渡し方
 *
 * 動的配色は五つの系統（accent1〜3・neutral1〜2）× 十三段の明度で持つ。
 * 段は 0 が最も明るく、1000 が最も暗い。ここでは——
 *
 * - **無彩の二系統は、紙(0)から鉄紺(1000)へ渡す。**
 *   白と黒の代わりに、この体系の地と字を両端に置く。
 *   明るい面で組まれたウィジェットは紙の上に鉄紺で、暗い面で組まれたものは
 *   鉄紺の上に紙で描かれる。**どちらに転んでも 02 の対になる。**
 * - **有彩の三系統は燠火の濃淡。** 中ほど(400)が燠火そのものである。
 */
internal object WidgetPalette {

    /** 段の刻み。0 が最も明るく 1000 が最も暗い（Android の定めた並び）。 */
    private val SHADES = intArrayOf(0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000)

    private const val PAPER = 0xFFE7E4DE.toInt()
    private const val GROUND = 0xFF22303C.toInt()
    private const val EMBER = 0xFFE4572E.toInt()

    /** 燠火の最も明るい端。紙に燠火をひと匙落とした色。 */
    private const val EMBER_LIGHT = 0xFFFBEDE7.toInt()

    /** 燠火の最も暗い端。鉄紺に燠火を溶かした色。 */
    private const val EMBER_DARK = 0xFF2A1109.toInt()

    fun observatory(): SparseIntArray {
        val out = SparseIntArray(SHADES.size * 5)
        SHADES.forEachIndexed { at, shade ->
            val t = shade / 1000f
            // 無彩：紙 → 鉄紺。neutral2 はわずかに地寄りにして、面の重なりを作る。
            out.put(neutral1(at), lerp(PAPER, GROUND, t))
            out.put(neutral2(at), lerp(PAPER, GROUND, minOf(1f, t + 0.06f)))
            // 有彩：明るい端 → 燠火 → 暗い端。400 が燠火そのもの。
            val ember = if (t <= 0.4f) {
                lerp(EMBER_LIGHT, EMBER, t / 0.4f)
            } else {
                lerp(EMBER, EMBER_DARK, (t - 0.4f) / 0.6f)
            }
            out.put(accent1(at), ember)
            // 二次・三次は彩度を落とす。**燠火は 12% までという約束**を、
            // 面の広いところへ回さないための下げ幅である。
            out.put(accent2(at), desaturate(ember, 0.55f))
            out.put(accent3(at), desaturate(ember, 0.30f))
        }
        return out
    }

    private fun lerp(from: Int, to: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        fun mix(a: Int, b: Int) = (a + (b - a) * t).toInt().coerceIn(0, 255)
        return Color.argb(
            255,
            mix(Color.red(from), Color.red(to)),
            mix(Color.green(from), Color.green(to)),
            mix(Color.blue(from), Color.blue(to)),
        )
    }

    /** 明るさは保ったまま、色みだけを弱める。 */
    private fun desaturate(color: Int, keep: Float): Int {
        val grey = (Color.red(color) * 0.299f +
            Color.green(color) * 0.587f +
            Color.blue(color) * 0.114f).toInt()
        return lerp(Color.argb(255, grey, grey, grey), color, keep)
    }

    // 資源の識別子は連番で並んでいないので、素直に並べて引く。
    private fun neutral1(at: Int) = NEUTRAL1[at]
    private fun neutral2(at: Int) = NEUTRAL2[at]
    private fun accent1(at: Int) = ACCENT1[at]
    private fun accent2(at: Int) = ACCENT2[at]
    private fun accent3(at: Int) = ACCENT3[at]

    private val NEUTRAL1 = intArrayOf(
        R.color.system_neutral1_0, R.color.system_neutral1_10, R.color.system_neutral1_50,
        R.color.system_neutral1_100, R.color.system_neutral1_200, R.color.system_neutral1_300,
        R.color.system_neutral1_400, R.color.system_neutral1_500, R.color.system_neutral1_600,
        R.color.system_neutral1_700, R.color.system_neutral1_800, R.color.system_neutral1_900,
        R.color.system_neutral1_1000,
    )
    private val NEUTRAL2 = intArrayOf(
        R.color.system_neutral2_0, R.color.system_neutral2_10, R.color.system_neutral2_50,
        R.color.system_neutral2_100, R.color.system_neutral2_200, R.color.system_neutral2_300,
        R.color.system_neutral2_400, R.color.system_neutral2_500, R.color.system_neutral2_600,
        R.color.system_neutral2_700, R.color.system_neutral2_800, R.color.system_neutral2_900,
        R.color.system_neutral2_1000,
    )
    private val ACCENT1 = intArrayOf(
        R.color.system_accent1_0, R.color.system_accent1_10, R.color.system_accent1_50,
        R.color.system_accent1_100, R.color.system_accent1_200, R.color.system_accent1_300,
        R.color.system_accent1_400, R.color.system_accent1_500, R.color.system_accent1_600,
        R.color.system_accent1_700, R.color.system_accent1_800, R.color.system_accent1_900,
        R.color.system_accent1_1000,
    )
    private val ACCENT2 = intArrayOf(
        R.color.system_accent2_0, R.color.system_accent2_10, R.color.system_accent2_50,
        R.color.system_accent2_100, R.color.system_accent2_200, R.color.system_accent2_300,
        R.color.system_accent2_400, R.color.system_accent2_500, R.color.system_accent2_600,
        R.color.system_accent2_700, R.color.system_accent2_800, R.color.system_accent2_900,
        R.color.system_accent2_1000,
    )
    private val ACCENT3 = intArrayOf(
        R.color.system_accent3_0, R.color.system_accent3_10, R.color.system_accent3_50,
        R.color.system_accent3_100, R.color.system_accent3_200, R.color.system_accent3_300,
        R.color.system_accent3_400, R.color.system_accent3_500, R.color.system_accent3_600,
        R.color.system_accent3_700, R.color.system_accent3_800, R.color.system_accent3_900,
        R.color.system_accent3_1000,
    )
}
