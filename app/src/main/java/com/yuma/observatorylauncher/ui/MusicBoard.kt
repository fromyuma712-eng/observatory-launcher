package com.yuma.observatorylauncher.ui

import android.content.pm.ApplicationInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.data.AppRepository
import com.yuma.observatorylauncher.data.Listening
import com.yuma.observatorylauncher.data.Music
import com.yuma.observatorylauncher.data.Sound
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.yuma.observatorylauncher.data.Artwork
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * 音の面。
 *
 * 三つの面はそれぞれ役割が違う。
 *
 * | 面 | 役割 |
 * | --- | --- |
 * | 計器盤（左） | **選ばない。** 端末から取れるものを全部並べる。長く、巻いて読む |
 * | ホーム（中） | **選ぶ。** 日々目に入るものだけ。**ここだけが入口を持つ** |
 * | 音（右） | **一枚で完結する。** 巻かない。時計も検索も置かない |
 *
 * ## ⚠️ この面だけは「巻かない」ことが設計条件である
 *
 * > 「音楽の方だけは、一枚で完成されたものが綺麗だなと感じる」（本人・2026/9/1）
 *
 * 巻けるようにすると、中身をいくらでも足せてしまう。
 * **入る分しか置かない**という制約が、この面の質を保つ。
 * 足したいものが出てきたら、何かを削るか、置き方を変える。
 *
 * だから高さは重み（`weight`）で配り、中身は画面に合わせて伸縮させる。
 * 固定の高さを積み上げると、機種が変わった瞬間にはみ出す。
 *
 * ## 規律を外している
 *
 * ホームと計器盤は「面を塗らない・燠火は 12%・絵柄を置かない」で通すが、
 * この面はその外に置くことを利用者が決めた。
 * **外すのは規律であって作法ではない**——等幅の数字・秒二桁・押し所の大きさは持ち込む。
 */
internal object MusicBoard {

    /** 頁の番号。利用者の頁の**右**に置く。左は計器盤。 */
    fun page(pageCount: Int): Int = pageCount
}

/**
 * 音の面の中身。**一枚で完結させる。**
 *
 * 鳴っているときと鳴っていないときで、置くものを入れ替える。
 * どちらも巻かずに収まるよう、高さは重みで配る。
 */
@Composable
internal fun MusicBoardPage(
    display: LayoutConfig.Display,
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // **生きた値も絵柄も、この面で一度だけ作る。**
    // 下の板が別々に取りに行くと、同じものを二度読み、片方だけが古くなる。
    val live = rememberNowPlaying(refreshKey)
    val loaded by produceState<Artwork.Loaded?>(null, refreshKey, live.revision) {
        value = withContext(Dispatchers.IO) { Artwork.load(context) }
    }
    val route by produceState(initialValue = "", refreshKey) {
        value = withContext(Dispatchers.Default) { Sound.route(context) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(musicGround(loaded?.stripe))
            .padding(horizontal = ObservatoryDimens.Gutter),
    ) {
        // 出口は一行だけ、面の頭に置く。**どこから鳴るかは、押す前に知りたい。**
        if (route.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(ObservatoryDimens.Anchor)
                        .background(ObservatoryColors.LineStrong),
                )
                Spacer(modifier = Modifier.width(8.dp))
                ObservatoryText(
                    text = route,
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Note,
                    mono = true,
                )
            }
        }

        if (live.current != null) {
            NowPlayingPanel(
                live = live,
                loaded = loaded,
                refreshKey = refreshKey,
                modifier = Modifier.weight(1f).padding(top = 12.dp),
            )
        } else {
            SilentBoard(
                display = display,
                refreshKey = refreshKey,
                modifier = Modifier.weight(1f).padding(top = 12.dp),
            )
        }
    }
}

/**
 * 面の地。**今かかっている曲の色を、うすく敷く。**
 *
 * > 「Apple Music が再生中の曲に合わせて背景を変えるようなもの。
 * >  あれはアニメーションがついているが、静止でよい。
 * >  あまり派手に変わるとあれなので、程よく」（本人・2026/9/2）
 *
 * ## ⚠️ 絵の色をそのまま敷くと、字が読めなくなる
 *
 * 白っぽい絵柄をわずか 8% 混ぜただけで、燠火の文字が 5.21:1 から **3.80:1** へ落ちた
 * （小さな字の下限は 4.5:1）。**色を薄めるのではなく、明るさだけを落とす**
 * （[Artwork.sink]）。色味は残るので、曲ごとに面の表情は変わる。
 *
 * この処理を通すと 55% 混ぜても紙は 10:1、燠火の文字も 4.5:1 を保つ（実測）。
 * ここでは上端 45%、中ほど 18%、下端は地のまま——**上から下へ抜ける**ようにして、
 * 面が一色で塗られたようには見せない。
 */
