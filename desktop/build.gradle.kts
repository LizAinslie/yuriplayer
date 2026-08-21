plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":components"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.vlcj)
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.transport.native.unix)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    implementation(libs.ktor.client.cio)
}

compose.desktop {
    application {
        mainClass = "capital.yuri.yuriplayer.desktop.MainKt"
        nativeDistributions {
            packageName = "Yuri Player"
            packageVersion = "0.1.0"
            description = "Yuri Player"
            copyright = "AGPL-3.0"
            vendor = "Yuri"
        }
    }
}
