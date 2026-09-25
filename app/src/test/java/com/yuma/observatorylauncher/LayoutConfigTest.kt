package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.config.LayoutConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 宣言(layout.json)の試験。
 *
 * ここは**画面より先に守るべき層**である。表示が崩れても直せるが、
 * 宣言が壊れれば持ち物そのものが失われる。とくに次の二つを見張る。
 *
 * 1. **書いて読んで同じ値に戻ること**（往復）。
 * 2. **足したものが引けること**（逆操作）。「追加」に対する「削除」が
 *    無い、あるいは効かない、という不具合を実際に出したため。
 */
class LayoutConfigTest {

    private fun sample(): LayoutConfig = LayoutConfig(
        sections = listOf(
            LayoutConfig.Section("常用", listOf("a.pkg", "b.pkg"), row = true, page = 0),
            LayoutConfig.Section("二枚目", listOf("c.pkg"), row = false, page = 1),
        ),
        groups = listOf(LayoutConfig.Section("金融", listOf("bank.pkg"))),
        display = LayoutConfig.Display(iconSize = 32, showLabels = false),
        pageCount = 2,
        widgets = listOf(LayoutConfig.Widget(id = 7, height = 150, span = 2)),
        sites = listOf(LayoutConfig.Site("s1", "vault", "https://example.test")),
    )

    // ---- 往復 ------------------------------------------------------------

    @Test
    fun json_round_trip_keeps_every_field() {
        val original = sample()
        val restored = LayoutConfig.parse(original.toJson())
        assertNotNull(restored)
        assertEquals(original.sections, restored!!.sections)
        assertEquals(original.groups, restored.groups)
        assertEquals(original.display, restored.display)
        assertEquals(original.widgets, restored.widgets)
        assertEquals(original.pageCount, restored.pageCount)
        assertEquals(original.sites.map { it.id }, restored.sites.map { it.id })
    }

    @Test
    fun broken_text_is_refused_rather_than_guessed() {
        assertNull(LayoutConfig.parse("これはJSONではない"))
        assertNull(LayoutConfig.parse(""))
    }

    /** 旧い版のウィジェットは数値の並びだった。読めなくなる方向へ倒さない。 */
    @Test
    fun old_widget_format_is_still_readable() {
        val old = """{"schema":1,"sections":[],"widgets":[3,4]}"""
        val parsed = LayoutConfig.parse(old)
        assertNotNull(parsed)
        assertEquals(listOf(3, 4), parsed!!.widgets.map { it.id })
        assertTrue(parsed.widgets.all { it.span == 4 && it.height == 0 })
    }

    // ---- 逆操作 ----------------------------------------------------------

    @Test
    fun pin_and_unpin_are_inverse() {
        val base = LayoutConfig(sections = listOf(LayoutConfig.Section("常用", emptyList())))
        val added = base.pinned("x.pkg", "常用")
        assertTrue(added.sections.first().items.contains("x.pkg"))
        assertEquals(base.sections, added.without("x.pkg").sections)
    }

    @Test
    fun move_up_and_down_are_inverse() {
        val base = sample()
        val moved = base.movedDown("a.pkg").movedUp("a.pkg")
        assertEquals(base.sections, moved.sections)
    }

    @Test
    fun section_add_and_remove_are_inverse() {
        val base = sample()
        val added = base.sectionAdded("常用", page = 0)
        assertEquals(base.sections.size + 1, added.sections.size)
        val createdName = added.sections.first { it !in base.sections }.title
        assertEquals(base.sections, added.sectionRemoved(createdName).sections)
    }

    @Test
    fun group_add_and_remove_are_inverse() {
        val base = sample()
        val added = base.groupAdded("金融")
        val createdName = added.groups.first { it !in base.groups }.title
        assertEquals(base.groups, added.groupRemoved(createdName).groups)
    }

    @Test
    fun widget_add_and_remove_are_inverse() {
        val base = sample()
        val added = base.widgetAdded(99)
        assertTrue(added.widgets.any { it.id == 99 })
        assertEquals(base.widgets, added.widgetRemoved(99).widgets)
    }

    @Test
    fun site_add_and_remove_are_inverse() {
        val base = sample()
        val added = base.siteAdded("新しい頁", "https://example.test/2", "常用")
        val created = added.sites.first { it.id !in base.sites.map { s -> s.id } }
        val removed = added.siteRemoved(created.id)
        assertEquals(base.sites.map { it.id }, removed.sites.map { it.id })
        assertEquals(base.sections, removed.sections)
    }

    @Test
    fun toggles_return_to_the_original_when_pressed_twice() {
        val base = sample()
        assertEquals(base.display, base.withLabels(!base.display.showLabels).withLabels(base.display.showLabels).display)
        assertEquals(base.sections, base.sectionRowToggled("常用").sectionRowToggled("常用").sections)
        assertEquals(base.widgets, base.widgetZoneToggled(7).widgetZoneToggled(7).widgets)
    }

