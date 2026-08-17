import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "capital.yuri.yuriplayer"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "capital.yuri.yuriplayer"
        minSdk = 27
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0-local"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties()
    if (keystorePropsFile.exists()) {
        keystoreProps.load(FileInputStream(keystorePropsFile))
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("debug") {
            java.srcDir("build/generated/ksp/debug/java")
            java.srcDir("build/generated/ksp/debug/kotlin")
        }
        getByName("release") {
            java.srcDir("build/generated/ksp/release/java")
            java.srcDir("build/generated/ksp/release/kotlin")
        }
    }
}

// ---------------------------------------------------------------------------
// NDK FFmpeg: build once, cache on script + constraints hash.
// Outputs land in app/src/main/assets/ffmpeg/<abi>/ffmpeg
// First run is long; later assemble* is a no-op unless native/ffmpeg changes.
// ---------------------------------------------------------------------------
val ffmpegAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val ffmpegRoot = rootProject.file("native/ffmpeg")
val ffmpegAssetsDir = file("src/main/assets/ffmpeg")
val ffmpegConstraints = ffmpegRoot.resolve("constraints.env")

val buildFfmpeg by tasks.registering(Exec::class) {
    group = "native"
    description = "Cross-compile FFmpeg for Android ABIs via NDK (cached)"

    inputs.file(ffmpegRoot.resolve("build.sh"))
    inputs.file(ffmpegConstraints)
    inputs.dir(ffmpegRoot.resolve("patches")).optional = true
    ffmpegAbis.forEach { abi ->
        outputs.file(ffmpegAssetsDir.resolve("$abi/ffmpeg"))
    }

    workingDir = ffmpegRoot
    commandLine("bash", "build.sh")
    environment("FFMPEG_ASSETS_DIR", ffmpegAssetsDir.absolutePath)
    environment("FFMPEG_ABIS", ffmpegAbis.joinToString(","))

    onlyIf {
        val script = ffmpegRoot.resolve("build.sh")
        script.isFile && ffmpegConstraints.isFile
    }

    doFirst {
        if (System.getenv("ANDROID_NDK_HOME").isNullOrBlank() &&
            System.getenv("ANDROID_NDK").isNullOrBlank()
        ) {
            logger.warn(
                "ANDROID_NDK_HOME not set — skipping FFmpeg native build. " +
                    "GIF crop will fall back to still-image path until you build."
            )
            // soft skip: don't fail the whole assemble
        }
    }
}

// Optional: wire into preBuild when binaries missing (does not re-run if outputs present)
tasks.named("preBuild").configure {
    dependsOn(buildFfmpeg)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)
    implementation(libs.androidx.palette)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.jaudiotagger)

    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
