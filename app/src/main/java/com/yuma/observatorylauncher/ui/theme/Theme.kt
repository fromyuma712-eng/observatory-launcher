package com.yuma.observatorylauncher.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * OBSERVATORY(Design02)はMaterial Youのダイナミックカラーに従わない固定パレット。
 * 端末の壁紙色に引きずられないよう dynamicColor は常にオフ。
 */
private val ObservatoryColorScheme = darkColorScheme(
    background = ObservatoryColors.Ground,
    surface = ObservatoryColors.Ground,
    primary = ObservatoryColors.EmberText,
    onBackground = ObservatoryColors.Paper,
    onSurface = ObservatoryColors.Paper,
    onPrimary = ObservatoryColors.Ground,
)

@Composable
fun ObservatoryLauncherTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = ObservatoryColorScheme,
        typography = Typography,
        content = content
    )
}
