package com.yuma.observatorylauncher.data

import android.content.pm.ApplicationInfo

/**
 * 群の既定値を決める規則表。
 *
 * OSが持つ `ApplicationInfo.category` は粗く、**金融・ポイントカード・交通といった
 * 日常の区分をまったく持たない**。そこで、パッケージ名とラベルに対する規則を先に当て、
 * どれにも当たらなかったものだけをOSの系統へ落とす。
 *
 * ここは「既定値」でしかない。宣言(layout.json の groups)に書かれた割り当てが常に優先され、
 * 行の長押し「群を移す」で上書きが積まれる。規則が外した分は手で直せる、という前提で作る。
 *
 * 規則は上から順に当てる。先に書いたものが強い。
 */
object Categories {

    /** 規則一件。[packages] はパッケージ名の部分一致、[labels] は表示名の部分一致。 */
    private data class Rule(
        val group: String,
        val packages: List<String> = emptyList(),
        val labels: List<String> = emptyList(),
    )

    private val RULES = listOf(
        // --- 金融 ------------------------------------------------------------
        // 銀行・証券・カード・決済。生活の中で最も取り違えたくない区分なので最初に当てる。
        Rule(
            group = "金融",
            packages = listOf(
                "bank", "banking", "mufg", "smbc", "mizuho", "resona", "jpbank", "yuchou",
                "rakuten_bank", "rakuten.bank", "aeonbank", "paypay", "payb", "kyash",
                "securities", "nomura", "daiwa", "sbisec", "monex", "matsui",
                "moneyforward", "zaim", "mfclient", "vpass", "smbc.card", "jcb", "aeon.card",
                "epos", "orico", "saison", "rakutencard", "creditcard", "wallet",
                "insurance", "hoken", "nenkin", "e-tax", "nta.go", "furusato",
            ),
            labels = listOf(
                "銀行", "証券", "カード", "ペイ", "Pay", "pay", "ウォレット", "家計",
                "保険", "投資", "信託", "マネー", "Money", "口座", "決済",
            ),
        ),
        // --- 会員証・ポイント -------------------------------------------------
        // 店舗アプリ・ポイント・クーポン。金融に紛れると財布の中身が見えなくなるので分ける。
        Rule(
            group = "会員証",
            packages = listOf(
                "ponta", "dpoint", "tpoint", "vpoint", "rakuten.pointclub", "pointclub",
                "majica", "tsuruha", "matsukiyo", "welcia", "sugi", "cocokara",
                "sej.", "7app", "familymart", "lawson", "ministop", "aeon.app", "ito-yokado",
                "mcdonalds", "starbucks", "sbux", "kfc", "sukiya", "yoshinoya",
                "gyoza", "cocoichi", "31club", "baskin", "mosburger", "doutor", "tullys", "komeda",
                "uniqlo", "gu.", "muji", "shimamura", "nitori", "biccamera", "yodobashi",
                "yamada", "kojima", "edion", "tsutaya", "bookoff",
                "coupon", "membership", "meccha", "point",
            ),
            labels = listOf(
                "ポイント", "カード会員", "会員", "クーポン", "マイル", "スタンプ",
                "マクドナルド", "スターバックス", "ローソン", "セブン", "ファミマ",
                "イオン", "ドラッグ", "薬局",
            ),
        ),
        // --- 交通 -------------------------------------------------------------
        Rule(
            group = "交通",
            packages = listOf(
                "transit", "jorudan", "navitime", "ekitan", "yahoo.android.apps.transit",
                "jreast", "jrwest", "jrcentral", "suica", "icoca", "pasmo", "mobilesuica",
                "jp.co.ana", "com.ana.", "jp.co.jal", "com.jal.", "peach", "jetstar", "skyscanner",
                "taxi", "taxibell", "uber", "timescar", "parking", "maps",
            ),
            labels = listOf("乗換", "時刻表", "路線", "地図", "マップ", "タクシー", "駐車"),
        ),
        // --- 買物 -------------------------------------------------------------
        Rule(
            group = "買物",
            packages = listOf(
                "amazon", "rakuten.android", "ichiba", "mercari", "yahoo.android.yshopping",
                "paypaymall", "zozo", "shein", "temu", "qoo10", "aliexpress",
                "yafuoku", "auctions", "jmty", "rakuma",
            ),
            labels = listOf("ショッピング", "フリマ", "オークション", "通販"),
        ),
        // --- 学び -------------------------------------------------------------
        Rule(
            group = "学び",
            packages = listOf(
                "duolingo", "anki", "quizlet", "kahoot", "obsidian", "notion",
                "classroom", "moodle", "manaba", "kansai-u", "toeic", "eiken",
                "dictionary", "weblio", "jisho", "scholar", "arxiv",
            ),
            labels = listOf("辞書", "単語", "学習", "英会話", "講義", "大学", "図書"),
        ),
        // --- 健康 -------------------------------------------------------------
        Rule(
            group = "健康",
            packages = listOf(
                "apps.fitness", "fitbit", "health", "asken", "myfitnesspal", "sleep", "strava",
                "karada", "omron", "tanita", "hospital", "clinic", "epark",
            ),
            labels = listOf("健康", "睡眠", "体重", "運動", "病院", "診察", "薬"),
        ),
        // --- 交信 -------------------------------------------------------------
        Rule(
            group = "交信",
            packages = listOf(
                "dialer", "messaging", "contacts", "line.android", "discord", "slack",
                "whatsapp", "telegram", "messenger", "android.gm", "mail", "zoom", "meet",
                "skype", "teams",
            ),
            labels = listOf("電話", "メッセージ", "連絡先", "メール", "通話"),
        ),
        // --- 記録 -------------------------------------------------------------
        Rule(
            group = "記録",
            packages = listOf(
                "calendar", "keep", "docs", "sheets", "slides", "drive", "dropbox",
                "onedrive", "evernote", "todoist", "tasks", "recorder", "scan",
            ),
            labels = listOf("カレンダー", "メモ", "ドキュメント", "スプレッド", "ドライブ", "予定"),
        ),
        // --- 道具 -------------------------------------------------------------
        Rule(
            group = "道具",
            packages = listOf(
                "settings", "deskclock", "calculator", "files", "documentsui",
                "translate", "camera", "gallery", "photos", "printservice",
                "packageinstaller", "vending", "gms", "emergency", "safetyhub",
            ),
            labels = listOf("設定", "時計", "電卓", "翻訳", "ファイル", "カメラ", "緊急"),
        ),
    )

