package capital.yuri.yuriplayer.core.library

/** Downsampled ARGB for theme extraction (folder art, then embedded). */
expect fun sampleCoverArgb(
    artworkUri: String?,
    audioPath: String? = null,
    size: Int = 48
): IntArray?
