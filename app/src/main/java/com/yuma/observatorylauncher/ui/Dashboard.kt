package com.yuma.observatorylauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens

/**
 * 計器盤。
 *
 * 頁 0・1 は**選ぶ**面である。日々目に入るものだけを置き、
 * 要らないものは消す——利用者はそう使っている。
 *
 * この面はその逆で、**選ばない**。端末から取れるものを全部並べる。
 * 「あれは今どうなっていたか」を確かめに来る場所であり、
 * 選別された面と役割が違うので、同じ器を二つ持つ意味がある。
 *
 * ## 置き場所
 *
 * 頁 1 の**左**（index -1）に置く。0 番に据えると利用者の頁が全部ずれる。
 * 端に置くことで「常用の面はそのまま、確かめたいときだけ左へ寄る」という
 * 動きになり、日々の動線を変えない。
 *
 * ## 何を載せるか
 *
 * **目録から自動で組む。** 一覧に札を足せばここにも自動で載るので、
 * 二箇所を手で揃え続ける必要がない（揃え忘れは必ず起きる）。
 *
 * 載せないのは二種類だけ。
 *
 * - **利用者が中身を書くもの**（メモ・時間割・期限・習慣）。
 *   空の器を並べても面が埋まるだけで、何も分からない。
 * - **より詳しい札に取って代わられたもの**。同じ値を二度書くと、
 *   どちらを見ればよいのかが分からなくなる。
 */
internal object Dashboard {

    /** 頁の番号。利用者の頁は 0 から始まるので、その左に置く。 */
    const val PAGE = -1

    /**
     * 詳しい札に取って代わられたもの。
     *
     * 値そのものは正しいので目録には残すが、計器盤では新しい方だけを出す。
     */
    private val SUPERSEDED = mapOf(
        LayoutConfig.INFO_USAGE to LayoutConfig.INFO_USAGE_BARS,
        LayoutConfig.INFO_MEDIA to LayoutConfig.INFO_MUSIC,
        LayoutConfig.INFO_BATTERY to LayoutConfig.INFO_CHARGE,
        LayoutConfig.INFO_BATTERY_DETAIL to LayoutConfig.INFO_CHARGE,
        // 天気と詳細は**この面では一枚**にする。見出しが二つに割れる理由が無い。
        LayoutConfig.INFO_WEATHER to LayoutConfig.INFO_WEATHER_FULL,
        LayoutConfig.INFO_WEATHER_DETAIL to LayoutConfig.INFO_WEATHER_FULL,
        // 空き容量は保存領域の札が持つので、端末状態からは抜く。
        LayoutConfig.INFO_DEVICE to LayoutConfig.INFO_DEVICE_BRIEF,
        // 今日の予定は時間軸が、明日以降は時間軸の脚注が受け持つ。
        // 同じ予定を二箇所に書くと、どちらが正かを読む側が考えることになる。
        LayoutConfig.INFO_CALENDAR to LayoutConfig.INFO_CALENDAR_TIMELINE,
        // 日付は面の一番上の時計が持つ。週番号と年内日数だけを残す。
        LayoutConfig.INFO_DATE to LayoutConfig.INFO_DATE_BRIEF,
    )

    /** 音の面が受け持つので、計器盤には出さないもの。 */
    private val MOVED_TO_MUSIC_BOARD = setOf(
        LayoutConfig.INFO_MUSIC,
        LayoutConfig.INFO_MEDIA,
    )

    /** 目録に無い、この面だけの種類。統合したものはここに入る。 */
    private val DASHBOARD_ONLY = setOf(
        LayoutConfig.INFO_WEATHER_FULL,
        LayoutConfig.INFO_DEVICE_BRIEF,
        LayoutConfig.INFO_DATE_BRIEF,
        LayoutConfig.INFO_WEATHER_SERIES,
        LayoutConfig.INFO_LISTENING,
    )

