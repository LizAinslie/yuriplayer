package capital.yuri.yuriplayer.core.player

import kotlinx.serialization.Serializable

/**
 * Repeat semantics. [COLD] loops the cold queue (album / playlist context)
 * after it ends — the Android `QueueManager` name for what desktop used to
 * call `ALL`.
 */
@Serializable
enum class RepeatMode { OFF, ONE, COLD }

fun RepeatMode.next(): RepeatMode = when (this) {
    RepeatMode.OFF -> RepeatMode.COLD
    RepeatMode.COLD -> RepeatMode.ONE
    RepeatMode.ONE -> RepeatMode.OFF
}
