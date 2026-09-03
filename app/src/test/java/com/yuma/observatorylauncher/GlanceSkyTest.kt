package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.data.Glance
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 天気記号の言葉。
 *
 * **分からない空を「晴れ」と書かない**——取れなかったときは空文字であること。
 */
class GlanceSkyTest {

    @Test
    fun `代表的な記号が言葉になる`() {
        assertEquals("快晴", Glance.skyOf(0))
        assertEquals("晴れ", Glance.skyOf(1))
        assertEquals("くもり", Glance.skyOf(3))
        assertEquals("雨", Glance.skyOf(63))
        assertEquals("雪", Glance.skyOf(73))
        assertEquals("雷雨", Glance.skyOf(95))
    }

    @Test
    fun `取れなかった記号は空文字`() {
        assertEquals("", Glance.skyOf(-1))
        // 知らない番号を勝手に晴れにしない。
        assertEquals("", Glance.skyOf(999))
    }
}
