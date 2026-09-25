package com.yuma.observatorylauncher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuma.observatorylauncher.data.Friction
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens

/**
 * 摩擦の面。
 *
 * ## 何を狙っているか
 *
 * 難しい問題を解かせることではない。
 * **反射でアイコンを押した状態から、一度考える状態へ戻すこと**である。
 * だから問いは数秒で解ける程度にしてある。
 * 難しすぎると、摩擦そのものを外したくなる——外されたら効果は 0 になる。
 *
 * ## 責めない
 *
 * 「本当に開きますか」とは書かない。道具が人を裁く形になる。
 * 置くのは事実だけで、判断は本人に返す。
 *
 * ## 逃げ道を必ず置く
 *
 * 「やめる」を必ず出す。**閉じ込めない。**
 * そして押されたら、それを**成果として数える**（開かなかった回数）。
 * 我慢が記録に残らないなら、我慢する理由がひとつ減る。
 */
@Composable
internal fun ChallengePanel(
    label: String,
    notice: String,
    level: Friction.Level,
    onPass: () -> Unit,
    onGiveUp: () -> Unit,
) {
    val questions = remember(label, level) { Friction.questions(level.questions) }
    var at by remember(label, level) { mutableIntStateOf(0) }
    var typed by remember(label, level) { mutableStateOf("") }
    var wrong by remember(label, level) { mutableStateOf(false) }
    val focus = remember { FocusRequester() }

    // 問いが無い段（見せるだけ）でも、面は出す。**一拍置くこと自体が摩擦**である。
    LaunchedEffect(questions) {
        if (questions.isNotEmpty()) runCatching { focus.requestFocus() }
    }
    BackHandler(enabled = true) { onGiveUp() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // 地の色で塞ぐ。半透明にすると、後ろの面が透けて「まだホームに居る」
            // という感覚が残り、一度考える状態へ戻らない。
            .background(ObservatoryColors.Ground)
            .padding(horizontal = ObservatoryDimens.Gutter),
        contentAlignment = Alignment.Center,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            ObservatoryText(
                text = label,
                color = ObservatoryColors.Paper,
                size = ObservatoryType.Value,
            )
            ObservatoryText(
                text = notice,
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Note,
                modifier = Modifier.padding(top = 6.dp),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 16.dp)
                    .height(ObservatoryDimens.Hairline)
                    .background(ObservatoryColors.Line),
            )

            if (questions.isEmpty()) {
                ObservatoryText(
                    text = "そのまま開けます",
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Note,
                )
            } else {
                val q = questions[at.coerceAtMost(questions.lastIndex)]
                Row(verticalAlignment = Alignment.Bottom) {
                    ObservatoryText(
                        text = "${at + 1} / ${questions.size}",
                        color = ObservatoryColors.Faint,
                        size = ObservatoryType.Label,
                        mono = true,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    ObservatoryText(
                        text = q.text + " =",
                        color = ObservatoryColors.Paper,
                        size = ObservatoryType.Hero,
                        mono = true,
                    )
                }
                BasicTextField(
                    value = typed,
                    onValueChange = { next ->
                        // 数字だけ受ける。記号を弾くのは、打ち間違いを不正解に数えないため。
                        typed = next.filter { it.isDigit() || it == '-' }.take(6)
                        wrong = false
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = ObservatoryColors.Paper,
                        fontSize = 22.sp,
                    ),
                    cursorBrush = SolidColor(ObservatoryColors.Ember),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (Friction.correct(q, typed)) {
                                typed = ""
                                if (at >= questions.lastIndex) onPass() else at += 1
                            } else {
                                wrong = true
                            }
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .border(ObservatoryDimens.Hairline, ObservatoryColors.Line)
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .focusRequester(focus),
                )
                if (wrong) {
                    // **間違いを咎めない。** 数字だけを置き直させる。
                    ObservatoryText(
                        text = "もう一度",
                        color = ObservatoryColors.EmberText,
                        size = ObservatoryType.Note,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }

            Row(modifier = Modifier.padding(top = 22.dp)) {
                PanelAction(
                    label = "やめる",
                    hot = true,
                    onClick = onGiveUp,
                )
                Spacer(modifier = Modifier.width(20.dp))
                if (questions.isEmpty()) {
                    PanelAction(label = "開く", hot = false, onClick = onPass)
                }
            }
        }
    }
}

@Composable
private fun PanelAction(label: String, hot: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .heightIn(min = ObservatoryDimens.Row)
            .clickable(
                interactionSource = interaction,
                indication = PressIndication.Item,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        ObservatoryText(
            text = label,
            color = if (hot) ObservatoryColors.EmberText else ObservatoryColors.Paper,
            size = ObservatoryType.Body,
        )
    }
}
