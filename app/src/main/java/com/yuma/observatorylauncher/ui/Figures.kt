package com.yuma.observatorylauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import androidx.compose.foundation.layout.Arrangement
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 図の語彙。
 *
 * 情報の札が「見出しと数行」しか持てなかったので、値は出せても
 * 既存のウィジェットに見劣りした。**足りないのは情報量ではなく形**である。
 *
 * ここに置くのは、この体系の語彙だけで作った図の部品である。
 *
 * ## 約束
 *
 * - **面で塗らない。** 燠火は ground 比 3.64:1 で AA に届かないので、
 *   広い面積に置くと読めない色の塊になる。使うのは罫・小さな格点・短い刻みだけ。
 * - **燠火は「今」だけ。** 今この瞬間を指す印にしか使わない。
 *   済んだものは沈め([ObservatoryColors.Faint])、これからのものは白の刻み。
 * - **軸には必ず目盛りを添える。** 目盛りの無い棒は、長さの意味が読めない。
 * - **数字は等幅。** 桁が揺れると図の中で数字が跳ねる。
 */
object Figures {

    /** 時間軸に置く一件。[from]/[to] は軸の左右端と同じ単位（ミリ秒）でよい。 */
    data class Span(
        val label: String,
        val from: Long,
        val to: Long,
        /** 済んだもの。沈めて描く。 */
        val done: Boolean = false,
        /** 今まさに動いているもの。**燠火はここだけ**。 */
        val running: Boolean = false,
    )

    /** 順位付きの一項目。[value] は同じ単位であればよい（秒でもバイトでも）。 */
    data class Item(val label: String, val value: Long, val note: String = "")
}

/**
 * 時間軸。一日を一本の線にして、予定を横棒で置く。
 *
 * 予定の一覧は「次に何があるか」しか答えないが、時間軸は
 * **空いているのはどこか**に答える。既存の暦ウィジェットが持たない形で、
 * この体系の罫と格点だけで描ける。
 *
 * @param spans 置くもの。軸からはみ出す分は端で切る（跨ぎの予定がそうなる）。
 * @param startHour 軸の左端。眠っている時間まで描くと、昼の密度が潰れる。
 * @param endHour 軸の右端。
 * @param nowFraction 今の位置(0..1)。範囲外なら燠火の縦線を引かない。
 */
@Composable
fun TimelineFigure(
    spans: List<Figures.Span>,
    dayStart: Long,
    startHour: Int = 7,
    endHour: Int = 24,
    now: Long = System.currentTimeMillis(),
    modifier: Modifier = Modifier,
) {
    val hourMs = 3_600_000L
    val axisFrom = dayStart + startHour * hourMs
    val axisTo = dayStart + endHour * hourMs
    val width = (axisTo - axisFrom).toFloat()

    fun at(t: Long): Float = ((t - axisFrom) / width).coerceIn(0f, 1f)

    val rule = ObservatoryColors.Line
    val tickColor = ObservatoryColors.LineStrong
    val nowAt = if (now in axisFrom..axisTo) at(now) else null

    // 名前の列の幅。**軸も棒も、この列を除いた同じ幅で描く。**
    //
    // ⚠️ ここを揃えていなかったため、軸は画面いっぱい・棒は名前を除いた幅、
    // という二つの座標系ができ、17:10 の予定が 13 時台に描かれた（実機・2026/8/31）。
    // 図で最も壊れやすいのは値でも色でもなく、**軸と中身が同じ物差しを見ているか**である。
    // **予定が無い窓では、名前の列を確保しない。**
    // 空の列のぶんだけ軸が右で途切れ、他の札が幅いっぱいなのにここだけ短く見える。
    // 確保する理由（名前を置く）が無いときに確保するのは、ただの非対称である。
    val nameWidth = if (spans.isEmpty()) 0.dp else 132.dp
    val nameGap = if (spans.isEmpty()) 0.dp else 8.dp

    Column(modifier = modifier.fillMaxWidth()) {
        // **凡例を一行だけ置く。**
        //
        // 目盛りと棒だけでは、初見の人に「何の図か」が伝わらない（本人の指摘・2026/8/31）。
        // 図が自分で名乗らないなら、それは読む側に前提を要求しているということである。
        // 何の一日か（今日）・燠火が何を指すか（現在）を、言葉で一度だけ示す。
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ObservatoryText(
                text = "今日",
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Note,
                mono = true,
            )
            Spacer(modifier = Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(width = 2.dp, height = 8.dp)
                    .background(ObservatoryColors.Ember),
            )
            Spacer(modifier = Modifier.width(4.dp))
            ObservatoryText(
                text = "現在 " + clockLabel(now),
                color = ObservatoryColors.Muted,
                size = ObservatoryType.Note,
                mono = true,
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f)) {
                // 目盛り。三時間ごとに長刻み、一時間ごとに短刻み。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .drawBehind {
                            val baseY = size.height - 1.dp.toPx()
                            drawRect(rule, Offset(0f, baseY), Size(size.width, 1.dp.toPx()))
                            // 一時間ごとに長刻み、三十分ごとに短刻み。
                            //
                            // 24時間を幅いっぱいに詰めていた頃は三時間ごとに間引いていたが、
                            // **窓を六時間にしたので刻みを間引く理由が無くなった。**
                            // 三十分の予定が読めるかどうかは、ここの解像度で決まる。
                            var half = startHour * 2
                            while (half <= endHour * 2) {
                                val x = at(dayStart + half * hourMs / 2) *
                                    (size.width - 1.dp.toPx())
                                val onTheHour = half % 2 == 0
                                val len = if (onTheHour) 8.dp.toPx() else 4.dp.toPx()
                                drawRect(
                                    color = if (onTheHour) tickColor else rule,
                                    topLeft = Offset(x, baseY - len),
                                    size = Size(1.dp.toPx(), len),
                                )
                                half++
                            }
                            // 今の位置。軸を貫く一本だけが燠火。
                            nowAt?.let {
                                val x = it * (size.width - 1.dp.toPx())
                                drawRect(
                                    color = ObservatoryColors.Ember,
                                    topLeft = Offset(x, 0f),
                                    size = Size(1.dp.toPx(), size.height),
                                )
                            }
                        },
                )
                // 時刻の見出しは**刻みと同じ位置**へ置く。
                // 等間隔に配ると、軸の右端が半端な時刻のときに見出しだけがずれる。
                // **字を小さくしすぎない。** 9sp では読めないという指摘を受けた。
                // 図の見出しは装飾ではなく、軸を読むための唯一の手掛かりである。
                AxisLabels(
                    marks = (startHour..endHour).map { h ->
                        at(dayStart + h * hourMs) to "%02d".format(h % 24)
                    },
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Note,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            Spacer(modifier = Modifier.width(nameGap))
            Spacer(modifier = Modifier.width(nameWidth))
        }
        Spacer(modifier = Modifier.height(10.dp))

        if (spans.isEmpty()) {
            // ⚠️ 「予定なし」とだけ書いていた。**どの範囲の話かが抜けている。**
            //
            // 六時間の窓を映す図なので、窓の外に予定があっても「予定なし」と出る。
            // 実際、下の脚注には「今日あと 2 件」と書いてあり、
            // **同じ札の中で矛盾しているように読めた**（外部評価・2026/9/2）。
            //
            // 図が見ている範囲は、図が言う。
            ObservatoryText(
                text = "%02d–%02d時に予定なし".format(startHour % 24, endHour % 24),
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Note,
                modifier = Modifier.padding(top = 6.dp),
            )
            return@Column
        }

        spans.forEach { span ->
            val left = at(span.from)
            val right = at(span.to)
            val ink = when {
                span.running -> ObservatoryColors.Ember
                span.done -> ObservatoryColors.Faint
                else -> ObservatoryColors.Muted
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(9.dp)
                        .drawBehind {
                            val midY = size.height / 2
                            val h = 4.dp.toPx()
                            // 帯は細い罫で。**塗った箱にはしない**——
                            // 面積を持たせると、予定の数だけ色の塊が並ぶ。
                            val x0 = left * size.width
                            val x1 = max(right * size.width, x0 + 2.dp.toPx())
                            drawRect(ink, Offset(x0, midY - h / 2), Size(x1 - x0, h))
                            // 始まりと終わりに格点を置く。境目がどこかを形で示す。
                            listOf(x0, x1 - 2.dp.toPx()).forEach { x ->
                                drawRect(
                                    color = ink,
                                    topLeft = Offset(x, midY - 4.dp.toPx()),
                                    size = Size(2.dp.toPx(), 8.dp.toPx()),
                                )
                            }
                        },
                )
                Spacer(modifier = Modifier.width(nameGap))
                // **時刻を名前の前に置く。** 棒の位置から時刻を読ませるのは、
                // 軸を目で追える人にしか通じない。数字で一度書けば誰にでも通じる。
                Row(
                    modifier = Modifier.width(nameWidth),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ObservatoryText(
                        text = clockLabel(span.from),
                        color = ObservatoryColors.Faint,
                        size = ObservatoryType.Label,
                        mono = true,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    ObservatoryText(
                        text = span.label,
                        color = if (span.done) {
                            ObservatoryColors.Faint
                        } else {
                            ObservatoryColors.Paper
                        },
                        size = ObservatoryType.Note,
                    )
                }
            }
        }
    }
}

