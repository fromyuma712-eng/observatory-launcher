package com.yuma.observatorylauncher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yuma.observatorylauncher.data.Artwork
import com.yuma.observatorylauncher.data.Listening
import com.yuma.observatorylauncher.data.Music
import com.yuma.observatorylauncher.ui.theme.ObservatoryColors
import com.yuma.observatorylauncher.ui.theme.ObservatoryDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity

/**
 * 今かかっているもの——**音の面のためだけの意匠**。
 *
 * ## この面だけ規律を外している
 *
 * ホームと計器盤は「面を塗らない・燠火は 12%・絵柄を置かない」で通す。
 * 音の面はその外に置くことを利用者が決めた（2026/9/1）。
 *
 * > 「音楽の画面に関してはその制限を突破しても構わない。
 * > 音楽の画面は音楽の画面で発達させたい」
 *
 * だから**アルバムアートを大きく置く**。これまで断っていたのは、
 * 罫と格点で組んだ面に写真を置くと最大の色の塊になり、地と字の比が壊れるからだった。
 * この面では絵柄こそが主題なので、その理由が当たらない。
 *
 * **ただし外すのは規律であって、作法ではない。**
 * 数字は等幅・秒は二桁・押し所は 44dp——読みやすさの約束はそのまま持ち込む。
 * 面ごとに語彙は変えても、**同じものが違う顔をしない**ようにする。
 */
