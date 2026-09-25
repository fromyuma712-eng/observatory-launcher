package com.yuma.observatorylauncher.data

import android.content.Context
import android.location.Geocoder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 気象庁の公式データ。**日本の中では、実測と公式の予報を Open-Meteo より優先する。**
 *
 * ## ⚠️ なぜ重ねたか（2026-09-25 の突き合わせ）
 *
 * 同じ地点・同じ時刻で比べた。
 *
 * | | Open-Meteo（それまでの出所） | 気象庁 |
 * | --- | --- | --- |
 * | 今の気温 | 23.7℃ | **26.5℃**（最寄りのアメダスの実測） |
 * | 降水確率 | 今日 2%・「この先 12 時間は低いまま」 | **今夜 20%・明朝 70%・明日昼 80%** |
 *
 * 気温は 2〜3℃ 低く、翌日の雨がほぼ消えていた。モデルを気象庁のもの（`jma_seamless`）に
 * 替えても値は同じで、降水確率はそもそも出ない。**モデルの選び直しでは直らない**ので、
 * 実測（アメダス）と公式の予報（6 時間ごとの降水確率）を重ねる。
 *
 * - 取れなければ、何もせず Open-Meteo の値のまま返す（面は壊さない）。
 * - 観測点の一覧と予報区の表は大きく、滅多に変わらないので端末に 30 日置く。
 */
internal object Jma {
    data class Observation(val station: String, val temperature: Double, val humidity: Int?)

    /** @param pops 6 時間ごとの降水確率（区間の始まり・%）。 */
    data class Forecast(val area: String, val weatherToday: String, val pops: List<Pair<Long, Int>>)

    fun inJapan(lat: Double, lon: Double): Boolean = lat in 24.0..46.0 && lon in 122.0..154.0

    // ---- 実測 --------------------------------------------------------------

    /** 最寄りの（気温を測っている）アメダスの、最新の実測。 */
    fun observation(context: Context, lat: Double, lon: Double): Observation? = runCatching {
        val table = JSONObject(cached(context, "amedastable.json", AMEDAS_TABLE) ?: return null)
        var best: Pair<String, Double>? = null
        table.keys().forEach { code ->
            val s = table.getJSONObject(code)
            if (!s.optString("elems").startsWith("1")) return@forEach // 気温を測っていない
            val sLat = degrees(s.getJSONArray("lat"))
            val sLon = degrees(s.getJSONArray("lon"))
            val d = hypot(sLat - lat, (sLon - lon) * cos(Math.toRadians(lat)))
            if (best == null || d < best!!.second) best = code to d
        }
        val code = best?.first ?: return null
        val name = table.getJSONObject(code).optString("kjName")
        // 三時間ごとの束（YYYYMMDD_HH）。束の頭は中身がまだ無いことがあるので、一つ前も見る。
        val jst = TimeZone.getTimeZone("Asia/Tokyo")
        val now = System.currentTimeMillis()
        for (back in 0..1) {
            val at = now - back * 3 * 3_600_000L
            val day = SimpleDateFormat("yyyyMMdd", Locale.JAPAN).apply { timeZone = jst }.format(Date(at))
            val hour = SimpleDateFormat("HH", Locale.JAPAN).apply { timeZone = jst }.format(Date(at)).toInt() / 3 * 3
            val body = get("$AMEDAS_POINT/$code/${day}_%02d.json".format(hour)) ?: continue
            val records = JSONObject(body)
            val latest = records.keys().asSequence()
                .filter { records.getJSONObject(it).optJSONArray("temp")?.isNull(0) == false }
                .maxOrNull() ?: continue
            val r = records.getJSONObject(latest)
            return Observation(
                station = name,
                temperature = r.getJSONArray("temp").getDouble(0),
                humidity = r.optJSONArray("humidity")?.takeIf { !it.isNull(0) }?.getInt(0),
            )
        }
        null
    }.getOrNull()

    // ---- 予報 --------------------------------------------------------------

