package com.yuma.observatorylauncher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.net.Uri
import android.util.LruCache
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 絵柄（アルバムアート）を取る。
 *
 * ## ⚠️ 「出るものと出ないものがある」の正体
 *
 * 長らく `METADATA_KEY_ALBUM_ART` と `METADATA_KEY_ART` の**二つしか見ていなかった**。
 * この二つは「metadata の中に絵そのものが入っている」場合の鍵である。
 *
 * ところが `MediaMetadata` が絵柄を運ぶ道は**六つ**ある。
 *
 * | 鍵 | 中身 |
 * | --- | --- |
 * | `METADATA_KEY_ALBUM_ART` | 絵そのもの |
 * | `METADATA_KEY_ART` | 絵そのもの |
 * | `METADATA_KEY_DISPLAY_ICON` | 絵そのもの（表示用） |
 * | `METADATA_KEY_ALBUM_ART_URI` | **絵の在り処** |
 * | `METADATA_KEY_ART_URI` | **絵の在り処** |
 * | `METADATA_KEY_DISPLAY_ICON_URI` | **絵の在り処** |
 *
 * どれを使うかは**アプリと曲の素性で変わる**。
 * YouTube Music は、正規のアルバムに属する曲なら絵を直に渡してくるが、
 * 歌ってみた・カバーのように**アルバムを持たない曲では在り処だけ**を渡すことがある。
 * だから「出るものと出ないものがある」ように見えた（本人の指摘・2026/9/1）。
 *
 * **鍵が六つあるものを二つだけ見て、取れなかったと決めない。**
 *
 * ## 取れなかったときは
 *
 * 面から絵柄の枡ごと消していたので、曲が変わるたびに下の文字が飛び上がっていた。
 * **取れなかったものを詰めない**——枡は残し、罫と格点で「ここに在るはずのもの」を示す。
 */
internal object Artwork {

