package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.data.Charge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 充電の実測の試験。
 *
 * 実機がなければ `BatteryManager` は触れないので、**端末に依らない部分を切り出して**
 * ここで押さえる。符号の扱いと記録の畳み方は、間違えても例外にならず、
 * 「なんとなく変な図」として出てくるだけなので、試験で留めるしかない。
 */
class ChargeTest {

    // ── 電流の符号 ─────────────────────────────────────────

    /** 端末が正で返そうと負で返そうと、充電中は正になる。 */
    @Test
    fun theSignComesFromTheChargingState() {
        assertEquals(1500, Charge.signed(1500, charging = true))
        assertEquals(1500, Charge.signed(-1500, charging = true))
        assertEquals(-420, Charge.signed(420, charging = false))
        assertEquals(-420, Charge.signed(-420, charging = false))
    }

    /** 0 は向きを持たない。 */
    @Test
    fun zeroHasNoDirection() {
        assertEquals(0, Charge.signed(0, charging = true))
        assertEquals(0, Charge.signed(0, charging = false))
    }

    // ── 表示 ───────────────────────────────────────────────

    /** 充電中は符号を明示する。向きが消えると充放電の区別がつかない。 */
    @Test
    fun theAmpLabelKeepsItsSign() {
        assertEquals("+1500mA", Charge.ampLabel(1500))
        assertEquals("-420mA", Charge.ampLabel(-420))
        assertEquals("—", Charge.ampLabel(null))
    }

    /** 電流か電圧が欠ければ電力は出さない。片方を仮定した数字は推測である。 */
    @Test
    fun thePowerIsOmittedWhenAnyFactorIsMissing() {
        val complete = reading(milliAmp = 1500, volt = 4.2)
        assertEquals(6.3, complete.watt!!, 0.01)
        assertNull(reading(milliAmp = null, volt = 4.2).watt)
        assertNull(reading(milliAmp = 1500, volt = null).watt)
    }

    /** 放電中でも電力の大きさは正。向きは電流が持つ。 */
    @Test
    fun thePowerIsAMagnitude() {
        assertEquals(1.68, reading(milliAmp = -400, volt = 4.2).watt!!, 0.01)
    }

    // ── 記録 ───────────────────────────────────────────────

    private val t0 = 1_756_000_000_000L

    /** 書いて読めば元に戻る。 */
    @Test
    fun theLogRoundTrips() {
        val samples = listOf(
            Charge.Sample(t0, 80, 1500),
            Charge.Sample(t0 + Charge.INTERVAL_MS, 82, null),
        )
        val back = Charge.parse(Charge.format(samples))
        assertEquals(samples, back)
    }

    /** 壊れた記録は空として扱う。**一箇所の傷で面を壊さない。** */
    @Test
    fun aBrokenLogIsTreatedAsEmpty() {
        assertTrue(Charge.parse("").isEmpty())
        assertTrue(Charge.parse("{壊れている").isEmpty())
        // 形は正しいが中身が足りないものは、その一件だけ落ちる。
        assertEquals(1, Charge.parse("""[{"t":0,"p":50},{"t":$t0,"p":50}]""").size)
    }

    /** 間隔が空いていなければ足さない。 */
    @Test
    fun samplesTooCloseTogetherAreDropped() {
        val first = listOf(Charge.Sample(t0, 80, null))
        val tooSoon = Charge.appended(first, Charge.Sample(t0 + 60_000, 79, null))
        assertEquals(first, tooSoon)

        val later = Charge.appended(first, Charge.Sample(t0 + Charge.INTERVAL_MS, 79, null))
        assertEquals(2, later.size)
    }

    /** 一日より古いものは落ちる。 */
    @Test
    fun theLogKeepsOnlyOneDay() {
        val old = Charge.Sample(t0, 100, null)
        val fresh = Charge.Sample(t0 + Charge.KEEP_MS + 60_000, 40, null)
        val kept = Charge.appended(listOf(old), fresh)
        assertEquals(listOf(fresh), kept)
    }

