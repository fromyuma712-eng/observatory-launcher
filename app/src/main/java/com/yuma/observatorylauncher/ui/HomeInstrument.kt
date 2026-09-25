package com.yuma.observatorylauncher.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.data.AppRepository
import com.yuma.observatorylauncher.data.Appointment
import com.yuma.observatorylauncher.data.Behaviour
import com.yuma.observatorylauncher.data.Glance
import com.yuma.observatorylauncher.data.Health
import com.yuma.observatorylauncher.data.Weather
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * ホームの計器。**時計・空・次の行動（上段）と、一日の帯・計器の列（下段）。**
 *
 * ## なぜ作り直したか（2026-09-25）
 *
 * > 「今メインの画面がパッとしない PPTX スライドみたいになっているので、
 * >  おしゃれかつ情報を得ることができるようにしたい。ここが最も多くの人の目に触れる」（本人）
 *
 * 以前のホームは、左寄せの文字行と罫だけで組まれていた。時計・予定・使用・天気が
 * **同じ重さで縦に並び、どこから見るかが決まっていなかった**。図は一枚も無かった。
 *
 * 作り直しの方針は三つ。
 *
 * 1. **一目で答える数字を二つだけ大きくする**——今の時刻と、今の気温。
 * 2. **一日を図にする**——文字で「本日 17:30 …」と並べる代わりに、0〜24 時の帯の上に
 *    昼の長さ・予定・今の位置を置く。何時に何があり、今日があとどれだけ残っているかが形で読める。
 * 3. **計器は札（欧文）と値（和文）の組で横に並べる**——縦に積むと「箇条書きのスライド」になる。
 *
 * 上段（今）と下段（一日と道具）の間の空きは、あえて残す。親指の届く下段に
 * 見る頻度の高いもの（帯・計器・像）を集め、上段は離れて見ても読める大きさにする。
 *
 * 札の「次の行動」「今日の歩調」「天気」「天気詳細」は、この計器が同じ答えを出すので、
 * 計器を出している間は面に置かない（[COVERS]。編集中は従来どおり見える）。
 */
internal object HomeInstrument {
    /** 計器が受け持つ札の種類。計器を出している間、面に重ねて置かない。 */
    val COVERS = setOf(
        LayoutConfig.INFO_NEXT_ACTION,
        LayoutConfig.INFO_PACE,
        LayoutConfig.INFO_USAGE,
        LayoutConfig.INFO_WEATHER,
        LayoutConfig.INFO_WEATHER_DETAIL,
        LayoutConfig.INFO_WEATHER_FULL,
        LayoutConfig.INFO_DATE,
        LayoutConfig.INFO_DATE_BRIEF,
        LayoutConfig.INFO_BATTERY,
    )
}

/** 今の時刻。秒の境目で進む（[Header] と同じ、境目を跨いだと確信するための 40ms の余白）。 */
@Composable
private fun rememberNow(): Long {
    val owner = LocalLifecycleOwner.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000L - (System.currentTimeMillis() % 1_000L) + 40L)
            }
        }
    }
    return now
}

// ---- 上段 ------------------------------------------------------------------

/**
 * 上段。左に時計、右に空。下に次の行動。
 *
 * 押せば時計アプリ、長押しで面の設定（従来の [Header] と同じ）。
 */
@Composable
internal fun HomeHero(
    refreshKey: Int,
    onOpenClock: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 横向きは三列の左に入るので、時計を一回り小さくする（76sp では「20:4..」と切れた）。
    val clockSize = if (isLandscape()) 54.sp else 76.sp
    val skySize = if (isLandscape()) 34.sp else 44.sp
    val context = LocalContext.current
    val now = rememberNow()
    val weather by produceState<Weather?>(null, refreshKey) {
        value = withContext(Dispatchers.Default) { runCatching { Glance.weather(context) }.getOrNull() }
    }
    val interaction = remember { MutableInteractionSource() }
    val clock = remember(now / 60_000) { SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(now)) }
    val seconds = remember(now / 1_000) { SimpleDateFormat("ss", Locale.JAPAN).format(Date(now)) }
    val date = remember(now / 60_000) { SimpleDateFormat("yyyy.MM.dd  EEE", Locale.JAPAN).format(Date(now)) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ObservatoryDimens.Gutter)
            .padding(top = 20.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        interactionSource = interaction,
                        indication = PressIndication.Row,
                        onClick = onOpenClock,
                        onLongClick = onLongPress,
                    ),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    // 時計は Light。76sp まで上げると Regular では塊になる。
                    RollingText(
                        text = clock,
                        color = ObservatoryColors.Paper,
                        size = clockSize,
                        weight = FontWeight.Light,
                        tracking = -0.02f,
                    )
                    RollingText(
                        text = seconds,
                        color = ObservatoryColors.Muted,
                        size = ObservatoryType.Value,
                        modifier = Modifier.padding(start = 6.dp, bottom = 14.dp),
                    )
                }
                SecondSweep(modifier = Modifier.padding(top = 2.dp).width(196.dp))
                ObservatoryText(
                    text = date,
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Note,
                    mono = true,
                    tracking = 0.10f,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            weather?.let { w -> SkyReadout(w, skySize) }
        }
        NextActionLine(refreshKey = refreshKey, now = now, modifier = Modifier.padding(top = 22.dp))
    }
}

