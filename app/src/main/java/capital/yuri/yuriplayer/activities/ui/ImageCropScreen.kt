package capital.yuri.yuriplayer.activities.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import android.graphics.Rect as AndroidRect
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.BuildConfig
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

/** Max pinch scale relative to ContentScale.Fit base (1f = fit to stage). */
private const val MaxZoomIn = 8f

/**
 * Pan/zoom crop: source is shown at natural aspect (Fit), with a fixed target-aspect
 * frame overlaid. The image is always forced to **cover** the frame — zoom cannot go
 * below the cover scale, and pan is clamped so the frame never shows empty space.
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
    var stageSize by remember { mutableStateOf(Size.Zero) }
    var busy by remember { mutableStateOf(false) }
    var isAnimated by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val chrome = Color(0xFF121212)
    val onChrome = Color(0xFFF5F5F5)
    val onChromeMuted = Color(0xFFB0B0B0)
    val frameStroke = Color.White.copy(alpha = 0.95f)
    val gridStroke = Color.White.copy(alpha = 0.35f)
    val dimOutside = Color.Black.copy(alpha = 0.58f)

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

    val cropFrame = remember(stageSize, aspect) {
        cropFrameRect(stageSize, aspect)
    }

    fun baseFitSize(bmp: Bitmap, stage: Size): Size {
        if (stage.width <= 0f || stage.height <= 0f) return Size.Zero
        val imgAspect = bmp.width.toFloat() / bmp.height.toFloat()
        val stageAspect = stage.width / stage.height
        return if (imgAspect > stageAspect) {
            val w = stage.width
            Size(w, w / imgAspect)
        } else {
            val h = stage.height
            Size(h * imgAspect, h)
        }
    }

    /** Smallest scale (relative to Fit) where the image fully covers [frame]. */
    fun coverScale(bmp: Bitmap, stage: Size, frame: Rect): Float {
        val base = baseFitSize(bmp, stage)
        if (base.width <= 0f || base.height <= 0f || frame.width <= 0f) return 1f
        return max(frame.width / base.width, frame.height / base.height)
            .coerceAtLeast(0.01f)
    }

    /**
     * Clamp pan so the scaled image always covers the crop frame on every axis.
     * (No letterboxing inside the frame.)
     */
    fun clampOffset(s: Float, o: Offset, stage: Size, frame: Rect, bmp: Bitmap?): Offset {
        if (bmp == null || stage.width <= 0f || frame.width <= 0f) return o
        val base = baseFitSize(bmp, stage)
        if (base.width <= 0f) return o
        val scaledW = base.width * s
        val scaledH = base.height * s
        // If somehow still undersized, pin centered — coverScale should prevent this.
        if (scaledW < frame.width || scaledH < frame.height) {
            return Offset.Zero
        }
        val centerX = stage.width / 2f
        val centerY = stage.height / 2f
        val baseLeft = centerX - scaledW / 2f
        val baseTop = centerY - scaledH / 2f

        // Image edges must stay outside or on the frame edges.
        val minLeft = frame.right - scaledW
        val maxLeft = frame.left
        val minTop = frame.bottom - scaledH
        val maxTop = frame.top

        val minOx = minLeft - baseLeft
        val maxOx = maxLeft - baseLeft
        val minOy = minTop - baseTop
        val maxOy = maxTop - baseTop
        return Offset(
            o.x.coerceIn(min(minOx, maxOx), max(minOx, maxOx)),
            o.y.coerceIn(min(minOy, maxOy), max(minOy, maxOy))
        )
    }

    LaunchedEffect(bitmap, stageSize, aspect) {
        val bmp = bitmap ?: return@LaunchedEffect
        if (stageSize.width <= 0f || cropFrame.width <= 0f) return@LaunchedEffect
        val minS = coverScale(bmp, stageSize, cropFrame)
        // Start at cover so the frame is filled; never go below it.
        if (scale < minS) scale = minS
        scale = scale.coerceIn(minS, MaxZoomIn)
        offset = clampOffset(scale, offset, stageSize, cropFrame, bmp)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(chrome)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCancel) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Cancel",
                    tint = onChrome
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = onChrome,
                modifier = Modifier.weight(1f)
            )
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
                                stage = stageSize,
                                frame = cropFrame,
                                ffmpeg = ffmpeg
                            )
                        }
                        busy = false
                        if (out != null) onCropped(Uri.fromFile(out))
                    }
                },
                enabled = !busy && localUri != null
            ) {
                Text(
                    if (busy) "Working…" else "Done",
                    color = onChrome
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(chrome)
                .onSizeChanged {
                    stageSize = Size(it.width.toFloat(), it.height.toFloat())
                }
                .pointerInput(bitmap, stageSize, cropFrame) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val bmp = bitmap ?: return@detectTransformGestures
                        val minS = coverScale(bmp, stageSize, cropFrame)
                        val newScale = (scale * zoom).coerceIn(minS, MaxZoomIn)
                        scale = newScale
                        offset = clampOffset(newScale, offset + pan, stageSize, cropFrame, bmp)
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                loadError != null -> Text(loadError!!, color = Color(0xFFFF8A80))
                bitmap != null -> {
                    val bmp = bitmap!!
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                translationX = offset.x
                                translationY = offset.y
                            }
                    )
                }
                isAnimated && localUri != null -> Text(
                    "Animated image — center crop on save",
                    color = onChromeMuted
                )
                else -> Text("Loading…", color = onChromeMuted)
            }

            if (cropFrame.width > 0f) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val f = cropFrame
                    drawRect(dimOutside, Offset.Zero, Size(size.width, f.top))
                    drawRect(
                        dimOutside,
                        Offset(0f, f.bottom),
                        Size(size.width, size.height - f.bottom)
                    )
                    drawRect(
                        dimOutside,
                        Offset(0f, f.top),
                        Size(f.left, f.height)
                    )
                    drawRect(
                        dimOutside,
                        Offset(f.right, f.top),
                        Size(size.width - f.right, f.height)
                    )

                    val w = f.width
                    val h = f.height
                    val origin = Offset(f.left, f.top)
                    val stroke = 1.5.dp.toPx()
                    drawLine(
                        gridStroke,
                        origin + Offset(w / 3f, 0f),
                        origin + Offset(w / 3f, h),
                        stroke
                    )
                    drawLine(
                        gridStroke,
                        origin + Offset(2f * w / 3f, 0f),
                        origin + Offset(2f * w / 3f, h),
                        stroke
                    )
                    drawLine(
                        gridStroke,
                        origin + Offset(0f, h / 3f),
                        origin + Offset(w, h / 3f),
                        stroke
                    )
                    drawLine(
                        gridStroke,
                        origin + Offset(0f, 2f * h / 3f),
                        origin + Offset(w, 2f * h / 3f),
                        stroke
                    )
                    val arm = min(w, h) * 0.08f
                    val thick = 3.dp.toPx()
                    val l = f.left
                    val t = f.top
                    val r = f.right
                    val b = f.bottom
                    drawLine(frameStroke, Offset(l, t), Offset(l + arm, t), thick)
                    drawLine(frameStroke, Offset(l, t), Offset(l, t + arm), thick)
                    drawLine(frameStroke, Offset(r, t), Offset(r - arm, t), thick)
                    drawLine(frameStroke, Offset(r, t), Offset(r, t + arm), thick)
                    drawLine(frameStroke, Offset(l, b), Offset(l + arm, b), thick)
                    drawLine(frameStroke, Offset(l, b), Offset(l, b - arm), thick)
                    drawLine(frameStroke, Offset(r, b), Offset(r - arm, b), thick)
                    drawLine(frameStroke, Offset(r, b), Offset(r, b - arm), thick)
                }
            }
        }

        Text(
            buildString {
                append("Pinch to zoom · drag to pan")
                if (BuildConfig.DEBUG) {
                    append(" · frame ")
                    append(if (aspect == 1f) "1:1" else "%.2f".format(aspect))
                    append(" output")
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = onChromeMuted,
            modifier = Modifier.padding(16.dp)
        )
    }
}

