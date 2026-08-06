package net.rpcsx

import net.rpcsx.ui.theme.LibraryBackgroundColorPreferences

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.rpcsx.dialogs.AlertDialogQueue
import net.rpcsx.ui.navigation.AppNavHost
import net.rpcsx.utils.GeneralSettings
import net.rpcsx.utils.GitHub
import net.rpcsx.utils.RpcsxUpdater
import java.io.File
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private lateinit var unregisterUsbEventListener: () -> Unit
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        GeneralSettings.init(this)

        // ARMSX3 subsystems. All read their persisted state from the same
        // "app_prefs" store GeneralSettings just opened, so they must come after
        // it and before any Compose content that reads their state.
        SetupRepository.load()
        AndroidPerformance.load()
        LibraryBackgroundColorPreferences.load()
        CoverRepository.initialize(this)
        MenuSfx.load(this)
        LibraryMusic.load()
        PauseMusic.load()

        WindowCompat.setDecorFitsSystemWindows(window, false)

        if (!RPCSX.initialized) {
            Permission.PostNotifications.requestPermission(this)

            with(getSystemService(NOTIFICATION_SERVICE) as NotificationManager) {
                val channel = NotificationChannel(
                    "rpcsx-progress",
                    getString(R.string.installation_progress),
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }

                createNotificationChannel(channel)
            }

            RPCSX.rootDirectory = applicationContext.getExternalFilesDir(null).toString()
            if (!RPCSX.rootDirectory.endsWith("/")) {
                RPCSX.rootDirectory += "/"
            }

            lifecycleScope.launch {
                GameRepository.load()
            }

            FirmwareRepository.load()
            GitHub.initialize(this)

            // ARMSX3: resolve the native lib dir FIRST so we can fall back to
            // the bundled core. Upstream RPCSX ships no core in the APK -- it
            // downloads one from its update channels on first run, so with no
            // "rpcsx_library" pref the whole app (including our setup screen)
            // is stuck behind an early return in AppNavHost. We bundle
            // librpcsx-android.so in jniLibs and default to it, so a fresh
            // install is immediately usable and works offline.
            val nativeLibraryDir =
                packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir
            RPCSX.nativeLibDirectory = nativeLibraryDir

            var rpcsxLibrary = GeneralSettings["rpcsx_library"] as? String
            if (rpcsxLibrary == null) {
                val bundled = File(nativeLibraryDir, "libarmsx3-core.so")
                if (bundled.exists()) {
                    rpcsxLibrary = bundled.absolutePath
                    GeneralSettings["rpcsx_library"] = rpcsxLibrary
                    GeneralSettings.sync()
                }
            }

            val rpcsxUpdateStatus = GeneralSettings["rpcsx_update_status"]
            val rpcsxPrevLibrary = GeneralSettings["rpcsx_prev_library"] as? String

            if (rpcsxLibrary != null) {
                if (rpcsxUpdateStatus == false && rpcsxPrevLibrary != null) {
                    GeneralSettings["rpcsx_library"] = rpcsxPrevLibrary
                    GeneralSettings["rpcsx_installed_arch"] = GeneralSettings["rpcsx_prev_installed_arch"]
                    GeneralSettings["rpcsx_prev_installed_arch"] = null
                    GeneralSettings["rpcsx_prev_library"] = null
                    GeneralSettings["rpcsx_bad_version"] = RpcsxUpdater.getFileVersion(File(rpcsxLibrary))
                    GeneralSettings.sync()

                    File(rpcsxLibrary).delete()
                    rpcsxLibrary = rpcsxPrevLibrary

                    AlertDialogQueue.showDialog(
                        getString(R.string.failed_to_update_rpcsx),
                        getString(R.string.failed_to_load_new_version)
                    )
                } else if (rpcsxUpdateStatus == null) {
                    GeneralSettings["rpcsx_update_status"] = false
                    GeneralSettings.sync()
                }

                RPCSX.openLibrary(rpcsxLibrary)
            }

            if (RPCSX.activeLibrary.value != null) {
                RPCSX.instance.initialize(RPCSX.rootDirectory, UserRepository.getUserFromSettings())

                // ARMSX3: ANGLE selection for the OpenGL renderer. This only sets two
                // environment variables that gl::es::egl_initialize() reads with getenv
                // in this same process, so it has to happen before the GS thread makes
                // a context - i.e. before any game boots. See AngleConfig.
                AngleConfig.apply(
                    nativeLibraryDir,
                    enabled = GeneralSettings["use_angle_opengl"] as? Boolean ?: false,
                    renderer = runCatching {
                        RPCSX.instance.settingsGet("Video@@Renderer").trim().trim('"')
                    }.getOrNull()
                )

                val gpuDriverPath = GeneralSettings["gpu_driver_path"] as? String
                val gpuDriverName = GeneralSettings["gpu_driver_name"] as? String

                if (gpuDriverPath != null && gpuDriverName != null) {
                    RPCSX.instance.setCustomDriver(gpuDriverPath, gpuDriverName, nativeLibraryDir)
                }

                lifecycleScope.launch {
                    UserRepository.load()
                }

                RPCSX.initialized = true

                thread {
                    RPCSX.instance.startMainThreadProcessor()
                }

                thread {
                    RPCSX.instance.processCompilationQueue()
                }

                GeneralSettings["rpcsx_update_status"] = true
                if (rpcsxPrevLibrary != null) {
                    if (rpcsxLibrary != rpcsxPrevLibrary) {
                        File(rpcsxPrevLibrary).delete()
                    }

                    GeneralSettings["rpcsx_prev_library"] = null
                    GeneralSettings["rpcsx_prev_installed_arch"] = null
                    GeneralSettings.sync()
                }
            }

            val updateFile = File(RPCSX.rootDirectory + "cache", "rpcsx-${BuildConfig.Version}.apk")
            if (updateFile.exists()) {
                updateFile.delete()
            }
        }

        setContent {
            RPCSXTheme {
                AppNavHost()
            }
        }

        if (RPCSX.activeLibrary.value != null) {
            unregisterUsbEventListener = listenUsbEvents(this)
        } else {
            unregisterUsbEventListener = {}
        }
    }

    // ARMSX3 audio lifecycle. LibraryMusic/PauseMusic/MenuSfx were loaded in
    // onCreate, but load() only restores persisted state -- without these hooks
    // nothing ever starts, and the whole audio layer is silent dead code.
    //
    // LibraryMusic gates itself on the emulator being stopped (see its own
    // check against RPCSX.state), so calling start() unconditionally here is
    // safe: it will not talk over a running game.
    override fun onResume() {
        super.onResume()
        MenuSfx.play(MenuSfx.Event.WAKE)
        PauseMusic.setForeground(true)
        LibraryMusic.start(this)
    }

    override fun onPause() {
        super.onPause()
        // Stop rather than duck: Android reclaims the audio device from a
        // backgrounded app anyway, and a stream left open shows up as an
        // AudioMix wake lock against our uid.
        MenuSfx.play(MenuSfx.Event.SLEEP)
        PauseMusic.setForeground(false)
        LibraryMusic.stop(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        PauseMusic.stop()
        LibraryMusic.stop(this)
        unregisterUsbEventListener()
    }
}
