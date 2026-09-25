package com.yuma.observatorylauncher.ui

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.data.AppRepository
import com.yuma.observatorylauncher.data.Glance
import com.yuma.observatorylauncher.data.InfoAccess
import com.yuma.observatorylauncher.data.InfoSources
import com.yuma.observatorylauncher.data.InfoValue
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import com.yuma.observatorylauncher.data.Habit

data class InfoCatalogItem(
    val kind: String,
    val group: String,
    val label: String,
    val detail: String,
    val needsEditor: Boolean = false,
)

val INFO_CATALOG = listOf(
    InfoCatalogItem(LayoutConfig.INFO_BATTERY, "端末", "バッテリー", "残量"),
    InfoCatalogItem(
        LayoutConfig.INFO_BATTERY_DETAIL,
        "端末",
        "充電状態・完了予測",
        "充放電、温度、充電完了まで",
    ),
    InfoCatalogItem(LayoutConfig.INFO_DEVICE, "端末", "端末状態", "通信、機内モード、空き容量"),
    InfoCatalogItem(LayoutConfig.INFO_DATE, "端末", "日付情報", "週番号、今年の何日目"),
    InfoCatalogItem(LayoutConfig.INFO_SOUND, "端末", "音・おやすみモード", "着信音と通知制限"),
    InfoCatalogItem(LayoutConfig.INFO_BLUETOOTH, "端末", "Bluetooth", "状態と登録済み機器"),
    InfoCatalogItem(LayoutConfig.INFO_CALENDAR, "予定", "カレンダー", "直近7日、最大3件"),
    InfoCatalogItem(
        LayoutConfig.INFO_NEXT_ACTION,
        "予定",
        "次の行動",
        "いま何の最中か、次は何か、あと何分か",
    ),
    InfoCatalogItem(LayoutConfig.INFO_CALENDAR_MONTH, "予定", "月の格子", "今月の予定を格子で"),
    InfoCatalogItem(
        LayoutConfig.INFO_CALENDAR_TIMELINE,
        "予定",
        "今日の時間軸",
        "今日の予定を一本の軸に横棒で。空きが読める",
    ),
    InfoCatalogItem(LayoutConfig.INFO_HABIT, "予定", "習慣", "続けたいことを日ごとに付ける", true),
    InfoCatalogItem(LayoutConfig.INFO_ALARM, "予定", "次のアラーム", "OSの次回アラーム"),
    InfoCatalogItem(LayoutConfig.INFO_NOTE, "予定", "短いメモ", "任意の見出しと本文", true),
    InfoCatalogItem(LayoutConfig.INFO_SCHEDULE, "予定", "授業・時間割", "手入力の授業予定", true),
    InfoCatalogItem(LayoutConfig.INFO_DEADLINE, "予定", "提出・試験期限", "日時までの残り時間", true),
    InfoCatalogItem(LayoutConfig.INFO_WEATHER, "天気・環境", "天気", "気温、最低・最高、降水"),
    InfoCatalogItem(
        LayoutConfig.INFO_WEATHER_DETAIL,
        "天気・環境",
        "天気詳細",
        "体感温度、湿度、風、UV、日の出入り",
    ),
    InfoCatalogItem(
        LayoutConfig.INFO_AIR_QUALITY,
        "天気・環境",
        "大気質・花粉",
        "地点と情報提供元の設定が必要",
        true,
    ),
    InfoCatalogItem(LayoutConfig.INFO_NOTIFICATIONS, "アクセスが必要", "未読通知", "通知へのアクセス"),
    InfoCatalogItem(LayoutConfig.INFO_MEDIA, "アクセスが必要", "現在再生中", "通知へのアクセス"),
    InfoCatalogItem(LayoutConfig.INFO_MUSIC, "アクセスが必要", "再生中の一曲", "曲名・演者・位置の目盛り・操作"),
    InfoCatalogItem(LayoutConfig.INFO_USAGE, "アクセスが必要", "画面使用時間", "使用状況へのアクセス"),
    InfoCatalogItem(
        LayoutConfig.INFO_USAGE_BARS,
        "アクセスが必要",
        "使用時間の内訳",
        "アプリ別に多い順の横棒。使用状況へのアクセス",
    ),
    InfoCatalogItem(
        LayoutConfig.INFO_TRAFFIC,
        "アクセスが必要",
        "通信量",
        "今日と今月、アプリ別の内訳。使用状況へのアクセス",
    ),
    InfoCatalogItem(
        LayoutConfig.INFO_CHARGE,
        "端末",
        "充電の実測",
        "電流・電圧・電力と残量の推移",
    ),
    InfoCatalogItem(
        LayoutConfig.INFO_SPACE,
        "アクセスが必要",
        "保存領域の内訳",
        "空きと、大きいアプリの並び。使用状況へのアクセス",
    ),
    InfoCatalogItem(
        LayoutConfig.INFO_ATTENTION,
        "アクセスが必要",
        "注意の会計",
        "摩擦を掛けたアプリの時間・開かなかった回数・生まれた時間",
    ),
    InfoCatalogItem(
        LayoutConfig.INFO_TRAFFIC_WEEK,
        "アクセスが必要",
        "通信量の七日",
        "モバイル通信の日ごとの棒",
    ),
    InfoCatalogItem(
        LayoutConfig.INFO_STEPS_WEEK,
        "アクセスが必要",
        "歩数の七日",
        "日ごとの歩数。Health Connectの許可が要る",
    ),
    InfoCatalogItem(
        LayoutConfig.INFO_PACE,
        "アクセスが必要",
        "今日の歩調",
        "この時刻までの使用と、ふだんの同時刻との差",
    ),
    InfoCatalogItem(
        LayoutConfig.INFO_OPENS,
        "アクセスが必要",
        "ひらいた回数",
        "今日ひらいた回数と、一回あたりの長さ",
    ),
    InfoCatalogItem(
        LayoutConfig.INFO_DAY_SHAPE,
        "アクセスが必要",
        "時刻別の分布",
        "時間帯ごとの使用と、最も長い一続き",
    ),
    InfoCatalogItem(
        LayoutConfig.INFO_USAGE_HEAT,
        "アクセスが必要",
        "ひと月の使い方",
        "日ごとの使用時間を格子の濃淡で",
    ),
    InfoCatalogItem(
        LayoutConfig.INFO_SPREAD,
        "アクセスが必要",
        "七日の五数要約",
        "使用時間の箱ひげ図",
    ),
    InfoCatalogItem(
        LayoutConfig.INFO_TOUCH,
        "アクセスが必要",
        "解錠回数",
        "今日ひらいた回数と、最初・最後に触った時刻",
    ),
    InfoCatalogItem(LayoutConfig.INFO_HEALTH, "アクセスが必要", "歩数・睡眠・心拍", "歩数・睡眠・心拍。Health Connectの許可が要る"),
    InfoCatalogItem(LayoutConfig.INFO_TRANSIT, "外部サービス", "電車遅延・次の電車", "提供元と表示値を設定", true),
    InfoCatalogItem(LayoutConfig.INFO_COMMUTE, "外部サービス", "目的地までの時間", "提供元と表示値を設定", true),
    InfoCatalogItem(LayoutConfig.INFO_MARKET, "外部サービス", "為替・株価", "提供元と表示値を設定", true),
    InfoCatalogItem(LayoutConfig.INFO_NEWS, "外部サービス", "ニュース", "提供元と見出しを設定", true),
)

