package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.ui.InfoMerge
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 札を畳む規則。
 *
 * 守りたいのは三つ。
 * 1. **両方揃っているときだけ**畳む（片方だけの面を勝手に作り替えない）
 * 2. 畳んだ札は**先に置かれていた方の場所と id を継ぐ**
 * 3. **編集中は畳まない**（どちらを消すか選べなくなる）
 */
class InfoMergeTest {

    private fun tile(id: String, kind: String) =
        LayoutConfig.InfoTile(id = id, kind = kind)

    @Test
    fun `両方あればひとつに畳む`() {
        val out = InfoMerge.collapse(
            listOf(
                tile("a", LayoutConfig.INFO_WEATHER),
                tile("b", LayoutConfig.INFO_WEATHER_DETAIL),
            )
        )
        assertEquals(1, out.size)
        assertEquals(LayoutConfig.INFO_WEATHER_FULL, out[0].kind)
    }

    @Test
    fun `畳んだ札は先にあった方の id と場所を継ぐ`() {
        val out = InfoMerge.collapse(
            listOf(
                tile("first", LayoutConfig.INFO_WEATHER_DETAIL),
                tile("second", LayoutConfig.INFO_WEATHER),
            )
        )
        assertEquals("first", out[0].id)
    }

    @Test
    fun `他の札の並びは動かさない`() {
        val out = InfoMerge.collapse(
            listOf(
                tile("x", LayoutConfig.INFO_CALENDAR),
                tile("a", LayoutConfig.INFO_WEATHER),
                tile("y", LayoutConfig.INFO_BATTERY),
                tile("b", LayoutConfig.INFO_WEATHER_DETAIL),
                tile("z", LayoutConfig.INFO_SPACE),
            )
        )
        assertEquals(
            listOf("x", "a", "y", "z"),
            out.map { it.id },
        )
        assertEquals(LayoutConfig.INFO_WEATHER_FULL, out[1].kind)
    }

    @Test
    fun `片方しか無ければ触らない`() {
        val only = listOf(tile("a", LayoutConfig.INFO_WEATHER))
        assertEquals(only, InfoMerge.collapse(only))
        val detail = listOf(tile("b", LayoutConfig.INFO_WEATHER_DETAIL))
        assertEquals(detail, InfoMerge.collapse(detail))
    }

    @Test
    fun `編集中は畳まない`() {
        val both = listOf(
            tile("a", LayoutConfig.INFO_WEATHER),
            tile("b", LayoutConfig.INFO_WEATHER_DETAIL),
        )
        assertEquals(both, InfoMerge.collapse(both, editing = true))
    }
}
