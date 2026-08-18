package capital.yuri.yuriplayer.activities.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.media.FfmpegService
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Pan/zoom crop with edge snapping: the image always fully covers the crop frame.
 * Still images apply the live transform on save; animated uses center crop via FFmpeg.
 */
@Composable
fun ImageCropScreen(
    sourceUri: Uri,
    title: String = "Crop",
    aspect: Float = 1f,
    onCancel: () -> Unit,
    onCropped: (Uri) -> Unit
) {
    val context = LocalContext.current
    val ffmpeg: FfmpegService = koinInject()
    val http: HttpClient = koinInject()
    val scope = rememberCoroutineScope()
    var localUri by remember { mutableStateOf<Uri?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(Size.Zero) }
    var busy by remember { mutableStateOf(false) }
    var isAnimated by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sourceUri) {
        withContext(Dispatchers.IO) {
            val resolved = resolveToLocal(context, http, sourceUri)
            if (resolved == null) {
                loadError = "Could not load image"
                return@withContext
            }
            localUri = resolved
            val type = runCatching { context.contentResolver.getType(resolved) }.getOrNull()
                ?: resolved.lastPathSegment?.substringAfterLast('.', "")
            isAnimated = type == "image/gif" || type == "gif" ||
                type == "image/webp" || type == "webp"
            if (!isAnimated) {
                context.contentResolver.openInputStream(resolved)?.use {
                    bitmap = BitmapFactory.decodeStream(it)
                }
            }
        }
    }

    fun clampOffset(s: Float, o: Offset, vp: Size, bmp: Bitmap?): Offset {
        if (vp.width <= 0f || vp.height <= 0f || bmp == null) return o
        // Image is drawn with ContentScale.Crop into [vp], then scaled by [s] around center.
        val imgAspect = bmp.width.toFloat() / bmp.height.toFloat()
        val vpAspect = vp.width / vp.height
        val baseW: Float
        val baseH: Float
        if (imgAspect > vpAspect) {
            // image wider → height matches viewport, width overflows
            baseH = vp.height
            baseW = baseH * imgAspect
        } else {
            baseW = vp.width
            baseH = baseW / imgAspect
        }
        val scaledW = baseW * s
        val scaledH = baseH * s
        val maxX = max(0f, (scaledW - vp.width) / 2f)
        val maxY = max(0f, (scaledH - vp.height) / 2f)
        return Offset(
            o.x.coerceIn(-maxX, maxX),
            o.y.coerceIn(-maxY, maxY)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Cancel")
            }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(
                onClick = {
                    if (busy) return@TextButton
                    val src = localUri ?: return@TextButton
                    busy = true
                    scope.launch {
                        val out = withContext(Dispatchers.IO) {
                            cropToFile(
                                context = context,
                                sourceUri = src,
                                bitmap = bitmap,
                                isAnimated = isAnimated,
                                aspect = aspect,
                                scale = scale,
                                offset = offset,
                                viewport = viewportSize,
                                ffmpeg = ffmpeg
                            )
                        }
                        busy = false
                        if (out != null) onCropped(Uri.fromFile(out))
                    }
                },
                enabled = !busy && localUri != null
            ) { Text(if (busy) "Working…" else "Done") }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(aspect)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .onSizeChanged {
                        viewportSize = Size(it.width.toFloat(), it.height.toFloat())
                        // re-clamp after layout
                        offset = clampOffset(scale, offset, viewportSize, bitmap)
                    }
                    .pointerInput(bitmap, viewportSize) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val newScale = (scale * zoom).coerceIn(1f, 6f)
                            val provisional = offset + pan
                            scale = newScale
                            offset = clampOffset(newScale, provisional, viewportSize, bitmap)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                when {
                    loadError != null -> Text(
                        loadError!!,
                        color = MaterialTheme.colorScheme.error
                    )
                    bitmap != null -> Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                    )
                    isAnimated && localUri != null -> Text(
                        "Animated image — center crop on save",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Text(
                        "Loading…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            "Pinch to zoom · drag to pan · snaps to edges · output is ${if (aspect == 1f) "1:1" else aspect}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.padding(16.dp)
        )
    }
}

private suspend fun resolveToLocal(
    context: android.content.Context,
    http: HttpClient,
    uri: Uri
): Uri? {
    val scheme = uri.scheme?.lowercase()
    if (scheme == "http" || scheme == "https") {
        return try {
            val response = http.get(uri.toString())
            if (!response.status.isSuccess()) return null
            val bytes = response.readRawBytes()
            if (bytes.isEmpty()) return null
            val dir = File(context.cacheDir, "remote_images").also { it.mkdirs() }
            val ext = when {
                uri.toString().contains(".png", true) -> "png"
                uri.toString().contains(".gif", true) -> "gif"
                uri.toString().contains(".webp", true) -> "webp"
                else -> "jpg"
            }
            val dest = File(dir, "dl_${System.currentTimeMillis()}.$ext")
            dest.writeBytes(bytes)
            Uri.fromFile(dest)
        } catch (_: Exception) {
            null
        }
    }
    return uri
}

