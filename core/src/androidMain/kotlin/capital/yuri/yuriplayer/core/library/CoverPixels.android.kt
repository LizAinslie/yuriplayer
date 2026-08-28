package capital.yuri.yuriplayer.core.library

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.URI

actual fun sampleCoverArgb(artworkUri: String?, audioPath: String?, size: Int): IntArray? {
    val path = when {
        artworkUri.isNullOrBlank() -> audioPath
        artworkUri.startsWith("file:") -> runCatching { File(URI(artworkUri)).path }.getOrNull()
        else -> artworkUri
    } ?: return null
    val file = File(path)
    if (!file.isFile) return null
    val opts = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(file.absolutePath, opts)
    val longest = maxOf(opts.outWidth, opts.outHeight).coerceAtLeast(1)
    val sample = (longest / size).coerceAtLeast(1)
    val decoded = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
    ) ?: return null
    val scaled = Bitmap.createScaledBitmap(decoded, size, size, true)
    if (scaled != decoded) decoded.recycle()
    val pixels = IntArray(size * size)
    scaled.getPixels(pixels, 0, size, 0, 0, size, size)
    if (scaled != decoded) scaled.recycle()
    return pixels
}