@Composable
internal fun NowPlayingPanel(
    live: NowPlaying,
    loaded: Artwork.Loaded?,
    modifier: Modifier = Modifier,
    refreshKey: Int = 0,
) {
    val context = LocalContext.current
    // ⚠️ **生きた値も絵柄も、外から受け取る。**
    // ここで自分の `rememberNowPlaying` を作っていた頃は、
    // 面と板が別々に席を購読していた。絵柄も同じで、
    // 面の地・絵柄の枡・色の帯が別々に読むと同じ絵を三度読むことになる。
    val now = live.current

    // ⚠️ 合図は**席からの知らせの番号**で取る。
    //
    // 題と演者だけを合図にしていたら、途中から鳴らして次の曲へ移ったとき
    // 絵柄が出ないことがあった（本人の指摘・2026/9/1）。
    // `MediaSession` は一曲ぶんを**二度に分けて**渡すことがあり、
    // 一度目には題しか入っていない。そこで「絵は無い」と決めてしまうと、
    // 二度目の知らせでは題が変わらないので**取り直しが起きない**。
    //
    // 番号なら、二度目の知らせでも必ず動く。
    val art = remember(loaded) { loaded?.bitmap?.asImageBitmap() }

    Column(modifier = modifier.fillMaxWidth()) {
        // ## 絵柄の枡と、余った高さの使い道
        //
        // ⚠️ **正方形に切り落としていた。**
        // 歌ってみた・カバーの絵は 16:9 の縮小画のことがあり、
        // 正方形へ `Crop` すると**左右が落ちて誰の絵か分からなくなる**。
        // 縦横の比は絵が決めるものであって、面が決めるものではない
        // （本人指示・2026/9/1「どのサイズでもいいように」）。
        //
        // ただし比を絵に任せると、**横長の絵では高さが余る**。
        // 余りをそのまま空けると、一枚で完結するはずの面に穴が開く。
        // だから**入る分だけ「今日きいたもの」を継ぎ足す**
        // ——正方形の絵なら余りが出ないので、何も足さない。
        // 面が自分の空きを測って、置けるものを置く。
        BoxWithConstraints(
            // ⚠️ `fill = false` にしてある。
            //
            // 埋めさせると、絵と「今日の音」を置いた後の**余りが枡の中に残り**、
            // 絵の下と題の上に空きが挟まる——これが「額縁のようなもの」に見えていた
            // （本人の指摘・2026/9/2）。
            //
            // 埋めさせなければ、枡は中身の高さで畳まれ、
            // **余りは面の末尾に一度だけ**落ちる。
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                // 角を丸めない。**この体系は直角で組む**——
                // 規律を外すのは色と面積であって、形の語彙までは変えない。
                .background(ObservatoryColors.Ground),
        ) {
            val boxWidth = maxWidth
            val boxHeight = maxHeight
            val image = art
            val ratio = image
                ?.let { (it.width.toFloat() / it.height).takeIf { r -> r.isFinite() && r > 0f } }
                ?: 1f

            // ## 幅いっぱいに置けるか
            //
            // ⚠️ 正方形の絵柄で**左右に空きが出た**（本人の指摘・2026/9/2）。
            // 高さが足りないときに絵を縮めていたからである。
            //
            // 縦横の比は絵が決めるという約束は守りつつ、
            // **足りない分がわずかなら、上下をほんの少し落として幅いっぱいに置く**。
            // 正方形の盤で 3% ほど——絵として何が失われたか分からない量である。
            //
            // 落とす量が[MAX_CROP]を超えるなら、**縮めて全部を見せる方を採る**。
            // 絵を切るために置いているのではない。
            val natural = boxWidth / ratio
            val fits = natural <= boxHeight
            val shortfall = if (fits || natural <= 0.dp) 0f else (natural - boxHeight) / natural
            val cropped = !fits && shortfall <= MAX_CROP
            val drawWidth = if (fits || cropped) boxWidth else boxHeight * ratio
            val drawHeight = if (fits) natural else boxHeight

            Column(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(drawHeight),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    if (image != null) {
                        Image(
                            bitmap = image,
                            contentDescription = null,
                            contentScale = if (cropped) {
                                ContentScale.Crop
                            } else {
                                ContentScale.Fit
                            },
                            modifier = Modifier.size(drawWidth, drawHeight),
                        )
                    } else {
                        // 取れなかった枡。罫と格点——**この面以外の語彙に戻す**。
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .border(ObservatoryDimens.Hairline, ObservatoryColors.Line),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(ObservatoryDimens.Anchor)
                                    .background(ObservatoryColors.Line),
                            )
                        }
                    }
                }
                // 余った高さの使い道。
                //
                // ⚠️ ここには一度「この曲の色」の帯を敷いていたが、やめた
                // （本人・2026/9/2）。**色は面の地が受け持つ**ようになったので、
                // 同じことを二度見せる帯になっていた。
                //
                // 代わりに「今日の音」を置く。横長の絵柄でだけ余りが出るので、
                // **横長の絵は余りを帯に、正方形の絵は余りを絵に**という配り方は
                // そのまま生きている。
                val spare = boxHeight - drawHeight
                if (spare >= TAPE_MIN) {
                    DayTape(
                        refreshKey = refreshKey,
                        revision = live.revision,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                }
            }
        }
        now?.let { playing ->
            // 題を押せば、鳴らしているアプリの**今の画面**へ入る。
            // 席が `sessionActivity` を持っていればそこへ、無ければ起動口へ。
            val titleInteraction = remember { MutableInteractionSource() }
            ObservatoryText(
                text = playing.title.ifBlank { "(無題)" },
                color = ObservatoryColors.Paper,
                size = ObservatoryType.Value,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = titleInteraction,
                        indication = null,
                    ) { Music.openPlayer(context) }
                    .padding(top = 14.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                ObservatoryText(
                    text = playing.artist,
                    color = ObservatoryColors.EmberText,
                    size = ObservatoryType.Body,
                    modifier = Modifier.weight(1f),
                )
                ObservatoryText(
                    text = playing.app,
                    color = ObservatoryColors.Faint,
                    size = ObservatoryType.Label,
                    mono = true,
                )
            }
            // ## 進行は**塗った帯**、そして**掴める**
            //
            // 面積を許した面なので、目盛りより帯の方が遠目に読める。
            //
            // 掴ませてよいのは、席が `SEEK_TO` を名乗っているときだけである。
            // 名乗らない席で掴ませると、指は動くのに音は動かない
            // ——**動いたように見えて動かないのが、最も悪い。**
            //
            // 掴んでいる間は手元の値を出す（`scrub`）。放してから席が新しい位置を
            // 知らせてくるまでの間、帯が元の位置へ跳ね戻るのを防ぐ。
            // 知らせが来たら（`revision` が動いたら）手元の値を捨てる。
            var scrub by remember { mutableStateOf<Float?>(null) }
            LaunchedEffect(live.revision) { scrub = null }

            playing.progress?.let { reported ->
                val fraction = scrub ?: reported
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        // 押し所は帯そのものではなく、**指の入る高さ**で取る。
                        // 帯は 6dp だが、6dp を狙わせてはいけない。
                        .height(ObservatoryDimens.Row)
                        .then(
                            if (!live.canSeek || playing.duration <= 0L) {
                                Modifier
                            } else {
                                Modifier.pointerInput(live.revision, playing.duration) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown()
                                        val width = size.width.toFloat()
                                        scrub = (down.position.x / width).coerceIn(0f, 1f)
                                        down.consume()
                                        horizontalDrag(down.id) { change ->
                                            scrub = (change.position.x / width).coerceIn(0f, 1f)
                                            change.consume()
                                        }
                                        scrub?.let {
                                            live.seekTo((playing.duration * it).toLong())
                                        }
                                    }
                                }
                            },
                        )
                        .drawBehind {
                            val bar = 6.dp.toPx()
                            val top = (size.height - bar) / 2f
                            drawRect(
                                ObservatoryColors.Line,
                                Offset(0f, top),
                                Size(size.width, bar),
                            )
                            drawRect(
                                ObservatoryColors.Ember,
                                Offset(0f, top),
                                Size(size.width * fraction, bar),
                            )
                            // 掴める帯にだけ、掴む所を示す印を置く。
                            // **掴めない帯と同じ姿にしない**——押せるものは押せる形をする。
                            if (live.canSeek) {
                                val grip = 3.dp.toPx()
                                drawRect(
                                    ObservatoryColors.Paper,
                                    Offset(
                                        (size.width * fraction - grip)
                                            .coerceIn(0f, size.width - grip * 2),
                                        top - grip,
                                    ),
                                    Size(grip * 2, bar + grip * 2),
                                )
                            }
                        },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 数字は等幅・秒は二桁。**読みやすさの約束は持ち込む。**
                    // 掴んでいる間は、掴んだ先の時刻を出す。
                    ObservatoryText(
                        text = Music.clock(
                            scrub?.let { (playing.duration * it).toLong() } ?: playing.position,
                        ),
                        color = if (scrub != null) {
                            ObservatoryColors.Paper
                        } else {
                            ObservatoryColors.Muted
                        },
                        size = ObservatoryType.Note,
                        mono = true,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    ObservatoryText(
                        text = Music.clock(playing.duration),
                        color = ObservatoryColors.Muted,
                        size = ObservatoryType.Note,
                        mono = true,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BigControl("◂◂", live.previous)
                BigControl(if (playing.playing) "▮▮" else "▶", live.toggle)
                BigControl("▸▸", live.next)
            }

            // 二段目——**席が名乗ったものだけ**を置く。
            SeatMarks(live)

            // 席から**追加で読み取れるもの**。題と演者だけが中身ではない。
            SoundDetail(refreshKey = refreshKey, trackKey = playing.title)

            // ⚠️ ここに `Spacer(weight(1f))` を置いてはならない。
            // 絵の枡も重みを持つので、**残りを半分ずつ分け合ってしまう**
            // ——絵が画面の半分までしか伸びず、左右に額縁が出た（実機・2026/9/2）。
            //
            // 枡が `fill = false` である以上、**使わなかった分は自然に末尾へ落ちる**。
            // 余りを明に置く必要は無い。

            // **一枚を締める。** 今週の音は、今かかっている一曲を
            // **自分の一週間の中に置く**数字である。
            //
            // ⚠️ ここに `weight` を置いて下へ押し付けていたが、やめた。
            // 余りは**絵柄の枡が飲む**方が正しい——空けた隙間は隙間にしか見えないが、
            // 絵に回せば絵が大きくなる。
            WeekOfSound(refreshKey = refreshKey)
        }
    }
}

