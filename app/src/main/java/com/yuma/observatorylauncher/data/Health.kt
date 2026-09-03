package com.yuma.observatorylauncher.data

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.LocalDate
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 歩数・睡眠・心拍を Health Connect から読む。
 *
 * ここは**端末の他のアプリが書き込んだ記録**を借りて読む窓口である。
 * 自分で歩数を数えるのではなく、Fitbit や体組成計が置いていったものを見る。
 *
 * 三つの段を踏まないと一つも読めない。
 *
 * 1. **宣言**（`AndroidManifest.xml` の `android.permission.health.READ_*`）。
 *    宣言していない種類は、許可を求めることすらできない。
 * 2. **許可**（Health Connect の画面で利用者が種類ごとに与える）。
 * 3. **読み取り**。
 *
 * 以前ここは実装されておらず、1 も 2 も無いまま「個別許可が必要」とだけ出していた。
 * 押しても何も起きなかったのはそのためである。
 */
internal object Health {

    /** 読みたい種類。**宣言と一致していなければ許可を求められない。** */
    val PERMISSIONS: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
    )

    /** この端末で Health Connect を使えるか。 */
    enum class Availability { READY, UPDATE_REQUIRED, UNAVAILABLE }

    fun availability(context: Context): Availability =
        when (runCatching { HealthConnectClient.getSdkStatus(context) }.getOrNull()) {
            HealthConnectClient.SDK_AVAILABLE -> Availability.READY
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> Availability.UPDATE_REQUIRED
            else -> Availability.UNAVAILABLE
        }

    private fun client(context: Context): HealthConnectClient? =
        runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull()

    /** 与えられている許可。一つも無ければ空。 */
    suspend fun granted(context: Context): Set<String> {
        val client = client(context) ?: return emptySet()
        return runCatching { client.permissionController.getGrantedPermissions() }
            .getOrDefault(emptySet())
    }

    /** 今日の歩数、直近の睡眠、今日の心拍。読めなかったものは null。 */
    data class Reading(
        val steps: Long?,
        val sleep: Duration?,
        val heartRate: Long?,
    ) {
        val isEmpty: Boolean get() = steps == null && sleep == null && heartRate == null
    }

    /**
     * 今日の分をまとめて集計する。
     *
     * 睡眠だけは区間が今日に収まらない（昨夜から今朝へまたぐ）ので、
     * **昨日の正午から今まで**を見る。日付で切ると一晩分がまるごと落ちる。
     */
    /**
     * 日ごとの歩数。**一日の値だけでは、多いのか少ないのかが分からない。**
     * 自分の七日と比べて初めて意味を持つ。
     *
     * ⚠️ 一日ずつ集計を投げる。まとめて投げると期間全体の合計しか返らない。
     */
    suspend fun dailySteps(context: Context, days: Int = 7): List<Pair<LocalDate, Long>> {
        val client = client(context) ?: return emptyList()
        val today = LocalDate.now()
        return ((days - 1) downTo 0).map { back ->
            val day = today.minusDays(back.toLong())
            val from = day.atStartOfDay()
            val to = if (day == today) LocalDateTime.now() else day.plusDays(1).atStartOfDay()
            val steps = runCatching {
                client.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(from, to),
                    ),
                ).get(StepsRecord.COUNT_TOTAL)
            }.getOrNull() ?: 0L
            day to steps
        }
    }

    suspend fun today(context: Context): Reading? {
        val client = client(context) ?: return null
        val midnight = LocalDateTime.now().with(LocalTime.MIN)
        val now = LocalDateTime.now()

        suspend fun aggregate(
            metrics: Set<androidx.health.connect.client.aggregate.AggregateMetric<*>>,
            from: LocalDateTime,
        ): AggregationResult? = runCatching {
            client.aggregate(
                AggregateRequest(
                    metrics = metrics,
                    timeRangeFilter = TimeRangeFilter.between(from, now),
                ),
            )
        }.getOrNull()

        val day = aggregate(
            setOf(StepsRecord.COUNT_TOTAL, HeartRateRecord.BPM_AVG),
            midnight,
        )
        val night = aggregate(
            setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL),
            midnight.minusHours(12),
        )

        return Reading(
            steps = day?.get(StepsRecord.COUNT_TOTAL),
            sleep = night?.get(SleepSessionRecord.SLEEP_DURATION_TOTAL),
            heartRate = day?.get(HeartRateRecord.BPM_AVG),
        )
    }
}
