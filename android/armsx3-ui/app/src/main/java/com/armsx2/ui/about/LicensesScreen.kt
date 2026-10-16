package com.armsx2.ui.about

import android.content.Context
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.armsx2.i18n.str
import com.armsx2.ui.common.ArmsBackdrop
import com.armsx2.ui.common.ArmsTopBar
import com.armsx2.ui.common.GlassPanel
import com.armsx2.ui.common.RoundAction
import com.armsx2.ui.settings.ControllerAutoScroll
import com.armsx2.ui.settings.controllerFocusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * About > Open-source licenses: every third-party component inside the APK, with its license.
 *
 * BSD, MIT, Apache-2.0 and the rest allow shipping a binary only with their notices, so this
 * list is part of what makes the APK redistributable. android/tools/gen_open_source_licenses.py
 * writes it into assets/licenses/; the GitHub flavor adds a second file for what only that build
 * bundles. The license texts stay in English, as their authors wrote them.
 *
 * A plain Column, like the News screen: controllerFocusable only registers rows that are
 * composed, so a lazy list would leave holes in the pad's nav registry. The pad moves between
 * entries and the right stick scrolls through a long text.
 */
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries by produceState<List<License>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadLicenses(context) }
    }
    val scroll = rememberScrollState()
    ControllerAutoScroll(scroll)

    ArmsBackdrop {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            ArmsTopBar(
                title = str("about.licenses.title"),
                leading = { RoundAction("←", str("action.back"), onBack) },
            )
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                GlassPanel(Modifier.fillMaxWidth()) {
                    Text(str("licenses.intro"), style = MaterialTheme.typography.bodySmall)
                }
                val list = entries
                if (list == null) {
                    GlassPanel(Modifier.fillMaxWidth()) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                } else {
                    list.forEachIndexed { index, license -> LicenseCard(license, "licenses.$index") }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun LicenseCard(license: License, id: String) {
    GlassPanel(Modifier.fillMaxWidth()) {
        LicenseEntry(license, id, nested = false)
    }
}

@Composable
private fun LicenseEntry(license: License, id: String, nested: Boolean) {
    var expanded by rememberSaveable(id) { mutableStateOf(false) }
    val toggle = { expanded = !expanded }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                // clickable for touch, controllerFocusable for the pad's confirm button: the
                // second does not make the row answer a finger (see ReleaseCard in News).
                .clickable(onClick = toggle)
                .controllerFocusable(id, onConfirm = toggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                Text(
                    license.name,
                    style = if (nested) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    license.license,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (expanded) "−" else "+",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (expanded) {
            license.notice?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            license.text?.let {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 13.sp)
            }
            license.children.forEachIndexed { index, child ->
                LicenseEntry(child, "$id.$index", nested = true)
            }
        }
    }
}

private class License(
    val name: String,
    val license: String,
    val notice: String?,
    val text: String?,
    val children: List<License>,
)

private val LICENSE_FILES = listOf(
    "licenses/open_source_licenses.json",
    // Only the GitHub flavor has this one; the Play build does not bundle what it lists.
    "licenses/open_source_licenses_github.json",
)

private fun loadLicenses(context: Context): List<License> =
    LICENSE_FILES.flatMap { path ->
        val json = runCatching { context.assets.open(path).bufferedReader().use { it.readText() } }.getOrNull()
            ?: return@flatMap emptyList()
        val root = JSONObject(json)
        val texts = root.optJSONObject("texts")
        parse(root.getJSONArray("components"), texts)
    }

private fun parse(array: JSONArray, texts: JSONObject?): List<License> =
    (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        val text = item.optString("text").ifBlank { null }
            ?: item.optString("textId").ifBlank { null }?.let { texts?.optString(it) }?.ifBlank { null }
        License(
            name = item.getString("name"),
            license = item.getString("license"),
            notice = item.optString("notice").ifBlank { null },
            text = text,
            children = item.optJSONArray("children")?.let { parse(it, texts) } ?: emptyList(),
        )
    }