fun infoTileLabel(kind: String): String =
    INFO_CATALOG.firstOrNull { it.kind == kind }?.label ?: "情報"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun InfoTilePicker(
    onSelect: (InfoCatalogItem) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 430.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ObservatoryDimens.Gutter),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val closeInteraction = remember { MutableInteractionSource() }
            ObservatoryText(
                text = "OBSERVATORY情報を追加",
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
                        indication = PressIndication.Row,
                        onClick = onClose,
                    )
                    .padding(start = 12.dp),
            )
        }
        INFO_CATALOG.groupBy { it.group }.forEach { (group, entries) ->
            ObservatoryText(
                text = "$group  ${entries.size}",
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Note,
                mono = true,
                modifier = Modifier.padding(top = 12.dp, bottom = 2.dp),
            )
            entries.forEach { entry ->
                key(entry.kind) {
                    val interaction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 54.dp)
                            .combinedClickable(
                                interactionSource = interaction,
                                indication = PressIndication.Row,
                                onClick = { onSelect(entry) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(ObservatoryDimens.Anchor)
                                .background(ObservatoryColors.Ember)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            ObservatoryText(entry.label, ObservatoryColors.Paper, 14.sp)
                            ObservatoryText(entry.detail, ObservatoryColors.Muted, ObservatoryType.Note)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun InfoTileEditor(
    tile: LayoutConfig.InfoTile,
    onSave: (LayoutConfig.InfoTile) -> Unit,
    onClose: () -> Unit,
) {
    var title by remember(tile.id) { mutableStateOf(TextFieldValue(tile.title)) }
    var value by remember(tile.id) { mutableStateOf(TextFieldValue(tile.value)) }
    var source by remember(tile.id) { mutableStateOf(TextFieldValue(tile.source)) }
    var target by remember(tile.id) {
        mutableStateOf(
            TextFieldValue(
                tile.targetAt.takeIf { it > 0 }?.let {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN).format(Date(it))
                }.orEmpty()
            )
        )
    }
    var error by remember(tile.id) { mutableStateOf<String?>(null) }
    val needsTarget = tile.kind == LayoutConfig.INFO_DEADLINE
    val external = tile.kind in setOf(
        LayoutConfig.INFO_AIR_QUALITY,
        LayoutConfig.INFO_TRANSIT,
        LayoutConfig.INFO_COMMUTE,
        LayoutConfig.INFO_MARKET,
        LayoutConfig.INFO_NEWS,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ObservatoryDimens.Gutter, vertical = 8.dp)
            .border(ObservatoryDimens.Hairline, ObservatoryColors.Line)
            .padding(top = 10.dp),
    ) {
        ObservatoryText(
            text = "${infoTileLabel(tile.kind)}を設定",
            color = ObservatoryColors.EmberText,
            size = ObservatoryType.Body,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        InfoField("見出し", title) { title = it; error = null }
        InfoField(
            if (external) "現在表示する値・見出し" else "本文・予定内容",
            value,
        ) { value = it; error = null }
        if (needsTarget) {
            InfoField("期限（例 2026-09-01 09:00）", target) { target = it; error = null }
        }
        if (external) {
            InfoField("情報提供元URL（任意・自動取得はまだ行わない）", source) {
                source = it
                error = null
            }
        }
        error?.let {
            ObservatoryText(
                it,
                ObservatoryColors.EmberText,
                ObservatoryType.Note,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        ActionStrip(
            startPadding = 12.dp,
            actions = listOf(
                LedgerAction("保存") {
                    val targetAt = if (needsTarget) {
                        runCatching {
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN).apply {
                                isLenient = false
                            }.parse(target.text.trim())?.time ?: 0L
                        }.getOrDefault(0L)
                    } else {
                        tile.targetAt
                    }
                    if (needsTarget && targetAt <= 0L) {
                        error = "期限の形式を確認してください"
                    } else {
                        onSave(
                            tile.copy(
                                title = title.text,
                                value = value.text,
                                source = source.text,
                                targetAt = targetAt,
                            )
                        )
                    }
                },
                LedgerAction("閉じる", onClose),
            ),
        )
    }
}

@Composable
private fun InfoField(
    label: String,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)) {
        ObservatoryText(label, ObservatoryColors.Muted, ObservatoryType.Note)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            cursorBrush = SolidColor(ObservatoryColors.Ember),
            textStyle = TextStyle(
                color = ObservatoryColors.Paper,
                fontSize = ObservatoryType.Body,
                fontFamily = ObservatoryFonts.Sans,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .border(ObservatoryDimens.Hairline, ObservatoryColors.Line)
                .padding(horizontal = 9.dp, vertical = 9.dp),
        )
    }
}

@Composable
internal fun InfoTilePanel(
    tiles: List<LayoutConfig.InfoTile>,
    pageCount: Int,
    editing: Boolean,
    commands: TileCommands,
    modifier: Modifier = Modifier,
    refreshEpoch: Int = 0,
    /** 摩擦を掛けたアプリ。注意の会計の札だけが使う。 */
    frictionApps: Set<String> = emptySet(),
) {
    if (tiles.isEmpty()) return
    val rows = remember(tiles) { packIntoRows(tiles) { it.span } }
    Column(modifier = modifier.fillMaxWidth()) {
        rows.forEach { row ->
            val used = row.sumOf { it.span.coerceIn(1, HOME_COLUMNS) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ObservatoryDimens.Gutter),
            ) {
                row.forEachIndexed { index, tile ->
                    Box(modifier = Modifier.weight(tile.span.coerceIn(1, HOME_COLUMNS).toFloat())) {
                        InfoTileCell(
                            tile = tile,
                            editing = editing,
                            index = tiles.indexOf(tile),
                            total = tiles.size,
                            pageCount = pageCount,
                            commands = commands,
                            refreshEpoch = refreshEpoch,
                            frictionApps = frictionApps,
                        )
                    }
                    if (index < row.lastIndex) Spacer(modifier = Modifier.width(8.dp))
                }
                if (used < 4) Spacer(modifier = Modifier.weight((4 - used).toFloat()))
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InfoTileCell(
    tile: LayoutConfig.InfoTile,
    editing: Boolean,
    index: Int,
    total: Int,
    pageCount: Int,
    commands: TileCommands,
    refreshEpoch: Int,
    frictionApps: Set<String>,
) {
    val context = LocalContext.current
    var content by remember(tile.id) { mutableStateOf(InfoValue("読み込み中")) }
    var refresh by remember(tile.id) { mutableIntStateOf(0) }
    val interaction = remember(tile.id) { MutableInteractionSource() }

    LaunchedEffect(tile, refresh, refreshEpoch) {
        content = loadInfoValue(context, tile)
    }
    LaunchedEffect(tile.id) {
        while (true) {
            delay(60_000L)
            refresh += 1
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interaction,
                indication = PressIndication.Row,
                onClick = {
                    when {
                        // 許可が要るなら、まずそれを求める。
                        content.access != InfoAccess.NONE -> commands.requestAccess(content.access)
                        // 予定を映している札は、押せば暦へ入る。
                        // **見ている中身の出どころへ行けること**が、押した人の期待である
                        // （本人指示・2026/9/1。時計を押せば時計アプリへ入るのと対にする）。
                        tile.kind in CALENDAR_KINDS -> AppRepository.openCalendar(context)
                        else -> refresh += 1
                    }
                },
            )
            .padding(vertical = 7.dp),
    ) {
        // 見出しは編集中だけ出す。「32° 降水78%」が天気であることは値を見れば分かり、
        // 常時添えると情報と情報の間に説明が挟まって面が読みにくくなる。
        // ただし編集中は、どの札を動かしているのか分からなくなるので出す。
        // 許可が要る札だけは、押せば済むことが分からないと詰まるので常に添える。
        val needsAccess = content.access != InfoAccess.NONE
        if (editing || needsAccess) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(ObservatoryDimens.Anchor)
                        .background(
                            if (needsAccess) ObservatoryColors.Line else ObservatoryColors.Ember
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                ObservatoryText(
                    text = tile.title.ifBlank { infoTileLabel(tile.kind) },
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Note,
                )
            }
        }
        // ## 図の札は表で引く
        //
        // ⚠️ ここは `else if` が二十本並び、**同じ形の十二行が十八回**書いてあった
        // （許可を確かめ、無ければ一行出し、あれば札を描く）。
        //
        // 形が同じものを繰り返し書くと、**足すたびに写し間違える**し、
        // 直すときは全部を直すことになる。表に移した。
        //
        // 表に載らないのは[LayoutConfig.INFO_HABIT]だけ——
        // あれは札の宣言そのもの（`tile`）と書き戻しの手（`commands`）が要る。
        val slot = FIGURE_SLOTS[tile.kind]
        val figurePad = Modifier.padding(top = if (editing) 5.dp else 0.dp)
        if (tile.kind == LayoutConfig.INFO_HABIT) {
            // 暦と同じ格子で、源だけが違う。押すとその日の印が付け外しされ、
            // 宣言へ書き戻る——**世代に残るので「元に戻す」で取り消せる。**
            MonthTile(
                modifier = figurePad,
                source = MonthSource.Habit(
                    record = tile.value,
                    onToggle = { date ->
                        commands.update(tile.copy(value = Habit.toggled(tile.value, date)))
                    },
                ),
                refreshKey = refresh + refreshEpoch,
            )
        } else if (slot != null) {
            if (slot.gate?.invoke(context) == false) {
                MissingAccess(slot.missing)
            } else {
                slot.draw(figurePad, refresh + refreshEpoch, frictionApps)
            }
        } else {
        // **添えの値は右へ置く。台帳は名を左に、値を右に書く。**
        //
        // 見出しの下へ積むと、行は左端に寄り、面の右半分が空いたままになる。
        // 一行だけの添えなら、同じ行の右端へ寄せれば幅が埋まり、縦も詰まる。
        // 二行以上あるもの（予定の並びなど）は、右へ寄せると読む順が崩れるので積む。
        // 予定は**次の一件を左、その後を右**に置く。
        // 縦に積むと左端へ寄って右が空き、しかも「次」と「その後」が同じ重さに見える。
        // 左右に分ければ、**どれが次か**が置き場所だけで分かる。
        val asAgenda = tile.kind == LayoutConfig.INFO_CALENDAR &&
            content.lines.isNotEmpty() &&
            content.access == InfoAccess.NONE &&
            tile.span >= 3
        val asLedger = !asAgenda && content.lines.size == 1 && tile.span >= 3
        val headSize = if (tile.span <= 1) ObservatoryType.Label else ObservatoryType.Note
        val headTop = if (editing || needsAccess) 5.dp else 0.dp
        if (asAgenda) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = headTop),
                verticalAlignment = Alignment.Top,
            ) {
                // 次の一件。**面の主**なので左に、大きく。
                ObservatoryText(
                    text = content.headline,
                    color = ObservatoryColors.Paper,
                    size = headSize,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(16.dp))
                // その後の予定。右端へ寄せて縦に積む。
                Column(horizontalAlignment = Alignment.End) {
                    content.lines.take(3).forEachIndexed { at, line ->
                        ObservatoryText(
                            text = line,
                            color = ObservatoryColors.EmberText,
                            size = ObservatoryType.Note,
                            modifier = Modifier.padding(top = if (at == 0) 0.dp else 2.dp),
                        )
                    }
                }
            }
        } else if (asLedger) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = headTop),
                verticalAlignment = Alignment.Bottom,
            ) {
                ObservatoryText(
                    text = content.headline,
                    color = ObservatoryColors.Paper,
                    size = headSize,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(12.dp))
                ObservatoryText(
                    text = content.lines.first(),
                    color = ObservatoryColors.EmberText,
                    size = ObservatoryType.Note,
                )
            }
        } else {
            ObservatoryText(
                text = content.headline,
                color = ObservatoryColors.Paper,
                size = headSize,
                modifier = Modifier.fillMaxWidth().padding(top = headTop),
            )
            // ⚠️ **添えの行を燠火で書くのをやめた（2026/9/1）。**
            //
            // 燠火は「**今まさに動いているもの**」の一語だけを担う色である（意匠帳 §1）。
            // 添えの行は「今」ではなく「詳しく」であって、動いてもいない。
            // 天気を一枚に畳んだ結果、**燠火の二行が面の真ん中に横たわり**、
            // 何かが起きているように見えた。一つの色に二つの意味を持たせると、
            // 意味は両方とも死ぬ。
            //
            // 予定の右列（`asAgenda`）と一行値（`asLedger`）はそのまま燠火にしてある
            // ——あちらは色が**置き場所と対で意味を作っている**。
            content.lines.take(if (tile.span <= 1) 1 else 3).forEach { line ->
                ObservatoryText(
                    text = line,
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Note,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp),
                )
            }
        }
        }
        if (editing) {
            ActionStrip(
                startPadding = 0.dp,
                actions = buildList {
                    add(
                        LedgerAction(
                            if (tile.zone == LayoutConfig.ZONE_ABOVE) {
                                "トラスの下へ"
                            } else {
                                "トラスの上へ"
                            }
                        ) { commands.placement.toggleZone(tile.id) }
                    )
                    if (tile.page > 0) add(LedgerAction("前ページへ") { commands.placement.movePage(tile.id, -1) })
                    if (tile.page < pageCount - 1) {
                        add(LedgerAction("次ページへ") { commands.placement.movePage(tile.id, 1) })
                    }
                    add(LedgerAction("幅：${tile.span}/4") { commands.placement.cycleSpan(tile.id) })
                    if (tile.kind in EDITABLE_INFO_KINDS) {
                        add(LedgerAction("内容を編集") { commands.edit(tile) })
                    }
                    if (index > 0) add(LedgerAction("前へ") { commands.placement.move(tile.id, -1) })
                    if (index < total - 1) add(LedgerAction("後ろへ") { commands.placement.move(tile.id, 1) })
                    add(LedgerAction("削除") { commands.placement.remove(tile.id) })
                },
            )
        }
    }
}

