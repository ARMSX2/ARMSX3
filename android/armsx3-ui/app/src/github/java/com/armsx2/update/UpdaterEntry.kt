package com.armsx2.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.armsx2.BuildConfig
import com.armsx2.i18n.str
import com.armsx2.runtime.MainActivityRuntime
import com.armsx2.ui.common.GlassPanel
import com.armsx2.ui.common.SettingSwitchRow
import com.armsx2.ui.settings.controllerFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Calendar
import java.util.TimeZone

/**
 * In-app updater for the GitHub release channel.
 *
 * Renders a "Check for updates" panel in the App settings tab: queries the ARMSX3 releases API,
 * compares the latest tag against the installed build, downloads the .apk asset with a progress
 * bar into externalCacheDir/updates/, and hands it to the system package installer through a
 * FileProvider. The user confirms the install; nothing is installed silently.
 *
 * A SELF-UPDATING APP IS A HARD PLAY-POLICY VIOLATION. ARMSX2 keeps this out of its Play bundle
 * with a src/github vs src/play flavor split plus a build script that fails closed if
 * REQUEST_INSTALL_PACKAGES appears in the bundle manifest. ARMSX3 has no Play build and no
 * flavors, so this lives in src/main behind BuildConfig.IN_APP_UPDATER instead. If ARMSX3 ever
 * gains a Play target, that split has to come first: move this file and the permission and the
 * provider into a github flavor, because the runtime flag alone does not stop the permission
 * shipping, and the permission is what Play rejects.
 *
 * Nightly-safe: a nightly build would use versionCode = Unix seconds (> 1e6), always numerically
 * ahead of any stable, so those are short-circuited rather than being offered a downgrade.
 */

private const val LATEST_URL = "https://api.github.com/repos/ARMSX2/ARMSX3/releases/latest"
private const val RELEASES_URL = "https://api.github.com/repos/ARMSX2/ARMSX3/releases?per_page=20"
private const val NIGHTLY_VC_THRESHOLD = 1_000_000  // stable VCs are ~1300; nightly = Unix seconds.

private sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val version: String, val notes: String, val apkUrl: String) : UpdateState
    data class Downloading(val pct: Int) : UpdateState
    data class Error(val msg: String) : UpdateState
}

@Composable
fun UpdaterEntry() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    // str() is @Composable, so resolve the strings the background check / onClick handlers need
    // here and capture them (they run outside composition).
    val checkFailedPrefix = str("update.checkFailed")
    val downloadFailedPrefix = str("update.downloadFailed")

    GlassPanel(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        Column(Modifier.padding(4.dp)) {
            Text(str("update.title"), style = MaterialTheme.typography.titleMedium)
            Text(
                "${str("update.currentVersion")}: ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            when (val s = state) {
                is UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(str("update.checking"), style = MaterialTheme.typography.bodySmall)
                }
                is UpdateState.UpToDate -> Text(
                    if (BuildConfig.VERSION_CODE > NIGHTLY_VC_THRESHOLD) str("update.onNightly")
                    else str("update.upToDate"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                is UpdateState.Error -> Text(
                    s.msg, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                is UpdateState.Downloading -> Column {
                    Text("${str("update.downloading")} ${s.pct}%", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { s.pct / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                else -> {}
            }
            Spacer(Modifier.height(8.dp))
            // Extracted so the controller's confirm and the touch onClick share one action, and
            // the button joins the nav registry ("update.check") — the whole updater section was
            // touch-only before.
            val runCheck: () -> Unit = {
                scope.launch {
                    state = UpdateState.Checking
                    state = checkForUpdate(
                        MainActivityRuntime.prefs.getBoolean("update.includeNightly", false),
                        checkFailedPrefix,
                    )
                }
            }
            Button(
                enabled = state !is UpdateState.Checking && state !is UpdateState.Downloading,
                onClick = runCheck,
                modifier = Modifier.controllerFocusable("update.check", onConfirm = runCheck),
            ) { Text(str("update.check")) }

            // Opt-in: silently check GitHub for a newer release on every app launch (default off).
            var checkOnLaunch by remember {
                mutableStateOf(MainActivityRuntime.prefs.getBoolean("update.checkOnLaunch", false))
            }
            SettingSwitchRow(
                title = str("update.checkOnLaunch"),
                description = str("update.checkOnLaunch.desc"),
                checked = checkOnLaunch,
                onCheckedChange = {
                    checkOnLaunch = it
                    MainActivityRuntime.prefs.edit().putBoolean("update.checkOnLaunch", it).apply()
                },
            )

            // Opt-in: also consider nightly (pre-release) builds when checking (default off).
            var includeNightly by remember {
                mutableStateOf(MainActivityRuntime.prefs.getBoolean("update.includeNightly", false))
            }
            SettingSwitchRow(
                title = str("update.includeNightly"),
                description = str("update.includeNightly.desc"),
                checked = includeNightly,
                onCheckedChange = {
                    includeNightly = it
                    MainActivityRuntime.prefs.edit().putBoolean("update.includeNightly", it).apply()
                },
            )
        }
    }

    (state as? UpdateState.Available)?.let { avail ->
        AlertDialog(
            onDismissRequest = { state = UpdateState.Idle },
            title = { Text("${str("update.available")}  ${avail.version}") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        avail.notes.ifBlank { str("update.notesUnavailable") },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            downloadAndInstall(context, avail) { pct -> state = UpdateState.Downloading(pct) }
                            state = UpdateState.Idle
                        } catch (e: Exception) {
                            state = UpdateState.Error("$downloadFailedPrefix: ${e.message}")
                        }
                    }
                }) { Text(str("update.install")) }
            },
            dismissButton = {
                TextButton(onClick = { state = UpdateState.Idle }) { Text(str("action.cancel")) }
            },
        )
    }
}

