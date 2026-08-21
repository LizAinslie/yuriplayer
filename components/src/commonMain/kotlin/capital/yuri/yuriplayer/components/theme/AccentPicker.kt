package capital.yuri.yuriplayer.components.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeModePicker(
    mode: ThemeMode,
    onMode: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ThemeMode.entries.forEach { m ->
            FilterChip(
                selected = mode == m,
                onClick = { onMode(m) },
                label = { Text(m.label) }
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccentPicker(
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        Text(
            "Accent",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentCatalog.all.forEach { swatch ->
                val selected = swatch.id == selectedId
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(swatch.color)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                            shape = CircleShape
                        )
                        .clickable { onSelect(swatch.id) }
                )
            }
        }
    }
}
