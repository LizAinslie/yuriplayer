import java.io.File
import java.net.URI
import java.nio.file.Files
import java.util.zip.ZipInputStream

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

val libvlcRoot = layout.buildDirectory.dir("libvlc")
val libvlcResources = layout.buildDirectory.dir("libvlc-resources")
val downloadAllLibVlc = (findProperty("yuri.libvlc.all") as String?) == "true"
val hostLibVlc = hostLibVlcPlatform()

val downloadLibVlc by tasks.registering {
    group = "vlc"
    description = "Download official LibVLC natives for the host OS (set yuri.libvlc.all=true for every OS)."
    val destRoot = libvlcRoot
    val all = downloadAllLibVlc
    val host = hostLibVlc
    outputs.dir(destRoot)
    notCompatibleWithConfigurationCache("LibVLC download uses script helpers")
    doLast {
        val wanted = if (all) {
            listOf("windows-x64", "macos-x64", "linux-x64")
        } else {
            listOf(host)
        }
        wanted.forEach { platform ->
            val dest = destRoot.get().asFile.resolve(platform)
            if (hasLibvlc(dest)) {
                println("LibVLC $platform already present")
                return@forEach
            }
            dest.mkdirs()
            println("Downloading LibVLC for $platform…")
            when (platform) {
                "windows-x64" -> unpackNuget(
                    "https://api.nuget.org/v3-flatcontainer/videolan.libvlc.windows/3.0.23.1/videolan.libvlc.windows.3.0.23.1.nupkg",
                    dest,
                    "libvlc.dll"
                )
                "macos-x64" -> unpackNuget(
                    "https://api.nuget.org/v3-flatcontainer/videolan.libvlc.mac/3.1.3.1/videolan.libvlc.mac.3.1.3.1.nupkg",
                    dest,
                    "libvlc.dylib"
                )
                "linux-x64" -> unpackDebianLibvlc(dest)
                else -> println("No bundled LibVLC recipe for $platform — system VLC will be used")
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
        root.listFiles()?.filter { it.isDirectory }?.forEach { src ->
            src.copyRecursively(out.resolve(src.name), overwrite = true)
        }
    }
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
            appResourcesRootDir.set(libvlcResources)
        }
    }
}

afterEvaluate {
    tasks.matching { it.name.contains("prepareAppResources", ignoreCase = true) }.configureEach {
        dependsOn(copyLibVlcResources)
    }
    tasks.matching { it.name == "run" }.configureEach {
        dependsOn(downloadLibVlc)
        if (this is JavaExec) {
            val dir = libvlcRoot.get().asFile.resolve(hostLibVlc)
            systemProperty("yuri.libvlc.dir", dir.absolutePath)
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
        n.startsWith("libvlc") && (n.endsWith(".dll") || n.endsWith(".so") || n.endsWith(".dylib") || n.contains(".so."))
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

fun unpackDebianLibvlc(dest: File) {
    val base = "https://deb.debian.org/debian/pool/main/v/vlc"
    val debs = listOf(
        "libvlc5_3.0.23-3+b4_amd64.deb",
        "libvlccore9_3.0.23-3+b4_amd64.deb",
        "vlc-plugin-base_3.0.23-3+b4_amd64.deb",
        "vlc-data_3.0.23-3_all.deb"
    )
    val scratch = dest.resolveSibling("${dest.name}-debs")
    scratch.deleteRecursively()
    scratch.mkdirs()
    debs.forEach { name ->
        val file = scratch.resolve(name)
        download("$base/$name", file)
        extractDeb(file, scratch.resolve("tree"))
    }
    val tree = scratch.resolve("tree")
    dest.mkdirs()
    val libDir = tree.resolve("usr/lib/x86_64-linux-gnu")
    if (libDir.isDirectory) {
        libDir.listFiles()?.filter { it.isFile && it.name.startsWith("libvlc") }?.forEach {
            it.copyTo(dest.resolve(it.name), overwrite = true)
        }
        val so = dest.resolve("libvlc.so.5")
        if (so.exists() && !dest.resolve("libvlc.so").exists()) {
            dest.resolve("libvlc.so").let { link ->
                runCatching { Files.createSymbolicLink(link.toPath(), so.toPath().fileName) }
                    .onFailure { so.copyTo(dest.resolve("libvlc.so"), overwrite = true) }
            }
        }
        val core = dest.resolve("libvlccore.so.9")
        if (core.exists() && !dest.resolve("libvlccore.so").exists()) {
            runCatching {
                Files.createSymbolicLink(
                    dest.resolve("libvlccore.so").toPath(),
                    core.toPath().fileName
                )
            }.onFailure { core.copyTo(dest.resolve("libvlccore.so"), overwrite = true) }
        }
        val plugins = libDir.resolve("vlc/plugins")
        if (plugins.isDirectory) {
            plugins.copyRecursively(dest.resolve("plugins"), overwrite = true)
        }
    }
    scratch.deleteRecursively()
    check(hasLibvlc(dest)) { "Debian LibVLC extract produced no libvlc" }
}

fun extractDeb(deb: File, into: File) {
    into.mkdirs()
    val ar = ProcessBuilder("ar", "t", deb.absolutePath).start()
    val entries = ar.inputStream.bufferedReader().readLines()
    check(ar.waitFor() == 0) { "ar failed on ${deb.name}" }
    val data = entries.firstOrNull { it.startsWith("data.tar") } ?: error("no data.tar in ${deb.name}")
    val flag = when {
        data.endsWith(".xz") -> "-xJ"
        data.endsWith(".gz") -> "-xz"
        data.endsWith(".zst") -> "-x --zstd"
        data.endsWith(".bz2") -> "-xj"
        else -> "-x"
    }
    val extract = ProcessBuilder(
        "bash",
        "-lc",
        "ar p ${deb.absolutePath.shell()} $data | tar $flag -C ${into.absolutePath.shell()}"
    )
        .redirectErrorStream(true)
        .start()
    val out = extract.inputStream.bufferedReader().readText()
    check(extract.waitFor() == 0) { "extract ${deb.name} failed: $out" }
}

fun download(url: String, dest: File) {
    dest.parentFile.mkdirs()
    dest.outputStream().use { out ->
        URI(url).toURL().openStream().use { it.copyTo(out) }
    }
}

fun String.shell(): String = "'" + replace("'", "'\\''") + "'"
