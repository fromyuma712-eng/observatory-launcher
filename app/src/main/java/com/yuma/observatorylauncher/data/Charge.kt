package com.yuma.observatorylauncher.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 充電の実測。
 *
 * 既存の電池の札は「82% 充電中／温度 31.2℃／完了まで約45分」までは出せる。
 * **出せていないのは、今どれくらいの速さで入っているか**である。
 * 電流と電圧は端末が持っているのに、設定画面にも出てこない
 * ——取り出すには開発者向けの経路が要る。この体系が引き受けるべき値の典型である。
 *
 * ## ⚠️ 電流の符号は端末ごとに違う
 *
 * `BATTERY_PROPERTY_CURRENT_NOW` は「充電中は正・放電中は負」が建前だが、
 * **逆に返す実装がある**（メーカー差）。値の符号だけを信じると、
 * 充電中に「-1500mA」と出て、放電しているように見える。
 *
 * そこで**符号は充電状態から決め直す**。大きさだけを端末から受け取り、
 * 向きは `BATTERY_STATUS_CHARGING` かどうかで与える。これなら実装差に依らない。
 */
internal object Charge {

    /** 今の一枚。取れなかった項目は null——**0 で埋めない**（0mA は「流れていない」という主張になる）。 */
    data class Reading(
        val percent: Int,
        val charging: Boolean,
        val full: Boolean,
        /** ミリアンペア。充電中は正、放電中は負。 */
        val milliAmp: Int?,
        val volt: Double?,
        val celsius: Double?,
        /** 電池に溜まっている電荷(mAh)。 */
        val chargeMah: Int?,
        /** 満充電までの見込み(ミリ秒)。放電中は空になるまでの見込み。 */
        val toFullMs: Long?,
    ) {
        /** ワット。電流か電圧が欠ければ出さない——片方を仮定した電力は数字の顔をした推測である。 */
        val watt: Double?
            get() {
                val a = milliAmp ?: return null
                val v = volt ?: return null
                return kotlin.math.abs(a) / 1000.0 * v
            }
    }

    fun now(context: Context): Reading? {
        val state = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val manager = context.getSystemService(BatteryManager::class.java) ?: return null

        val level = state?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = state?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        if (percent < 0) return null

        val status = state?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val full = status == BatteryManager.BATTERY_STATUS_FULL
        // ⚠️ **満充電も「放電していない」側に数える。**
        //
        // `BATTERY_STATUS_FULL` を外していたので、100%で繋いだままのとき
        // 向きが放電になり、面に**「満充電  −1mA」**と並んだ（2026/9/2 の点検）。
        // 端末の符号は信用しないと決めた以上、向きはこちらで筋を通す。
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || full

        // **今の値と均した値を分けて使う。**
        // 表示は今の値（それが「今どれくらい入っているか」だから）、
        // 残り時間の計算は均した値を優先する——瞬間値は一瞬の凪を拾うことがあり、
        // 実機で +1mA を拾って「満まで783時間」と出た（2026/9/1）。
        val nowMicro = property(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val averageMicro = property(manager, BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val milliAmp = (nowMicro ?: averageMicro)?.let { signed(it / 1000, charging) }
        val steadyMilliAmp = (averageMicro ?: nowMicro)?.let { signed(it / 1000, charging) }

        val volt = state?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }?.let { it / 1000.0 }
        val celsius = state?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE && it > -1000 }?.let { it / 10.0 }
        val chargeMah = property(manager, BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            ?.let { it / 1000 }
        // ⚠️ **端末の見積りは当てにしない。**
        // `computeChargeTimeRemaining()` は 49%・2020mAh・1780mA の状態で
        // 「7時間47分」を返した（実機・2026/9/1）。実際は一時間強である。
        // 電荷と電流という**実測値が二つ揃っている**のだから、自分で割る方が確かで、
        // しかもなぜその数字なのかを説明できる。端末の値は最後の手段に回す。
        val estimated = estimate(percent, chargeMah, steadyMilliAmp)
        val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { manager.computeChargeTimeRemaining() }.getOrNull()?.takeIf { it > 0 }
        } else {
            null
        }

        return Reading(
            percent, charging, full, milliAmp, volt, celsius, chargeMah,
            estimated ?: fallback,
        )
    }

    /**
     * 満充電まで／空になるまでの見込み。
     *
     * 電荷(mAh)と残量(%)から満容量を割り出し、残りを電流(mA)で割る。
     *
     * ```
     * 満容量 = 電荷 ÷ (残量/100)
     * 充電中: (満容量 - 電荷) ÷ 電流
     * 放電中: 電荷 ÷ |電流|
     * ```
     *
     * **⚠️ これは直線の見積りである。** 充電の電流は満に近づくほど絞られるので、
     * 終盤は実際より短く出る。だから画面では「約」を付ける
     * ——精度の限界を数字の見た目で隠さない。
     *
     * 材料が欠けたら null。**片方を仮定して出した時間は、数字の顔をした推測である。**
     */
    fun estimate(percent: Int, chargeMah: Int?, milliAmp: Int?): Long? {
        if (percent !in 1..99) return null
        val charge = chargeMah?.takeIf { it > 0 } ?: return null
        // ⚠️ **小さすぎる電流で割らない。**
        // 満に近づくと電流は絞られ、一瞬 +1mA のような値を拾う。
        // それで割ると「満まで783時間」になる（実機で出た）。
        // 割り算が壊れているのではなく、**割る資格の無い値を渡している**。
        val amp = milliAmp?.takeIf { kotlin.math.abs(it) >= MIN_AMP } ?: return null
        val fullMah = charge * 100.0 / percent
        val remaining = if (amp > 0) fullMah - charge else charge.toDouble()
        if (remaining <= 0) return null
        val hours = remaining / kotlin.math.abs(amp)
        val ms = (hours * 3_600_000).toLong()
        // 一日を超える見込みは、当たっていても役に立たない。出さない方が正直である。
        return ms.takeIf { it > 0 && it <= LIMIT_MS }
    }

