package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.data.Listening
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聴いたものの記録の試験。
 *
 * 端末なしで確かめられる部分——重複の弾き方、古いものの落とし方、
 * 聴いた時間の見積り——を押さえる。集計の誤りは例外にならず、
 * ただ違う数字が出るだけなので、ここで留めるしかない。
 */
class ListeningTest {

    private val t0 = 1_756_000_000_000L

    private fun play(at: Long, title: String, artist: String = "A", duration: Long = -1L) =
        Listening.Play(at = at, title = title, artist = artist, app = "app", durationMs = duration)

    // ── 読み書き ───────────────────────────────────────────

    @Test
    fun theLogRoundTrips() {
        val plays = listOf(
            play(t0, "One", "Tame Impala", 210_000),
            play(t0 + 300_000, "Two", ""),
        )
        assertEquals(plays, Listening.parse(Listening.format(plays)))
    }

    /** 壊れた記録は空として扱う。**一箇所の傷で面を壊さない。** */
    @Test
    fun aBrokenLogIsTreatedAsEmpty() {
        assertTrue(Listening.parse("").isEmpty())
        assertTrue(Listening.parse("[壊れている").isEmpty())
    }

    /** 題の無いものは記録として意味を持たないので落ちる。 */
    @Test
    fun anEntryWithoutATitleIsDropped() {
        assertTrue(Listening.parse("""[{"t":$t0,"n":""}]""").isEmpty())
    }

    // ── 重複 ───────────────────────────────────────────────

    /** **同じ曲の知らせが続いても一度だけ。** 席は一曲の中でも何度も知らせてくる。 */
    @Test
    fun theSameTrackIsNotRecordedTwiceInARow() {
        val first = listOf(play(t0, "One"))
        val again = Listening.appended(first, play(t0 + 5_000, "One"))
        assertEquals(first, again)
    }

    /** 間合いが空けば、同じ曲でも数える——本当にかけ直したということである。 */
    @Test
    fun theSameTrackCountsAgainAfterTheGap() {
        val first = listOf(play(t0, "One"))
        val later = Listening.appended(first, play(t0 + Listening.DEDUPE_MS + 1, "One"))
        assertEquals(2, later.size)
    }

    /** 別の曲なら間合いに関係なく数える。 */
    @Test
    fun aDifferentTrackIsAlwaysRecorded() {
        val first = listOf(play(t0, "One"))
        assertEquals(2, Listening.appended(first, play(t0 + 1_000, "Two")).size)
    }

    // ── 古いもの ───────────────────────────────────────────

    @Test
    fun oldEntriesFallAway() {
        val old = play(t0, "Old")
        val fresh = play(t0 + Listening.KEEP_MS + 60_000, "Fresh")
        assertEquals(listOf(fresh), Listening.appended(listOf(old), fresh))
    }

    @Test
    fun theLogIsCapped() {
        var log = emptyList<Listening.Play>()
        repeat(Listening.KEEP_COUNT + 50) { i ->
            log = Listening.appended(log, play(t0 + i * 120_000L, "T$i"))
        }
        assertEquals(Listening.KEEP_COUNT, log.size)
        // 残っているのは新しい方である。
        assertEquals("T${Listening.KEEP_COUNT + 49}", log.last().title)
    }

    // ── 集計 ───────────────────────────────────────────────

    /** 一曲ぶんは「次までの間隔」と「曲の長さ」の短い方。 */
    @Test
    fun aSpanIsTheShorterOfTheGapAndTheLength() {
        val plays = listOf(
            // 三分の曲を、次まで十分空けて置く → 三分で頭打ち
            play(t0, "Long", "X", 180_000),
            // 三分の曲を、一分で次へ → 一分だけ聴いた
            play(t0 + 600_000, "Skipped", "X", 180_000),
            play(t0 + 660_000, "Next", "Y", 180_000),
        )
        val s = Listening.summarize(plays, t0 + 660_000 + 180_000)
        assertEquals(3, s.plays)
        assertEquals(180_000 + 60_000 + 180_000, s.listenedMs)
    }

    /** 最後の一曲も 0 にしない。**今かけている曲が集計から消えるのを防ぐ。** */
    @Test
    fun theLastTrackStillCounts() {
        val s = Listening.summarize(listOf(play(t0, "Now", "X", 200_000)), t0 + 30_000)
        assertTrue(s.listenedMs > 0)
        assertEquals(30_000, s.listenedMs)
    }