/**
 * 順位付きの横棒。内訳を「多い順に、長さで」示す。
 *
 * 「今日 2時間52分・最長 YouTube」と書くよりも、**何がどれだけ占めたか**が一目で出る。
 * 通信量・保存領域・アプリ別の使用時間はすべて同じ形で表せる。
 *
 * @param format 値を人が読む形へ。単位はこの図では持たない。
 */
@Composable
fun BarsFigure(
    items: List<Figures.Item>,
    format: (Long) -> String,
    modifier: Modifier = Modifier,
    max: Long = items.maxOfOrNull { it.value } ?: 1L,
    /**
     * 下に値軸を敷く。
     *
     * 横棒は「どれが長いか」は一目で分かるが、**どれだけか**は右の数字を読むしかない。
     * 軸があれば、数字を読まずに長さから量が取れる——統計の図がそうしている理由である。
     */
    axis: Boolean = false,
    /** 軸の刻みに添える単位の書き方。 */
    axisLabel: (Float) -> String = { format(it.toLong()) },
    /** 目盛りを刻む単位（[TICK_MINUTE] など）。刻みの「きりの良さ」は表示の単位で決める。 */
    tickUnit: Float = 1f,
) {
    if (items.isEmpty()) return
    val top = max.coerceAtLeast(1L)

    // ## ⚠️ 棒と目盛りは、同じ分母で割らなければならない
    //
    // 目盛りは「いちばん高い棒より上に一本」出るようにした（[niceTicks]）。
    // ところがここは**棒を最大値で割り、目盛りも最大値で割っていた**ので、
    // 最後の目盛りだけが 1 を超え、**数直線の右端の外へ「25分」が飛び出した**
    // （本人の指摘・2026/9/2）。
    //
    // 軸を敷くときは、棒も目盛りも**目盛りの天井**で割る。
    // 軸が無いときは最大値のままでよい——あちらは長さの比べ合いで、絶対量を読ませない。
    val ticks = remember(top, tickUnit) { niceTicksIn(top.toFloat(), tickUnit) }
    val ceiling = if (axis) ticks.last().coerceAtLeast(top.toFloat()) else top.toFloat()

    // **値の列は固定幅**（下の注記）。ただし幅は見積らず、いちばん長い値を測って決める。
    val valueWidth = measureLabels(items.map { format(it.value) }).widest + 1.dp
    val reveal = rememberReveal()

    Column(modifier = modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            val share = (item.value.toFloat() / ceiling).coerceIn(0f, 1f)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = if (index == 0) 0.dp else 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ObservatoryText(
                    text = item.label,
                    color = ObservatoryColors.Paper,
                    size = ObservatoryType.Note,
                    modifier = Modifier.width(96.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .drawBehind {
                            val midY = size.height / 2
                            val h = 3.dp.toPx()
                            // 全体の幅を薄い罫で先に引く。**分母が見えないと割合が読めない。**
                            drawRect(
                                ObservatoryColors.Grid,
                                Offset(0f, midY - 0.5.dp.toPx()),
                                Size(size.width, 1.dp.toPx()),
                            )
                            // 首位だけ燠火。「今いちばん食っているもの」を指す。
                            val ink = if (index == 0) {
                                ObservatoryColors.Ember
                            } else {
                                ObservatoryColors.Muted
                            }
                            drawRect(
                                ink,
                                Offset(0f, midY - h / 2),
                                Size(size.width * share * reveal.value, h),
                            )
                        },
                )
                Spacer(modifier = Modifier.width(8.dp))
                // **値の列は固定幅。** 幅が中身で変わると、下の軸と棒の物差しがずれる
                // ——軸だけが少し短い図になる（実機で出た・2026/9/1）。
                ObservatoryText(
                    text = format(item.value),
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Label,
                    mono = true,
                    modifier = Modifier.width(valueWidth),
                )
            }
        }
        if (axis) {
            // 棒と同じ幅の帯に、同じ物差しで刻みを打つ。
            // 名前の列と数字の列を除いた幅が棒の領域なので、そこへ揃える。
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Spacer(modifier = Modifier.width(96.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(5.dp)
                            .drawBehind {
                                val hair = 1.dp.toPx()
                                val full = size.width
                                drawRect(
                                    ObservatoryColors.LineStrong,
                                    Offset(0f, 0f),
                                    Size(full, hair),
                                )
                                ticks.forEach { v: Float ->
                                    val x = full * (v / ceiling)
                                    drawRect(
                                        ObservatoryColors.LineStrong,
                                        Offset(x.coerceAtMost(full - hair), 0f),
                                        Size(hair, 4.dp.toPx()),
                                    )
                                }
                            },
                    )
                    // ⚠️ **高さを明示する。** `offset` は測る大きさを変えないので、
                    // 器の高さが刻みの線(5dp)のままだと、数字は場所を持てずに消える。
                    // 実機で軸の線だけが出て数字が出なかった（2026/9/1）。
                    // 高さは字を測って決める——拡大設定の端末で数字が縦に切れた（実機）。
                    AxisLabels(
                        marks = ticks.map { v -> (v / ceiling) to axisLabel(v) },
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Spacer(modifier = Modifier.width(valueWidth))
            }
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

/** 横棒の右に置く値の列の幅。軸の帯もこれと同じだけ空ける。 */

/**
 * 推移。折れ線と格点で描く。
 *
 * §N-7 の弦材と同じ形で、**上下の基準線の間に折れ線を通す**。
 * 面で塗らないのは他の図と同じ理由による。
 *
 * @param values 左から右へ。空なら何も描かない。
 * @param labels 軸の下に置く見出し。数は [values] と揃っていなくてよい（両端だけでも足りる）。
 */
@Composable
fun TrendFigure(
    values: List<Float>,
    modifier: Modifier = Modifier,
    labels: List<String> = emptyList(),
    height: androidx.compose.ui.unit.Dp = 44.dp,
    ink: Color = ObservatoryColors.Muted,
    /** 与えると左に値軸を出す。単位は見出しに書く。 */
    axisUnit: String? = null,
) {
    if (values.size < 2) return
    val low = values.min()
    val high = values.max()
    val range = (high - low).takeIf { it > 0f } ?: 1f
    val reveal = rememberReveal()
    Column(modifier = modifier.fillMaxWidth()) {
      Row(modifier = Modifier.fillMaxWidth()) {
        // 左の値軸。**線と同じ式で置く**（等間隔に並べると線とずれる）。
        if (axisUnit != null) {
            ValueAxis(
                ticks = listOf(high, low).map { v ->
                    (1f - (v - low) / range) to "%.0f".format(v)
                },
                height = height,
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .height(height)
                .drawBehind {
                    val hair = 1.dp.toPx()
                    // 上下の弦。値の幅がここに収まっていることを示す。
                    drawRect(ObservatoryColors.Grid, Offset(0f, 0f), Size(size.width, hair))
                    drawRect(
                        ObservatoryColors.Grid,
                        Offset(0f, size.height - hair),
                        Size(size.width, hair),
                    )
                    val step = size.width / (values.size - 1)
                    fun y(v: Float) = size.height - ((v - low) / range) * size.height
                    val drawn = (values.size - 1) * reveal.value
                    for (i in 0 until values.size - 1) {
                        val part = (drawn - i).coerceIn(0f, 1f)
                        if (part <= 0f) break
                        val a = Offset(i * step, y(values[i]))
                        val b = Offset((i + 1) * step, y(values[i + 1]))
                        drawLine(color = ink, start = a, end = a + (b - a) * part, strokeWidth = hair)
                    }
                    // 格点。最後の一つだけ燠火——**それが「今」である**。
                    values.forEachIndexed { i, v ->
                        val s = 3.dp.toPx()
                        drawRect(
                            color = if (i == values.lastIndex) ObservatoryColors.Ember else ink,
                            topLeft = Offset(i * step - s / 2, y(v) - s / 2),
                            size = Size(s, s),
                        )
                    }
                },
        )
      }
        if (labels.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
                labels.forEachIndexed { index, text ->
                    ObservatoryText(
                        text = text,
                        color = ObservatoryColors.Faint,
                        size = ObservatoryType.Label,
                        mono = true,
                    )
                    if (index != labels.lastIndex) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** 秒数を「2時間52分」の形へ。図の脇に添える用。 */
fun readableMinutes(millis: Long): String {
    val minutes = (millis / 60_000).toInt()
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}時間${m}分" else "${m}分"
}

/** バイト数を人が読む形へ。**基数は1024**で、単位を必ず添える。 */
fun readableBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes}B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var at = 0
    while (value >= 1024 && at < units.lastIndex) {
        value /= 1024
        at++
    }
    return if (value >= 100) {
        "${value.roundToInt()}${units[at]}"
    } else {
        "%.1f%s".format(value, units[at])
    }
}


/**
 * 五数要約。**並べ替えて位置で取る**——外れ値に引きずられない取り方である。
 *
 * 四分位は「下位半分の中央値・上位半分の中央値」で取る（Tukey の方法）。
 * 標本が少ないときに素直で、手で検算できる。
 */
data class FiveNumber(
    val min: Float,
    val q1: Float,
    val median: Float,
    val q3: Float,
    val max: Float,
) {
    companion object {
        fun of(values: List<Float>): FiveNumber? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            fun med(list: List<Float>): Float {
                val n = list.size
                return if (n % 2 == 1) list[n / 2] else (list[n / 2 - 1] + list[n / 2]) / 2f
            }
            val half = s.size / 2
            val lower = s.take(half)
            val upper = if (s.size % 2 == 1) s.drop(half + 1) else s.drop(half)
            return FiveNumber(
                min = s.first(),
                q1 = if (lower.isEmpty()) s.first() else med(lower),
                median = med(s),
                q3 = if (upper.isEmpty()) s.last() else med(upper),
                max = s.last(),
            )
        }
    }
}

/**
 * 箱ひげ図。統計検定の練習サイトの箱ひげを横に倒して移した。
 *
 * **平均と最大だけでは散らばりが分からない**——七日のうち一日だけ跳ねているのか、
 * 毎日同じくらいなのかは、箱の幅にしか出ない。
 *
 * 箱は塗らず、罫と格点で組む（この体系の他の図と同じ約束）。
 * 中央値だけを燠火にする——箱の中で最も読ませたい一本だからである。
 */
@Composable
fun BoxPlotFigure(
    summary: FiveNumber,
    format: (Float) -> String,
    modifier: Modifier = Modifier,
    ceiling: Float = summary.max,
    /** 目盛りを刻む単位。[BarsFigure] と同じ。 */
    tickUnit: Float = 1f,
) {
    val ticks = remember(ceiling, tickUnit) { niceTicksIn(ceiling, tickUnit) }
    // 天井は最後の目盛り。**箱も目盛りも同じ天井で割る**（[BarsFigure] の注記）。
    val top = ticks.last().coerceAtLeast(ceiling).coerceAtLeast(1f)
    val ink = ObservatoryColors.Muted
    Column(modifier = modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .drawBehind {
                        val hair = 1.dp.toPx()
                        fun x(v: Float) = size.width * (v / top)
                        val midY = size.height / 2
                        ticks.forEach { v ->
                            drawRect(
                                ObservatoryColors.Grid,
                                Offset(x(v), 0f),
                                Size(hair, size.height),
                            )
                        }
                        drawRect(
                            ink,
                            Offset(x(summary.min), midY),
                            Size(x(summary.q1) - x(summary.min), hair),
                        )
                        drawRect(
                            ink,
                            Offset(x(summary.q3), midY),
                            Size(x(summary.max) - x(summary.q3), hair),
                        )
                        listOf(summary.min, summary.max).forEach { v ->
                            drawRect(
                                ink,
                                Offset(x(v), midY - 6.dp.toPx()),
                                Size(hair, 12.dp.toPx()),
                            )
                        }
                        val left = x(summary.q1)
                        val right = x(summary.q3).coerceAtLeast(left + 2.dp.toPx())
                        val boxTop = midY - 9.dp.toPx()
                        val boxH = 18.dp.toPx()
                        drawRect(ink, Offset(left, boxTop), Size(right - left, hair))
                        drawRect(ink, Offset(left, boxTop + boxH), Size(right - left, hair))
                        drawRect(ink, Offset(left, boxTop), Size(hair, boxH))
                        drawRect(ink, Offset(right - hair, boxTop), Size(hair, boxH))
                        drawRect(
                            ObservatoryColors.Ember,
                            Offset(x(summary.median) - hair / 2, boxTop),
                            Size(hair * 2, boxH),
                        )
                    },
            )
            AxisLabels(
                marks = ticks.map { v -> (v / top) to format(v) },
                modifier = Modifier.padding(top = 36.dp),
            )
        }
        // ⚠️ **器の高さは既に刻みの分を含んでいる。**
        // `BoxWithConstraints` の高さは中で最も背の高い子（＝刻みの帯 36+16dp）で決まるので、
        // ここでもう一度その分を空けると、図の三倍の余白ができる（実機でそうなった）。
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * 縦棒の並び。日ごとの推移に使う。
 *
 * 横棒（[BarsFigure]）は「何が」を並べるためのもので、こちらは「いつ」を並べる。
 * **軸の向きが問いの向きと揃っていないと、読む側が頭の中で回すことになる。**
 */
@Composable
fun ColumnsFigure(
    labels: List<String>,
    values: List<Float>,
    format: (Float) -> String,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 84.dp,
    /** 強調する位置（今日など）。 */
    hot: Int = values.lastIndex,
    /** 目盛りを刻む単位。[BarsFigure] と同じ。 */
    tickUnit: Float = 1f,
) {
    if (values.isEmpty() || labels.size != values.size) return
    val ticks = remember(values, tickUnit) { niceTicksIn(values.max(), tickUnit) }
    val ceiling = ticks.last().coerceAtLeast(values.max()).coerceAtLeast(1f)
    val axisTexts = ticks.map(format)
    val axisWidth = valueAxisWidth(axisTexts)
    val reveal = rememberReveal()
    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            ValueAxis(
                ticks = ticks.mapIndexed { i, v -> (1f - v / ceiling) to axisTexts[i] },
                height = height,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .drawBehind {
                        val hair = 1.dp.toPx()
                        ticks.forEach { v ->
                            val y = size.height * (1f - v / ceiling)
                            drawRect(ObservatoryColors.Grid, Offset(0f, y), Size(size.width, hair))
                        }
                        drawRect(
                            ObservatoryColors.LineStrong,
                            Offset(0f, 0f),
                            Size(hair, size.height),
                        )
                        drawRect(
                            ObservatoryColors.LineStrong,
                            Offset(0f, size.height - hair),
                            Size(size.width, hair),
                        )
                        val slot = size.width / values.size
                        val w = (slot * 0.46f).coerceAtMost(14.dp.toPx())
                        values.forEachIndexed { i, v ->
                            val h = size.height * (v / ceiling) * reveal.value
                            if (h > 0f) {
                                drawRect(
                                    if (i == hot) {
                                        ObservatoryColors.Ember
                                    } else {
                                        ObservatoryColors.Muted
                                    },
                                    Offset(slot * (i + 0.5f) - w / 2, size.height - h),
                                    Size(w, h),
                                )
                            }
                        }
                    },
            )
        }
        // 下の見出しは軸の幅＋間（4dp）だけ右から始める。**決め打ちの 38dp で寄せていた**ので、
        // 軸の字が長いと見出しが棒とずれた。
        Row(modifier = Modifier.fillMaxWidth().padding(start = axisWidth + 4.dp, top = 3.dp)) {
            labels.forEachIndexed { i, text ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ObservatoryText(
                        text = text,
                        color = if (i == hot) ObservatoryColors.Muted else ObservatoryColors.Faint,
                        size = ObservatoryType.Label,
                        mono = true,
                    )
                }
            }
        }
    }
}


/**
 * 濃淡の格子。**暦の姿をとらない。**
 *
 * ⚠️ 最初は月の格子（`MonthTile`）を借りて描いた。同じ面に暦が二つ並び、
 * 「カレンダーが二種類ある」ことになった（本人の指摘・2026/9/1）。
 *
 * 暦は「その日に何があるか」を引く道具で、こちらは「量の癖」を見る図である。
 * **問いが違うなら形も違うべき**なので、
 * 週を列・曜日を行に置いた別の格子にする。月の見出しも日付の数字も持たない。
 *
 * 量は**格点の大きさ**で表す。この体系は面を塗らないので、
 * 濃淡の塗り分けを許すと燠火の 12% が意味を失う。
 */
@Composable
fun HeatGridFigure(
    values: List<Pair<java.time.LocalDate, Long>>,
    modifier: Modifier = Modifier,
) {
    if (values.isEmpty()) return
    // **記録の無い先頭は切り落とす。**
    // 端末は日ごとの桶を二週ほどしか保たないので、二十八日を求めると
    // 前半が空のまま並び、「使っていない週」に見えてしまう。
    // 記録が無いことと、使っていないことは違う。
    val trimmed = values.dropWhile { it.second <= 0L }
    if (trimmed.isEmpty()) return
    val top = trimmed.maxOf { it.second }.coerceAtLeast(1L)
    val today = trimmed.last().first
    // 列は週。左端の列が最も古い週になるよう、最初の日を日曜まで戻す。
    val first = trimmed.first().first
    val lead = first.dayOfWeek.value % 7
    val cells = List(lead) { null } + trimmed.map { it }
    val weeks = cells.chunked(7)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth()) {
            // 行の見出し（日・水・土だけ）。全部書くと格子より見出しが目立つ。
            Column(modifier = Modifier.width(20.dp)) {
                listOf("日", "", "", "水", "", "", "土").forEach { name ->
                    Box(modifier = Modifier.height(18.dp), contentAlignment = Alignment.CenterStart) {
                        if (name.isNotBlank()) {
                            ObservatoryText(
                                text = name,
                                color = ObservatoryColors.Faint,
                                size = ObservatoryType.Label,
                                mono = true,
                            )
                        }
                    }
                }
            }
            weeks.forEach { week ->
                Column(modifier = Modifier.weight(1f)) {
                    (0 until 7).forEach { row ->
                        val cell = week.getOrNull(row)
                        Box(
                            modifier = Modifier.height(18.dp).fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (cell != null) {
                                val share = (cell.second.toFloat() / top).coerceIn(0f, 1f)
                                val side = 3.dp + (11.dp - 3.dp) * share
                                Box(
                                    modifier = Modifier
                                        .size(side)
                                        .background(
                                            if (cell.first == today) {
                                                ObservatoryColors.Ember
                                            } else {
                                                ObservatoryColors.Muted
                                            },
                                        ),
                                )
                            }
                        }
                    }
                }
            }
        }
        // 目盛りの代わりに、大きさの意味を一行で置く。
        // **図の読み方を図の外に置かない。**
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ObservatoryText(
                text = "少",
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
            )
            listOf(3.dp, 6.dp, 8.dp, 11.dp).forEach { side ->
                Spacer(modifier = Modifier.width(5.dp))
                Box(
                    modifier = Modifier.size(side).background(ObservatoryColors.Muted),
                )
            }
            Spacer(modifier = Modifier.width(5.dp))
            ObservatoryText(
                text = "多",
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
            )
        }
    }
}

