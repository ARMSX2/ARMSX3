package net.rpcsx.ui.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import net.rpcsx.ArmsxLinks
import net.rpcsx.BuildConfig
import net.rpcsx.MenuSfx
import net.rpcsx.R
import net.rpcsx.utils.GitHub

/**
 * About + What's New.
 *
 * The changelog is the GitHub Releases feed for [ArmsxLinks.REPO], fetched
 * through the existing GitHub helper (which already caches responses, so
 * reopening this screen does not re-hit the API).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(navigateBack: () -> Unit) {
    val context = LocalContext.current

    var releases by remember { mutableStateOf<List<GitHub.Release>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        when (val result = GitHub.fetchReleaseNotes(ArmsxLinks.REPO)) {
            is GitHub.FetchResult.Success<*> -> {
                @Suppress("UNCHECKED_CAST")
                releases = result.content as? List<GitHub.Release>
            }

            is GitHub.FetchResult.Error -> error = result.message
        }
    }

    val open: (String) -> Unit = { url ->
        MenuSfx.play(MenuSfx.Event.SELECT)
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = {
                        MenuSfx.play(MenuSfx.Event.BACK)
                        navigateBack()
                    }) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_keyboard_arrow_left),
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "ARMSX3",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = BuildConfig.Version,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "PlayStation 3 emulation, built on RPCS3",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            LinkRow("GitHub", ArmsxLinks.REPO) { open(ArmsxLinks.REPO) }
            LinkRow("Discord", ArmsxLinks.DISCORD) { open(ArmsxLinks.DISCORD) }
            LinkRow("Website", ArmsxLinks.WEBSITE) { open(ArmsxLinks.WEBSITE) }
            LinkRow("Report an issue", ArmsxLinks.ISSUES) { open(ArmsxLinks.ISSUES) }

            HorizontalDivider()

            Text(
                text = "What's new",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
            )

            when {
                error != null -> {
                    Text(
                        text = "Couldn't load release notes.\n$error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                releases == null -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }

                releases!!.isEmpty() -> {
                    Text(
                        text = "No releases published yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                else -> releases!!.forEach { release -> ReleaseCard(release) }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReleaseCard(release: GitHub.Release) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = release.name.ifBlank { release.tag_name },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (release.prerelease) {
                    Text(
                        text = "pre-release",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            // GitHub returns ISO-8601; the date half is all we want and
            // substringBefore avoids pulling in a formatter for it.
            if (release.published_at.isNotBlank()) {
                Text(
                    text = release.published_at.substringBefore('T'),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (release.body.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    // Release bodies are markdown. Rendering them properly would
                    // mean a markdown dependency; monospace keeps bullet lists
                    // and indentation readable without one.
                    text = release.body.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
