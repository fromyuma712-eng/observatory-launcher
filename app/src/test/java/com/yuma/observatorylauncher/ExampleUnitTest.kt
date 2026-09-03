package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.config.LayoutConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import com.yuma.observatorylauncher.data.Reading
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun addingPagePreservesExistingLayoutAndCreatesAnEmptyDestination() {
        val original = LayoutConfig(
            sections = listOf(LayoutConfig.Section("常用", listOf("app.one"))),
        )

        val next = original.pageAdded()

        assertEquals(2, next.pageCount)
        assertEquals(original.sections.first(), next.sections.first())
        assertEquals(1, next.sections.last().page)
        assertTrue(next.sections.last().items.isEmpty())
    }

    /**
     * 旧い版の `info` 欄は**読むときだけ**情報カードへ読み替え、書き戻さない。
     * 移行のための項目を宣言に残し続けると、新しい読み手が
     * 「どちらが正本か」を毎回判断することになる。
     */
    @Test
    fun legacyInfoBlockIsReadOnceAndNeverWrittenBack() {
        val old = """{"schema":1,"sections":[],"info":{"showWeather":true,"showCalendar":false,"showAlarm":false,"showBattery":true}}"""
        val migrated = LayoutConfig.parse(old)
        assertNotNull(migrated)
        assertEquals(
            listOf("legacy:weather", "legacy:battery"),
            migrated!!.infoTiles.map { it.id },
        )
        // 書き出したものに `info` は残らない
        assertFalse(migrated.toJson().contains("showWeather"))
    }

    /** 情報カードを全部消した状態は、旧い欄から作り直されないこと。 */
    @Test
    fun emptyInfoTilesAreNotRepopulatedFromLegacy() {
        val explicit = """{"schema":1,"sections":[],"infoTiles":[],"info":{"showWeather":true}}"""
        val parsed = LayoutConfig.parse(explicit)
        assertNotNull(parsed)
        assertTrue(parsed!!.infoTiles.isEmpty())
    }

    @Test
    fun movingItemAcrossPagesDoesNotChangeEitherPageAssignment() {
        val original = LayoutConfig(
            pageCount = 2,
            sections = listOf(
                LayoutConfig.Section("常用", listOf("app.one"), page = 0),
                LayoutConfig.Section("作業", emptyList(), page = 1),
            ),
        )

        val next = original.pinned("app.one", "作業")

        assertTrue(next.sections[0].items.isEmpty())
        assertEquals(listOf("app.one"), next.sections[1].items)
        assertEquals(listOf(0, 1), next.sections.map { it.page })
    }

    @Test
    fun informationTileCanMoveBetweenZonesPagesAndWidths() {
        val tile = LayoutConfig.InfoTile(
            id = "info:test",
            kind = LayoutConfig.INFO_DEVICE,
            page = 0,
            zone = LayoutConfig.ZONE_ABOVE,
            span = 4,
        )
        val original = LayoutConfig(
            sections = emptyList(),
            pageCount = 2,
            infoTiles = listOf(tile),
        )

        val moved = original
            .infoTileZoneToggled(tile.id)
            .infoTilePageMoved(tile.id, 1)
            .infoTileSpanCycled(tile.id)

        assertEquals(LayoutConfig.ZONE_BELOW, moved.infoTiles.single().zone)
        assertEquals(1, moved.infoTiles.single().page)
        assertEquals(3, moved.infoTiles.single().span)
    }

    @Test
    fun externalWidgetKeepsItsPageAndZoneWhenResized() {
        val original = LayoutConfig(
            sections = emptyList(),
            pageCount = 2,
        ).widgetAdded(42, page = 1, zone = LayoutConfig.ZONE_ABOVE)

        val resized = original.widgetSpanCycled(42).widgetHeightSet(42, 180)

        assertEquals(1, resized.widgets.single().page)
        assertEquals(LayoutConfig.ZONE_ABOVE, resized.widgets.single().zone)
        assertEquals(3, resized.widgets.single().span)
        assertEquals(180, resized.widgets.single().height)
    }

    @Test
    fun movingWidgetOnlyReordersPeersOnTheSamePageAndZone() {
        val original = LayoutConfig(
            sections = emptyList(),
            pageCount = 2,
            widgets = listOf(
                LayoutConfig.Widget(1, page = 0, zone = LayoutConfig.ZONE_BELOW),
                LayoutConfig.Widget(9, page = 1, zone = LayoutConfig.ZONE_BELOW),
                LayoutConfig.Widget(2, page = 0, zone = LayoutConfig.ZONE_BELOW),
            ),
        )

        val moved = original.widgetMoved(1, 1)

        assertEquals(listOf(2, 9, 1), moved.widgets.map { it.id })
        assertEquals(1, moved.widgets.first { it.id == 9 }.page)
    }
}