/** 時刻を `HH:mm` で。図の脇に添える用。 */
private fun clockLabel(at: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.JAPAN).format(java.util.Date(at))

/**
 * 割合の目盛り。罫を一本引き、四分の一ごとに格点を置き、今の位置に燠火の節点。
 *
 * 音楽の再生位置と同じ形である——**同じ問い（どこまで来ているか）には同じ形**を当てる。
 * 面で塗らないのは、この図の語彙すべてに共通する約束による。
 */
@Composable
fun ProportionScale(fraction: Float, modifier: Modifier = Modifier) {
    val rule = ObservatoryColors.Line
    val tick = ObservatoryColors.LineStrong
    val hand = ObservatoryColors.Ember
    val at = fraction.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(10.dp)
            .drawBehind {
                val midY = size.height / 2
                drawRect(rule, Offset(0f, midY), Size(size.width, 1.dp.toPx()))
                listOf(0f, 0.25f, 0.5f, 0.75f, 1f).forEach { f ->
                    val w = 1.dp.toPx()
                    val h = 5.dp.toPx()
                    drawRect(tick, Offset((size.width - w) * f, midY - h / 2), Size(w, h))
                }
                val s = 5.dp.toPx()
                drawRect(hand, Offset((size.width - s) * at, midY - s / 2), Size(s, s))
            },
    )
}

