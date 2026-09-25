package com.yuma.observatorylauncher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
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
 * 動きの道具置き場。
 *
 * ⚠️ 以前は「動きの語彙は rise と G-2 標識灯の二つで打ち止め」と定めていた。
 * 2026-09-25、本人が解いた——
 * > 「スマホは動いてなんぼ快適でなんぼのもんなので制限を解除します」
 *
 * 今の語彙は [ObservatoryMotion] の表。**足してよいが、動きには必ず役目を持たせる**
 * （押せたことを返す・どこから来てどこへ行くかを示す・変わったことに気付かせる）。
 * 飾りのためだけに動かすものは足さない。
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
 * アイコンは描画の最中に復号しない。作ってあれば即返し、無ければ背景で作って差し替える。
 */
@Composable
fun rememberAppIcon(entry: AppEntry, sizePx: Int): State<ImageBitmap?> {
    val context = LocalContext.current
    // 行を削除するとComposeは同じ位置のStateを次の行へ再利用する。
    // keyが変わった時点で新しい項目のキャッシュへ必ず入れ替えないと、
    // 直前の行のアイコンが残り続ける。
    val mono = IconTone.mono
    val state = remember(entry.id, entry.component, sizePx, mono) {
        mutableStateOf(AppRepository.cachedIcon(entry, sizePx, mono))
    }

    // 控えに無いものだけ取りに行く。
    //
    // ⚠️ ここを `if (state.value == null) { LaunchedEffect(...) }` と書くと、
    // **合成の構造が値で変わる**——像が入った瞬間に効果の節点が消える。
    // 速さのために一度そう書いたが、構造を値で揺らす書き方は割に合わない。
    // 呼び出しは常に一つに保ち、**中で早く帰る**。
    LaunchedEffect(entry.id, entry.component, sizePx, mono) {
        if (state.value != null) return@LaunchedEffect
        state.value = withContext(Dispatchers.Default) {
            AppRepository.icon(context, entry, sizePx, mono)
        }
    }
    return state
}

private const val STAGGER_MS = 18L

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

/**
 * この面が今、画面に見えているか。面送り（`HorizontalPager`）が面ごとに与える。
 *
 * 隣の面は先に組んでおくので（掃いた瞬間の引っかかりを防ぐため）、組まれた時点では
 * まだ見えていない。**見えてから動かす**ために、動きはこれを見て始める（[rememberReveal]）。
 */
val LocalPageVisible = compositionLocalOf { true }

/**
 * 図が現れるときの伸び（0→1）。**面が見えた最初の一度だけ**伸びる。
 *
 * 棒は根元から伸び、線は左から引かれる。数字が変わっただけでは再演しない
 * ——開くたびに動くと、うっとうしくなる（[riseIn] と同じ判断）。
 * 読むのは描く段（`drawBehind`）の中だけにすること。組み直しを起こさずに済む。
 */
@Composable
fun rememberReveal(): State<Float> {
    val visible = LocalPageVisible.current
    val progress = remember { Animatable(0f) }
    LaunchedEffect(visible) {
        if (visible && progress.value < 1f) {
            progress.animateTo(1f, tween(durationMillis = 650, easing = FastOutSlowInEasing))
        }
    }
    return progress.asState()
}

/**
 * 像を単色で描くか。宣言（`LayoutConfig.Display.monoIcons`）から写す。
 *
 * 状態として持つので、切り替えた瞬間に全部の像が描き直される。
 */
object IconTone {
    var mono by mutableStateOf(true)
}
