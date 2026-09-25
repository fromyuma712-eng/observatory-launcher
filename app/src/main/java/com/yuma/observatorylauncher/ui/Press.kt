package com.yuma.observatorylauncher.ui

import android.graphics.Rect
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import kotlinx.coroutines.launch

/**
 * 押した手応え。**押した瞬間に僅かに沈み、薄く明るみ、離すとバネで戻る。**
 *
 * ## ⚠️ 手応えを消していた理由と、戻した理由（2026-09-25）
 *
 * 以前は 27 箇所すべてで `indication = PressIndication.Row` にしていた——波紋（Material の ripple）は
 * 丸く広がる形で、直角で組むこの体系の語彙に無いからである。
 * だが手応えが無いと、**押せたのか、押し損ねたのか**が指に返らない。
 * 本人が動きの制限を解いたので（「スマホは動いてなんぼ快適でなんぼ」）、
 * 波紋ではなく**沈み**で返す。形を持たないので、体系の語彙と喧嘩しない。
 *
 * @param depth 沈む量（1 − 縮む割合）。像は深く、幅いっぱいの行は浅く——
 *   大きなものが大きく縮むと、面ごと揺れて見える。
 */
internal class PressIndication(private val depth: Float, private val glow: Float) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressNode(interactionSource, depth, glow)

    override fun equals(other: Any?): Boolean =
        other is PressIndication && other.depth == depth && other.glow == glow

    override fun hashCode(): Int = 31 * depth.hashCode() + glow.hashCode()

    companion object {
        /** 像・押し所の小さなもの。 */
        val Item = PressIndication(depth = 0.06f, glow = 0.06f)

        /** 幅いっぱいの行・札。 */
        val Row = PressIndication(depth = 0.015f, glow = 0.05f)
    }
}

private class PressNode(
    private val source: InteractionSource,
    private val depth: Float,
    private val glow: Float,
) : Modifier.Node(), DrawModifierNode {
    private val progress = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    // 沈みは速く（指に遅れない）、戻りはバネで（離したことが形で分かる）。
                    is PressInteraction.Press -> launch {
                        progress.animateTo(1f, tween(durationMillis = 70))
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> launch {
                        progress.animateTo(
                            0f,
                            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                        )
                    }
                }
            }
        }
        coroutineScope.launch {
            snapshotFlowOf { progress.value }.collect { invalidateDraw() }
        }
    }

    override fun ContentDrawScope.draw() {
        val p = progress.value
        if (p <= 0f) {
            drawContent()
            return
        }
        scale(1f - depth * p) {
            this@draw.drawContent()
            drawRect(ObservatoryColors.Paper.copy(alpha = glow * p))
        }
    }
}

private fun <T> snapshotFlowOf(block: () -> T) = androidx.compose.runtime.snapshotFlow(block)

/**
 * アプリを開く起点。押した像の場所を覚えておき、起動のときに `sourceBounds` として渡す
 * （[com.yuma.observatorylauncher.data.AppRepository.launchNow]）。
 *
 * ⚠️ 像から拡がって開く動きそのものは OS が許さなかった（launchNow の注記）。
 * 二秒より古い位置は使わない——別の経路（検索・近道）で開いたときに、無関係な場所から拡がらないように。
 */
internal object LaunchOrigin {
    @Volatile
    private var rect: Rect? = null

    @Volatile
    private var at = 0L

    fun set(bounds: Rect) {
        rect = bounds
        at = SystemClock.uptimeMillis()
    }

    fun take(): Rect? {
        val r = rect
        rect = null
        return r?.takeIf { SystemClock.uptimeMillis() - at < 2_000 }
    }
}

/** この要素を押したら、その場所を起点として覚える。押す操作そのものは奪わない。 */
internal fun Modifier.launchOrigin(): Modifier = composed {
    var bounds by remember { mutableStateOf<Rect?>(null) }
    this
        .onGloballyPositioned { coords ->
            val b = coords.boundsInWindow()
            bounds = Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                bounds?.let(LaunchOrigin::set)
            }
        }
}

/**
 * 知らせの印。**像の右肩に、燠火の小さな四角。**
 *
 * 数は書かない——「何件か」より「有るか」が像を選ぶ手掛かりで、数字は像の上で読めない。
 * 四角の周りに地の色の縁を回す。像の色が燠火に近い（赤い像）ときも印が埋もれない。
 */
internal fun Modifier.notificationMark(on: Boolean): Modifier =
    if (!on) this else drawWithContent {
        drawContent()
        val side = 9.dp.toPx()
        val rim = 2.dp.toPx()
        val left = size.width - side - rim
        val top = 0f
        drawRect(ObservatoryColors.Ground, Offset(left - rim, top), Size(side + rim * 2, side + rim * 2))
        drawRect(ObservatoryColors.Ember, Offset(left, top + rim), Size(side, side))
    }

/**
 * 知らせの印を出すか。
 *
 * ⚠️ 2026-09-25 に本人の判断で**止めた**（「通知マークはなくてもいいのでコードは消さずに機能だけオフに」）。
 * 見張り側の集計（`ObservatoryNotificationListener.badged`）と描く側（[notificationMark]）は残してある。
 * 戻すときはここを true にするだけでよい。
 */
internal const val NOTIFICATION_MARKS_ENABLED = false

private val NO_BADGES = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())

/** 知らせの残っているアプリの集合。印を止めている間は常に空。 */
@androidx.compose.runtime.Composable
internal fun rememberBadged(): androidx.compose.runtime.State<Set<String>> =
    if (NOTIFICATION_MARKS_ENABLED) {
        com.yuma.observatorylauncher.data.ObservatoryNotificationListener.badged.collectAsState()
    } else {
        NO_BADGES.collectAsState()
    }
