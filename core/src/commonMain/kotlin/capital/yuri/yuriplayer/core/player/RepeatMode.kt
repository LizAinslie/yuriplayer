package capital.yuri.yuriplayer.core.player

enum class RepeatMode { OFF, ALL, ONE }

fun RepeatMode.next(): RepeatMode = when (this) {
    RepeatMode.OFF -> RepeatMode.ALL
    RepeatMode.ALL -> RepeatMode.ONE
    RepeatMode.ONE -> RepeatMode.OFF
}