/**
 * 目盛りの刻み幅を決める。
 *
 * 統計検定の練習サイトで使っている考え方をそのまま移した。
 * **1・2・2.5・5・10 の倍数だけを刻みに採る**——半端な刻み(3.7 など)は
 * 目盛りとして読めず、値を軸から読み取れなくなる。
 */
internal fun niceTicks(low: Float, high: Float, count: Int = 4): List<Float> {
    if (!(high > low)) return listOf(low)
    val raw = (high - low) / count
    val power = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
    val step = listOf(1f, 2f, 2.5f, 5f, 10f).first { it * power >= raw * 0.999f } * power
    val out = ArrayList<Float>()
    var v = ceil(low / step) * step
    while (v <= high + step * 1e-6f) {
        out += v
        v += step
    }
    // ⚠️ **いちばん高い棒より上に、目盛りが一本要る。**
    //
    // ここで止めると最上段の目盛りは必ず最大値より下に来るので、
    // いちばん高い棒は目盛りを突き抜け、**その棒がいくつなのか読めない**
    // （外部評価・2026/9/2「上限値が書かれていないため高さの比較精度が低い」）。
    //
    // 一段だけ上へ伸ばす。棒が天井に触れなくなる代わりに、全部の棒が読める。
    if (out.isEmpty() || out.last() < high - step * 1e-6f) out += v
    return if (out.size > 1) out else listOf(low, high)
}

