package com.yuma.observatorylauncher.ui.theme

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity

/**
 * observatory_template.html の24pxドットグリッドをCompose側に移植した最初の「型」。
 * 個々の画面がドットを直接描かず、この一箇所だけを使う。
 *
 * 実装は1マス分の画像を作って敷き詰める。全画面に千数百個の円を毎フレーム描く
 * 素朴な実装は、地(常に全面に在るもの)の代償として重すぎた——地は最も安く在るべき。
 */
@Composable
fun ObservatoryDotGrid(modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val spacing = with(density) { ObservatoryDimens.DotGrid.roundToPx().coerceAtLeast(1) }

    val brush = remember(spacing) {
        val tile = Bitmap.createBitmap(spacing, spacing, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(tile)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ObservatoryColors.Line.toArgb()
        }
        // マスの中央に打つ。角に打つと継ぎ目で四分の一しか出ない。
        canvas.drawCircle(spacing / 2f, spacing / 2f, 1f, paint)
        ShaderBrush(
            ImageShader(tile.asImageBitmap(), TileMode.Repeated, TileMode.Repeated)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(ObservatoryColors.Ground)
            .background(brush)
    )
}
