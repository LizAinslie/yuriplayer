package capital.yuri.yuriplayer.components.menu

/**
 * Hierarchical context menu. Implementors declare options; the host renders
 * them with prediction-cone hover for submenus.
 *
 * ```
 * buildContextMenu {
 *   item("Add to playlist", alternate = {
 *     item("Chill") { addTo("chill") }
 *   }) { openPicker() }
 *   submenu("Go to") {
 *     item("Album") { openAlbum() }
 *     item("Artist") { openArtist() }
 *   }
 *   divider()
 *   item("Delete", destructive = true) { remove() }
 * }
 * ```
 */
sealed class MenuEntry {
    data class Item(
        val label: String,
        val shortcut: String? = null,
        val destructive: Boolean = false,
        val enabled: Boolean = true,
        val alternate: List<MenuEntry> = emptyList(),
        val onClick: () -> Unit
    ) : MenuEntry()

    data class Submenu(
        val label: String,
        val children: List<MenuEntry>
    ) : MenuEntry()

    data object Divider : MenuEntry()
}

@DslMarker
annotation class ContextMenuDsl

@ContextMenuDsl
class ContextMenuScope {
    private val entries = mutableListOf<MenuEntry>()

    fun item(
        label: String,
        shortcut: String? = null,
        destructive: Boolean = false,
        enabled: Boolean = true,
        alternate: (ContextMenuScope.() -> Unit)? = null,
        onClick: () -> Unit
    ) {
        val alt = alternate?.let { ContextMenuScope().apply(it).build() }.orEmpty()
        entries += MenuEntry.Item(label, shortcut, destructive, enabled, alt, onClick)
    }

    fun submenu(label: String, block: ContextMenuScope.() -> Unit) {
        entries += MenuEntry.Submenu(label, ContextMenuScope().apply(block).build())
    }

    fun divider() {
        entries += MenuEntry.Divider
    }

    internal fun build(): List<MenuEntry> = entries.toList()
}

fun buildContextMenu(block: ContextMenuScope.() -> Unit): List<MenuEntry> =
    ContextMenuScope().apply(block).build()

typealias ContextAction = MenuEntry.Item