/**
 * 表示の単位で刻む目盛り。値そのものの単位で返す。
 *
 * ## ⚠️ 刻みの「きりの良さ」は、表示の単位で決めなければならない（2026/9/25）
 *
 * 使用時間はミリ秒で持っている。ミリ秒のまま [niceTicks] に掛けると
 * 「2,500,000 ミリ秒」刻みになり、分に直すと **0・41・83・125 分**という
 * 読めない目盛りが出ていた（五数要約は 0・166・333・500 分）。
 * バイトも同じで、10 進の刻みを 1024 進で書くと「238.4MB」になる。
 *
 * 表示の単位へ割ってから刻み、刻んだ値を元の単位へ戻す。
 */
internal fun niceTicksIn(max: Float, unit: Float = 1f, count: Int = 3): List<Float> =
    niceTicks(0f, (max / unit).coerceAtLeast(1f), count).map { it * unit }

/** 一分（ミリ秒）。使用時間・聴取時間の目盛りに使う。 */
internal const val TICK_MINUTE = 60_000f

/** バイト数を刻む単位。最大値に合わせて KB・MB・GB のいずれかで刻む。 */
internal fun byteTickUnit(max: Float): Float {
    var unit = 1f
    while (max / unit >= 1024f && unit < 1024f * 1024f * 1024f) unit *= 1024f
    return unit
}

