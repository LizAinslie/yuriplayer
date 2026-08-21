package capital.yuri.yuriplayer.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.components.dialog.InWindowPanel
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pan/zoom crop for any aspect. `aspect` is width / height (1 = square cover,
 * 3 = Twitter-style header). Source is scaled to cover the frame.
 */
@Composable
fun ImageCropDialog(
    source: File,
    title: String = "Crop",
    aspect: Float = 1f,
    onCancel: () -> Unit,
    onCropped: (File) -> Unit
) {
    val image = remember(source) { runCatching { ImageIO.read(source) }.getOrNull() }
    if (image == null) {
        onCancel()
        return
    }
    val bitmap = remember(image) { image.toComposeImageBitmap() }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var stage by remember { mutableStateOf(Size.Zero) }
    val wide = aspect >= 1.4f
    val panel = if (wide) Modifier.size(760.dp, 520.dp) else Modifier.size(560.dp, 640.dp)

    InWindowPanel(onDismiss = onCancel, modifier = panel) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Scroll to zoom · drag to pan",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black)
                        .onSizeChanged { stage = Size(it.width.toFloat(), it.height.toFloat()) }
                        .pointerInput(image) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Scroll) {
                                        val dy = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                        zoom = (zoom * (1f - dy * 0.08f)).coerceIn(1f, 8f)
                                    }
                                }
                            }
                        }
                        .pointerInput(image) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                pan += drag
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val layout = cropLayout(size, image.width, image.height, aspect, zoom, pan)
                        drawImage(
                            image = bitmap,
                            dstOffset = IntOffset(layout.drawLeft.roundToInt(), layout.drawTop.roundToInt()),
                            dstSize = IntSize(layout.drawW.roundToInt().coerceAtLeast(1), layout.drawH.roundToInt().coerceAtLeast(1))
                        )
                        val dim = Color.Black.copy(alpha = 0.5f)
                        drawRect(dim, size = Size(size.width, layout.frameTop))
                        drawRect(
                            dim,
                            topLeft = Offset(0f, layout.frameTop + layout.frameH),
                            size = Size(size.width, size.height - layout.frameTop - layout.frameH)
                        )
                        drawRect(
                            dim,
                            topLeft = Offset(0f, layout.frameTop),
                            size = Size(layout.frameLeft, layout.frameH)
                        )
                        drawRect(
                            dim,
                            topLeft = Offset(layout.frameLeft + layout.frameW, layout.frameTop),
                            size = Size(size.width - layout.frameLeft - layout.frameW, layout.frameH)
                        )
                        val grid = Color.White.copy(alpha = 0.28f)
                        for (i in 1..2) {
                            val x = layout.frameLeft + layout.frameW * i / 3f
                            val y = layout.frameTop + layout.frameH * i / 3f
                            drawLine(grid, Offset(x, layout.frameTop), Offset(x, layout.frameTop + layout.frameH), 1f)
                            drawLine(grid, Offset(layout.frameLeft, y), Offset(layout.frameLeft + layout.frameW, y), 1f)
                        }
                        drawRect(
                            Color.White,
                            topLeft = Offset(layout.frameLeft, layout.frameTop),
                            size = Size(layout.frameW, layout.frameH),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            val out = cropToAspect(image, aspect, zoom, pan, stage)
                            if (out != null) onCropped(out) else onCancel()
                        }
                    ) { Text("Use photo") }
                }
            }
        }
    }
}

private data class CropLayout(
    val frameLeft: Float,
    val frameTop: Float,
    val frameW: Float,
    val frameH: Float,
    val drawLeft: Float,
    val drawTop: Float,
    val drawW: Float,
    val drawH: Float,
    val cover: Float,
    val panX: Float,
    val panY: Float
)

private fun cropLayout(
    canvas: Size,
    imgWpx: Int,
    imgHpx: Int,
    aspect: Float,
    zoom: Float,
    pan: Offset
): CropLayout {
    val maxW = canvas.width * 0.9f
    val maxH = canvas.height * 0.88f
    val ratio = aspect.coerceAtLeast(0.2f)
    val (frameW, frameH) = if (maxW / maxH > ratio) {
        maxH * ratio to maxH
    } else {
        maxW to maxW / ratio
    }
    val frameLeft = (canvas.width - frameW) / 2f
    val frameTop = (canvas.height - frameH) / 2f
    val imgW = imgWpx.toFloat()
    val imgH = imgHpx.toFloat()
    val cover = max(frameW / imgW, frameH / imgH) * zoom.coerceAtLeast(1f)
    val drawW = imgW * cover
    val drawH = imgH * cover
    val maxPanX = max(0f, (drawW - frameW) / 2f)
    val maxPanY = max(0f, (drawH - frameH) / 2f)
    val px = pan.x.coerceIn(-maxPanX, maxPanX)
    val py = pan.y.coerceIn(-maxPanY, maxPanY)
    val drawLeft = frameLeft + (frameW - drawW) / 2f + px
    val drawTop = frameTop + (frameH - drawH) / 2f + py
    return CropLayout(frameLeft, frameTop, frameW, frameH, drawLeft, drawTop, drawW, drawH, cover, px, py)
}

internal fun cropToAspect(
    src: BufferedImage,
    aspect: Float,
    zoom: Float,
    pan: Offset,
    canvas: Size,
    longEdge: Int = 1536
): File? {
    if (canvas.width <= 1f || canvas.height <= 1f) return null
    val layout = cropLayout(canvas, src.width, src.height, aspect.coerceAtLeast(0.2f), zoom, pan)
    val left = (layout.drawW - layout.frameW) / 2f - layout.panX
    val top = (layout.drawH - layout.frameH) / 2f - layout.panY
    val srcX = (left / layout.cover).toInt().coerceIn(0, src.width - 1)
    val srcY = (top / layout.cover).toInt().coerceIn(0, src.height - 1)
    val srcW = (layout.frameW / layout.cover).toInt().coerceAtLeast(1).coerceAtMost(src.width - srcX)
    val srcH = (layout.frameH / layout.cover).toInt().coerceAtLeast(1).coerceAtMost(src.height - srcY)
    val cropped = src.getSubimage(srcX, srcY, srcW, srcH)
    val ratio = aspect.coerceAtLeast(0.2f)
    val outW = if (ratio >= 1f) longEdge else (longEdge * ratio).roundToInt().coerceAtLeast(1)
    val outH = if (ratio >= 1f) (longEdge / ratio).roundToInt().coerceAtLeast(1) else longEdge
    val dest = BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB)
    val g = dest.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.drawImage(cropped, 0, 0, outW, outH, null)
    g.dispose()
    val out = File.createTempFile("yuri-crop-", ".jpg")
    ImageIO.write(dest, "jpg", out)
    return out
}
