package com.yuma.observatorylauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuma.observatorylauncher.data.Music
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens
import kotlinx.coroutines.delay
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.platform.LocalLifecycleOwner
import com.yuma.observatorylauncher.data.ObservatoryNotificationListener
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import android.os.Looper
import android.os.Handler
import android.media.session.PlaybackState
import android.media.session.MediaSessionManager
import android.media.session.MediaController
import android.media.MediaMetadata
import android.content.ComponentName
import androidx.compose.foundation.layout.width

/**
 * 今鳴っているもの。
 *
 * 通知の文字を二行出すだけだったところを、**針の付いた計器**にする。
 *
 * ## 描き方の約束
 *
 * - **絵は出さない。** 絵柄（アルバムの図）はこの面で最も大きな色の塊になり、
 *   地と字の 88:12 を一枚で壊す。**出さないことを選ぶ。**
 * - **位置は目盛りで示す。** 塗った帯ではなく、罫と格点で表す
 *   ——§N-7 の弦材と同じ語彙である。
 * - **燠火は針ひとつだけ。** 今どこを鳴らしているか、が「今」であり、
 *   この面で燠火を許すのはそこだけ。
 * - 数字は等幅で、秒は二桁。桁が揺れると数字が跳ねる。
 */
@Composable
internal fun MusicTile(
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
) {
    // **生きた値は [rememberNowPlaying] が持つ。**
    // 同じ仕掛けを二度書くと、片方だけが古いままになる
    // ——実際、音の面の新しい表示がそれで動かなかった（2026/9/1）。
    val context = LocalContext.current
    val live = rememberNowPlaying(refreshKey)
    val playing = live.current

    val now = playing
    if (now == null) {
        // **文言は出さない。** 「再生中のメディアなし」と書くのは、
        // 何も無いことを言葉で埋めているだけで、面に情報を足していない。
        // 目盛りだけを残す——**そこに音楽の席があること**は形で分かる。
        Scale(progress = null, modifier = modifier.fillMaxWidth())
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 曲名を押すと、鳴らしているアプリの「今の曲」の画面へ移る。
        // 押し所を稼ぐため、余白は clickable の**内側**に置く
        // ——外に置くと当たり判定が文字の高さのままになる。
        ObservatoryText(
            text = now.title.ifBlank { "(無題)" },
            color = ObservatoryColors.Paper,
            size = ObservatoryType.Body,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { Music.openPlayer(context) }
                .padding(vertical = 6.dp),
        )
        // 演者を左、出どころを右。**台帳と同じ組み方**で、一行に収める。
        // 下へ積むと行が左へ寄り、右が空いたまま縦だけ伸びる。
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            ObservatoryText(
                text = now.artist,
                color = ObservatoryColors.EmberText,
                size = ObservatoryType.Note,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(12.dp))
            ObservatoryText(
                text = now.app,
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
            )
        }

        Scale(progress = now.progress, modifier = Modifier.padding(top = 8.dp))

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ObservatoryText(
                text = Music.clock(now.position),
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Note,
                mono = true,
            )
            Spacer(modifier = Modifier.weight(1f))
            Control(label = "◂◂", onClick = live.previous)
            Control(label = if (now.playing) "▮▮" else "▶", onClick = live.toggle)
            Control(label = "▸▸", onClick = live.next)
            Spacer(modifier = Modifier.weight(1f))
            ObservatoryText(
                text = Music.clock(now.duration),
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Note,
                mono = true,
            )
        }

    }
}

/**
 * 位置の目盛り。
 *
 * 罫を一本引き、四分の一ごとに格点を置く。今の位置は燠火の節点。
 * **塗った帯にはしない**——面積で示すと、そこだけ色の塊になる。
 *
 * 長さが分からないときは節点を置かない。**位置の分からないものに針を立てると、
 * 嘘の位置を示すことになる。**
 */
@Composable
private fun Scale(progress: Float?, modifier: Modifier = Modifier) {
    val rule = ObservatoryColors.Line
    val tick = ObservatoryColors.LineStrong
    val hand = ObservatoryColors.Ember
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .drawBehind {
                val midY = size.height / 2
                drawRect(
                    color = rule,
                    topLeft = Offset(0f, midY),
                    size = Size(size.width, 1.dp.toPx()),
                )
                // 四分の一ごとの格点。目盛りが目盛りであることを示す最小限。
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { at ->
                    val w = 1.dp.toPx()
                    val h = 5.dp.toPx()
                    val x = (size.width - w) * at
                    drawRect(
                        color = tick,
                        topLeft = Offset(x, midY - h / 2),
                        size = Size(w, h),
                    )
                }
                if (progress != null) {
                    val s = 5.dp.toPx()
                    drawRect(
                        color = hand,
                        topLeft = Offset((size.width - s) * progress, midY - s / 2),
                        size = Size(s, s),
                    )
                }
            },
    )
}

/** 操作。押し所は HIG の最小(44dp)を確保する。 */
@Composable
private fun Control(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .heightIn(min = ObservatoryDimens.Row)
            .widthIn(min = ObservatoryDimens.Row)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ObservatoryText(text = label, color = ObservatoryColors.Muted, size = 12.sp)
    }
}
