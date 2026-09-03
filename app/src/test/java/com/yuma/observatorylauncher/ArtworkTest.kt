package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.data.Artwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 絵柄の選び方。**端末が要らない部分だけを確かめる。**
 *
 * ここで守りたいのは三つ。
 * 1. 在り処は**上から順に、空でない最初のもの**（空文字を「在る」と数えない）
 * 2. 曲の見分けに**演者が入る**（同じ題の別演者を取り違えない）
 * 3. 縮小は**上限を割らない最大の刻み**
 */
class ArtworkTest {

    @Test
    fun `在り処は上から順に選ぶ`() {
        assertEquals("a", Artwork.chooseUri(listOf("a", "b", "c")))
        assertEquals("b", Artwork.chooseUri(listOf(null, "b", "c")))
    }

    @Test
    fun `空文字は在ると数えない`() {
        // MediaMetadata は鍵が無いとき空文字を返すことがある。
        // これを拾うと「在り処が在るのに取りに行けない」状態になる。
        assertEquals("c", Artwork.chooseUri(listOf("", "   ", "c")))
        assertNull(Artwork.chooseUri(listOf("", null, "")))
    }

    @Test
    fun `同じ題でも演者が違えば別の曲として扱う`() {
        val original = Artwork.identity("4BLOOD", "GARNiDELiA", "https://x/1")
        val cover = Artwork.identity("4BLOOD", "小鳥遊キアラ", "https://x/2")
        assertEquals(false, original == cover)
    }

    @Test
    fun `題も在り処も無ければ鍵を作らない`() {
        assertNull(Artwork.identity("", "", ""))
        assertNull(Artwork.identity(null, null, null))
    }

    @Test
    fun `縮小は上限を割らない最大の刻み`() {
        assertEquals(1, Artwork.sampleSize(1080, 1080, maxEdge = 1080))
        assertEquals(2, Artwork.sampleSize(2160, 2160, maxEdge = 1080))
        assertEquals(4, Artwork.sampleSize(4320, 4320, maxEdge = 1080))
        // 小さい絵を引き伸ばさない。刻みは 1 のまま。
        assertEquals(1, Artwork.sampleSize(300, 300, maxEdge = 1080))
    }

    @Test
    fun `大きさが取れなくても落ちない`() {
        // BitmapFactory は読めない絵に対して -1 を返す。
        assertEquals(1, Artwork.sampleSize(-1, -1))
        assertEquals(1, Artwork.sampleSize(0, 0))
    }

    @Test
    fun `色は平均と最鮮の中間になる`() {
        // 灰(128,128,128) と 赤(255,0,0) を半分ずつ混ぜる。
        val mixed = com.yuma.observatorylauncher.data.Artwork.blend(
            (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128,
            (0xFF shl 24) or (255 shl 16),
            0.5f,
        )
        assertEquals(191, (mixed shr 16) and 0xFF)
        assertEquals(64, (mixed shr 8) and 0xFF)
        assertEquals(64, mixed and 0xFF)
    }

    @Test
    fun `混ぜる割合が端なら元の色のまま`() {
        val grey = (0xFF shl 24) or (128 shl 16) or (128 shl 8) or 128
        val red = (0xFF shl 24) or (255 shl 16)
        assertEquals(grey, com.yuma.observatorylauncher.data.Artwork.blend(grey, red, 0f))
        assertEquals(red, com.yuma.observatorylauncher.data.Artwork.blend(grey, red, 1f))
    }
}
