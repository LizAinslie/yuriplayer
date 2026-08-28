package capital.yuri.yuriplayer.components.platform

import android.content.res.Resources
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

actual fun screenSizePx(): IntSize {
    val metrics = Resources.getSystem().displayMetrics
    return IntSize(metrics.widthPixels, metrics.heightPixels)
}

actual fun windowPositionOnScreenPx(): IntOffset = IntOffset.Zero