    /**
     * 節と、その中の並び・幅。
     *
     * ## 並べ方の約束
     *
     * - **急ぐものから置く。** 予定・空模様は時刻に縛られ、端末の状態は縛られない。
     * - **節の中は「図が先、数字が後」。** 大きな図と一行の値を交互に置くと、
     *   視線が上下に跳ねて、どこまで読んだかを見失う。
     * - 幅 4 は面いっぱい、2 は半分。**一行に収まる値は必ず二列に詰める**
     *   ——一行ずつ積むと縦だけが伸びて右が空く。
     */
    private val SECTIONS: List<Pair<String, List<Pair<String, Int>>>> = listOf(
        "予定" to listOf(
            LayoutConfig.INFO_NEXT_ACTION to 4,
            LayoutConfig.INFO_CALENDAR_TIMELINE to 4,
            LayoutConfig.INFO_CALENDAR_MONTH to 4,
        ),
        "空" to listOf(
            LayoutConfig.INFO_WEATHER_SERIES to 4,
            LayoutConfig.INFO_WEATHER_FULL to 4,
        ),
        // 「使い方」は大きくなりすぎたので三つに割った。
        //
        // **一つの節に七つ八つ並ぶと、節としての意味が消える。**
        // 節は「同じ問いに答えるものの束」であって、
        // 「余ったものの置き場」ではない。問いごとに割り直す。
        //
        // 今日どれだけか → どんな癖か → 減らせているか、の順で並べる。
        // 事実・形・評価の順であり、**評価を先に置かない**
        // ——数字を見る前に良し悪しを言われるのは、道具の越権である。
        "当日の集計" to listOf(
            // **最初に置く。** 開いてまず読むのはここで、
            // ここが読めれば残りは「なぜそうなったか」の説明になる。
            LayoutConfig.INFO_PACE to 4,
            LayoutConfig.INFO_USAGE_BARS to 4,
            LayoutConfig.INFO_OPENS to 4,
        ),
        "分布" to listOf(
            LayoutConfig.INFO_DAY_SHAPE to 4,
            LayoutConfig.INFO_SPREAD to 4,
            LayoutConfig.INFO_USAGE_HEAT to 4,
        ),
        // ⚠️ 「減らせているか」の節は**摩擦を掛けたアプリが一つも無ければ出さない**。
        //
        // 節の中身（注意の会計）は、摩擦を掛けたアプリの時間と、
        // 掛けた結果ひらかなかった回数を数えるものである。
        // 掛けていなければ数えるものが無く、**0 が並ぶだけの節**になる。
        //
        // > 「摩擦をかけたアプリがない以上、減らせているかを表示する意味はない」
        // > （本人・2026/9/1）
        //
        // 消すのではなく**条件付きにした**——掛けた日から節が現れる方が、
        // 掛けたことの意味が面に出る。
        "前後の比較" to listOf(
            LayoutConfig.INFO_ATTENTION to 4,
        ),
        "端末" to listOf(
            LayoutConfig.INFO_CHARGE to 4,
            LayoutConfig.INFO_SPACE to 4,
            LayoutConfig.INFO_TRAFFIC to 4,
            LayoutConfig.INFO_TRAFFIC_WEEK to 4,
        ),
        // 再生中の札は置かない（音の面が受け持つ）が、**数はここの仕事**である。
        "音" to listOf(
            LayoutConfig.INFO_LISTENING to 4,
        ),
        "身体" to listOf(
            LayoutConfig.INFO_HEALTH to 4,
            LayoutConfig.INFO_STEPS_WEEK to 4,
        ),
        "状態" to listOf(
            LayoutConfig.INFO_DEVICE_BRIEF to 2,
            LayoutConfig.INFO_ALARM to 2,
            LayoutConfig.INFO_SOUND to 2,
            LayoutConfig.INFO_BLUETOOTH to 2,
            LayoutConfig.INFO_NOTIFICATIONS to 2,
            LayoutConfig.INFO_TOUCH to 2,
            LayoutConfig.INFO_DATE_BRIEF to 2,
        ),
    )

    /**
     * 実際に並べる節。
     *
     * 表に書いていない種類は「そのほか」へ落とす——**目録に足したものが
     * 黙って消えるのを防ぐ**。ここを手で揃え続ける約束にすると、必ず忘れる。
     */
    fun sections(hasFriction: Boolean = true): List<Pair<String, List<LayoutConfig.InfoTile>>> {
        val placed = SECTIONS.flatMap { it.second }.map { it.first }.toSet()
        val leftovers = INFO_CATALOG
            .filterNot { it.needsEditor }
            .filterNot { it.kind in SUPERSEDED }
            .filterNot { it.kind in placed }
            // ⚠️ 節から外しただけでは足りない。
            // **行き場を失った札は「そのほか」に現れる**ので、目録側でも落とす。
            .filterNot { !hasFriction && it.kind == LayoutConfig.INFO_ATTENTION }
            // 音の面が一枚まるごと音のためにある以上、計器盤に再生の札は要らない
            // ——**同じ情報を二箇所に書かない**（本人指示・2026/9/2）。
            .filterNot { it.kind in MOVED_TO_MUSIC_BOARD }
            .map { it.kind to 4 }

        val sections = if (hasFriction) {
            SECTIONS
        } else {
            SECTIONS.filterNot { it.first == "前後の比較" }
        }
        return (sections + ("そのほか" to leftovers))
            .map { (title, entries) -> title to entries.mapNotNull { tileFor(it.first, it.second) } }
            .filter { it.second.isNotEmpty() }
    }

