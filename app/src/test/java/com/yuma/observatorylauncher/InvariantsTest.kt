package com.yuma.observatorylauncher

import com.yuma.observatorylauncher.data.Reading
import com.yuma.observatorylauncher.data.Yomi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手で書いた表が**黙って壊れていないか**を見る。
 *
 * 辞書も仮名の対応表も、書き間違えても例外にはならない。
 * 「なぜか当たらない」という形でしか現れないので、目で追っても見つからない。
 * ここで捕まえる。
 */
class InvariantsTest {

    /** 辞書の自己点検。空でなければ、その文言がそのまま直すべき箇所である。 */
    @Test
    fun theDictionaryIsWellFormed() {
        val complaints = Yomi.audit()
        assertTrue(complaints.joinToString("\n"), complaints.isEmpty())
    }

    /**
     * 半角片仮名の対応。**位置で対にしている**ので、片方に一字足すと
     * そこから先が全部ずれる。ずれても長さが合っていれば例外は出ない。
     */
    @Test
    fun halfWidthKanaMapsToTheRightFullWidthKana() {
        val pairs = listOf(
            "ｱ" to "ア", "ｵ" to "オ", "ｶ" to "カ", "ｺ" to "コ", "ｻ" to "サ",
            "ｿ" to "ソ", "ﾀ" to "タ", "ﾄ" to "ト", "ﾅ" to "ナ", "ﾉ" to "ノ",
            "ﾊ" to "ハ", "ﾎ" to "ホ", "ﾏ" to "マ", "ﾓ" to "モ", "ﾔ" to "ヤ",
            "ﾖ" to "ヨ", "ﾗ" to "ラ", "ﾛ" to "ロ", "ﾜ" to "ワ", "ﾝ" to "ン",
            "ｦ" to "ヲ", "ｯ" to "ッ", "ｰ" to "ー", "ｬ" to "ャ", "ｮ" to "ョ",
        )
        // 片仮名は羅字へも倒されるので、**先頭**（書かれているとおりの表記）を見る。
        pairs.forEach { (half, full) ->
            assertEquals(half, full, Reading.forms(half).first())
        }
    }

    /** 濁点・半濁点は次の一字として来る。合成できないと `ｻｲﾌ` の類が崩れる。 */
    @Test
    fun voicingMarksAreCombined() {
        assertEquals("ガ", Reading.forms("ｶﾞ").first())
        assertEquals("パ", Reading.forms("ﾊﾟ").first())
        assertEquals("バ", Reading.forms("ﾊﾞ").first())
        assertEquals("ヴ", Reading.forms("ｳﾞ").first())
    }

    /** 半濁点が付くのはハ行だけ。範囲を絞らないと `ｶﾟ` が `キ` に化ける。 */
    @Test
    fun theHandakutenDoesNotBendOtherRows() {
        val forms = Reading.forms("ｶﾟ")
        assertTrue(forms.toString(), forms.none { it.contains('キ') || it.contains("ki") })
    }

    /** 実機に居る半角の名前が、普通の仮名で引けること。 */
    @Test
    fun theHalfWidthNameIsReachable() {
        assertEquals("オサイフケータイ", Reading.forms("おｻｲﾌｹｰﾀｲ").first())
    }

    /** 読みは必ず一つ以上返る。空を返すと照合の段が丸ごと落ちる。 */
    @Test
    fun everyNameYieldsAtLeastOneForm() {
        val names = listOf(
            "設定", "X", "VWFNDR™ + MBL", "31Club", "おｻｲﾌｹｰﾀｲ",
            "関西ＵＦＪ銀行", "Windows にリンク", "ToDo リスト", "会員CLUB",
        )
        names.forEach { assertTrue(it, Reading.forms(it).isNotEmpty()) }
    }

    /** 読みを開く段は、表に無い漢字が来ても落ちない（開けないだけ）。 */
    @Test
    fun unknownKanjiDoesNotBreakTheOpening() {
        listOf("鬱蒼", "彁", "𠮷野家", "設定鬱").forEach {
            assertTrue(it, Reading.forms(it).isNotEmpty())
        }
    }
}
