package io.github.xzdw.dylt.ui

import android.app.Activity
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import io.github.xzdw.dylt.config.ColorMode
import io.github.xzdw.dylt.config.ManagerSettings

@Composable
fun OfflineGlassTheme(settings: ManagerSettings, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.colorMode) {
        ColorMode.SYSTEM -> systemDark
        ColorMode.LIGHT -> false
        ColorMode.DARK -> true
    }

    LaunchedEffect(dark) {
        val controller = (context as? Activity)?.window?.insetsController ?: return@LaunchedEffect
        val lightFlags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        controller.setSystemBarsAppearance(if (dark) 0 else lightFlags, lightFlags)
    }

    // The manager UI is the ported liquid-glass build, which renders its own glass
    // surfaces over a recorded backdrop, so it deliberately does not sit inside a
    // Material/Miuix theme.
    content()
}
