package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.ui.FixedAxis
import com.yuma.observatorylauncher.ui.TICK_MINUTE
import com.yuma.observatorylauncher.ui.byteTickUnit
import com.yuma.observatorylauncher.ui.niceTicksIn
import com.yuma.observatorylauncher.ui.readableBytes
import com.yuma.observatorylauncher.ui.readableMinutes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 図に添える数字の試験。
 *
 * 単位の付け方は間違えても例外にならず、**桁が一つずれた数字**として
 * 平然と表示されるだけである。図の中で最も静かに嘘をつく場所なので、ここで押さえる。
 */
class FiguresTest {

    // ── 時間 ───────────────────────────────────────────────

    @Test
    fun minutesAreWrittenInJapanese() {
        assertEquals("0分", readableMinutes(0))
        assertEquals("3分", readableMinutes(3 * 60_000L))
        assertEquals("1時間0分", readableMinutes(60 * 60_000L))
        assertEquals("2時間52分", readableMinutes((2 * 60 + 52) * 60_000L))
    }

    /** 一分に満たない分は切り捨てる。**繰り上げると合計が実際より大きく出る。** */
    @Test
    fun secondsAreDropped() {
        assertEquals("0分", readableMinutes(59_000L))
        assertEquals("1分", readableMinutes(61_000L))
    }

    // ── 容量 ───────────────────────────────────────────────

    /** **基数は1024。** 1000で割ると端末の設定画面の数字と合わなくなる。 */
    @Test
    fun bytesUseBinaryUnits() {
        assertEquals("512B", readableBytes(512))
        assertEquals("1.0KB", readableBytes(1024))
        assertEquals("1.0MB", readableBytes(1024L * 1024))
        assertEquals("1.0GB", readableBytes(1024L * 1024 * 1024))
    }

    /** 三桁に届いたら小数を落とす。「126.0GB」の小数点以下は読む側に何も与えない。 */
    @Test
    fun theDecimalIsDroppedOnceItReachesThreeDigits() {
        assertEquals("99.5MB", readableBytes((99.5 * 1024 * 1024).toLong()))
        assertEquals("126GB", readableBytes((126.0 * 1024 * 1024 * 1024).toLong()))
    }

    /** 単位は必ず付く。裸の数字は読む側に前提を要求する。 */
    @Test
    fun everyValueCarriesItsUnit() {
        listOf(0L, 1L, 1023L, 1024L, Long.MAX_VALUE / 2).forEach {
            val text = readableBytes(it)
            assertEquals(text, true, text.any { c -> c.isLetter() })
        }
    }

    // ── 固定の軸（2026/9/25） ──────────────────────────────

    @Test
    fun `降水確率の軸は0から100で動かない`() {
        assertEquals(listOf(0f, 20f, 40f, 60f, 80f, 100f), FixedAxis.PERCENT.ticks)
        assertEquals(0.3f, FixedAxis.PERCENT.fraction(30f), 1e-6f)
    }

    @Test
    fun `気温の軸は10度刻みで降水の軸と目盛り線が重なる`() {
        assertEquals(listOf(-10f, 0f, 10f, 20f, 30f, 40f), FixedAxis.TEMPERATURE.ticks)
        assertEquals(FixedAxis.PERCENT.ticks.size, FixedAxis.TEMPERATURE.ticks.size)
        assertEquals(FixedAxis.PERCENT.ticks.size, FixedAxis.PRESSURE.ticks.size)
        // 20℃ は下から 3/5、降水 60% の線と同じ高さ。
        assertEquals(FixedAxis.PERCENT.fraction(60f), FixedAxis.TEMPERATURE.fraction(20f), 1e-6f)
    }

    /** 軸の外の値は端に留める。**図の外へはみ出して他の札に描かない。** */
    @Test
    fun `軸の外の値は端に留まる`() {
        assertEquals(1f, FixedAxis.TEMPERATURE.fraction(43f), 0f)
        assertEquals(0f, FixedAxis.TEMPERATURE.fraction(-15f), 0f)
    }

    // ── 表示の単位で刻む（2026/9/25） ────────────────────────

    /** ミリ秒のまま刻むと 0・41・83・125 分になっていた。**分で刻めば 0・50・100・150 分。** */
    @Test
    fun `使用時間の目盛りは分で切りよく刻む`() {
        val ticks = niceTicksIn(125 * 60_000f, TICK_MINUTE).map { (it / 60_000f).toInt() }
        assertEquals(listOf(0, 50, 100, 150), ticks)
    }

    @Test
    fun `バイトの目盛りは大きさに合った単位で刻む`() {
        assertEquals(1f, byteTickUnit(500f), 0f)
        assertEquals(1024f * 1024f, byteTickUnit(300f * 1024 * 1024), 0f)
        assertEquals(1024f * 1024f * 1024f, byteTickUnit(3f * 1024 * 1024 * 1024), 0f)
    }
}
