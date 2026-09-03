package com.yuma.observatorylauncher.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.yuma.observatorylauncher.data.Listening
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 聴いたものの記録を、**面ごとの約束つきで**読む。
 *
 * ## ⚠️ ここに寄せた理由
 *
 * 記録の読み出しは四箇所にあり、そのそれぞれが
 * 「私的な記録をどう扱うか」を自分で決めていた。
 * **決め方が散っていると、面を一つ足したときに落ちる。**
 *
 * 約束は面ごとに違う。だから読み方を一箇所に集め、
 * **どちらの約束で読むかを、呼ぶ側に必ず選ばせる**。
 */
internal enum class Hidden {
    /** 私的な記録を落とす。**音の面はこちら**——あの面は本人の面である。 */
    DROP,

    /**
     * 私的な記録の名を伏せて残す。**計器盤はこちら。**
     * 消すと「今日どれだけ聴いたか」が嘘になるので、数は残す。
     */
    ANONYMISE,
}

@Composable
internal fun rememberPlays(refreshKey: Int, hidden: Hidden): State<List<Listening.Play>> {
    val context = LocalContext.current
    return produceState(emptyList(), refreshKey, hidden) {
        value = withContext(Dispatchers.Default) {
            val log = Listening.read(context)
            when (hidden) {
                Hidden.DROP -> Listening.visible(log)
                Hidden.ANONYMISE -> Listening.anonymised(log)
            }
        }
    }
}
