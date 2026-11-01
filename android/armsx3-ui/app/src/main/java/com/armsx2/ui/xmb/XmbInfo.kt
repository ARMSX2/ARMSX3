package com.armsx2.ui.xmb

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
    // The ARMSX3 mark: the firmware's Online Manuals icon, used here before, is a question mark.
    val mark = androidx.compose.runtime.remember { XmbWordmark.markIcon(192).asImageBitmap() }
    val icon = XmbIcon(bitmap = mark)
    val loading = str("news.loading")
    val none = str("news.unavailable")
    val reader: @Composable () -> Unit = {
        LaunchedEffect(Unit) {
            // Again each time it is opened while it has nothing: a failed fetch is not kept for the run.
            if (XmbNews.items.value.isNullOrEmpty()) {
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
                // The notes as written, their pictures too, under who published and who is credited.
                val blocks = item.blocks.ifEmpty { listOf(News.Block.Text(text)) }
                // Each release with its publisher's GitHub picture, as the normal screen shows it.
                val picture = if (item.authorAvatar.isNotBlank()) XmbIcon(model = item.authorAvatar, bitmap = null, shadow = false) else icon
                val sub = listOf(item.published.take(10), item.authorLogin).filter { it.isNotBlank() }.joinToString("   ")
                XmbNode.Panel("news.${item.tag}", heading, picture, sub) { _ ->
                    XmbBlocksPanel(blocks) { XmbReleaseHeader(item) }
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

/**
 * Notes to read in an XMB panel, text and pictures as written: paragraphs in the XMB's font, and
 * each picture the width of the panel at its own shape. [top] comes before them (an offer's
 * buttons), and the pad starts on [first]. Up and Down go a paragraph or a picture at a time.
 */
@Composable
fun XmbBlocksPanel(blocks: List<News.Block>, first: String = "xmb.text.0", top: (@Composable () -> Unit)? = null) {
    val font = rememberXmbFont()
    val scroll = rememberScrollState()
    com.armsx2.ui.settings.ControllerAutoScroll(scroll)
    LaunchedEffect(blocks, first) {
        var tries = 0
        while (!com.armsx2.ui.settings.SettingsControllerNav.selectById(first) && tries++ < 10) {
            androidx.compose.runtime.withFrameNanos {}
        }
    }
    val style = TextStyle(fontFamily = font, fontSize = 17.sp, lineHeight = 24.sp, color = Color.White.copy(alpha = 0.92f))
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 8.dp)) {
        top?.invoke()
        var n = 0
        blocks.forEach { block ->
            when (block) {
                is News.Block.Text -> paragraphsOf(block.text).forEach { p ->
                    Text(
                        p,
                        style = style,
                        modifier = Modifier
                            .fillMaxWidth()
                            .controllerFocusable("xmb.text.${n++}", ring = false)
                            .padding(vertical = 5.dp),
                    )
                }
                is News.Block.Image -> coil.compose.AsyncImage(
                    model = block.url,
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                        .aspectRatio(block.aspectRatio)
                        .controllerFocusable("xmb.text.${n++}", ring = false),
                )
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}

/**
 * A new ARMSX3, offered in the XMB: a panel of its notes (their pictures too) under Download and
 * Install and Later, with the download's progress. From the check on launch, or Check for Updates.
 */
object XmbUpdateOffer {
    class Offer(val version: String, val notes: String, val install: () -> Unit)

    val offer = mutableStateOf<Offer?>(null)

    /** The download, 0 to 100, while it runs. */
    val progress = mutableStateOf<Int?>(null)

    /** Why the download stopped, when it did. */
    val error = mutableStateOf<String?>(null)

    /** Show [o] over the XMB that is up, at home or over a game. */
    fun show(o: Offer) {
        offer.value = o
        progress.value = null
        error.value = null
        val c = XmbDialogs.target() ?: XmbHomeState.controller
        c.dialog = null
        c.panel = XmbNode.Panel(
            "update.offer",
            com.armsx2.i18n.I18n.get("update.available") + "  " + o.version,
            XmbIcon("explore_category_sysconf", "tex_update", "\u27F3"),
        ) { close -> XmbUpdatePanel(close) }
    }
}

@Composable
private fun XmbUpdatePanel(close: () -> Unit) {
    val o = XmbUpdateOffer.offer.value ?: return
    val pct = XmbUpdateOffer.progress.value
    val failed = XmbUpdateOffer.error.value
    val font = rememberXmbFont()
    val none = str("update.notesUnavailable")
    val blocks = androidx.compose.runtime.remember(o) { News.blocksFrom(o.notes).ifEmpty { listOf(News.Block.Text(none)) } }
    val installLabel = str("update.install")
    val downloading = str("update.downloading")
    val later = str("update.later")
    val installed = str("update.currentVersion")
    XmbBlocksPanel(blocks, first = "update.install") {
        Text(
            "$installed: ${com.armsx2.BuildConfig.VERSION_NAME}   \u2192   ${o.version}",
            style = TextStyle(fontFamily = font, fontSize = 18.sp, color = Color.White),
            modifier = Modifier.padding(vertical = 6.dp),
        )
        androidx.compose.foundation.layout.Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(vertical = 10.dp),
        ) {
            XmbPanelButton("update.install", if (pct != null) "$downloading $pct%" else installLabel, font) {
                if (pct == null) o.install()
            }
            XmbPanelButton("update.later", later, font) { close() }
        }
        if (pct != null) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .padding(vertical = 6.dp)
                    .fillMaxWidth(0.6f)
                    .height(6.dp)
                    .background(Color.White.copy(alpha = 0.22f), androidx.compose.foundation.shape.RoundedCornerShape(50)),
            ) {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxWidth(pct / 100f)
                        .fillMaxHeight()
                        .background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(50)),
                )
            }
        }
        failed?.let {
            Text(it, style = TextStyle(fontFamily = font, fontSize = 15.sp, color = Color(0xFFFF8A80)), modifier = Modifier.padding(vertical = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** A button in an XMB panel: glass with a light edge, in the XMB's font. */
@Composable
internal fun XmbPanelButton(id: String, label: String, font: androidx.compose.ui.text.font.FontFamily, onClick: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
    androidx.compose.foundation.layout.Box(
        Modifier
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.08f))),
                shape,
            )
            .border(1.dp, Color.White.copy(alpha = 0.55f), shape)
            .clickable(onClick = onClick)
            .controllerFocusable(id, shape, onConfirm = onClick)
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(label, style = TextStyle(fontFamily = font, fontSize = 17.sp, color = Color.White))
    }
}

/**
 * Who a release is from and who it credits, at the head of its notes: the publisher's GitHub
 * picture and name with the date, then each contributor the notes thank with their picture, as
 * the normal What's New shows them.
 */
@Composable
private fun XmbReleaseHeader(item: News.Item) {
    val font = rememberXmbFont()
    val contributors = str("news.contributors")
    val prerelease = str("news.prerelease")
    val ring = androidx.compose.foundation.shape.CircleShape
    Column(Modifier.padding(vertical = 6.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            if (item.authorAvatar.isNotBlank()) {
                coil.compose.AsyncImage(
                    model = item.authorAvatar,
                    contentDescription = item.authorLogin,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.size(38.dp).clip(ring).border(1.dp, Color.White.copy(alpha = 0.6f), ring),
                )
                Spacer(Modifier.width(10.dp))
            }
            Text(
                listOfNotNull(item.authorLogin.takeIf { it.isNotBlank() }, item.published.take(10), prerelease.takeIf { item.prerelease })
                    .joinToString("   "),
                style = TextStyle(fontFamily = font, fontSize = 16.sp, color = Color.White.copy(alpha = 0.9f)),
            )
        }
        if (item.credited.isNotEmpty()) {
            Text(contributors, style = TextStyle(fontFamily = font, fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f)))
            androidx.compose.foundation.layout.Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp),
            ) {
                item.credited.forEach { person ->
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, modifier = Modifier.width(66.dp)) {
                        coil.compose.AsyncImage(
                            model = person.avatar,
                            contentDescription = person.login,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.size(42.dp).clip(ring).border(1.dp, Color.White.copy(alpha = 0.6f), ring),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            person.login,
                            style = TextStyle(fontFamily = font, fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f)),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
