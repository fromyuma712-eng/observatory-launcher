package com.yuma.observatorylauncher.data

/**
 * 漢字と欧字を「打てる音」へ開く。
 *
 * 検索が当たらない最大の原因は、**表示名が漢字で、打つ側が仮名**だからである。
 * `設定` を「せってい」と打っても、字面の上では一文字も共有していない。
 * 綴りをいくら賢く比べても、読みを持たないかぎり永久に当たらない。
 *
 * 先例としては Migemo（高林哲, 2000）がある。ローマ字のまま日本語を引くために、
 * **辞書で読みと表記を結ぶ**という方法をとる。ここも同じ立場をとる——
 * 推測で当てにいくのではなく、読みは辞書から引く。
 *
 * ただし Migemo は打った側から正規表現を組み立てる。こちらは逆に
 * **表示名の側を読みへ開いて候補に加える**。引く相手が高々数百件と分かっているので、
 * 表記を揃えてしまう方が単純で、[Reading] の採点をそのまま使い回せる。
 *
 * 辞書は端末に持てる大きさに絞る。ここに無い漢字は開けないが、
 * 開けなかったところで**元の表記での照合は残る**ので、悪化はしない。
 */
internal object Yomi {

    /** 一件の表示名から作る読みの上限。組み合わせは掛け算で増えるので頭を抑える。 */
    private const val MAX_FORMS = 6

    /**
     * [text] を読みへ開いた別表記を返す。開くところが無ければ空。
     *
     * 開き方は二つある。**漢字を辞書で読みへ**、**大文字の並びを字の名へ**。
     * 後者は `ChatGPT` の `GPT` を「ジーピーティー」と打つ人のためのもので、
     * 日本語話者が欧字を読むときの規則そのものである。
     */
    fun variants(text: String): List<String> {
        val seeds = buildList {
            add(text)
            letterNames(text)?.let { add(it) }
        }
        val out = LinkedHashSet<String>()
        seeds.forEach { seed ->
            if (seed != text) out.add(seed)
            kanjiReadings(seed).forEach { out.add(it) }
        }
        out.remove(text)
        return out.take(MAX_FORMS)
    }

    // ---- 漢字を読みへ -----------------------------------------------------