private suspend fun loadInfoValue(context: Context, tile: LayoutConfig.InfoTile): InfoValue =
    when (tile.kind) {
        LayoutConfig.INFO_WEATHER -> {
            if (!Glance.hasLocationPermission(context)) {
                InfoValue("位置情報の許可が必要", access = InfoAccess.LOCATION)
            } else {
                Glance.weather(context)?.let { weather ->
                    InfoValue(
                        headline = listOf(
                            weather.sky.takeIf { it.isNotBlank() },
                            "${weather.temperature.roundToInt()}°",
                            "降水${weather.precipitation}%",
                        ).filterNotNull().joinToString("  "),
                        lines = listOfNotNull(
                            "最低${weather.low.roundToInt()}° 最高${weather.high.roundToInt()}°",
                            weatherOrigin(weather),
                        ),
                    )
                } ?: InfoValue("天気を取得できません")
            }
        }
        // 天気と詳細をひとつに。**同じ一度の取得**を使うので通信は増えない。
        // 二枚に分けていたのは頁で選べるようにするためで、
        // 全部載せる面では分ける理由が無い——見出しが二つに割れるだけである。
        LayoutConfig.INFO_WEATHER_FULL -> {
            if (!Glance.hasLocationPermission(context)) {
                InfoValue("位置情報の許可が必要", access = InfoAccess.LOCATION)
            } else {
                Glance.weather(context)?.let { weather ->
                    val detail = buildList {
                        weather.apparent?.let { add("体感${it.roundToInt()}°") }
                        weather.humidity?.let { add("湿度${it}%") }
                        weather.wind?.let { add("風%.0fkm/h".format(it)) }
                        weather.uv?.let { add("UV%.0f".format(it)) }
                    }
                    InfoValue(
                        // **空模様を先に置く。** 数字より先に知りたいのは
                        // 「今日は雨か」であって、何度かではない。
                        headline = listOf(
                            weather.sky.takeIf { it.isNotBlank() },
                            "${weather.temperature.roundToInt()}°",
                            "${weather.low.roundToInt()}–${weather.high.roundToInt()}°",
                            "降水${weather.precipitation}%",
                        ).filterNotNull().joinToString("  "),
                        lines = listOfNotNull(
                            detail.joinToString("  ").takeIf { it.isNotBlank() },
                            weather.sunrise?.let { rise ->
                                weather.sunset?.let { set -> "日の出 $rise  日の入り $set" }
                            },
                            // **出所を隠さない。**
                            // どこの空か（地名）と、位置が取れなかった旨。
                            // **今日ひととおりの空**は、今の空と違うときだけ添える。
                            // 同じなら見出しが既に言っている——**同じことを二度書かない。**
                            weather.skyToday
                                .takeIf { it.isNotBlank() && it != weather.sky }
                                ?.let { "今日はおおむね $it" },
                            weatherOrigin(weather),
                        ),
                    )
                } ?: InfoValue("天気を取得できません")
            }
        }
        LayoutConfig.INFO_DEVICE_BRIEF -> InfoSources.deviceBrief(context)
        LayoutConfig.INFO_DATE_BRIEF -> InfoSources.dateBrief()
        LayoutConfig.INFO_WEATHER_DETAIL -> {
            if (!Glance.hasLocationPermission(context)) {
                InfoValue("位置情報の許可が必要", access = InfoAccess.LOCATION)
            } else {
                // 天気の札と**同じ一度の取得**を共有する(30分保持)。
                // 二つ並べても外へは一度しか問い合わせない。
                Glance.weather(context)?.let { weather ->
                    val detail = buildList {
                        weather.humidity?.let { add("湿度${it}%") }
                        weather.wind?.let { add("風%.0fkm/h".format(it)) }
                        weather.uv?.let { add("UV%.0f".format(it)) }
                    }
                    InfoValue(
                        headline = weather.apparent
                            ?.let { "体感${it.roundToInt()}°  " + detail.joinToString(" ") }
                            ?: detail.joinToString(" ").ifBlank { "詳細を取得できません" },
                        lines = listOfNotNull(
                            weather.sunrise?.let { rise ->
                                weather.sunset?.let { set -> "日の出 $rise  日の入り $set" }
                            },
                        ),
                    )
                } ?: InfoValue("天気を取得できません")
            }
        }
        LayoutConfig.INFO_CALENDAR -> {
            if (!Glance.hasCalendarPermission(context)) {
                InfoValue("カレンダーの許可が必要", access = InfoAccess.CALENDAR)
            } else {
                val appointments = Glance.appointments(context)
                if (appointments.isEmpty()) {
                    InfoValue("直近7日間の予定なし")
                } else {
                    InfoValue(
                        headline = appointments.first().let {
                            "${it.dayLabel(System.currentTimeMillis())} ${it.timeLabel()}  ${it.title}"
                        },
                        lines = appointments.drop(1).map {
                            "${it.dayLabel(System.currentTimeMillis())} ${it.timeLabel()}  ${it.title}"
                        },
                    )
                }
            }
        }
        // 格子そのものは[MonthTile]が描く。ここは許可の有無だけを答える。
        LayoutConfig.INFO_CALENDAR_MONTH -> if (Glance.hasCalendarPermission(context)) {
            InfoValue("")
        } else {
            InfoValue("カレンダーの許可が必要", access = InfoAccess.CALENDAR)
        }
        LayoutConfig.INFO_ALARM -> InfoValue(
            AppRepository.nextAlarm(context)?.let { "次のアラーム $it" } ?: "アラーム設定なし"
        )
        LayoutConfig.INFO_BATTERY -> InfoSources.battery(context, detailed = false)
        LayoutConfig.INFO_BATTERY_DETAIL -> InfoSources.battery(context, detailed = true)
        LayoutConfig.INFO_DEVICE -> InfoSources.device(context)
        LayoutConfig.INFO_DATE -> InfoSources.dateFacts()
        LayoutConfig.INFO_NOTIFICATIONS -> InfoSources.notifications(context)
        LayoutConfig.INFO_MEDIA -> InfoSources.media(context)
        // 中身は[MusicTile]が描く。ここは許可の有無だけを答える。
        LayoutConfig.INFO_MUSIC -> if (InfoSources.hasNotificationAccess(context)) {
            InfoValue("")
        } else {
            InfoValue("通知へのアクセスが必要", access = InfoAccess.NOTIFICATIONS)
        }
        LayoutConfig.INFO_USAGE -> InfoSources.usage(context)
        LayoutConfig.INFO_SOUND -> InfoSources.sound(context)
        LayoutConfig.INFO_BLUETOOTH -> InfoSources.bluetooth(context)
        LayoutConfig.INFO_HEALTH -> InfoSources.health(context)
        LayoutConfig.INFO_DEADLINE -> deadlineValue(tile)
        LayoutConfig.INFO_NOTE,
        LayoutConfig.INFO_SCHEDULE -> manualValue(tile)
        LayoutConfig.INFO_AIR_QUALITY,
        LayoutConfig.INFO_TRANSIT,
        LayoutConfig.INFO_COMMUTE,
        LayoutConfig.INFO_MARKET,
        LayoutConfig.INFO_NEWS -> if (tile.value.isBlank()) {
            InfoValue("情報提供元と表示値を設定してください")
        } else {
            InfoValue(
                headline = tile.value.lineSequence().firstOrNull().orEmpty(),
                lines = tile.value.lineSequence().drop(1).toList() +
                    listOfNotNull(tile.source.takeIf { it.isNotBlank() }?.let { "出典 $it" }),
            )
        }
        else -> InfoValue("未対応の情報です")
    }

