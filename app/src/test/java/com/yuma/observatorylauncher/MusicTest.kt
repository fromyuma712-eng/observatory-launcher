package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.data.Music
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 再生位置と時間の表し方の試験。
 *
 * ここも**間違えても例外にならない**類である。針が止まって見える、
 * 秒の桁が跳ねる、長さの分からない曲に嘘の針が立つ——
 * どれも「なんとなく変」としてしか現れない。
 */
class MusicTest {

    /** 秒は必ず二桁。桁が揺れると数字が跳ねて読みにくい。 */
    @Test
    fun theClockKeepsTwoDigitSeconds() {
        assertEquals("0:05", Music.clock(5_000))
        assertEquals("1:00", Music.clock(60_000))
        assertEquals("3:07", Music.clock(187_000))
        assertEquals("59:59", Music.clock(3_599_000))
    }

    /** 一時間を超えたら時から出す。 */
    @Test
    fun longThingsGetAnHourField() {
        assertEquals("1:00:00", Music.clock(3_600_000))
        assertEquals("2:03:04", Music.clock(7_384_000))
    }

    /** 分からない長さは、分からないと出す。0:00 と書くと嘘になる。 */
    @Test
    fun anUnknownLengthIsSaidToBeUnknown() {
        assertEquals("--:--", Music.clock(-1))
    }

    /** 長さが分からなければ目盛りに針を立てない。 */
    @Test
    fun noHandWithoutALength() {
        val unknown = Music.Playing("a", "b", "c", "com.example", position = 1_000, duration = -1, playing = true)
        assertNull(unknown.progress)
        val unstarted = Music.Playing("a", "b", "c", "com.example", position = -1, duration = 1_000, playing = true)
        assertNull(unstarted.progress)
    }

    /** 割合は 0〜1 に収める。位置が長さを超えても針は端で止まる。 */
    @Test
    fun theHandStaysOnTheScale() {
        val over = Music.Playing("a", "b", "c", "com.example", position = 9_000, duration = 3_000, playing = true)
        assertEquals(1f, over.progress!!, 0.0001f)
        val half = Music.Playing("a", "b", "c", "com.example", position = 1_500, duration = 3_000, playing = true)
        assertEquals(0.5f, half.progress!!, 0.0001f)
    }

    /** 状態が無ければ位置も無い。 */
    @Test
    fun withoutAStateThereIsNoPosition() {
        assertEquals(-1, Music.positionAt(null, 1_000))
    }

    private fun seat(hasContent: Boolean, playing: Boolean = false) =
        Music.Seat(hasContent = hasContent, playing = playing)

    /**
     * どの席を採るか。**並べ替えず、候補を絞ってから選ぶ。**
     *
     * 昔「鳴っているものを優先」という自前の順を作って失敗した
     * （電話の席が音楽より上に来た）。今回入れ直した規則は、
     * **先に「出せるものがある席」だけへ絞る**ので、その失敗を踏まない
     * ——電話の席は題も演者も持たず、絞りの時点で落ちる。
     */
    @Test
    fun thePlatformOrderIsRespected() {
        // 先頭に出せるものがあり、どれも鳴っていなければ先頭。
        assertEquals(0, Music.chooseIndex(listOf(seat(true), seat(true), seat(true))))
        // 出すものを持たない席（電話など）は飛ばす。
        assertEquals(1, Music.chooseIndex(listOf(seat(false), seat(true), seat(true))))
        assertEquals(2, Music.chooseIndex(listOf(seat(false), seat(false), seat(true))))
    }

    /**
     * ⚠️ 実機で **YouTube Music（再生中）と Apple Music（停止中・題あり）**が
     * 同時に生きていた（2026/9/1）。**鳴っている方を採る。**
     */
    @Test
    fun theSeatThatIsPlayingWins() {
        assertEquals(
            1,
            Music.chooseIndex(
                listOf(
                    seat(hasContent = true, playing = false),
                    seat(hasContent = true, playing = true),
                ),
            ),
        )
    }

