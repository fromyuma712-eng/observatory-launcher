package com.yuma.observatorylauncher.ui

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens
import kotlin.math.roundToInt
import com.yuma.observatorylauncher.data.Reading
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import com.yuma.observatorylauncher.ui.theme.WidgetPalette

/**
 * ウィジェットの受け入れ。ホームアプリだけが持てる仕組みで、
 * 他アプリが提供する面をこちらの面の中に置く。
 *
 * 置き場所は弦材と台帳の間——状態(時刻・予定・天気)と台帳の中間にあたる帯で、
 * ここは元々ほぼ空いていた。面の語彙は借り物になるので、周りに枠や飾りを足さず
 * 罫一本で境を示すに留める(他所の意匠をこちらの体系で塗り替えない)。
 *
 * 並べ方は**四分割の桁**による。幅を4/4にすれば面いっぱい、2/4なら半分で、
 * 隣に収まるものがあれば横に並ぶ。桁を使うのは、自由に置かせると
 * 端が揃わなくなり、この体系が最も嫌う「測っていない面」になるため。
 *
 * 操作の言葉はこの体系の簡潔な語(「外す」「名」等)を使わず、**普通の言い方**にする
 * ——ウィジェットは他所から来た概念で、こちらの語彙で言い換えると意味が伝わらない。
 */
private const val HOST_ID = 0x4F42 // "OB"

data class WidgetProviderGroup(
    val appLabel: String,
    val packageName: String,
    val providers: List<AppWidgetProviderInfo>,
)

@Composable
fun rememberWidgetHost(): AppWidgetHost {
    val context = LocalContext.current
    val host = remember { AppWidgetHost(context.applicationContext, HOST_ID) }
    DisposableEffect(host) {
        runCatching { host.startListening() }
        onDispose { runCatching { host.stopListening() } }
    }
    return host
}

/**
 * 選ばれた提供元をこのホストへ結び付ける。OSの確認が必要な端末では確認面を開き、
 * 設定画面を持つものは続けて開く。
 */
@Composable
fun rememberWidgetBinder(
    host: AppWidgetHost,
    onAdded: (Int) -> Unit,
): (AppWidgetProviderInfo) -> Unit {
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }
    val pending = remember { mutableIntStateOf(0) }
    val pendingProvider = remember { mutableStateOf<AppWidgetProviderInfo?>(null) }

    val configure = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = pending.intValue
        if (result.resultCode == Activity.RESULT_OK && id != 0) {
            onAdded(id)
        } else if (id != 0) {
            host.deleteAppWidgetId(id)
        }
        pending.intValue = 0
        pendingProvider.value = null
    }

    val configureOrAdd: (AppWidgetProviderInfo) -> Unit = { info ->
        val id = pending.intValue
        val configureComponent = info.configure
        if (configureComponent != null && id != 0) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
                .setComponent(configureComponent)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            val launched = runCatching { configure.launch(intent) }.isSuccess
            if (!launched) {
                onAdded(id)
                pending.intValue = 0
                pendingProvider.value = null
            }
        } else if (id != 0) {
            onAdded(id)
            pending.intValue = 0
            pendingProvider.value = null
        }
    }

    val bind = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = pending.intValue
        val info = pendingProvider.value
        if (result.resultCode != Activity.RESULT_OK || id == 0 || info == null) {
            if (id != 0) host.deleteAppWidgetId(id)
            pending.intValue = 0
            pendingProvider.value = null
            return@rememberLauncherForActivityResult
        }
        configureOrAdd(info)
    }

    return { info ->
        val id = host.allocateAppWidgetId()
        pending.intValue = id
        pendingProvider.value = info
        if (manager.bindAppWidgetIdIfAllowed(id, info.provider)) {
            configureOrAdd(info)
        } else {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            val ok = runCatching { bind.launch(intent) }.isSuccess
            if (!ok) {
                host.deleteAppWidgetId(id)
                pending.intValue = 0
                pendingProvider.value = null
            }
        }
    }
}

