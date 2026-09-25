package com.yuma.observatorylauncher.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens

/**
 * 台帳の部品。ホームと検索面の双方がここを使い、行の作りを二箇所に持たない。
 * 見え方(アイコン寸法・名前の有無)は宣言 [LayoutConfig.Display] からのみ受け取る。
 */

/** 一つの操作。地層(§N-6)の縦材に沿って並べる。 */
data class LedgerAction(val label: String, val onClick: () -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppRow(
    label: String,
    icon: ImageBitmap?,
    display: LayoutConfig.Display,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 並べ替えの検出。クリックより内側へ置くのでここで受け取る。 */
    dragModifier: Modifier = Modifier,
    number: Int? = null,
    // 立ち上がりの動きを掛ける順番。null なら動かさない。
    // 長い一覧(検索面)では、弾いて流すたびに新しい行が現れ、そのすべてが
    // 200msの動きを始めてしまう——動きの量ではなく回数が描画を圧迫する。
    index: Int? = null,
    highlighted: Boolean = false,
    trailing: String? = null,
    fallbackLabel: String? = null,
    actions: List<LedgerAction> = emptyList(),
    expanded: Boolean = false,
    /** 知らせが残っているか。像の右肩に印を出す（[notificationMark]）。 */
    badged: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val iconSize = display.iconSize.dp
    val rowHeight = maxOf(ObservatoryDimens.Row, iconSize + 16.dp)

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = rowHeight)
                .then(if (index != null) Modifier.riseIn(index) else Modifier)
                .launchOrigin()
                .combinedClickable(
                    interactionSource = interaction,
                    indication = PressIndication.Row,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .then(dragModifier)
                .padding(horizontal = ObservatoryDimens.Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (number != null) {
                ObservatoryText(
                    text = "%02d".format(number),
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Note,
                    mono = true,
                    modifier = Modifier.width(26.dp),
                )
            }
            Box(
                modifier = Modifier.width(iconSize + 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier.size(iconSize).notificationMark(badged),
                    )
                } else if (fallbackLabel != null) {
                    FallbackIcon(fallbackLabel, iconSize)
                }
            }
            if (display.showLabels) {
                ObservatoryText(
                    text = label,
                    color = if (highlighted) ObservatoryColors.EmberText else ObservatoryColors.Paper,
                    // ⚠️ ここは `Value`(22sp) にしていたが、**大きすぎた**
                    // （本人・2026/9/1「太くてダサい」→ 9/2「でかすぎるので標準に」）。
                    //
                    // 段の規定は「札に一つだけ置く数値」のために書いたもので、
                    // **名前が何十行も縦に並ぶ場所には当たらない**。
                    // 一覧の行は読ませる文なので `Body`。太さも規定どおり Regular に戻る。
                    size = ObservatoryType.Body,
                )
            }
            if (trailing != null) {
                Spacer(modifier = Modifier.weight(1f))
                ObservatoryText(
                    text = trailing,
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Note,
                    mono = true,
                    tracking = 0.2f,
                )
            }
        }
        if (expanded && actions.isNotEmpty()) {
            ActionStrip(
                actions = actions,
                startPadding = ObservatoryDimens.Gutter + if (number != null) 26.dp else 0.dp,
            )
        }
    }
}

