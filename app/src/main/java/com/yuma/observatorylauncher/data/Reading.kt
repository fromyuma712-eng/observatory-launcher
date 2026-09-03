package com.yuma.observatorylauncher.data

/**
 * 検索の照合に使う「読み」の作成。
 *
 * 「ゆ」と打って `YouTube` に届かないのは、**打った側が仮名で、書かれている側が羅字**
 * だからで、表記を揃えないかぎり永久に当たらない。ここでは仮名を羅字へ倒し、
 * 両方の表記を候補として持たせる。
 *
 * 綴りは一通りに決めない。日本語話者が英語名を仮名で打つとき、
 * 綴りはヘボン式にも訓令式にも転ぶ（`ちゅ` は `chu` とも `tyu` とも `tu` とも書かれる）。
 * `YouTube` の綴りは `Tu` なので、**訓令式を併せて持つかどうかで当否が変わる**。
 * そこで一度の走査で二通りを同時に組み立て、どちらかが当たればよいことにする。
 */
object Reading {

    /** 平仮名を片仮名へ寄せ、全角英数を半角へ落とす。表記の差で落とさないための下地。 */
    fun normalize(text: String): String = shape(text).lowercase()

    /**
     * 上と同じ整形だが**大小はそのまま残す**。
     * `YouTube` の `T` のような大小の変わり目を「切れ目」として加点するため、
     * 照合に使う表記では潰さない(比べるときだけ小文字へ倒す)。
     */
    private fun shape(text: String): String {
        val trimmed = text.trim()
        val sb = StringBuilder(trimmed.length)
        var i = 0
        while (i < trimmed.length) {
            val ch = trimmed[i]
            val half = HALF_KANA[ch]
            if (half != null) {
                // 半角片仮名。濁点・半濁点は次の一字として来るので、ここで合わせる。
                val mark = trimmed.getOrNull(i + 1)
                val voiced = when {
                    mark == 'ﾞ' -> voice(half)
                    // 半濁点が付くのはハ行だけである。範囲を絞らないと
                    // `ｶﾟ` のような並びが `キ` に化ける。
                    mark == 'ﾟ' && half in "ハヒフヘホ" -> half + 2
                    else -> null
                }
                if (voiced != null) {
                    sb.append(voiced)
                    i += 2
                } else {
                    sb.append(half)
                    i++
                }
                continue
            }
            sb.append(
                when {
                    ch in 'ぁ'..'ゖ' -> ch + 0x60
                    ch in '！'..'～' -> ch - 0xFEE0
                    ch == '　' -> ' '
                    else -> ch
                }
            )
            i++
        }
        return sb.toString()
    }

    /** 濁点を付けた片仮名。ウ だけは並びの上で隣り合っていないので個別に置く。 */
    private fun voice(kana: Char): Char? = when {
        kana == 'ウ' -> 'ヴ'
        kana in "カキクケコサシスセソタチツテトハヒフヘホ" -> kana + 1
        else -> null
    }

    /** 半角片仮名 → 全角。`おｻｲﾌｹｰﾀｲ` のような表示名が実際に端末に入っている。 */
    private val HALF_KANA: Map<Char, Char> = buildMap {
        val half = "｡｢｣､･ｦｧｨｩｪｫｬｭｮｯｰｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜﾝ"
        val full = "。「」、・ヲァィゥェォャュョッーアイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワン"
        half.forEachIndexed { at, ch -> put(ch, full[at]) }
    }

    /**
     * 照合に使う一つの表記と、その**確からしさ**。
     *
     * 表記は等価ではない。書かれているとおりの名前が最も確かで、仮名を羅字へ倒したもの、
     * 漢字を辞書で開いたもの、欧字を字の名で読んだもの——と、離れるほど当てずっぽうに近づく。
     * 重みを持たせないと、遠い表記で気持ちよく当たった別の名前が、
     * 近い表記で不格好に当たった本命を追い越す（`ゆ` で `関西UFJ銀行` が
     * `YouTube` の上に来た。「ユーエフ」の `yu` が続けて当たるためである）。
     */
    internal data class Form(val text: String, val weight: Double)