/**
 * Boot-time auto-check (github flavor only). Mounted once at the app root; when the "check on
 * launch" toggle is on, it runs a single silent GitHub check on start and pops the update prompt
 * ONLY if a newer release exists — no "up to date" popup, no noise on every boot. Reuses the exact
 * check/download/install path as the manual button. Nightly-safe via checkForUpdate's VC guard.
 */
@Composable
fun AutoUpdateGate() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    val checkFailedPrefix = str("update.checkFailed")

    LaunchedEffect(Unit) {
        if (MainActivityRuntime.prefs.getBoolean("update.checkOnLaunch", false)) {
            val result = checkForUpdate(
                MainActivityRuntime.prefs.getBoolean("update.includeNightly", false),
                checkFailedPrefix,
            )
            if (result is UpdateState.Available) state = result  // stay silent on up-to-date / errors
        }
    }

    val s = state
    if (s is UpdateState.Available || s is UpdateState.Downloading) {
        val avail = s as? UpdateState.Available
        AlertDialog(
            onDismissRequest = { if (state !is UpdateState.Downloading) state = UpdateState.Idle },
            title = {
                Text(
                    if (state is UpdateState.Downloading) str("update.downloading")
                    else "${str("update.available")}  ${avail?.version.orEmpty()}",
                )
            },
            text = {
                when (val cur = state) {
                    is UpdateState.Available -> Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        Text(cur.notes.ifBlank { str("update.notesUnavailable") }, style = MaterialTheme.typography.bodySmall)
                    }
                    is UpdateState.Downloading -> Column {
                        Text("${cur.pct}%", style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { cur.pct / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {}
                }
            },
            confirmButton = {
                if (avail != null) {
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                downloadAndInstall(context, avail) { pct -> state = UpdateState.Downloading(pct) }
                            } finally {
                                state = UpdateState.Idle
                            }
                        }
                    }) { Text(str("update.install")) }
                }
            },
            dismissButton = {
                if (state is UpdateState.Available) {
                    TextButton(onClick = { state = UpdateState.Idle }) { Text(str("update.later")) }
                }
            },
        )
    }
}