/**
 * 目盛りの数字。桁数は**軸ごとに決める**。
 *
 * ⚠️ **桁の大きい値をそのまま書くと欄から溢れる。**
 * 気圧（1013.2 hPa）を小数まで書いたら、数字が途中で切れた
 * （本人の指摘・2026/9/1）。
 *
 * ⚠️ **だが一律に丸めると、目盛りが嘘をつく。**
 * 1013.4 と 1013.8 を整数に丸めたら、
 * **三本の目盛りのうち二本が「1013」になった**（実機・2026/9/1）。
 * 違う高さに同じ数字が並ぶのは、読み取れないどころか誤りである。
 *
 * だから桁数は固定しない。**全部が別の数字になる最も少ない桁数**を採る。
 * 値の幅が広ければ整数で足り、狭ければ自然に小数が現れる。
 */
private fun formatTick(v: Float, decimals: Int = 0): String =
    if (decimals <= 0) Math.round(v).toString() else "%.${decimals}f".format(v)

/** 目盛りが互いに別の数字になる、最も少ない桁数。 */
private fun tickDecimals(ticks: List<Float>): Int {
    for (d in 0..2) {
        val labels = ticks.map { formatTick(it, d) }
        if (labels.size == labels.distinct().size) return d
    }
    return 2
}


/**
 * 固定の軸。**値がどうであれ、目盛りは動かさない。**
 *
 * @param low 下端。
 * @param high 上端。
 * @param step 刻み。(high - low) を割り切る値にする。
 */
data class FixedAxis(val low: Float, val high: Float, val step: Float) {
    val ticks: List<Float>
        get() = generateSequence(low) { it + step }
            .takeWhile { it <= high + step / 1000f }
            .toList()

    /** 下端から測った割合（0〜1）。軸の外の値は端に留める。 */
    fun fraction(v: Float): Float = ((v - low) / (high - low)).coerceIn(0f, 1f)

