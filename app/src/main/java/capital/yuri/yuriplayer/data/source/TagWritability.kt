package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.Song

/**
 * Whether embedded audio tags can be written for offerings of this source type.
 *
 * Writable today:
 * - [SourceType.LOCAL] (MediaStore / SAF / file paths)
 * - [SourceType.WEBDAV] (and later OneDrive / Dropbox / Drive / Nextcloud mounts)
 *
 * Never writable (streaming library APIs with no file-level tag write):
 * - [SourceType.JELLYFIN]
 * - [SourceType.NAVIDROME] (Subsonic / OpenSubsonic / Navidrome)
 * - [SourceType.OTHER]
 */
val SourceType.supportsEmbeddedTagWrites: Boolean
    get() = when (this) {
        SourceType.LOCAL -> true
        SourceType.WEBDAV -> true
        SourceType.JELLYFIN -> false
        SourceType.NAVIDROME -> false
        SourceType.OTHER -> false
    }

fun SourceOffering.isTagWritable(): Boolean = sourceType.supportsEmbeddedTagWrites

fun List<SourceOffering>.writableOfferings(): List<SourceOffering> =
    filter { it.isTagWritable() }

fun List<SourceOffering>.readOnlyOfferings(): List<SourceOffering> =
    filter { !it.isTagWritable() }

/**
 * A song that has at least one offering whose source supports embedded tag writes.
 * Only those [writableOfferings] are eligible for metadata save; the user picks
 * which subset to update when more than one is present.
 *
 * Songs that only exist on Jellyfin / Subsonic (Navidrome) never implement this.
 */
interface MetadataWritableSong {
    val song: Song
    /** Offerings that can actually receive tag writes (never Jellyfin/Navidrome). */
    val writableOfferings: List<SourceOffering>

    val hasWritableSource: Boolean
        get() = writableOfferings.isNotEmpty()
}

/**
 * An album that has at least one track offering with a writable source.
 * Album-level tag writes (album name, album artist, year, genre, cover) only
 * touch the selected writable offerings.
 */
interface MetadataWritableAlbum {
    val album: AlbumItem
    val writableOfferings: List<SourceOffering>

    val hasWritableSource: Boolean
        get() = writableOfferings.isNotEmpty()
}

data class WritableSongTarget(
    override val song: Song,
    override val writableOfferings: List<SourceOffering>
) : MetadataWritableSong {
    init {
        require(writableOfferings.isNotEmpty()) {
            "WritableSongTarget requires at least one writable offering"
        }
        require(writableOfferings.all { it.isTagWritable() }) {
            "writableOfferings must all support embedded tag writes"
        }
    }
}

data class WritableAlbumTarget(
    override val album: AlbumItem,
    override val writableOfferings: List<SourceOffering>
) : MetadataWritableAlbum {
    init {
        require(writableOfferings.isNotEmpty()) {
            "WritableAlbumTarget requires at least one writable offering"
        }
        require(writableOfferings.all { it.isTagWritable() }) {
            "writableOfferings must all support embedded tag writes"
        }
    }
}

/**
 * Build a [MetadataWritableSong] only when the song has writable offerings.
 * Returns null for pure Jellyfin / Navidrome (Subsonic) tracks.
 */
fun Song.asWritableTarget(offerings: List<SourceOffering>): MetadataWritableSong? {
    val writable = offerings.writableOfferings()
    if (writable.isEmpty()) return null
    return WritableSongTarget(song = this, writableOfferings = writable)
}

/**
 * Build a [MetadataWritableAlbum] from the album's track offerings that support writes.
 */
fun AlbumItem.asWritableTarget(offerings: List<SourceOffering>): MetadataWritableAlbum? {
    val writable = offerings.writableOfferings()
    if (writable.isEmpty()) return null
    return WritableAlbumTarget(album = this, writableOfferings = writable)
}

/** Human label for source sheets: "Local files · writable" vs "Navidrome · read-only". */
fun SourceOffering.writabilityLabel(): String =
    if (isTagWritable()) "$sourceName · writable" else "$sourceName · read-only"
