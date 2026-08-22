package capital.yuri.yuriplayer.core.log

/**
 * Kotlin DSL over platform logs.
 *
 * Desktop → SLF4J/Logback, Android → logcat, Darwin (later) → os_log.
 *
 * ```
 * val log = yuriLog("Vlcj")
 * log.i { "play ${redactSecrets(mrl)}" }
 * log.e(t) { "stream failed" }
 *
 * yuriLog("Index") {
 *     d { "merge +$n" }
 * }
 * ```
 *
 * Tags are short (`Vlcj`, `Queue`). Platforms prefix `YuriPlayer.`, so logcat is
 * `YuriPlayer.Vlcj`. `YuriPlayer.` on the caller's tag is stripped so
 * `yuriLog("YuriPlayer.Radio")` and `yuriLog("Radio")` log the same.
 */
enum class LogLevel { TRACE, DEBUG, INFO, WARN, ERROR }

class YuriLogger(val tag: String) {
    inline fun t(t: Throwable? = null, msg: () -> String) = emit(LogLevel.TRACE, t, msg)
    inline fun d(t: Throwable? = null, msg: () -> String) = emit(LogLevel.DEBUG, t, msg)
    inline fun i(t: Throwable? = null, msg: () -> String) = emit(LogLevel.INFO, t, msg)
    inline fun w(t: Throwable? = null, msg: () -> String) = emit(LogLevel.WARN, t, msg)
    inline fun e(t: Throwable? = null, msg: () -> String) = emit(LogLevel.ERROR, t, msg)

    inline fun emit(level: LogLevel, t: Throwable? = null, msg: () -> String) {
        if (!platformLogEnabled(level)) return
        platformLog(level, tag, redactSecrets(msg()), t)
    }
}

fun yuriLog(tag: String): YuriLogger = YuriLogger(normalizeLogTag(tag))

inline fun yuriLog(tag: String, block: YuriLogger.() -> Unit) = yuriLog(tag).block()

fun normalizeLogTag(tag: String): String {
    val t = tag.removePrefix("YuriPlayer.")
    return if (t.isBlank() || t == "YuriPlayer") "App" else t
}

expect fun platformLog(level: LogLevel, tag: String, message: String, throwable: Throwable?)

expect fun platformLogEnabled(level: LogLevel): Boolean

fun redactSecrets(text: String): String =
    text
        .replace(Regex("([?&](?:t|s|api_key|ApiKey|access_token|password|pw|secret)=)[^&\\s]+"), "$1***")
        .replace(Regex("(Authorization:\\s*)\\S+", RegexOption.IGNORE_CASE), "$1***")