    /** 鳴っていても出すものが無ければ候補にしない——**電話の席を拾わないための線**。 */
    @Test
    fun aSeatWithoutContentIsNeverChosen() {
        assertEquals(
            1,
            Music.chooseIndex(
                listOf(
                    seat(hasContent = false, playing = true),
                    seat(hasContent = true, playing = false),
                ),
            ),
        )
    }

    /** どの席も出すものを持たなければ、出さない。 */
    @Test
    fun nothingToShowMeansNothing() {
        assertNull(Music.chooseIndex(listOf(seat(false), seat(false))))
        assertNull(Music.chooseIndex(emptyList()))
    }

    /**
     * 切替の名前から状態を読む。
     *
     * ⚠️ **これは規約ではない。** framework に「今シャッフルか」を訊く道は無く、
     * 実機の YouTube Music が表示名に書いているのを読んでいるだけである。
     * **読めなければ「切」ではなく「読めない」を返す**こと。
     */
    @Test
    fun theStateIsReadFromTheLabelWhenItIsThere() {
        val (label, state, text) = Music.splitLabel("シャッフル（オフ）")
        assertEquals("シャッフル", label)
        assertEquals(Music.Switch.OFF, state)
        assertEquals("オフ", text)
        assertEquals(Music.Switch.ON, Music.splitLabel("リピート（オン）").second)
        assertEquals(Music.Switch.ON, Music.splitLabel("Shuffle (on)").second)
    }

    @Test
    fun anUnreadableStateIsNotCalledOff() {
        // 括弧はあるが中身が知らない言葉——**言葉は出すが、状態は主張しない。**
        val (label, state, text) = Music.splitLabel("リピート（1曲）")
        assertEquals("リピート", label)
        assertEquals(Music.Switch.UNKNOWN, state)
        assertEquals("1曲", text)
    }

    @Test
    fun aPlainActionIsNotAToggle() {
        // 括弧が無いものは切替ではない（「高く評価」など）。
        val (label, state, text) = Music.splitLabel("高く評価")
        assertEquals("高く評価", label)
        assertEquals(Music.Switch.UNKNOWN, state)
        assertNull(text)
    }

    /**
     * **時間の軸を取り違えると位置が暴走する。**
     *
     * `lastPositionUpdateTime` は `SystemClock.elapsedRealtime()` の軸である。
     * 壁時計を渡すと経過が数十年になり、`3:37` の曲に `487654:39:20` と出て、
     * その一行が横へ広がって隣の操作の位置までずれる（実機でそうなった）。
     * **例外は出ない**ので、ここで押さえる。
     */
    @Test
    fun theElapsedClockIsNotTheWallClock() {
        val updatedAt = 456_554_360L // 起動からの経過（elapsedRealtime）の軸
        // 正しい軸：三秒後なら三秒進む。
        assertEquals(
            3_000,
            Music.advance(0, updatedAt, 1f, playing = true, elapsed = updatedAt + 3_000),
        )
        // 壁時計を渡すと桁違いになる（＝この試験が守っている誤り）。
        val wall = 1_756_522_800_000L
        assertTrue(Music.advance(0, updatedAt, 1f, playing = true, elapsed = wall) > 1_000_000_000L)
    }

    /** 止まっているものは進めない。 */
    @Test
    fun aPausedThingDoesNotAdvance() {
        assertEquals(
            130_000,
            Music.advance(130_000, 1_000, 1f, playing = false, elapsed = 999_000),
        )
    }

    /** 更新の時刻より前を渡されても巻き戻さない。 */
    @Test
    fun itNeverRunsBackwards() {
        assertEquals(5_000, Music.advance(5_000, 10_000, 1f, playing = true, elapsed = 9_000))
    }

    /** 速さを掛ける。倍速なら倍進む。 */
    @Test
    fun theRateIsApplied() {
        assertEquals(2_000, Music.advance(0, 0, 2f, playing = true, elapsed = 1_000))
        // 速さが申告されていなければ等速とみなす。
        assertEquals(1_000, Music.advance(0, 0, 0f, playing = true, elapsed = 1_000))
    }

    /** 位置が分からないものは、進めても分からないまま。 */
    @Test
    fun anUnknownPositionStaysUnknown() {
        assertEquals(-1, Music.advance(-1, 0, 1f, playing = true, elapsed = 9_999))
    }
}