    /**
     * 既定の群。宣言に無いときだけ使われる。
     * 規則に当たらなければOSの系統へ、それも無ければ「未分類」。
     */
    fun of(entry: AppEntry): String {
        val pkg = entry.packageName.lowercase()
        val label = entry.label
        RULES.forEach { rule ->
            if (rule.packages.any { pkg.contains(it) }) return rule.group
            if (rule.labels.any { label.contains(it) }) return rule.group
        }
        return systemCategory(entry.category)
    }

    /** 規則が外したときの受け皿。OSがアプリ自身の申告として持っている分類。 */
    private fun systemCategory(category: Int): String = when (category) {
        ApplicationInfo.CATEGORY_GAME -> "遊戯"
        ApplicationInfo.CATEGORY_AUDIO -> "音"
        ApplicationInfo.CATEGORY_VIDEO -> "映像"
        ApplicationInfo.CATEGORY_IMAGE -> "像"
        ApplicationInfo.CATEGORY_SOCIAL -> "交信"
        ApplicationInfo.CATEGORY_NEWS -> "報道"
        ApplicationInfo.CATEGORY_MAPS -> "交通"
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> "作業"
        ApplicationInfo.CATEGORY_ACCESSIBILITY -> "支援"
        else -> "未分類"
    }
}
