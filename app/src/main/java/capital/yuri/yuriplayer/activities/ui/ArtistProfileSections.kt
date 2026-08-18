package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.data.source.ArtistEvent
import capital.yuri.yuriplayer.data.source.ArtistLink
import capital.yuri.yuriplayer.data.source.LinkCategory

@Composable
fun ArtistGenreChips(
    genres: List<String>,
    titleColor: androidx.compose.ui.graphics.Color,
    mutedColor: androidx.compose.ui.graphics.Color
) {
    if (genres.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            "Genres",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            genres.take(16).forEach { g ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                ) {
                    Text(
                        g,
                        style = MaterialTheme.typography.labelMedium,
                        color = titleColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ArtistUpcomingShows(
    events: List<ArtistEvent>,
    titleColor: androidx.compose.ui.graphics.Color,
    mutedColor: androidx.compose.ui.graphics.Color,
    onOpenUrl: (String) -> Unit
) {
    if (events.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            "Upcoming shows",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = titleColor
        )
        Text(
            "Bandsintown",
            style = MaterialTheme.typography.labelSmall,
            color = mutedColor,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        events.forEach { e ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (e.url != null) Modifier.clickable { onOpenUrl(e.url) }
                        else Modifier
                    )
                    .padding(vertical = 10.dp)
            ) {
                Text(
                    e.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor
                )
                val place = listOfNotNull(e.venue, e.city, e.region, e.country)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (place.isNotBlank()) {
                    Text(place, style = MaterialTheme.typography.bodySmall, color = mutedColor)
                }
                e.datetime?.let {
                    Text(
                        it.replace('T', ' ').take(16),
                        style = MaterialTheme.typography.labelMedium,
                        color = mutedColor
                    )
                }
            }
            HorizontalDivider(color = mutedColor.copy(alpha = 0.2f))
        }
    }
}

@Composable
fun ArtistBioCard(
    bio: String,
    titleColor: androidx.compose.ui.graphics.Color,
    mutedColor: androidx.compose.ui.graphics.Color
) {
    if (bio.isBlank()) return
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "About",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = titleColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                bio,
                style = MaterialTheme.typography.bodyMedium,
                color = titleColor.copy(alpha = 0.9f)
            )
        }
    }
}

/** Deduped, categorized links for the Links sheet. */
@Composable
fun ArtistDataSourcesContent(
    links: List<ArtistLink>,
    onOpenUrl: (String) -> Unit
) {
    val deduped = links.distinctBy { it.url.lowercase() }
    if (deduped.isEmpty()) {
        Text(
            "No external links found yet.",
            modifier = Modifier.padding(20.dp),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
        )
        return
    }
    val order = listOf(
        LinkCategory.OFFICIAL,
        LinkCategory.STREAMING,
        LinkCategory.SOCIAL,
        LinkCategory.DATABASE,
        LinkCategory.OTHER
    )
    val grouped = deduped.groupBy { it.category }
    order.forEach { cat ->
        val items = grouped[cat].orEmpty()
        if (items.isEmpty()) return@forEach
        Text(
            when (cat) {
                LinkCategory.OFFICIAL -> "Official"
                LinkCategory.STREAMING -> "Streaming & platforms"
                LinkCategory.SOCIAL -> "Social"
                LinkCategory.DATABASE -> "Databases"
                LinkCategory.OTHER -> "Other"
            },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        items.forEach { link ->
            Text(
                link.label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenUrl(link.url) }
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            )
        }
    }
}
