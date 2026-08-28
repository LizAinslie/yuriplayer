package capital.yuri.yuriplayer.components.platform

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Size of the primary display / viewport in pixels.
 */
expect fun screenSizePx(): IntSize

/**
 * Top-left of the current (focused or active) window on screen, in pixels.
 *
 * Android has a single full-screen window, so this is always [IntOffset.Zero].
 * Desktop reads the AWT window position (the popup window while a menu is open).
 */
expect fun windowPositionOnScreenPx(): IntOffset
