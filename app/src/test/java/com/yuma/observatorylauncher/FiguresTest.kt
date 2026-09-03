package com.yuma.observatorylauncher

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
}
