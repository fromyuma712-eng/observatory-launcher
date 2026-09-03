package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.config.LayoutConfig
import com.yuma.observatorylauncher.config.LayoutStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 保管庫の試験。世代・巻き戻し・控えの読み書きを、実機を使わずに確かめる。
 * ここが壊れると**持ち物が失われる**——画面の不具合と違い、後から直せない。
 */
class LayoutStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store(): LayoutStore = LayoutStore(folder.root, { LayoutStore.EMPTY_SEED })

    private fun withSection(name: String): LayoutConfig =
        LayoutConfig(sections = listOf(LayoutConfig.Section(name, listOf("a.pkg"))))

    @Test
    fun first_load_writes_the_seed() {
        val loaded = store().load()
        assertNotNull(loaded)
        assertTrue(folder.root.resolve("layout.json").exists())
    }

    @Test
    fun save_then_load_returns_the_same_declaration() {
        val s = store()
        s.load()
        s.save(withSection("常用"))
        assertEquals(listOf("常用"), s.load().sections.map { it.title })
    }

    /** 書き換えるたびに世代が積まれること。積まれなければ巻き戻せない。 */
    @Test
    fun each_save_adds_one_generation() {
        val s = store()
        s.load()
        assertEquals(0, s.generationCount())
        s.save(withSection("一"))
        assertEquals(1, s.generationCount())
        s.save(withSection("二"))
        assertEquals(2, s.generationCount())
    }

    @Test
    fun rollback_returns_the_previous_declaration() {
        val s = store()
        s.load()
        s.save(withSection("一"))
        s.save(withSection("二"))
        val back = s.rollback()
        assertNotNull(back)
        assertEquals(listOf("一"), back!!.sections.map { it.title })
        assertEquals(listOf("一"), s.load().sections.map { it.title })
    }

    /** 巻き戻しを繰り返すと、初期状態まで遡って止まること（往復に陥らない）。 */
    @Test
    fun rollback_walks_back_and_then_stops() {
        val s = store()
        s.load()
        s.save(withSection("一"))
        s.save(withSection("二"))
        s.save(withSection("三"))
        assertEquals(listOf("二"), s.rollback()!!.sections.map { it.title })
        assertEquals(listOf("一"), s.rollback()!!.sections.map { it.title })
        assertNotNull(s.rollback())
        assertNull(s.rollback())
    }

    /** 巻き戻した後に書き換えても、履歴が壊れないこと。 */
    @Test
    fun saving_after_rollback_keeps_history_consistent() {
        val s = store()
        s.load()
        s.save(withSection("一"))
        s.save(withSection("二"))
        s.rollback()
        s.save(withSection("三"))
        assertEquals(listOf("三"), s.load().sections.map { it.title })
        assertEquals(listOf("一"), s.rollback()!!.sections.map { it.title })
    }

    // ---- 控え ------------------------------------------------------------

    @Test
    fun export_then_import_restores_the_same_declaration() {
        val s = store()
        s.load()
        s.save(withSection("常用"))
        val backup = s.exportText()

        s.save(LayoutConfig(sections = emptyList()))
        assertTrue(s.load().sections.isEmpty())

        val restored = s.importText(backup)
        assertNotNull(restored)
        assertEquals(listOf("常用"), s.load().sections.map { it.title })
    }

    /** 控えから戻すこと自体も一つの書き換え。取り消せなければ事故になる。 */
    @Test
    fun import_can_be_rolled_back() {
        val s = store()
        s.load()
        s.save(withSection("元の姿"))
        val before = s.load()
        s.importText(LayoutConfig(sections = listOf(LayoutConfig.Section("別物", emptyList()))).toJson())
        assertEquals(listOf("別物"), s.load().sections.map { it.title })
        assertEquals(before.sections.map { it.title }, s.rollback()!!.sections.map { it.title })
    }

    /** 壊れた控えで上書きしないこと。 */
    @Test
    fun broken_backup_is_refused_and_changes_nothing() {
        val s = store()
        s.load()
        s.save(withSection("守るべき値"))
        assertNull(s.importText("壊れている"))
        assertEquals(listOf("守るべき値"), s.load().sections.map { it.title })
    }

    // ---- 世代の上限 ------------------------------------------------------

    /**
     * 地層は積むものだが、無限に積めるものではない。
     * 上限を超えたら古い方から捨て、**捨てた分は履歴からも外す**
     * ——参照だけ残ると「押しても戻らない項目」になる。
     */
    @Test
    fun generations_are_capped_and_history_stays_usable() {
        val s = store()
        s.load()
        repeat(80) { i -> s.save(withSection("第${i}版")) }

        val files = folder.root.resolve("generations").listFiles()!!.size
        assertTrue("残った世代=$files", files <= 60)
        assertTrue(s.generationCount() <= 60)

        // 履歴が残っている限り、押せば必ず戻ること
        var steps = 0
        while (s.rollback() != null) steps++
        assertTrue("巻き戻せた回数=$steps", steps > 0)
        assertEquals(0, s.generationCount())
    }

    /** 世代の実体が失われても、そこで詰まらずに次を見ること。 */
    @Test
    fun rollback_skips_generations_whose_file_is_gone() {
        val s = store()
        s.load()
        s.save(withSection("一"))
        s.save(withSection("二"))
        s.save(withSection("三"))

        val newest = folder.root.resolve("generations").listFiles()!!.maxByOrNull { it.name }!!
        assertTrue(newest.delete())

        val back = s.rollback()
        assertNotNull(back)
    }
}
