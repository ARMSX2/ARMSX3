package com.armsx2.ui.xmb

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.News
import com.armsx2.i18n.str
import com.armsx2.navigation.openExternalUrl
import com.armsx2.ui.settings.controllerFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Text to read in the XMB, in an XMB panel: paragraphs in the XMB's font, white on the panel's dark
 * glass, as the console shows its information screens. Up and Down go a paragraph at a time.
 */
@Composable
fun XmbTextPanel(paragraphs: List<String>) {
    val font = rememberXmbFont()
    val scroll = rememberScrollState()
    com.armsx2.ui.settings.ControllerAutoScroll(scroll)
    // The pad's Up and Down land on the paragraphs, starting at the first.
    LaunchedEffect(paragraphs) {
        var tries = 0
        while (!com.armsx2.ui.settings.SettingsControllerNav.selectById("xmb.text.0") && tries++ < 10) {
            androidx.compose.runtime.withFrameNanos {}
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 8.dp)) {
        paragraphs.forEachIndexed { i, p ->
            Text(
                p,
                style = TextStyle(fontFamily = font, fontSize = 17.sp, lineHeight = 24.sp, color = Color.White.copy(alpha = 0.92f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .controllerFocusable("xmb.text.$i", ring = false)
                    .padding(vertical = 5.dp),
            )
        }
        Spacer(Modifier.height(80.dp))
    }
}

/** Text split into paragraphs at blank lines, each kept whole. */
internal fun paragraphsOf(text: String): List<String> =
    text.replace("\r", "").split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }

/** What's New: ARMSX3's releases, newest first, each opening its notes. */
object XmbNews {
    val items = mutableStateOf<List<News.Item>?>(null)
}

@Composable
fun xmbNewsNode(): XmbNode {
    val context = LocalContext.current
    val title = str("news.title")
    val icon = XmbIcon("explore_category_network", "tex_onlinemanual", "📰")
    val loading = str("news.loading")
    val none = str("news.unavailable")
    val reader: @Composable () -> Unit = {
        LaunchedEffect(Unit) {
            if (XmbNews.items.value == null) {
                XmbNews.items.value = withContext(Dispatchers.IO) {
                    runCatching { News.fetch(context, false)?.items }.getOrNull().orEmpty()
                }
            }
        }
    }
    return XmbNode.Folder("armsx3.news", title, icon, live = true, host = reader) {
        val items = XmbNews.items.value
        when {
            items == null -> listOf(XmbNode.Action("news.loading", loading, icon) {})
            items.isEmpty() -> listOf(XmbNode.Action("news.none", none, icon) {})
            else -> items.map { item ->
                val heading = listOf(item.tag, item.title).filter { it.isNotBlank() }.distinct().joinToString("  ")
                val text = item.blocks.filterIsInstance<News.Block.Text>().joinToString("\n\n") { it.text }.ifBlank { item.notes }
                XmbNode.Panel("news.${item.tag}", heading, icon, item.published.take(10)) { _ ->
                    XmbTextPanel(paragraphsOf(text))
                }
            }
        }
    }
}

/** About ARMSX3: its version and the device's, where it comes from, and the licences of what it uses. */
@Composable
fun xmbAboutNode(markIcon: XmbIcon): XmbNode {
    val context = LocalContext.current
    val vm: com.armsx2.ui.about.AboutViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val state = vm.state.value
    LaunchedEffect(Unit) { vm.load() }
    val info = XmbIcon("explore_category_sysconf", "tex_console", "ℹ")
    fun row(key: String, label: String, value: String) = XmbNode.Action("about.$key", label, info, value.ifBlank { "-" }) {}
    val build = str("about.build.title")
    val hardware = str("about.hardware.title")
    val repoTitle = str("about.repository.title")
    val repoDesc = str("about.repository.description")
    val rpcs3Title = str("about.rpcs3.title")
    val rpcs3Desc = str("about.rpcs3.description")
    val licensesTitle = str("about.licenses.title")
    val licensesDesc = str("about.licenses.description")
    val tagline = str("about.tagline")
    val labels = listOf(
        str("about.appVersion"), str("about.coreVersion"), str("about.device"), str("about.androidVersion"), str("about.soc"),
        str("about.gpu"), str("about.cpuCores"), str("about.memory"), str("about.display"), str("about.architecture"), str("about.pageSize"),
    )
    val licenses = com.armsx2.ui.about.xmbLicenseNodes()
    return XmbNode.Folder("armsx3.about", str("about.title"), markIcon, tagline) {
        listOf(
            XmbNode.Folder("about.build", build, info, "${state.appVersion}   ${state.coreVersion}") {
                listOf(row("app", labels[0], state.appVersion), row("core", labels[1], state.coreVersion))
            },
            XmbNode.Folder("about.hardware", hardware, info, state.device) {
                listOf(
                    row("device", labels[2], state.device),
                    row("android", labels[3], state.androidVersion),
                    row("soc", labels[4], state.soc),
                    row("gpu", labels[5], state.gpu),
                    row("cpu", labels[6], state.cpu),
                    row("memory", labels[7], state.memory),
                    row("display", labels[8], state.display),
                    row("arch", labels[9], state.architecture),
                    row("page", labels[10], state.pageSize),
                )
            },
            XmbNode.Action("about.repo", repoTitle, info, repoDesc) { openExternalUrl(context, "https://github.com/ARMSX2/ARMSX3") },
            XmbNode.Action("about.rpcs3", rpcs3Title, info, rpcs3Desc) { openExternalUrl(context, "https://github.com/RPCS3/rpcs3") },
            XmbNode.Folder("about.licenses", licensesTitle, info, licensesDesc) { licenses },
        )
    }
}
