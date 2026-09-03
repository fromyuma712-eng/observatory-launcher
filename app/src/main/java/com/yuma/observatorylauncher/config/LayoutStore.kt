package com.yuma.observatorylauncher.config

import android.content.Context
import java.io.File
import org.json.JSONArray

/** 何も無いときの宣言。ファイル自体が無い端末で初回に書き出す。 */
/**
 * 残す世代の数。1枚あたり数KBなので、これで数百KBに収まる。
 * 巻き戻しは「直前の失敗を取り消す」ためのもので、何十手も遡る道具ではない。
 */
private const val MAX_GENERATIONS = 60

private const val EMPTY = """{"schema":1,"sections":[]}"""

/**
 * 端末で使うときの入口。**[LayoutStore] 自身は Android に依存しない**——
 * 置き場所と初期値だけを受け取る形にしてあるので、世代と巻き戻しの振る舞いを
 * 実機を使わずに試験できる（試験できない部品は、壊れても気付けない）。
 */
fun LayoutStore(context: Context): LayoutStore = LayoutStore(
    filesDir = context.filesDir,
    seed = {
        runCatching {
            context.assets.open("layout.default.json").bufferedReader().use { it.readText() }
        }.getOrNull() ?: EMPTY
    },
)

/**
 * 宣言(layout.json)の保管庫。
 * 書き換えのたびに旧版を世代として残す＝NixOS の世代と §N-6「地層」の実装。
 * 訂正で古い値を消さない(§C-2)ため、ロールバックもまた新しい世代として積む。
 */
class LayoutStore(
    private val filesDir: File,
    private val seed: () -> String,
) {
    companion object {
        /** 何も無いときの宣言。試験からも参照する。 */
        const val EMPTY_SEED = EMPTY
    }

    private val current: File get() = File(filesDir, "layout.json")
    private val generationsDir: File get() = File(filesDir, "generations")
    private val undoStackFile: File get() = File(filesDir, "undo-stack.json")

    /** 現在の宣言。無ければ既定を書き出してから返す。 */
    fun load(): LayoutConfig {
        if (!current.exists()) {
            filesDir.mkdirs()
            current.writeText(seed())
        }
        return LayoutConfig.parse(current.readText()) ?: LayoutConfig(sections = emptyList())
    }

    /**
     * 控えとして書き出す文字列。今の宣言をそのまま返す。
     * 端末を替えても、世代を失っても、これさえ在れば元へ戻せる。
     */
    fun exportText(): String = if (current.exists()) current.readText() else seed()

    /**
     * 控えから戻す。読めない文字列は受け付けない（壊れた宣言で上書きしない）。
     * 戻すこと自体も一つの書き換えとして世代へ積むので、取り消せる。
     */
    fun importText(text: String): LayoutConfig? {
        val parsed = LayoutConfig.parse(text) ?: return null
        save(parsed)
        return parsed
    }

    /** 旧版を世代へ退避してから書き込む。 */
    fun save(config: LayoutConfig) {
        // archive後に旧世代から索引を初期化すると、同じ世代を二重登録する。
        // 先に現在の巻き戻し対象を確定してから一件だけ積む。
        val undo = undoSteps()
        val archived = if (current.exists()) archive(current.readText()) else null
        current.writeText(config.toJson())
        if (archived != null) writeUndoSteps(undo + archived.name)
        // 刈り込みは履歴を書き終えた後に行う。先に刈ると、直後の書き戻しで復活する。
        prune()
    }

    fun generationCount(): Int = undoSteps().size

    /**
     * 直近の世代へ戻す。戻す前の現行も世代として残すので、巻き戻し自体を巻き戻せる。
     * 戻せる世代が無ければ null を返す(呼び出し側で黙って何もしない)。
     */
    fun rollback(): LayoutConfig? {
        // 消えた世代を指していたら、そこで止まらずに次を見る。
        // 「戻せないのに履歴が減らない」状態は、永久に巻き戻せない詰みになる。
        var undo = undoSteps()
        var target: File? = null
        while (undo.isNotEmpty()) {
            val candidate = File(generationsDir, undo.last())
            if (candidate.exists()) {
                target = candidate
                break
            }
            undo = undo.dropLast(1)
            writeUndoSteps(undo)
        }
        if (target == null) return null
        val text = target.readText()
        val restored = LayoutConfig.parse(text) ?: return null
        // 巻き戻す前の現行も履歴として保存するが、次のundo対象には積まない。
        // 積むと次回はその状態へ戻ってしまい、二状態の往復になる。
        if (current.exists()) archive(current.readText())
        current.writeText(text)
        writeUndoSteps(undo.dropLast(1))
        return restored
    }

    private fun archive(text: String): File {
        generationsDir.mkdirs()
        val next = (generations().lastOrNull()?.nameWithoutExtension?.toIntOrNull() ?: 0) + 1
        return File(generationsDir, "%04d.json".format(next)).also { it.writeText(text) }
    }

    /**
     * 古い世代を捨てる。**地層は積むものだが、無限に積めるものではない**。
     * 端末の中に溜め続ければいつか溢れるので、直近 [MAX_GENERATIONS] 枚だけ残す。
     * 捨てた分は巻き戻しの履歴からも外す——参照だけ残ると、押しても戻らない項目になる。
     */
    private fun prune() {
        val all = generations()
        if (all.size <= MAX_GENERATIONS) return
        val doomed = all.take(all.size - MAX_GENERATIONS)
        val names = doomed.map { it.name }.toSet()
        doomed.forEach { it.delete() }
        writeUndoSteps(undoSteps().filterNot { it in names })
    }

    private fun generations(): List<File> =
        generationsDir.listFiles()
            ?.filter { it.name.matches(Regex("\\d{4,}\\.json")) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** 旧版からの更新時は、既存の全世代を一度だけundo対象として引き継ぐ。 */
    private fun undoSteps(): List<String> {
        if (undoStackFile.exists()) {
            val parsed = runCatching {
                val array = JSONArray(undoStackFile.readText())
                (0 until array.length()).map { array.getString(it) }
            }.getOrNull()
            if (parsed != null) return parsed
        }
        val all = generations()
        val currentConfig = if (current.exists()) LayoutConfig.parse(current.readText()) else null
        val lastCurrent = if (currentConfig == null) {
            -1
        } else {
            all.indexOfLast { file -> LayoutConfig.parse(file.readText()) == currentConfig }
        }
        // 外部バックアップから現行を復元した場合、その現行と同じ世代より後ろには
        // 検証や破棄した枝が残りうる。履歴ファイルは消さず、undo対象からだけ外す。
        val initial = if (lastCurrent in 0 until all.lastIndex) {
            all.take(lastCurrent)
        } else {
            all
        }
        return initial.map { it.name }.also(::writeUndoSteps)
    }

    private fun writeUndoSteps(steps: List<String>) {
        undoStackFile.writeText(JSONArray(steps).toString())
    }

}
