import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.aboutlibraries.android)
}

fun gitCommand(vararg args: String): String {
    return try {
        val proc = ProcessBuilder("git", *args)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor()
        if (proc.exitValue() == 0 && out.isNotBlank()) out else "unknown"
    } catch (_: Exception) {
        "unknown"
    }
}

val gitCommit: String = gitCommand("rev-parse", "HEAD")
val gitCommitShort: String = gitCommand("rev-parse", "--short", "HEAD")
val gitBranch: String = gitCommand("rev-parse", "--abbrev-ref", "HEAD")
val gitDescribe: String = gitCommand("describe", "--tags", "--always", "--dirty")
val gitTag: String = run {
    val exact = gitCommand("describe", "--tags", "--exact-match")
    if (exact != "unknown") exact else ""
}
val gitDirty: Boolean = gitCommand("status", "--porcelain").let {
    it != "unknown" && it.isNotBlank()
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

        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "GIT_COMMIT_SHORT", "\"$gitCommitShort\"")
        buildConfigField("String", "GIT_BRANCH", "\"$gitBranch\"")
        buildConfigField("String", "GIT_DESCRIBE", "\"$gitDescribe\"")
        buildConfigField("String", "GIT_TAG", "\"$gitTag\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
        buildConfigField("String", "REPO_URL", "\"https://github.com/LizAinslie/yuriplayer\"")
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
        buildConfig = true
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

aboutLibraries {
    // Merge duplicates; keep SPDX ids for our Settings rows
    library {
        duplicationMode.set(com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE)
        duplicationRule.set(com.mikepenz.aboutlibraries.plugin.DuplicateRule.SIMPLE)
    }
}

val ffmpegAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val ffmpegRoot = rootProject.file("native/ffmpeg")
val ffmpegAssetsDir = file("src/main/assets/ffmpeg")
val ffmpegConstraints = ffmpegRoot.resolve("constraints.env")

fun newestNdkUnder(sdkRoot: File): File? {
    val ndkRoot = File(sdkRoot, "ndk")
    if (!ndkRoot.isDirectory) return null
    return ndkRoot.listFiles()
        ?.filter { it.isDirectory && File(it, "toolchains/llvm/prebuilt").isDirectory }
        ?.maxByOrNull { it.name }
}

fun resolveNdkHome(): File? {
    listOf("ANDROID_NDK_HOME", "ANDROID_NDK")
        .mapNotNull { System.getenv(it)?.takeIf { p -> p.isNotBlank() } }
        .map { File(it) }
        .firstOrNull { it.isDirectory }
        ?.let { return it }

    System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { home ->
        newestNdkUnder(File(home))?.let { return it }
    }

    val localProps = rootProject.file("local.properties")
    if (localProps.isFile) {
        val props = Properties()
        localProps.inputStream().use { props.load(it) }
        val sdkDir = props.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }
        if (sdkDir != null) {
            newestNdkUnder(File(sdkDir))?.let { return it }
        }
    }
    return null
}

tasks.register<Exec>("buildFfmpeg") {
    group = "native"
    description = "Cross-compile slim FFmpeg for Android ABIs via NDK (Gradle-cached)"

    inputs.file(ffmpegRoot.resolve("build.sh"))
    inputs.file(ffmpegConstraints)
    ffmpegAbis.forEach { abi ->
        outputs.file(ffmpegAssetsDir.resolve("$abi/ffmpeg"))
    }

    workingDir = ffmpegRoot
    commandLine("bash", "build.sh")

    val ndk = resolveNdkHome()
    environment("FFMPEG_ASSETS_DIR", ffmpegAssetsDir.absolutePath)
    environment("FFMPEG_ABIS", ffmpegAbis.joinToString(","))
    if (ndk != null) {
        environment("ANDROID_NDK_HOME", ndk.absolutePath)
        environment("ANDROID_NDK", ndk.absolutePath)
        logger.lifecycle("buildFfmpeg: ANDROID_NDK_HOME=${ndk.absolutePath}")
    } else {
        logger.warn(
            "buildFfmpeg: no NDK found (env + local.properties sdk.dir/ndk). " +
                "Install an NDK under the Android SDK or set ANDROID_NDK_HOME."
        )
    }
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
    implementation(libs.androidx.documentfile)
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

    implementation(libs.jellyfin.core)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.gif)

    // License metadata only — UI stays our Settings rows
    implementation(libs.aboutlibraries.core)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
