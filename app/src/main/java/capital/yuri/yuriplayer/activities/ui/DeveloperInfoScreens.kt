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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import capital.yuri.yuriplayer.BuildConfig
import capital.yuri.yuriplayer.R
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library

/**
 * Prefer public project / license URLs (GitHub, SPDX, homepage) over embedding full text.
 */
private fun Library.openUrl(): String? {
    website?.takeIf { it.startsWith("http") }?.let { return it }
    scm?.url?.takeIf { it.startsWith("http") }?.let { return it }
    licenses.firstOrNull()?.url?.takeIf { it.startsWith("http") }?.let { return it }
    return null
}

private fun Library.licenseLabel(): String {
    val ids = licenses.mapNotNull { lic ->
        lic.spdxId?.takeIf { it.isNotBlank() } ?: lic.name.takeIf { it.isNotBlank() }
    }.distinct()
    return when {
        ids.isEmpty() -> "License unknown"
        ids.size == 1 -> ids.first()
        else -> ids.joinToString(", ")
    }
}

@Composable
fun OpenSourceLicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    val libraries = remember {
        runCatching {
            Libs.Builder()
                .withJson(context, R.raw.aboutlibraries)
                .build()
                .libraries
                .sortedBy { it.name.lowercase() }
        }.getOrElse { emptyList() }
    }

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
            "Generated from declared Gradle dependencies. Tap a row to open the project or license page.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )

        SettingsSectionTitle("Application")
        SettingsGroup {
            SettingsNavRow(
                title = "YuriPlayer",
                subtitle = "AGPL-3.0 · this application",
                icon = Icons.Default.Code,
                trailing = "GitHub",
                onClick = { openUrl(BuildConfig.REPO_URL) }
            )
        }

        SettingsSectionTitle("Libraries (${libraries.size})")
        SettingsGroup {
            if (libraries.isEmpty()) {
                SettingsNavRow(
                    title = "No license metadata",
                    subtitle = "Rebuild so AboutLibraries can generate R.raw.aboutlibraries",
                    onClick = {}
                )
            } else {
                libraries.forEach { lib ->
                    val url = lib.openUrl()
                    val version = lib.artifactVersion?.takeIf { it.isNotBlank() }
                    val subtitle = buildString {
                        append(lib.licenseLabel())
                        if (version != null) {
                            append(" · ")
                            append(version)
                        }
                    }
                    SettingsNavRow(
                        title = lib.name.ifBlank { lib.uniqueId },
                        subtitle = subtitle,
                        icon = if (url != null) Icons.Default.OpenInNew else null,
                        onClick = { if (url != null) openUrl(url) }
                    )
                }
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