    /** これ未満の電流では割らない。絞られた終盤の瞬間値を掴んでも意味が無い。 */
    private const val MIN_AMP = 50

    /** これを超える見込みは出さない。一日以上かかる充電も放電も、見込みとして役に立たない。 */
    private const val LIMIT_MS = 24 * 60 * 60_000L

    /**
     * 端末が返した大きさに、充電状態から決めた向きを与える。
     *
     * **端末の符号は信用しない。** 逆に返す実装があるので、
     * 大きさ（絶対値）だけを受け取り、向きはこちらで決める。
     */
    fun signed(rawMilliAmp: Int, charging: Boolean): Int {
        val size = kotlin.math.abs(rawMilliAmp)
        return if (charging) size else -size
    }

    private fun property(manager: BatteryManager, id: Int): Int? =
        runCatching { manager.getIntProperty(id) }
            .getOrNull()
            // 取れないときは Int.MIN_VALUE か 0 が返る。0 も「取れなかった」側に倒す
            // ——本当に 0mA なら、それは満充電か完全停止であって、図に線を引く値ではない。
            ?.takeIf { it != Int.MIN_VALUE && it != 0 }

    /** 電流を読む形へ。**符号を残す**——向きが消えると充放電の区別がつかない。 */
    fun ampLabel(milliAmp: Int?): String = when {
        milliAmp == null -> "—"
        milliAmp >= 0 -> "+${milliAmp}mA"
        else -> "${milliAmp}mA"
    }

    /** 電力を読む形へ。 */
    fun wattLabel(watt: Double?): String = if (watt == null) "—" else "%.1fW".format(watt)

    // ---- 推移の記録 --------------------------------------------------------

    /**
     * 残量の推移。
     *
     * 一枚の値だけでは「今どうか」しか答えられない。
     * **一日でどう減ったか**は、記録しなければ誰も答えられない
     * ——端末は残量の履歴を外へ出さないからである。
     *
     * ## 置き場所と大きさ
     *
     * 宣言（`layout.json`）には**混ぜない**。宣言は利用者の持ち物で、世代に残り、
     * 控えとして書き出される。毎分書き換わる測定値をそこへ混ぜると、
     * 「元に戻す」が測定値の巻き戻しになってしまう。別のファイルに置く。
     *
     * 保つのは一日分だけ。古いものから捨てる。
     */
    data class Sample(val at: Long, val percent: Int, val milliAmp: Int?)

    const val LOG_NAME = "battery-log.json"

    /** 最短の記録間隔。これより短い間隔で書いても、図の解像度は上がらない。 */
    const val INTERVAL_MS = 5 * 60_000L

    /** 保つ長さ。一日。 */
    const val KEEP_MS = 24 * 60 * 60_000L

    fun parse(text: String): List<Sample> = runCatching {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val at = o.optLong("t", 0L)
            if (at <= 0L) return@mapNotNull null
            Sample(
                at = at,
                percent = o.optInt("p", -1).takeIf { it in 0..100 } ?: return@mapNotNull null,
                milliAmp = if (o.has("a")) o.optInt("a") else null,
            )
        }
    }.getOrDefault(emptyList())

    fun format(samples: List<Sample>): String {
        val array = JSONArray()
        samples.forEach { s ->
            array.put(
                JSONObject().apply {
                    put("t", s.at)
                    put("p", s.percent)
                    s.milliAmp?.let { put("a", it) }
                },
            )
        }
        return array.toString()
    }

    /**
     * 一枚を足す。**間隔が空いていなければ足さない**し、古いものは落とす。
     * 時計が巻き戻った（時刻合わせ・時間帯の変更）場合は、先の分を捨てる
     * ——並びが前後すると折れ線が折り返して読めなくなる。
     */
    fun appended(existing: List<Sample>, sample: Sample): List<Sample> {
        val cleaned = existing.filter { it.at <= sample.at && sample.at - it.at <= KEEP_MS }
        val last = cleaned.lastOrNull()
        if (last != null && sample.at - last.at < INTERVAL_MS) return cleaned
        return cleaned + sample
    }

    fun read(context: Context): List<Sample> {
        val file = File(context.filesDir, LOG_NAME)
        if (!file.exists()) return emptyList()
        return parse(runCatching { file.readText() }.getOrDefault(""))
    }

    /** 書き込む。失敗しても黙って諦める——記録が取れないことは、面を壊す理由にならない。 */
    fun write(context: Context, samples: List<Sample>) {
        runCatching { File(context.filesDir, LOG_NAME).writeText(format(samples)) }
    }

    /** 今を一枚記録して、記録全体を返す。 */
    fun record(context: Context, reading: Reading, at: Long = System.currentTimeMillis()):
        List<Sample> {
        val next = appended(read(context), Sample(at, reading.percent, reading.milliAmp))
        write(context, next)
        return next
    }
}