    /** 長さの分からない曲には、おおよその一曲を当てる。 */
    @Test
    fun anUnknownLengthFallsBackToAnAssumedTrack() {
        val s = Listening.summarize(listOf(play(t0, "Unknown")), t0 + 60L * 60_000)
        assertEquals(Listening.ASSUMED_MS, s.listenedMs)
    }

    /** 最も長く聴いた演者を出す。演者の無いものは数に入れない。 */
    @Test
    fun theTopArtistIsByTime() {
        val plays = listOf(
            play(t0, "a", "Short", 60_000),
            play(t0 + 60_000, "b", "Long", 300_000),
            play(t0 + 360_000, "c", "", 60_000),
        )
        val s = Listening.summarize(plays, t0 + 420_000)
        assertEquals("Long", s.topArtist)
    }

    /** 何も無ければ 0。空の集計で落ちない。 */
    @Test
    fun anEmptyLogSummarizesToZero() {
        val s = Listening.summarize(emptyList(), t0)
        assertEquals(0, s.plays)
        assertEquals(0L, s.listenedMs)
        assertEquals("", s.topArtist)
    }

    /** 今日ぶんだけを切り出す。 */
    @Test
    fun todayIsCutFromMidnight() {
        val now = t0
        val all = listOf(play(now - 40L * 60 * 60_000, "yesterday"), play(now - 60_000, "today"))
        val today = Listening.today(all, now)
        assertEquals(1, today.size)
        assertEquals("today", today.first().title)
    }

    /**
     * 色は書いたとおりに戻る。
     *
     * **0 は書かない**——「取れなかった」を記録に残す必要は無く、
     * 欄が無いことがそのまま「取れなかった」を意味する。
     */
    @Test
    fun `色は往復する`() {
        val plays = listOf(
            Listening.Play(1L, "a", "x", "app", 1000L, tint = 0xFFE4572E.toInt()),
            Listening.Play(2L, "b", "y", "app", 1000L, tint = 0),
        )
        val back = Listening.parse(Listening.format(plays))
        assertEquals(0xFFE4572E.toInt(), back[0].tint)
        assertEquals(0, back[1].tint)
    }

    /** 古い記録（色の欄が無いもの）も読める。 */
    @Test
    fun `色の欄が無い記録も読める`() {
        val back = Listening.parse("""[{"t":1,"n":"a","a":"x","p":"app","d":1000}]""")
        assertEquals(1, back.size)
        assertEquals(0, back[0].tint)
    }

    /**
     * 私的な記録は**消さずに伏せる**。
     *
     * 消すと「今日どれだけ聴いたか」が嘘になる。
     * 名だけを一つに束ねれば、数は正しいまま中身は出ない。
     */
    @Test
    fun `私的な記録は名だけを伏せる`() {
        val plays = listOf(
            Listening.Play(1L, "ふつうの曲", "演者A", "app", 60_000L, tint = 0x11223344),
            Listening.Play(2L, "秘密の曲", "演者B", "priv", 60_000L, tint = 0x55667788, hidden = true),
        )
        val out = Listening.anonymised(plays)
        assertEquals(2, out.size)
        assertEquals("ふつうの曲", out[0].title)
        assertEquals(Listening.HIDDEN_NAME, out[1].title)
        assertEquals(Listening.HIDDEN_NAME, out[1].artist)
        assertEquals("", out[1].app)
        // 色も落とす——絵柄の色は、どの曲かを指し示してしまう。
        assertEquals(0, out[1].tint)
        // 時刻と長さは残る。**数は正しいまま。**
        assertEquals(2L, out[1].at)
        assertEquals(60_000L, out[1].durationMs)
    }

    @Test
    fun `音の面は私的な記録を落とす`() {
        val plays = listOf(
            Listening.Play(1L, "a", "x", "app", 1000L),
            Listening.Play(2L, "b", "y", "priv", 1000L, hidden = true),
        )
        val out = Listening.visible(plays)
        assertEquals(1, out.size)
        assertEquals("a", out[0].title)
    }

    @Test
    fun `伏せた印も往復する`() {
        val plays = listOf(Listening.Play(1L, "a", "x", "app", 1000L, hidden = true))
        assertEquals(true, Listening.parse(Listening.format(plays))[0].hidden)
        val open = listOf(Listening.Play(1L, "a", "x", "app", 1000L))
        assertEquals(false, Listening.parse(Listening.format(open))[0].hidden)
    }
}
