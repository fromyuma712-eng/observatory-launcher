package com.yuma.observatorylauncher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens
import java.io.IOException

private const val PREFS_NAME = "observatory_launcher"
private const val KEY_WALLPAPER_APPLIED = "wallpaper_applied_v5"

/**
 * OBSERVATORYの地色(ground)+ドットグリッドをシステム壁紙として一度だけ適用する。
 * アプリ切り替え(Overview)の背景はOS側(SystemUI)が常にシステム壁紙を描くため、
 * ランチャー自身の見た目ではなく壁紙側をテーマに合わせるのが正攻法。
 *
 * ロック画面は第三者アプリが置き換えられない(キーガードはOSの保護対象)。
 * 手を入れられるのは壁紙だけなので、ロック側には弦材(§N-7)を一本通した版を当て、
 * 施錠中でも同じ体系の面であることが分かるようにする。
 *
 * 色・寸法はObservatoryColors/ObservatoryDimensをそのまま参照し、値を二重管理しない。
 * パレット変更時は KEY_WALLPAPER_APPLIED のバージョン番号を上げて再適用させる。
 */
fun applyObservatoryWallpaperIfNeeded(context: Context) {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    if (prefs.getBoolean(KEY_WALLPAPER_APPLIED, false)) return

    val metrics = context.resources.displayMetrics
    val width = metrics.widthPixels
    val height = metrics.heightPixels
    val density = metrics.density

    val home = observatoryField(width, height, density, truss = false)
    val lock = observatoryDial(width, height, density)

    try {
        val manager = WallpaperManager.getInstance(context)
        manager.setBitmap(home, null, true, WallpaperManager.FLAG_SYSTEM)
        manager.setBitmap(lock, null, true, WallpaperManager.FLAG_LOCK)
        prefs.edit { putBoolean(KEY_WALLPAPER_APPLIED, true) }
    } catch (_: IOException) {
        // 壁紙設定に失敗してもホーム画面自体の起動は継続する
    } finally {
        home.recycle()
        lock.recycle()
    }
}

/**
 * ロック画面の壁紙——**弧と地平**（Claude Design の別案 2d より）。
 *
 * ## なぜこの案か
 *
 * ロック画面の意匠は四案あったが、実装できるのは**壁紙として成り立つもの**だけである。
 * ロック画面そのもの（時計・下端の近道）は OS の保護対象で、
 * こちらから置き換えられない。だから「電池・気温・歩数を並べた案」は初めから外れる
 * ——**描けないものを選ばない。**
 *
 * 残る三案のうち、
 *
 * | 案 | 見送った理由 |
 * | --- | --- |
 * | 柱状節理 | 縦の柱が密で、OS の時計と通知の列と competing する |
 * | 47 の枡 | 相性表から来た格子で、施錠の面と意味がつながらない |
 * | **弧と地平** | 中ほどだけを使い、上（時計）と下（近道）を空けている |
 *
 * 弧と地平は元から「背景だけを壁紙にした版」として描かれていた。
 * **壁紙として作られたものを、壁紙に使う。**
 *
 * ## 図の読み
 *
 * 地平線に沿って 04・13・22 の刻みがあり、弧は空の高さを表す。
 * 燠火の射線が一本だけ伸びて、格点で終わる——**「今」を指す一点**である。
 * この体系で燠火が担うのは常にその一語だけなので、施錠中でも読み方は変わらない。
 *
 * ⚠️ 下端の弦材からは**斜材を外してある**。
 * 面の中で斜材が「無名の折れ線グラフ」に見えたので取り除いた（2026/9/2）。
 * 壁紙にだけ残すと、同じ形が二つの意味を持つことになる。
 */
