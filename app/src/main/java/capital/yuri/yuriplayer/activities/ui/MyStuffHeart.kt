package capital.yuri.yuriplayer.activities.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun MyStuffHeart(
    saved: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
    savedTint: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 24.dp
) {
    IconButton(onClick = onToggle, modifier = modifier) {
        Icon(
            imageVector = if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            contentDescription = if (saved) "Remove from My Stuff" else "Add to My Stuff",
            tint = if (saved) savedTint else tint,
            modifier = Modifier.then(
                if (size != 24.dp) Modifier else Modifier
            )
        )
    }
}