/** 提供元アプリを見出しにし、その中へ各ウィジェットを並べる選択面。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WidgetProviderPicker(
    onSelect: (AppWidgetProviderInfo) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 名札は**一度だけ**引く。`loadLabel` は package manager への問い合わせなので、
    // 描き直しのたびに 97 件ぶん呼ぶと、絞り込みの一打ごとに面が固まる。
    val labels = remember(context) {
        val pm = context.packageManager
        AppWidgetManager.getInstance(context).installedProviders.associate { info ->
            info.provider to (
                info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() }.orEmpty()
                )
        }
    }
    val groups = remember(context, labels) {
        val manager = AppWidgetManager.getInstance(context)
        val pm = context.packageManager
        manager.installedProviders
            .groupBy { it.provider.packageName }
            .map { (packageName, providers) ->
                val appLabel = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
                }.getOrDefault(packageName)
                WidgetProviderGroup(
                    appLabel = appLabel,
                    packageName = packageName,
                    providers = providers.sortedBy {
                        labels[it.provider].orEmpty().lowercase()
                    },
                )
            }
            .sortedBy { it.appLabel.lowercase() }
    }

    // 97 件を目で探すのは現実的でない。アプリの一覧と同じ照合をここにも効かせる
    // ——「かれんだー」と打てばカレンダーの提供分だけが残る。
    var filter by remember { mutableStateOf("") }
    val shown = remember(groups, labels, filter) {
        if (filter.isBlank()) {
            groups
        } else {
            val matcher = Reading.matcher(filter)
            groups.mapNotNull { group ->
                // 提供元アプリの名で当たったなら、その中身は全部残す。
                val byApp = matcher.score(group.appLabel) != null
                val kept = if (byApp) {
                    group.providers
                } else {
                    group.providers.filter { matcher.score(labels[it.provider].orEmpty()) != null }
                }
                if (kept.isEmpty()) null else group.copy(providers = kept)
            }
        }
    }

    ObservatoryPanel(
        modifier = modifier
            .padding(horizontal = ObservatoryDimens.Gutter)
            .padding(top = 8.dp),
    ) {
      Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val closeInteraction = remember { MutableInteractionSource() }
            ObservatoryText(
                text = "ウィジェットを提供アプリから選ぶ",
                color = ObservatoryColors.EmberText,
                size = ObservatoryType.Body,
            )
            Spacer(modifier = Modifier.weight(1f))
            ObservatoryText(
                text = "閉じる",
                color = ObservatoryColors.Paper,
                size = ObservatoryType.Note,
                modifier = Modifier
                    .heightIn(min = ObservatoryDimens.Row)
                    .combinedClickable(
                        interactionSource = closeInteraction,
                        indication = null,
                        onClick = onClose,
                    )
                    .padding(start = 12.dp),
            )
        }

        // 絞り込みは**送っても消えない位置**に置く。走る一覧の中に入れると、
        // 探している最中に見出しごと上へ流れてしまう。
        BasicTextField(
            value = filter,
            onValueChange = { filter = it },
            singleLine = true,
            textStyle = TextStyle(
                color = ObservatoryColors.Paper,
                fontSize = 14.sp,
                fontFamily = FontFamily.SansSerif,
            ),
            cursorBrush = SolidColor(ObservatoryColors.Ember),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ObservatoryDimens.Row)
                .padding(top = 6.dp),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (filter.isEmpty()) {
                        ObservatoryText(
                            text = "絞り込む",
                            color = ObservatoryColors.Muted,
                            size = ObservatoryType.Body,
                        )
                    }
                    field()
                }
            },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ObservatoryDimens.Hairline)
                .background(ObservatoryColors.Line),
        )

        Column(
          modifier = Modifier
              .fillMaxWidth()
              .heightIn(max = 380.dp)
              .verticalScroll(rememberScrollState()),
        ) {
        if (shown.isEmpty()) {
            ObservatoryText(
                text = if (filter.isBlank()) "追加できるウィジェットはありません" else "該当なし",
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Note,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        }
        shown.forEach { group ->
            ObservatoryText(
                text = "${group.appLabel}  ${group.providers.size}",
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Note,
                mono = true,
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
            )
            group.providers.forEach { info ->
                val interaction = remember(info.provider) { MutableInteractionSource() }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ObservatoryDimens.Row)
                        .combinedClickable(
                            interactionSource = interaction,
                            indication = null,
                            onClick = { onSelect(info) },
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(ObservatoryDimens.Anchor)
                            .background(ObservatoryColors.Ember)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    ObservatoryText(
                        text = labels[info.provider]?.takeIf { it.isNotBlank() }
                            ?: group.appLabel,
                        color = ObservatoryColors.Paper,
                        size = ObservatoryType.Body,
                    )
                }
            }
        }
        }
    }
      }

}

/**
 * 置かれているウィジェットの面。四分割の桁に沿って並べ、収まる限り横に並べる。
 * 編集モードの間だけ操作を出す——普段は面をそのまま見せ、触れば提供元の反応に任せる。
 */
