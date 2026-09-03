package com.yuma.observatorylauncher.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens
import com.yuma.observatorylauncher.ui.theme.ObservatoryMotion
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
/*
 * 画面上部（時刻・標識灯・編集の卓・ページ送り）と最下端の検索帯。
 * いずれも台帳そのものではなく、その周りに常設される装置である。
 */

/**
 * 時刻・日付と、G-2 標識灯(存在の申告)。標識灯は一面に一つまで。
 * 押すと見え方(アイコンの大きさ・名前の有無)の操作列が開く。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun Header(
    display: LayoutConfig.Display,
    editorMode: Boolean,
    menuOpen: Boolean,
    generations: Int,
    pageCount: Int,
    notice: String?,
    commands: HomeCommands,
) {
    // ## ⚠️ 時計がまれに遅れていた理由は二つある（2026/9/1）
    //
    // 1. **待ちが早く明けると、一分ぶん遅れたまま固まる。**
    //    `delay` は要求した時間を**下回って**明けることがある。
    //    分の変わり目の 1ms 手前で `Date()` を読むと前の分が入り、
    //    次の待ちはまた約 60 秒——**まる一分、古い時刻を出し続ける**。
    //    境目を跨いだと確信できるまでの余白を足して跨がせる。
    //
    // 2. **面から離れている間、待ちは時間どおりに明けない。**
    //    端末が眠ると待ちも止まるので、戻ってきたとき次の拍まで古い時刻が残る。
    //    面へ戻るたびに拍を組み直し、**組み直した瞬間に必ず一度合わせる**。
    val owner = LocalLifecycleOwner.current
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = Date()
                // 秒を出すので拍は一秒。**面から離れれば止まる**（`repeatOnLifecycle`）ので、
                // 見ていない間に毎秒描き直すことはない。
                delay(1_000L - (System.currentTimeMillis() % 1_000L) + TICK_MARGIN_MS)
            }
        }
    }
    val clock = remember(now) { SimpleDateFormat("HH:mm", Locale.JAPAN).format(now) }
    val seconds = remember(now) { SimpleDateFormat("ss", Locale.JAPAN).format(now) }
    // 日付は日をまたぐまで変わらない。**毎秒作り直さない。**
    val dayKey = remember(now) { SimpleDateFormat("yyyyMMdd", Locale.JAPAN).format(now) }
    val date = remember(dayKey) { SimpleDateFormat("yyyy.MM.dd EEE", Locale.JAPAN).format(now) }
    val interaction = remember { MutableInteractionSource() }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pressFade(interaction)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    // 押せば時計アプリへ。長押しで編集の操作列が開く。
                    // 時刻は最も押しやすい場所にあるので、そこを設定の入口に
                    // 独占させない(まず時計として使えること)。
                    onClick = commands.openClock,
                    onLongClick = commands.toggleMenu,
                )
                .padding(horizontal = ObservatoryDimens.Gutter)
                .padding(top = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                // ## 秒の置き方
                //
                // 秒を時分と同じ大きさで並べると、**面の主役が時計になってしまう**。
                // 時計は「今が何時か」を答えるものであって、
                // 秒は**その答えが今も生きていることの印**である。
                //
                // だから時分は 52sp のまま、秒は一段落として添える。
                // 下端を揃えるので、数字の列としては一続きに読める。
                Row(verticalAlignment = Alignment.Bottom) {
                    ObservatoryText(
                        clock,
                        ObservatoryColors.Paper,
                        52.sp,
                        mono = true,
                        tracking = 0f,
                    )
                    ObservatoryText(
                        ":$seconds",
                        ObservatoryColors.Muted,
                        ObservatoryType.Value,
                        mono = true,
                        tracking = 0f,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    )
                }
                ObservatoryText(
                    date,
                    ObservatoryColors.Muted,
                    12.sp,
                    mono = true,
                    tracking = 0.12f,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Beacon()
        }
        if (menuOpen) {
            ActionStrip(
                actions = listOf(
                    LedgerAction(
                        if (editorMode) "編集モードを終える" else "編集モードに入る",
                        commands.toggleEditor,
                    ),
                    LedgerAction("情報を追加", commands.addInfo),
                    LedgerAction("ウィジェットを追加", commands.addWidget),
                    LedgerAction("サイト・HTMLを追加", commands.addSite),
                    LedgerAction("新しいホームページを追加", commands.addPage),
                    LedgerAction("アイコンの大きさ：${display.iconSize}", commands.cycleIconSize),
                    LedgerAction(
                        if (display.showLabels) "アプリ名：表示" else "アプリ名：非表示",
                        commands.toggleLabels,
                    ),
                    LedgerAction("控えを書き出す", commands.exportBackup),
                    LedgerAction("控えから戻す", commands.importBackup),
                    LedgerAction("閉じる", commands.close),
                )
            )
        }
        // 済んだこと・断ったことを字で残す。黙って終わらせない。
        if (notice != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ObservatoryDimens.Gutter)
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(ObservatoryDimens.Anchor)
                        .background(ObservatoryColors.Ember)
                )
                Spacer(modifier = Modifier.width(10.dp))
                ObservatoryText(notice, ObservatoryColors.EmberText, 12.sp)
            }
        }
        if (editorMode) {
            EditorBar(
                display = display,
                generations = generations,
                pageCount = pageCount,
                commands = commands,
            )
        }
    }
}

/**
 * 編集の卓。編集モードに入っている間だけ出る。
 *
 * 一つ直すたびに長押しし直す必要が無いよう、**全体に効く操作をここへ集める**。
 * 個々の行や節に効く操作は、その行や節を押したときに出る。
 * 言葉は簡潔な語ではなく普通の言い方にする——編集は迷ってはいけない場面である。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun EditorBar(
    display: LayoutConfig.Display,
    generations: Int,
    pageCount: Int,
    commands: HomeCommands,
) {
    val interaction = remember { MutableInteractionSource() }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ObservatoryDimens.Gutter)
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(ObservatoryDimens.Anchor)
                    .background(ObservatoryColors.Ember)
            )
            Spacer(modifier = Modifier.width(10.dp))
            ObservatoryText(
                text = "編集モード — 押すと操作、引くと並べ替え",
                color = ObservatoryColors.EmberText,
                size = ObservatoryType.Note,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .pressFade(interaction)
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = commands.toggleEditor,
                    )
                    .padding(start = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                ObservatoryText(
                    text = "完了",
                    color = ObservatoryColors.Paper,
                    size = ObservatoryType.Body,
                    tracking = 0.1f,
                )
            }
        }
        ActionStrip(
            actions = buildList {
                add(LedgerAction("情報を追加", commands.addInfo))
                add(LedgerAction("ウィジェットを追加", commands.addWidget))
                add(LedgerAction("サイト・HTMLを追加", commands.addSite))
                add(LedgerAction("節を追加", commands.addSection))
                add(LedgerAction("ページを追加", commands.addPage))
                // 足したものは畳めること。中身は前のページへ移すので消えない。
                if (pageCount > 1) {
                    add(LedgerAction("このページを削除", commands.removePage))
                }
                add(LedgerAction("アイコンの大きさ：${display.iconSize}", commands.cycleIconSize))
                add(
                    LedgerAction(
                        if (display.showLabels) "アプリ名：表示" else "アプリ名：非表示",
                        commands.toggleLabels,
                    )
                )
                if (generations > 0) {
                    add(LedgerAction("元に戻す($generations)", commands.rollback))
                }
                add(LedgerAction("控えを書き出す", commands.exportBackup))
                add(LedgerAction("控えから戻す", commands.importBackup))
            },
        )
    }
}

/**
 * §G-2 標識灯。40回/分＝周期1500ms・点灯350ms、opacityのみ、断続させ滑らかに繋がない。
 * 用途は存在の申告ただ一つで、祝祭・強調・誘目に転用しない。
 */
