package com.yuma.observatorylauncher.data

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import java.util.Calendar

/**
 * 通信量。
 *
 * **端末の設定を開かないと出てこない値**の代表である。
 * 「今月あとどれくらい使えるか」は月に何度も気にするのに、
 * そのたびに設定 > ネットワーク > データ使用量まで潜る必要がある。
 *
 * 読み出しには利用状況へのアクセス（`GET_USAGE_STATS`）が要る。
 * 既に画面使用時間で許してもらっているので、追加の解錠は無い。
 *
 * ## 数え方
 *
 * - **受信と送信を足す。** どちらか一方だけでは「使った量」にならない。
 * - 加入者の識別子（subscriberId）は **null で問い合わせる**。
 *   渡せば回線ごとに分けられるが、そのためには電話番号に紐づく許可が要る。
 *   月の合計を見るのにそこまでの立ち入りは要らない。
 */
internal object Traffic {

    /** ある期間の通信量。 */
    data class Usage(
        val mobile: Long,
        val wifi: Long,
        /**
         * **モバイル分だけ**のアプリ別内訳（多い順・名前は解決済み）。
         *
         * Wi-Fi をここに混ぜない。端末が Wi-Fi の uid 別を返さないため
         * （実機で 3.4GB が丸ごと欠けた・2026/8/31）、混ぜると
         * 「計上外 3.4GB」が首位に立つだけの図になる。
         * そもそも上限に関わるのはモバイルであって、そちらは完全に取れる。
         */
        val ranked: List<Pair<String, Long>>,
    ) {
        val total: Long get() = mobile + wifi
    }

    /** 今日の零時から今まで。 */
    fun today(context: Context): Usage? = between(context, startOfDay(), System.currentTimeMillis())

    /** 今月の一日から今まで。 */
    fun thisMonth(context: Context): Usage? =
        between(context, startOfMonth(), System.currentTimeMillis())

    /**
     * 日ごとのモバイル通信量。**上限に関わるのはモバイルだけ**なので、こちらを並べる。
     *
     * ⚠️ 一日ずつ問い合わせる。期間をまとめて渡すと、どの日の分か分からなくなる。
     */
    fun dailyMobile(context: Context, days: Int = 7): List<Pair<Long, Long>> {
        val manager = context.getSystemService(NetworkStatsManager::class.java) ?: return emptyList()
        val today = startOfDay()
        return ((days - 1) downTo 0).map { back ->
            val from = today - back * 24 * 60 * 60_000L
            val to = from + 24 * 60 * 60_000L
            from to (deviceTotal(manager, ConnectivityManager.TYPE_MOBILE, from, to - 1) ?: 0L)
        }
    }

    private fun between(context: Context, from: Long, to: Long): Usage? {
        val manager = context.getSystemService(NetworkStatsManager::class.java) ?: return null
        val mobile = deviceTotal(manager, ConnectivityManager.TYPE_MOBILE, from, to)
        val wifi = deviceTotal(manager, ConnectivityManager.TYPE_WIFI, from, to)
        if (mobile == null && wifi == null) return null

        // アプリ別。**取れないことがある**（端末や版によって拒まれる）ので、
        // 取れなければ合計だけを返す——空の図を出すより、出せるものを出す。
        // 内訳はモバイルだけ。Wi-Fi の uid 別は端末が返さない。
        val byUid = HashMap<Int, Long>()
        runCatching {
            manager.querySummary(ConnectivityManager.TYPE_MOBILE, null, from, to)?.use { stats ->
                val bucket = NetworkStats.Bucket()
                while (stats.hasNextBucket()) {
                    stats.getNextBucket(bucket)
                    val bytes = bucket.rxBytes + bucket.txBytes
                    if (bytes > 0) byUid[bucket.uid] = (byUid[bucket.uid] ?: 0L) + bytes
                }
            }
        }

        // **解決できない uid を捨てない。**
        //
        // ⚠️ 最初はパッケージ名を引けない uid を落としていた。その結果、
        // 3.4GB 使っている日に内訳が二件しか出ず、片方は名前が空だった（実機・2026/8/31）。
        // 通信の主は端末側（システム・移譲・削除済み）に付くことが多く、
        // それらを落とすと**内訳の合計が本体と合わなくなる**——
        // 図としてこれは嘘に近い。名前を付けて残す。
        val packages = context.packageManager
        val ranked = byUid.entries
            .map { (uid, bytes) -> displayName(context, packages, uid) to bytes }
            // 同じ名に落ちるもの（システム・その他）は畳んでから並べる。
            .groupBy({ it.first }, { it.second })
            .map { (name, values) -> name to values.sum() }
            .sortedByDescending { it.second }

        // **合わない分を黙って落とさない。**
        // 端末全体の値は取れるのに内訳が届かないことがある。差を書かずに並べると、
        // 「これで全部だ」と読める図になり、それは嘘である。差は一行として置く。
        val total = mobile ?: 0L
        val counted = ranked.sumOf { it.second }
        val missing = total - counted
        val complete = if (missing > total / 20) {
            (ranked + ("計上外" to missing)).sortedByDescending { it.second }
        } else {
            ranked
        }

        return Usage(mobile ?: 0L, wifi ?: 0L, complete)
    }

    /**
     * uid を人が読む名へ。
     *
     * 引けないものは黙って捨てず、由来の分かる名に寄せる。
     * 「その他 1.2GB」は情報だが、消えた 1.2GB は誤解の種にしかならない。
     */
    private fun displayName(
        context: Context,
        packages: android.content.pm.PackageManager,
        uid: Int,
    ): String {
        if (uid < 0) return "その他"
        if (uid == android.os.Process.SYSTEM_UID) return "システム"
        val names = runCatching { packages.getPackagesForUid(uid) }.getOrNull().orEmpty()
        val self = context.packageName
        val pick = names.firstOrNull { it != self } ?: names.firstOrNull()
        // 引けない uid は端末側の部品である。**「uid 1051」と出すのは、
        // 名前ではなく内部の番号を人に見せているだけ**で、図の中で浮く。
        // 系が持つ名（`shared:android.uid.system` など）の末尾を借り、
        // それも無ければ「システム」に寄せる。
            ?: return runCatching { packages.getNameForUid(uid) }.getOrNull()
                ?.substringAfterLast('.')
                ?.substringAfterLast(':')
                ?.takeIf { it.isNotBlank() }
                ?: "システム"
        // 名が空で返る組み込みがある。**空の札は図の中で無言の棒になる**ので、
        // その場合はパッケージ名の末尾へ落とす。
        val label = runCatching {
            packages.getApplicationLabel(packages.getApplicationInfo(pick, 0)).toString()
        }.getOrDefault("")
        return label.ifBlank { pick.substringAfterLast('.') }
    }

    private fun deviceTotal(
        manager: NetworkStatsManager,
        type: Int,
        from: Long,
        to: Long,
    ): Long? = runCatching {
        val bucket = manager.querySummaryForDevice(type, null, from, to)
        bucket.rxBytes + bucket.txBytes
    }.getOrNull()

    private fun startOfDay(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun startOfMonth(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
