package com.yuma.observatorylauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 引いて並べ替えられる像の格子。
 *
 * ## ⚠️ 作り直した理由（2026-09-25）
 *
 * > 「アプリの移動についてですがドラッグ＆ドロップで上手いこと動かせない」（本人）
 *
 * 以前の作りには四つの欠陥があった。
 *
 * 1. **横にしか動かなかった。** 引く量の x だけを見ていたので、別の行へ直接移せない。
 * 2. **一区画越えるたびに宣言を書いた。** 行末で折り返すと像は次の行の頭へ跳ぶのに、
 *    指の側は「一区画ぶん横へ」しか動いていないので、像と指がずれた。
 * 3. **書くたびに世代が一つ増えた。** 一度引くだけで「元に戻す」が十も二十も増えた。
 * 4. **編集中しか動かせなかった。**
 *
 * ## 今の作り
 *
 * - **長押しで持ち上がり、指に付いて縦横どこへでも動く。** 指の下の枡へ、他の像がバネで道を空ける。
 * - **離した瞬間に一度だけ書く**（[LayoutConfig.sectionReordered]）。
 * - 編集中でなくても引ける。**長押しして動かさずに離せば、従来どおり操作列が開く。**
 * - 指の受け口は**格子に一つだけ**置き、どの像かは座標から割り出す。像それぞれに受け口を置くと、
 *   指の下で像が動くたびに座標の基準が動き、引く量が狂う。
 */
