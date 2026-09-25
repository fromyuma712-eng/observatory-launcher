package com.yuma.observatorylauncher.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors

/**
 * 再生の操作の記号。**字ではなく図形で描く。**
 *
 * ⚠️ 以前は「◂◂ ▮▮ ▶ ▸▸」を字として置いていた。これらは Plex にも Roboto にも無く、
 * 端末が別の書体から借りてくるので、**三つの記号で大きさも太さも揃わなかった**。
 * 図形で描けば揃い、直線だけで組めるので体系の語彙（直角）にも収まる。
 *
 * 再生と一時停止は**形が移り変わる**——二本の棒が、左右に割った三角形の半分ずつへ
 * 頂点を動かして三角になる。押したことが、記号そのものの動きで返る（2026-09-25）。
 */
internal enum class TransportKind { PREVIOUS, NEXT }

@Composable
internal fun PlayPauseGlyph(playing: Boolean, size: Dp = 28.dp, color: Color = ObservatoryColors.Paper) {
    // 0 = 一時停止の記号（鳴っている）、1 = 再生の記号（止まっている）。
    val p by animateFloatAsState(
        targetValue = if (playing) 0f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "playPause",
    )
    Canvas(modifier = Modifier.size(size)) {
        fun quad(from: List<Offset>, to: List<Offset>) {
            val path = Path()
            from.indices.forEach { i ->
                val pt = from[i] + (to[i] - from[i]) * p
                val x = pt.x * this.size.width
                val y = pt.y * this.size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            drawPath(path, color)
        }
        quad(PAUSE_LEFT, PLAY_LEFT)
        quad(PAUSE_RIGHT, PLAY_RIGHT)
    }
}

@Composable
internal fun TransportGlyph(kind: TransportKind, size: Dp = 24.dp, color: Color = ObservatoryColors.Paper) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        fun x(v: Float) = if (kind == TransportKind.NEXT) v * w else (1f - v) * w
        val triangle = Path().apply {
            moveTo(x(0.12f), 0.16f * h)
            lineTo(x(0.70f), 0.50f * h)
            lineTo(x(0.12f), 0.84f * h)
            close()
        }
        drawPath(triangle, color)
        val bar = Path().apply {
            moveTo(x(0.74f), 0.16f * h)
            lineTo(x(0.88f), 0.16f * h)
            lineTo(x(0.88f), 0.84f * h)
            lineTo(x(0.74f), 0.84f * h)
            close()
        }
        drawPath(bar, color)
    }
}

// 単位枡（0〜1）の頂点。左上・右上・右下・左下の順に対応させてある。
private val PAUSE_LEFT = listOf(Offset(0.20f, 0.12f), Offset(0.42f, 0.12f), Offset(0.42f, 0.88f), Offset(0.20f, 0.88f))
private val PAUSE_RIGHT = listOf(Offset(0.58f, 0.12f), Offset(0.80f, 0.12f), Offset(0.80f, 0.88f), Offset(0.58f, 0.88f))
private val PLAY_LEFT = listOf(Offset(0.24f, 0.10f), Offset(0.57f, 0.30f), Offset(0.57f, 0.70f), Offset(0.24f, 0.90f))
private val PLAY_RIGHT = listOf(Offset(0.57f, 0.30f), Offset(0.90f, 0.50f), Offset(0.90f, 0.50f), Offset(0.57f, 0.70f))
