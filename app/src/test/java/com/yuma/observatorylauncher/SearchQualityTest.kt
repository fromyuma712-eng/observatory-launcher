package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.data.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 検索の質を、**実機に入っている表示名そのもの**に対して測る。
 *
 * 一覧は端末から書き出したものである（`関西ＵＦＪ銀行` の全角、`おｻｲﾌｹｰﾀｲ` の半角片仮名まで
 * 実物どおり）。作り物の一覧で測ると、実際に落ちている形が試験をすり抜ける——
 * 半角片仮名が当たらないことも、`Windows にリンク` が当たらないことも、
 * 端末から名前を取ってきて初めて見つかった。
 *
 * **採点の規則は互いに釣り合っているので、一つ変えると別のところが沈む。**
 * 個々の規則の単体試験では足りないため、実際に打ちそうな語を一括で流す。
 */
class SearchQualityTest {

    private val labels = listOf(
        "31Club",
        "Amazon ショッピング",
        "Apple Music",
        "Audio Connect",
        "ChatGPT",
        "Chrome",
        "Claude",
        "DOMリモコン",
        "Discord",
        "Files",
        "Find Hub",
        "Fit",
        "GO",
        "GU",
        "Gemini",
        "Gmail",
        "SilverPoint",
        "Google",
        "Google One",
        "Google TV",
        "Health",
        "KFC",
        "Keep メモ",
        "LINE",
        "MUJI",
        "Meet",
        "My Pixel",
        "NERV防災",
        "NinjaMustDie",
        "Notebook",
        "Notion",
        "Now Playing",
        "Outlook",
        "PayPay",
        "Play ストア",
        "Prime Video",
        "Rocket Now",
        "Slack",
        "Starbucks",
        "Tailscale",
        "Teams",
        "Termius",
        "TimeTree",
        "ToDo リスト",
        "VWFNDR™ + MBL",
        "Watch",
        "Windows にリンク",
        "WordHolic",
        "X",
        "YT Music",
        "Yodobashi",
        "YouTube",
        "iGrow",
        "skyticket",
        "おｻｲﾌｹｰﾀｲ",
        "保険グループ",
        "ウォレット",
        "カメラ",
        "カラオケ予約",
        "カレンダー",
        "ジャングル",
        "スズ薬局",
        "スプレッドシート",
        "セブン-イレブン",
        "ドキュメント",
        "ドライブ",
        "ニュース",
        "バーガーキング",
        "パスワード",
        "フォト",
        "ホロドリ",
        "マクドナルド",
        "マップ",
        "メッセージ",
        "レコーダー",
        "交通カード",
        "関西ＵＦＪ銀行",
        "天気情報",
        "会員CLUB",
        "時計",
        "家計銀行",
        "緊急情報",
        "翻訳",
        "設定",
        "連絡先",
        "大学ポータル",
        "電卓",
        "電話"
    )

    private fun hits(query: String, tolerance: Int): List<String> =
        labels.mapNotNull { l -> Reading.match(l, query, tolerance)?.let { l to it } }
            .sortedByDescending { it.second }
            .map { it.first }

    /**
     * 画面側([rank])と同じ二段構え。厳密な段で足りたときは寛容な段へ行かず、
     * 行くときも**厳密に当たったものの下へ足すだけ**にする。
     */
    private fun ranked(query: String): List<String> {
        val strict = hits(query, 0)
        if (strict.size >= 3) return strict
        val allowance = Reading.allowance(query.length)
        if (allowance <= 0) return strict
        return strict + hits(query, allowance).filterNot { it in strict }
    }

    private fun best(query: String): String? = ranked(query).firstOrNull()