private fun manualValue(tile: LayoutConfig.InfoTile): InfoValue {
    val lines = tile.value.lineSequence().filter { it.isNotBlank() }.toList()
    return InfoValue(
        headline = lines.firstOrNull() ?: "内容を設定してください",
        lines = lines.drop(1),
    )
}

private fun deadlineValue(tile: LayoutConfig.InfoTile): InfoValue {
    if (tile.targetAt <= 0L) return InfoValue("期限を設定してください")
    val delta = tile.targetAt - System.currentTimeMillis()
    val absMinutes = TimeUnit.MILLISECONDS.toMinutes(kotlin.math.abs(delta))
    val days = absMinutes / (24 * 60)
    val hours = (absMinutes / 60) % 24
    val remaining = if (delta >= 0) {
        if (days > 0) "あと${days}日${hours}時間" else "あと${hours}時間"
    } else {
        if (days > 0) "期限超過 ${days}日" else "期限超過"
    }
    val date = SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date(tile.targetAt))
    return InfoValue(remaining, listOf(date, tile.value).filter { it.isNotBlank() })
}



val EDITABLE_INFO_KINDS = setOf(
    LayoutConfig.INFO_NOTE,
    LayoutConfig.INFO_SCHEDULE,
    LayoutConfig.INFO_DEADLINE,
    LayoutConfig.INFO_AIR_QUALITY,
    LayoutConfig.INFO_TRANSIT,
    LayoutConfig.INFO_COMMUTE,
    LayoutConfig.INFO_MARKET,
    LayoutConfig.INFO_NEWS,
    LayoutConfig.INFO_HABIT,
)

