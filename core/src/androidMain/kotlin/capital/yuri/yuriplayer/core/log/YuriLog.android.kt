package capital.yuri.yuriplayer.core.log

import android.util.Log

actual fun platformLog(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
    val name = "YuriPlayer.$tag"
    when (level) {
        LogLevel.TRACE -> if (throwable != null) Log.v(name, message, throwable) else Log.v(name, message)
        LogLevel.DEBUG -> if (throwable != null) Log.d(name, message, throwable) else Log.d(name, message)
        LogLevel.INFO -> if (throwable != null) Log.i(name, message, throwable) else Log.i(name, message)
        LogLevel.WARN -> if (throwable != null) Log.w(name, message, throwable) else Log.w(name, message)
        LogLevel.ERROR -> if (throwable != null) Log.e(name, message, throwable) else Log.e(name, message)
    }
}

actual fun platformLogEnabled(level: LogLevel): Boolean = true
