package com.yuma.observatorylauncher.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.sqrt

/**
 * 単色の像。**地より少し明るい円に、アプリの印（ロゴ）を紙色で描く。**
 *
 * > 「アプリアイコンの色が変えれるならデザインを統一させて、かつそのアプリが何かもわかるように」
 * > （本人・2026-09-25）
 *
 * 見分けの手掛かりは**色ではなく形**に預ける。色を揃えても、ロゴの形は残る。
 *
 * ## 印の取り出し方（二通り）
 *
 * 1. **アプリが単色版を持っていれば、それを使う**（Android 13 のテーマアイコン）。
 *    アプリ自身が「単色ならこの形」と描いたものなので、最もきれいに出る。
 * 2. 持っていなければ**像から抜き出す**。像の縁の色を「地の色」とみなし、
 *    地の色から離れている画素ほど濃く残す——白地に赤いロゴでも、赤地に白いロゴでも、
 *    ロゴの側が残る。抜き出した面が広すぎる（写真のような像）ときは、明るさで代える。
 */
internal object IconMono {
    /** 印の色（紙）。 */
    private const val INK = 0xFFE7E4DE.toInt()

    /** 円の色（地に紙を 9% 重ねた色）。地から浮くが、印より前に出ない。 */
    private val PLATE = blend(0xFF22303C.toInt(), INK, 0.09f)