    /** 実際に打ちそうな語の一式。**一位に来なければ落第**とする。 */
    @Test
    fun theBattery() {
        val cases = listOf(
            "ぺいぺい" to "PayPay",
            "すたば" to "Starbucks",
            "すたーばっくす" to "Starbucks",
            "せぶん" to "セブン-イレブン",
            "まくど" to "マクドナルド",
            "まくどなるど" to "マクドナルド",
            "ばーがーきんぐ" to "バーガーキング",
            "ようつべ" to "YouTube",
            "ゆーちゅーぶ" to "YouTube",
            "ぐーぐる" to "Google",
            "くろーむ" to "Chrome",
            "でぃすこーど" to "Discord",
            "すらっく" to "Slack",
            "のーしょん" to "Notion",
            "くろーど" to "Claude",
            "じぇみに" to "Gemini",
            "じーめーる" to "Gmail",
            "まっぷ" to "マップ",
            "かれんだー" to "カレンダー",
            "ふぉと" to "フォト",
            "どらいぶ" to "ドライブ",
            "めっせーじ" to "メッセージ",
            "かめら" to "カメラ",
            "とけい" to "時計",
            "でんたく" to "電卓",
            "でんわ" to "電話",
            "せってい" to "設定",
            "れんらくさき" to "連絡先",
            "ほんやく" to "翻訳",
            "てんき" to "天気情報",
            "かけい" to "家計銀行",
            "かんさい" to "関西ＵＦＪ銀行",
            "こうつう" to "交通カード",
            "だいがく" to "大学ポータル",
            "すず" to "スズ薬局",
            "やっきょく" to "スズ薬局",
            "かいいん" to "会員CLUB",
            "じゃんぐる" to "ジャングル",
            "りもこん" to "DOMリモコン",
            "よどばし" to "Yodobashi",
            "ぱすわーど" to "パスワード",
            "あうとるっく" to "Outlook",
            "ちーむず" to "Teams",
            "ぷらいむ" to "Prime Video",
            "あまぞん" to "Amazon ショッピング",
            "ぼうさい" to "NERV防災",
            "おさいふけーたい" to "おｻｲﾌｹｰﾀｲ",
            "すぷれっどしーと" to "スプレッドシート",
            "どきゅめんと" to "ドキュメント",
            "にゅーす" to "ニュース",
            "ぷれいすとあ" to "Play ストア",
            "れこーだー" to "レコーダー",
            "うぉれっと" to "ウォレット",
            "からおけ" to "カラオケ予約",
            "えっくす" to "X",
            "ほろどり" to "ホロドリ",
            "ほけん" to "保険グループ",
            "たいむつりー" to "TimeTree",
            "あっぷるみゅーじっく" to "Apple Music",
            "ういんどうず" to "Windows にリンク",
            "きんきゅう" to "緊急情報",
            "ぐーぐるてぃーびー" to "Google TV",
            "ふぁいるず" to "Files",
            "みーと" to "Meet"
        )
        val missed = cases.filterNot { (q, want) -> best(q) == want }
        assertTrue(
            "一位を外した: " + missed.joinToString { it.first + "->" + best(it.first) },
            missed.isEmpty(),
        )
    }

    /**
     * 一位ではないが上位には出る語。**取りこぼしてはいない**ことだけを守る。
     *
     * `きーぷ` は `キープ` の長音が英語では `ee` と綴られるため骨格でしか当たらず、
     * `たーみうす` は `Teams` の骨格 `tms` に丸ごと一致してしまう。
     * ここを一位に押し上げようとすると別の語が沈むので、二番手を許容する。
     */
    @Test
    fun theseStayReachable() {
        val cases = listOf(
            "きーぷ" to "Keep メモ",
            "たーみうす" to "Termius"
        )
        cases.forEach { (q, want) ->
            assertTrue(q + " -> " + ranked(q).take(3), want in ranked(q).take(3))
        }
    }

    /** 漢字の名前。辞書で読みへ開かないかぎり、一文字も共有しないので当たらない。 */
    @Test
    fun kanjiNamesAreReachableByKana() {
        assertEquals("設定", best("せってい"))
        assertEquals("時計", best("とけい"))
        assertEquals("電話", best("でんわ"))
        assertEquals("スズ薬局", best("やっきょく"))
    }

    /**
     * 欧字を字の名で読む。`GPT` を「じーぴーてぃー」と打つ人のための段。
     *
     * **一文字の名前は字の名でしか打てない**ので、二文字以上という条件から外してある
     * （`X` を「えっくす」で引けること。本人が要件として名指ししている）。
     */
    @Test
    fun latinLettersAreReachableByTheirNames() {
        assertEquals("ChatGPT", best("ちゃっとじーぴーてぃー"))
        assertEquals("X", best("えっくす"))
        assertEquals("X", best("ekkusu"))
    }

    /** 打ち間違い。厳密な段で足りないときだけ、落としてよい文字数の範囲で許す。 */
    @Test
    fun typosAreForgivenWithinBudget() {
        assertEquals("YouTube", best("youtub"))
        assertEquals("Google", best("gogle"))
        assertEquals("Slack", best("slak"))
        assertEquals("Chrome", best("chorme"))
    }

    /** 短い語は許さない。許すと何にでも当たり、絞るという目的を失う。 */
    @Test
    fun shortQueriesAreNotForgiven() {
        assertEquals(0, Reading.allowance(1))
        assertEquals(0, Reading.allowance(3))
        assertEquals(1, Reading.allowance(4))
        assertEquals(1, Reading.allowance(7))
        assertEquals(2, Reading.allowance(8))
        assertNull(Reading.match("Slack", "sxk", 2))
    }

    /** 打ち間違いを許した当たりは、厳密に当たったものより必ず下に来る。 */
    @Test
    fun forgivenMatchesRankBelowStrictOnes() {
        val strict = Reading.match("Google", "google", 0)
        val forgiven = Reading.match("Google", "googlex", 2)
        assertNotNull(strict)
        assertNotNull(forgiven)
        assertTrue(strict.toString() + " > " + forgiven, strict!! > forgiven!!)
    }

    /** 一文字目を打った時点で、書かれているとおりの名前が読みで開いた名前より上に来る。 */
    @Test
    fun asWrittenBeatsOpenedReadings() {
        assertEquals("YouTube", best("ゆ"))
    }

    /** 打ち足すたびに候補は減る。増えるなら規則のどこかが破れている。 */
    @Test
    fun typingMoreNarrowsTheResults() {
        val counts = listOf("す", "すた", "すたば").map { hits(it, 0).size }
        counts.zipWithNext().forEach { (wide, narrow) ->
            assertTrue(counts.toString(), narrow <= wide)
        }
        assertEquals("Starbucks", best("すたば"))
    }
}
