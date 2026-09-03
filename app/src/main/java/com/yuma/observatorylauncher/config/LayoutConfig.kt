package com.yuma.observatorylauncher.config

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * ランチャーの宣言。UIはこの値からのみ組み立てる(画面側に既定の並びや寸法を持たない)。
 * NixOS の宣言的設定に倣い、状態はこのJSONだけが持ち、操作は必ずこの値の書き換えを経由する。
 *
 * - [sections] ホーム台帳の節と並び
 * - [groups]   検索面の群。宣言された順が表示順で、items は「系統の自動分類」への上書き
 * - [display]  見え方(アイコンの大きさ・名前を出すか)
 * - [sites]    ホームへ置くWebサイトまたはHTMLファイル。節のitemsからidで参照する
 */
data class LayoutConfig(
    val schema: Int = SCHEMA,
    val sections: List<Section>,
    val groups: List<Section> = emptyList(),
    val display: Display = Display(),
    val pageCount: Int = 1,
    val infoTiles: List<InfoTile> = emptyList(),
    val widgets: List<Widget> = emptyList(),
    val sites: List<Site> = emptyList(),
    /**
     * 摩擦を掛けるアプリ。
     *
     * **利用者が指す。** 端末の分類から推すことはしない——
     * 何が自分にとって「無意識に開いてしまうもの」かは本人にしか分からず、
     * 道具が勝手に決めると、外したくなるだけである。
     *
     * 宣言に持たせるので、世代に残り「元に戻す」で戻せる。
     */
    val frictionApps: Set<String> = emptySet(),
) {
    /** URLまたはAndroidのファイル選択で得たcontent URIを、アプリと同じ台帳へ置く。 */
    data class Site(val id: String, val title: String, val location: String)

    /**
     * 置かれたウィジェット一件。
     * [height] は 0 のとき提供元の申告する高さに従う。値はdp。
     * [span] は横幅を四分割の単位で持つ(1〜4)。4なら面いっぱい、2なら半分。
     * 同じ行に収まる限り、隣り合うウィジェットは横に並ぶ。
     */
    data class Widget(
        val id: Int,
        val height: Int = 0,
        val span: Int = 4,
        val page: Int = 0,
        val zone: String = ZONE_BELOW,
    )

    /**
     * OBSERVATORY自身が描く情報ウィジェット。
     * 外部AppWidgetと同じ四分割幅を使い、ページとトラスの上下を宣言として持つ。
     * [title]/[value]/[source]/[targetAt] は種類ごとの設定欄で、不要な種類では空のままにする。
     */
    data class InfoTile(
        val id: String,
        val kind: String,
        val page: Int = 0,
        val zone: String = ZONE_ABOVE,
        val span: Int = 4,
        val title: String = "",
        val value: String = "",
        val source: String = "",
        val targetAt: Long = 0L,
    )

    /**
     * 節または群。[row] が真なら、行を縦に積まず**アイコンを横へ並べる**。
     * 群の中で更に小さな塊を作りたいときは、横並びの節を足して名前を付ける
     * ——入れ子の階層を作らずに「まとまり」を表せる。
     */
    data class Section(
        val title: String,
        val items: List<String>,
        val row: Boolean = false,
        val page: Int = 0,
    )

    /** 見え方。寸法はdpの実数値をそのまま持つ(トークン側の名前に依存させない)。 */
    data class Display(
        val iconSize: Int = 24,
        val showLabels: Boolean = true,
    )


    /** 摩擦を掛ける／外す。宣言なので世代に残り、「元に戻す」で戻せる。 */
    fun frictionToggled(packageName: String): LayoutConfig = copy(
        frictionApps = if (packageName in frictionApps) {
            frictionApps - packageName
        } else {
            frictionApps + packageName
        },
    )

    fun toJson(): String {
        val root = JSONObject()
        root.put("schema", schema)
        root.put(
            "display",
            JSONObject()
                .put("iconSize", display.iconSize)
                .put("showLabels", display.showLabels),
        )
        root.put("pageCount", pageCount.coerceAtLeast(1))
        if (frictionApps.isNotEmpty()) {
            root.put("frictionApps", JSONArray().also { a -> frictionApps.forEach(a::put) })
        }
        root.put(
            "infoTiles",
            JSONArray().also { a ->
                infoTiles.forEach { tile ->
                    a.put(
                        JSONObject()
                            .put("id", tile.id)
                            .put("kind", tile.kind)
                            .put("page", tile.page)
                            .put("zone", tile.zone)
                            .put("span", tile.span)
                            .put("title", tile.title)
                            .put("value", tile.value)
                            .put("source", tile.source)
                            .put("targetAt", tile.targetAt)
                    )
                }
            },
        )
        root.put("sections", sectionsToJson(sections))
        root.put("groups", sectionsToJson(groups))
        root.put(
            "sites",
            JSONArray().also { a ->
                sites.forEach { site ->
                    a.put(
                        JSONObject()
                            .put("id", site.id)
                            .put("title", site.title)
                            .put("location", site.location)
                    )
                }
            },
        )
        root.put(
            "widgets",
            JSONArray().also { a ->
                widgets.forEach { w ->
                    a.put(
                        JSONObject()
                            .put("id", w.id)
                            .put("height", w.height)
                            .put("span", w.span)
                            .put("page", w.page)
                            .put("zone", w.zone)
                    )
                }
            },
        )
        return root.toString(2)
    }

    private fun sectionsToJson(list: List<Section>): JSONArray {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject()
                    .put("title", s.title)
                    .put("row", s.row)
                    .put("page", s.page)
                    .put("items", JSONArray().also { a -> s.items.forEach(a::put) })
            )
        }
        return arr
    }

    // ---- ホーム台帳 ------------------------------------------------------

    /** 指定パッケージを全節から取り除く。 */
    fun without(packageName: String): LayoutConfig =
        copy(sections = sections.map { it.copy(items = it.items - packageName) })

    /** 指定節の末尾へ追加する(既に何処かに在れば先に取り除く＝重複を作らない)。 */
    fun pinned(packageName: String, sectionTitle: String): LayoutConfig {
        val base = without(packageName)
        val exists = base.sections.any { it.title == sectionTitle }
        val sections = if (exists) {
            base.sections.map {
                if (it.title == sectionTitle) it.copy(items = it.items + packageName) else it
            }
        } else {
            base.sections + Section(sectionTitle, listOf(packageName))
        }
        return base.copy(sections = sections)
    }

    fun movedUp(packageName: String): LayoutConfig =
        copy(sections = sections.map { it.moved(packageName, -1) })

    fun movedDown(packageName: String): LayoutConfig =
        copy(sections = sections.map { it.moved(packageName, +1) })

    fun renamedSection(from: String, to: String): LayoutConfig {
        val name = to.trim()
        if (name.isEmpty() || sections.any { it.title == name }) return this
        return copy(sections = sections.map { if (it.title == from) it.copy(title = name) else it })
    }

    fun sectionAdded(after: String, page: Int = 0): LayoutConfig =
        copy(
            sections = sections.withAdded(
                after,
                "節",
                page.coerceIn(0, pageCount.coerceAtLeast(1) - 1),
            )
        )

    /** 空の次ページを一件足す。既存ページの節・並びは一切動かさない。 */
    fun pageAdded(): LayoutConfig {
        val page = pageCount.coerceAtLeast(1)
        var n = page + 1
        while (sections.any { it.title == "ページ$n" }) n++
        return copy(
            pageCount = page + 1,
            sections = sections + Section("ページ$n", emptyList(), page = page),
        )
    }

    /**
     * ページを一枚畳む。**載っていたものは消さず前のページへ移す**——
     * 「追加」の逆は「削除」だが、削除が中身ごと消えることを意味してはならない。
     * 最後の一枚は畳めない(ホームそのものが無くなる)。
     */
    fun pageRemoved(page: Int): LayoutConfig {
        val total = pageCount.coerceAtLeast(1)
        if (total <= 1) return this
        val target = page.coerceIn(0, total - 1)
        val moveTo = if (target > 0) target - 1 else 0
        fun remap(p: Int): Int = when {
            p == target -> moveTo
            p > target -> p - 1
            else -> p
        }
        return copy(
            pageCount = total - 1,
            // 畳んだページに在った節のうち、**中身が無いものは連れて行かない**。
            // 連れて行くと「ページを削除したのに、その名の空の節が残る」ことになり、
            // 利用者から見れば削除できていない。中身のある節は前のページへ移す。
            sections = sections
                .filterNot { it.page == target && it.items.isEmpty() }
                .map { it.copy(page = remap(it.page)) },
            infoTiles = infoTiles.map { it.copy(page = remap(it.page)) },
            widgets = widgets.map { it.copy(page = remap(it.page)) },
        )
    }

    fun sectionRemoved(title: String): LayoutConfig =
        copy(sections = sections.filterNot { it.title == title })

    /** 節の並びを縦(台帳)と横(アイコンの列)で切り替える。 */
    fun sectionRowToggled(title: String): LayoutConfig = copy(
        sections = sections.map { if (it.title == title) it.copy(row = !it.row) else it }
    )

    /** サイトを宣言へ追加し、指定節へ固定する。既存アプリと同じ移動・横並び規則を使う。 */
    fun siteAdded(title: String, location: String, sectionTitle: String): LayoutConfig {
        val site = Site(
            id = "site:${UUID.randomUUID()}",
            title = title.trim(),
            location = location.trim(),
        )
        return copy(sites = sites + site).pinned(site.id, sectionTitle)
    }

    fun siteUpdated(id: String, title: String, location: String): LayoutConfig = copy(
        sites = sites.map { site ->
            if (site.id == id) {
                site.copy(title = title.trim(), location = location.trim())
            } else {
                site
            }
        }
    )

    /** サイトの宣言と、節からの参照を同時に取り除き、到達不能な設定を残さない。 */
    fun siteRemoved(id: String): LayoutConfig =
        copy(
            sites = sites.filterNot { it.id == id },
            sections = sections.map { it.copy(items = it.items - id) },
        )

    /** 群の並びを縦横で切り替える。宣言に無い群は、その名で宣言を新設してから切り替える。 */
    fun groupRowToggled(title: String): LayoutConfig {
        val exists = groups.any { it.title == title }
        val next = if (exists) {
            groups.map { if (it.title == title) it.copy(row = !it.row) else it }
        } else {
            groups + Section(title, emptyList(), row = true)
        }
        return copy(groups = next)
    }

    fun widgetAdded(id: Int, page: Int = 0, zone: String = ZONE_BELOW): LayoutConfig =
        if (widgets.any { it.id == id }) {
            this
        } else {
            copy(
                widgets = widgets + Widget(
                    id = id,
                    page = page.coerceIn(0, pageCount.coerceAtLeast(1) - 1),
                    zone = normalizeZone(zone),
                )
            )
        }

    fun widgetRemoved(id: Int): LayoutConfig =
        copy(widgets = widgets.filterNot { it.id == id })

    fun widgetMoved(id: Int, delta: Int): LayoutConfig {
        val widget = widgets.firstOrNull { it.id == id } ?: return this
        val peers = widgets.filter { it.page == widget.page && it.zone == widget.zone }
        val from = peers.indexOfFirst { it.id == id }
        val target = from + delta
        if (from < 0 || target !in peers.indices) return this
        val other = peers[target]
        val fromGlobal = widgets.indexOfFirst { it.id == id }
        val toGlobal = widgets.indexOfFirst { it.id == other.id }
        val next = widgets.toMutableList()
        next[fromGlobal] = other
        next[toGlobal] = widget
        return copy(widgets = next)
    }

    /**
     * 幅を一段狭める(4→3→2→1→4)。四分割の単位で持つ。
     *
     * [minSpan] より狭くはしない。**ウィジェットは自分が要る最小の幅を申告している**
     * （月表示のカレンダーなら 250dp）。それを下回る桁へ入れると、
     * 中身が潰れるか、作者が用意した小さい版へ差し替わる。
     * 選べてしまうと「置けるのに壊れている」という分かりにくい状態になるので、
     * 最初から選べなくする。
     */
    fun widgetSpanCycled(id: Int, minSpan: Int = 1): LayoutConfig = copy(
        widgets = widgets.map { w ->
            if (w.id != id) {
                w
            } else {
                val floor = minSpan.coerceIn(1, WIDGET_MAX_SPAN)
                w.copy(span = if (w.span <= floor) WIDGET_MAX_SPAN else w.span - 1)
            }
        }
    )

    /** 高さを直に与える(つまみを引いたときに使う)。 */
    fun widgetHeightSet(id: Int, dp: Int): LayoutConfig = copy(
        widgets = widgets.map { w ->
            if (w.id != id) w else w.copy(height = dp.coerceIn(64, 640))
        }
    )

    /** 高さを次の段へ送る。0(自動)から始まり、低・中・高を巡る。 */
    fun widgetHeightCycled(id: Int): LayoutConfig = copy(
        widgets = widgets.map { w ->
            if (w.id != id) w else {
                val i = WIDGET_HEIGHTS.indexOf(w.height)
                w.copy(height = WIDGET_HEIGHTS[(i + 1).mod(WIDGET_HEIGHTS.size)])
            }
        }
    )

    fun widgetZoneToggled(id: Int): LayoutConfig = copy(
        widgets = widgets.map { widget ->
            if (widget.id != id) widget else widget.copy(zone = oppositeZone(widget.zone))
        }
    )

    fun widgetPageMoved(id: Int, delta: Int): LayoutConfig = copy(
        widgets = widgets.map { widget ->
            if (widget.id != id) {
                widget
            } else {
                widget.copy(
                    page = (widget.page + delta).coerceIn(0, pageCount.coerceAtLeast(1) - 1)
                )
            }
        }
    )

    // ---- 内製情報ウィジェット --------------------------------------------

    fun infoTileAdded(tile: InfoTile): LayoutConfig {
        if (infoTiles.any { it.id == tile.id }) return this
        return copy(
            infoTiles = infoTiles + tile.copy(
                page = tile.page.coerceIn(0, pageCount.coerceAtLeast(1) - 1),
                zone = normalizeZone(tile.zone),
                span = tile.span.coerceIn(1, 4),
            )
        )
    }

    fun infoTileUpdated(tile: InfoTile): LayoutConfig = copy(
        infoTiles = infoTiles.map { existing ->
            if (existing.id != tile.id) {
                existing
            } else {
                tile.copy(
                    page = tile.page.coerceIn(0, pageCount.coerceAtLeast(1) - 1),
                    zone = normalizeZone(tile.zone),
                    span = tile.span.coerceIn(1, 4),
                    title = tile.title.trim(),
                    value = tile.value.trim(),
                    source = tile.source.trim(),
                )
            }
        }
    )

    fun infoTileRemoved(id: String): LayoutConfig =
        copy(infoTiles = infoTiles.filterNot { it.id == id })

    fun infoTileMoved(id: String, delta: Int): LayoutConfig {
        val tile = infoTiles.firstOrNull { it.id == id } ?: return this
        val peers = infoTiles.filter { it.page == tile.page && it.zone == tile.zone }
        val peerIndex = peers.indexOfFirst { it.id == id }
        val target = peerIndex + delta
        if (target !in peers.indices) return this
        val other = peers[target]
        val fromGlobal = infoTiles.indexOfFirst { it.id == id }
        val toGlobal = infoTiles.indexOfFirst { it.id == other.id }
        val next = infoTiles.toMutableList()
        next[fromGlobal] = other
        next[toGlobal] = tile
        return copy(infoTiles = next)
    }

    fun infoTileSpanCycled(id: String): LayoutConfig = copy(
        infoTiles = infoTiles.map { tile ->
            if (tile.id != id) tile else tile.copy(span = if (tile.span <= 1) 4 else tile.span - 1)
        }
    )

    fun infoTileZoneToggled(id: String): LayoutConfig = copy(
        infoTiles = infoTiles.map { tile ->
            if (tile.id != id) tile else tile.copy(zone = oppositeZone(tile.zone))
        }
    )

    fun infoTilePageMoved(id: String, delta: Int): LayoutConfig = copy(
        infoTiles = infoTiles.map { tile ->
            if (tile.id != id) {
                tile
            } else {
                tile.copy(page = (tile.page + delta).coerceIn(0, pageCount.coerceAtLeast(1) - 1))
            }
        }
    )

    // ---- 検索面の群 ------------------------------------------------------

    /** 群を明示する。自動分類より宣言が優先される。 */
    fun assignedToGroup(packageName: String, groupTitle: String): LayoutConfig {
        val cleared = groups.map { it.copy(items = it.items - packageName) }
        val exists = cleared.any { it.title == groupTitle }
        val next = if (exists) {
            cleared.map {
                if (it.title == groupTitle) it.copy(items = it.items + packageName) else it
            }
        } else {
            cleared + Section(groupTitle, listOf(packageName))
        }
        return copy(groups = next)
    }

    fun renamedGroup(from: String, to: String): LayoutConfig {
        val name = to.trim()
        if (name.isEmpty() || groups.any { it.title == name }) return this
        val exists = groups.any { it.title == from }
        // 自動分類の名前(宣言に無い群)を改名する場合は、その名で宣言を新設する。
        val next = if (exists) {
            groups.map { if (it.title == from) it.copy(title = name) else it }
        } else {
            groups + Section(name, emptyList())
        }
        return copy(groups = next)
    }

    fun groupAdded(after: String): LayoutConfig =
        copy(groups = groups.withAdded(after, "群"))

    /** 群の宣言を消す。中身は自動分類へ戻るだけで、アプリが消えるわけではない。 */
    fun groupRemoved(title: String): LayoutConfig =
        copy(groups = groups.filterNot { it.title == title })

    // ---- 見え方 ----------------------------------------------------------

    fun withIconSize(dp: Int): LayoutConfig = copy(display = display.copy(iconSize = dp))

    fun withLabels(show: Boolean): LayoutConfig = copy(display = display.copy(showLabels = show))





    private fun Section.moved(packageName: String, delta: Int): Section {
        val i = items.indexOf(packageName)
        if (i < 0) return this
        val target = i + delta
        if (target !in items.indices) return this
        val m = items.toMutableList()
        m.removeAt(i)
        m.add(target, packageName)
        return copy(items = m)
    }

    private fun List<Section>.withAdded(after: String, prefix: String, page: Int = 0): List<Section> {
        var n = size + 1
        while (any { it.title == "$prefix$n" }) n++
        val created = Section("$prefix$n", emptyList(), page = page)
        val index = indexOfFirst { it.title == after }
        val list = toMutableList()
        if (index < 0) list.add(created) else list.add(index + 1, created)
        return list
    }

    companion object {
        const val SCHEMA = 1

        val ICON_SIZES = listOf(20, 24, 32, 40)

        /** 0 は「自動」。以降は dp。 */
        /**
         * 押して巡る高さ。**月表示のカレンダーは 4 セル分（≒400dp）を想定して作られている**
         * ので、300dp で止めると本来の姿が出ない。つまみでは 640dp まで引けたが、
         * 巡回だけを使う人にはそこが天井に見えていた。
         */
        val WIDGET_HEIGHTS = listOf(0, 96, 150, 220, 300, 400, 500, 640)

        /** 四分割の単位。 */
        const val WIDGET_MAX_SPAN = 4

        /**
         * ウィジェット一つ分の縦の単位。点格子(24dp)の四つ分。
         *
         * `targetCellHeight` は「何セル分で作ったか」という申告で、
         * 一セルが何 dp かは置く側が決める。**こちらの格子で答える**。
         */
        const val WIDGET_CELL_DP = 96

        const val ZONE_ABOVE = "above"
        const val ZONE_BELOW = "below"

        const val INFO_WEATHER = "weather"
        const val INFO_CALENDAR = "calendar"
        const val INFO_CALENDAR_MONTH = "calendar_month"
        const val INFO_CALENDAR_TIMELINE = "calendar_timeline"
        /** 次に何をすればいいかを一行で。 */
        const val INFO_NEXT_ACTION = "next_action"
        const val INFO_HABIT = "habit"
        const val INFO_ALARM = "alarm"
        const val INFO_BATTERY = "battery"
        const val INFO_BATTERY_DETAIL = "battery_detail"
        const val INFO_DEVICE = "device"
        const val INFO_DATE = "date"
        const val INFO_WEATHER_DETAIL = "weather_detail"
        const val INFO_AIR_QUALITY = "air_quality"
        const val INFO_NOTE = "note"
        const val INFO_SCHEDULE = "schedule"
        const val INFO_DEADLINE = "deadline"
        const val INFO_NOTIFICATIONS = "notifications"
        const val INFO_MEDIA = "media"
        const val INFO_MUSIC = "music"
        const val INFO_USAGE = "usage"
        const val INFO_USAGE_BARS = "usage_bars"
        const val INFO_TRAFFIC = "traffic"
        const val INFO_CHARGE = "charge"
        const val INFO_SPACE = "space"
        const val INFO_TOUCH = "touch"
        /** 注意の会計。摩擦を掛けたアプリの時間と、開かなかった回数。 */
        const val INFO_ATTENTION = "attention"
        /** 七日の使用時間の散らばり（箱ひげ）。 */
        const val INFO_SPREAD = "spread"
        /** 通信量の七日推移。 */
        const val INFO_TRAFFIC_WEEK = "traffic_week"
        /** 歩数の七日推移。 */
        const val INFO_STEPS_WEEK = "steps_week"
        /** ひと月の使用時間を格子の濃淡で。 */
        const val INFO_USAGE_HEAT = "usage_heat"
        /** 今日ひらいた回数と、アプリ別の内訳。 */
        const val INFO_OPENS = "opens"
        /** 一日のどの時間帯に使ったか。 */
        const val INFO_DAY_SHAPE = "day_shape"
        /** 今この時刻までの使用と、ふだんの同時刻との差。 */
        const val INFO_PACE = "pace"

        // ── 計器盤だけが使う種類 ──────────────────────────
        // **目録には載せない。** 一覧から頁へ足せてしまうと、
        // 「統合済みの札」と「元の札」が同じ面に並ぶことになり、統合した意味が消える。
        /** 天気と天気詳細をひとつに。 */
        const val INFO_WEATHER_FULL = "weather_full"
        /** 端末状態から空き容量を抜いたもの。空きは保存領域の札が持つ。 */
        const val INFO_DEVICE_BRIEF = "device_brief"
        /** 日付情報から「9月1日 火曜日」を抜いたもの。日付は上の時計が持つ。 */
        const val INFO_DATE_BRIEF = "date_brief"
        /** 降水確率と気温の時系列。 */
        const val INFO_WEATHER_SERIES = "weather_series"

        /**
         * 聴いたものの集計。**再生中の操作ではない。**
         *
         * 計器盤には再生の札を置かない（音の面が一枚まるごと受け持つ）。
         * ただし**数**は計器盤の仕事である——
         * 私的な側の再生も、名を伏せてここへ含める。
         */
        const val INFO_LISTENING = "listening"
        const val INFO_SOUND = "sound"
        const val INFO_BLUETOOTH = "bluetooth"
        const val INFO_HEALTH = "health"
        const val INFO_TRANSIT = "transit"
        const val INFO_COMMUTE = "commute"
        const val INFO_MARKET = "market"
        const val INFO_NEWS = "news"

        fun widgetHeightLabel(height: Int): String = when (height) {
            0 -> "自動"
            96 -> "低い"
            150 -> "ふつう"
            220 -> "高い"
            300 -> "とても高い"
            // ここから上は、月表示のカレンダーのように面で見せるもののための段。
            400 -> "月表示ほど"
            500 -> "半画面"
            else -> "最大"
        }

        fun parse(text: String): LayoutConfig? = runCatching {
            val root = JSONObject(text)
            val display = root.optJSONObject("display")
            // 旧い版は時計の周りの情報を真偽値四つで持っていた。
            // 現行は情報カードの台帳が正本なので、**読むときだけ**変換して受け、
            // 書き戻さない。移行のための項目を宣言に残し続けると、
            // 新しい読み手が「どちらが正本か」を毎回判断することになる。
            val legacy = root.optJSONObject("info")
            val sections = parseSections(root.optJSONArray("sections"))
            val storedPageCount = root.optInt("pageCount", 1).coerceAtLeast(1)
            val pageCount = maxOf(storedPageCount, (sections.maxOfOrNull { it.page } ?: 0) + 1)
            LayoutConfig(
                frictionApps = root.optJSONArray("frictionApps")
                    ?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it) } }
                    ?.filter { it.isNotBlank() }
                    ?.toSet()
                    .orEmpty(),
                schema = root.optInt("schema", SCHEMA),
                sections = sections,
                groups = parseSections(root.optJSONArray("groups")),
                display = Display(
                    iconSize = display?.optInt("iconSize", 24) ?: 24,
                    showLabels = display?.optBoolean("showLabels", true) ?: true,
                ),
                pageCount = pageCount,
                infoTiles = if (root.has("infoTiles")) {
                    parseInfoTiles(root.optJSONArray("infoTiles"), pageCount)
                } else {
                    legacyInfoTiles(legacy)
                },
                widgets = root.optJSONArray("widgets").let { arr ->
                    if (arr == null) {
                        emptyList()
                    } else {
                        // 旧い版は数値の並びだった。読めなくなる方向へ倒さない。
                        (0 until arr.length()).mapNotNull { i ->
                            when (val v = arr.opt(i)) {
                                is Int -> Widget(v)
                                is JSONObject -> Widget(
                                    id = v.optInt("id"),
                                    height = v.optInt("height", 0),
                                    span = v.optInt("span", 4).coerceIn(1, 4),
                                    page = v.optInt("page", 0).coerceIn(0, pageCount - 1),
                                    zone = normalizeZone(v.optString("zone", ZONE_BELOW)),
                                )
                                else -> null
                            }
                        }
                    }
                },
                sites = root.optJSONArray("sites").let { arr ->
                    if (arr == null) {
                        emptyList()
                    } else {
                        (0 until arr.length()).mapNotNull { i ->
                            val site = arr.optJSONObject(i) ?: return@mapNotNull null
                            val id = site.optString("id").trim()
                            val title = site.optString("title").trim()
                            val location = site.optString("location").trim()
                            if (id.isEmpty() || title.isEmpty() || location.isEmpty()) null
                            else Site(id = id, title = title, location = location)
                        }.distinctBy { it.id }
                    }
                },
            )
        }.getOrNull()

        private fun parseSections(arr: JSONArray?): List<Section> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val items = o.optJSONArray("items") ?: JSONArray()
                Section(
                    title = o.optString("title", ""),
                    items = (0 until items.length()).map { items.getString(it) },
                    row = o.optBoolean("row", false),
                    page = o.optInt("page", 0).coerceAtLeast(0),
                )
            }
        }

        private fun parseInfoTiles(arr: JSONArray?, pageCount: Int): List<InfoTile> {
            if (arr == null) return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                val value = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = value.optString("id").trim()
                val kind = value.optString("kind").trim()
                if (id.isEmpty() || kind.isEmpty()) return@mapNotNull null
                InfoTile(
                    id = id,
                    kind = kind,
                    page = value.optInt("page", 0).coerceIn(0, pageCount - 1),
                    zone = normalizeZone(value.optString("zone", ZONE_ABOVE)),
                    span = value.optInt("span", 4).coerceIn(1, 4),
                    title = value.optString("title", ""),
                    value = value.optString("value", ""),
                    source = value.optString("source", ""),
                    targetAt = value.optLong("targetAt", 0L),
                )
            }.distinctBy { it.id }
        }

        /** 旧い版の `info` 欄を、現行の情報カードへ読み替える。書き戻しはしない。 */
        private fun legacyInfoTiles(info: JSONObject?): List<InfoTile> = buildList {
            if (info?.optBoolean("showWeather", true) != false) {
                add(InfoTile("legacy:weather", INFO_WEATHER))
            }
            if (info?.optBoolean("showCalendar", true) != false) {
                add(InfoTile("legacy:calendar", INFO_CALENDAR))
            }
            if (info?.optBoolean("showAlarm", true) != false) {
                add(InfoTile("legacy:alarm", INFO_ALARM, span = 2))
            }
            if (info?.optBoolean("showBattery", false) == true) {
                add(InfoTile("legacy:battery", INFO_BATTERY, span = 2))
            }
        }

        private fun normalizeZone(zone: String): String =
            if (zone == ZONE_BELOW) ZONE_BELOW else ZONE_ABOVE

        private fun oppositeZone(zone: String): String =
            if (normalizeZone(zone) == ZONE_ABOVE) ZONE_BELOW else ZONE_ABOVE
    }
}