/**
 * 押せば暦へ入る札。
 *
 * 月の格子（`INFO_CALENDAR_MONTH`）は**入れない**——
 * あちらは枡ごとに日付を持っており、押した日を映して開く方が正しい。
 * ここへ入れると、どの日を押しても今日が開くことになる。
 */
private val CALENDAR_KINDS = setOf(
    LayoutConfig.INFO_CALENDAR,
    LayoutConfig.INFO_CALENDAR_TIMELINE,
    LayoutConfig.INFO_SCHEDULE,
    LayoutConfig.INFO_NEXT_ACTION,
)

/**
 * 位置が取れなかったときの断り書き。
 *
 * **仮の値と本物を同じ顔で並べない。** どこの空を出しているのかは、
 * 数字と同じ場所に書いてあってはじめて伝わる。
 */
private const val APPROXIMATE_NOTE = "位置は概略（大阪）"

/**
 * どこの空を出しているのか。
 *
 * **仮の値と本物を同じ顔で並べない。** 位置が取れていれば地名を、
 * 取れていなければ既定の点で引いた旨を書く。
 * どちらも書けないときは何も足さない——**空欄を作り話で埋めない。**
 */
private fun weatherOrigin(weather: com.yuma.observatorylauncher.data.Weather): String? {
    val where = when {
        weather.approximate -> APPROXIMATE_NOTE
        weather.place.isNotBlank() -> weather.place + "の空"
        else -> null
    }
    // 値の出所も添える（2026-09-25・気象庁の値を重ねるようになったため）。
    return listOfNotNull(where, weather.source.takeIf { it.isNotBlank() }).joinToString("  ∧  ").ifBlank { null }
}


