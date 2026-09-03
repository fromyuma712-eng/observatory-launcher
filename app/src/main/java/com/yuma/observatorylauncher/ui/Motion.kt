package com.yuma.observatorylauncher.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.yuma.observatorylauncher.data.AppEntry
import com.yuma.observatorylauncher.data.AppRepository
import com.yuma.observatorylauncher.ui.theme.ObservatoryMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 動きの語彙は rise と G-2 標識灯の二つで打ち止め、という規定を守ったまま
 * 「機械のように無反応ではない」状態を作るための道具置き場。
 * ここに新しい語彙(弾み・伸縮・連鎖・スイープ)を足さない。
 */

/**
 * rise の適用。opacity と 4dp の上昇、200ms。[index] で僅かにずらして順に立ち上げる。
 * 一度立ち上がったら再演しない——ホームへ戻るたびに動くと、うっとうしくなる。
 */
@Composable
fun Modifier.riseIn(index: Int = 0): Modifier {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(index * STAGGER_MS)
        shown = true
    }
    val progress by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(ObservatoryMotion.RiseDurationMs),
        label = "rise",
    )
    val offsetPx = with(LocalDensity.current) { ObservatoryMotion.RiseOffset.toPx() }
    return this.graphicsLayer {
        alpha = progress
        translationY = (1f - progress) * offsetPx
    }
}

/**
 * 押している間の反応。面を塗らず不透明度だけを落とす(§H 既定は面を塗らない)。
 * 100ms は既存実装(LEXICON)の transform .1s と同じ値で、新しい時間を導入していない。
 */
@Composable
fun Modifier.pressFade(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) PRESSED_ALPHA else 1f,
        animationSpec = tween(PRESS_MS),
        label = "press",
    )
    return this.graphicsLayer { this.alpha = alpha }
}

/**
 * アイコンは描画の最中に復号しない。作ってあれば即返し、無ければ背景で作って差し替える。
 */
@Composable
fun rememberAppIcon(entry: AppEntry, sizePx: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    // 行を削除するとComposeは同じ位置のStateを次の行へ再利用する。
    // keyが変わった時点で新しい項目のキャッシュへ必ず入れ替えないと、
    // 直前の行のアイコンが残り続ける。
    val state = remember(entry.id, entry.component, sizePx) {
        mutableStateOf(AppRepository.cachedIcon(entry, sizePx))
    }

    // 控えに無いものだけ取りに行く。
    //
    // ⚠️ ここを `if (state.value == null) { LaunchedEffect(...) }` と書くと、
    // **合成の構造が値で変わる**——像が入った瞬間に効果の節点が消える。
    // 速さのために一度そう書いたが、構造を値で揺らす書き方は割に合わない。
    // 呼び出しは常に一つに保ち、**中で早く帰る**。
    LaunchedEffect(entry.id, entry.component, sizePx) {
        if (state.value != null) return@LaunchedEffect
        state.value = withContext(Dispatchers.Default) {
            AppRepository.icon(context, entry, sizePx)
        }
    }
    return state
}

private const val STAGGER_MS = 18L
private const val PRESS_MS = 100
private const val PRESSED_ALPHA = 0.55f

/**
 * アプリが備える近道の読み出し。開いた札の分だけ、必要になった時に取りに行く。
 * 既定のホームでなければ取れないので、その場合は空のまま静かに諦める。
 */
@Composable
fun rememberShortcuts(
    packageName: String?,
    user: android.os.UserHandle?,
    enabled: Boolean,
): List<AppRepository.AppShortcut> {
    val context = LocalContext.current
    if (packageName == null || user == null || !enabled) return emptyList()
    return produceState(initialValue = emptyList(), packageName, user, enabled) {
        value = AppRepository.shortcuts(context, packageName, user)
    }.value
}