/**
 * 長押しで開く操作列。§N-6の地層に従い、左に燠火の縦材を通して面は塗らない。
 * 最後に必ず「閉」を置く——開いたものを閉じる道が無い状態を作らない。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionStrip(
    actions: List<LedgerAction>,
    startPadding: androidx.compose.ui.unit.Dp = ObservatoryDimens.Gutter,
) {
    // 選択肢が画面幅を超えたら折り返す。横へ流すと端の操作(「元に戻す」等)が
    // 隠れてしまい、探しに行かないと使えない——編集は迷ってはいけない場面である。
    // 縦材は「線を引く」ことで表す。高さを揃えるために内寸を測らせると、
    // 折り返した二行目以降が切られてしまう(実機で欠けを確認した)。
    val hairline = with(LocalDensity.current) { ObservatoryDimens.Hairline.toPx() }
    val ember = ObservatoryColors.Ember
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = ObservatoryDimens.Gutter, bottom = 6.dp)
            .riseIn()
            .drawBehind {
                drawRect(color = ember, size = Size(hairline, size.height))
            }
            .padding(start = 12.dp),
    ) {
        actions.forEach { action -> ActionItem(action) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActionItem(action: LedgerAction) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .heightIn(min = ObservatoryDimens.Row)
            .combinedClickable(
                interactionSource = interaction,
                indication = PressIndication.Row,
                onClick = action.onClick,
            )
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        ObservatoryText(text = action.label, color = ObservatoryColors.EmberText, size = ObservatoryType.Note)
    }
}

/**
 * 節・群の見出し。§D-2の節点(6dpの格点)を罫の起点に置く＝装飾ではなく位置の宣言。
 * 押すと操作列が開き、もう一度押すか「閉」で戻る。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GroupHeader(
    title: String,
    menuOpen: Boolean,
    renaming: Boolean,
    onToggleMenu: () -> Unit,
    onRename: (String) -> Unit,
    actions: List<LedgerAction>,
    trailing: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = interaction,
                    indication = PressIndication.Row,
                    onClick = onToggleMenu,
                    onLongClick = onToggleMenu,
                )
                .padding(horizontal = ObservatoryDimens.Gutter)
                .padding(top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(if (menuOpen) ObservatoryColors.Ember else ObservatoryColors.Line)
            )
            Spacer(modifier = Modifier.width(10.dp))
            if (renaming) {
                NameField(initial = title, onDone = onRename)
            } else {
                // 既定の群には欧文の札を添える（「TOOLS 道具」、[Lexicon.group]）。利用者が付けた名には付かない。
                Lexicon.group(title)?.let { tag ->
                    ObservatoryTag(text = tag, color = if (menuOpen) ObservatoryColors.EmberText else ObservatoryColors.Faint)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                // ⚠️ 名は罫の色（Line・2.6:1）で描いていた。**罫の色は字に使わない**（Color.kt）。
                ObservatoryText(
                    text = title,
                    color = if (menuOpen) ObservatoryColors.EmberText else ObservatoryColors.Faint,
                    size = ObservatoryType.Note,
                    tracking = 0.24f,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(ObservatoryDimens.Hairline)
                    .background(ObservatoryColors.Line)
            )
            if (trailing != null) {
                Spacer(modifier = Modifier.width(10.dp))
                ObservatoryText(
                    text = trailing,
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Note,
                    mono = true,
                )
            }
        }
        if (menuOpen && !renaming) {
            ActionStrip(actions)
        }
    }
}

/** 名前の入力。専用の画面を作らず、その場で置き換える(面を増やさない)。 */
@Composable
fun NameField(initial: String, onDone: (String) -> Unit) {
    var text by remember(initial) { mutableStateOf(TextFieldValue(initial)) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        cursorBrush = SolidColor(ObservatoryColors.Ember),
        textStyle = TextStyle(
            color = ObservatoryColors.EmberText,
            fontSize = ObservatoryType.Body,
            fontFamily = ObservatoryFonts.Sans,
            letterSpacing = 0.3.sp,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone(text.text) }),
        modifier = Modifier
            .width(160.dp)
            .focusRequester(focus),
    )
}