    private fun tileFor(kind: String, span: Int): LayoutConfig.InfoTile? {
        if (kind !in DASHBOARD_ONLY) {
            val known = INFO_CATALOG.firstOrNull { it.kind == kind } ?: return null
            if (known.needsEditor) return null
        }
        if (kind in SUPERSEDED) return null
        // **id は種類から決める。** 毎回作り直すと Compose が別物と見なして
        // 描き直しになり、図の状態（月の送り位置など）が毎回失われる。
        return LayoutConfig.InfoTile(
            id = "dash-$kind",
            kind = kind,
            page = PAGE,
            zone = LayoutConfig.ZONE_ABOVE,
            span = span,
        )
    }
}

/**
 * 計器盤の面。
 *
 * 節ごとに見出しを置き、あとは通常の札の描画に渡す。
 * **描き方を作り直さない**——同じ札が頁によって違う顔をするのは、
 * 覚えることを増やすだけである。
 */
@Composable
internal fun DashboardPage(
    pageCount: Int,
    commands: TileCommands,
    refreshEpoch: Int,
    modifier: Modifier = Modifier,
    frictionApps: Set<String> = emptySet(),
) {
    val sections = remember(frictionApps.isEmpty()) {
        Dashboard.sections(hasFriction = frictionApps.isNotEmpty())
    }
    Column(modifier = modifier.fillMaxWidth()) {
        sections.forEachIndexed { index, (title, tiles) ->
            DashboardHeading(
                title = title,
                meta = META[title].orEmpty(),
                modifier = Modifier.padding(top = if (index == 0) 4.dp else 18.dp),
            )
            InfoTilePanel(
                tiles = tiles,
                pageCount = pageCount,
                editing = false,
                commands = commands,
                refreshEpoch = refreshEpoch,
                frictionApps = frictionApps,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
}

/**
 * 節の見出し。格点・名・弦材の順で、台帳の見出しと同じ語彙にする。
 * 面が変わっても読み方が変わらないようにするためである。
 */
/** 節が名乗る範囲。**図がどの解像度で見ているか**を、節の右肩に置く。 */
private val META = mapOf(
    "予定" to "今日 ∧ 今月",
    "空" to "12時間 ∧ 1時間刻み",
    "当日の集計" to "今日 ∧ 0時から",
    "分布" to "七日 ∧ 四週",
    "前後の比較" to "七日平均との差",
    "端末" to "今 ∧ 七日",
    "音" to "七日 ∧ 名は伏せる",
    "身体" to "今日 ∧ 七日",
    "状態" to "今",
)

/**
 * 面の頭に置く一本の帯と名札。
 *
 * ## 12% の帯
 *
 * 幅は画面の 12%。**この体系の「地と字は 88:12」という約束を、
 * そのまま目に見える一本にした**もの。
 * 三枚の面すべてに同じ位置・同じ幅で置くので、
 * どの面に居ても「同じ道具の中に居る」ことが形で分かる。
 *
 * ## 名札
 *
 * 「計器盤 ∧ 01/03」——**どの面の何枚目か**を常に名乗る。
 * 点の頁表示より情報量が多く、しかも場所を取らない。
 */
@Composable
internal fun BoardMasthead(
    name: String,
    index: Int,
    count: Int,
    right: String = "",
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.12f)
                .height(3.dp)
                .background(ObservatoryColors.Ember),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ObservatoryDimens.Gutter)
                .padding(top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ObservatoryText(
                text = "$name ∧ %02d/%02d".format(index, count),
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
                tracking = 0.10f,
            )
            if (right.isNotBlank()) {
                Spacer(modifier = Modifier.weight(1f))
                ObservatoryText(
                    text = right,
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                    tracking = 0.10f,
                )
            }
        }
    }
}

@Composable
private fun DashboardHeading(
    title: String,
    modifier: Modifier = Modifier,
    meta: String = "",
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ObservatoryDimens.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(ObservatoryDimens.Anchor)
                .background(ObservatoryColors.LineStrong),
        )
        Spacer(modifier = Modifier.width(8.dp))
        ObservatoryText(
            text = title,
            color = ObservatoryColors.Muted,
            size = ObservatoryType.Note,
            mono = true,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(ObservatoryDimens.Hairline)
                .background(ObservatoryColors.Line),
        )
        // 右のメタ。**節が自分の範囲を名乗る。**
        // 「14–20時 ／ 30分刻み」のように、何をどの解像度で見ているかを置く。
        if (meta.isNotBlank()) {
            Spacer(modifier = Modifier.width(12.dp))
            ObservatoryText(
                text = meta,
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
            )
        }
    }
}
