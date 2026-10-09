package com.armsx2.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.armsx2.BuildConfig
import com.armsx2.i18n.I18n
import java.io.File

/**
 * What happens when the device has no system document picker.
 *
 * OpenDocumentTree, OpenDocument and GetContent are all answered by the system document picker.
 * A device without Google services can ship without anything that handles them, HarmonyOS among
 * them, and launch() then throws ActivityNotFoundException straight out of the click that started
 * it, which closes the app. Issue #186: a Huawei MatePad Air on HarmonyOS 4.2 could not get past
 * the ROMs step of first-run setup.
 *
 * MainActivityRuntime.startActivityForResult catches that once for every launcher in the app and
 * reports it here. By default the user is told; [launchWithFallback] lets a caller that has a way
 * around it, our own folder browser, take over instead.
 */
object SystemPickers {
    private const val TAG = "ARMSX3-Picker"

    // Main thread only, set and read around one synchronous launch() call.
    private var fallbackArmed = false
    private var failed = false

    fun onLaunchFailed(context: Context, intent: Intent) {
        Log.w(TAG, "nothing on this device handles ${intent.action}")
        if (fallbackArmed) {
            failed = true
        } else {
            Toast.makeText(context, I18n.get("browse.noSystemPicker"), Toast.LENGTH_LONG).show()
        }
    }

    /** Launches, and runs [fallback] instead when nothing on the device can handle it. */
    fun <I> launchWithFallback(launcher: ActivityResultLauncher<I>, input: I, fallback: () -> Unit) {
        failed = false
        fallbackArmed = true
        try {
            launcher.launch(input)
        } catch (_: ActivityNotFoundException) {
            // Normally caught by the activity before it gets here; this covers a launcher
            // registered somewhere that does not route through it.
            failed = true
        } finally {
            fallbackArmed = false
        }
        if (failed) {
            failed = false
            fallback()
        }
    }
}

/**
 * A folder picker that still works without the system one: the system picker where it exists,
 * otherwise our own browser in folder mode. That browser needs All files access, which is asked
 * for first on the builds that can have it. [onTree] gets the system picker's tree URI, [onPath]
 * a folder chosen in our browser. Returns the action that opens it.
 */
@Composable
fun rememberFolderPicker(
    title: String,
    onTree: (Uri) -> Unit,
    onPath: (File) -> Unit,
): () -> Unit {
    val context = LocalContext.current
    var browsing by remember { mutableStateOf(false) }

    val system = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(onTree)
    }
    val allFiles = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (canBrowse()) {
            browsing = true
        } else {
            Toast.makeText(context, I18n.get("browse.allFilesNeeded"), Toast.LENGTH_LONG).show()
        }
    }

    if (browsing) {
        FileBrowserDialog(
            title = title,
            onPick = { dir ->
                browsing = false
                onPath(dir)
            },
            onDismiss = { browsing = false },
            pickFolder = true,
        )
    }

    return {
        SystemPickers.launchWithFallback(system, null) {
            when {
                canBrowse() -> browsing = true
                BuildConfig.STORAGE_ALL_FILES && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                    SystemPickers.launchWithFallback(
                        allFiles,
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    ) {
                        // Some ROMs have only the general screen. If that is missing as well, the
                        // activity's catch tells the user.
                        allFiles.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                else ->
                    Toast.makeText(context, I18n.get("browse.noSystemPicker"), Toast.LENGTH_LONG).show()
            }
        }
    }
}