    /**
     * 漢字を含む並びを読みへ開く。**長い語から先に当てる**——
     * `時計` を `時`+`計` に割ると「ジケイ」になってしまい、「とけい」に届かない。
     * 熟字訓のように字を足しても読みが出ない語があるので、語の辞書を先に引く。
     */
    private fun kanjiReadings(text: String): List<String> {
        if (text.none { it.isKanji() }) return emptyList()
        var branches = mutableListOf(StringBuilder())
        var opened = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (!ch.isKanji()) {
                branches.forEach { it.append(ch) }
                i++
                continue
            }
            // 語の辞書を長い方から引く。
            var readings: List<String>? = null
            var width = 0
            for (len in minOf(MAX_WORD, text.length - i) downTo 2) {
                val word = text.substring(i, i + len)
                val found = WORDS[word]
                if (found != null) {
                    readings = found
                    width = len
                    break
                }
            }
            if (readings == null) {
                readings = CHARS[ch]
                width = 1
            }
            if (readings == null) {
                // 辞書に無い字はそのまま置く。ここだけ当たらなくなるが、
                // 前後の当たりは残る。
                branches.forEach { it.append(ch) }
                i++
                continue
            }
            opened = true
            val next = ArrayList<StringBuilder>(branches.size)
            for (branch in branches) {
                for (reading in readings) {
                    if (next.size >= MAX_FORMS) break
                    next.add(StringBuilder(branch).append(reading))
                }
                if (next.size >= MAX_FORMS) break
            }
            branches = next
            i += width
        }
        if (!opened) return emptyList()
        return branches.map { it.toString() }.distinct()
    }

    private fun Char.isKanji(): Boolean = this in '一'..'鿿' || this == '々'

    // ---- 欧字を字の名へ ---------------------------------------------------

    /**
     * 大文字の並びを字の名へ置き換える。`UFJ` → `ユーエフジェー`。
     *
     * 二文字以上の並びだけを対象にする。`Chrome` の頭の `C` まで「シー」にすると、
     * 語として読まれる名前まで崩れてしまう。ただし**名前そのものが一文字**のとき
     * （`X`）は、字の名で呼ぶ以外に打ちようがないので対象に含める。
     */
    private fun letterNames(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.length == 1 && trimmed[0].isLatinLetter()) {
            return LETTERS[trimmed[0].uppercaseChar()]
        }
        val sb = StringBuilder(text.length * 2)
        var changed = false
        var i = 0
        while (i < text.length) {
            if (!text[i].isUpperLatin()) {
                sb.append(text[i])
                i++
                continue
            }
            var j = i
            while (j < text.length && text[j].isUpperLatin()) j++
            val run = text.substring(i, j)
            if (run.length >= 2) {
                run.forEach { sb.append(LETTERS[it] ?: it.toString()) }
                changed = true
            } else {
                sb.append(run)
            }
            i = j
        }
        return if (changed) sb.toString() else null
    }

    private fun Char.isUpperLatin(): Boolean = this in 'A'..'Z'

    private fun Char.isLatinLetter(): Boolean = this in 'A'..'Z' || this in 'a'..'z'

    private val LETTERS: Map<Char, String> = mapOf(
        'A' to "エー", 'B' to "ビー", 'C' to "シー", 'D' to "ディー", 'E' to "イー",
        'F' to "エフ", 'G' to "ジー", 'H' to "エイチ", 'I' to "アイ", 'J' to "ジェー",
        'K' to "ケー", 'L' to "エル", 'M' to "エム", 'N' to "エヌ", 'O' to "オー",
        'P' to "ピー", 'Q' to "キュー", 'R' to "アール", 'S' to "エス", 'T' to "ティー",
        'U' to "ユー", 'V' to "ブイ", 'W' to "ダブリュー", 'X' to "エックス",
        'Y' to "ワイ", 'Z' to "ゼット",
    )


    /**
     * 辞書の自己点検。**黙って壊れる書き間違いを、試験で捕まえるための窓**である。
     *
     * 手で書いた表なので、次のような誤りは動かしても例外にならない——
     * 同じ見出しを二度書けば後が黙って勝ち、字の表に二字の語を書けば
     * 一字目だけが使われ、読みに平仮名が混ざれば照合の段で当たらなくなる。
     * どれも「当たらない」としてしか現れないので、目で追うのは当てにならない。
     */
    fun audit(): List<String> {
        val out = mutableListOf<String>()

        fun rows(source: String) = source.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { it.substringBefore(' ') to it.substringAfter(' ') }
            .toList()

        fun readings(row: Pair<String, String>) = row.second.split('|')

        val words = rows(WORDS_SRC)
        val chars = rows(CHARS_SRC)

        (words + chars).forEach { row ->
            if (row.second.isBlank()) out += "読みが空: ${row.first}"
            readings(row).forEach { reading ->
                val bad = reading.filterNot { it in 'ァ'..'ヶ' || it == 'ー' }
                if (bad.isNotEmpty()) out += "読みが片仮名でない: ${row.first} → $reading"
            }
        }

        words.forEach { (key, _) ->
            if (key.length < 2) out += "語の表に一字の見出し: $key"
            // MAX_WORD を超える見出しは**引かれることが一度も無い**。
            if (key.length > MAX_WORD) out += "語が長すぎて引かれない(${MAX_WORD}字まで): $key"
        }
        chars.forEach { (key, _) ->
            if (key.length != 1) out += "字の表に一字でない見出し: $key"
        }

        listOf("語" to words, "字" to chars).forEach { (name, table) ->
            table.groupBy { it.first }.filterValues { it.size > 1 }.keys.forEach {
                out += "${name}の表に重複した見出し: $it"
            }
        }
        return out
    }

    // ---- 辞書 -------------------------------------------------------------

    private const val MAX_WORD = 4

    private fun table(source: String): Map<String, List<String>> = buildMap {
        source.lineSequence().forEach { line ->
            val row = line.trim()
            if (row.isEmpty()) return@forEach
            val space = row.indexOf(' ')
            if (space <= 0) return@forEach
            put(row.substring(0, space), row.substring(space + 1).split('|'))
        }
    }

    private fun chars(source: String): Map<Char, List<String>> =
        table(source).entries.associate { (key, value) -> key[0] to value }

    /**
     * 語の辞書。**字を足しても読みが出ない語**（時計・薬局）と、
     * 端末に入っている名前に実際に現れる語を収める。
     */
    private val WORDS_SRC =
        """
        設定 セッテイ
        時計 トケイ
        時刻 ジコク
        時刻表 ジコクヒョウ
        電卓 デンタク
        電話 デンワ
        電池 デンチ
        電車 デンシャ
        電源 デンゲン
        連絡 レンラク
        連絡先 レンラクサキ
        連携 レンケイ
        写真 シャシン
        天気 テンキ
        音楽 オンガク
        音声 オンセイ
        翻訳 ホンヤク
        地図 チズ
        地下鉄 チカテツ
        地震 ジシン
        銀行 ギンコウ
        証券 ショウケン
        薬局 ヤッキョク
        関西 カンサイ
        大学 ダイガク
        特務 トクム
        機関 キカン
        資産 シサン
        形成 ケイセイ
        検索 ケンサク
        買物 カイモノ
        買い物 カイモノ
        予約 ヨヤク
        予報 ヨホウ
        予防 ヨボウ
        会員 カイイン
        会議 カイギ
        保険 ホケン
        保護 ホゴ
        保存 ホゾン
        交通 コウツウ
        乗換 ノリカエ
        乗り換え ノリカエ
        案内 アンナイ
        画面 ガメン
        通知 ツウチ
        通信 ツウシン
        時間 ジカン
        利用 リヨウ
        記録 キロク
        健康 ケンコウ
        歩数 ホスウ
        睡眠 スイミン
        体重 タイジュウ
        食事 ショクジ
        家計 カケイ
        収支 シュウシ
        明細 メイサイ
        残高 ザンダカ
        送金 ソウキン
        支払 シハライ
        支払い シハライ
        口座 コウザ
        番号 バンゴウ
        住所 ジュウショ
        新幹線 シンカンセン
        空港 クウコウ
        航空 コウクウ
        災害 サイガイ
        警報 ケイホウ
        防災 ボウサイ
        録音 ロクオン
        動画 ドウガ
        書籍 ショセキ
        漫画 マンガ
        雑誌 ザッシ
        新聞 シンブン
        放送 ホウソウ
        番組 バングミ
        映画 エイガ
        辞書 ジショ
        単語 タンゴ
        英語 エイゴ
        日本 ニホン|ニッポン
        日本語 ニホンゴ
        学習 ガクシュウ
        勉強 ベンキョウ
        授業 ジュギョウ
        試験 シケン
        成績 セイセキ
        出席 シュッセキ
        講義 コウギ
        図書 トショ
        図書館 トショカン
        教育 キョウイク
        教習所 キョウシュウジョ
        情報 ジョウホウ
        緊急 キンキュウ
        管理 カンリ
        開発 カイハツ
        端末 タンマツ
        接続 セツゾク
        充電 ジュウデン
        位置 イチ
        現在地 ゲンザイチ
        履歴 リレキ
        削除 サクジョ
        編集 ヘンシュウ
        追加 ツイカ
        共有 キョウユウ
        認証 ニンショウ
        安全 アンゼン
        個人 コジン
        生活 セイカツ
        仕事 シゴト
        求人 キュウジン
        転職 テンショク
        給与 キュウヨ
        免許 メンキョ
        運転 ウンテン
        合宿 ガッシュク
        病院 ビョウイン
        診療 シンリョウ
        郵便 ユウビン
        配送 ハイソウ
        宅配 タクハイ
        注文 チュウモン
        購入 コウニュウ
        決済 ケッサイ
        請求 セイキュウ
        領収 リョウシュウ
        割引 ワリビキ
        特典 トクテン
        還元 カンゲン
        本人 ホンニン
        確認 カクニン
        登録 トウロク
        変更 ヘンコウ
        再生 サイセイ
        自分 ジブン
        株価 カブカ
        投資 トウシ
        信託 シンタク
        積立 ツミタテ
        貯金 チョキン
        預金 ヨキン
        入金 ニュウキン
        出金 シュッキン
        振込 フリコミ
        明日 アシタ
        今日 キョウ
        毎日 マイニチ
        週間 シュウカン
        月間 ゲッカン
        年間 ネンカン
        """.trimIndent()

    private val WORDS: Map<String, List<String>> = table(WORDS_SRC)


    /**
     * 字の辞書。語の辞書で拾えなかった漢字を一字ずつ開く。
     * 読みは**名前に現れやすい順**に並べる。前の方から組み合わせを作るためである。
     */
    private val CHARS_SRC =
        """
        設 セツ
        定 テイ|ジョウ
        時 ジ|トキ
        計 ケイ
        電 デン
        卓 タク
        話 ワ|ハナシ
        連 レン
        絡 ラク
        先 サキ|セン
        写 シャ|ウツ
        真 シン|マ
        天 テン|アマ
        気 キ|ケ
        音 オン|オト
        楽 ガク|ラク|タノ
        翻 ホン
        訳 ヤク
        地 チ|ジ
        図 ズ|ト
        銀 ギン
        行 コウ|ギョウ|イ
        証 ショウ
        券 ケン
        三 サン|ミ
        菱 ビシ|ヒシ
        井 イ|セイ
        友 トモ|ユウ
        緊 キン
        急 キュウ|イソ
        薬 ヤク|クスリ
        局 キョク
        関 カン|セキ
        西 サイ|ニシ
        大 ダイ|タイ|オオ
        学 ガク
        快 カイ
        活 カツ
        特 トク
        務 ム
        機 キ
        資 シ
        産 サン
        形 ケイ|カタ
        成 セイ|ナ
        検 ケン
        索 サク
        買 カイ|カ
        物 モノ|ブツ
        予 ヨ
        約 ヤク
        会 カイ
        員 イン
        保 ホ
        険 ケン
        交 コウ
        通 ツウ|トオ
        乗 ジョウ|ノ
        換 カン|カ
        案 アン
        内 ナイ|ウチ
        池 チ|イケ
        画 ガ|カク
        面 メン
        知 チ|シ
        間 カン|マ
        利 リ
        用 ヨウ
        記 キ
        録 ロク
        健 ケン
        康 コウ
        歩 ホ|アル
        数 スウ|カズ
        睡 スイ
        眠 ミン|ネム
        体 タイ|カラダ
        重 ジュウ|オモ
        食 ショク|タ
        事 ジ|コト
        家 カ|イエ
        収 シュウ
        支 シ
        明 メイ|アカ
        細 サイ|コマ
        残 ザン|ノコ
        高 コウ|タカ
        送 ソウ|オク
        金 キン|カネ
        払 ハラ
        口 コウ|クチ
        座 ザ
        番 バン
        号 ゴウ
        住 ジュウ|ス
        所 ショ|トコロ
        下 カ|ゲ|シタ
        鉄 テツ
        車 シャ|クルマ
        新 シン|アタラ
        幹 カン
        線 セン
        空 クウ|ソラ
        港 コウ|ミナト
        航 コウ
        報 ホウ
        災 サイ
        害 ガイ
        震 シン
        警 ケイ
        防 ボウ
        声 セイ|コエ
        動 ドウ|ウゴ
        書 ショ|カ
        籍 セキ
        漫 マン
        雑 ザツ
        誌 シ
        聞 ブン|キ
        放 ホウ
        組 グミ|ソ
        映 エイ|ウツ
        辞 ジ
        単 タン
        語 ゴ
        英 エイ
        日 ニチ|ヒ|ジツ
        本 ホン|モト
        習 シュウ|ナラ
        勉 ベン
        強 キョウ|ツヨ
        授 ジュ
        業 ギョウ
        試 シ|タメ
        験 ケン
        績 セキ
        出 シュツ|デ
        席 セキ
        講 コウ
        義 ギ
        館 カン
        教 キョウ|オシ
        育 イク
        情 ジョウ
        管 カン
        理 リ
        開 カイ|ヒラ
        発 ハツ
        端 タン|ハシ
        末 マツ|スエ
        接 セツ
        続 ゾク|ツヅ
        信 シン
        源 ゲン
        充 ジュウ
        位 イ
        置 チ|オ
        現 ゲン
        在 ザイ
        履 リ
        歴 レキ
        削 サク
        除 ジョ
        編 ヘン
        集 シュウ|アツ
        追 ツイ|オ
        加 カ
        共 キョウ|トモ
        有 ユウ|ア
        存 ゾン
        認 ニン|ミト
        安 アン|ヤス
        全 ゼン
        護 ゴ
        個 コ
        人 ジン|ニン|ヒト
        生 セイ|ショウ|ナマ
        仕 シ
        議 ギ
        携 ケイ
        求 キュウ|モト
        転 テン
        職 ショク
        給 キュウ
        与 ヨ|アタ
        免 メン
        許 キョ
        運 ウン|ハコ
        宿 シュク|ヤド
        合 ゴウ|ガッ|ア
        病 ビョウ
        院 イン
        診 シン|ミ
        療 リョウ
        郵 ユウ
        便 ビン|ベン
        配 ハイ
        宅 タク
        注 チュウ
        文 ブン|モン
        購 コウ
        入 ニュウ|イ
        決 ケツ
        済 サイ|ズ
        請 セイ
        領 リョウ
        割 ワリ|カツ
        引 ヒキ|イン
        典 テン
        還 カン
        元 ゲン|モト
        確 カク
        登 トウ|ノボ
        変 ヘン|カ
        更 コウ|サラ
        再 サイ
        自 ジ|シ
        分 ブン|フン|ワ
        探 サガ|タン
        表 ヒョウ|オモテ
        株 カブ
        価 カ
        投 トウ|ナ
        託 タク
        積 セキ|ツ
        立 リツ|タ
        貯 チョ
        預 ヨ|アズ
        振 シン|フ
        込 コミ|コ
        今 コン|イマ
        毎 マイ
        週 シュウ
        月 ゲツ|ガツ|ツキ
        年 ネン|トシ
        """.trimIndent()

    private val CHARS: Map<Char, List<String>> = chars(CHARS_SRC)

}
