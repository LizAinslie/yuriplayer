plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    android {
        namespace = "capital.yuri.yuriplayer.components"
        compileSdk = 37
        minSdk = 27
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(libs.jetbrains.compose.runtime)
            implementation(libs.jetbrains.compose.foundation)
            implementation(libs.jetbrains.compose.ui)
            implementation(libs.jetbrains.compose.material3)
            implementation(libs.jetbrains.compose.material.icons.extended)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
