import java.io.File
import java.net.URI
import java.util.zip.ZipInputStream
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

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
    implementation(libs.jaudiotagger)
}

val libvlcRoot = layout.buildDirectory.dir("libvlc")
val libvlcResources = layout.buildDirectory.dir("libvlc-resources")
val downloadAllLibVlc = (findProperty("yuri.libvlc.all") as String?) == "true"
val hostLibVlc = hostLibVlcPlatform()
val bundleLibVlcOnHost = !hostLibVlc.startsWith("linux")

/** Official VideoLAN natives for Windows/macOS installers. Linux uses distro VLC. */
val downloadLibVlc by tasks.registering {
    group = "vlc"
    description = "Download bundled LibVLC for Windows/macOS (Linux packages depend on system vlc)."
    val destRoot = libvlcRoot
    val all = downloadAllLibVlc
    val host = hostLibVlc
    outputs.dir(destRoot)
    notCompatibleWithConfigurationCache("LibVLC download uses script helpers")
    doLast {
        val wanted = if (all) {
            listOf("windows-x64", "macos-x64", "macos-arm64")
        } else if (host.startsWith("linux")) {
            emptyList()
        } else {
            listOf(host)
        }
        if (wanted.isEmpty()) {
            println("Linux: not bundling LibVLC (installer Depends/Requires vlc)")
            destRoot.get().asFile.mkdirs()
            return@doLast
        }
        wanted.forEach { platform ->
            val dest = destRoot.get().asFile.resolve(platform)
            if (hasLibvlc(dest)) {
                println("LibVLC $platform already present")
                return@forEach
            }
            dest.mkdirs()
            println("Downloading LibVLC for $platform…")
            when {
                platform.startsWith("windows") -> unpackNuget(
                    "https://api.nuget.org/v3-flatcontainer/videolan.libvlc.windows/3.0.23.1/videolan.libvlc.windows.3.0.23.1.nupkg",
                    dest,
                    "libvlc.dll"
                )
                platform.startsWith("macos") -> unpackNuget(
                    "https://api.nuget.org/v3-flatcontainer/videolan.libvlc.mac/3.1.3.1/videolan.libvlc.mac.3.1.3.1.nupkg",
                    dest,
                    "libvlc.dylib"
                )
                else -> println("Skip $platform")
            }
        }
    }
}

val copyLibVlcResources by tasks.registering {
    dependsOn(downloadLibVlc)
    outputs.dir(libvlcResources)
    notCompatibleWithConfigurationCache("LibVLC copy")
    doLast {
        val root = libvlcRoot.get().asFile
        val out = libvlcResources.get().asFile
        out.mkdirs()
        root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith("linux") }
            ?.forEach { src ->
                src.copyRecursively(out.resolve(src.name), overwrite = true)
            }
    }
}

compose.desktop {
    application {
        mainClass = "capital.yuri.yuriplayer.desktop.MainKt"
        nativeDistributions {
            targetFormats(
                TargetFormat.Deb,
                TargetFormat.Rpm,
                TargetFormat.Dmg,
                TargetFormat.Pkg,
                TargetFormat.Msi,
                TargetFormat.Exe
            )
            packageName = "Yuri Player"
            packageVersion = "0.1.0"
            description = "Yuri Player"
            copyright = "AGPL-3.0"
            vendor = "Yuri"
            appResourcesRootDir.set(libvlcResources)
            linux {
                packageName = "yuri-player"
                debMaintainer = "yuri@yuri.capital"
                menuGroup = "AudioVideo"
                appCategory = "AudioVideo"
                appRelease = "1"
                rpmLicenseType = "AGPLv3"
            }
            windows {
                menuGroup = "Yuri"
                dirChooser = true
            }
            macOS {
                bundleID = "capital.yuri.yuriplayer"
                dockName = "Yuri Player"
                packageVersion = "1.0.0"
            }
        }
    }
}

afterEvaluate {
    tasks.matching { it.name.contains("prepareAppResources", ignoreCase = true) }.configureEach {
        dependsOn(copyLibVlcResources)
    }
    tasks.matching { it.name == "run" }.configureEach {
        if (bundleLibVlcOnHost) {
            dependsOn(downloadLibVlc)
            if (this is JavaExec) {
                systemProperty("yuri.libvlc.dir", libvlcRoot.get().asFile.resolve(hostLibVlc).absolutePath)
            }
        }
    }
    tasks.withType<AbstractJPackageTask>().configureEach {
        if (targetFormat == TargetFormat.Deb || targetFormat == TargetFormat.Rpm) {
            freeArgs.addAll(listOf("--linux-package-deps", "vlc"))
        }
        if (targetFormat == TargetFormat.Msi ||
            targetFormat == TargetFormat.Exe ||
            targetFormat == TargetFormat.Dmg ||
            targetFormat == TargetFormat.Pkg
        ) {
            dependsOn(copyLibVlcResources)
        }
    }
}

fun hostLibVlcPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val family = when {
        os.contains("win") -> "windows"
        os.contains("mac") || os.contains("darwin") -> "macos"
        else -> "linux"
    }
    val cpu = when {
        arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
        else -> "x64"
    }
    return "$family-$cpu"
}

fun hasLibvlc(dir: File): Boolean {
    if (!dir.isDirectory) return false
    return dir.walkTopDown().any { f ->
        val n = f.name
        n.startsWith("libvlc") && (
            n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib") || n.contains(".so.")
        )
    }
}

fun unpackNuget(url: String, dest: File, marker: String) {
    val tmp = dest.resolveSibling("${dest.name}.nupkg")
    download(url, tmp)
    val unzip = dest.resolveSibling("${dest.name}-unpack")
    unzip.deleteRecursively()
    unzip.mkdirs()
    unzipFile(tmp, unzip)
    val found = unzip.walkTopDown().firstOrNull { it.name.equals(marker, ignoreCase = true) }
        ?: error("NuGet package did not contain $marker")
    found.parentFile.copyRecursively(dest, overwrite = true)
    tmp.delete()
    unzip.deleteRecursively()
}

fun unzipFile(zip: File, dest: File) {
    ZipInputStream(zip.inputStream().buffered()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
            val out = dest.resolve(entry.name)
            if (entry.isDirectory) {
                out.mkdirs()
            } else {
                out.parentFile.mkdirs()
                out.outputStream().use { zis.copyTo(it) }
            }
            entry = zis.nextEntry
        }
    }
}

fun download(url: String, dest: File) {
    dest.parentFile.mkdirs()
    dest.outputStream().use { out ->
        URI(url).toURL().openStream().use { it.copyTo(out) }
    }
}
