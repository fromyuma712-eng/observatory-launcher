plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Android Studio と CLI が同一の build/ を同時に触ると、Windowsでは中間ファイルが
// ロックされ「Unable to delete directory / Failed to clean up output files」で落ちる。
// デーモン停止で凌ぐと同じ事故を毎回踏むので、CLIからのビルドは出力先ごと分離する。
//
// ただし分離先を **プロジェクト配下に置いてはならない** ——
// Android Studio はモジュール直下を丸ごと監視・索引するため、app/build-cli でも
// 同じロックに巻き込まれる(実際に巻き込まれたので下記へ変更した)。
// 出力はIDEの監視範囲の外(ユーザーのTEMP配下)へ置く。
//   CLI:  ./gradlew assembleDebug -PcliBuild   → %TEMP%/observatory-build-cli
//   IDE:  従来どおり app/build
if (providers.gradleProperty("cliBuild").isPresent) {
    val outside = File(System.getProperty("java.io.tmpdir"), "observatory-build-cli")
    layout.buildDirectory.set(outside)
}

android {
    namespace = "com.yuma.observatorylauncher"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.yuma.observatorylauncher"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 日常使いの版に署名する。
    //
    // ⚠️ **デバッグ版は AOT コンパイルされない。**
    // `android:debuggable` が立っている間、ART は必ず解釈実行のままにする
    // （実機で `pm art dump` が `status=verify` を返し、`cmd package compile -m speed -f`
    // を通しても `verify` から動かないことを確認した・2026/8/31）。
    // そのため一覧を掃くたびに実行時コンパイルの暖まりが起き、
    // 同じ操作を繰り返すと 18% → 3% と落ちていく曲線が出る。
    // 常用するのは曲線の冷たい側なので、体感はいつも重い。
    //
    // **開発用の鍵をそのまま使う。** 署名が同じなら既に入っている版へ上書きでき、
    // 宣言(layout.json)・世代・既定ホームの指定・許可が失われない。
    signingConfigs {
        create("daily") {
            storeFile = File(System.getProperty("user.home"), ".android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // R8 は当面かけない。**効きの主因は debuggable の解除**であって、
            // 縮小ではない。壊れる余地を増やさずに取れる分を先に取る。
            optimization {
                enable = false
            }
            signingConfig = signingConfigs.getByName("daily")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // 歩数・睡眠・心拍は Health Connect からしか読めない。
    // Android 14 以降は OS の一部だが、読み取りの窓口はこの依存が持つ。
    implementation(libs.androidx.health.connect)
    testImplementation(libs.junit)
    // Android 同梱の org.json は JVM の試験では中身が無く、呼ぶと例外になる。
    // 本物を試験の依存に置くことで、宣言の読み書きを**実機を使わずに**確かめられる。
    // 実機の計測試験は対象アプリを消す(既定ホーム・許可・filesDir が失われる)ので、
    // 端末を要しない検証はすべてこちら側へ寄せる。
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}