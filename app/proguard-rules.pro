# YuriPlayer — release ProGuard / R8 rules
# Keep line numbers in stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# Koin
-keep class org.koin.** { *; }
-keepclassmembers class * {
    @org.koin.core.annotation.* <methods>;
}
-dontwarn org.koin.**

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Our models used via JSON / reflection-ish persistence
-keep class capital.yuri.yuriplayer.data.** { *; }
-keep class capital.yuri.yuriplayer.player.** { *; }

# Compose (usually fine with defaults; keep if you hit odd crashes)
-dontwarn androidx.compose.**
