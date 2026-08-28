plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Vendored fork of dev.toastbits:mediasession (0.1.1). Kept as a local
// subproject so we can fix upstream bugs (Float/Double volume marshalling,
// microsecond seek offsets, missing onSetVolume callback) without waiting
// on upstream releases.
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(libs.dbus.java.core)
            implementation(libs.dbus.java.transport.native.unix)
            implementation(libs.junixsocket.core)
            implementation(libs.jna)
            implementation(libs.jna.platform)
        }
    }
}
