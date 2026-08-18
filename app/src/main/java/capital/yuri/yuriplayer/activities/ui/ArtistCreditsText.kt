package capital.yuri.yuriplayer.activities.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import capital.yuri.yuriplayer.data.Song

/**
 * Renders track artist credits as comma-separated tappable names (Spotify-style).
 * Falls back to plain [Song.displayArtist] when there is only one / no parseable credits.
 */
@Composable
fun ArtistCreditsText(
    song: Song,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
    linkColor: Color = color,
    maxLines: Int = 1,
    onArtistClick: (String) -> Unit
) {
    val credits = remember(song.artist, song.albumArtist) { song.creditArtists }
    if (credits.isEmpty()) {
        Text(
            text = song.displayArtist,
            style = style,
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier
        )
        return
    }
    if (credits.size == 1) {
        Text(
            text = credits[0],
            style = style,
            color = linkColor,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.clickable { onArtistClick(credits[0]) }
        )
        return
    }

    val annotated = remember(credits, linkColor, color) {
        buildAnnotatedString {
            credits.forEachIndexed { index, name ->
                if (index > 0) {
                    withStyle(SpanStyle(color = color)) { append(", ") }
                }
                pushStringAnnotation(tag = "artist", annotation = name)
                withStyle(
                    SpanStyle(
                        color = linkColor,
                        fontWeight = FontWeight.Medium
                    )
                ) {
                    append(name)
                }
                pop()
            }
        }
    }

    ClickableText(
        text = annotated,
        style = style.copy(color = color),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
        onClick = { offset ->
            annotated.getStringAnnotations("artist", offset, offset)
                .firstOrNull()
                ?.let { onArtistClick(it.item) }
        }
    )
}