/**
 * 横並びの塊。節や群を「横」に切り替えたときの見え方で、番号を持たず像だけを並べる。
 * 台帳(縦の一列)は順序を読ませる形、この塊は**まとまりを一目で示す**形——
 * 用途が違うので、同じ行の作りを横にしただけにはしない。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun IconCluster(
    entries: List<ClusterItem>,
    display: LayoutConfig.Display,
    modifier: Modifier = Modifier,
    /** 与えれば、長押しで引いて並べ替えられる（[ReorderableIconGrid]）。離したときの並びを返す。 */
    onReorder: ((List<String>) -> Unit)? = null,
) {
    val iconSize = display.iconSize.dp + 8.dp
    // 名前を出すときは枡を広げる。像だけの幅で一行に切ると、
    // 「メッセー…」のような残骸になる。一列に減るが、読める方を採る。
    // ⚠️ 字の下限を 12sp に上げたので（2026/9/25）、56dp の枡では
    // 「Apple …」「YT Mu…」と切れた。**読める幅まで広げる。**
    val cell = if (display.showLabels) {
        iconSize.coerceAtLeast(72.dp)
    } else {
        iconSize.coerceAtLeast(44.dp)
    }
    if (onReorder != null) {
        ReorderableIconGrid(
            entries = entries,
            display = display,
            cell = cell,
            onReorder = onReorder,
            modifier = modifier,
        )
        return
    }
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            // 罫が像に触れないだけの余白を持たせる。
            // 触れていると、区切りが像の一部のように見えて面が締まらない。
            .padding(horizontal = ObservatoryDimens.Gutter, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        entries.forEachIndexed { i, item ->
            val interaction = remember(item.key) { MutableInteractionSource() }
            Column(
                modifier = Modifier
                    .width(cell)
                    .riseIn(i)
                    .launchOrigin()
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = PressIndication.Item,
                        onClick = item.onClick,
                        onLongClick = item.onLongClick,
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (item.icon != null) {
                    Image(
                        bitmap = item.icon,
                        contentDescription = null,
                        modifier = Modifier.size(display.iconSize.dp).notificationMark(item.badged),
                    )
                } else if (item.fallbackLabel != null) {
                    FallbackIcon(item.fallbackLabel, display.iconSize.dp)
                }
                if (display.showLabels) {
                    // **一行に収める。**
                    //
                    // 以前は二行許していた。長い名前が二行、短い名前が一行になるので
                    // **枡の高さが揃わず、行ごとに段が不揃いになる**——
                    // 文字が読めることより、並びが乱れることの方が目に付いた。
                    // 名前は像の添えであって、読ませるのは一覧の側の役目である。
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

/** 横並びの塊に置く一件。 */
data class ClusterItem(
    val key: String,
    val label: String,
    val icon: ImageBitmap?,
    val fallbackLabel: String? = null,
    val onClick: () -> Unit,
    val onLongClick: () -> Unit,
    /** 知らせが残っているか。 */
    val badged: Boolean = false,
)

/** サイト等、Androidアプリ由来の画像を持たない項目にも空白でない識別像を与える。 */
@Composable
internal fun FallbackIcon(label: String, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .border(ObservatoryDimens.Hairline, ObservatoryColors.EmberText),
        contentAlignment = Alignment.Center,
    ) {
        ObservatoryText(
            text = label,
            color = ObservatoryColors.EmberText,
            size = if (size < 28.dp) 7.sp else 8.sp,
            mono = true,
            tracking = 0f,
        )
    }
}

/**
 * 選択面や編集面のための「面」。地の上に重ねる一枚として立たせる。
 *
 * 地のドットを透かさないよう**不透明な鉄紺で塗り**、1pxの罫で囲い、
 * 上端に幅12%・3dpの燠火帯を置く(§D 天の署名＝面積比88:12を紙面の端で宣言する device)。
 * 新しい色も角丸も影も足していない——**既にある語彙だけで、地と面を分ける**。
 */
@Composable
fun ObservatoryPanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ObservatoryColors.Ground)
            .border(ObservatoryDimens.Hairline, ObservatoryColors.LineStrong),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.12f)
                .height(3.dp)
                .background(ObservatoryColors.Ember)
        )
        content()
    }
}
