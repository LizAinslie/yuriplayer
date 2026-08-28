package capital.yuri.yuriplayer.core.library

import org.jaudiotagger.audio.AudioFileIO
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import javax.imageio.ImageIO

actual fun sampleCoverArgb(artworkUri: String?, audioPath: String?, size: Int): IntArray? =
    CoverPixels.argb(artworkUri, audioPath, size)

/** Downsampled ARGB for theme extraction. Folder art first, then embedded. */
object CoverPixels {
    fun argb(artworkUri: String?, audioPath: String?, size: Int = 48): IntArray? {
        val image = load(artworkUri) ?: loadEmbedded(audioPath) ?: return null
        return downsample(image, size)
    }

    private fun load(uri: String?): BufferedImage? {
        if (uri.isNullOrBlank()) return null
        return try {
            when {
                uri.startsWith("http://", true) || uri.startsWith("https://", true) ->
                    ImageIO.read(URI(uri).toURL())
                uri.startsWith("file:") -> {
                    val file = File(URI(uri))
                    if (!file.isFile) null else ImageIO.read(file)
                }
                else -> {
                    val file = File(uri)
                    if (!file.isFile) null else ImageIO.read(file)
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun loadEmbedded(audioPath: String?): BufferedImage? {
        if (audioPath.isNullOrBlank()) return null
        val file = File(audioPath)
        if (!file.isFile) return null
        return try {
            val art = AudioFileIO.read(file).tag?.firstArtwork ?: return null
            val bytes = art.binaryData ?: return null
            ImageIO.read(ByteArrayInputStream(bytes))
        } catch (_: Exception) {
            null
        }
    }

    private fun downsample(src: BufferedImage, size: Int): IntArray {
        val dst = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = dst.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(src, 0, 0, size, size, null)
        g.dispose()
        val pixels = IntArray(size * size)
        dst.getRGB(0, 0, size, size, pixels, 0, size)
        return pixels
    }
}
