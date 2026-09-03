package com.yuma.observatorylauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuma.observatorylauncher.data.Glance
import com.yuma.observatorylauncher.data.MonthView
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens
import java.time.LocalDate
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.getValue
import java.time.YearMonth
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import android.content.Intent
import com.yuma.observatorylauncher.data.Habit
import com.yuma.observatorylauncher.data.AppRepository
import com.yuma.observatorylauncher.data.Holidays

/**
 * 月の格子。**借りたウィジェットではなく、こちらで描く。**
 *
 * 提供元アプリのウィジェットは向こうの意匠で描かれ、こちらからは色一つ渡せない
 * （[com.yuma.observatorylauncher.ui.theme.WidgetPalette] に実測を記した）。
 * 面ごと 02 に揃えたければ、自分で描くしかない。
 *
 * ## 描き方の約束
 *
 * - **枡は塗らない。** 予定の有無は枡の下に置いた小さな刻みで示す。
 *   塗ると 42 枡のうち何枚もが面になり、地と字の 88:12 が崩れる。
 * - **罫は週の切れ目だけ。** 縦の罫は引かない——列は曜日の見出しが決めている。
 *   42 枡すべてを罫で囲むと、格子ではなく表になる。
 * - **燠火は今日ひとつだけ。** これがこの面で唯一の有彩である。
 *   置く場所は**数字のすぐ右**——枡の隅に置くと、隣の枡の数字に付いて見えた。
 * - 四隅の格点は**やめた**。罫が既に構造を示しており、格点は意味を足さずに
 *   汚れとして読まれる。§D-2 の語彙でも、使う場所は選ぶ。
 */
/**
 * 格子が何を映すか。**相（格子）は同じで、源だけが違う。**
 * 設計の「源 × 相」が最初に形になったところである。
 */
internal sealed interface MonthSource {
    /** 端末の暦を借りて映す。押すとその日の暦を開く。 */
    data object Calendar : MonthSource

    /** 利用者が自分で付けた印を映す。押すと付け外しする。 */
    data class Habit(val record: String, val onToggle: (LocalDate) -> Unit) : MonthSource

}