/**
 * Map the live pan/zoom transform back onto the source bitmap and write a JPEG.
 * Animated sources still use center crop via FFmpeg.
 */
private fun cropToFile(
    context: android.content.Context,
    sourceUri: Uri,
    bitmap: Bitmap?,
    isAnimated: Boolean,
    aspect: Float,
    scale: Float,
    offset: Offset,
    viewport: Size,
    ffmpeg: FfmpegService
): File? {
    val dir = File(context.cacheDir, "crops").also { it.mkdirs() }
    val out = File(dir, "crop_${System.currentTimeMillis()}.jpg")

    if (isAnimated) {
        val tmp = File(dir, "anim_src_${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: return null
        val gifOut = File(dir, "crop_${System.currentTimeMillis()}.gif")
        val target = if (
            sourceUri.toString().contains("gif", true) ||
                (sourceUri.path?.endsWith(".gif", true) == true)
        ) gifOut else out
        val ok = runBlocking {
            ffmpeg.cropCenter(tmp, target, 512, (512 / aspect).toInt().coerceAtLeast(1))
        }
        tmp.delete()
        return if (ok) target else {
            val frame = BitmapFactory.decodeFile(tmp.absolutePath) ?: return null
            val side = min(frame.width, frame.height)
            val x = ((frame.width - side) / 2f).toInt().coerceAtLeast(0)
            val y = ((frame.height - side) / 2f).toInt().coerceAtLeast(0)
            val cropped = Bitmap.createBitmap(frame, x, y, side, side)
            FileOutputStream(out).use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            out
        }
    }

    val src = bitmap ?: return null
    if (viewport.width <= 0f || viewport.height <= 0f) {
        // Fallback center crop matching aspect
        return centerCropBitmap(src, aspect, out)
    }

    val imgAspect = src.width.toFloat() / src.height.toFloat()
    val vpAspect = viewport.width / viewport.height
    val baseW: Float
    val baseH: Float
    if (imgAspect > vpAspect) {
        baseH = viewport.height
        baseW = baseH * imgAspect
    } else {
        baseW = viewport.width
        baseH = baseW / imgAspect
    }
    val scaledW = baseW * scale
    val scaledH = baseH * scale

    // Top-left of scaled image relative to viewport center
    val left = (viewport.width - scaledW) / 2f + offset.x
    val top = (viewport.height - scaledH) / 2f + offset.y

    // Viewport rect in scaled-image coordinates → source bitmap pixels
    val srcLeft = ((0f - left) / scaledW * src.width).coerceIn(0f, src.width.toFloat())
    val srcTop = ((0f - top) / scaledH * src.height).coerceIn(0f, src.height.toFloat())
    val srcRight = ((viewport.width - left) / scaledW * src.width).coerceIn(0f, src.width.toFloat())
    val srcBottom = ((viewport.height - top) / scaledH * src.height).coerceIn(0f, src.height.toFloat())

    val x = srcLeft.toInt().coerceIn(0, src.width - 1)
    val y = srcTop.toInt().coerceIn(0, src.height - 1)
    val w = (srcRight - srcLeft).toInt().coerceAtLeast(1).coerceAtMost(src.width - x)
    val h = (srcBottom - srcTop).toInt().coerceAtLeast(1).coerceAtMost(src.height - y)

    val cropped = Bitmap.createBitmap(src, x, y, w, h)
    FileOutputStream(out).use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    return out
}

private fun centerCropBitmap(src: Bitmap, aspect: Float, out: File): File {
    val srcAspect = src.width.toFloat() / src.height.toFloat()
    val cropW: Int
    val cropH: Int
    if (srcAspect > aspect) {
        cropH = src.height
        cropW = (cropH * aspect).toInt().coerceAtLeast(1)
    } else {
        cropW = src.width
        cropH = (cropW / aspect).toInt().coerceAtLeast(1)
    }
    val x = ((src.width - cropW) / 2f).toInt().coerceAtLeast(0)
    val y = ((src.height - cropH) / 2f).toInt().coerceAtLeast(0)
    val cropped = Bitmap.createBitmap(src, x, y, cropW.coerceAtMost(src.width - x), cropH.coerceAtMost(src.height - y))
    FileOutputStream(out).use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    return out
}
