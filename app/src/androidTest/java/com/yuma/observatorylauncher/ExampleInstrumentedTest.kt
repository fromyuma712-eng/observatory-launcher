package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.config.LayoutConfig
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4

import org.junit.Test
import org.junit.runner.RunWith

import org.junit.Assert.*

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.yuma.observatorylauncher", appContext.packageName)
    }

    @Test
    fun oldClockFlagsMigrateToMovableInformationTiles() {
        val oldJson = """
            {
              "schema": 1,
              "display": {"iconSize": 40, "showLabels": false},
              "info": {
                "showWeather": true,
                "showCalendar": true,
                "showAlarm": true,
                "showBattery": false
              },
              "sections": [],
              "groups": [],
              "sites": [],
              "widgets": []
            }
        """.trimIndent()

        val migrated = requireNotNull(LayoutConfig.parse(oldJson))

        assertEquals(
            listOf(
                LayoutConfig.INFO_WEATHER,
                LayoutConfig.INFO_CALENDAR,
                LayoutConfig.INFO_ALARM,
            ),
            migrated.infoTiles.map { it.kind },
        )
        assertTrue(migrated.infoTiles.all { it.page == 0 })
        assertTrue(migrated.infoTiles.all { it.zone == LayoutConfig.ZONE_ABOVE })
    }

    @Test
    fun informationAndWidgetPlacementRoundTripsThroughJson() {
        val original = LayoutConfig(
            sections = listOf(
                LayoutConfig.Section("常用", emptyList(), page = 0),
                LayoutConfig.Section("ページ2", emptyList(), page = 1),
            ),
            pageCount = 2,
            infoTiles = listOf(
                LayoutConfig.InfoTile(
                    id = "info:test",
                    kind = LayoutConfig.INFO_NOTE,
                    page = 1,
                    zone = LayoutConfig.ZONE_BELOW,
                    span = 2,
                    title = "メモ",
                    value = "本文",
                )
            ),
            widgets = listOf(
                LayoutConfig.Widget(
                    id = 42,
                    height = 180,
                    span = 3,
                    page = 1,
                    zone = LayoutConfig.ZONE_ABOVE,
                )
            ),
        )

        val parsed = requireNotNull(LayoutConfig.parse(original.toJson()))

        assertEquals(original.infoTiles, parsed.infoTiles)
        assertEquals(original.widgets, parsed.widgets)
        assertEquals(2, parsed.pageCount)
    }
}