    /** **時計が巻き戻ったら、先の分を捨てる。** 並びが前後すると折れ線が折り返す。 */
    @Test
    fun futureSamplesAreDroppedWhenTheClockGoesBack() {
        val ahead = listOf(
            Charge.Sample(t0, 80, null),
            Charge.Sample(t0 + 2 * Charge.INTERVAL_MS, 78, null),
        )
        val back = Charge.appended(ahead, Charge.Sample(t0 + Charge.INTERVAL_MS, 79, null))
        assertEquals(listOf(ahead.first(), Charge.Sample(t0 + Charge.INTERVAL_MS, 79, null)), back)
        // 並びは常に時の順である。
        back.zipWithNext { a, b -> assertTrue(a.at <= b.at) }
    }

    /** 記録は必ず時の順に並ぶ。 */
    @Test
    fun theLogStaysOrdered() {
        var log = emptyList<Charge.Sample>()
        repeat(10) { i ->
            log = Charge.appended(log, Charge.Sample(t0 + i * Charge.INTERVAL_MS, 90 - i, null))
        }
        assertEquals(10, log.size)
        log.zipWithNext { a, b -> assertTrue(a.at < b.at) }
    }

    private fun reading(milliAmp: Int?, volt: Double?) = Charge.Reading(
        percent = 80,
        charging = milliAmp != null && milliAmp > 0,
        full = false,
        milliAmp = milliAmp,
        volt = volt,
        celsius = null,
        chargeMah = null,
        toFullMs = null,
    )

    // ── 満充電までの見込み ─────────────────────────────────

    /** 実機で端末が「7時間47分」と返した状態を、自前で出し直すと一時間強になる。 */
    @Test
    fun theEstimateMatchesTheMeasuredState() {
        // 49% / 2020mAh / +1780mA → 満容量 4122mAh・残り 2102mAh・1.181時間＝70分。
        // 端末の申告(7時間47分)の**六分の一**である。
        val ms = Charge.estimate(percent = 49, chargeMah = 2020, milliAmp = 1780)!!
        assertEquals(70, ms / 60_000)
    }

    /** 放電中は、今ある電荷を電流で割る。 */
    @Test
    fun dischargingEstimatesTimeToEmpty() {
        val ms = Charge.estimate(percent = 50, chargeMah = 2000, milliAmp = -400)!!
        assertEquals(5 * 60, ms / 60_000)
    }

    /** 材料が欠けたら出さない。片方を仮定した時間は数字の顔をした推測である。 */
    @Test
    fun theEstimateNeedsEveryFactor() {
        assertNull(Charge.estimate(49, null, 1780))
        assertNull(Charge.estimate(49, 2020, null))
        assertNull(Charge.estimate(49, 0, 1780))
        assertNull(Charge.estimate(49, 2020, 0))
    }

    /** 満と空では割り出せない——0で割るか、残りが0になる。 */
    @Test
    fun theEndsOfTheRangeHaveNoEstimate() {
        assertNull(Charge.estimate(100, 4000, 200))
        assertNull(Charge.estimate(0, 0, -400))
    }

    /** ⚠️ **小さすぎる電流で割らない。** 実機で +1mA を拾い「783時間」と出た。 */
    @Test
    fun aTinyCurrentProducesNoEstimate() {
        assertNull(Charge.estimate(percent = 81, chargeMah = 3342, milliAmp = 1))
        assertNull(Charge.estimate(percent = 81, chargeMah = 3342, milliAmp = 49))
        // 50mA からは割る。
        assertEquals(true, Charge.estimate(81, 3342, 50) != null)
    }

    /** 一日を超える見込みは出さない。当たっていても役に立たない。 */
    @Test
    fun anEstimateBeyondADayIsNotShown() {
        // 50mA で 780mAh 残っていれば 15.6時間＝出す。
        assertEquals(true, Charge.estimate(81, 3342, 800) != null)
        // 残り 2000mAh を 60mA では 33時間＝出さない。
        assertNull(Charge.estimate(50, 2000, 60))
    }
}