@Composable
internal fun MonthTile(
    modifier: Modifier = Modifier,
    source: MonthSource = MonthSource.Calendar,
    refreshKey: Int = 0,
) {
    val context = LocalContext.current
    val today = LocalDate.now()
    // 送った月数。今月が 0。**画面を離れても覚えない**——次に見るときは
    // 今月から始まるのが自然で、先月を開いたまま忘れる方が困る。
    var offset by remember { mutableIntStateOf(0) }
    val anchor = remember(today.month, offset) {
        YearMonth.from(today).plusMonths(offset.toLong()).atDay(1)
    }
    // **源そのものを鍵にしてはならない。** `Habit` は押したときの手続きを抱えており、
    // それは組み直しのたびに別物になる。鍵にすると毎回組み直しが走り、
    // 描く→鍵が変わる→また描く、という循環になる。
    // 中身が変わったかどうかだけを鍵にする。
    val sourceKey = when (source) {
        is MonthSource.Habit -> "habit:" + source.record
        MonthSource.Calendar -> "calendar"
    }
    val view by produceState(
        initialValue = MonthView.of(anchor),
        anchor,
        sourceKey,
        refreshKey,
    ) {
        value = when (source) {
            // 習慣は宣言に書いてある。問い合わせは要らないので、そのまま組む。
            is MonthSource.Habit ->
                MonthView.of(anchor, Habit.marksFor(source.record, YearMonth.from(anchor)))


            MonthSource.Calendar -> {
                val skeleton = MonthView.of(anchor)
                MonthView.of(
                    anchor,
                    Glance.monthMarks(
                        context,
                        skeleton.days.first().date,
                        skeleton.days.last().date,
                    ),
                )
            }
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        // 見出し。月の名は数字だけで足りる——年は時計の下に既に在る。
        // ただし年を跨いだときは、どの年か分からないと困るので添える。
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ObservatoryText(
                text = if (view.month.year == today.year) {
                    "${view.month.monthValue}月"
                } else {
                    "${view.month.year}年${view.month.monthValue}月"
                },
                color = ObservatoryColors.Paper,
                size = ObservatoryType.Body,
            )
            if (source is MonthSource.Habit) {
                Spacer(modifier = Modifier.width(10.dp))
                // 続いた日数。習慣で見たいのは月の全体より、まずここである。
                ObservatoryText(
                    text = "連続 ${Habit.streak(source.record, today)} 日",
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Note,
                )
            }
            if (offset != 0) {
                Spacer(modifier = Modifier.width(10.dp))
                // 今月へ戻る道。**送ったなら戻れること**を面に出しておく。
                Step(label = "今月", onClick = { offset = 0 })
            }
            Spacer(modifier = Modifier.weight(1f))
            Step(label = "◂", onClick = { offset -= 1 })
            Spacer(modifier = Modifier.width(4.dp))
            Step(label = "▸", onClick = { offset += 1 })
        }

        Row(modifier = Modifier.fillMaxWidth()) {
            MonthView.HEADINGS.forEach { heading ->
                ObservatoryText(
                    text = heading,
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Label,
                    mono = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 下に余白を置く。置かないと最終週の刻みが弦材(§N-7)に接して、
        // 刻みが弦材の一部に見える。
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                view.weeks().forEachIndexed { at: Int, week: List<MonthView.Day> ->
                    // 週の切れ目にだけ罫を引く。最初の週の上には引かない
                    // ——曜日の見出しとの間が詰まって、見出しが枡に見える。
                    if (at > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ObservatoryDimens.Hairline)
                                .background(ObservatoryColors.Line),
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            DayCell(
                                day = day,
                                isToday = day.date == today,
                                onOpen = {
                                    when (source) {
                                        is MonthSource.Habit ->
                                            // 前後の月の埋め草には付けない。
                                            // どの月に付いたのか分からなくなる。
                                            if (day.inMonth) source.onToggle(day.date)

                                        MonthSource.Calendar ->
                                            openCalendarAt(context, day.date)
                                    }
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
        }
    }
}

/**
 * 送りと戻りの押し所。**触れる大きさは HIG の最小(44dp)を確保する**——
 * 印そのものは小さくてよいが、押せる範囲まで小さくしてはならない。
 */
@Composable
private fun Step(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .heightIn(min = ObservatoryDimens.Row)
            .widthIn(min = ObservatoryDimens.Row)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ObservatoryText(text = label, color = ObservatoryColors.Muted, size = 13.sp)
    }
}

/**
 * その日の暦を開く。
 *
 * 開けなかったときは暦のアプリそのものを開く。**押して何も起きないのが一番困る**
 * ので、二段構えにしておく。
 */
private fun openCalendarAt(context: android.content.Context, date: LocalDate) {
    // 実体は [AppRepository.openCalendar] に一本化した。
    // 同じ道を二箇所に書くと、片方だけが古いままになる。
    AppRepository.openCalendar(
        context,
        date.atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )
}

@Composable
private fun DayCell(
    day: MonthView.Day,
    isToday: Boolean,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        // 枡は横長にする。正方形に近いと数字と刻みの間が空き、
        // **刻みがどの週のものか**が読めなくなる。
        modifier = modifier
            .aspectRatio(1.55f)
            .clickable(interactionSource = interaction, indication = null, onClick = onOpen)
            .padding(vertical = 3.dp),
    ) {
        // 今日は**形**で示す。
        //
        // 以前はここに燠火の点を置いていた。**それが誤りだった**——
        // 進行中の予定も燠火なので、枡に既にオレンジが在り、
        // 十七時に予定が始まっても「色が付いた」ことに気付けない。
        // 燠火は「今まさに動いている」の一語だけを担うべきで、
        // 「今日」は色を使わず、数字の下の罫（§N-7 の弦材）で示す。
        Column(
            // 幅を数字ぴったりにする。`fillMaxWidth` は枡いっぱいまで伸びるので、
            // 罫が数字より長くなり、隣の枡の数字に掛かって見える。
            modifier = Modifier.align(Alignment.TopStart).width(IntrinsicSize.Min),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val isRest = day.date.dayOfWeek == java.time.DayOfWeek.SUNDAY ||
                Holidays.isHoliday(day.date)
            ObservatoryText(
                text = day.date.dayOfMonth.toString(),
                // 前後の月からの埋め草は薄く。消してしまうと週の並びが読めなくなる。
                color = when {
                    // 前後の月の日。**在ることは示すが読ませない**——ここだけが Ghost の役。
                    !day.inMonth -> ObservatoryColors.Ghost
                    isToday -> ObservatoryColors.Paper
                    // 休みの日は色で分ける。**暦という道具の側の約束**なので、
                    // この体系の二色主義より、読める暦であることを採る。
                    // 祝日は日曜と同じ扱い——法の上でも「休日」で一括りである。
                    isRest -> ObservatoryColors.Sunday
                    day.date.dayOfWeek == java.time.DayOfWeek.SATURDAY ->
                        ObservatoryColors.Saturday
                    else -> ObservatoryColors.Muted
                },
                size = ObservatoryType.Note,
                mono = true,
            )
            if (isToday) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(ObservatoryColors.Paper),
                )
            }
        }
        if (day.count > 0 && day.inMonth) {
            // 予定は刻みで示す。三つで頭打ちにする——
            // 四つ以上を並べても「多い」以上のことは伝わらず、枡が埋まるだけである。
            //
            // **色で顔を分ける。** 済んだものは沈め、今まさに進んでいるものだけを
            // 燠火にする。燠火が「今」を意味する、という約束はここでも同じで、
            // 今日の印と同じ色になるのは偶然ではない。
            Row(
                modifier = Modifier.align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                day.marks.ordered(3).forEach { state ->
                    Box(
                        modifier = Modifier
                            .size(
                                when (state) {
                                    MonthView.State.RUNNING -> 4.dp
                                    MonthView.State.MARKED -> 6.dp
                                    else -> 3.dp
                                }
                            )
                            .background(
                                when (state) {
                                    // 済んだものは在ることだけ示す。読ませはしない。
                                    MonthView.State.DONE -> ObservatoryColors.Faint
                                    // 今まさに。**この面で燠火はここだけ**である。
                                    MonthView.State.RUNNING -> ObservatoryColors.Ember
                                    // これから。今までどおりの白い刻み。
                                    MonthView.State.AHEAD -> ObservatoryColors.Muted
                                    // 自分で付けた印。**やったという事実**なので、
                                    // 予定の刻みより一段はっきり出す。
                                    MonthView.State.MARKED -> ObservatoryColors.Paper
                                }
                            ),
                    )
                }
            }
        }
    }
}
