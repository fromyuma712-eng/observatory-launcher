package com.yuma.observatorylauncher.ui

import com.yuma.observatorylauncher.config.LayoutConfig

/**
 * 同じ問いに答える札を、置く直前にひとつへ畳む。
 *
 * ## なぜ「宣言を書き換える」のではなく「置く直前に畳む」のか
 *
 * 天気と天気詳細は、外への問い合わせを共有しているので**中身は元から一つ**である。
 * 分けていたのは、頁に置くものを選べるようにするためだった。
 * ところが両方を置くと、**見出しが二つに割れて、同じ空模様が二段に散る**。
 *
 * > 「ホームの天気と天気詳細を統合して見やすいものにしてください」（本人・2026/9/1）
 *
 * 直し方は二つあった。
 *
 * | やり方 | 難点 |
 * | --- | --- |
 * | 宣言（`layout.json`）を書き換える | 世代に残る。**利用者の持ち物を勝手に書き換える**ことになる |
 * | **置く直前に畳む** | 宣言はそのまま。畳み方を変えれば、いつでも元の姿に戻る |
 *
 * 後者を採った。計器盤が既に同じやり方（`Dashboard.SUPERSEDED`）を採っており、
 * **面ごとに畳み方だけが違う**という形に揃う。
 *
 * ## ⚠️ 編集中は畳まない
 *
 * 畳んだ姿しか見えないと、**どちらの札を消せばいいのか分からなくなる**。
 * 並べ替えも消去も、元の二枚に対して行えなければならない。
 */
internal object InfoMerge {

    /** 天気の対と、その畳み先。 */
    private val WEATHER_PAIR = setOf(
        LayoutConfig.INFO_WEATHER,
        LayoutConfig.INFO_WEATHER_DETAIL,
    )

    /**
     * 一つの帯に置く札を畳む。
     *
     * **両方揃っているときだけ**畳む。片方しか置いていない人の面を、
     * 頼まれてもいないのに作り替えない。
     *
     * 畳んだ札は**先に置かれていた方の場所と id を継ぐ**
     * ——並び順が動くと、置いた場所を覚えていられなくなる。
     */
    fun collapse(
        tiles: List<LayoutConfig.InfoTile>,
        editing: Boolean = false,
    ): List<LayoutConfig.InfoTile> {
        if (editing) return tiles
        if (!tiles.map { it.kind }.containsAll(WEATHER_PAIR)) return tiles
        var merged = false
        return tiles.mapNotNull { tile ->
            when {
                tile.kind !in WEATHER_PAIR -> tile
                merged -> null
                else -> {
                    merged = true
                    tile.copy(kind = LayoutConfig.INFO_WEATHER_FULL)
                }
            }
        }
    }
}
