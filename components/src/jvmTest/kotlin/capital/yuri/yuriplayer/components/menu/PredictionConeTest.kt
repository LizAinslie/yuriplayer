package capital.yuri.yuriplayer.components.menu

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PredictionConeTest {
    private val submenu = Rect(200f, 40f, 360f, 200f)
    private val origin = Offset(80f, 80f)

    @Test
    fun pointInsideTriangleCounts() {
        assertTrue(pointInTriangle(Offset(10f, 10f), Offset(0f, 0f), Offset(20f, 0f), Offset(0f, 20f)))
        assertFalse(pointInTriangle(Offset(20f, 20f), Offset(0f, 0f), Offset(20f, 0f), Offset(0f, 20f)))
    }

    @Test
    fun diagonalTowardSubmenuIsSafe() {
        assertTrue(pointerInPredictionCone(Offset(180f, 100f), origin, submenu))
        assertTrue(pointerInPredictionCone(Offset(150f, 90f), origin, submenu))
    }

    @Test
    fun siblingItemBelowIsNotSafe() {
        assertFalse(pointerInPredictionCone(Offset(80f, 280f), origin, submenu))
        assertFalse(pointerInPredictionCone(Offset(40f, 300f), origin, submenu))
    }
}
