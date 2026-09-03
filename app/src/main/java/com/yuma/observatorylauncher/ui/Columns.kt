package com.yuma.observatorylauncher.ui

/**
 * 面の横の割り方。**四分割の桁**を唯一の基準として持つ。
 *
 * ウィジェットと情報カードは別の物だが、置かれ方は同じである
 * ——幅を桁の数で宣言し、同じ行に収まる限り横へ並ぶ。
 * 同じ算法を二箇所に持っていると、片方だけ直したときに面が食い違う。
 */
internal const val HOME_COLUMNS = 4

/** 桁に収まる分だけ同じ行へ詰め、溢れたら次の行へ送る。 */
internal fun <T> packIntoRows(items: List<T>, span: (T) -> Int): List<List<T>> {
    val rows = mutableListOf<MutableList<T>>()
    var used = 0
    items.forEach { item ->
        val width = span(item).coerceIn(1, HOME_COLUMNS)
        if (rows.isEmpty() || used + width > HOME_COLUMNS) {
            rows += mutableListOf(item)
            used = width
        } else {
            rows.last() += item
            used += width
        }
    }
    return rows
}
