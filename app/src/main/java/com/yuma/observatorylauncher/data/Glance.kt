package com.yuma.observatorylauncher.data

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.time.ZoneOffset
import java.time.ZoneId
import java.time.LocalDate
import java.time.Instant
import android.os.Build
import android.os.CancellationSignal
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import androidx.annotation.RequiresApi
import android.location.Geocoder

/** 予定一件。表示に要る最小限だけを持つ。 */
data class Appointment(
    val title: String,
    val begin: Long,
    /** 終わり。時間軸に横棒で置くには、始まりだけでは足りない。 */
    val end: Long,
    val allDay: Boolean,
) {
    fun timeLabel(): String = if (allDay) {
        "終日"
    } else {
        SimpleDateFormat("HH:mm", Locale.JAPAN).format(Date(begin))
    }

    fun dayLabel(now: Long): String {
        val today = SimpleDateFormat("yyyyMMdd", Locale.JAPAN)
        val a = today.format(Date(now))
        val b = today.format(Date(begin))
        return if (a == b) "本日" else SimpleDateFormat("M/d", Locale.JAPAN).format(Date(begin))
    }
}

/**
 * 現在の気温と当日の幅。§N-5(±の併記)に従い、気温は幅・出典・日付のいずれかを伴わせる
 * ——ここでは最高/最低の幅を必ず添えるので、裸の数値を置かない。
 */
data class Weather(
    val temperature: Double,
    val high: Double,
    val low: Double,
    val precipitation: Int,
    val fetchedAt: Long,
    /**
     * 位置が取れず、既定の点（大阪）で引いたか。
     *
     * ⚠️ **これが無かった。** 注記には「出所を隠さないため画面側にその旨を出す」と
     * 書いてあったのに、それを載せる欄が無く、**仮の値と本物が同じ顔で並んでいた**
     * （2026/9/1 に気付いた）。
     *
     * 書いた意図は、欄を作って初めて実装になる。
     */
    val approximate: Boolean = false,
    /**
     * 空模様の言葉（「晴れ」「雨」など）。
     *
     * ⚠️ **数字だけでは天気が分からない。**
     * 「30° 24–31° 降水93%」を見ても、**今日は雨なのか晴れなのか**は答えられない
     * （本人の指摘・2026/9/2）。降水確率は「降るかもしれない度合い」であって、
     * 空の様子ではない。
     */
    val sky: String = "",
    /** 今日ひととおりの空模様。今この瞬間の [sky] とは別。 */
    val skyToday: String = "",
    /**
     * どこの空か。
     *
     * ⚠️ **出所が分からない数字は使えない。**
     * 位置から引いていることは書いてあっても、**どこの位置なのか**は
     * 画面に出ていなかった（本人の指摘・2026/9/2）。
     */
    val place: String = "",
    /** 値の出所（「気温 ○○（アメダス）で補正・降水 気象庁」）。空なら Open-Meteo のみ。 */
    val source: String = "",
    // ここから下は詳細の札だけが使う。同じ一度の問い合わせで一緒に受け取る——
    // 詳細のために二度目を投げるのは、送る回数を増やすだけで何の得も無い。
    val apparent: Double? = null,
    val humidity: Int? = null,
    val wind: Double? = null,
    val uv: Double? = null,
    val sunrise: String? = null,
    val sunset: String? = null,
    // ここから下は図のためのもの。**同じ一度の問い合わせで一緒に受け取る**——
    // 図のために二度目を投げるのは、送る回数を増やすだけで何の得も無い。
    /** 今から先の時刻ラベル（"HH"）。 */
    val hours: List<String> = emptyList(),
    /** 時刻ごとの気温。[hours] と同じ長さ。 */
    val hourlyTemp: List<Double> = emptyList(),
    /** 時刻ごとの降水確率(%)。[hours] と同じ長さ。 */
    val hourlyRain: List<Int> = emptyList(),
    /** 時刻ごとの湿度(%)。 */
    val hourlyHumidity: List<Int> = emptyList(),
    /** 時刻ごとの地上気圧(hPa)。 */
    val hourlyPressure: List<Double> = emptyList(),
)

