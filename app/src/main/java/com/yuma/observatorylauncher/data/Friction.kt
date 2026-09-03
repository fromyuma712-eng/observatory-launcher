package com.yuma.observatorylauncher.data

import kotlin.random.Random

/**
 * 摩擦。
 *
 * ## 何のためのものか
 *
 * SNSを**禁止しない**。使えるが、無意識に開くことを難しくする。
 * 狙いは難しい問題を解かせることではなく、
 * **反射でアイコンを押した状態から、一度考える状態へ戻すこと**である。
 *
 * ## ⚠️ 効く範囲には限りがある
 *
 * Android はホームアプリに、他のアプリの起動を止める権限を与えない。
 * 掛けられるのは**このランチャーの行から起動したときだけ**で、
 * 通知・ウィジェット・最近使用からは素通りする。
 *
 * それでも無意識のタップの大半はホーム経由なので、効果は残る。
 * **半分しか掛からないことを承知の上で置く**——
 * 完全でないことは、置かない理由にはならない。
 *
 * ## 強さの決め方
 *
 * 二つの軸から決め、**厳しい方を採る**。
 *
 * - 今日その手のアプリに使った時間（使うほど重くなる）
 * - 前回閉じてからの時間（**すぐ戻るほど重くなる**）
 *
 * 後者が本命である。総量よりも、閉じた数分後にまた開く反復こそが
 * 無意識の行動だからである。
 */
internal object Friction {

    /** 掛ける強さ。 */
    enum class Level {
        /** 何もしない。 */
        NONE,

        /** 使用時間を見せるだけ。止めはしない。 */
        NOTICE,

        /** 一問。 */
        ONE,

        /** 二問。 */
        TWO,

        /** 三問。 */
        THREE,
        ;

        val questions: Int
            get() = when (this) {
                NONE, NOTICE -> 0
                ONE -> 1
                TWO -> 2
                THREE -> 3
            }

        val stops: Boolean get() = this != NONE
    }

    private val ORDER = listOf(Level.NONE, Level.NOTICE, Level.ONE, Level.TWO, Level.THREE)

    /**
     * 今日の使用時間から決まる強さ。
     *
     * 0〜10分は素通り。**使い始めを妨げない**——
     * 一度も見ていない朝に問題を出されるのは、ただの嫌がらせである。
     */
    fun fromUsage(usedMs: Long): Level {
        val minutes = usedMs / 60_000
        return when {
            minutes < 10 -> Level.NONE
            minutes < 20 -> Level.NOTICE
            minutes < 30 -> Level.ONE
            minutes < 45 -> Level.TWO
            else -> Level.THREE
        }
    }

    /**
     * 前回閉じてからの時間で決まる強さ。
     *
     * **これが本命。** 一時間も空いていれば、それは意図して開いている。
     * 三分前に閉じたばかりなら、それはほぼ確実に無意識である。
     *
     * 前回が分からない（今日はまだ開いていない）ときは掛けない。
     */
    fun fromReentry(sinceCloseMs: Long?): Level {
        val minutes = (sinceCloseMs ?: return Level.NONE) / 60_000
        return when {
            minutes >= 60 -> Level.NONE
            minutes >= 30 -> Level.NOTICE
            minutes >= 10 -> Level.ONE
            minutes >= 3 -> Level.TWO
            else -> Level.THREE
        }
    }

    /** 二つの軸の**厳しい方**を採る。 */
    fun level(usedMs: Long, sinceCloseMs: Long?): Level {
        val a = fromUsage(usedMs)
        val b = fromReentry(sinceCloseMs)
        return if (ORDER.indexOf(a) >= ORDER.indexOf(b)) a else b
    }

    // ---- 問い -------------------------------------------------------------

    /** 一問。答えは整数一つ。 */
    data class Question(val text: String, val answer: Int)

    /**
     * 暗算を一問作る。
     *
     * **数秒で解ける程度にする。** 難しすぎると、摩擦そのものを外したくなる
     * ——外されたら効果は 0 である。狙うのは軽い認知の負荷だけ。
     *
     * 形は `a × b − c` と `a ÷ b + c` の二つ。割り算は必ず割り切れる形で作る。
     */
    fun question(random: Random = Random.Default): Question =
        if (random.nextBoolean()) {
            val a = random.nextInt(12, 20)
            val b = random.nextInt(4, 9)
            val c = random.nextInt(10, 50)
            Question("$a × $b − $c", a * b - c)
        } else {
            val b = random.nextInt(3, 13)
            val q = random.nextInt(4, 13)
            val c = random.nextInt(10, 40)
            Question("${b * q} ÷ $b + $c", q + c)
        }

    /** 必要な数だけ作る。**同じ式を二度出さない**——同じ答えを打つだけになる。 */
    fun questions(count: Int, random: Random = Random.Default): List<Question> {
        val out = ArrayList<Question>()
        var guard = 0
        while (out.size < count && guard < 100) {
            val q = question(random)
            if (out.none { it.text == q.text }) out += q
            guard++
        }
        return out
    }

    /** 答え合わせ。空白は無視し、数字でなければ不正解とする。 */
    fun correct(question: Question, typed: String): Boolean =
        typed.trim().toIntOrNull() == question.answer

    // ---- 言葉 -------------------------------------------------------------

    /**
     * 止めるときに見せる一行。
     *
     * **責めない。** 「また開くのですか」と書くと、道具が人を裁くことになる。
     * 事実だけを置いて、判断は本人に返す。
     */
    fun notice(label: String, usedMs: Long, sinceCloseMs: Long?): String {
        val used = usedMs / 60_000
        val since = sinceCloseMs?.let { it / 60_000 }
        return when {
            since != null && since < 10 -> "$since 分前にも開いています。今日 $used 分。"
            used > 0 -> "今日 $label を $used 分。"
            else -> "今日はまだ開いていません。"
        }
    }
}
