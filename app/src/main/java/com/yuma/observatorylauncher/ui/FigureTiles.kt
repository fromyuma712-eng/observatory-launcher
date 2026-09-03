package com.yuma.observatorylauncher.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuma.observatorylauncher.data.AppRepository
import com.yuma.observatorylauncher.data.Appointment
import com.yuma.observatorylauncher.data.Behaviour
import com.yuma.observatorylauncher.data.Charge
import com.yuma.observatorylauncher.data.Glance
import com.yuma.observatorylauncher.data.Usage
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.yuma.observatorylauncher.data.Health
import com.yuma.observatorylauncher.data.Listening
import com.yuma.observatorylauncher.data.Restraint
import com.yuma.observatorylauncher.data.Space
import com.yuma.observatorylauncher.data.Traffic
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import java.time.LocalDate
import java.time.ZoneId

/**
 * 今日の時間軸。
 *
 * 予定の一覧は「次に何があるか」しか答えない。時間軸は
 * **空いているのはどこか**に答える——これは既存の暦ウィジェットが持たない形である。
 */
@Composable
internal fun TimelineTile(modifier: Modifier = Modifier, refreshKey: Int = 0) {
    val context = LocalContext.current
    val loaded by produceState<Pair<List<Appointment>, List<Appointment>>?>(null, refreshKey) {
        value = Glance.today(context)
    }
    // 今日より先の一件。**窓の外を数だけで済ませると、明日以降が見えなくなる。**
    // 直近の予定を別に並べていた頃はそちらが受け持っていたが、
    // 同じ予定を二箇所に書くのをやめたので、ここが引き受ける。
    val ahead by produceState<Appointment?>(null, refreshKey) {
        value = Glance.appointments(context, limit = 4).firstOrNull()
    }
    val now = System.currentTimeMillis()
    val zone = remember { ZoneId.systemDefault() }
    val dayStart = remember(refreshKey) {
        LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    }

    val timed = loaded?.first.orEmpty()
    val allDay = loaded?.second.orEmpty()

    // **窓は六時間。一日を丸ごと詰めない。**
    //
    // ⚠️ 以前は 7 時〜24 時（十七時間）を幅いっぱいに詰めていた。
    // すると三十分の予定は幅の 1/34 にしかならず、点にしか見えない。
    // 目盛りも三時間ごとに間引かざるを得ず、軸として読めなくなっていた
    // ——**意匠が実用を潰していた**（本人の指摘・2026/9/1）。
    //
    // 六時間なら三十分は 1/12 で、刻みも毎時置ける。
    // 窓は今より一時間手前から始める。直前に何があったかは、
    // これから何があるかと同じくらい要る。
    val nowHour = remember(refreshKey) {
        ((now - dayStart) / 3_600_000L).toInt().coerceIn(0, 23)
    }
    val startHour = remember(nowHour) { (nowHour - 1).coerceIn(0, 24 - WINDOW_HOURS) }
    val endHour = remember(startHour) { startHour + WINDOW_HOURS }

    // 窓の外にある予定は、数だけ添える。**黙って落とすと「予定なし」に見える。**
    val inWindow = remember(timed, startHour, endHour) {
        val from = dayStart + startHour * 3_600_000L
        val to = dayStart + endHour * 3_600_000L
        timed.filter { it.end > from && it.begin < to }
    }
    val outside = timed.size - inWindow.size
    val nextOutside = remember(timed, endHour) {
        val to = dayStart + endHour * 3_600_000L
        timed.filter { it.begin >= to }.minByOrNull { it.begin }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        if (allDay.isNotEmpty()) {
            // 終日は軸に置けない。**一日を端から端まで塗ってしまう**ので、
            // 名前だけを軸の上に添える。
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ObservatoryText(
                    text = "終日",
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                )
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                ObservatoryText(
                    text = allDay.joinToString("・") { it.title },
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Note,
                )
            }
        }
        TimelineFigure(
            spans = inWindow.map {
                Figures.Span(
                    label = it.title,
                    from = it.begin,
                    to = it.end,
                    done = it.end <= now,
                    running = it.begin <= now && now < it.end,
                )
            },
            dayStart = dayStart,
            startHour = startHour,
            endHour = endHour,
            now = now,
        )
        // 窓の外を一行で。**今日の残りが先、無ければ明日以降の一件。**
        // 全部並べるなら窓を切った意味が無いので、次の一件だけ時刻を添える。
        val footer = when {
            outside > 0 && nextOutside != null ->
                "今日あと $outside 件　次 " + clockOf(nextOutside.begin) + " " + nextOutside.title
            outside > 0 -> "今日あと $outside 件"
            ahead != null -> "次 " + ahead!!.dayLabel(now) + " " +
                ahead!!.timeLabel() + " " + ahead!!.title
            else -> null
        }
        footer?.let {
            // 窓の外に何件あるかは**自明ではない**ので、読める濃さで置く。
            ObservatoryText(
                text = it,
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Body,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

/** 軸の幅。六時間。三十分の予定が幅の 1/12 になり、刻みを毎時置ける。 */
private const val WINDOW_HOURS = 6

/**
 * 使用時間の内訳。
 *
 * 「今日 2時間52分・最長 YouTube」は事実だが、**何がどれだけ占めたか**は答えない。
 * 上位だけを出す——全件並べても「多い」以上のことは伝わらず、面が埋まるだけである。
 */
@Composable
internal fun UsageBarsTile(modifier: Modifier = Modifier, refreshKey: Int = 0, top: Int = 5) {
    val context = LocalContext.current
    val today by produceState<Usage.Today?>(null, refreshKey) {
        value = Usage.today(context)
    }
    val data = today
    if (data == null || data.ranked.isEmpty()) {
        ObservatoryText(
            text = if (data == null) "" else "記録なし",
            color = ObservatoryColors.Faint,
            size = ObservatoryType.Note,
            modifier = modifier,
        )
        return
    }

    // 名前は一覧の控えから引く。端末へ問い合わせ直すと、
    // 描くたびにラベルの読み出しが走る。
    //
    // ⚠️ **控えが空のときに備えること。** 立ち上げ直後は一覧がまだ作られておらず、
    // パッケージ名の末尾（android・calculator・chatgpt…）が並んだ（実機で出た）。
    // 控えに無いものだけ端末へ訊く。数件なので走査の費用は無視できる。
    val names = remember(data.ranked, refreshKey) {
        val known = AppRepository.cached().associate { it.packageName to it.label }
        val packages = context.packageManager
        data.ranked.take(top).map { (pkg, ms) ->
            val label = known[pkg] ?: runCatching {
                packages.getApplicationLabel(packages.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg.substringAfterLast('.')
            Figures.Item(label = label, value = ms)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ObservatoryText(
                text = "合計",
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
            )
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            ObservatoryText(
                text = readableMinutes(data.total),
                color = ObservatoryColors.Paper,
                size = ObservatoryType.Body,
                mono = true,
            )
        }
        BarsFigure(
            items = names,
            format = { readableMinutes(it) },
            axis = true,
            axisLabel = { "%d分".format((it / 60_000).toInt()) },
        )
    }
}

/**
 * 通信量。
 *
 * 「今月あとどれくらい使えるか」は月に何度も気にするのに、
 * そのたびに設定の奥まで潜る必要がある値である。
 *
 * 今日と今月を数字で、内訳を横棒で出す——**使用時間の内訳と同じ形**にした。
 * 単位が違うだけで問いは同じ（何がどれだけ占めたか）なので、
 * 形を変える理由が無い。
 */
@Composable
internal fun TrafficTile(modifier: Modifier = Modifier, refreshKey: Int = 0, top: Int = 5) {
    val context = LocalContext.current
    val pair by produceState<Pair<Traffic.Usage?, Traffic.Usage?>?>(null, refreshKey) {
        value = withContext(Dispatchers.Default) {
            Traffic.today(context) to Traffic.thisMonth(context)
        }
    }
    val today = pair?.first
    val month = pair?.second
    if (today == null && month == null) {
        ObservatoryText(
            text = if (pair == null) "" else "取得できない",
            color = ObservatoryColors.Faint,
            size = ObservatoryType.Note,
            modifier = modifier,
        )
        return
    }

    // 名前は取得側で解決済み。ここで引き直さない。
    //
    // **帰属が過半に満たないなら棒を出さない。**
    // 端末は uid 別のモバイル通信を部分的にしか返さず（加入者の識別子を渡さない限り）、
    // 残りは「計上外」に落ちる。実機では 72.6MB のうち 56.2MB が計上外だった。
    // 首位が「計上外」の図は、読む側に何も与えないうえ、
    // 内訳を名乗っておいて内訳になっていない。**数字だけを出す方が正直である。**
    val names = remember(today) {
        val all = today?.ranked.orEmpty()
        val unknown = all.firstOrNull { it.first == "計上外" }?.second ?: 0L
        val total = today?.mobile ?: 0L
        if (total <= 0L || unknown * 2 > total) {
            emptyList()
        } else {
            all.take(top).map { (name, bytes) -> Figures.Item(label = name, value = bytes) }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 今日と今月を一行に。**モバイルとWi-Fiを分けて書く**——
        // 足した数字だけでは、上限に関わるのがどちらか分からない。
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            TrafficColumn("今日", today)
            Spacer(modifier = Modifier.weight(1f))
            TrafficColumn("今月", month, alignEnd = true)
        }
        if (names.isNotEmpty()) {
            // **何の内訳かを名乗る。** モバイルだけを数えていることを書かないと、
            // 上の合計(Wi-Fi込み)と桁が合わず、読む側が混乱する。
            ObservatoryText(
                text = "モバイルの内訳",
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            BarsFigure(
                items = names,
                format = { readableBytes(it) },
                axis = true,
                axisLabel = { readableBytes(it.toLong()) },
            )
        }
    }
}

@Composable
private fun TrafficColumn(
    heading: String,
    usage: Traffic.Usage?,
    alignEnd: Boolean = false,
) {
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        ObservatoryText(text = heading, color = ObservatoryColors.Faint, size = ObservatoryType.Label, mono = true)
        ObservatoryText(
            text = usage?.let { readableBytes(it.total) } ?: "—",
            color = ObservatoryColors.Paper,
            size = ObservatoryType.Body,
            mono = true,
        )
        ObservatoryText(
            text = usage?.let {
                "モバイル " + readableBytes(it.mobile) + "  Wi-Fi " + readableBytes(it.wifi)
            } ?: "",
            color = ObservatoryColors.Muted,
            size = ObservatoryType.Label,
            mono = true,
        )
    }
}

/**
 * 充電の実測。
 *
 * 「82% 充電中」までは既存の札が出している。ここが引き受けるのは
 * **今どれくらいの速さで入っているか**——電流・電圧・電力である。
 * 端末は持っているのに設定画面にも出さない値で、この体系が引き受けるべきものの典型。
 *
 * 下に残量の推移を敷く。一枚の値は「今どうか」しか答えないが、
 * 折れ線は**一日でどう減ったか**に答える。記録が溜まるまでは線を出さない
 * ——点が二つ未満の折れ線は、線ではなく飾りである。
 */
@Composable
internal fun ChargeTile(modifier: Modifier = Modifier, refreshKey: Int = 0) {
    val context = LocalContext.current
    val state by produceState<Pair<Charge.Reading, List<Charge.Sample>>?>(null, refreshKey) {
        value = withContext(Dispatchers.Default) {
            val reading = Charge.now(context) ?: return@withContext null
            reading to Charge.record(context, reading)
        }
    }
    val now = state?.first
    if (now == null) {
        ObservatoryText(
            text = "",
            color = ObservatoryColors.Faint,
            size = ObservatoryType.Note,
            modifier = modifier,
        )
        return
    }
    val log = state?.second.orEmpty()

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            ObservatoryText(
                text = "${now.percent}%",
                color = ObservatoryColors.Paper,
                size = ObservatoryType.Hero,
                mono = true,
            )
            Spacer(modifier = Modifier.padding(horizontal = 5.dp))
            ObservatoryText(
                text = when {
                    now.full -> "満充電"
                    now.charging -> "充電中"
                    else -> "放電中"
                },
                color = if (now.charging) ObservatoryColors.EmberText else ObservatoryColors.Muted,
                size = ObservatoryType.Note,
            )
            Spacer(modifier = Modifier.weight(1f))
            // 電流と電力を右へ。**この札の主題はここ**なので、大きさを揃えて並べる。
            ObservatoryText(
                text = Charge.ampLabel(now.milliAmp),
                color = ObservatoryColors.Paper,
                size = ObservatoryType.Body,
                mono = true,
            )
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            ObservatoryText(
                text = Charge.wattLabel(now.watt),
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Note,
                mono = true,
            )
        }
        // 添えの値。**単位を必ず書く**——裸の数字は読む側に前提を要求する。
        ObservatoryText(
            text = buildList {
                now.volt?.let { add("%.2fV".format(it)) }
                now.celsius?.let { add("%.1f℃".format(it)) }
                now.chargeMah?.let { add("${it}mAh") }
                // 「約」を付ける。直線の見積りなので、終盤は実際より短く出る。
                now.toFullMs?.let {
                    add((if (now.charging) "満まで約" else "空まで約") + readableMinutes(it))
                }
            }.joinToString("  "),
            color = ObservatoryColors.Faint,
            size = ObservatoryType.Label,
            mono = true,
            modifier = Modifier.padding(top = 2.dp),
        )

        if (log.size >= 2) {
            Spacer(modifier = Modifier.padding(top = 8.dp))
            TrendFigure(
                values = log.map { it.percent.toFloat() },
                axisUnit = "%",
                labels = listOf(
                    clockOf(log.first().at),
                    "残量 ${log.minOf { it.percent }}–${log.maxOf { it.percent }}%",
                    clockOf(log.last().at),
                ),
            )
        }
    }
}

private fun clockOf(at: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.JAPAN).format(java.util.Date(at))

/**
 * 保存領域。
 *
 * 「空きが少ない」は設定を開けば分かる。**何が食っているか**は
 * アプリを一つずつ開かないと分からない——並べれば一目で済む。
 */
@Composable
internal fun SpaceTile(modifier: Modifier = Modifier, refreshKey: Int = 0) {
    val context = LocalContext.current
    val report by produceState<Space.Report?>(null, refreshKey) {
        // ⚠️ 一件ずつ端末へ訊くので、必ず背景で。
        value = withContext(Dispatchers.Default) { Space.read(context) }
    }
    val data = report
    if (data == null) {
        ObservatoryText(text = "", color = ObservatoryColors.Faint, size = ObservatoryType.Note, modifier = modifier)
        return
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column {
                ObservatoryText(
                    text = "使用",
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                )
                ObservatoryText(
                    text = readableBytes(data.used),
                    color = ObservatoryColors.Paper,
                    size = ObservatoryType.Body,
                    mono = true,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                ObservatoryText(
                    text = "空き",
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                )
                ObservatoryText(
                    text = readableBytes(data.free) + " / " + readableBytes(data.total),
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Note,
                    mono = true,
                )
            }
        }
        // 全体に対する使用の位置を、音楽と同じ目盛りで示す。
        // **同じ問いには同じ形**——どこまで来ているか、は針で表す。
        data.ratio?.let {
            Spacer(modifier = Modifier.padding(top = 6.dp))
            ProportionScale(fraction = it)
        }
        if (data.ranked.isNotEmpty()) {
            ObservatoryText(
                text = "大きいもの",
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            BarsFigure(
                items = data.ranked.map { (name, bytes) -> Figures.Item(name, bytes) },
                format = { readableBytes(it) },
                axis = true,
                axisLabel = { readableBytes(it.toLong()) },
            )
        }
    }
}

/**
 * 今日の触り方。
 *
 * 使用時間が「どれだけ使ったか」なら、解錠回数は**どれだけ手が伸びたか**である。
 * 長く使っていなくても五十回触っていれば、それは別の事実で、
 * 端末は持っているのにどのウィジェットも出さない。
 */
@Composable
internal fun TouchTile(modifier: Modifier = Modifier, refreshKey: Int = 0) {
    val context = LocalContext.current
    val touch by produceState<Usage.Touch?>(null, refreshKey) {
        value = withContext(Dispatchers.Default) { Usage.touchesToday(context) }
    }
    val data = touch
    if (data == null) {
        ObservatoryText(text = "", color = ObservatoryColors.Faint, size = ObservatoryType.Note, modifier = modifier)
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column {
            ObservatoryText(
                text = "解錠",
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
            )
            ObservatoryText(
                text = "${data.unlocks}回",
                color = ObservatoryColors.Paper,
                size = ObservatoryType.Hero,
                mono = true,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        // 一日の輪郭。**最初と最後だけ**でよい——間の全部を書いても輪郭は太くならない。
        ObservatoryText(
            text = listOfNotNull(
                data.firstAt?.let { "最初 " + clockOf(it) },
                data.lastAt?.let { "最後 " + clockOf(it) },
            ).joinToString("  "),
            color = ObservatoryColors.Muted,
            size = ObservatoryType.Note,
            mono = true,
        )
    }
}

/**
 * 天気の時系列。
 *
 * 「26° 降水82%」は今の一点でしかない。**これから降るのか、もう止むのか**は、
 * 並べてみないと分からない。降水確率を棒に、気温を線にして同じ時間軸へ重ねる。
 *
 * 意匠は統計検定の練習サイトのものを借りた（左に値軸・薄い目盛り線・L字の軸）。
 */
@Composable
internal fun WeatherSeriesTile(modifier: Modifier = Modifier, refreshKey: Int = 0) {
    val context = LocalContext.current
    val weather by produceState<com.yuma.observatorylauncher.data.Weather?>(null, refreshKey) {
        value = withContext(Dispatchers.Default) { Glance.weather(context) }
    }
    val w = weather ?: return
    if (w.hours.size < 4) return
    Column(modifier = modifier.fillMaxWidth()) {
        SeriesFigure(
            labels = w.hours,
            bars = w.hourlyRain.map { it.toFloat() },
            barUnit = "降水確率 %",
            line = w.hourlyTemp.map { it.toFloat() },
            lineUnit = "気温 ℃",
        )
        // 図から読み取れることを一行だけ言葉にする。
        // **図は形を見せ、言葉は結論を置く**——どちらか片方では届かない。
        val peak = w.hourlyRain.withIndex().maxByOrNull { it.value }
        val summary = when {
            peak == null -> null
            peak.value < 20 -> "この先12時間、降水確率は低いまま"
            else -> "最も高いのは ${w.hours[peak.index]}時の ${peak.value}%"
        }
        summary?.let {
            ObservatoryText(
                text = it,
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // 湿度と気圧。**気圧は数字で見ても意味が掴めないが、向きは掴める**——
        // 下がっていれば崩れる、という読み方ができるのは並べたときだけである。
        if (w.hourlyHumidity.size == w.hours.size) {
            Spacer(modifier = Modifier.padding(top = 10.dp))
            SeriesFigure(
                labels = w.hours,
                bars = w.hourlyHumidity.map { it.toFloat() },
                barUnit = "湿度 %",
                line = w.hourlyPressure.map { it.toFloat() },
                lineUnit = if (w.hourlyPressure.isEmpty()) "" else "気圧 hPa",
                height = 72.dp,
            )
        }
    }
}

/**
 * 次の行動。
 *
 * この面で**最も強く出すべき一行**である。
 * 予定の一覧は「何があるか」を並べるが、開いた人が知りたいのはたいてい一つ
 * ——**次に何をすればいいか**である。
 *
 * 出し分けは三つだけ。
 *
 * - 今まさに何かの最中なら、それが何で、いつ終わるか。
 * - そうでなければ、次は何で、あと何分か。
 * - 今日この先が空なら、明日の最初。
 *
 * **四つ目を作らない。** 場合分けを増やすほど、読む側は「どの場合か」を
 * 判じることになり、一目で分かるという最初の目的から遠ざかる。
 */
@Composable
internal fun NextActionTile(modifier: Modifier = Modifier, refreshKey: Int = 0) {
    val context = LocalContext.current
    val today by produceState<Pair<List<Appointment>, List<Appointment>>?>(null, refreshKey) {
        value = Glance.today(context)
    }
    val upcoming by produceState(initialValue = emptyList<Appointment>(), refreshKey) {
        value = Glance.appointments(context, limit = 6)
    }
    val ahead = upcoming.firstOrNull()
    val now = System.currentTimeMillis()
    val timed = today?.first.orEmpty()

    val running = timed.firstOrNull { it.begin <= now && now < it.end }
    val next = timed.firstOrNull { it.begin > now }
    val beyond = ahead?.takeIf { it.begin > now }

    val heading: String
    val title: String
    val trailing: String
    val hot: Boolean
    when {
        running != null -> {
            heading = "いま"
            title = running.title
            trailing = "〜" + clockOf(running.end) + "  残り " + gap(running.end - now)
            hot = true
        }
        next != null -> {
            heading = "次"
            title = clockOf(next.begin) + "  " + next.title
            trailing = "あと " + gap(next.begin - now)
            hot = false
        }
        beyond != null -> {
            heading = beyond.dayLabel(now)
            title = beyond.timeLabel() + "  " + beyond.title
            trailing = ""
            hot = false
        }
        else -> {
            heading = "次"
            title = "予定なし"
            trailing = ""
            hot = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            ObservatoryText(
                text = heading,
                // **進行中だけ燠火。** 「今まさに動いている」という意味は、
                // この体系では一貫して燠火が担う。
                //
                // ⚠️ ここは Faint の Label にしていたが読めなかった。
                // **自明でないものを薄くしない**——「次」「いま」は
                // その下の一行が何であるかを決める語である（本人指摘・2026/9/1）。
                color = if (hot) ObservatoryColors.EmberText else ObservatoryColors.Muted,
                size = ObservatoryType.Note,
                mono = true,
            )
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
                size = ObservatoryType.Body,
                mono = true,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // **その後の予定も、この一枚が持つ。**
        //
        // 直近の予定を別の札で並べると、先頭がここと同じものになる。
        // 同じ予定を二箇所に書くと、どちらが正かを読む側が考えることになるので、
        // 一枚にまとめ、**ここで出したものは除いて**続きを並べる。
        val shown = running?.begin ?: next?.begin ?: beyond?.begin
        val rest = upcoming.filter { it.begin != shown && it.begin > now }.take(2)
        rest.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                ObservatoryText(
                    text = item.dayLabel(now) + " " + item.timeLabel(),
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Note,
                    mono = true,
                )
                Spacer(modifier = Modifier.width(10.dp))
                ObservatoryText(
                    text = item.title,
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Body,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 残り時間を読む形へ。**一時間を超えたら分は丸める**——分単位の精度は要らない。 */
private fun gap(millis: Long): String {
    val minutes = (millis / 60_000).coerceAtLeast(0)
    return when {
        minutes < 60 -> "${minutes}分"
        minutes < 24 * 60 -> "${minutes / 60}時間${minutes % 60}分"
        else -> "${minutes / (24 * 60)}日"
    }
}

/**
 * 注意の会計。
 *
 * 制限した時間ではなく、**生まれた時間**の側を数える。
 * 「今日 38 分使った」は事実だが、それだけでは道具が人を責める形にしかならない。
 * 「開かずに戻った 7 回」「先週の平均より 32 分少ない」を並べて、
 * **やらなかったことを成果として残す**。
 *
 * 対象は摩擦を掛けたアプリだけ。**指していないものを勝手に数えない**。
 */
@Composable
internal fun AttentionTile(
    friction: Set<String>,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
) {
    val context = LocalContext.current
    if (friction.isEmpty()) {
        ObservatoryText(
            text = "摩擦を掛けたアプリがありません",
            color = ObservatoryColors.Faint,
            size = ObservatoryType.Note,
            modifier = modifier,
        )
        return
    }
    val days by produceState(initialValue = emptyList<Usage.Day>(), refreshKey) {
        value = withContext(Dispatchers.Default) { Usage.days(context, 7) }
    }
    val avoided by produceState(initialValue = 0, refreshKey) {
        value = withContext(Dispatchers.Default) { Restraint.today(context) }
    }
    if (days.isEmpty()) return

    val perDay = remember(days, friction) {
        days.map { day -> day.byPackage.filterKeys { it in friction }.values.sum() }
    }
    val today = perDay.lastOrNull() ?: 0L
    // 比べる相手は**今日を除いた過去の平均**。今日を混ぜると、
    // 今日が良い日でも平均が引き寄せられて差が縮む。
    val past = perDay.dropLast(1)
    val average = if (past.isEmpty()) 0L else past.sum() / past.size
    val saved = (average - today).coerceAtLeast(0L)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column {
                ObservatoryText(
                    text = "今日",
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                )
                ObservatoryText(
                    text = readableMinutes(today),
                    color = ObservatoryColors.Paper,
                    size = ObservatoryType.Value,
                    mono = true,
                )
            }
            Spacer(modifier = Modifier.width(20.dp))
            Column {
                ObservatoryText(
                    text = "開かなかった",
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                )
                ObservatoryText(
                    text = "${avoided}回",
                    color = ObservatoryColors.Paper,
                    size = ObservatoryType.Value,
                    mono = true,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                ObservatoryText(
                    text = "生まれた時間",
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                )
                ObservatoryText(
                    // **0 でも出す。** 出したり消したりすると、
                    // 「無い日は書かない」という不正直な見え方になる。
                    text = readableMinutes(saved),
                    color = if (saved > 0) {
                        ObservatoryColors.EmberText
                    } else {
                        ObservatoryColors.Muted
                    },
                    size = ObservatoryType.Hero,
                    mono = true,
                )
            }
        }
        Spacer(modifier = Modifier.padding(top = 10.dp))
        ColumnsFigure(
            labels = days.map { weekdayOf(it.start) },
            values = perDay.map { it.toFloat() },
            format = { "%d分".format((it / 60_000).toInt()) },
        )
        ObservatoryText(
            text = "七日平均 " + readableMinutes(
                if (perDay.isEmpty()) 0L else perDay.sum() / perDay.size,
            ),
            color = ObservatoryColors.Faint,
            size = ObservatoryType.Label,
            mono = true,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/**
 * 七日の散らばり。
 *
 * 合計や平均では、**毎日同じくらい使っているのか、一日だけ跳ねているのか**が分からない。
 * 箱ひげはそこにだけ答える図である。
 */
@Composable
internal fun SpreadTile(modifier: Modifier = Modifier, refreshKey: Int = 0) {
    val context = LocalContext.current
    val days by produceState(initialValue = emptyList<Usage.Day>(), refreshKey) {
        value = withContext(Dispatchers.Default) { Usage.days(context, 7) }
    }
    if (days.size < 3) return
    val values = days.map { it.totalMs.toFloat() }
    val five = remember(values) { FiveNumber.of(values) } ?: return
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            ObservatoryText(
                text = "七日の五数要約",
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
            )
            Spacer(modifier = Modifier.weight(1f))
            ObservatoryText(
                text = "中央値 " + readableMinutes(five.median.toLong()),
                color = ObservatoryColors.EmberText,
                size = ObservatoryType.Note,
                mono = true,
            )
        }
        Spacer(modifier = Modifier.padding(top = 8.dp))
        BoxPlotFigure(
            summary = five,
            format = { "%d分".format((it / 60_000).toInt()) },
        )
    }
}


/** 通信量の七日推移。上限に関わるのはモバイルなので、そちらを並べる。 */
@Composable
internal fun TrafficWeekTile(modifier: Modifier = Modifier, refreshKey: Int = 0) {
    val context = LocalContext.current
    val days by produceState(initialValue = emptyList<Pair<Long, Long>>(), refreshKey) {
        value = withContext(Dispatchers.Default) { Traffic.dailyMobile(context, 7) }
    }
    if (days.size < 2) return
    Column(modifier = modifier.fillMaxWidth()) {
        ObservatoryText(
            text = "モバイル 七日",
            color = ObservatoryColors.Faint,
            size = ObservatoryType.Label,
            mono = true,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        ColumnsFigure(
            labels = days.map { weekdayOf(it.first) },
            values = days.map { it.second.toFloat() },
            format = { readableBytes(it.toLong()) },
        )
    }
}

/** 歩数の七日推移。**一日の値だけでは、多いのか少ないのかが分からない。** */
@Composable
internal fun StepsWeekTile(modifier: Modifier = Modifier, refreshKey: Int = 0) {
    val context = LocalContext.current
    val days by produceState(initialValue = emptyList<Pair<java.time.LocalDate, Long>>(), refreshKey) {
        value = withContext(Dispatchers.Default) { Health.dailySteps(context, 7) }
    }
    if (days.size < 2 || days.all { it.second == 0L }) return
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            ObservatoryText(
                text = "歩数 七日",
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
            )
            Spacer(modifier = Modifier.weight(1f))
            ObservatoryText(
                text = "平均 " + (days.sumOf { it.second } / days.size) + "歩",
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Note,
                mono = true,
            )
        }
        Spacer(modifier = Modifier.padding(top = 6.dp))
        ColumnsFigure(
            labels = days.map {
                listOf("日", "月", "火", "水", "木", "金", "土")[it.first.dayOfWeek.value % 7]
            },
            values = days.map { it.second.toFloat() },
            format = { "%.0f".format(it) },
        )
    }
}

/**
 * ひと月の使い方を格子で。
 *
 * 棒は七日までなら読めるが、**ひと月の癖**——週末に偏るのか、平日が一定なのか——は
 * 格子でしか見えない。月の暦と同じ形なので、曜日の並びがそのまま比較になる。
 */
@Composable
internal fun UsageHeatTile(modifier: Modifier = Modifier, refreshKey: Int = 0) {
    val context = LocalContext.current
    val zone = remember { java.time.ZoneId.systemDefault() }
    val values by produceState(
        initialValue = emptyList<Pair<java.time.LocalDate, Long>>(),
        refreshKey,
    ) {
        value = withContext(Dispatchers.Default) {
            Usage.days(context, 28).map {
                java.time.Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate() to it.totalMs
            }
        }
    }
    if (values.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            ObservatoryText(
                text = "曜日別の分布",
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
            )
            Spacer(modifier = Modifier.weight(1f))
            ObservatoryText(
                text = "最大 " + readableMinutes(values.maxOfOrNull { it.second } ?: 0L),
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Label,
                mono = true,
            )
        }
        HeatGridFigure(values = values, modifier = Modifier.padding(top = 8.dp))
    }
}


/**
 * 開いた回数。
 *
 * **「三十分」より「四十二回」の方が効く。**
 * 時間は流れるが、回数は数えた分だけ自分の指の動きである。
 * 合計だけを出していた頃は、その数字が自分の行動だと感じられなかった。
 */
@Composable
internal fun OpensTile(modifier: Modifier = Modifier, refreshKey: Int = 0, top: Int = 4) {
    val context = LocalContext.current
    val analysis by produceState<Behaviour.Analysis?>(null, refreshKey) {
        value = withContext(Dispatchers.Default) { Behaviour.today(context) }
    }
    val a = analysis ?: return
    if (a.opensTotal == 0) return
    val names = remember(a.opens, refreshKey) {
        val known = AppRepository.cached().associate { it.packageName to it.label }
        val packages = context.packageManager
        a.opens.entries.sortedByDescending { it.value }.take(top).map { (pkg, count) ->
            val label = known[pkg] ?: runCatching {
                packages.getApplicationLabel(packages.getApplicationInfo(pkg, 0)).toString()
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: pkg.substringAfterLast('.')
            Figures.Item(label = label, value = count.toLong())
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column {
                ObservatoryText(
                    text = "起動回数",
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                )
                ObservatoryText(
                    text = "${a.opensTotal}回",
                    color = ObservatoryColors.Paper,
                    size = ObservatoryType.Hero,
                    mono = true,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                ObservatoryText(
                    text = "一回あたり平均",
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                )
                ObservatoryText(
                    text = readableMinutes(a.averageMs),
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Body,
                    mono = true,
                )
            }
        }
        Spacer(modifier = Modifier.padding(top = 8.dp))
        BarsFigure(
            items = names,
            format = { "${it}回" },
            axis = true,
            axisLabel = { "%d".format(it.toInt()) },
        )
    }
}

/**
 * 一日の時間帯。
 *
 * 「今日 3 時間」では、それが**いつの三時間だったか**が分からない。
 * 昼休みに集中していたのか、夜中に伸びたのかで、次にどうするかが変わる。
 */
@Composable
internal fun DayShapeTile(modifier: Modifier = Modifier, refreshKey: Int = 0) {
    val context = LocalContext.current
    val analysis by produceState<Behaviour.Analysis?>(null, refreshKey) {
        value = withContext(Dispatchers.Default) { Behaviour.today(context) }
    }
    val a = analysis ?: return
    if (a.totalMs <= 0L) return
    // 三時間ずつ八本に畳む。二十四本は棒が細すぎて形が読めない。
    val bins = remember(a.hourly) { (0 until 8).map { i -> a.hourly.drop(i * 3).take(3).sum() } }
    val peak = bins.withIndex().maxByOrNull { it.value }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            ObservatoryText(
                text = "時刻別の分布",
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (a.lateMs > 0) {
                // 夜は別に言う。**昼の一時間と重さが違う。**
                ObservatoryText(
                    text = "22時以降 " + readableMinutes(a.lateMs),
                    color = ObservatoryColors.EmberText,
                    size = ObservatoryType.Note,
                    mono = true,
                )
            }
        }
        Spacer(modifier = Modifier.padding(top = 8.dp))
        ColumnsFigure(
            labels = (0 until 8).map { "%02d".format(it * 3) },
            values = bins.map { it.toFloat() },
            format = { "%d分".format((it / 60_000).toInt()) },
            hot = peak?.index ?: -1,
        )
        a.longest?.let { s ->
            val name = remember(s, refreshKey) {
                AppRepository.cached().firstOrNull { it.packageName == s.packageName }?.label
                    ?: s.packageName.substringAfterLast('.')
            }
            ObservatoryText(
                text = "最長の区間 " + name + "  " + readableMinutes(s.lengthMs) +
                    "（" + clockOf(s.from) + "〜）",
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}


/**
 * 今日の歩調。
 *
 * **この一つが最も行動に効く。**
 * 「今日 1時間20分」だけでは多いか少ないか判じられないが、
 * 「ふだんのこの時刻より +32分」なら、その場で分かる。
 *
 * ⚠️ 比べるのは**同じ時刻まで**。一日の合計と今日の途中を比べると、
 * 朝はいつでも「少ない」ことになり、数字が何も言わなくなる。
 */
@Composable
internal fun PaceTile(modifier: Modifier = Modifier, refreshKey: Int = 0) {
    val context = LocalContext.current
    val pace by produceState<Behaviour.Pace?>(null, refreshKey) {
        value = withContext(Dispatchers.Default) { Behaviour.pace(context) }
    }
    val p = pace ?: return
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column {
                // ⚠️ ここは「02:05 時点」とだけ書いていた。**時刻は主語ではない。**
                //
                // 計器盤では節の見出し（「今日の使用」）が主語を補っていたが、
                // **ホームには節の見出しが無い**ので、数字が何の数字か分からなかった
                // （外部評価・2026/9/2「5分は何の5分か復元できない」）。
                //
                // 札は、置かれる面を選べない。**札自身が名乗る。**
                // ⚠️ 「画面時間」ではない。
                //
                // 数えているのは**起動口を持つアプリを前面に置いていた時間**で、
                // このランチャー自身は除いてある。画面が点いていても、
                // ホームを見ているだけの時間はここに入らない。
                //
                // 「SNSだけの時間か」と問われた（本人・2026/9/2）。
                // **主語を与えたのに、その主語が正確でなければ意味が無い。**
                ObservatoryText(
                    text = "今日のアプリ使用",
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                )
                ObservatoryText(
                    text = readableMinutes(p.todayMs),
                    color = ObservatoryColors.Paper,
                    size = ObservatoryType.Hero,
                    mono = true,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (p.comparable) {
                Column(horizontalAlignment = Alignment.End) {
                    ObservatoryText(
                        text = "平均との差",
                        color = ObservatoryColors.Faint,
                        size = ObservatoryType.Label,
                        mono = true,
                    )
                    val diff = p.differenceMs
                    ObservatoryText(
                        // **符号を残す。** 多いのか少ないのかが消えると、比べた意味が無い。
                        text = (if (diff >= 0) "+" else "−") + readableMinutes(kotlin.math.abs(diff)),
                        // 多い側だけ燠火。少ない日を責めない代わりに、
                        // 多い日は目に留まるようにする。
                        color = if (diff > 0) {
                            ObservatoryColors.EmberText
                        } else {
                            ObservatoryColors.Muted
                        },
                        size = ObservatoryType.Value,
                        mono = true,
                    )
                }
            }
        }
        if (p.comparable) {
            ObservatoryText(
                // **見積りに「約」を付ける。** 過去の日は、消えた出来事を
                // その日の欠け具合で補って出している（[Behaviour.pace]）。
                text = clockOf(System.currentTimeMillis()) + " 時点 ∧ 直近 " +
                    p.days + " 日の同時刻平均 約" + readableMinutes(p.usualMs),
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 曜日一文字。 */
private fun weekdayOf(at: Long): String {
    val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
    return listOf("日", "月", "火", "水", "木", "金", "土")[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
}

/**
 * 聴いたものの集計——**計器盤の側**。
 *
 * ## 音の面と、何が違うか
 *
 * | | 音の面 | ここ（計器盤） |
 * | --- | --- | --- |
 * | 私的な側の再生 | **出さない** | **名を伏せて入れる** |
 * | 見せるもの | 今の一曲・絵柄・操作 | 数だけ |
 *
 * > 「プライベートスペースの再生した情報は匿名加工して情報のダッシュボード面に
 * >  乗るようにして下さい。プライベートのやつは適当な名前を付けて
 * >  ひとまとめにするなどしてください」（本人・2026/9/2）
 *
 * ⚠️ **消すのではなく、伏せる。** 消すと「今日どれだけ聴いたか」が嘘になる。
 * 名だけを一つ（[Listening.HIDDEN_NAME]）に束ねれば、
 * **数は正しいまま、中身は出ない**。
 */
@Composable
internal fun ListeningTile(modifier: Modifier = Modifier, refreshKey: Int = 0) {
    val context = LocalContext.current
    // 計器盤は**数の面**なので、私的な記録は名を伏せて残す（[Hidden.ANONYMISE]）。
    val log by rememberPlays(refreshKey, Hidden.ANONYMISE)
    if (log.isEmpty()) return
    val now = System.currentTimeMillis()
    val days = remember(log) { Listening.byDay(log, 7, now) }
    // ⚠️ **節が名乗る範囲（七日）に揃える。**
    // ここだけ記録の全期間（ひと月）から数えていたので、
    // 七日の棒と演者の内訳が別の期間を指していた（2026/9/2 の点検で発見）。
    val artists = remember(log) { Listening.byArtist(Listening.within(log, 7, now), now) }
    val today = remember(log) { Listening.summarize(Listening.today(log, now), now) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column {
                ObservatoryText(
                    text = "今日",
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                )
                ObservatoryText(
                    text = readableMinutes(today.listenedMs),
                    color = ObservatoryColors.Paper,
                    size = ObservatoryType.Hero,
                    mono = true,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                ObservatoryText(
                    text = "七日",
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                )
                ObservatoryText(
                    text = readableMinutes(days.sumOf { it.listenedMs }),
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Value,
                    mono = true,
                )
            }
        }
        if (days.any { it.listenedMs > 0 }) {
            ColumnsFigure(
                labels = days.map { it.label },
                values = days.map { (it.listenedMs / 60_000).toFloat() },
                format = { "${it.toInt()}分" },
                height = 76.dp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (artists.isNotEmpty()) {
            BarsFigure(
                items = artists.take(4).map { Figures.Item(label = it.first, value = it.second) },
                format = { readableMinutes(it) },
                axis = true,
                axisLabel = { "${(it / 60_000).toInt()}分" },
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
