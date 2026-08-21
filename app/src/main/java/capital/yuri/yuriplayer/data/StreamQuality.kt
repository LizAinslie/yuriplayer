package capital.yuri.yuriplayer.data

/**
 * Remote stream / prefetch quality. Original is the server file (Jellyfin
 * `static=true`, Subsonic `format=raw`). Other steps request a transcode so
 * the background buffer is smaller on cellular.
 */
enum class StreamQuality(
    val id: String,
    val displayName: String,
    val subtitle: String,
    /** Null = original file. Otherwise target audio bitrate in bits/sec. */
    val bitRate: Int?
) {
    ORIGINAL(
        id = "original",
        displayName = "Original",
        subtitle = "FLAC / source file — streamed, no transcode",
        bitRate = null
    ),
    KBPS_320(
        id = "320",
        displayName = "320 kbps",
        subtitle = "High — AAC/MP3 transcode",
        bitRate = 320_000
    ),
    KBPS_256(
        id = "256",
        displayName = "256 kbps",
        subtitle = "High",
        bitRate = 256_000
    ),
    KBPS_192(
        id = "192",
        displayName = "192 kbps",
        subtitle = "Standard",
        bitRate = 192_000
    ),
    KBPS_128(
        id = "128",
        displayName = "128 kbps",
        subtitle = "Data saver",
        bitRate = 128_000
    ),
    KBPS_96(
        id = "96",
        displayName = "96 kbps",
        subtitle = "Low bandwidth",
        bitRate = 96_000
    );

    val kbps: Int? get() = bitRate?.div(1000)

    companion object {
        /** Play-time default; [LibrarySettings] keeps this in sync with prefs. */
        @Volatile
        var active: StreamQuality = ORIGINAL

        fun fromId(id: String?): StreamQuality =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: ORIGINAL
    }
}