@Composable
internal fun Beacon() {
    val transition = rememberInfiniteTransition(label = "beacon")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = ObservatoryMotion.BeaconPeriodMs
                1f at 0 using LinearEasing
                1f at ObservatoryMotion.BeaconOnMs using LinearEasing
                0f at ObservatoryMotion.BeaconOnMs + 1 using LinearEasing
                0f at ObservatoryMotion.BeaconPeriodMs using LinearEasing
            }
        ),
        label = "beaconAlpha",
    )
    Box(
        modifier = Modifier
            .size(ObservatoryDimens.Anchor)
            .alpha(alpha)
            .background(ObservatoryColors.Ember)
    )
}

/**
 * ページの位置。
 *
 * 以前は「前のページ」「次のページ」「01 / 02」と**言葉で三つ**書いていた。
 * 言葉は、それが何であるかを説明しているだけで、**位置そのものは示していない**。
 * 二枚しかない面に三つの語を置くのは、面に対して重すぎる。
 *
 * §D-2 の格点で置き換える。**枚数だけ点を並べ、今居るところを塗る。**
 * 数えれば何枚あるか分かり、塗りを見れば何枚目か分かる。押せばそこへ移る。
 * 点は小さくてよいが、**押し所は HIG の最小(44dp)を確保する**。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PageNavigator(
    page: Int,
    count: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    // 一枚しか無いなら、位置を示す意味がない。何も置かない。
    if (count <= 1) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ObservatoryDimens.Row),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { at ->
            val interaction = remember(at) { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(ObservatoryDimens.Row)
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = at != page,
                        // 隣へ移るのが常なので、前後の指示をそのまま使う。
                        // 二枚を超える面は今のところ想定していない。
                        onClick = { if (at < page) onPrevious() else onNext() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(if (at == page) 7.dp else 5.dp)
                        .background(
                            if (at == page) ObservatoryColors.Paper else ObservatoryColors.Line
                        ),
                )
            }
        }
    }
}

/**
 * 画面最下端の常設行。全アプリへの唯一の動線で、親指の届く位置に固定する。
 * 触れるだけでなく上下いずれの掃きでも開く。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CommandBar(onOpenSearch: () -> Unit) {
    val barInteraction = remember { MutableInteractionSource() }
    Column(
        modifier = Modifier.pointerInput(Unit) {
            var travel = 0f
            val threshold = 24.dp.toPx()
            detectVerticalDragGestures(
                onDragStart = { travel = 0f },
                onDragEnd = { if (abs(travel) > threshold) onOpenSearch() },
            ) { _, amount -> travel += amount }
        }
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = ObservatoryDimens.Gutter)
                .fillMaxWidth()
                .height(ObservatoryDimens.Hairline)
                .background(ObservatoryColors.Line)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ObservatoryDimens.Row + 8.dp)
                .pressFade(barInteraction)
                .combinedClickable(
                    interactionSource = barInteraction,
                    indication = null,
                    onClick = onOpenSearch,
                )
                .padding(horizontal = ObservatoryDimens.Gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(ObservatoryDimens.Anchor)
                    .background(ObservatoryColors.Line)
            )
            Spacer(modifier = Modifier.width(12.dp))
            ObservatoryText(
                text = "検索",
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Body,
                tracking = 0.18f,
            )
        }
    }
}

/**
 * 境目を跨いだと確信するための余白。
 *
 * 待ちが要求より早く明けることがあり、そのとき `Date()` は前の秒（分）を返す。
 * 一度でも前を掴むと、次の待ちはまた丸一拍ぶんになり、**そのままずれ続ける**。
 */
private const val TICK_MARGIN_MS = 40L
