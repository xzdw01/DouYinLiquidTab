package io.github.xzdw.dylt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

internal const val AUTHOR_COOLAPK_BIGDICTATOR = "https://www.coolapk.com/u/24915298"
internal const val AUTHOR_COOLAPK_XIAOZHENG = "https://www.coolapk.com/u/2242350"

@Composable
internal fun ManagerHomeLeadingIcon(image: ImageVector, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(30.dp)
            .background(Color.Black, CircleShape),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        Icon(
            imageVector = image,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(16.5.dp),
        )
    }
}