    /** その地点の予報区（一次細分区域）の、今日の天気と 6 時間ごとの降水確率。 */
    fun forecast(context: Context, lat: Double, lon: Double): Forecast? = runCatching {
        val area = JSONObject(cached(context, "area.json", AREA_TABLE) ?: return null)
        val (office, class10) = resolve(context, area, lat, lon) ?: return null
        val root = JSONArray(get("$FORECAST/$office.json") ?: return null)
        val series = root.getJSONObject(0).getJSONArray("timeSeries")
        fun areaOf(ts: JSONObject): JSONObject? {
            val areas = ts.getJSONArray("areas")
            for (i in 0 until areas.length()) {
                val a = areas.getJSONObject(i)
                if (a.getJSONObject("area").optString("code") == class10) return a
            }
            return if (areas.length() > 0) areas.getJSONObject(0) else null
        }
        val weathers = areaOf(series.getJSONObject(0))
        val popTs = series.getJSONObject(1)
        val popArea = areaOf(popTs)
        val defines = popTs.getJSONArray("timeDefines")
        val values = popArea?.optJSONArray("pops")
        val pops = (0 until defines.length()).mapNotNull { i ->
            val v = values?.optString(i)?.toIntOrNull() ?: return@mapNotNull null
            OffsetDateTime.parse(defines.getString(i)).toInstant().toEpochMilli() to v
        }
        Forecast(
            area = weathers?.getJSONObject("area")?.optString("name").orEmpty(),
            // 気象庁の文は全角空白で区切られている（「くもり　時々　雨」）。
            weatherToday = weathers?.optJSONArray("weathers")?.optString(0).orEmpty()
                .replace('　', ' ').trim(),
            pops = pops,
        )
    }.getOrNull()

    /** 住所（市区町村）から、府県予報区と一次細分区域の番号を引く。 */
    private fun resolve(context: Context, area: JSONObject, lat: Double, lon: Double): Pair<String, String>? {
        @Suppress("DEPRECATION")
        val address = runCatching {
            Geocoder(context, Locale.JAPAN).getFromLocation(lat, lon, 1)?.firstOrNull()
        }.getOrNull() ?: return null
        val offices = area.getJSONObject("offices")
        val class10s = area.getJSONObject("class10s")
        val class15s = area.getJSONObject("class15s")
        val class20s = area.getJSONObject("class20s")
        val locality = address.locality ?: address.subAdminArea
        val admin = address.adminArea
        // 市区町村の名から辿る。同名の市（府中市など）は、都道府県の名で絞る。
        class20s.keys().forEach { code ->
            val c20 = class20s.getJSONObject(code)
            if (c20.optString("name") != locality) return@forEach
            val c15 = class15s.optJSONObject(c20.optString("parent")) ?: return@forEach
            val c10code = c15.optString("parent")
            val office = class10s.optJSONObject(c10code)?.optString("parent") ?: return@forEach
            if (admin == null || offices.optJSONObject(office)?.optString("name") == admin) {
                return office to c10code
            }
        }
        // 引けなければ、都道府県の名で予報区を選び、最初の区域を使う。
        offices.keys().forEach { code ->
            val o = offices.getJSONObject(code)
            if (o.optString("name") == admin) {
                val first = o.optJSONArray("children")?.optString(0) ?: return@forEach
                return code to first
            }
        }
        return null
    }

    // ---- 通信 --------------------------------------------------------------

    private fun degrees(dm: JSONArray): Double = dm.getDouble(0) + dm.getDouble(1) / 60.0

    /** 大きく、滅多に変わらない表。端末に 30 日置く。 */
    private fun cached(context: Context, name: String, url: String): String? {
        val file = File(context.cacheDir, "jma_$name")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < TABLE_TTL) {
            return runCatching { file.readText() }.getOrNull()
        }
        val body = get(url) ?: return if (file.exists()) runCatching { file.readText() }.getOrNull() else null
        runCatching { file.writeText(body) }
        return body
    }

    private fun get(url: String): String? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        try {
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private const val BASE = "https://www.jma.go.jp/bosai"
    private const val AMEDAS_TABLE = "$BASE/amedas/const/amedastable.json"
    private const val AMEDAS_POINT = "$BASE/amedas/data/point"
    private const val AREA_TABLE = "$BASE/common/const/area.json"
    private const val FORECAST = "$BASE/forecast/data/forecast"
    private const val TABLE_TTL = 30L * 24 * 3_600_000
}