    /** @param label アプリの名。印が抜き出せないとき、頭文字を描くのに使う。 */
    fun render(drawable: Drawable, sizePx: Int, label: String = ""): ImageBitmap {
        val glyph = monochromeLayer(drawable, sizePx) ?: extracted(drawable, sizePx) ?: initial(label, sizePx)
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val r = sizePx / 2f
        canvas.drawCircle(r, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLATE })
        canvas.drawBitmap(
            glyph,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = PorterDuffColorFilter(INK, PorterDuff.Mode.SRC_IN)
            },
        )
        glyph.recycle()
        return out.asImageBitmap()
    }

    /** アプリ自身の単色版。適応型の層は 108dp 描いて中の 72dp を見せる作りなので、1.5 倍に敷く。 */
    private fun monochromeLayer(drawable: Drawable, sizePx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val mono = (drawable as? AdaptiveIconDrawable)?.monochrome ?: return null
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val extra = sizePx / 4
        mono.setBounds(-extra, -extra, sizePx + extra, sizePx + extra)
        mono.draw(Canvas(out))
        // ⚠️ アプリの単色版が**絵をそのまま入れただけ**のことがある（Technics Audio Connect）。
        // 紙色で塗ると四角ごと塗りつぶされるので、円の半分を超えて塗られていたら使わず、抜き出しに回す。
        val c = sizePx / 2f
        var inside = 0
        var painted = 0
        for (y in 0 until sizePx) for (x in 0 until sizePx) {
            if (hypot(x + 0.5f - c, y + 0.5f - c) > c * 0.9f) continue
            inside++
            if (Color.alpha(out.getPixel(x, y)) > 128) painted++
        }
        if (inside > 0 && painted > inside * 0.55f) {
            out.recycle()
            return null
        }
        return out
    }

    /** 像から印を抜き出す。返すのは濃さだけを持つ絵（色は描くときに紙へ揃える）。 */
    private fun extracted(drawable: Drawable, sizePx: Int): Bitmap? {
        val src = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(Canvas(src))
        val px = IntArray(sizePx * sizePx)
        src.getPixels(px, 0, sizePx, 0, 0, sizePx, sizePx)
        src.recycle()

        val c = sizePx / 2f
        // 地の色：縁の近く（半径の 80〜88%）の輪の色の中央値。
        //
        // ⚠️ 縁が透けている像（四角い絵を小さく置いた古い形式）では輪が空になり、
        // 四角ごと印として残った（Technics・2026-09-25 実機）。そのときは
        // **中の不透明な画素で最も多い色**を地とみなす——地は最も広い色だからである。
        val ring = ArrayList<Int>()
        var ringAll = 0
        for (y in 0 until sizePx) for (x in 0 until sizePx) {
            val d = hypot(x + 0.5f - c, y + 0.5f - c) / c
            if (d in 0.80f..0.88f) {
                ringAll++
                if (Color.alpha(px[y * sizePx + x]) > 200) ring += px[y * sizePx + x]
            }
        }
        val ground = if (ring.size > ringAll / 2) median(ring) else commonest(px)

        // 地から離れた画素を印とみなす。**薄い印（白地に淡い灰の字など）**は最初の閾値では拾えないので、
        // 三倍ほど敏感な閾値でもう一度だけ試す。
        fun attempt(e0: Float, e1: Float): Pair<FloatArray, Int> {
            val alpha = FloatArray(px.size)
            var covered = 0
            for (y in 0 until sizePx) for (x in 0 until sizePx) {
                val i = y * sizePx + x
                val d = hypot(x + 0.5f - c, y + 0.5f - c) / c
                if (d > 0.90f) continue // 円の縁そのものは印ではない
                val p = px[i]
                val a = Color.alpha(p) / 255f
                val v = if (ground == null) a else a * smooth(e0, e1, distance(p, ground))
                alpha[i] = v
                if (v > 0.5f) covered++
            }
            return alpha to covered
        }
        var inside = 0
        for (y in 0 until sizePx) for (x in 0 until sizePx) {
            if (hypot(x + 0.5f - c, y + 0.5f - c) / c <= 0.90f) inside++
        }
        var (alpha, covered) = attempt(0.10f, 0.30f)
        if (covered < inside * 0.02f) {
            val second = attempt(0.03f, 0.10f)
            alpha = second.first
            covered = second.second
        }
        // ⚠️ それでも拾えないなら**明るさで代えない**。明るさで代えると白い地がそのまま
        // 塊になり、「白い四角」が出た（Technics・2026-09-25 実機）。頭文字に任せる。
        if (covered < inside * 0.02f) return null
        // 印が面の七割を超えるなら、抜き出しに失敗している（写真・全面の絵柄）。明るさで代える。
        val useLuma = ground == null || covered > inside * 0.70f
        val out = IntArray(px.size)
        for (i in px.indices) {
            val y = i / sizePx
            val x = i % sizePx
            val d = hypot(x + 0.5f - c, y + 0.5f - c) / c
            if (d > 0.90f) continue
            val v = if (useLuma) {
                val p = px[i]
                (Color.alpha(p) / 255f) * smooth(0.35f, 0.85f, luma(p))
            } else {
                alpha[i]
            }
            out[i] = Color.argb((v * 255).toInt().coerceIn(0, 255), 255, 255, 255)
        }
        return Bitmap.createBitmap(out, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    }

    /** 印が抜き出せない像の代わり。**アプリ名の頭文字**を印として描く。 */
    private fun initial(label: String, sizePx: Int): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val ch = label.trim().firstOrNull()?.uppercase() ?: return out
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = sizePx * 0.46f
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF, android.graphics.Typeface.BOLD)
        }
        val y = sizePx / 2f - (paint.descent() + paint.ascent()) / 2f
        Canvas(out).drawText(ch, sizePx / 2f, y, paint)
        return out
    }

    private fun hypot(x: Float, y: Float) = sqrt(x * x + y * y)

    private fun distance(a: Int, b: Int): Float {
        val dr = (Color.red(a) - Color.red(b)) / 255f
        val dg = (Color.green(a) - Color.green(b)) / 255f
        val db = (Color.blue(a) - Color.blue(b)) / 255f
        return sqrt((dr * dr + dg * dg + db * db) / 3f)
    }

    private fun luma(p: Int) = (0.2126f * Color.red(p) + 0.7152f * Color.green(p) + 0.0722f * Color.blue(p)) / 255f

    private fun smooth(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3 - 2 * t)
    }

    /** 不透明な画素のうち最も多い色（各成分を 32 段に丸めて数える）。無ければ null。 */
    private fun commonest(px: IntArray): Int? {
        val counts = HashMap<Int, Int>()
        px.forEach { p ->
            if (Color.alpha(p) > 200) {
                val q = ((Color.red(p) shr 3) shl 10) or ((Color.green(p) shr 3) shl 5) or (Color.blue(p) shr 3)
                counts[q] = (counts[q] ?: 0) + 1
            }
        }
        val q = counts.maxByOrNull { it.value }?.key ?: return null
        return Color.rgb(((q shr 10) and 31) shl 3, ((q shr 5) and 31) shl 3, (q and 31) shl 3)
    }

    private fun median(colors: List<Int>): Int {
        fun mid(f: (Int) -> Int) = colors.map(f).sorted()[colors.size / 2]
        return Color.rgb(mid(Color::red), mid(Color::green), mid(Color::blue))
    }

    private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
    )
}
