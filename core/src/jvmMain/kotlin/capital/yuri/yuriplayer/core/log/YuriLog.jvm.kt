package capital.yuri.yuriplayer.core.log

import org.slf4j.LoggerFactory

actual fun platformLog(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
    val log = LoggerFactory.getLogger("YuriPlayer.$tag")
    when (level) {
        LogLevel.TRACE -> if (throwable != null) log.trace(message, throwable) else log.trace(message)
        LogLevel.DEBUG -> if (throwable != null) log.debug(message, throwable) else log.debug(message)
        LogLevel.INFO -> if (throwable != null) log.info(message, throwable) else log.info(message)
        LogLevel.WARN -> if (throwable != null) log.warn(message, throwable) else log.warn(message)
        LogLevel.ERROR -> if (throwable != null) log.error(message, throwable) else log.error(message)
    }
}

actual fun platformLogEnabled(level: LogLevel): Boolean = true
