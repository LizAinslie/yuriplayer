package capital.yuri.yuriplayer.core.artist

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ArtistProfileStore(configDir: String, cacheDir: String) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(configDir, "artists").also { it.mkdirs() }
    private val bannerDir = File(cacheDir, "artist-banners").also { it.mkdirs() }

    fun load(name: String): ArtistProfile? {
        val key = artistKey(name) ?: return null
        val file = File(dir, "$key.json")
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<ArtistProfile>(file.readText()) }.getOrNull()
    }

    fun save(profile: ArtistProfile) {
        runCatching {
            File(dir, "${profile.artistKey}.json").writeText(json.encodeToString(profile))
        }
    }

    fun bannerFile(name: String): File? {
        val prefix = filePrefix(name) ?: return null
        return bannerDir.listFiles()?.filter { it.name.startsWith(prefix) }?.maxByOrNull { it.lastModified() }
    }

    fun setLocalBanner(name: String, file: File): String? {
        val prefix = filePrefix(name) ?: return null
        return runCatching {
            clearBannerFiles(prefix)
            val dest = File(bannerDir, "$prefix-${System.currentTimeMillis()}.jpg")
            file.copyTo(dest, overwrite = true)
            dest.toURI().toString()
        }.getOrNull()
    }

    fun writeBannerBytes(name: String, bytes: ByteArray): String? {
        val prefix = filePrefix(name) ?: return null
        return runCatching {
            clearBannerFiles(prefix)
            val dest = File(bannerDir, "$prefix-${System.currentTimeMillis()}.jpg")
            dest.writeBytes(bytes)
            dest.toURI().toString()
        }.getOrNull()
    }

    fun clearBanner(name: String) {
        filePrefix(name)?.let { clearBannerFiles(it) }
    }

    private fun filePrefix(name: String): String? =
        artistKey(name)?.replace(Regex("[^a-z0-9]+"), "-")?.trim('-')?.takeIf { it.isNotEmpty() }

    private fun clearBannerFiles(prefix: String) {
        bannerDir.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
    }
}