    @Test
    fun cycles_come_back_around() {
        var config = sample()
        repeat(4) { config = config.widgetSpanCycled(7) }
        assertEquals(sample().widgets, config.widgets)

        var sized = sample()
        repeat(LayoutConfig.ICON_SIZES.size) { sized = sized.withIconSize(nextIconSize(sized)) }
        assertEquals(sample().display.iconSize, sized.display.iconSize)
    }

    private fun nextIconSize(config: LayoutConfig): Int {
        val sizes = LayoutConfig.ICON_SIZES
        return sizes[(sizes.indexOf(config.display.iconSize) + 1) % sizes.size]
    }

    // ---- ページの畳み方 --------------------------------------------------

    /**
     * ページを畳んでも**中身は消えない**。ただし空の節は連れて行かない
     * ——連れて行くと「削除したのに、その名の空の節が残る」ことになる。
     */
    @Test
    fun removing_a_page_keeps_its_contents_but_not_empty_shells() {
        val config = LayoutConfig(
            sections = listOf(
                LayoutConfig.Section("常用", listOf("a.pkg"), page = 0),
                LayoutConfig.Section("空の節", emptyList(), page = 1),
                LayoutConfig.Section("中身あり", listOf("b.pkg"), page = 1),
            ),
            pageCount = 2,
            infoTiles = listOf(LayoutConfig.InfoTile(id = "t1", kind = "weather", page = 1)),
            widgets = listOf(LayoutConfig.Widget(id = 5, page = 1)),
        )
        val folded = config.pageRemoved(1)
        assertEquals(1, folded.pageCount)
        assertTrue(folded.sections.none { it.title == "空の節" })
        assertEquals(0, folded.sections.first { it.title == "中身あり" }.page)
        assertEquals(0, folded.infoTiles.first().page)
        assertEquals(0, folded.widgets.first().page)
    }

    /** 最後の一枚は畳めない。畳めるとホームそのものが無くなる。 */
    @Test
    fun the_last_page_cannot_be_folded() {
        val single = LayoutConfig(sections = emptyList(), pageCount = 1)
        assertEquals(single, single.pageRemoved(0))
    }

    /**
     * 桁の巡回は、ウィジェットが申告した最小の幅を下回らない。
     * 下回れると「置けているのに中身が潰れている」という一番分かりにくい壊れ方になる。
     */
    @Test
    fun widgetSpanNeverGoesBelowWhatTheWidgetNeeds() {
        var config = LayoutConfig(sections = emptyList()).widgetAdded(id = 7)
        // 全幅から始まり、最小2桁なら 4→3→2→4 と巡る（1は飛ばす）。
        val seen = mutableListOf<Int>()
        repeat(4) {
            config = config.widgetSpanCycled(7, minSpan = 2)
            seen += config.widgets.first { it.id == 7 }.span
        }
        assertEquals(listOf(3, 2, 4, 3), seen)
        assertTrue(seen.none { it < 2 })
    }

    /** 最小を申告しないものは今までどおり 1 まで狭められる。 */
    @Test
    fun widgetSpanStillReachesOneWhenNothingIsDeclared() {
        var config = LayoutConfig(sections = emptyList()).widgetAdded(id = 8)
        repeat(3) { config = config.widgetSpanCycled(8) }
        assertEquals(1, config.widgets.first { it.id == 8 }.span)
    }

    /**
     * 高さの巡回は月表示の大きさまで届く。
     * 300dp で止めていたため、巡回だけを使う人にはそこが天井に見えていた。
     */
    @Test
    fun theHeightCycleReachesAMonthView() {
        assertTrue(LayoutConfig.WIDGET_HEIGHTS.any { it >= 400 })
        var config = LayoutConfig(sections = emptyList()).widgetAdded(id = 9)
        val seen = mutableSetOf<Int>()
        repeat(LayoutConfig.WIDGET_HEIGHTS.size) {
            config = config.widgetHeightCycled(9)
            seen += config.widgets.first { it.id == 9 }.height
        }
        // 一巡すると全ての段を通り、元へ戻る。
        assertEquals(LayoutConfig.WIDGET_HEIGHTS.toSet(), seen)
        assertEquals(0, config.widgets.first { it.id == 9 }.height)
    }

    // ── 引いて並べ替える（2026-09-25） ─────────────────────

    @Test
    fun `並べ替えは与えた順になり他の節は動かない`() {
        val config = LayoutConfig(
            sections = listOf(
                LayoutConfig.Section("Daily", listOf("a", "b", "c", "d")),
                LayoutConfig.Section("Other", listOf("x", "y")),
            ),
        )
        val next = config.sectionReordered("Daily", listOf("c", "a", "d", "b"))
        org.junit.Assert.assertEquals(listOf("c", "a", "d", "b"), next.sections[0].items)
        org.junit.Assert.assertEquals(listOf("x", "y"), next.sections[1].items)
    }

    @Test
    fun `並べ替えで失われるものは無い`() {
        val config = LayoutConfig(sections = listOf(LayoutConfig.Section("Daily", listOf("a", "b", "c"))))
        // 知らない id は捨て、渡し忘れた id は末尾に残す。
        val next = config.sectionReordered("Daily", listOf("c", "zzz", "a"))
        org.junit.Assert.assertEquals(listOf("c", "a", "b"), next.sections[0].items)
    }
}