@Composable
internal fun ReorderableIconGrid(
    entries: List<ClusterItem>,
    display: LayoutConfig.Display,
    cell: Dp,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val gapX = 16.dp
    val gapY = 14.dp
    val labelHeight = if (display.showLabels) measureLabels(listOf("あ"), ObservatoryType.Label, mono = false).height + 4.dp else 0.dp
    val cellHeight = display.iconSize.dp + labelHeight

    val keys = entries.map { it.key }
    var order by remember(keys) { mutableStateOf(keys) }
    val byKey = entries.associateBy { it.key }
    var dragging by remember { mutableStateOf<String?>(null) }
    var pressed by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var travelled by remember { mutableStateOf(0f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    // 離した像と、離した位置。像はそこから新しい枡へバネで収まる。
    var dropped by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    val latestOrder by rememberUpdatedState(order)
    // ⚠️ 指の受け口は一度しか作られないので、「引く前の並び」も常に最新を読む。
    // 最初の並びを握ったままだと、元の並びへ戻したときに「変化なし」と見なされて書かれず、
    // 長押しは「並べ替えた」と見なされて操作列が開かなかった（実機・2026-09-25）。
    val latestKeys by rememberUpdatedState(keys)
    val latestEntries by rememberUpdatedState(byKey)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ObservatoryDimens.Gutter, vertical = 12.dp),
    ) {
        val columns = ((maxWidth + gapX) / (cell + gapX)).toInt().coerceAtLeast(1)
        val rows = ceil(order.size / columns.toFloat()).toInt().coerceAtLeast(1)
        val cellW = with(density) { cell.toPx() }
        val cellH = with(density) { cellHeight.toPx() }
        val stepX = with(density) { (cell + gapX).toPx() }
        val stepY = with(density) { (cellHeight + gapY).toPx() }
        fun slotOf(index: Int) = Offset((index % columns) * stepX, (index / columns) * stepY)
        fun indexAt(p: Offset): Int? {
            val col = (p.x / stepX).toInt()
            val row = (p.y / stepY).toInt()
            if (col !in 0 until columns || row < 0) return null
            val inCell = p.x - col * stepX <= cellW && p.y - row * stepY <= cellH
            val i = row * columns + col
            return if (inCell && i in latestOrder.indices) i else null
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cellHeight * rows + gapY * (rows - 1))
                .onGloballyPositioned { origin = it.boundsInWindow().topLeft }
                .pointerInput(columns, rows) {
                    detectTapGestures(
                        onPress = { p ->
                            pressed = indexAt(p)?.let { latestOrder[it] }
                            tryAwaitRelease()
                            pressed = null
                        },
                        onTap = tap@{ p ->
                            val key = indexAt(p)?.let { latestOrder[it] } ?: return@tap
                            val slot = slotOf(latestOrder.indexOf(key))
                            LaunchOrigin.set(
                                android.graphics.Rect(
                                    (origin.x + slot.x).roundToInt(),
                                    (origin.y + slot.y).roundToInt(),
                                    (origin.x + slot.x + cellW).roundToInt(),
                                    (origin.y + slot.y + cellH).roundToInt(),
                                ),
                            )
                            latestEntries[key]?.onClick?.invoke()
                        },
                        // 長押しは下の「引く」が受け持つ。ここで空にしておかないと、長押しも押下として数えられる。
                        onLongPress = {},
                    )
                }
                .pointerInput(columns, rows) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = start@{ p ->
                            val i = indexAt(p) ?: return@start
                            val key = latestOrder[i]
                            dragging = key
                            grab = p - slotOf(i)
                            pointer = p
                            travelled = 0f
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        },
                        onDrag = drag@{ change, amount ->
                            val key = dragging ?: return@drag
                            change.consume()
                            pointer += amount
                            travelled += amount.getDistance()
                            // 像の中心がどの枡にあるかで、行き先を決める。
                            val center = pointer - grab + Offset(cellW / 2, cellH / 2)
                            val col = (center.x / stepX).toInt().coerceIn(0, columns - 1)
                            val row = (center.y / stepY).toInt().coerceIn(0, rows - 1)
                            val target = (row * columns + col).coerceIn(0, latestOrder.lastIndex)
                            val from = latestOrder.indexOf(key)
                            if (target != from && from >= 0) {
                                order = latestOrder.toMutableList().apply {
                                    removeAt(from)
                                    add(target, key)
                                }
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                        },
                        onDragEnd = {
                            val key = dragging
                            if (key != null) dropped = key to (pointer - grab)
                            dragging = null
                            if (key != null) {
                                // 動かさずに離した長押しは、従来どおり操作列を開く。
                                if (travelled < with(density) { 12.dp.toPx() } && latestOrder == latestKeys) {
                                    latestEntries[key]?.onLongClick?.invoke()
                                } else if (latestOrder != latestKeys) {
                                    onReorder(latestOrder)
                                }
                            }
                        },
                        onDragCancel = {
                            dragging?.let { dropped = it to (pointer - grab) }
                            dragging = null
                            order = latestKeys
                        },
                    )
                },
        ) {
            order.forEachIndexed { index, k ->
                // ⚠️ 取り出しは key の外で。key（Compose のインライン関数）の中から return すると、
                // dex へ変換できない形にコンパイルされてビルドが落ちた（2026-09-25）。
                val item = byKey[k] ?: return@forEachIndexed
                key(k) {
                    val slot = slotOf(index)
                    val place = remember { Animatable(slot, Offset.VectorConverter) }
                    val isDragged = dragging == k
                    LaunchedEffect(slot, isDragged) {
                        if (isDragged) return@LaunchedEffect
                        // 離した像は指の位置から、道を空けた像は元の枡から、新しい枡へバネで収まる。
                        dropped?.takeIf { it.first == k }?.let {
                            place.snapTo(it.second)
                            dropped = null
                        }
                        if (place.value != slot) {
                            place.animateTo(slot, spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow))
                        }
                    }
                    // 持ち上げている間は、位置は指そのもの（描く段で読む）。
                    val position = { if (isDragged) pointer - grab else place.value }
                    val lift by animateFloatAsState(if (isDragged) 1f else 0f, spring(stiffness = Spring.StiffnessMedium), label = "lift")
                    val press by animateFloatAsState(if (pressed == k && !isDragged) 1f else 0f, label = "press")
                    Column(
                        modifier = Modifier
                            .offset { position().let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) } }
                            .zIndex(if (isDragged) 1f else 0f)
                            .width(cell)
                            .graphicsLayer {
                                val s = 1f + 0.12f * lift - 0.06f * press
                                scaleX = s
                                scaleY = s
                                alpha = 1f - 0.08f * lift
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box {
                            if (item.icon != null) {
                                Image(
                                    bitmap = item.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(display.iconSize.dp).notificationMark(item.badged),
                                )
                            } else if (item.fallbackLabel != null) {
                                FallbackIcon(item.fallbackLabel, display.iconSize.dp)
                            }
                            // 持ち上げている像には、燠火の細い枠。**今動かしているのはこれ**と示す。
                            if (isDragged) {
                                Box(
                                    modifier = Modifier
                                        .size(display.iconSize.dp)
                                        .border(1.dp, ObservatoryColors.Ember),
                                )
                            }
                        }
                        if (display.showLabels) {
                            ObservatoryText(
                                text = item.label,
                                color = ObservatoryColors.Paper,
                                size = ObservatoryType.Label,
                                maxLines = 1,
                                center = true,
                                condensed = true,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