/**
 * 秒の進み。**一分をかけて左から右へ、途切れずに伸びる一本の線。**
 *
 * > 「時計の秒数はもっとスムーズに変わって欲しい」（本人・2026-09-25）
 *
 * 数字は一秒に一度しか替われない。滑らかさは数字ではなく**線の側**で出す——
 * 0.1 秒ごとに今の時刻を読んで伸ばす。面が見えていないときは止める。
 */
@Composable
private fun SecondSweep(modifier: Modifier = Modifier) {
    val visible = LocalPageVisible.current
    var frame by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // ⚠️ 毎コマ（120Hz）描き直していたら、置いておくだけで毎秒 130 コマ・1 コマ 11ms を使っていた
    // （2026-09-25 実測）。線は一分で約 500 画素しか進まないので、**0.1 秒ごとで一画素ずつ**
    // 進む——見た目は変わらず、描き直しは十二分の一になる。
    LaunchedEffect(visible) {
        while (visible) {
            frame = System.currentTimeMillis()
            delay(SWEEP_STEP_MS)
        }
    }
    Box(
        modifier = modifier
            .height(2.dp)
            .drawBehind {
                val t = (frame % 60_000L) / 60_000f
                drawRect(ObservatoryColors.Line, size = size)
                drawRect(ObservatoryColors.Ember, size = Size(size.width * t, size.height))
            },
    )
}

private const val SWEEP_STEP_MS = 100L

