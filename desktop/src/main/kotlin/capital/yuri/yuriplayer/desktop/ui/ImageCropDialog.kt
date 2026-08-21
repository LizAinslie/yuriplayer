package capital.yuri.yuriplayer.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/**
 * Pan/zoom square crop. Source is scaled to cover the frame; wheel zooms, drag pans.
 */
@Composable
fun ImageCropDialog(
    source: File,
    title: String = "Crop cover",
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

    DialogWindow(onCloseRequest = onCancel, title = title) {
        Surface(Modifier.size(560.dp, 640.dp), color = MaterialTheme.colorScheme.surface) {
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
                        val frame = min(size.width, size.height) * 0.82f
                        val frameLeft = (size.width - frame) / 2f
                        val frameTop = (size.height - frame) / 2f
                        val imgW = image.width.toFloat()
                        val imgH = image.height.toFloat()
                        val cover = max(frame / imgW, frame / imgH) * zoom
                        val drawW = imgW * cover
                        val drawH = imgH * cover
                        val maxPanX = max(0f, (drawW - frame) / 2f)
                        val maxPanY = max(0f, (drawH - frame) / 2f)
                        val px = pan.x.coerceIn(-maxPanX, maxPanX)
                        val py = pan.y.coerceIn(-maxPanY, maxPanY)
                        val left = frameLeft + (frame - drawW) / 2f + px
                        val top = frameTop + (frame - drawH) / 2f + py
                        drawImage(
                            image = bitmap,
                            dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                            dstSize = androidx.compose.ui.unit.IntSize(drawW.toInt(), drawH.toInt())
                        )
                        drawRect(Color.Black.copy(alpha = 0.45f), size = Size(size.width, frameTop))
                        drawRect(
                            Color.Black.copy(alpha = 0.45f),
                            topLeft = Offset(0f, frameTop + frame),
                            size = Size(size.width, size.height - frameTop - frame)
                        )
                        drawRect(
                            Color.Black.copy(alpha = 0.45f),
                            topLeft = Offset(0f, frameTop),
                            size = Size(frameLeft, frame)
                        )
                        drawRect(
                            Color.Black.copy(alpha = 0.45f),
                            topLeft = Offset(frameLeft + frame, frameTop),
                            size = Size(size.width - frameLeft - frame, frame)
                        )
                        drawRect(
                            Color.White,
                            topLeft = Offset(frameLeft, frameTop),
                            size = Size(frame, frame),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            val out = cropToSquare(image, zoom, pan)
                            if (out != null) onCropped(out) else onCancel()
                        }
                    ) { Text("Use photo") }
                }
            }
        }
    }
}

private fun cropToSquare(src: BufferedImage, zoom: Float, pan: Offset): File? {
    val imgW = src.width.toFloat()
    val imgH = src.height.toFloat()
    val frame = 1024f
    val cover = max(frame / imgW, frame / imgH) * zoom.coerceAtLeast(1f)
    val drawW = imgW * cover
    val drawH = imgH * cover
    val maxPanX = max(0f, (drawW - frame) / 2f)
    val maxPanY = max(0f, (drawH - frame) / 2f)
    val px = pan.x.coerceIn(-maxPanX, maxPanX)
    val py = pan.y.coerceIn(-maxPanY, maxPanY)
    val left = (drawW - frame) / 2f - px
    val top = (drawH - frame) / 2f - py
    val srcX = (left / cover).toInt().coerceIn(0, src.width - 1)
    val srcY = (top / cover).toInt().coerceIn(0, src.height - 1)
    val srcSide = (frame / cover).toInt().coerceAtLeast(1)
    val w = min(srcSide, src.width - srcX)
    val h = min(srcSide, src.height - srcY)
    val cropped = src.getSubimage(srcX, srcY, w, h)
    val square = BufferedImage(1024, 1024, BufferedImage.TYPE_INT_RGB)
    val g = square.createGraphics()
    g.drawImage(cropped, 0, 0, 1024, 1024, null)
    g.dispose()
    val out = File.createTempFile("yuri-crop-", ".jpg")
    ImageIO.write(square, "jpg", out)
    return out
}
