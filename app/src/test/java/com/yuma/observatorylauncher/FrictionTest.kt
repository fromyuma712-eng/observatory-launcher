package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.data.Friction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 摩擦の試験。
 *
 * 強さの決め方を間違えても例外は出ない。**ただ、掛かるべきときに掛からない**
 * （あるいはその逆になる）だけなので、境目を一つずつ押さえる。
 */
class FrictionTest {

    private fun min(n: Long) = n * 60_000

    // ── 使用時間の軸 ───────────────────────────────────────

    /** 使い始めは妨げない。一度も見ていない朝に問題を出すのは、ただの嫌がらせである。 */
    @Test
    fun theFirstTenMinutesArePassedThrough() {
        assertEquals(Friction.Level.NONE, Friction.fromUsage(0))
        assertEquals(Friction.Level.NONE, Friction.fromUsage(min(9)))
    }

    @Test
    fun usageRaisesTheLevelInSteps() {
        assertEquals(Friction.Level.NOTICE, Friction.fromUsage(min(10)))
        assertEquals(Friction.Level.ONE, Friction.fromUsage(min(20)))
        assertEquals(Friction.Level.TWO, Friction.fromUsage(min(30)))
        assertEquals(Friction.Level.THREE, Friction.fromUsage(min(45)))
        assertEquals(Friction.Level.THREE, Friction.fromUsage(min(300)))
    }

    // ── 再入場の軸（本命） ─────────────────────────────────

    /** 一時間空いていれば、それは意図して開いている。掛けない。 */
    @Test
    fun anHourAwayMeansNoFriction() {
        assertEquals(Friction.Level.NONE, Friction.fromReentry(min(60)))
        assertEquals(Friction.Level.NONE, Friction.fromReentry(min(600)))
    }

    /** **すぐ戻るほど重い。** 三分前に閉じたばかりなら、ほぼ確実に無意識である。 */
    @Test
    fun returningSoonerMeansHeavierFriction() {
        assertEquals(Friction.Level.NOTICE, Friction.fromReentry(min(30)))
        assertEquals(Friction.Level.ONE, Friction.fromReentry(min(10)))
        assertEquals(Friction.Level.TWO, Friction.fromReentry(min(3)))
        assertEquals(Friction.Level.THREE, Friction.fromReentry(min(2)))
        assertEquals(Friction.Level.THREE, Friction.fromReentry(0))
    }

    /** 今日まだ開いていないなら掛けない。**分からないことを理由に止めない。** */
    @Test
    fun anUnknownLastCloseMeansNoFriction() {
        assertEquals(Friction.Level.NONE, Friction.fromReentry(null))
    }

    // ── 合成 ───────────────────────────────────────────────

    /** 二つの軸の厳しい方を採る。 */
    @Test
    fun theStricterAxisWins() {
        // 今日5分・最後は2時間前 → 素通り
        assertEquals(Friction.Level.NONE, Friction.level(min(5), min(120)))
        // 今日45分・最後は50分前 → 使用時間の側が三問
        assertEquals(Friction.Level.THREE, Friction.level(min(45), min(50)))
        // 今日5分・最後は2分前 → 再入場の側が三問
        assertEquals(Friction.Level.THREE, Friction.level(min(5), min(2)))
        // 今日25分・最後は40分前 → 使用時間が一問、再入場が表示のみ → 一問
        assertEquals(Friction.Level.ONE, Friction.level(min(25), min(40)))
    }

    @Test
    fun theQuestionCountFollowsTheLevel() {
        assertEquals(0, Friction.Level.NONE.questions)
        assertEquals(0, Friction.Level.NOTICE.questions)
        assertEquals(1, Friction.Level.ONE.questions)
        assertEquals(3, Friction.Level.THREE.questions)
        assertFalse(Friction.Level.NONE.stops)
        assertTrue(Friction.Level.NOTICE.stops)
    }

    // ── 問い ───────────────────────────────────────────────

    /** 作った問いの答えは、式と合っている。 */
    @Test
    fun everyQuestionHasTheRightAnswer() {
        val random = Random(20260901)
        repeat(200) {
            val q = Friction.question(random)
            val value = evaluate(q.text)
            assertEquals(q.text, value, q.answer)
        }
    }

    /** 割り算は必ず割り切れる。**余りの出る式を暗算させない。** */
    @Test
    fun divisionsAreAlwaysExact() {
        val random = Random(7)
        repeat(200) {
            val q = Friction.question(random)
            if ("÷" in q.text) {
                val parts = q.text.split(" ")
                assertEquals(0, parts[0].toInt() % parts[2].toInt())
            }
        }
    }

    /** 同じ式を二度出さない。同じ答えを打つだけになる。 */
    @Test
    fun questionsAreDistinct() {
        val list = Friction.questions(3, Random(42))
        assertEquals(3, list.size)
        assertEquals(3, list.map { it.text }.toSet().size)
    }

    /** 答え合わせは空白を許し、数字でなければ不正解。 */
    @Test
    fun theAnswerCheckIsForgivingAboutSpaces() {
        val q = Friction.Question("1 + 1", 2)
        assertTrue(Friction.correct(q, "2"))
        assertTrue(Friction.correct(q, "  2 "))
        assertFalse(Friction.correct(q, ""))
        assertFalse(Friction.correct(q, "に"))
        assertFalse(Friction.correct(q, "3"))
    }

    // ── 言葉 ───────────────────────────────────────────────

    /** **責めない。** 事実だけを置く。 */
    @Test
    fun theNoticeStatesFactsOnly() {
        val soon = Friction.notice("X", min(27), min(3))
        assertTrue(soon, soon.contains("3 分前"))
        assertTrue(soon, soon.contains("27 分"))
        // 問いかけや否定の語を含まない。
        listOf("本当に", "ですか", "やめ", "また").forEach {
            assertFalse(soon, soon.contains(it))
        }
    }

    /** 式を評価する（試験のためだけの簡単な評価器）。 */
    private fun evaluate(text: String): Int {
        val p = text.split(" ")
        val a = p[0].toInt()
        val b = p[2].toInt()
        val c = p[4].toInt()
        val first = if (p[1] == "×") a * b else a / b
        return if (p[3] == "−") first - c else first + c
    }
}