    companion object {
        /**
         * 降水確率・湿度。**百分率は 0〜100 以外の軸を持ってはならない。**
         *
         * > 「降水確率と気温の分布ですが、数値が固定でないので見た時にだまされます。
         * >  降水確率は100％固定で」（本人・2026/9/25）
         *
         * 最大値に合わせて伸縮させていたので、30% の山が図の天井まで届き、
         * **一目では 90% と見分けがつかなかった。**
         */
        val PERCENT = FixedAxis(0f, 100f, 20f)

        /**
         * 気温。**雨温図の気温軸にならい、10℃刻みで固定する。**
         *
         * > 「気温は日本の雨温図で採用されている温度軸とかにしてください」（本人・2026/9/25）
         *
         * 教科書の雨温図は月平均を描くので −20〜30℃ が多いが、ここは**一時間ごとの実測**を
         * 描く。大阪の夏は 38℃ を超えるので上端を 40℃ まで伸ばし、下端は −10℃ とした。
         * 刻みの数（五つ）を [PERCENT] と揃えてあるので、**二つの軸の目盛り線が重なる**
         * ——雨温図が左右の軸を一組の罫で読ませているのと同じ作りである。
         */
        val TEMPERATURE = FixedAxis(-10f, 40f, 10f)

        /**
         * 気圧。平地で普段とる幅（おおよそ 1000〜1025hPa）を真ん中に置く。
         * 台風の中心のように 980 を割る値は下端に留まる。刻みの数は [PERCENT] と揃える。
         */
        val PRESSURE = FixedAxis(980f, 1030f, 10f)
    }
}

/**
 * 時系列の図。**棒と線を同じ時間軸に重ねる。**
 *
 * 統計検定の練習サイトの意匠を借りた。あちらと同じ約束を守る。
 *
 * - **左に値軸を置き、薄い目盛り線を横へ引く。** 目盛りの無い棒は長さの意味が読めない。
 * - **軸は L 字**（左と下）。下だけの罫では、縦の値がどこから測られているか分からない。
 *
 * ## ⚠️ 軸は固定する（2026/9/25）
 *
 * 以前は棒を最大値に、線を最小〜最大に合わせて伸縮させていた。
 * **図の形がいつも同じになり、値の大小が形から読めない**——30% の雨も 90% の雨も
 * 天井まで届き、2℃ しか動かない日も 10℃ 動く日も同じ振れ幅に見えた。
 * 軸を固定すれば、形そのものが量を語る。
 *
 * 棒と線の軸は刻みの数を揃え（[FixedAxis] の注記）、目盛り線を一組にする。
 * この体系に合わせて変えたのは色だけである。棒は塗らず細い罫にし、
 * 燠火は**今の一点**にだけ使う。
 *
 * @param bars 左の軸で読む値（降水確率など）。
 * @param line 右の軸で読む別の系列（気温など）。
 */
@Composable
fun SeriesFigure(
    labels: List<String>,
    bars: List<Float>,
    barUnit: String,
    barAxis: FixedAxis,
    modifier: Modifier = Modifier,
    line: List<Float> = emptyList(),
    lineUnit: String = "",
    lineAxis: FixedAxis? = null,
    height: Dp = 112.dp,
) {
    if (labels.size < 2 || bars.size != labels.size) return
    val hasLine = lineAxis != null && line.size == bars.size

    val axis = ObservatoryColors.LineStrong
    val grid = ObservatoryColors.Grid
    val barInk = ObservatoryColors.Muted
    val lineInk = ObservatoryColors.EmberText

    val barTexts = barAxis.ticks.map { formatTick(it) }
    val lineTexts = lineAxis?.ticks?.map { formatTick(it) }.orEmpty()
    val barAxisWidth = valueAxisWidth(barTexts)
    val reveal = rememberReveal()
    val lineAxisWidth = if (hasLine) valueAxisWidth(lineTexts) else 0.dp

    Column(modifier = modifier.fillMaxWidth()) {
        // 何の図かを名乗る。単位を書かない図は、読む側に前提を要求する。
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            ObservatoryText(text = barUnit, color = ObservatoryColors.Faint, size = ObservatoryType.Label, mono = true)
            if (hasLine) {
                Spacer(modifier = Modifier.weight(1f))
                ObservatoryText(
                    text = lineUnit,
                    color = ObservatoryColors.EmberText,
                    size = ObservatoryType.Label,
                    mono = true,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            // 左の値軸。**線と同じ式で置く**（等間隔に並べると線とずれる）。
            ValueAxis(
                ticks = barAxis.ticks.mapIndexed { i, v -> (1f - barAxis.fraction(v)) to barTexts[i] },
                height = height,
            )
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .drawBehind {
                        val hair = 1.dp.toPx()
                        // 目盛り線。**薄く、しかし必ず引く。**
                        barAxis.ticks.forEach { v ->
                            val y = size.height * (1f - barAxis.fraction(v))
                            drawRect(grid, Offset(0f, y.coerceAtMost(size.height - hair)), Size(size.width, hair))
                        }
                        // L 字の軸。
                        drawRect(axis, Offset(0f, 0f), Size(hair, size.height))
                        drawRect(axis, Offset(0f, size.height - hair), Size(size.width, hair))

                        // 棒。塗らずに細い罫で立てる——本数ぶんの色の塊を作らない。
                        val slot = size.width / bars.size
                        val w = (slot * 0.34f).coerceAtMost(5.dp.toPx())
                        bars.forEachIndexed { i, v ->
                            val h = size.height * barAxis.fraction(v) * reveal.value
                            if (h > 0f) {
                                drawRect(
                                    barInk,
                                    Offset(slot * (i + 0.5f) - w / 2, size.height - h),
                                    Size(w, h),
                                )
                            }
                        }
                        // 線。右の軸で読む。**軸の外の値は端に留める**（[FixedAxis.fraction]）。
                        if (hasLine) {
                            fun y(v: Float) = size.height * (1f - lineAxis.fraction(v))
                            // 線は左から引かれる。引き終わっていない区間は、途中まで描く。
                            val drawn = (line.size - 1) * reveal.value
                            for (i in 0 until line.size - 1) {
                                val part = (drawn - i).coerceIn(0f, 1f)
                                if (part <= 0f) break
                                val a = Offset(slot * (i + 0.5f), y(line[i]))
                                val b = Offset(slot * (i + 1.5f), y(line[i + 1]))
                                drawLine(
                                    color = lineInk,
                                    start = a,
                                    end = a + (b - a) * part,
                                    strokeWidth = 1.5.dp.toPx(),
                                )
                            }
                            // **今の一点だけ燠火の格点。** ほかは線のまま。
                            val s = 4.dp.toPx()
                            drawRect(
                                ObservatoryColors.Ember,
                                Offset(slot * 0.5f - s / 2, y(line.first()) - s / 2),
                                Size(s, s),
                            )
                        }
                    },
            )
            // 線の側にも目盛りを立てる。
            //
            // ⚠️ **線に軸が無いと、形は見えても値が読めない。**
            // 「気温が上がっている」までは分かるが、何度から何度へ上がったのかが
            // 分からない（本人の指摘・2026/9/1）。左の軸は棒のものなので、
            // 線には**右に別の軸**を立てる。
            if (hasLine) {
                Spacer(modifier = Modifier.width(4.dp))
                ValueAxis(
                    ticks = lineAxis.ticks.mapIndexed { i, v ->
                        (1f - lineAxis.fraction(v)) to lineTexts[i]
                    },
                    height = height,
                    color = ObservatoryColors.EmberText,
                    end = false,
                )
            }
        }
        // 下の見出し。全部書くと潰れるので、三つおきに置く。
        //
        // ⚠️ 左右の余白は**軸の実際の幅**から取る。決め打ち（左 30dp・右 34dp）で寄せていたので、
        // 軸の字の幅が変わると見出しが棒とずれた（2026/9/25 の点検）。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = barAxisWidth + 4.dp,
                    end = if (hasLine) lineAxisWidth + 4.dp else 0.dp,
                )
                .padding(top = 4.dp),
        ) {
            labels.forEachIndexed { i, text ->
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (i % 3 == 0) {
                        ObservatoryText(
                            text = text,
                            color = ObservatoryColors.Faint,
                            size = ObservatoryType.Label,
                            mono = true,
                        )
                    }
                }
            }
        }
    }
}