@Composable
internal fun WidgetPanel(
    config: LayoutConfig,
    page: Int,
    zone: String,
    host: AppWidgetHost,
    editing: Boolean,
    commands: WidgetCommands,
    modifier: Modifier = Modifier,
) {
    val visibleWidgets = remember(config.widgets, page, zone) {
        config.widgets.filter { it.page == page && it.zone == zone }
    }
    if (visibleWidgets.isEmpty()) return
    val context = LocalContext.current
    val manager = remember { AppWidgetManager.getInstance(context) }

    val rows = remember(visibleWidgets) { packIntoRows(visibleWidgets) { it.span } }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val usable = maxWidth - ObservatoryDimens.Gutter * 2
        Column(modifier = Modifier.fillMaxWidth()) {
            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = ObservatoryDimens.Gutter),
                ) {
                    row.forEachIndexed { i, widget ->
                        val span = widget.span.coerceIn(1, HOME_COLUMNS)
                        val cellWidth = usable * span / HOME_COLUMNS
                        Box(modifier = Modifier.width(cellWidth)) {
                            WidgetCell(
                                widget = widget,
                                info = remember(widget.id) {
                                    runCatching { manager.getAppWidgetInfo(widget.id) }.getOrNull()
                                },
                                host = host,
                                editing = editing,
                                index = visibleWidgets.indexOf(widget),
                                total = visibleWidgets.size,
                                pageCount = config.pageCount,
                                commands = commands,
                            )
                        }
                        if (i < row.lastIndex) Spacer(modifier = Modifier.width(8.dp))
                    }
                }
                Box(
                    modifier = Modifier
                        .padding(horizontal = ObservatoryDimens.Gutter)
                        .fillMaxWidth()
                        .height(ObservatoryDimens.Hairline)
                        .background(ObservatoryColors.Line)
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WidgetCell(
    widget: LayoutConfig.Widget,
    info: AppWidgetProviderInfo?,
    host: AppWidgetHost,
    editing: Boolean,
    index: Int,
    total: Int,
    pageCount: Int,
    commands: WidgetCommands,
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    if (info == null) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ObservatoryDimens.Row)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { commands.placement.remove(widget.id) },
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ObservatoryText(
                text = "表示できないウィジェット（押すと削除します）",
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Note,
            )
        }
        return
    }

    // ProviderInfoの寸法はpx。dpとして直に扱うと高密度端末で過大になり、
    // 0を申告するショートカット型は逆に内容の基準寸法を受け取れず崩れる。
    //
    // **`minHeight` は「これ未満だと壊れる」という下限であって、望ましい大きさではない。**
    // これを初期値にしていたため、どのウィジェットも一番小さい姿で置かれていた。
    // 作者が「何セル分で作ったか」を申告していれば(`targetCellHeight`)、そちらを採る。
    val autoHeight = remember(info, widget.span) {
        val floor = if (info.minHeight > 0) {
            with(density) { info.minHeight.toDp().value.roundToInt() }
        } else {
            if (widget.span <= 1) 96 else 150
        }
        val intended = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            info.targetCellHeight.takeIf { it > 0 }?.times(LayoutConfig.WIDGET_CELL_DP)
        } else {
            null
        }
        maxOf(floor, intended ?: 0).coerceIn(72, 640)
    }
    // つまみを引いている間の高さ。離した時点で宣言へ書き戻す。
    var dragging by remember(widget.id) { mutableStateOf<Int?>(null) }
    val heightDp = dragging ?: if (widget.height == 0) autoHeight else widget.height

    Column(modifier = Modifier.fillMaxWidth()) {
        if (editing) {
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(ObservatoryDimens.Anchor)
                        .background(ObservatoryColors.Ember)
                )
                Spacer(modifier = Modifier.width(8.dp))
                ObservatoryText(
                    text = info.loadLabel(context.packageManager) ?: "ウィジェット",
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Note,
                )
            }
            ActionStrip(
                startPadding = 0.dp,
                actions = buildList {
                    add(
                        LedgerAction(
                            if (widget.zone == LayoutConfig.ZONE_ABOVE) {
                                "トラスの下へ"
                            } else {
                                "トラスの上へ"
                            }
                        ) { commands.placement.toggleZone(widget.id) }
                    )
                    if (widget.page > 0) {
                        add(LedgerAction("前のページへ") { commands.placement.movePage(widget.id, -1) })
                    }
                    if (widget.page < pageCount - 1) {
                        add(LedgerAction("次のページへ") { commands.placement.movePage(widget.id, +1) })
                    }
                    add(LedgerAction("幅：${widget.span}/4") { commands.placement.cycleSpan(widget.id) })
                    add(
                        LedgerAction(
                            "高さ：" + LayoutConfig.widgetHeightLabel(widget.height)
                        ) { commands.cycleHeight(widget.id) }
                    )
                    if (index > 0) add(LedgerAction("前へ移動") { commands.placement.move(widget.id, -1) })
                    if (index < total - 1) add(LedgerAction("後ろへ移動") { commands.placement.move(widget.id, +1) })
                    add(LedgerAction("このウィジェットを削除") { commands.placement.remove(widget.id) })
                },
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .height(heightDp.dp)
        ) {
            val widthDp = maxWidth.value.roundToInt().coerceAtLeast(1)
            AndroidView(
                factory = { ctx ->
                    host.createView(ctx.applicationContext, widget.id, info).apply {
                        setAppWidget(widget.id, info)
                        // **中身は向こうの持ち物だが、色だけは渡せる。**
                        // 動的配色で組まれたウィジェットは、これで 02 の色になる。
                        // 色を直に書いているものと、暦ごとの色（利用者が決めた色）は
                        // 変わらない——後者は変えてはならない。
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            runCatching { setColorResources(WidgetPalette.observatory()) }
                        }
                    }
                },
                update = { view ->
                    // 実際に与えた枠を提供元へ通知する。通知がないと、Claude等の
                    // 小型ショートカット型が初期寸法のまま描画されることがある。
                    view.updateSize(widthDp, heightDp)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (editing) {
            // 高さのつまみ。段の選択では届かない寸法を、引いて決められるようにする。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(18.dp)
                    .pointerInput(widget.id) {
                        detectDragGestures(
                            onDragStart = { dragging = heightDp },
                            onDragEnd = {
                                dragging?.let { commands.setHeight(widget.id, it) }
                                dragging = null
                            },
                            onDragCancel = { dragging = null },
                        ) { change, drag ->
                            change.consume()
                            val delta = with(density) { drag.y.toDp().value.toInt() }
                            dragging = ((dragging ?: heightDp) + delta).coerceIn(64, 640)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(ObservatoryDimens.Hairline * 2)
                        .background(ObservatoryColors.Ember)
                )
            }
            ObservatoryText(
                text = "つまみを上下に引くと高さが変わります（${heightDp}dp）",
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Note,
            )
        }
    }
}

@Suppress("DEPRECATION")
private fun AppWidgetHostView.updateSize(widthDp: Int, heightDp: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        updateAppWidgetSize(Bundle.EMPTY, listOf(SizeF(widthDp.toFloat(), heightDp.toFloat())))
    } else {
        updateAppWidgetSize(Bundle.EMPTY, widthDp, heightDp, widthDp, heightDp)
    }
}