@Composable
private fun musicGround(stripe: IntArray?): Brush {
    val ground = ObservatoryColors.Ground.toArgb()
    // ⚠️ **`remember` を条件の中に置かない。**
    // 早く返してしまうと、絵が来た瞬間に呼び出しの並びが変わり、
    // 覚えている値の対応がずれる（Compose の約束）。
    // 絵が無いときも同じ数だけ呼び、中で地のままの帯を作る。
    return remember(stripe) {
        if (stripe == null || stripe.isEmpty()) {
            return@remember Brush.verticalGradient(
                listOf(ObservatoryColors.Ground, ObservatoryColors.Ground),
            )
        }
        // 上は**その絵で最も鮮やかな色**、中ほどは全体の平均。
        // 二色にすると、帯の色の移り変わりが地にもうっすら残る。
        val vivid = stripe.maxByOrNull { c ->
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            maxOf(r, g, b) - minOf(r, g, b)
        } ?: ground
        val mean = (0xFF shl 24) or
            ((stripe.sumOf { (it shr 16) and 0xFF } / stripe.size) shl 16) or
            ((stripe.sumOf { (it shr 8) and 0xFF } / stripe.size) shl 8) or
            (stripe.sumOf { it and 0xFF } / stripe.size)
        // ⚠️ 「この曲の色」の帯をやめた分、**地に受け持たせる**（本人・2026/9/2）。
        // 絵柄は幅いっぱいに置かれるので、色が見えるのは主に**絵の下**である。
        // そこを薄くしていては、色が変わったことに気付けない。
        //
        // ⚠️ 一度 0.42/0.34 まで濃くしたが、**面全体が青く染まって派手になった**
        // （実機・2026/9/2。本人の求めは「程よく」である）。
        // 0.55 まで混ぜても字は読めるが、**読めることと程よいことは別**である。
        Brush.verticalGradient(
            0f to Color(Artwork.blend(ground, Artwork.sink(vivid, ground), 0.34f)),
            0.45f to Color(Artwork.blend(ground, Artwork.sink(vivid, ground), 0.26f)),
            0.80f to Color(Artwork.blend(ground, Artwork.sink(mean, ground), 0.10f)),
            1f to ObservatoryColors.Ground,
        )
    }
}

/**
 * 鳴っていないときの面。
 *
 * **空にしない。** 鳴っていない時間の方が長いので、そちらが本体である。
 *
 * ## ⚠️ 数字を並べるのをやめた（2026/9/2）
 *
 * 以前は「今日 22分／曲 5／最も長い演者」と数字を横に並べ、
 * その下に今日の一曲と最近の一覧を置いていた。
 *
 * > 「音楽がかかっていないときは、数値の結果をグラフを使用して表示して欲しい」（本人）
 *
 * 数字は**その一点しか答えない**。「22分」は多いのか少ないのか、
 * いつ聴いたのか、誰を聴いたのかに答えない。
 * 同じ記録を図にすれば、**問いを三つ増やさずに三つ答えられる**。
 *
 * 置くのは三つ——七日の推移・一日の形・よく聴く演者。
 * 一枚に収める以上、**四つ目を足すには何かを削る**。
 */
