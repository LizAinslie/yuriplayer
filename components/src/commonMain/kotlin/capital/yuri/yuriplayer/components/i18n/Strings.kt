package capital.yuri.yuriplayer.components.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Localizable UI strings. One [Strings] implementation per locale; add more
 * and register them in [resolveStrings]. Lyricist KSP codegen can replace this
 * manual `LocalStrings` later, but the contract stays identical.
 */
interface Strings {
    val noInternet: String
}

class EnStrings : Strings {
    override val noInternet: String = "No internet"
}

private fun resolveStrings(languageTag: String): Strings = when (languageTag.lowercase().substringBefore('-')) {
    else -> EnStrings()
}

val LocalStrings = staticCompositionLocalOf<Strings> { EnStrings() }