private suspend fun checkForUpdate(includeNightly: Boolean, checkFailedPrefix: String): UpdateState = withContext(Dispatchers.IO) {
    try {
        if (!includeNightly) {
            // Stable channel. A nightly build (VC = Unix seconds) is always ahead of any stable, so
            // never prompt it — and never offer it a stable (that would be a versionCode downgrade).
            if (BuildConfig.VERSION_CODE > NIGHTLY_VC_THRESHOLD) return@withContext UpdateState.UpToDate
            val obj = JSONObject(httpGet(LATEST_URL))
            val apkUrl = pickApkAsset(obj) ?: return@withContext UpdateState.UpToDate
            val tag = obj.getString("tag_name")
            return@withContext if (isNewer(tag, BuildConfig.VERSION_NAME))
                UpdateState.Available(tag, obj.optString("body", ""), apkUrl)
            else UpdateState.UpToDate
        }

        // Nightly channel: GitHub returns releases newest-first, so offer the first genuinely-newer
        // one that has an APK. Nightlies are pre-releases tagged nightly-YYYYMMDD; stables are vX.Y.Z.
        // Compare nightlies by day (the installed nightly's build day comes from its VC = Unix seconds);
        // compare stables by version name. A nightly install is never offered a stable — that's a
        // versionCode downgrade the system installer rejects anyway (reinstall stable manually).
        val arr = JSONArray(httpGet(RELEASES_URL))
        val installedIsNightly = BuildConfig.VERSION_CODE > NIGHTLY_VC_THRESHOLD
        val installedDay = if (installedIsNightly) epochSecToYyyymmdd(BuildConfig.VERSION_CODE.toLong()) else 0
        for (i in 0 until arr.length()) {
            val rel = arr.getJSONObject(i)
            if (rel.optBoolean("draft", false)) continue
            val apkUrl = pickApkAsset(rel) ?: continue
            val tag = rel.getString("tag_name")
            val isNightlyRel = rel.optBoolean("prerelease", false) || tag.startsWith("nightly-", ignoreCase = true)
            val newer = if (isNightlyRel) {
                nightlyTagDay(tag) > installedDay  // stable install => installedDay 0 => any nightly is newer
            } else {
                !installedIsNightly && isNewer(tag, BuildConfig.VERSION_NAME)
            }
            if (newer) return@withContext UpdateState.Available(tag, rel.optString("body", ""), apkUrl)
        }
        UpdateState.UpToDate
    } catch (e: Exception) {
        UpdateState.Error("$checkFailedPrefix: ${e.message}")
    }
}

/**
 * Does this CPU have the ARMv8.2 features the a13/a15 builds are compiled with?
 *
 * Those two are built -march=armv8.2-a+dotprod+fp16, which is a hard floor: the instructions can
 * appear anywhere in the core and a device without them takes SIGILL rather than falling back.
 * Handing such a device the wrong APK is a crash on launch with no explanation, so this is
 * checked rather than inferred from the Android version -- a budget A53 part can and does ship
 * with Android 13.
 *
 * /proc/cpuinfo is the portable read here: HWCAP needs NDK glue, and this runs once.
 */
private val hasArmv8_2Features: Boolean by lazy {
    runCatching {
        val features = File("/proc/cpuinfo").useLines { lines ->
            lines.firstOrNull { it.startsWith("Features") }.orEmpty()
        }
        // asimddp = FEAT_DotProd, asimdhp = FEAT_FP16 (half-precision Advanced SIMD).
        features.contains("asimddp") && features.contains("asimdhp")
    }.getOrDefault(false)
}

/**
 * Best `.apk` asset for THIS device, or null if the release has none.
 *
 * Releases carry four APKs that are alternatives, not an upgrade path (see
 * android/build-variants.sh):
 *
 *   -a15-armv8.2-sdk35       API 35 + armv8.2   newest devices
 *   -a13-armv8.2-sdk33       API 33 + armv8.2   the standard build
 *   -a11-armv8.2-sdk30       API 30 + armv8.2
 *   -legacy-armv8.1-sdk26    API 26 + armv8.1   fallback, no dotprod/FP16 required
 *
 * Picking the first asset the API happens to list would hand everyone the same file -- and
 * alphabetically that is a11, which fails to install below Android 11 and faults outright on a
 * pre-8.2 core. So walk the preference order and take the best one the device can actually run,
 * falling back to any .apk so single-asset releases (everything before 0.7.2) still work.
 *
 * Both tests matter and neither implies the other. The SDK gate is what the installer would
 * enforce anyway; the ISA gate is not enforced by anything, because a budget A53 part ships with
 * a current Android and would take SIGILL on the first dotprod instruction. A device that passes
 * the SDK test but fails the ISA test correctly falls all the way to legacy.
 */
