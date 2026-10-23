package io.github.xzdw.dylt.ui.liquid.utils

import androidx.compose.runtime.withFrameNanos

internal suspend fun awaitFrame(): Unit = withFrameNanos { }
