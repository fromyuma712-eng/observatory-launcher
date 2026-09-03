package com.yuma.observatorylauncher

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableIntStateOf
import com.yuma.observatorylauncher.ui.LauncherRoot
import com.yuma.observatorylauncher.ui.theme.ObservatoryLauncherTheme

class MainActivity : ComponentActivity() {

    /**
     * ホームは基底状態である、という約束をここで担保する。
     * ホーム操作(HOMEインテントの再送)が来るたびに値を進め、画面側は検索や編集を畳んで
     * 必ず台帳へ戻る。これが無いと、検索を開いたままホームへ戻ったときに
     * 一覧が出たままになり「戻ると一覧が出る」挙動になる。
     */
    private val homeEpoch = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // **ホームの「戻る」は何も起こさない。**
        //
        // ⚠️ 以前はここで `moveTaskToBack(false)` を呼んでいた。
        // Activity の破棄は避けられるが、**ホームを後ろへ退けてしまう**ので、
        // 前に出るものが無いときは系が最近使用画面を出し、
        // 「現在開いているアプリがない」と表示される（実機で確認・2026/8/31）。
        //
        // ホームは階層の底である。底から更に戻る先は無い。
        // 受け取って**握り潰す**のが正しく、これで既定の finish() も走らない。
        // 検索面・編集中はそれぞれの BackHandler が後勝ちで先に処理する。
        onBackPressedDispatcher.addCallback(this) {
            // 意図的に何もしない。
        }

        applyObservatoryWallpaperIfNeeded(this)

        setContent {
            ObservatoryLauncherTheme {
                LauncherRoot(homeEpoch = homeEpoch.intValue)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeEpoch.intValue += 1
    }
}