private fun pickApkAsset(release: JSONObject): String? {
    val assets = release.optJSONArray("assets") ?: return null

    val byName = buildMap {
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.getString("name")
            if (name.endsWith(".apk", ignoreCase = true)) put(name.lowercase(), a.getString("browser_download_url"))
        }
    }
    if (byName.isEmpty()) return null

    val sdk = Build.VERSION.SDK_INT
    val preferred = buildList {
        if (sdk >= 35 && hasArmv8_2Features) add("-a15-armv8.2-sdk35")
        if (sdk >= 33 && hasArmv8_2Features) add("-a13-armv8.2-sdk33")
        if (sdk >= 30 && hasArmv8_2Features) add("-a11-armv8.2-sdk30")
        add("-legacy-armv8.1-sdk26")

        // Pre-0.7.3 spellings, kept so an install from an older release still finds a variant
        // by name instead of dropping to "first asset wins" below. Ordered after the current
        // names so a release carrying both is read with the new scheme.
        if (sdk >= 35 && hasArmv8_2Features) add("-a15")
        if (sdk >= 33 && hasArmv8_2Features) add("-a13")
        add("-generic")
    }

    for (suffix in preferred) {
        byName.entries.firstOrNull { it.key.contains(suffix) }?.let { return it.value }
    }

    // No variant matched by name: an older single-APK release, or a naming change. Taking the
    // only asset is right for the former; for the latter a wrong guess beats offering nothing,
    // because the installer still refuses an APK whose minSdk this device fails.
    return byName.values.firstOrNull()
}

/** "nightly-YYYYMMDD" -> YYYYMMDD as an int (0 if the tag isn't a dated nightly). */
private fun nightlyTagDay(tag: String): Int =
    Regex("nightly-(\\d{8})", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: 0

/** Unix-epoch seconds (a nightly build's versionCode) -> UTC YYYYMMDD int, matching the nightly tag. */
private fun epochSecToYyyymmdd(epochSec: Long): Int {
    val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = epochSec * 1000L }
    return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
}

/** Semantic-version compare of the release tag vs the installed versionName. Non-numeric suffixes
 *  (e.g. the "2.6.4.3.r" tag) are dropped — only the leading dotted integers matter. */
private fun isNewer(remoteTag: String, installed: String): Boolean {
    fun parts(v: String) = v.trim().removePrefix("v").split('.', '-')
        .map { it.takeWhile(Char::isDigit) }.mapNotNull { it.toIntOrNull() }
    val r = parts(remoteTag); val i = parts(installed)
    for (k in 0 until maxOf(r.size, i.size)) {
        val a = r.getOrElse(k) { 0 }; val b = i.getOrElse(k) { 0 }
        if (a != b) return a > b
    }
    return false
}

private fun httpGet(url: String): String {
    val conn = URL(url).openConnection() as HttpURLConnection
    return try {
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "ARMSX3-Updater")
        conn.inputStream.bufferedReader().use { it.readText() }
    } finally {
        conn.disconnect()
    }
}

private suspend fun downloadAndInstall(context: Context, info: UpdateState.Available, onProgress: (Int) -> Unit) {
    val apk = withContext(Dispatchers.IO) {
        val dir = File(context.externalCacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }  // keep only the current download
        val out = File(dir, "armsx3-update.apk")
        val conn = URL(info.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "ARMSX3-Updater")
            val total = conn.contentLengthLong
            var read = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                out.outputStream().use { sink ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = ((read * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        out
    }
    // Hand the APK to the system package installer (user confirms the install).
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.updateprovider", apk)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