    /** 絵が直に入っている鍵。上から順に当たる。 */
    private val BITMAP_KEYS = listOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART,
        MediaMetadata.METADATA_KEY_ART,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON,
    )

    /** 在り処が入っている鍵。上から順に当たる。 */
    private val URI_KEYS = listOf(
        MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
        MediaMetadata.METADATA_KEY_ART_URI,
        MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
    )

    /**
     * 取り込む一辺の上限。
     *
     * 置き場は画面の幅（Pixel 8a で 1080px）なので、それ以上は捨てる。
     * 縮小せずに持つと、一曲ごとに数 MB を抱えることになる。
     */
    const val MAX_EDGE = 1080

    /** 取りに行く先が遠いときの見切り。天気と同じ 8 秒。 */
    const val TIMEOUT_MS = 8000

    /** 覚えておく量。**枚数ではなく重さで測る**——絵の大きさは曲ごとに違う。 */
    private const val CACHE_BYTES = 12 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * 取れなかった曲。
     *
     * ⚠️ **これが無いと、絵の無い曲を鳴らしている間ずっと取りに行き続ける。**
     * 先が空だと分かっているものを、拍ごとに叩きに行く理由は無い。
     */
    private val missing = LruCache<String, Long>(256)

    /**
     * 「取れなかった」を覚えておく長さ。
     *
     * ⚠️ **永久に覚えてはならない。**
     * 在り処はあるのに一度目が網の都合で落ちただけ、ということがある。
     * 永久に覚えると、その曲は**二度と絵が出なくなる**。
     */
    const val MISS_TTL_MS = 90_000L

    // ---- 純粋な部分（端末なしで確かめられる） -------------------------------

    /**
     * 在り処を選ぶ。**上から順に、空でない最初のもの。**
     *
     * 空文字を「在る」と数えない——`MediaMetadata` は鍵が無いとき空文字を返すことがある。
     */
    fun chooseUri(values: List<String?>): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    /**
     * 曲を見分ける鍵。
     *
     * 題だけでは足りない。同じ題で演者の違う曲——カバーはまさにそれ——を
     * 取り違えて、**前の曲の絵を出してしまう**。
     */
    fun identity(title: String?, artist: String?, uri: String?): String? {
        val t = title.orEmpty()
        val a = artist.orEmpty()
        val u = uri.orEmpty()
        if (t.isBlank() && u.isBlank()) return null
        return "$t / $a / $u"
    }

    /** 縮小の度合い。二の冪でしか刻めないので、**上限を割らない最大の刻み**を採る。 */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_EDGE): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= maxEdge || height / (sample * 2) >= maxEdge) {
            sample *= 2
        }
        return sample
    }

    // ---- 端末側 -------------------------------------------------------------

    /**
     * 絵柄を取る。**呼ぶ側は `Dispatchers.IO` に居ること**——外へ出ることがある。
     *
     * 取れなければ null。**面はそれを前提に組む**（枡を残す）。
     */
    fun of(context: Context, metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null

        // 直に入っているなら、それが一番よい。取りに出る必要が無い。
        BITMAP_KEYS.forEach { key ->
            runCatching { metadata.getBitmap(key) }.getOrNull()?.let { return it }
        }

        val uri = chooseUri(URI_KEYS.map { runCatching { metadata.getString(it) }.getOrNull() })
            ?: return null
        val id = identity(
            runCatching { metadata.getString(MediaMetadata.METADATA_KEY_TITLE) }.getOrNull(),
            runCatching { metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) }.getOrNull(),
            uri,
        ) ?: return null

        cache.get(id)?.let { return it }
        val missedAt = missing.get(id)
        val now = System.currentTimeMillis()
        if (missedAt != null && now - missedAt < MISS_TTL_MS) return null

        val bitmap = load(context, uri)
        if (bitmap == null) {
            missing.put(id, now)
        } else {
            missing.remove(id)
            cache.put(id, bitmap)
        }
        return bitmap
    }

    /**
     * 在り処から絵を起こす。
     *
     * `content://` `file://` は端末の中、`http://` `https://` は外。
     * **どちらも失敗しうるので、失敗は静かに null にする**——面を壊す理由にならない。
     */
    private fun load(context: Context, raw: String): Bitmap? = runCatching {
        val uri = Uri.parse(raw)
        val bytes = when (uri.scheme?.lowercase()) {
            "http", "https" -> fetch(raw)
            null -> null
            else -> context.contentResolver.openInputStream(uri)?.use { it.readAll() }
        } ?: return null
        decode(bytes)
    }.getOrNull()

    private fun fetch(raw: String): ByteArray? = runCatching {
        val conn = (URL(raw).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            conn.inputStream.use { it.readAll() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /**
     * 絵を起こす。**二度読む**——一度目は大きさだけを見て、縮小の度合いを決める。
     * 一度目で全部を起こしてしまうと、縮小する前に大きなものを抱えることになる。
     */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun InputStream.readAll(): ByteArray {
        val out = ByteArrayOutputStream()
        copyTo(out)
        return out.toByteArray()
    }
    // ---- 絵柄から色を抜く -----------------------------------------------------

    /**
     * 絵柄を**縦の細片に潰して、片ごとの平均色**を返す。
     *
     * 映画の全コマを一本の帯に並べる見せ方（film barcode）から採った。
     * 絵そのものではなく、**絵の色の並び**だけが残る。
     *
     * ## なぜこれを置くか
     *
     * 横長の絵柄では面の高さが余る。
     * そこへ履歴を並べたところ「それではなく、もっと革新的な何かが欲しい」
     * と言われた（本人・2026/9/1）。**一覧は、面をもう一つの台帳にしてしまう。**
     *
     * 色の帯は曲ごとに必ず違い、**今かかっているものと結びついている**。
     * 読むものではなく、見て分かるものである。
     * この面は色と面積を許した唯一の面なので、ここにしか置けない。
     *
     * @param columns 片の数。多すぎると帯が写真に戻ってしまう。
     */
    /** 平均へ最鮮の一点を混ぜる割合。 */
    const val VIVID_SHARE = 0.5f

    /** 二色を混ぜる。[share] は [b] の取り分。 */
    fun blend(a: Int, b: Int, share: Float): Int {
        val k = share.coerceIn(0f, 1f)
        fun mix(shift: Int): Int {
            val av = (a shr shift) and 0xFF
            val bv = (b shr shift) and 0xFF
            return (av + (bv - av) * k).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    fun stripe(bitmap: Bitmap, columns: Int = 40): IntArray {
        if (columns <= 0 || bitmap.width <= 0 || bitmap.height <= 0) return IntArray(0)
        val rows = 12
        val small = runCatching {
            Bitmap.createScaledBitmap(bitmap, columns, rows, true)
        }.getOrNull() ?: return IntArray(0)
        val pixels = IntArray(columns * rows)
        runCatching { small.getPixels(pixels, 0, columns, 0, 0, columns, rows) }
            .onFailure { return IntArray(0) }

        // ⚠️ **ただ平均すると濁る。**
        // 空・人物・海が一本の片に混ざり、どの絵でも同じ灰へ寄っていく
        // （実機・2026/9/1。元の絵は鮮やかなのに帯だけが眠かった）。
        //
        // 平均は**明るさの並び**を正しく残すので捨てない。
        // そこへ、その片で**最も鮮やかだった一点**を半分だけ混ぜて彩りを戻す。
        // 平均＝構図、最鮮＝性格。両方要る。
        return IntArray(columns) { x ->
            var r = 0L
            var g = 0L
            var b = 0L
            var vividColour = 0
            var vividness = -1
            for (y in 0 until rows) {
                val c = pixels[y * columns + x]
                val cr = (c shr 16) and 0xFF
                val cg = (c shr 8) and 0xFF
                val cb = c and 0xFF
                r += cr
                g += cg
                b += cb
                // 鮮やかさは「最大の色味と最小の色味の開き」で足りる。
                val spread = maxOf(cr, cg, cb) - minOf(cr, cg, cb)
                if (spread > vividness) {
                    vividness = spread
                    vividColour = c
                }
            }
            val n = rows.toLong()
            blend(
                (0xFF shl 24) or
                    (((r / n).toInt()) shl 16) or
                    (((g / n).toInt()) shl 8) or
                    ((b / n).toInt()),
                vividColour,
                VIVID_SHARE,
            )
        }
    }

    /**
     * 一曲を代表する一色。
     *
     * ## 何に使うか
     *
     * 記録へ一つだけ残して、**一日を色の帯として並べる**ための種である
     * （DAY TAPE）。帯を作る日が来てから集め始めると、その日は空の帯が出る。
     * **見せるより先に、貯め始める。**
     *
     * ## 選び方
     *
     * [stripe] を粗く（八片）取り、その中で**最も鮮やかな片**を採る。
     * 全体の平均を採ると、絵によらず同じ灰へ寄る——
     * 一曲を一色で表すのだから、**その絵で最も強い色**でなければ意味が無い。
     *
     * 取れなければ 0。**0 は「無い」という主張ではなく「取れなかった」の印**なので、
     * 帯を描く側で塗らずに空けること。
     */
    fun dominant(bitmap: Bitmap): Int {
        val slices = stripe(bitmap, columns = 8)
        if (slices.isEmpty()) return 0
        return slices.maxByOrNull { c ->
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            maxOf(r, g, b) - minOf(r, g, b)
        } ?: 0
    }

    // ---- 面の地へ混ぜるための色 ----------------------------------------------

    /**
     * 明るさの上限（地の明るさの何倍まで許すか）。
     *
     * ⚠️ **絵の色をそのまま地へ混ぜると、字が読めなくなる。**
     * 白っぽい絵柄を 8% 混ぜただけで、燠火の文字が 5.21:1 から **3.80:1** へ落ちた
     * （下限は 4.5:1）。
     *
     * 明るさだけを地の近くまで落とし、**色味は残す**。
     * こうすれば 55% 混ぜても紙は 10:1、燠火の文字も 4.5:1 を保つ（実測）。
     */
    const val TINT_LUMA_CAP = 1.35

    /** sRGB の一成分を、明るさの計算に使う値へ直す。 */
    private fun toLinear(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /** 相対輝度。 */
    fun luminance(colour: Int): Double =
        0.2126 * toLinear((colour shr 16) and 0xFF) +
            0.7152 * toLinear((colour shr 8) and 0xFF) +
            0.0722 * toLinear(colour and 0xFF)

    /**
     * 色味を残したまま、明るさだけを地の近くまで落とす。
     *
     * **暗くするのであって、灰色にするのではない。**
     * 三成分を同じ率で縮めるので、色相と鮮やかさの比は変わらない。
     */
    fun sink(colour: Int, ground: Int): Int {
        val cap = luminance(ground) * TINT_LUMA_CAP
        if (luminance(colour) <= cap) return colour
        var r = ((colour shr 16) and 0xFF).toDouble()
        var g = ((colour shr 8) and 0xFF).toDouble()
        var b = (colour and 0xFF).toDouble()
        repeat(48) {
            val here = (0xFF shl 24) or
                (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
            if (luminance(here) <= cap) return here
            r *= 0.96
            g *= 0.96
            b *= 0.96
        }
        return ground
    }

    /**
     * 一度の読み込みで得られるもの。
     *
     * **絵・帯・代表色を一緒に返す。** 面の地・絵柄の枡・色の帯が
     * それぞれ取りに行くと、同じ絵を三度読むことになる。
     */
    class Loaded(val bitmap: Bitmap, val stripe: IntArray, val tint: Int)

    /**
     * 今かかっている曲の絵を一度だけ読む。**`Dispatchers.IO` から呼ぶこと。**
     *
     * 記録への色の書き戻しもここで済ませる——
     * 見張り（通知）は外へ出ないので、在り処しか無い曲の色はここでしか取れない。
     */
    fun load(context: Context): Loaded? {
        val controller = Music.controller(context) ?: return null
        val metadata = controller.metadata
        val bitmap = of(context, metadata) ?: return null
        val tint = dominant(bitmap)
        runCatching {
            Listening.paint(
                context,
                title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
                tint = tint,
            )
        }
        return Loaded(bitmap, stripe(bitmap), tint)
    }

}