/**
 * 図で描く札の表。
 *
 * ## なぜ表にしたか
 *
 * 一つ足すたびに「許可を確かめ、無ければ一行出し、あれば札を描く」十二行を
 * 写していた。十八回写せば、**いつか写し間違える**。
 * 表なら一行で足りるし、門の掛け違いも一目で分かる。
 *
 * @property gate 許可の門。要らない札は null。
 * @property missing 門が閉じているときに出す一行。
 * @property draw 札そのもの。摩擦の一覧は使う札だけが受け取る。
 */
private class FigureSlot(
    val gate: ((Context) -> Boolean)? = null,
    val missing: String = "",
    val draw: @Composable (Modifier, Int, Set<String>) -> Unit,
)

private const val NEEDS_NOTIFICATION = "通知へのアクセスが必要"
private const val NEEDS_USAGE = "使用状況へのアクセスが必要"
private const val NEEDS_CALENDAR = "カレンダーの許可が必要"
private const val NEEDS_LOCATION = "位置情報の許可が必要"

private val FIGURE_SLOTS: Map<String, FigureSlot> = mapOf(
    LayoutConfig.INFO_MUSIC to FigureSlot(
        InfoSources::hasNotificationAccess,
        NEEDS_NOTIFICATION,
    ) { m, k, _ -> MusicTile(m, k) },
    LayoutConfig.INFO_LISTENING to FigureSlot(
        InfoSources::hasNotificationAccess,
        NEEDS_NOTIFICATION,
    ) { m, k, _ -> ListeningTile(m, k) },

    LayoutConfig.INFO_NEXT_ACTION to FigureSlot(
        Glance::hasCalendarPermission,
        NEEDS_CALENDAR,
    ) { m, k, _ -> NextActionTile(m, k) },
    LayoutConfig.INFO_CALENDAR_TIMELINE to FigureSlot(
        Glance::hasCalendarPermission,
        NEEDS_CALENDAR,
    ) { m, k, _ -> TimelineTile(m, k) },
    LayoutConfig.INFO_CALENDAR_MONTH to FigureSlot(
        Glance::hasCalendarPermission,
        NEEDS_CALENDAR,
    ) { m, k, _ -> MonthTile(modifier = m, refreshKey = k) },

    LayoutConfig.INFO_WEATHER_SERIES to FigureSlot(
        Glance::hasLocationPermission,
        NEEDS_LOCATION,
    ) { m, k, _ -> WeatherSeriesTile(m, k) },

    LayoutConfig.INFO_ATTENTION to FigureSlot(
        InfoSources::hasUsageAccess,
        NEEDS_USAGE,
    ) { m, k, friction -> AttentionTile(friction = friction, modifier = m, refreshKey = k) },
    LayoutConfig.INFO_USAGE_BARS to FigureSlot(
        InfoSources::hasUsageAccess,
        NEEDS_USAGE,
    ) { m, k, _ -> UsageBarsTile(m, k) },
    LayoutConfig.INFO_SPREAD to FigureSlot(
        InfoSources::hasUsageAccess,
        NEEDS_USAGE,
    ) { m, k, _ -> SpreadTile(m, k) },
    LayoutConfig.INFO_TOUCH to FigureSlot(
        InfoSources::hasUsageAccess,
        NEEDS_USAGE,
    ) { m, k, _ -> TouchTile(m, k) },
    LayoutConfig.INFO_SPACE to FigureSlot(
        InfoSources::hasUsageAccess,
        NEEDS_USAGE,
    ) { m, k, _ -> SpaceTile(m, k) },
    LayoutConfig.INFO_TRAFFIC to FigureSlot(
        InfoSources::hasUsageAccess,
        NEEDS_USAGE,
    ) { m, k, _ -> TrafficTile(m, k) },

    // 門の要らないもの。**要らないなら書かない**——
    // 念のための門は、いつか本当の門と見分けがつかなくなる。
    LayoutConfig.INFO_TRAFFIC_WEEK to FigureSlot { m, k, _ -> TrafficWeekTile(m, k) },
    LayoutConfig.INFO_STEPS_WEEK to FigureSlot { m, k, _ -> StepsWeekTile(m, k) },
    LayoutConfig.INFO_PACE to FigureSlot { m, k, _ -> PaceTile(m, k) },
    LayoutConfig.INFO_OPENS to FigureSlot { m, k, _ -> OpensTile(m, k) },
    LayoutConfig.INFO_DAY_SHAPE to FigureSlot { m, k, _ -> DayShapeTile(m, k) },
    LayoutConfig.INFO_USAGE_HEAT to FigureSlot { m, k, _ -> UsageHeatTile(m, k) },
    LayoutConfig.INFO_CHARGE to FigureSlot { m, k, _ -> ChargeTile(m, k) },
)

/**
 * 門が閉じているときの一行。
 *
 * **押せば済むことが分かる文言にする。** 何が足りないかだけを書き、
 * 責めない（意匠帳 §8）。
 */
@Composable
private fun MissingAccess(text: String) {
    ObservatoryText(
        text = text,
        color = ObservatoryColors.Paper,
        size = ObservatoryType.Body,
        modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
    )
}
