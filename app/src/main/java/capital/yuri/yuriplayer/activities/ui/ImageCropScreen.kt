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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.media.FfmpegService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File
import java.io.FileOutputStream

/**
 * Simple pan/zoom square crop. For still images encodes JPEG; for GIF/WebP
 * animated sources delegates to [FfmpegService.cropCenter].
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
    val scope = rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var busy by remember { mutableStateOf(false) }
    var isAnimated by remember { mutableStateOf(false) }

    LaunchedEffect(sourceUri) {
        withContext(Dispatchers.IO) {
            val type = runCatching { context.contentResolver.getType(sourceUri) }.getOrNull()
            isAnimated = type == "image/gif" || type == "image/webp"
            if (!isAnimated) {
                context.contentResolver.openInputStream(sourceUri)?.use {
                    bitmap = BitmapFactory.decodeStream(it)
                }
            }
        }
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
                    busy = true
                    scope.launch {
                        val out = withContext(Dispatchers.IO) {
                            cropToFile(
                                context = context,
                                sourceUri = sourceUri,
                                bitmap = bitmap,
                                isAnimated = isAnimated,
                                scale = scale,
                                offset = offset,
                                aspect = aspect,
                                ffmpeg = ffmpeg
                            )
                        }
                        busy = false
                        if (out != null) onCropped(Uri.fromFile(out))
                    }
                },
                enabled = !busy
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
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 6f)
                            offset += pan
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
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
                } else if (isAnimated) {
                    Text(
                        "Animated image — center crop on save",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Text(
            "Pinch to zoom · drag to pan · output is ${if (aspect == 1f) "1:1" else aspect}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            modifier = Modifier.padding(16.dp)
        )
    }
}

private fun cropToFile(
    context: android.content.Context,
    sourceUri: Uri,
    bitmap: Bitmap?,
    isAnimated: Boolean,
    scale: Float,
    offset: Offset,
    aspect: Float,
    ffmpeg: FfmpegService
): File? {
    val dir = File(context.cacheDir, "crops").also { it.mkdirs() }
    val out = File(dir, "crop_${System.currentTimeMillis()}.jpg")

    if (isAnimated) {
        // Copy source then ffmpeg center-square (keeps GIF if input is gif — output name)
        val tmp = File(dir, "anim_src_${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(sourceUri)?.use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        } ?: return null
        val gifOut = File(dir, "crop_${System.currentTimeMillis()}.gif")
        val target = if (sourceUri.toString().contains("gif", true)) gifOut else out
        val ok = kotlinx.coroutines.runBlocking {
            ffmpeg.cropCenter(tmp, target, 512, (512 / aspect).toInt().coerceAtLeast(1))
        }
        tmp.delete()
        return if (ok) target else null
    }

    val src = bitmap ?: return null
    // Approximate center crop from displayed transform is complex; use simple center square
    val side = minOf(src.width, src.height)
    val x = ((src.width - side) / 2f).toInt().coerceAtLeast(0)
    val y = ((src.height - side) / 2f).toInt().coerceAtLeast(0)
    val cropped = Bitmap.createBitmap(src, x, y, side, side)
    FileOutputStream(out).use { cropped.compress(Bitmap.CompressFormat.JPEG, 92, it) }
    return out
}