/**
 * 読みでの照合。仮名で打った語が羅字の名前へ届くことを、実機を使わずに確かめる。
 * 実機の `input text` は仮名を送れないため、ここが唯一の確認手段になる。
 */
class ReadingTest {

    @Test
    fun kana_reaches_latin_names() {
        // 本人の要件そのもの: 「ゆ」で YouTube が残る
        assertNotNull(Reading.match("YouTube", "ゆ"))
        assertNotNull(Reading.match("YouTube", "ゆー"))
        // 拗音の y を落とした綴りを併せ持つので「ゆーちゅ」(yutu) でも届く
        assertNotNull(Reading.match("YouTube", "ゆーちゅ"))
        assertNotNull(Reading.match("Maps", "ま"))
    }

    @Test
    fun latin_reaches_kana_names() {
        assertNotNull(Reading.match("マップ", "map"))
        assertNotNull(Reading.match("ドキュメント", "dokyu"))
    }

    /**
     * 打ち足せば必ず絞られること。
     * 「すたば」で「スズ薬局」が残っていた不具合の再発を見張る。
     * 原因は「頭の字が一致すれば残す」段を設けていたことで、
     * **打たれた文字がすべて順序どおり現れる**という必須条件を破っていた。
     */
    @Test
    fun typing_more_narrows() {
        assertNotNull(Reading.match("スズ薬局", "す"))
        assertNull(Reading.match("スズ薬局", "すた"))
        assertNull(Reading.match("スズ薬局", "すたば"))
        assertNotNull(Reading.match("スズ薬局", "すず"))
    }

    /** 語頭から当たったものが、名前の途中で続けて当たったものより上に来ること。 */
    @Test
    fun head_match_outranks_inner_match() {
        val head = Reading.match("YouTube", "ゆ")
        val inner = Reading.match("ニュース", "ゆ")
        assertNotNull(head)
        assertNotNull(inner)
        assertTrue(head!! > inner!!)
    }

    @Test
    fun literal_match_outranks_reading_match() {
        val literal = Reading.match("ユニクロ", "ゆ")
        val reading = Reading.match("YouTube", "ゆ")
        assertNotNull(literal)
        assertNotNull(reading)
        assertTrue(literal!! > reading!!)
    }

    /**
     * 母音を当てにできない綴りは、子音の骨格で拾う。
     * `グーグル`(guuguru) と `Google` は母音の質が違い、`スラック` は英語に無い母音を足す。
     * ラ行は r とも l とも綴られるので一つへ寄せてある。
     */
    @Test
    fun consonant_skeleton_reaches_spellings_whose_vowels_differ() {
        assertNotNull(Reading.match("Google", "ぐーぐる"))
        assertNotNull(Reading.match("Slack", "すらっく"))
        assertNotNull(Reading.match("Starbucks", "すたば"))
    }

    /** 骨格が違えば当たらないこと（当たり過ぎの歯止め）。 */
    @Test
    fun consonant_skeleton_still_discriminates() {
        assertNull(Reading.match("Google", "ちず"))
        assertNull(Reading.match("Chrome", "ぐーぐる"))
    }

    /** 骨格で拾ったものは、綴りどおりに当たったものより後ろへ並ぶこと。 */
    @Test
    fun skeleton_match_ranks_below_a_spelled_match() {
        val skeleton = Reading.match("Starbucks", "すたば")
        val spelled = Reading.match("Starbucks", "star")
        assertNotNull(skeleton)
        assertNotNull(spelled)
        assertTrue(spelled!! > skeleton!!)
    }
}
