package com.yuma.observatorylauncher.ui

/**
 * 英和の配分。**欧文は枠の名、和文は中身。**
 *
 * > 「表記で英語と日本語をいい塩梅で織り交ぜてください。洗練させていきたいです」
 * > （本人・2026/9/25）
 *
 * ## 線の引き方
 *
 * | 欧文（[ObservatoryTag]） | 和文 |
 * | --- | --- |
 * | 面の名（HOME / DASHBOARD / SOUND） | 値・文・単位（3時間24分・降水2%） |
 * | 節の札（SKY・DEVICE …）——和名の前に添える | 図の名（統計用語。2026/9/2 に揃えた） |
 * | 小さな見出し（TODAY・NEXT・QUEUE・TRACKS） | 本人の標語（選ぶ／選ばない／一枚） |
 * | 状態の二値（ON / OFF） | 予定・曲名・地名など、外から来た言葉 |
 *
 * 欧文は**読ませる字ではなく、形で覚える字**として置く。大文字・等幅・字間を開け、
 * 見出しの位置と形だけで「どこに居るか」が分かるようにする。
 * 読んで意味を取る必要があるもの（値・文・統計の名）は和文のまま残す。
 *
 * ⚠️ **欧文を和文の翻訳として並べない**のが原則。例外は節の見出しだけで、
 * あれは案内板と同じく「札（欧）＋名（和）」の対で読ませる。
 *
 * 語はここにしか書かない——画面ごとに英訳すると、同じものが二つの名で呼ばれる。
 */
internal object Lexicon {
    const val HOME = "Home"
    const val DASHBOARD = "Dashboard"
    const val SOUND = "Sound"

    const val TODAY = "Today"
    const val NEXT = "Next"
    const val QUEUE = "Queue"
    const val WEEK = "7 days"
    const val TRACKS = "Tracks"
    const val ARTISTS = "Artists"
    const val VERSUS_PREVIOUS = "vs prev"
    const val ON = "On"
    const val OFF = "Off"
    const val ART = "Art"

    /** 計器盤の節の札。鍵は節の和名（[Dashboard] の表と同じ語）。 */
    private val SECTIONS = mapOf(
        "予定" to "Agenda",
        "空" to "Sky",
        "当日の集計" to "Today",
        "分布" to "Spread",
        "前後の比較" to "Compare",
        "端末" to "Device",
        "音" to "Sound",
        "身体" to "Body",
        "状態" to "Status",
    )

    fun section(japanese: String): String? = SECTIONS[japanese]

    /** 一覧の群の札。鍵は群の和名（`Categories` が付ける名）。利用者が付けた名には札を付けない。 */
    private val GROUPS = mapOf(
        "道具" to "Tools",
        "健康" to "Health",
        "映像" to "Video",
        "写真" to "Photo",
        "遊戯" to "Games",
        "作業" to "Work",
        "交信" to "Talk",
        "金融" to "Money",
        "買物" to "Shop",
        "交通" to "Transit",
        "学び" to "Study",
        "報道" to "News",
        "音" to "Music",
        "会員証" to "Cards",
        "未分類" to "Misc",
        "常用" to "Pinned",
    )

    fun group(japanese: String): String? = GROUPS[japanese]

    const val FREQUENT = "Frequent"
}