@Composable
private fun SilentBoard(
    display: LayoutConfig.Display,
    refreshKey: Int,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // 音の面は**本人の面**なので、私的な記録は落とす（[Hidden.DROP]）。
    // 数だけを残す匿名化は計器盤の役で、ここは絵柄も題も大きく出す面である。
    val log by rememberPlays(refreshKey, Hidden.DROP)
    val candidates = remember(refreshKey) {
        AppRepository.cached()
            .filter { it.category == ApplicationInfo.CATEGORY_AUDIO }
            .map { it.packageName }
            .distinct()
    }
    val players by produceState(initialValue = emptySet<String>(), refreshKey, candidates) {
        value = withContext(Dispatchers.Default) { Sound.players(context, candidates) }
    }
    val apps = remember(refreshKey, players) {
        AppRepository.cached()
            .filter { it.packageName in players }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    val now = System.currentTimeMillis()
    val today = remember(log) { Listening.today(log, now) }
    val summary = remember(today) { Listening.summarize(today, now) }
    val days = remember(log) { Listening.byDay(log, DAYS_SHOWN, now) }
    val hours = remember(log) { Listening.byHour(log, now) }
    // 七日の推移と同じ期間で数える。**隣り合う図が別の期間を指さない。**
    val artists = remember(log) { Listening.byArtist(Listening.within(log, DAYS_SHOWN, now), now) }

    Column(modifier = modifier.fillMaxSize()) {
        // 今日の聴取だけは数字で置く。**札に一つだけ**という約束はここでも守る
        // ——図が三つ並ぶ面で、どこから読むかを決める一点が要る。
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column {
                ObservatoryText(
                    text = "今日",
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                )
                ObservatoryText(
                    text = readableMinutes(summary.listenedMs),
                    color = ObservatoryColors.Paper,
                    size = ObservatoryType.Hero,
                    mono = true,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            if (summary.plays > 0) {
                ObservatoryText(
                    text = "${summary.plays} 曲",
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Value,
                    mono = true,
                )
            }
        }

        if (days.any { it.listenedMs > 0 }) {
            BoardRule(modifier = Modifier.padding(top = 14.dp))
            FigureCaption("七日の時系列")
            ColumnsFigure(
                labels = days.map { it.label },
                values = days.map { (it.listenedMs / 60_000).toFloat() },
                format = { "${it.toInt()}分" },
                height = 76.dp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (hours.any { it > 0 }) {
            BoardRule(modifier = Modifier.padding(top = 14.dp))
            FigureCaption("時刻別の分布")
            // 24 本は Pixel の幅で潰れるので、三時間ずつ束ねて八本にする。
            // **読めない解像度で出すより、束ねて形を見せる。**
            val buckets = (0 until 8).map { i ->
                (0 until 3).sumOf { hours[i * 3 + it] } / 60_000f
            }
            ColumnsFigure(
                labels = (0 until 8).map { "%02d".format(it * 3) },
                values = buckets,
                format = { "${it.toInt()}分" },
                height = 76.dp,
                hot = buckets.indexOf(buckets.max()),
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (artists.isNotEmpty()) {
            BoardRule(modifier = Modifier.padding(top = 14.dp))
            FigureCaption("演者別の合計")
            BarsFigure(
                items = artists.take(ARTISTS_SHOWN).map {
                    Figures.Item(label = it.first, value = it.second)
                },
                format = { readableMinutes(it) },
                axis = true,
                axisLabel = { "${(it / 60_000).toInt()}分" },
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // アプリは面の足元へ。指の届く位置に置く。
        if (apps.isNotEmpty()) {
            BoardRule()
            val iconPx = with(LocalDensity.current) { display.iconSize.dp.roundToPx() }
            IconCluster(
                entries = apps.map { entry ->
                    ClusterItem(
                        key = entry.packageName,
                        label = entry.label,
                        icon = rememberAppIcon(entry, iconPx).value,
                        onClick = { AppRepository.launch(context, entry) },
                        onLongClick = { AppRepository.openAppInfo(context, entry) },
                    )
                },
                display = display.copy(showLabels = true),
                modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
            )
        }
    }
}

/** 図の名。**単位は図の中が持つ**ので、ここでは何の図かだけを言う。 */
@Composable
private fun FigureCaption(text: String) {
    ObservatoryText(
        text = text,
        color = ObservatoryColors.Faint,
        size = ObservatoryType.Label,
        mono = true,
        modifier = Modifier.padding(top = 8.dp),
    )
}

/** 推移に出す日数。一週間ぶん。 */
private const val DAYS_SHOWN = 7

/** 演者は四人まで。**一枚に収める面では、件数は器が決める。** */
private const val ARTISTS_SHOWN = 4

/**
 * 面の中の区切り。
 * **見出しの語を置かず、罫だけにする**——一枚に収める面では、語も場所を取る。
 */
@Composable
private fun BoardRule(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(ObservatoryDimens.Hairline)
            .background(ObservatoryColors.Line),
    )
}


