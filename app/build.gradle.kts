import java.util.Properties
import java.io.FileInputStream
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.aboutlibraries.android)
}

/**
 * Configuration-cache compatible git invocation.
 * Bare ProcessBuilder at configuration time is forbidden under Gradle CC.
 */
abstract class GitCommandValueSource : ValueSource<String, GitCommandValueSource.Params> {
    interface Params : ValueSourceParameters {
        val args: org.gradle.api.provider.ListProperty<String>
        val workingDir: org.gradle.api.file.DirectoryProperty
    }

    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val out = ByteArrayOutputStream()
        val result = execOperations.exec {
            commandLine(listOf("git") + parameters.args.get())
            workingDir(parameters.workingDir.get().asFile)
            standardOutput = out
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        val text = out.toString(Charsets.UTF_8).trim()
        return if (result.exitValue == 0 && text.isNotBlank()) text else "unknown"
    }
}

fun Project.gitOutput(vararg args: String): Provider<String> =
    providers.of(GitCommandValueSource::class.java) {
        parameters.args.set(args.toList())
        parameters.workingDir.set(rootProject.layout.projectDirectory)
    }

val gitCommit = gitOutput("rev-parse", "HEAD")
val gitCommitShort = gitOutput("rev-parse", "--short", "HEAD")
val gitBranch = gitOutput("rev-parse", "--abbrev-ref", "HEAD")
val gitDescribe = gitOutput("describe", "--tags", "--always", "--dirty")
val gitTagExact = gitOutput("describe", "--tags", "--exact-match")
val gitStatus = gitOutput("status", "--porcelain")

val gitTag: Provider<String> = gitTagExact.map { if (it == "unknown") "" else it }
val gitDirty: Provider<Boolean> = gitStatus.map { it != "unknown" && it.isNotBlank() }

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

        // .get() is fine: ValueSource is a tracked CC input
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit.get()}\"")
        buildConfigField("String", "GIT_COMMIT_SHORT", "\"${gitCommitShort.get()}\"")
        buildConfigField("String", "GIT_BRANCH", "\"${gitBranch.get()}\"")
        buildConfigField("String", "GIT_DESCRIBE", "\"${gitDescribe.get()}\"")
        buildConfigField("String", "GIT_TAG", "\"${gitTag.get()}\"")
        buildConfigField("boolean", "GIT_DIRTY", "${gitDirty.get()}")
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

    testOptions {
        // Keep the user's library / logins. Do not enable orchestrator
        // clearPackageData — these tests run against familiar on-device data.
        animationsDisabled = true
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            // LibVLC ships its own .so set; avoid merge conflicts with other natives
            pickFirsts += listOf(
                "lib/**/libc++_shared.so",
                "lib/**/libvlc.so",
                "lib/**/libvlcjni.so"
            )
        }
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

room {
    schemaDirectory(layout.projectDirectory.dir("schemas").asFile.absolutePath)
}

aboutLibraries {
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
    implementation(project(":core"))
    implementation(project(":components"))
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
    // ProcessLifecycleOwner for secret playlist cover reset on background / lock
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.material)
    implementation(libs.androidx.palette)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.common)
    // MediaStyle + MediaSessionCompat.Token for engine-agnostic notifications
    implementation(libs.androidx.media)

    // LibVLC — local FLAC/APE and other formats Media3 often rejects
    implementation(libs.libvlc.all)

    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.sqlite.bundled)
    ksp(libs.androidx.room.compiler)

    implementation(libs.jaudiotagger)

    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.kotlinx.json)

    implementation(libs.jellyfin.core)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor3)
    implementation(libs.coil.gif)

    implementation(libs.aboutlibraries.core)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
