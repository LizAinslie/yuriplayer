package capital.yuri.yuriplayer.components.menu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MenuDslTest {
    @Test
    fun itemAlternateIsNestedNotPrimary() {
        val menu = buildContextMenu {
            item("Add to playlist", alternate = {
                item("Late night") {}
                item("Gym") {}
            }) {}
        }
        val item = menu.single() as MenuEntry.Item
        assertEquals("Add to playlist", item.label)
        assertEquals(listOf("Late night", "Gym"), item.alternate.map { (it as MenuEntry.Item).label })
        assertTrue(item.alternate.none { it is MenuEntry.Submenu })
    }
}