/**
 * 席が出している操作——評価・シャッフル・リピートなど。
 *
 * ## ⚠️ 語で選り分けない
 *
 * はじめは「括弧で状態を書いているものだけが切替」として選り分けていた。
 * 実機で Apple Music を試したら、**操作が一つも出なかった**
 * ——あちらの名前は「お気に入り」「シャッフル」「リピート」で、括弧が無い。
 *
 * 選り分けをやめ、**アプリが並べた順の先頭から三つ**を出す。
 * アプリは自分の通知に出す順で並べているので、その順を信じてよい。
 * 実測でも、両アプリとも先頭三つがちょうど要るものだった。
 *
 * ## 状態は、名前が語ったときだけ
 *
 * - 「シャッフル（オフ）」→ 括弧の言葉をそのまま添え、沈める
 * - 「高評価を取り消す」→ **今は効いている**と読み、燠火にする
 * - 「お気に入り」→ 何も足さない。**読めないものを「切」と描かない**
 */
@Composable
private fun SeatMarks(live: NowPlaying, modifier: Modifier = Modifier) {
    if (live.marks.isEmpty()) return
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        live.marks.forEach { mark ->
            SeatMark(
                mark = mark,
                // 切と読めたものだけ沈める。読めないものは沈めない
                // ——**沈めることは「効いていない」という主張である。**
                dim = mark.state == Music.Switch.OFF,
                onClick = { live.sendMark(mark.id) },
            )
        }
    }
}