/** 予定と天気の取得。画面はここを経由し、ContentResolver や通信を直接触らない。 */
object Glance {

    fun hasCalendarPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** 直近の予定を古い順に最大 [limit] 件。権限が無ければ空を返す(例外にしない)。 */
    suspend fun appointments(context: Context, limit: Int = 3): List<Appointment> =
        withContext(Dispatchers.IO) {
            if (!hasCalendarPermission(context)) return@withContext emptyList()
            val now = System.currentTimeMillis()
            val until = now + TimeUnit.DAYS.toMillis(7)
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
                ContentUris.appendId(it, now)
                ContentUris.appendId(it, until)
                it.build()
            }
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
            )
            runCatching {
                context.contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { cursor ->
                    val out = ArrayList<Appointment>()
                    while (cursor.moveToNext() && out.size < limit) {
                        val title = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "(無題)"
                        out += Appointment(
                            title = title,
                            begin = cursor.getLong(1),
                            end = cursor.getLong(2),
                            allDay = cursor.getInt(3) == 1,
                        )
                    }
                    out.toList()
                } ?: emptyList()
            }.getOrDefault(emptyList())
        }

    /**
     * 今日ぶんの予定。**時間軸に置くためのもの**なので、限度で切らない。
     *
     * 一覧の札は「次の三件」を出せば足りるが、時間軸は一日を丸ごと描く。
     * 途中で切ると、軸の右側だけが理由もなく空になる。
     *
     * 終日の予定は別に返す。**軸に置けない**——始まりも終わりも
     * 日の境界なので、横棒にすると一日を端から端まで塗ってしまい、
     * 他の予定が読めなくなる。見出しの脇に名前だけを出す。
     */
    suspend fun today(context: Context): Pair<List<Appointment>, List<Appointment>> =
        withContext(Dispatchers.IO) {
            if (!hasCalendarPermission(context)) return@withContext emptyList<Appointment>() to
                emptyList()
            val zone = ZoneId.systemDefault()
            val from = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            val to = from + TimeUnit.DAYS.toMillis(1)
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
                ContentUris.appendId(it, from)
                ContentUris.appendId(it, to)
                it.build()
            }
            val projection = arrayOf(
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY,
            )
            runCatching {
                context.contentResolver.query(
                    uri,
                    projection,
                    null,
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC",
                )?.use { cursor ->
                    val timed = ArrayList<Appointment>()
                    val allDay = ArrayList<Appointment>()
                    while (cursor.moveToNext()) {
                        val item = Appointment(
                            title = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: "(無題)",
                            begin = cursor.getLong(1),
                            end = cursor.getLong(2),
                            allDay = cursor.getInt(3) == 1,
                        )
                        if (item.allDay) allDay += item else timed += item
                    }
                    timed.toList() to allDay.toList()
                } ?: (emptyList<Appointment>() to emptyList())
            }.getOrDefault(emptyList<Appointment>() to emptyList())
        }

    /**
     * 天気は Open-Meteo(api.open-meteo.com・鍵不要)から取得する。
     * 送るのは粗い精度の緯度経度のみで、端末の識別子や利用者情報は送らない。
     * 位置が取れない場合は大阪(34.69,135.50)を仮に用いる——出所を隠さないため
     * 画面側にその旨を出す。
     */
    suspend fun weather(context: Context): Weather? = withContext(Dispatchers.IO) {
        // 面を切り替えるたびに取りに行かない。
        // 天気は分単位で変わるものではなく、**画面の都合で外へ問い合わせるのは筋が悪い**。
        // 直近の取得から[WEATHER_TTL]の間は同じ値を返す。
        // ⚠️ 概略の位置で引いた値は**短く持つ**。
        // 30分そのままにすると、測位が戻っても半時間は前の土地の空が出続ける。
        cached?.let {
            val ttl = if (it.approximate) APPROXIMATE_TTL else WEATHER_TTL
            if (System.currentTimeMillis() - it.fetchedAt < ttl) return@withContext it
        }
        val located = coarseLocation(context)
        val point = located ?: DEFAULT_POINT
        val url = URL(
            "https://api.open-meteo.com/v1/forecast" +
                "?latitude=%.2f&longitude=%.2f".format(point.first, point.second) +
                "&current=temperature_2m,apparent_temperature," +
                "relative_humidity_2m,wind_speed_10m,weather_code" +
                "&daily=temperature_2m_max,temperature_2m_min," +
                "precipitation_probability_max,uv_index_max,sunrise,sunset,weather_code" +
                // 時系列。**二日ぶん取って今から先だけを使う**——
                // 一日ぶんだと夜には残りが数点になり、線が引けない。
                "&hourly=temperature_2m,precipitation_probability," +
                "relative_humidity_2m,pressure_msl" +
                "&timezone=Asia%2FTokyo&forecast_days=2"
        )
        runCatching {
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val root = JSONObject(body)
            val daily = root.getJSONObject("daily")
            val current = root.getJSONObject("current")
            // 詳細の項目は**取れなかったら黙って落とす**。
            // 一項目の欠けで天気そのものが出なくなる方が困る。
            fun clock(field: String): String? = daily.optJSONArray(field)
                ?.optString(0)?.takeIf { it.length >= 16 }?.substring(11, 16)
            Weather(
                approximate = located == null,
                sky = skyOf(current.optInt("weather_code", -1)),
                skyToday = skyOf(
                    daily.optJSONArray("weather_code")?.optInt(0, -1) ?: -1,
                ),
                place = placeOf(context, point),
                temperature = current.getDouble("temperature_2m"),
                high = daily.getJSONArray("temperature_2m_max").getDouble(0),
                low = daily.getJSONArray("temperature_2m_min").getDouble(0),
                precipitation = daily.getJSONArray("precipitation_probability_max").optInt(0, 0),
                fetchedAt = System.currentTimeMillis(),
                apparent = current.optDouble("apparent_temperature").takeIf { !it.isNaN() },
                humidity = current.optInt("relative_humidity_2m", -1).takeIf { it >= 0 },
                wind = current.optDouble("wind_speed_10m").takeIf { !it.isNaN() },
                uv = daily.optJSONArray("uv_index_max")?.optDouble(0)?.takeIf { !it.isNaN() },
                sunrise = clock("sunrise"),
                sunset = clock("sunset"),
            ).withSeries(root.optJSONObject("hourly"))
        }.getOrNull()
            ?.let { base ->
                // 日本の中では、気象庁の実測と公式の予報を重ねる（[Jma] の注記）。
                if (Jma.inJapan(point.first, point.second)) withJma(context, base, point) else base
            }
            ?.also { cached = it }
    }

    /**
     * 時系列を切り出して足す。
     *
     * **今の時刻から先だけ**を採る。過ぎた時間の予報を並べても、
     * これからどうなるかは分からない。長さは[SERIES_HOURS]で頭打ちにする
     * ——二日ぶん全部を一枚に入れると、刻みが潰れて線の形が読めなくなる。
     *
     * 一件でも欠けたら図を出さない。**歯抜けの時系列は、無いより悪い。**
     */
    /**
     * 気象庁の値を重ねる。**今の気温・湿度はアメダスの実測、降水確率と今日の天気は公式の予報。**
     *
     * 降水の見出しは「今日これからの最大」（区間の終わりが今より後で、始まりが今日のうち）。
     * 一日の最大のままだと、夜に見ても朝の値が出る。今日が尽きていれば次の区間を出す。
     * 毎時の棒は、その時刻を含む 6 時間の区間の値を置く（気象庁が出すのは 6 時間ごとの値だけ）。
     */
    private fun withJma(context: Context, w: Weather, point: Pair<Double, Double>): Weather {
        val obs = Jma.observation(context, point.first, point.second)
        val fc = Jma.forecast(context, point.first, point.second)
        if (obs == null && fc == null) return w
        var out = w
        obs?.let { o ->
            // 毎時の気温（モデル）を実測に寄せる。**今の差を足し、先の時間ほど薄める**（6 時間で約 1/e）。
            // 寄せないと、見出しは実測 27°・図の線はモデルの 23° から始まり、同じ面で食い違う。
            val bias = o.temperature - out.temperature
            val corrected = out.hourlyTemp.mapIndexed { i, t -> t + bias * kotlin.math.exp(-i / 6.0) }
            out = out.copy(
                hourlyTemp = corrected,
                temperature = o.temperature,
                humidity = o.humidity ?: out.humidity,
                // 実測が予報の幅を越えたら、幅の方を広げる（「26° 18–25°」のような食い違いを出さない）。
                high = maxOf(out.high, o.temperature),
                low = minOf(out.low, o.temperature),
            )
        }
        fc?.let { f ->
            val now = System.currentTimeMillis()
            val sixHours = 6 * 3_600_000L
            val tomorrow = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Tokyo")).plusDays(1)
                .atStartOfDay(java.time.ZoneId.of("Asia/Tokyo")).toInstant().toEpochMilli()
            val blocks = f.pops.map { (start, pop) -> Triple(start, start + sixHours, pop) }
            val headline = blocks.filter { it.second > now && it.first < tomorrow }.maxOfOrNull { it.third }
                ?: blocks.firstOrNull { it.second > now }?.third
            val hourStart = now - now % 3_600_000L
            val hourly = out.hours.indices.map { i ->
                val t = hourStart + i * 3_600_000L + 1_800_000L
                blocks.firstOrNull { t >= it.first && t < it.second }?.third ?: out.hourlyRain.getOrElse(i) { 0 }
            }
            out = out.copy(
                precipitation = headline ?: out.precipitation,
                skyToday = f.weatherToday.ifBlank { out.skyToday },
                hourlyRain = if (out.hours.isEmpty()) out.hourlyRain else hourly,
            )
        }
        return out.copy(
            source = listOfNotNull(
                obs?.let { "気温 ${it.station}（アメダス）で補正" },
                fc?.let { "降水 気象庁" },
            ).joinToString("・"),
        )
    }

    private fun Weather.withSeries(hourly: JSONObject?): Weather {
        if (hourly == null) return this
        val times = hourly.optJSONArray("time") ?: return this
        val temps = hourly.optJSONArray("temperature_2m") ?: return this
        val rains = hourly.optJSONArray("precipitation_probability") ?: return this
        val nowHour = SimpleDateFormat("yyyy-MM-dd'T'HH", Locale.JAPAN).format(Date())
        var start = -1
        for (i in 0 until times.length()) {
            if (times.optString(i).startsWith(nowHour)) { start = i; break }
        }
        if (start < 0) return this
        // 湿度と気圧は**在れば添える**。無くても天気の図は成り立つので、
        // ここで欠けを理由に全体を落とさない。
        val hums = hourly.optJSONArray("relative_humidity_2m")
        // 海面気圧。地表気圧は標高のぶん低く出て、天気図・予報の値と揃わない（2026-09-25）。
        val press = hourly.optJSONArray("pressure_msl")
        val hours = ArrayList<String>()
        val t = ArrayList<Double>()
        val r = ArrayList<Int>()
        val h = ArrayList<Int>()
        val pa = ArrayList<Double>()
        var i = start
        while (i < times.length() && hours.size < SERIES_HOURS) {
            val label = times.optString(i).takeIf { it.length >= 13 } ?: break
            val temp = temps.optDouble(i)
            val rain = rains.optInt(i, -1)
            if (temp.isNaN() || rain < 0) break
            hours += label.substring(11, 13)
            t += temp
            r += rain
            hums?.optInt(i, -1)?.takeIf { it >= 0 }?.let { h += it }
            press?.optDouble(i)?.takeIf { !it.isNaN() }?.let { pa += it }
            i++
        }
        if (hours.size < 4) return this
        return copy(
            hours = hours,
            hourlyTemp = t,
            hourlyRain = r,
            hourlyHumidity = if (h.size == hours.size) h else emptyList(),
            hourlyPressure = if (pa.size == hours.size) pa else emptyList(),
        )
    }

    /** 時系列の長さ。半日ぶん。これ以上入れると刻みが潰れる。 */
    private const val SERIES_HOURS = 12

    /**
     * [from] から [to] までの、日ごとの予定の内訳（済・進行中・これから）。
     *
     * **終日の予定は UTC の真夜中で入っている。** 暦の提供者はそう定めているので、
     * 端末の時間帯で日付へ直すと、日本では一日ずれる（前日の朝九時に見える）。
     * 終日だけは UTC で日付へ直す。ここを間違えても例外は出ず、
     * **一日ずれた暦が平然と表示されるだけ**なので、明示して書いておく。
     *
     * 跨ぐ予定は跨いだ日すべてに数える。月の格子では「その日に何かある」ことが
     * 分かればよく、始まりの日だけを塗ると連休が一日に見えてしまう。
     * どの顔で数えるかは日ごとに判じる（[MonthView.stateOf]）。
     */
    internal suspend fun monthMarks(
        context: Context,
        from: LocalDate,
        to: LocalDate,
        now: Long = System.currentTimeMillis(),
    ): Map<LocalDate, MonthView.Marks> = withContext(Dispatchers.IO) {
        if (!hasCalendarPermission(context)) return@withContext emptyMap()
        val zone = ZoneId.systemDefault()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val begin = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, begin)
            ContentUris.appendId(it, end)
            it.build()
        }
        val projection = arrayOf(
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
        )
        runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val out = HashMap<LocalDate, MonthView.Marks>()
                while (cursor.moveToNext()) {
                    val beginsAt = cursor.getLong(0)
                    val endsAt = cursor.getLong(1)
                    val allDay = cursor.getInt(2) == 1
                    val at = if (allDay) ZoneOffset.UTC else zone
                    val first = Instant.ofEpochMilli(beginsAt).atZone(at).toLocalDate()
                    // 終わりの時刻は「その瞬間まで」なので、丁度真夜中なら前日で終わる。
                    val lastRaw = Instant.ofEpochMilli(endsAt - 1).atZone(at).toLocalDate()
                    val last = maxOf(first, lastRaw)
                    var day = maxOf(first, from)
                    val stop = minOf(last, to)
                    while (!day.isAfter(stop)) {
                        // 終日の予定は、その日の間ずっと進んでいるものとして扱う。
                        val state = if (allDay) {
                            MonthView.stateOf(day, today, Long.MIN_VALUE, Long.MAX_VALUE, now)
                        } else {
                            MonthView.stateOf(day, today, beginsAt, endsAt, now)
                        }
                        val held = out[day] ?: MonthView.Marks.NONE
                        out[day] = when (state) {
                            MonthView.State.DONE -> held.copy(done = held.done + 1)
                            MonthView.State.RUNNING -> held.copy(running = held.running + 1)
                            MonthView.State.AHEAD -> held.copy(ahead = held.ahead + 1)
                            // 暦から MARKED は出ない（利用者が付ける印である）。
                            MonthView.State.MARKED -> held
                        }
                        day = day.plusDays(1)
                    }
                }
                out
            }.orEmpty()
        }.getOrDefault(emptyMap())
    }

    /** 直近に取れた天気。端末が起きている間だけ持てばよいので、保存はしない。 */
    @Volatile
    private var cached: Weather? = null

    private const val WEATHER_TTL = 30 * 60 * 1000L

    /** 概略の位置で引いたときの保持。測位が戻ったら早く追いつくよう短くする。 */
    private const val APPROXIMATE_TTL = 5 * 60 * 1000L

    /**
     * 位置の鮮度。これより古ければ、測り直しを頼む。
     *
     * ⚠️ **`getLastKnownLocation` は「最後に誰かが測った値」であって、今の値ではない。**
     * 自分では測らないので、他のアプリがしばらく測っていなければ古いままになる。
     *
     * ふだんは地図なりが測っているので表に出ないが、
     * **遠方へ移ったその日**（例：山形の合宿）に、前に居た土地の空模様が出る。
     */
    private const val FIX_STALE_MS = 2 * 60 * 60 * 1000L

    /** 測り直しの待ち。空模様のために長く待たせない。 */
    private const val FIX_TIMEOUT_MS = 10_000L

    private suspend fun coarseLocation(context: Context): Pair<Double, Double>? {
        // ここで直接検査することで、権限が実行中に取り消された場合も
        // LocationManagerへ到達しないことを静的解析にも明示する。
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) return null
        val manager = context.getSystemService(LocationManager::class.java) ?: return null
        val last = runCatching {
            manager.getProviders(true)
                .asSequence()
                .mapNotNull { manager.getLastKnownLocation(it) }
                .maxByOrNull { it.time }
        }.getOrNull()
        val fresh = last != null && System.currentTimeMillis() - last.time < FIX_STALE_MS
        if (fresh && last != null) return last.latitude to last.longitude

        // 古い（または無い）ので、一度だけ測り直しを頼む。
        // ⚠️ 測り直しを頼む道（`getCurrentLocation`）は API 30 から。
        // それ以前の端末では**古い値のまま使う**——無いよりは古い方がまし。
        val measured = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            measureOnce(context, manager)
        } else {
            null
        }
        // 測り直せなければ、古くても在るものを使う。**無いよりは古い方がまし。**
        return (measured ?: last)?.let { it.latitude to it.longitude }
    }

    private val DEFAULT_POINT = 34.69 to 135.50
    /**
     * 一度だけ測り直しを頼む。
     *
     * **粗い精度の許可しか持たないので、頼める先も粗い提供元に限る**
     * ——GPS を名指しすると権限違反で落ちる。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun measureOnce(
        context: Context,
        manager: LocationManager,
    ): android.location.Location? {
        // ⚠️ 呼ぶ側でも検査しているが、ここでも検査する。
        // **静的解析は呼ぶ側の検査を見てくれない**し、
        // 権限が実行中に取り消される道もある（この体系の他の場所と同じ作法）。
        if (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) != PackageManager.PERMISSION_GRANTED
        ) return null
        val provider = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                runCatching {
                    manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)
                }.getOrDefault(false) -> LocationManager.FUSED_PROVIDER
            runCatching {
                manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        return withTimeoutOrNull(FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine<android.location.Location?> { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { runCatching { signal.cancel() } }
                runCatching {
                    manager.getCurrentLocation(
                        provider,
                        signal,
                        ContextCompat.getMainExecutor(context),
                    ) { location -> if (cont.isActive) cont.resume(location) }
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    /**
     * WMO の天気記号を日本語へ。
     *
     * Open-Meteo が返すのは世界気象機関の番号（`weather_code`）である。
     * 番号のままでは読めないので、**言葉にして初めて情報になる**。
     *
     * 取れなかった（-1）ときは空文字を返す——**分からない空を「晴れ」と書かない。**
     */
    fun skyOf(code: Int): String = when (code) {
        0 -> "快晴"
        1 -> "晴れ"
        2 -> "薄ぐもり"
        3 -> "くもり"
        45, 48 -> "霧"
        51, 53, 55 -> "霧雨"
        56, 57 -> "凍える霧雨"
        61 -> "小雨"
        63 -> "雨"
        65 -> "強い雨"
        66, 67 -> "凍える雨"
        71 -> "小雪"
        73 -> "雪"
        75 -> "大雪"
        77 -> "細雪"
        80 -> "にわか雨"
        81 -> "強いにわか雨"
        82 -> "激しいにわか雨"
        85, 86 -> "にわか雪"
        95 -> "雷雨"
        96, 99 -> "雹まじりの雷雨"
        else -> ""
    }

    /**
     * 座標を地名へ。
     *
     * 端末の `Geocoder` を使う。**外の鍵つきサービスへ出ない**——
     * 空模様の一行のために、もう一つ依存を増やす理由が無い。
     *
     * ⚠️ 取れないことがある（機内モード、対応していない端末）。
     * そのときは空文字。**分からない場所を推測で埋めない。**
     */
    private fun placeOf(context: Context, point: Pair<Double, Double>): String = runCatching {
        if (!Geocoder.isPresent()) return ""
        @Suppress("DEPRECATION")
        val found = Geocoder(context, Locale.JAPAN)
            .getFromLocation(point.first, point.second, 1)
            ?.firstOrNull() ?: return ""
        // 市区町村がいちばん役に立つ。無ければ広い方へ落とす。
        found.locality
            ?: found.subAdminArea
            ?: found.adminArea
            ?: ""
    }.getOrDefault("")

}
