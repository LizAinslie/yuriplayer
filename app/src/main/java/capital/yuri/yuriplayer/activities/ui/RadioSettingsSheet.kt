package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.player.radio.RadioSession
import capital.yuri.yuriplayer.player.radio.RadioShuffleUnit
import capital.yuri.yuriplayer.player.radio.RadioSourcePrefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioSettingsSheet(
    session: RadioSession,
    onApply: (RadioSourcePrefs) -> Unit,
    onDismiss: () -> Unit
) {
    var prefs by remember(session.seedId, session.displayName) {
        mutableStateOf(session.prefs)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text(
                text = session.displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Radio preferences for this station",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
            )

            SectionLabel("Playback")
            PrefSwitch(
                title = "Shuffle",
                subtitle = if (prefs.shuffle) {
                    when (prefs.shuffleUnit) {
                        RadioShuffleUnit.SONGS -> "Random tracks from the pool"
                        RadioShuffleUnit.RELEASES -> "Random whole releases, tracks in order"
                    }
                } else {
                    "Whole releases in order (year desc)"
                },
                checked = prefs.shuffle,
                onCheckedChange = { prefs = prefs.copy(shuffle = it) }
            )

            if (prefs.shuffle) {
                Text(
                    "Shuffle unit",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 6.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = prefs.shuffleUnit == RadioShuffleUnit.SONGS,
                        onClick = { prefs = prefs.copy(shuffleUnit = RadioShuffleUnit.SONGS) },
                        label = { Text("Songs") }
                    )
                    FilterChip(
                        selected = prefs.shuffleUnit == RadioShuffleUnit.RELEASES,
                        onClick = { prefs = prefs.copy(shuffleUnit = RadioShuffleUnit.RELEASES) },
                        label = { Text("Releases") }
                    )
                }
                Text(
                    when (prefs.shuffleUnit) {
                        RadioShuffleUnit.SONGS ->
                            "Picks individual tracks at random from the whole radio pool."
                        RadioShuffleUnit.RELEASES ->
                            "Picks a random LP/EP/Single, plays its tracks in order, then the next."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel("Discovery sources")
            Text(
                "Combine any of these to expand what radio can pull from. " +
                    "Library is the on-device catalog; the others inject similar artists/songs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            PrefSwitch(
                title = "Library",
                subtitle = "Local + already-scanned sources in the catalog",
                checked = prefs.useLibraryDiscovery,
                onCheckedChange = { prefs = prefs.copy(useLibraryDiscovery = it) }
            )
            PrefSwitch(
                title = "Jellyfin Instant Mix",
                subtitle = "Server similar / Instant Mix when available",
                checked = prefs.useJellyfinInstantMix,
                onCheckedChange = { prefs = prefs.copy(useJellyfinInstantMix = it) }
            )
            PrefSwitch(
                title = "Subsonic / Navidrome",
                subtitle = "getSimilarSongs / getSimilarArtists",
                checked = prefs.useSubsonicSimilar,
                onCheckedChange = { prefs = prefs.copy(useSubsonicSimilar = it) }
            )
            PrefSwitch(
                title = "Monochrome",
                subtitle = "monochrome.tf recommendations (Tidal metadata)",
                checked = prefs.useMonochromeDiscovery,
                onCheckedChange = { prefs = prefs.copy(useMonochromeDiscovery = it) }
            )

            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(
                    onClick = {
                        onApply(prefs)
                        onDismiss()
                    }
                ) { Text("Apply") }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun PrefSwitch(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
