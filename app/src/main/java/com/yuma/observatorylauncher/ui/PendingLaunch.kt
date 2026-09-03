package com.yuma.observatorylauncher.ui

import com.yuma.observatorylauncher.data.AppEntry
import com.yuma.observatorylauncher.data.Friction

/** 摩擦の面が出ている間、掴んでおく起動。 */
internal data class PendingLaunch(
    val entry: AppEntry,
    val level: Friction.Level,
    val notice: String,
)
