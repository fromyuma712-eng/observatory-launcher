package com.yuma.observatorylauncher.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 動きの語彙は rise と G-2標識灯の二つで打ち止め(仕様確定済み)。
 * 新しい動きの語彙をここに増やさない。
 */
object ObservatoryMotion {
    // rise: opacity + translateY 4dp, 200ms
    const val RiseDurationMs = 200
    val RiseOffset = 4.dp

    // G-2 標識灯: 40回/分 = 周期1500ms・点灯350ms。用途は「存在の申告」のみ。
    const val BeaconPeriodMs = 1500
    const val BeaconOnMs = 350
}