    /** 表示名は変わらないので、一度作った読みは持ち続けてよい(打鍵ごとに作り直さない)。 */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, List<Form>>()

    internal fun formsCached(text: String): List<Form> = cache.getOrPut(text) { formsOf(text) }

    /**
     * 照合に使う表記の一式。仮名を含まなければ正規化した一件だけを返す。
     * 返す順は問わない——呼び出し側は最も良く当たったものを採る。
     */
    fun forms(text: String): List<String> = formsOf(text).map { it.text }

    internal fun formsOf(text: String): List<Form> {
        val base = shape(text)
        val out = LinkedHashMap<String, Double>()
        fun offer(value: String, weight: Double) {
            if (value.isEmpty()) return
            val held = out[value]
            if (held == null || weight > held) out[value] = weight
        }
        offer(base, AS_WRITTEN)
        if (base.any { it.isKana() }) romaji(base).forEach { offer(it, TRANSLITERATED) }
        for (seed in Yomi.variants(base)) {
            offer(seed, OPENED)
            if (seed.any { it.isKana() }) romaji(seed).forEach { offer(it, OPENED_ROMAJI) }
        }
        return out.entries.take(MAX_FORMS).map { Form(it.key, it.value) }
    }

    /**
     * 仮名の並びを羅字へ倒す。ヘボン式・訓令式に加え、**拗音の y を落とした綴り**も返す。
     *
     * 三つ目は日本語の翻字としては正しくないが、英語名の綴りに当てるために要る。
     * `チュ` はヘボン式 `chu`・訓令式 `tyu` だが、`YouTube` の綴りは `Tu` であり、
     * どちらでも届かない。英語側は拗音を素直な母音で綴ることが多い、という
     * 事実に合わせた三本目である。
     */
    private fun romaji(katakana: String): List<String> {
        val hepburn = StringBuilder()
        val kunrei = StringBuilder()
        val plain = StringBuilder()
        // ウ に母音が続く並びを w 系として読んだ綴り。
        // `ウインドウズ` は小さい「ィ」で打たれるとは限らず、
        // 普通の「い」で打たれる方が多い(ウイルス・ウイスキーと同じ綴り方)。
        // どちらも英語の `wi` を表すので、w 系の綴りを別に一本持つ。
        val wform = StringBuilder()
        var i = 0
        while (i < katakana.length) {
            val ch = katakana[i]
            when {
                // 長音符は落とす。伸ばす音は綴りに現れないことが多い(YouTube の "You")。
                ch == 'ー' -> i++

                // 促音は次の音の頭子音を重ねる。
                ch == 'ッ' -> {
                    val next = i + 1
                    if (next < katakana.length) {
                        val pair = syllable(katakana, next)
                        val head = pair?.first?.firstOrNull()
                        if (head != null && head !in "aiueo") {
                            hepburn.append(head)
                            kunrei.append(pair.second.first())
                            plain.append(pair.second.first())
                            wform.append(head)
                        }
                    }
                    i++
                }

                ch.isKana() -> {
                    val pair = syllable(katakana, i)
                    if (pair == null) {
                        hepburn.append(ch)
                        kunrei.append(ch)
                        plain.append(ch)
                        wform.append(ch)
                        i++
                    } else {
                        hepburn.append(pair.first)
                        kunrei.append(pair.second)
                        plain.append(pair.second.replace("y", ""))
                        if (ch == 'ウ' && katakana.getOrNull(i + 1) in FULL_VOWELS) {
                            wform.append('w')
                        } else {
                            wform.append(pair.first)
                        }
                        i += pair.third
                    }
                }

                else -> {
                    hepburn.append(ch)
                    kunrei.append(ch)
                    plain.append(ch)
                    wform.append(ch)
                    i++
                }
            }
        }
        return listOf(
            hepburn.toString(), kunrei.toString(), plain.toString(), wform.toString(),
        )
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * [at] から始まる一音を読む。戻り値は (ヘボン式, 訓令式, 消費した文字数)。
     * 拗音(キャ等)と外来音(ファ・ティ等)はここで二文字として食う。
     */
    private fun syllable(text: String, at: Int): Triple<String, String, Int>? {
        val ch = text[at]
        val base = TABLE[ch] ?: return null
        val next = text.getOrNull(at + 1)

        // 拗音: 小さいヤ行が続く。イ段の母音を落として y+母音を付ける。
        if (next != null && next in SMALL_Y) {
            val vowel = when (next) {
                'ャ' -> "a"
                'ュ' -> "u"
                else -> "o"
            }
            val h = base.first
            val k = base.second
            if (h.endsWith("i")) {
                // shi/chi/ji は子音側が既に口蓋化しているので y を挟まない。
                val hep = if (h == "shi" || h == "chi" || h == "ji") {
                    h.dropLast(1) + vowel
                } else {
                    h.dropLast(1) + "y" + vowel
                }
                val kun = k.dropLast(1) + "y" + vowel
                return Triple(hep, kun, 2)
            }
        }

        // 外来音: 小さい母音が続く。直前の母音を差し替える(フ+ァ = fa)。
        if (next != null && next in SMALL_V) {
            val vowel = SMALL_V.getValue(next)
            // ウ に小さい母音が続くのは w 系(ウィ=wi・ウォ=wo)。
            // ウ の綴りは母音一字なので、尾を落とすと子音まで消えてしまい、
            // `ウィンドウズ` の頭が `i` になって `Windows` に届かなくなる。
            if (ch == 'ウ') return Triple("w" + vowel, "w" + vowel, 2)
            val hep = base.first.dropLast(1) + vowel
            val kun = base.second.dropLast(1) + vowel
            return Triple(hep, kun, 2)
        }

        return Triple(base.first, base.second, 1)
    }

    private fun Char.isKana(): Boolean = this in 'ァ'..'ヶ' || this == 'ー'

    private val SMALL_Y = setOf('ャ', 'ュ', 'ョ')

    private val FULL_VOWELS = setOf('ア', 'イ', 'エ', 'オ')

    private val SMALL_V = mapOf('ァ' to "a", 'ィ' to "i", 'ゥ' to "u", 'ェ' to "e", 'ォ' to "o")

    /** 片仮名 → (ヘボン式, 訓令式)。差の無い音は同じ綴りを二度持つ。 */
    private val TABLE: Map<Char, Pair<String, String>> = buildMap {
        fun put(kana: String, hepburn: String, kunrei: String = hepburn) {
            this[kana[0]] = hepburn to kunrei
        }
        put("ア", "a"); put("イ", "i"); put("ウ", "u"); put("エ", "e"); put("オ", "o")
        put("カ", "ka"); put("キ", "ki"); put("ク", "ku"); put("ケ", "ke"); put("コ", "ko")
        put("ガ", "ga"); put("ギ", "gi"); put("グ", "gu"); put("ゲ", "ge"); put("ゴ", "go")
        put("サ", "sa"); put("シ", "shi", "si"); put("ス", "su"); put("セ", "se"); put("ソ", "so")
        put("ザ", "za"); put("ジ", "ji", "zi"); put("ズ", "zu"); put("ゼ", "ze"); put("ゾ", "zo")
        put("タ", "ta"); put("チ", "chi", "ti"); put("ツ", "tsu", "tu")
        put("テ", "te"); put("ト", "to")
        put("ダ", "da"); put("ヂ", "ji", "di"); put("ヅ", "zu", "du")
        put("デ", "de"); put("ド", "do")
        put("ナ", "na"); put("ニ", "ni"); put("ヌ", "nu"); put("ネ", "ne"); put("ノ", "no")
        put("ハ", "ha"); put("ヒ", "hi"); put("フ", "fu", "hu"); put("ヘ", "he"); put("ホ", "ho")
        put("バ", "ba"); put("ビ", "bi"); put("ブ", "bu"); put("ベ", "be"); put("ボ", "bo")
        put("パ", "pa"); put("ピ", "pi"); put("プ", "pu"); put("ペ", "pe"); put("ポ", "po")
        put("マ", "ma"); put("ミ", "mi"); put("ム", "mu"); put("メ", "me"); put("モ", "mo")
        put("ヤ", "ya"); put("ユ", "yu"); put("ヨ", "yo")
        put("ラ", "ra"); put("リ", "ri"); put("ル", "ru"); put("レ", "re"); put("ロ", "ro")
        put("ワ", "wa"); put("ヲ", "o", "wo"); put("ン", "n")
        put("ヴ", "vu", "bu")
    }

    /**
     * 一件の表示名を、打たれた語で採点する。当たらなければ null。
     * 画面側はここだけを呼ぶ——照合の規則を面の実装に散らさない。
     *
     * 採点は **fzy / fzf が採る Smith–Waterman 系の動的計画法**に倣う。要点は二つ。
     *
     * 1. **打たれた文字がすべて、順序どおり現れることを必須とする。**
     *    これを外すと打ち足しても絞られない（「すたば」で「スズ薬局」が残る、という
     *    実際に起きた不具合がこれである。頭の字だけで残す段を設けていた）。
     * 2. 当たり方の質を点にする。語頭・区切りの直後・大小の変わり目で当たれば加点し、
     *    続けて当たればさらに加点する。飛ばした分は減点する。
     *
     * 段を手で並べるのをやめ、規則をこの一つに統一した。
     */
    fun match(label: String, query: String, tolerance: Int = 0): Int? =
        matcher(query, tolerance).score(label)

    /**
     * 打たれた語を**一度だけ**組み立てて持つ。以後は名前を渡すだけで採点できる。
     *
     * 打鍵ごとに何十件も引くので、打たれた語の読み(仮名・羅字・子音の骨格)を
     * 名前の件数だけ作り直してはならない。fzf も fzy も、打たれた語を先に
     * 組み上げてから候補を流す。実測で**打っている間だけ 29% が落ちていた**のは
     * これが原因で、組み立てを外へ出すと 3% まで下がった。
     */
    fun matcher(query: String, tolerance: Int = 0): Matcher =
        Matcher(formsOf(query), tolerance)

    class Matcher internal constructor(
        private val queryForms: List<Form>,
        private val tolerance: Int,
    ) {
        private val skeletons = queryForms.map { consonants(it.text) }

        val isEmpty: Boolean get() = queryForms.isEmpty()

        fun score(label: String): Int? {
            if (queryForms.isEmpty()) return null
            var best: Int? = null
            fun offer(value: Int) {
                if (best == null || value > best!!) best = value
            }
            for (candidate in formsCached(label)) {
                // 名前の側の骨格も、打たれた語の数だけ作り直さない。
                val skeleton = consonants(candidate.text)
                queryForms.forEachIndexed { qi, q ->
                    val raw = Reading.score(candidate.text, q.text)
                        ?: forgiving(candidate.text, q.text, tolerance)
                    if (raw != null) offer((raw * candidate.weight * q.weight).toInt())

                    // 母音を当てにできない綴りのために、子音の骨格でも一度当てる。
                    val bones = skeletons[qi]
                    if (bones.length >= MIN_CONSONANTS &&
                        skeleton.firstOrNull() == bones.firstOrNull()
                    ) {
                        val raw = Reading.score(skeleton, bones)
                            // 促音は綴りに現れない(`ルック` の `kk` と `look` の `k`)。
                            // 骨格にも同じ許しを効かせて、この差を吸収する。
                            ?: forgiving(skeleton, bones, tolerance)
                        // **骨格が同じ長さでも「完全一致」ではない。**
                        // 母音を捨てた影が重なっただけである。最上位を与えると、
                        // 四字の骨格(kkts) が `X`(エックス=kks) に完全一致して
                        // `会員CLUB` を追い越す(実際に起きた)。上限を掛けて並みへ戻す。
                        // 骨格が短いほど当てずっぽうに近い(`mt` の二字は
                        // `Meet` にも他の名にも当たる)。長さに比例させる。
                        val hit = if (raw == EXACT) SKELETON_UNIT * bones.length else raw
                        hit?.let {
                            offer((it * CONSONANT_WEIGHT * candidate.weight * q.weight).toInt())
                        }
                    }
                }
            }
            return best
        }
    }

    /**
     * 子音の骨格。母音を落とし、**日本語話者にとって同じ音**を一つへ寄せる。
     *
     * 片仮名は英語の綴りに無い母音を足し（`スラック` の「ラ」）、母音の質もずれる
     * （`グーグル` は `guuguru` だが綴りは `google`）。母音を当てにする限り届かない。
     * ラ行は英語で `r` にも `l` にもなり、バ行は `b` にも `v` にもなるので、
     * それぞれ一つへ寄せる——**日本語にその区別が無いことに合わせる**。
     *
     * 頭の子音の一致を要り、二つ以上の子音を要る。外すと当たり過ぎて一覧が絞れない。
     */
    private fun consonants(text: String): String {
        val sb = StringBuilder(text.length)
        var previous = ' '
        for (raw in text) {
            val ch = raw.lowercaseChar()
            // y が子音なのは語頭だけである。語中の y は口蓋化の印(`myu`・`kya`)か
            // 母音の尾(`Pay`)であって、子音ではない。残すと `PayPay` の骨格が
            // `pypy` になり「ぺいぺい」の `pp` と合わず、`アップルミュージック` の
            // `myu` が `Music` の `mu` と合わない。
            val glide = ch == 'y' && previous.isLetter()
            previous = ch
            if (ch in "aiueo" || glide || !ch.isLetter()) continue
            sb.append(
                when (ch) {
                    // 日本語に区別の無い音を一つへ寄せる。綴りの側の揺れであって、
                    // 打つ側はどちらとも書き分けられない。
                    'l' -> 'r' // ラ行は r とも l とも
                    'v' -> 'b' // バ行は b とも v とも
                    'c' -> 'k' // カ行は k とも c とも(`スラック` の `ck`)
                    'j' -> 'g' // ジ は g とも j とも(`Gmail`＝ジーメール)
                    'z' -> 's' // ズ は s とも z とも(`Teams`＝チームズ)
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /**
     * 打ち間違いを許した採点。**厳密に当たらなかったときだけ**呼ぶ。
     *
     * fzf・fzy・KISS のいずれも打ち間違いを許さない。打った文字は一つ残らず
     * 順に現れなければならず、一文字でも違えば結果は空になる。一方で
     * Algolia・Typesense・Meilisearch のような検索側は、Damerau–Levenshtein の
     * 距離を 1〜2 まで許すのが定石で、短い語ほど厳しくする。
     *
     * ここは後者に倣う。ただし**許すのは「打ち過ぎた文字を落とす」ことだけ**とし、
     * 落とせる数を語の長さで決める（三文字以下は零、七文字以下は一つ、以降二つ）。
     * 短い語を許すと何にでも当たってしまい、絞るという目的を失う。
     * 落とした分は点を割り引き、**厳密に当たったものより必ず下に来る**ようにする。
     */
    private fun forgiving(candidate: String, query: String, tolerance: Int): Int? {
        val budget = minOf(tolerance, allowance(query.length))
        if (budget <= 0) return null
        val kept = keeping(candidate, query, budget) ?: return null
        val dropped = query.length - kept.length
        if (dropped <= 0) return null
        // 残った文字が少なすぎるものは、当たったとは言わない。
        if (kept.length < MIN_KEPT || kept.length * 10 < query.length * KEPT_RATIO) return null
        val base = score(candidate, kept) ?: return null
        var value = base.toDouble()
        repeat(dropped) { value *= TYPO_PENALTY }
        return value.toInt()
    }

    /** 語の長さに応じて落としてよい文字数。短いほど厳しくする。 */
    fun allowance(length: Int): Int = when {
        length <= 3 -> 0
        length <= 7 -> 1
        else -> 2
    }

    /**
     * [query] から高々 [budget] 文字を落として、[candidate] に順序どおり収まる
     * 最長の並びを返す。収まらなければ null。
     *
     * 落とした数ごとに「候補のどこまで進んだか」の最小値だけを持って進む
     * （落とす数は高々二なので、表は三行しか要らない）。
     * どの文字を残したかは、桁の印を持ち回って復元する。
     */
    private fun keeping(candidate: String, query: String, budget: Int): String? {
        if (query.length > MAX_QUERY) return null
        val far = Int.MAX_VALUE
        var reach = IntArray(budget + 1) { if (it == 0) 0 else far }
        var marks = LongArray(budget + 1)
        for (i in query.indices) {
            val nextReach = IntArray(budget + 1) { far }
            val nextMarks = LongArray(budget + 1)
            for (d in 0..budget) {
                // この文字を残す：候補の残りから同じ文字を探して先へ進む。
                if (reach[d] != far) {
                    val at = indexFrom(candidate, reach[d], query[i])
                    if (at >= 0) {
                        nextReach[d] = at + 1
                        nextMarks[d] = marks[d] or (1L shl i)
                    }
                }
                // この文字を落とす：進まないまま、落とした数だけ増える。
                if (d > 0 && reach[d - 1] != far && reach[d - 1] < nextReach[d]) {
                    nextReach[d] = reach[d - 1]
                    nextMarks[d] = marks[d - 1]
                }
            }
            reach = nextReach
            marks = nextMarks
        }
        val d = (0..budget).firstOrNull { reach[it] != far } ?: return null
        val mark = marks[d]
        val sb = StringBuilder(query.length)
        for (i in query.indices) if ((mark shr i) and 1L == 1L) sb.append(query[i])
        return sb.toString()
    }

    private fun indexFrom(text: String, from: Int, ch: Char): Int {
        for (i in from until text.length) if (fold(text[i]) == fold(ch)) return i
        return -1
    }

    /**
     * fzy の採点（Gotoh のアフィン空隙）。返す値は千倍した整数で、当たらなければ null。
     * 完全一致だけは別扱いにして最上位へ置く。
     */
    private fun score(candidate: String, query: String): Int? {
        if (query.isEmpty()) return null
        if (!isSubsequence(candidate, query)) return null
        if (candidate.length == query.length) return EXACT
        // 長すぎる相手には表を組まない。順序どおり現れることは既に確かめてある。
        if (candidate.length > MAX_CANDIDATE || query.length > MAX_QUERY) return 0

        val m = candidate.length
        val n = query.length
        val bonus = DoubleArray(m) { j -> bonusAt(candidate, j) }

        // D: その桁で「当たって終わる」最善、M: その桁までの最善。
        var previousD = DoubleArray(m)
        var previousM = DoubleArray(m)
        var currentD = DoubleArray(m)
        var currentM = DoubleArray(m)

        for (i in 0 until n) {
            var running = NEG
            val gap = if (i == n - 1) GAP_TRAILING else GAP_INNER
            for (j in 0 until m) {
                if (fold(query[i]) == fold(candidate[j])) {
                    var value = NEG
                    if (i == 0) {
                        // fzf の BonusFirstCharMultiplier。**打ち始めの一文字が切れ目で
                        // 当たったときだけ加点を倍にする**。これが無いと、名前の途中で
                        // 続けて当たった語(「ゆ」に対する `ニュース` の nyu**u**su)が、
                        // 語頭から当たった語(`YouTube`)より上に来る。
                        value = j * GAP_LEADING + bonus[j] * FIRST_CHAR_MULTIPLIER
                    } else if (j > 0) {
                        value = maxOf(
                            previousM[j - 1] + bonus[j],
                            previousD[j - 1] + MATCH_CONSECUTIVE,
                        )
                    }
                    currentD[j] = value
                    running = maxOf(value, running + gap)
                    currentM[j] = running
                } else {
                    currentD[j] = NEG
                    running += gap
                    currentM[j] = running
                }
            }
            val swapD = previousD; previousD = currentD; currentD = swapD
            val swapM = previousM; previousM = currentM; currentM = swapM
        }
        return (previousM[m - 1] * 1000).toInt()
    }

    /** 打たれた文字が順序どおり現れるか。ここが偽なら候補ではない。 */
    private fun isSubsequence(candidate: String, query: String): Boolean {
        var i = 0
        for (ch in candidate) {
            if (i < query.length && fold(ch) == fold(query[i])) i++
            if (i == query.length) return true
        }
        return i == query.length
    }

    /**
     * その桁で当たったときの加点。語頭・区切りの直後・大小の変わり目・
     * 和文と欧文の変わり目を「切れ目」とみなす。切れ目で当たる方が良い当たり方である。
     */
    private fun bonusAt(candidate: String, j: Int): Double {
        if (j == 0) return MATCH_SEPARATOR
        val previous = candidate[j - 1]
        val current = candidate[j]
        return when {
            previous in SEPARATORS -> MATCH_SEPARATOR
            previous.isLowerCase() && current.isUpperCase() -> MATCH_CAPITAL
            previous.isDigit() != current.isDigit() -> MATCH_WORD
            isLatin(previous) != isLatin(current) -> MATCH_SCRIPT
            else -> 0.0
        }
    }

    private fun isLatin(c: Char): Boolean = c.code < 0x3000

    private fun fold(c: Char): Char = c.lowercaseChar()

    private const val FIRST_CHAR_MULTIPLIER = 2.0

    /** 書かれているとおり。 */
    private const val AS_WRITTEN = 1.0

    /** 仮名を羅字へ倒しただけ。綴りの取り違えはあるが、音は名前そのもの。 */
    private const val TRANSLITERATED = 0.9

    /** 漢字を辞書で開いた、あるいは欧字を字の名で読んだ。読み方は一通りではない。 */
    private const val OPENED = 0.7

    /** 開いたうえに羅字へ倒した。名前から最も遠い。 */
    private const val OPENED_ROMAJI = 0.65

    private const val CONSONANT_WEIGHT = 0.55
    private const val MIN_CONSONANTS = 2
    private const val TYPO_PENALTY = 0.45
    private const val MIN_KEPT = 3
    private const val KEPT_RATIO = 7
    private const val MAX_FORMS = 10
    private const val EXACT = Int.MAX_VALUE / 2
    private const val SKELETON_UNIT = 2_400
    private const val MAX_CANDIDATE = 128
    private const val MAX_QUERY = 32
    private const val NEG = -1.0e9
    private const val GAP_LEADING = -0.005
    private const val GAP_TRAILING = -0.005
    private const val GAP_INNER = -0.01
    private const val MATCH_CONSECUTIVE = 1.0
    private const val MATCH_SEPARATOR = 0.9
    private const val MATCH_WORD = 0.8
    private const val MATCH_CAPITAL = 0.7
    private const val MATCH_SCRIPT = 0.7
    private val SEPARATORS = " -_/.:・、。（）()[]".toSet()
}