private fun observatoryDial(width: Int, height: Int, density: Float): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(ObservatoryColors.Ground.toArgb())

    val paper = ObservatoryColors.Paper.toArgb() and 0x00FFFFFF
    val ember = ObservatoryColors.Ember.toArgb() and 0x00FFFFFF
    fun paperAt(alpha: Double) = ((alpha * 255).toInt() shl 24) or paper
    fun emberAt(alpha: Double) = ((alpha * 255).toInt() shl 24) or ember

    // 地の点格子。面と同じ間隔なので、施錠中と解錠後で地続きに見える。
    val dots = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = paperAt(0.13) }
    val spacing = ObservatoryDimens.DotGrid.value * density
    var gy = spacing / 2f
    while (gy < height) {
        var gx = spacing / 2f
        while (gx < width) {
            canvas.drawCircle(gx, gy, 2f, dots)
            gx += spacing
        }
        gy += spacing
    }

    // 縦横は「1080×2400 の意匠」を割合に直して置く。**画面の大きさに依らない。**
    val w = width.toFloat()
    val h = height.toFloat()
    fun px(x: Double) = (x / 1080.0 * w).toFloat()
    fun py(y: Double) = (y / 2400.0 * h).toFloat()

    val hair = 2f * density / 2.75f
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = hair
    }

    val horizon = py(1900.0)

    // 弧は二重。外は空の高さ、内は控えの線。
    listOf(500.0 to 0.30, 390.0 to 0.14).forEach { (radius, alpha) ->
        stroke.color = paperAt(alpha)
        val r = px(radius)
        canvas.drawArc(
            px(540.0) - r,
            horizon - r,
            px(540.0) + r,
            horizon + r,
            180f,
            180f,
            false,
            stroke,
        )
    }

    // 地平線と、その上の刻み。
    //
    // ⚠️ **端まで引かない。** 端末は壁紙をわずかに拡大して敷くので、
    // 端に置いた線と数字は切れる（実機で「04」「22」が欠けた・2026/9/2）。
    // 面の左右余白（Gutter）と同じだけ内へ寄せる。
    stroke.color = paperAt(0.55)
    canvas.drawLine(px(60.0), horizon, px(1020.0), horizon, stroke)
    canvas.drawLine(px(60.0), py(1880.0), px(60.0), horizon, stroke)
    canvas.drawLine(px(1020.0), py(1880.0), px(1020.0), horizon, stroke)
    stroke.color = paperAt(0.30)
    canvas.drawLine(px(540.0), py(1380.0), px(540.0), py(1410.0), stroke)

    // 燠火の射線。**一本だけ**——この体系で燠火が担うのは「今」の一語である。
    stroke.color = emberAt(0.55)
    canvas.drawLine(px(540.0), horizon, px(960.0), py(1762.0), stroke)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ObservatoryColors.Ember.toArgb() }
    canvas.drawRect(px(954.0), py(1756.0), px(966.0), py(1768.0), fill)

    // 刻みの数字。等幅・薄く。**読ませはしないが、目盛りであることは示す。**
    val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paperAt(0.30)
        typeface = android.graphics.Typeface.MONOSPACE
        textSize = px(26.0)
    }
    canvas.drawText("04", px(60.0), py(1950.0), label)
    canvas.drawText("13", px(522.0), py(1352.0), label)
    canvas.drawText("22", px(972.0), py(1950.0), label)

    // 下端の弦材。**斜材は引かない**——面から外した形を、壁紙にだけ残さない。
    stroke.color = paperAt(0.35)
    val trussTop = py(2140.0)
    val trussBottom = py(2225.0)
    canvas.drawLine(px(60.0), trussTop, px(1020.0), trussTop, stroke)
    canvas.drawLine(px(60.0), trussBottom, px(1020.0), trussBottom, stroke)
    val anchor = px(12.0)
    val mid = (trussTop + trussBottom) / 2f
    listOf(380.0, 700.0).forEach { at ->
        canvas.drawRect(
            px(at) - anchor / 2f,
            mid - anchor / 2f,
            px(at) + anchor / 2f,
            mid + anchor / 2f,
            fill,
        )
    }

    // 面の頭と同じ 12% の帯。**三枚の面と壁紙が、同じ道具であることの印。**
    canvas.drawRect(0f, 0f, w * 0.12f, py(6.0).coerceAtLeast(3f), fill)
    return bitmap
}

/** 地＋ドットグリッド。[truss] が真なら§N-7の弦材と斜材を一本だけ通す。 */
private fun observatoryField(
    width: Int,
    height: Int,
    density: Float,
    truss: Boolean,
): Bitmap {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(ObservatoryColors.Ground.toArgb())

    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ObservatoryColors.Line.toArgb()
    }

    val spacingPx = ObservatoryDimens.DotGrid.value * density
    var y = 0f
    while (y < height) {
        var x = 0f
        while (x < width) {
            canvas.drawCircle(x, y, 1f, linePaint)
            x += spacingPx
        }
        y += spacingPx
    }

    if (truss) {
        val margin = ObservatoryDimens.Gutter.value * density
        val top = height * 0.55f
        val h = ObservatoryDimens.TrussHeight.value * density
        val half = ObservatoryDimens.TrussPanel.value * density / 2f
        val hair = ObservatoryDimens.Hairline.value * density
        val anchor = ObservatoryDimens.Anchor.value * density
        val right = width - margin

        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ObservatoryColors.Line.toArgb()
            strokeWidth = hair
        }
        canvas.drawLine(margin, top, right, top, stroke)
        canvas.drawLine(margin, top + h, right, top + h, stroke)

        var x = margin
        var atTop = false
        while (x < right) {
            val nx = (x + half).coerceAtMost(right)
            val y0 = if (atTop) top else top + h
            val y1 = if (atTop) top + h else top
            val t = (nx - x) / half
            canvas.drawLine(x, y0, nx, y0 + (y1 - y0) * t, stroke)
            canvas.drawRect(
                x - anchor / 2f,
                y0 - anchor / 2f,
                x + anchor / 2f,
                y0 + anchor / 2f,
                linePaint,
            )
            x = nx
            atTop = !atTop
        }
    }
    return bitmap
}