// ---- 目盛りの字の置き方（2026/9/25） -----------------------------------------
//
// ⚠️ **字の寸法を見積りで決め打ちにしない。**
// 軸の幅は「10sp の一字＝6.6dp」、字の中心は「左へ 8dp」「上へ 6dp」と、
// 図ごとに別々の見積りで置いていた。漢字の混じる目盛り（「1500分」）は幅が足りずに
// 切れ、字を大きくすると全部の位置がずれる。**描く字と同じ様式で測れば、外れようがない。**

/** 測った字の寸法。 */
internal class Measured(val widths: List<Dp>, val height: Dp) {
    val widest: Dp get() = widths.maxOrNull() ?: 0.dp
    val half: Dp get() = height / 2
}

/** 字を測る。[observatoryStyle] と同じ様式で測るので、描いた字と寸法が一致する。 */
@Composable
internal fun measureLabels(
    texts: List<String>,
    size: TextUnit = ObservatoryType.Label,
    mono: Boolean = true,
): Measured {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(texts, size, mono, density.density, density.fontScale) {
        val style = observatoryStyle(size, mono)
        val sizes = texts.ifEmpty { listOf("0") }.map {
            measurer.measure(it, style, maxLines = 1, softWrap = false).size
        }
        with(density) {
            Measured(
                widths = sizes.map { it.width.toDp() },
                height = sizes.maxOf { it.height }.toDp(),
            )
        }
    }
}

/**
 * 縦の値軸。目盛りの字を**線と同じ高さの中心**へ置く。
 *
 * @param ticks 上端からの割合（0 が上・1 が下）と字の組。
 * @param end 右寄せにするか（図の左に立てる軸）。左寄せは図の右に立てる軸。
 */
@Composable
internal fun ValueAxis(
    ticks: List<Pair<Float, String>>,
    height: Dp,
    color: Color = ObservatoryColors.Faint,
    end: Boolean = true,
) {
    val measured = measureLabels(ticks.map { it.second })
    Box(modifier = Modifier.width(measured.widest + 1.dp).height(height)) {
        ticks.forEach { (fromTop, text) ->
            ObservatoryText(
                text = text,
                color = color,
                size = ObservatoryType.Label,
                mono = true,
                modifier = Modifier
                    .align(if (end) Alignment.TopEnd else Alignment.TopStart)
                    .offset(y = height * fromTop - measured.half),
            )
        }
    }
}

/** 縦の値軸が取る幅。下の見出しを軸の分だけ右へ寄せるときに使う。 */
@Composable
internal fun valueAxisWidth(texts: List<String>): Dp = measureLabels(texts).widest + 1.dp

/**
 * 横の軸の見出し。字の**中心**を位置に合わせ、両端では図の外へ出ないよう内へ寄せる。
 *
 * @param marks 左端からの割合（0〜1）と字の組。
 */
@Composable
internal fun AxisLabels(
    marks: List<Pair<Float, String>>,
    modifier: Modifier = Modifier,
    color: Color = ObservatoryColors.Faint,
    size: TextUnit = ObservatoryType.Label,
) {
    val measured = measureLabels(marks.map { it.second }, size)
    BoxWithConstraints(modifier = modifier.fillMaxWidth().height(measured.height)) {
        val span = maxWidth
        marks.forEachIndexed { i, (at, text) ->
            val w = measured.widths[i]
            val x = (span * at - w / 2).coerceIn(0.dp, (span - w).coerceAtLeast(0.dp))
            ObservatoryText(
                text = text,
                color = color,
                size = size,
                mono = true,
                modifier = Modifier.offset(x = x),
            )
        }
    }
}
