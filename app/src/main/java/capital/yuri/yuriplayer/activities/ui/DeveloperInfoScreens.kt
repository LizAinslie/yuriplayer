package capital.yuri.yuriplayer.activities.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.BuildConfig

private data class OssLicense(
    val name: String,
    val license: String,
    /** Public license text / repo license page (prefer GitHub). */
    val url: String,
    val note: String? = null
)

/** Major third-party components — links to public license pages, not bundled text. */
private val OSS_LICENSES = listOf(
    OssLicense(
        name = "YuriPlayer",
        license = "AGPL-3.0",
        url = "https://github.com/LizAinslie/yuriplayer",
        note = "This application"
    ),
    OssLicense(
        name = "AndroidX / Jetpack",
        license = "Apache-2.0",
        url = "https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt"
    ),
    OssLicense(
        name = "Jetpack Compose",
        license = "Apache-2.0",
        url = "https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt"
    ),
    OssLicense(
        name = "Kotlin",
        license = "Apache-2.0",
        url = "https://github.com/JetBrains/kotlin/blob/master/license/LICENSE.txt"
    ),
    OssLicense(
        name = "Kotlin Coroutines",
        license = "Apache-2.0",
        url = "https://github.com/Kotlin/kotlinx.coroutines/blob/master/LICENSE.txt"
    ),
    OssLicense(
        name = "kotlinx.serialization",
        license = "Apache-2.0",
        url = "https://github.com/Kotlin/kotlinx.serialization/blob/master/LICENSE.txt"
    ),
    OssLicense(
        name = "Media3 (ExoPlayer)",
        license = "Apache-2.0",
        url = "https://github.com/androidx/media/blob/release/LICENSE"
    ),
    OssLicense(
        name = "Ktor",
        license = "Apache-2.0",
        url = "https://github.com/ktorio/ktor/blob/main/LICENSE"
    ),
    OssLicense(
        name = "Koin",
        license = "Apache-2.0",
        url = "https://github.com/InsertKoinIO/koin/blob/main/LICENSE"
    ),
    OssLicense(
        name = "Room",
        license = "Apache-2.0",
        url = "https://github.com/androidx/androidx/blob/androidx-main/LICENSE.txt"
    ),
    OssLicense(
        name = "Coil",
        license = "Apache-2.0",
        url = "https://github.com/coil-kt/coil/blob/main/LICENSE.txt"
    ),
    OssLicense(
        name = "Jellyfin Kotlin SDK",
        license = "LGPL-3.0",
        url = "https://github.com/jellyfin/jellyfin-sdk-kotlin/blob/master/LICENSE"
    ),
    OssLicense(
        name = "jaudiotagger",
        license = "LGPL-2.1-or-later",
        url = "https://github.com/ijabz/jaudiotagger/blob/master/license.txt"
    ),
    OssLicense(
        name = "Material Components",
        license = "Apache-2.0",
        url = "https://github.com/material-components/material-components-android/blob/master/LICENSE"
    )
)

@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "Open source licenses", onBack = onBack)

        Text(
            "YuriPlayer is free software. Tap a row to open the project’s public license page.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        SettingsSectionTitle("Application")
        SettingsGroup {
            val app = OSS_LICENSES.first()
            SettingsNavRow(
                title = app.name,
                subtitle = listOfNotNull(app.license, app.note).joinToString(" · "),
                icon = Icons.Default.Code,
                trailing = "GitHub",
                onClick = { openUrl(app.url) }
            )
        }

        SettingsSectionTitle("Libraries")
        SettingsGroup {
            OSS_LICENSES.drop(1).forEach { lib ->
                SettingsNavRow(
                    title = lib.name,
                    subtitle = lib.license,
                    icon = Icons.Default.OpenInNew,
                    onClick = { openUrl(lib.url) }
                )
            }
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
fun VersionInfoScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val commit = BuildConfig.GIT_COMMIT
    val short = BuildConfig.GIT_COMMIT_SHORT
    val branch = BuildConfig.GIT_BRANCH
    val describe = BuildConfig.GIT_DESCRIBE
    val tag = BuildConfig.GIT_TAG
    val dirty = BuildConfig.GIT_DIRTY
    val repo = BuildConfig.REPO_URL

    fun openUrl(url: String) {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
    ) {
        SettingsTopBar(title = "Version", onBack = onBack)

        SettingsSectionTitle("Build")
        SettingsGroup {
            SettingsNavRow(
                title = "Version name",
                subtitle = BuildConfig.VERSION_NAME,
                onClick = {}
            )
            SettingsNavRow(
                title = "Version code",
                subtitle = BuildConfig.VERSION_CODE.toString(),
                onClick = {}
            )
            SettingsNavRow(
                title = "Build type",
                subtitle = BuildConfig.BUILD_TYPE,
                onClick = {}
            )
        }

        SettingsSectionTitle("Git")
        SettingsGroup {
            SettingsNavRow(
                title = "Commit",
                subtitle = if (short != "unknown") {
                    buildString {
                        append(short)
                        if (dirty) append(" (dirty)")
                        if (commit != "unknown" && commit.length > 7) {
                            append(" · ")
                            append(commit)
                        }
                    }
                } else {
                    "unknown (git not available at build time)"
                },
                trailing = if (commit != "unknown") "GitHub" else null,
                onClick = {
                    if (commit != "unknown") {
                        openUrl("$repo/commit/$commit")
                    }
                }
            )
            SettingsNavRow(
                title = "Branch",
                subtitle = branch,
                onClick = {}
            )
            SettingsNavRow(
                title = "Tag",
                subtitle = tag.ifBlank { "(none on this commit)" },
                trailing = if (tag.isNotBlank()) "GitHub" else null,
                onClick = {
                    if (tag.isNotBlank()) openUrl("$repo/releases/tag/$tag")
                }
            )
            SettingsNavRow(
                title = "Describe",
                subtitle = describe,
                onClick = {}
            )
            SettingsNavRow(
                title = "Working tree",
                subtitle = if (dirty) "Dirty — uncommitted changes at build" else "Clean",
                onClick = {}
            )
        }

        SettingsSectionTitle("Runtime")
        SettingsGroup {
            SettingsNavRow(
                title = "Android",
                subtitle = "API ${Build.VERSION.SDK_INT} · ${Build.VERSION.RELEASE}",
                onClick = {}
            )
            SettingsNavRow(
                title = "Device",
                subtitle = "${Build.MANUFACTURER} ${Build.MODEL}",
                onClick = {}
            )
        }

        SettingsSectionTitle("Source")
        SettingsGroup {
            SettingsNavRow(
                title = "Repository",
                subtitle = repo.removePrefix("https://"),
                icon = Icons.Default.Code,
                trailing = "Open",
                onClick = { openUrl(repo) }
            )
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}