/** 右肩の空。大きな気温と、空模様・最高最低・降水。 */
@Composable
private fun SkyReadout(w: Weather, size: androidx.compose.ui.unit.TextUnit) {
    Column(horizontalAlignment = Alignment.End) {
        ObservatoryText(
            text = "${w.temperature.roundToInt()}°",
            color = ObservatoryColors.Paper,
            size = size,
            weight = FontWeight.Light,
            mono = true,
            tracking = -0.02f,
        )
        ObservatoryText(
            text = w.sky.ifBlank { w.skyToday },
            color = ObservatoryColors.Paper,
            size = ObservatoryType.Body,
        )
        ObservatoryText(
            text = "↑${w.high.roundToInt()}°  ↓${w.low.roundToInt()}°  降水${w.precipitation}%",
            color = ObservatoryColors.Muted,
            size = ObservatoryType.Note,
            mono = true,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

/**
 * 次の行動を一行で。出し分けは [NextActionTile] と同じ三つ（今・次・明日以降）。
 *
 * 札は欧文（NOW / NEXT）、中身は和文。今まさに最中のものだけ燠火にする。
 */
@Composable
private fun NextActionLine(refreshKey: Int, now: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val minute = now / 60_000
    val today by produceState<Pair<List<Appointment>, List<Appointment>>?>(null, refreshKey, minute / 5) {
        value = runCatching { Glance.today(context) }.getOrNull()
    }
    val upcoming by produceState(initialValue = emptyList<Appointment>(), refreshKey) {
        value = runCatching { Glance.appointments(context, limit = 6) }.getOrDefault(emptyList())
    }
    val timed = today?.first.orEmpty()
    val running = timed.firstOrNull { it.begin <= now && now < it.end }
        ?: today?.second.orEmpty().firstOrNull { it.begin <= now && now < it.end }
    val next = timed.firstOrNull { it.begin > now }
    val beyond = upcoming.firstOrNull { it.begin > now }

    val shown = running ?: next ?: beyond
    val (tag, title, trailing, hot) = when {
        running != null -> Quad(
            "Now",
            running.title,
            "〜" + (if (running.allDay) "終日" else clockOf(running.end)) + "  残り " + gap(running.end - now),
            true,
        )
        next != null -> Quad("Next", clockOf(next.begin) + "  " + next.title, "あと " + gap(next.begin - now), false)
        beyond != null -> Quad("Next", beyond.dayLabel(now) + " " + beyond.timeLabel() + "  " + beyond.title, "", false)
        else -> Quad("Next", "予定なし", "", false)
    }
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interaction,
                indication = PressIndication.Row,
                onClick = { AppRepository.openCalendar(context) },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ObservatoryTag(text = tag, color = if (hot) ObservatoryColors.EmberText else ObservatoryColors.Muted)
            Spacer(modifier = Modifier.width(10.dp))
            ObservatoryText(
                text = title,
                color = ObservatoryColors.Paper,
                size = ObservatoryType.Value,
                modifier = Modifier.weight(1f),
            )
        }
        if (trailing.isNotBlank()) {
            ObservatoryText(
                text = trailing,
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Note,
                mono = true,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // この先の予定。上の一行で出したものを除いて、三件まで。
        // **一行＝一件**。時刻の列は幅を揃え、題の頭が縦に揃うようにする。
        val later = upcoming.filter { it != shown && it.end > now }.take(3)
        if (later.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = 16.dp)) {
                later.forEach { e ->
                    Row(
                        modifier = Modifier.padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ObservatoryText(
                            text = e.dayLabel(now) + " " + e.timeLabel(),
                            color = ObservatoryColors.Muted,
                            size = ObservatoryType.Note,
                            mono = true,
                            modifier = Modifier.width(112.dp),
                        )
                        ObservatoryText(
                            text = e.title,
                            color = ObservatoryColors.Paper,
                            size = ObservatoryType.Body,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

private data class Quad(val tag: String, val title: String, val trailing: String, val hot: Boolean)

// ---- 下段 ------------------------------------------------------------------

/**
 * 下段。一日の帯と、計器の列。**アプリの台帳の直上**（親指の届く帯）に置く。
 */
@Composable
internal fun HomeHorizon(refreshKey: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ObservatoryDimens.Gutter)
            .padding(bottom = 22.dp),
    ) {
        DayBand(refreshKey = refreshKey)
        Readouts(refreshKey = refreshKey, modifier = Modifier.padding(top = 20.dp))
    }
}

/**
 * 一日の帯。0〜24 時の軸の上に、**昼の長さ・予定・今の位置**を置く。
 *
 * - 日の出から日の入りまでは帯を明るく敷く（昼の長さが形で見える）。
 * - 予定は軸の上に細い棒で。最中のものだけ燠火、済んだものは沈める。
 * - 今の位置は燠火の縦線。**過ぎた時間は地の色を重ねて沈める**——今日があとどれだけ残るかが見える。
 * - 目盛りは 0・6・12・18・24 時。刻みは三時間ごと。
 */
@Composable
private fun DayBand(refreshKey: Int) {
    val context = LocalContext.current
    val now = rememberNow()
    val minute = now / 60_000
    val weather by produceState<Weather?>(null, refreshKey) {
        value = withContext(Dispatchers.Default) { runCatching { Glance.weather(context) }.getOrNull() }
    }
    val today by produceState<Pair<List<Appointment>, List<Appointment>>?>(null, refreshKey, minute / 5) {
        value = runCatching { Glance.today(context) }.getOrNull()
    }
    val dayStart = remember(minute / (60 * 24)) {
        Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
    val dayMs = 24 * 60 * 60_000f
    fun at(t: Long) = ((t - dayStart) / dayMs).coerceIn(0f, 1f)
    fun hhmm(s: String?): Float? = s?.split(":")?.takeIf { it.size >= 2 }?.let {
        val h = it[0].trim().takeLast(2).toFloatOrNull() ?: return@let null
        val m = it[1].trim().take(2).toFloatOrNull() ?: return@let null
        (h * 60 + m) / (24 * 60)
    }
    val sunrise = hhmm(weather?.sunrise)
    val sunset = hhmm(weather?.sunset)
    // 十二時間を超えるものは「予定」ではなく「期間」（履修登録期間など）。帯に載せると
    // 一日を覆い尽くすので載せない——期間は上段の NOW の行が受け持つ。
    val events = today?.first.orEmpty().filter { !it.allDay && it.end - it.begin <= 12 * 60 * 60_000L }
    val reveal = rememberReveal()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ObservatoryTag(text = "Day", color = ObservatoryColors.Muted)
            Spacer(modifier = Modifier.weight(1f))
            if (sunrise != null && sunset != null) {
                ObservatoryText(
                    text = "昼 ${weather?.sunrise?.takeLast(5)}–${weather?.sunset?.takeLast(5)}",
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(34.dp)
                .drawBehind {
                    val w = size.width
                    val axisY = size.height - 1.dp.toPx()
                    val bandTop = 14.dp.toPx()
                    // 昼の帯。
                    if (sunrise != null && sunset != null) {
                        drawRect(
                            ObservatoryColors.Paper.copy(alpha = 0.10f),
                            Offset(w * sunrise, bandTop),
                            Size(w * (sunset - sunrise) * reveal.value, axisY - bandTop),
                        )
                    }
                    // 予定の棒。
                    events.forEach { e ->
                        val x0 = w * at(e.begin)
                        val x1 = (w * at(e.end)).coerceAtLeast(x0 + 3.dp.toPx())
                        val ink = when {
                            e.begin <= now && now < e.end -> ObservatoryColors.Ember
                            e.end <= now -> ObservatoryColors.Grid
                            else -> ObservatoryColors.Paper
                        }
                        drawRect(ink, Offset(x0, 3.dp.toPx()), Size((x1 - x0) * reveal.value, 5.dp.toPx()))
                    }
                    // 軸と刻み（三時間ごと、六時間ごとに長く）。
                    drawRect(ObservatoryColors.LineStrong, Offset(0f, axisY), Size(w, 1.dp.toPx()))
                    for (h in 0..24 step 3) {
                        val x = (w * h / 24f).coerceAtMost(w - 1.dp.toPx())
                        val len = if (h % 6 == 0) 7.dp.toPx() else 4.dp.toPx()
                        drawRect(ObservatoryColors.LineStrong, Offset(x, axisY - len), Size(1.dp.toPx(), len))
                    }
                    // 過ぎた時間を沈める。
                    val nx = w * at(now)
                    drawRect(ObservatoryColors.Ground.copy(alpha = 0.45f), Offset(0f, 0f), Size(nx, axisY))
                    // 今。
                    drawRect(ObservatoryColors.Ember, Offset(nx - 0.75.dp.toPx(), 0f), Size(1.5.dp.toPx(), axisY))
                    val s = 6.dp.toPx()
                    drawRect(ObservatoryColors.Ember, Offset(nx - s / 2, axisY - s / 2), Size(s, s))
                },
        )
        AxisLabels(
            marks = listOf(0, 6, 12, 18, 24).map { h -> (h / 24f) to "%02d".format(h) },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * 計器の列。**札（欧文）と値（和文）の組**を横に並べる。入り切らなければ次の段へ送る。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Readouts(refreshKey: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val pace by produceState<Behaviour.Pace?>(null, refreshKey) {
        value = withContext(Dispatchers.Default) { runCatching { Behaviour.pace(context) }.getOrNull() }
    }
    val steps by produceState<Long?>(null, refreshKey) {
        value = runCatching { Health.today(context)?.steps }.getOrNull()
    }
    val battery = remember(refreshKey) { batteryOf(context) }

    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        pace?.let { p ->
            val diff = p.differenceMs / 60_000
            Readout(
                tag = "Screen time",
                value = readableMinutes(p.todayMs),
                note = if (p.comparable) (if (diff >= 0) "+" else "−") + "${kotlin.math.abs(diff)}分 平均比" else null,
                hot = p.comparable && diff > 0,
            )
        }
        steps?.let { Readout(tag = "Steps", value = "%,d歩".format(it)) }
        battery?.let { (level, charging) ->
            Readout(tag = "Battery", value = "$level%", note = if (charging) "充電中" else null)
        }
    }
}

@Composable
private fun Readout(tag: String, value: String, note: String? = null, hot: Boolean = false) {
    Column(modifier = Modifier.widthIn(min = 88.dp).padding(end = 12.dp)) {
        ObservatoryTag(text = tag, color = ObservatoryColors.Muted)
        ObservatoryText(
            text = value,
            color = ObservatoryColors.Paper,
            size = ObservatoryType.Value,
            mono = true,
            modifier = Modifier.padding(top = 4.dp),
        )
        note?.let {
            ObservatoryText(
                text = it,
                color = if (hot) ObservatoryColors.EmberText else ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 電池の残量と充電中か。取れなければ null。 */
private fun batteryOf(context: Context): Pair<Int, Boolean>? = runCatching {
    val intent: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?: return@runCatching null
    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    if (level < 0 || scale <= 0) return@runCatching null
    val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    ((level * 100) / scale) to charging
}.getOrNull()
