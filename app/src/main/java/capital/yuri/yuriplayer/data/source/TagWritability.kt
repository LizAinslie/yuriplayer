package capital.yuri.yuriplayer.data.source

import capital.yuri.yuriplayer.data.AlbumItem
import capital.yuri.yuriplayer.data.Song

/**
 * A concrete playable offering that **may** accept embedded tag writes.
 * Streaming servers (Jellyfin / Subsonic / OpenSubsonic / Navidrome) never do.
 * Local files, SAF trees, WebDAV, and future cloud mounts (Drive / Dropbox /
 * OneDrive / Nextcloud) can when a writable path or content URI is available.
 */
data class MetadataWritableSong(
    val song: Song,
    val offering: SourceOffering,
    /** True only when both the source type allows writes and a file/URI target exists. */
    val canWriteTags: Boolean
)

/** Album-level view of which member tracks have writable offerings. */
data class MetadataWritableAlbum(
    val album: AlbumItem,
    val writableTracks: List<MetadataWritableSong>
) {
    val canWriteTags: Boolean get() = writableTracks.any { it.canWriteTags }
    val writableSongs: List<Song> get() = writableTracks.filter { it.canWriteTags }.map { it.song }
}

/**
 * Whether this backend type ever supports embedded audio-tag mutation.
 * Independent of whether a particular file is currently openable.
 */
fun SourceType.supportsEmbeddedTagWrites(): Boolean = when (this) {
    SourceType.LOCAL -> true
    SourceType.WEBDAV -> true
    // Future cloud file backends (OneDrive, Dropbox, Drive, Nextcloud, …)
    SourceType.OTHER -> true
    SourceType.JELLYFIN -> false
    SourceType.SUBSONIC -> false
    SourceType.NAVIDROME -> false
}

fun SourceOffering.isStreamingReadOnly(): Boolean =
    !sourceType.supportsEmbeddedTagWrites()

fun SourceOffering.displayLabel(): String = when {
    sourceName.isNotBlank() -> sourceName
    sourceType == SourceType.LOCAL -> "On this device"
    else -> sourceType.displayName()
}
