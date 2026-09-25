package com.yuma.observatorylauncher.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 動きの語彙。
 *
 * 2026-09-25 までは「rise と G-2 標識灯の二つで打ち止め」だった。本人が解いた
 * （「スマホは動いてなんぼ快適でなんぼ」）。**足してよいが、役目の無い動きは足さない。**
 *
 * | 語 | 役目 | 所在 |
 * | --- | --- | --- |
 * | rise | 面に来たものが立ち上がる | `riseIn` |
 * | 標識灯 | 存在の申告（点滅） | `Beacon` |
 * | 沈み | 押せたことを指へ返す | `PressIndication` |
 * | 追従 | 面が指に付いてくる・途中で引き返せる | `HorizontalPager` |
 * | （拡がり） | 像から拡がって開く——**OS が許さず断念**（AppRepository.launchNow の注記） | — |
 * | 伏せ | ジャケットが色面に溶けて替わる（360ms） | `ColorField` |
 * | 呼吸 | 鳴っている間、絵が 12 秒で 3% 寄って引く | `breathing` |
 * | 補間 | 進行の帯が 0.5 秒ごとの値の間を途切れず進む | `NowPlaying` |
 */
object ObservatoryMotion {
    // rise: opacity + translateY 4dp, 200ms
    const val RiseDurationMs = 200
    val RiseOffset = 4.dp

    // G-2 標識灯: 40回/分 = 周期1500ms・点灯350ms。用途は「存在の申告」のみ。
    const val BeaconPeriodMs = 1500
    const val BeaconOnMs = 350
}