/**
 * 印一つ。押し所は HIG の最小（44dp）を下回らせない。
 *
 * ## 記号は**アプリから借りる**
 *
 * 自前の記号を用意すると、どの操作にどの記号かを**言葉から当てる**ことになる。
 * 言葉は端末の設定でもアプリでも変わるので、当てそこねる。
 * `CustomAction` はアプリ自身の記号（通知に出しているものと同じ）を連れているので、
 * それを借りれば当てる必要が無い。
 *
 * ⚠️ **借りた形はこちらの色で染める。** 相手の色をそのまま置くと、
 * この面の 88:12 が崩れる。形だけを受け取り、色は体系の側が決める。
 *
 * 記号が起こせなければ言葉に戻す——**押せる所が消えるよりはよい。**
 */
@Composable
private fun SeatMark(
    mark: Music.Mark,
    dim: Boolean,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val sizePx = with(LocalDensity.current) { MARK_ICON.roundToPx() }
    val icon by produceState<ImageBitmap?>(null, mark.id, mark.icon, sizePx) {
        value = withContext(Dispatchers.Default) {
            Music.markIcon(context, mark, sizePx)?.asImageBitmap()
        }
    }
    val ink = when {
        mark.hot -> ObservatoryColors.EmberText
        dim -> ObservatoryColors.Faint
        else -> ObservatoryColors.Paper
    }

    Column(
        modifier = Modifier
            .heightIn(min = ObservatoryDimens.Row)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val shape = icon
        if (shape != null) {
            Image(
                bitmap = shape,
                contentDescription = mark.label,
                colorFilter = ColorFilter.tint(ink),
                modifier = Modifier.size(MARK_ICON),
            )
        } else {
            ObservatoryText(text = mark.label, color = ink, size = ObservatoryType.Body)
        }
        if (mark.stateText != null) {
            ObservatoryText(
                text = mark.stateText,
                color = if (mark.hot) ObservatoryColors.EmberText else ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 借りた記号の大きさ。操作の三つ組（64dp）より小さく、二段目であることを保つ。 */
private val MARK_ICON = 22.dp

/**
 * 幅いっぱいに置くために、上下から落としてよい割合の上限。
 *
 * これを超えるなら**縮めて全部を見せる**——絵を切るために置いているのではない。
 */
private const val MAX_CROP = 0.12f

/** 「今日の音」を出す最低の空き。これを割るなら、置かずに絵へ回す。 */
private val TAPE_MIN = 64.dp

/**
 * 今日の音の帯。
 *
 * 今日かけた曲を、**聴いた長さに比例した幅**で横に並べ、
 * それぞれを絵柄から採った色で塗る。
 *
 * ## なぜ曲名を並べないか
 *
 * ホームも計器盤も台帳である。ここに三つ目の台帳は要らない。
 * **一日がどんな音だったかは、色の並びで足りる。**
 *
 * ## ⚠️ 色の無い曲は塗らない
 *
 * 見張り（通知）は外へ出ないので、絵の在り処しか無い曲は色を持たない。
 * その区間は**罫だけで示す**——取れなかったものを、灰色で塗って
 * 「そういう色だった」ことにしない。
 *
 * 記録は今日から貯まり始める。**今日はまだ短い。**
 */
@Composable
private fun DayTape(refreshKey: Int, revision: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val log by rememberPlays(refreshKey + revision, Hidden.DROP)
    val spans = remember(log) {
        val now = System.currentTimeMillis()
        Listening.spans(Listening.today(log, now), now)
    }
    val total = spans.sumOf { it.second }
    if (spans.isEmpty() || total <= 0L) return

    Column(modifier = modifier.fillMaxWidth().padding(top = 14.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .drawBehind {
                    val hair = 1.dp.toPx()
                    var x = 0f
                    spans.forEach { (play, span) ->
                        val w = size.width * (span.toFloat() / total)
                        if (play.tint != 0) {
                            drawRect(Color(play.tint), Offset(x, 0f), Size(w + 1f, size.height))
                        } else {
                            // 色が取れなかった区間。**塗らずに枠だけ。**
                            drawRect(
                                ObservatoryColors.Line,
                                Offset(x, 0f),
                                Size(w, hair),
                            )
                            drawRect(
                                ObservatoryColors.Line,
                                Offset(x, size.height - hair),
                                Size(w, hair),
                            )
                        }
                        x += w
                    }
                    drawRect(ObservatoryColors.Ground.copy(alpha = 0.08f), Offset(0f, 0f), size)
                },
        )
        Row(modifier = Modifier.fillMaxWidth().padding(top = 5.dp)) {
            ObservatoryText(
                text = "今日の音",
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
            )
            Spacer(modifier = Modifier.weight(1f))
            ObservatoryText(
                text = Music.timeOfDay(spans.first().first.at) + " から",
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Label,
                mono = true,
            )
        }
    }
}

/**
 * 席から追加で読み取れるもの——**次にかかるもの・盤・年・繰り返し・混ぜ・速さ**。
 *
 * ## 置き方の約束
 *
 * - **取れないものは欄ごと出さない。** アプリによって埋めている鍵が違うので、
 *   空の欄を並べると「この曲には情報が無い」ではなく「この面は壊れている」に見える。
 * - **当たり前の値は出さない。** 等速の「×1.0」は情報量が無い。
 * - 一行に収める。一枚で完結する面なので、増やすなら何かを削る。
 */
@Composable
private fun SoundDetail(refreshKey: Int, trackKey: String) {
    val context = LocalContext.current
    val detail by produceState<Music.Detail?>(null, refreshKey, trackKey) {
        value = withContext(Dispatchers.Default) {
            runCatching { Music.controller(context)?.let { Music.detail(it) } }.getOrNull()
        }
    }
    val d = detail ?: return
    if (d.isEmpty) return

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        // 次にかかるもの。**これだけは行を分ける**——題は長く、他の値と並ばない。
        d.next?.let { next ->
            Row(verticalAlignment = Alignment.Bottom) {
                ObservatoryText(
                    text = "次",
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Note,
                    mono = true,
                )
                Spacer(modifier = Modifier.width(10.dp))
                ObservatoryText(
                    text = next,
                    color = ObservatoryColors.Muted,
                    size = ObservatoryType.Body,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val marks = buildList {
            d.album?.let { add(it) }
            d.year?.let { add(it) }
            d.queueSize?.let { add("列 ${it}曲") }
            d.speed?.let { add("×%.2f".format(it)) }
        }
        if (marks.isNotEmpty()) {
            ObservatoryText(
                text = marks.joinToString("  ∧  "),
                color = ObservatoryColors.Faint,
                size = ObservatoryType.Note,
                modifier = Modifier.fillMaxWidth().padding(top = if (d.next != null) 6.dp else 0.dp),
            )
        }
    }
}

/**
 * 今週の音。
 *
 * **前週比があって初めて、今週の数字が意味を持つ。**
 * 「6時間12分」だけでは多いか少ないか判じられない。
 */
@Composable
private fun WeekOfSound(refreshKey: Int) {
    val context = LocalContext.current
    val log by rememberPlays(refreshKey, Hidden.DROP)
    val w = remember(log) { Listening.week(log) }
    if (log.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(ObservatoryColors.LineStrong),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // ⚠️ 数えているのは**今から遡って七日**であって、暦の上の「今週」ではない。
            // 計器盤の「七日」とも揃う（2026/9/2 の点検）。
            WeekCell("七日", readableMinutes(w.listenedMs), Modifier.weight(1f))
            WeekCell("曲", "${w.plays}", Modifier.weight(1f))
            WeekCell("演者", "${w.artists}", Modifier.weight(1f))
            // 前週比は根拠があるときだけ。
            w.changePercent?.let {
                WeekCell(
                    "前の七日比",
                    (if (it >= 0) "+" else "") + it + "%",
                    Modifier.weight(1f),
                    hot = it > 0,
                )
            }
        }
    }
}

@Composable
private fun WeekCell(label: String, value: String, modifier: Modifier, hot: Boolean = false) {
    Column(modifier = modifier) {
        ObservatoryText(
            text = label,
            color = ObservatoryColors.Muted,
            size = ObservatoryType.Note,
            mono = true,
        )
        ObservatoryText(
            text = value,
            color = if (hot) ObservatoryColors.EmberText else ObservatoryColors.Paper,
            size = ObservatoryType.Value,
            mono = true,
        )
    }
}

/** 大きな操作。押し所は HIG の最小(44dp)を上回らせる——この面では指で操作するものが主役である。 */
@Composable
private fun BigControl(label: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(64.dp)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ObservatoryText(text = label, color = ObservatoryColors.Paper, size = 20.sp)
    }
}