/** Centered crop window of [aspect] inside [stage], with padding. */
private fun cropFrameRect(stage: Size, aspect: Float): Rect {
    if (stage.width <= 0f || stage.height <= 0f) return Rect.Zero
    val pad = min(stage.width, stage.height) * 0.06f
    val maxW = stage.width - pad * 2f
    val maxH = stage.height - pad * 2f
    val frameW: Float
    val frameH: Float
    if (maxW / maxH > aspect) {
        frameH = maxH
        frameW = frameH * aspect
    } else {
        frameW = maxW
        frameH = frameW / aspect
    }
    val left = (stage.width - frameW) / 2f
    val top = (stage.height - frameH) / 2f
    return Rect(left, top, left + frameW, top + frameH)
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

private fun cropToFile(
    context: android.content.Context,
    sourceUri: Uri,
    bitmap: Bitmap?,
    isAnimated: Boolean,
    aspect: Float,
    scale: Float,
    offset: Offset,
    stage: Size,
    frame: Rect,
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
            val frameBmp = BitmapFactory.decodeFile(tmp.absolutePath) ?: return null
            return centerCropBitmap(frameBmp, aspect, out)
        }
    }

    val src = bitmap ?: return null
    if (stage.width <= 0f || frame.width <= 0f) {
        return centerCropBitmap(src, aspect, out)
    }

    val imgAspect = src.width.toFloat() / src.height.toFloat()
    val stageAspect = stage.width / stage.height
    val baseW: Float
    val baseH: Float
    if (imgAspect > stageAspect) {
        baseW = stage.width
        baseH = baseW / imgAspect
    } else {
        baseH = stage.height
        baseW = baseH * imgAspect
    }
    val scaledW = baseW * scale
    val scaledH = baseH * scale
    val centerX = stage.width / 2f
    val centerY = stage.height / 2f
    val imgLeft = centerX - scaledW / 2f + offset.x
    val imgTop = centerY - scaledH / 2f + offset.y

    // Frame is always covered → sample the full frame rect in image space.
    val outW = 1024
    val outH = (outW / aspect).toInt().coerceAtLeast(1)
    val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = AndroidCanvas(result)
    canvas.drawColor(android.graphics.Color.BLACK)

    if (scaledW > 0f && scaledH > 0f) {
        val srcLeft = ((frame.left - imgLeft) / scaledW * src.width).toInt()
            .coerceIn(0, src.width - 1)
        val srcTop = ((frame.top - imgTop) / scaledH * src.height).toInt()
            .coerceIn(0, src.height - 1)
        val srcRight = ((frame.right - imgLeft) / scaledW * src.width).toInt()
            .coerceIn(srcLeft + 1, src.width)
        val srcBottom = ((frame.bottom - imgTop) / scaledH * src.height).toInt()
            .coerceIn(srcTop + 1, src.height)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            src,
            AndroidRect(srcLeft, srcTop, srcRight, srcBottom),
            AndroidRect(0, 0, outW, outH),
            paint
        )
    }

    FileOutputStream(out).use { result.compress(Bitmap.CompressFormat.JPEG, 92, it) }
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
    val cropped = Bitmap.createBitmap(
        src, x, y,
        cropW.coerceAtMost(src.width - x),
        cropH.coerceAtMost(src.height - y)
    )
    FileOutputStream(out).use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    return out
}
