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

# Models + player state (JSON / service)
-keep class capital.yuri.yuriplayer.data.** { *; }
-keep class capital.yuri.yuriplayer.player.** { *; }

# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class capital.yuri.yuriplayer.**$$serializer { *; }
-keepclassmembers class capital.yuri.yuriplayer.** {
    *** Companion;
}
-keepclasseswithmembers class capital.yuri.yuriplayer.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# jaudiotagger (reflection + format handlers)
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# Compose
-dontwarn androidx.compose.**
