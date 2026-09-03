package com.yuma.observatorylauncher.data

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.storage.StorageManager

/**
 * 保存領域。
 *
 * 「空きが少ない」は設定を開けば分かるが、**何が食っているか**は
 * アプリを一つずつ開かないと分からない。並べれば一目で済む。
 *
 * 読み出しには利用状況へのアクセス（`GET_USAGE_STATS`）が要る。
 * 画面使用時間・通信量と同じ許可で、追加の解錠は無い。
 *
 * ## 数え方
 *
 * アプリ一件の大きさは **本体＋データ＋キャッシュ** の合計とする。
 * 本体だけでは実感と合わない（動画アプリは本体が小さくデータが巨大になる）。
 *
 * ## ⚠️ 一件ずつ端末へ訊く
 *
 * `queryStatsForPackage` はアプリごとの問い合わせで、百件あれば百回になる。
 * **必ず背景で呼ぶこと。** 主スレッドで回すとそのまま画面が止まる。
 */
internal object Space {

    data class Report(
        val total: Long,
        val free: Long,
        /** 大きい順（名前は解決済み）。 */
        val ranked: List<Pair<String, Long>>,
    ) {
        val used: Long get() = (total - free).coerceAtLeast(0L)

        /** 使用の割合(0..1)。全体が取れなければ null——**目盛りを嘘にしない**。 */
        val ratio: Float?
            get() = if (total > 0) (used.toFloat() / total).coerceIn(0f, 1f) else null
    }

    /**
     * @param limit 並べる件数。全件並べても「多い」以上のことは伝わらない。
     */
    fun read(context: Context, limit: Int = 5): Report? {
        val stats = context.getSystemService(StorageStatsManager::class.java) ?: return null
        val uuid = StorageManager.UUID_DEFAULT
        val total = runCatching { stats.getTotalBytes(uuid) }.getOrNull() ?: return null
        val free = runCatching { stats.getFreeBytes(uuid) }.getOrDefault(0L)

        // 対象は**一覧に出るアプリ**だけ。組み込みの部品まで並べると、
        // 人が知っている名前が埋もれる。控えを使うので端末への問い合わせは増えない。
        val entries = AppRepository.cached().distinctBy { it.packageName }
        val user = android.os.Process.myUserHandle()
        val sizes = entries.mapNotNull { entry ->
            val size = runCatching {
                val s = stats.queryStatsForPackage(uuid, entry.packageName, user)
                s.appBytes + s.dataBytes + s.cacheBytes
            }.getOrNull() ?: return@mapNotNull null
            if (size <= 0L) null else entry.label to size
        }

        return Report(
            total = total,
            free = free,
            ranked = sizes.sortedByDescending { it.second }.take(limit),
        )
    }
}
