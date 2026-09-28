package com.armsx2

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Boot splash: plays the bundled ARMSX2 intro video (res/raw/boot_intro.mp4), or the user's
 * own from [BootIntro], once per process, then hands off to Main. Tapping, the Back button, a
 * hard timeout, and any playback error all fall through to the app so a bad codec or slow
 * decode never strands the user on a black screen. The splash is opt-out via the "ui.bootLogo"
 * preference (App settings, default on) — when disabled it launches Main immediately.
 *
 * Started with [BootIntro.EXTRA_PREVIEW] it is the Preview button in App settings instead:
 * it plays regardless of the toggle and the once-per-process rule, then just closes.
 */
class BootSplashActivity : ComponentActivity() {
    private var leaving = false
    private var preview = false
    private var fillScreen = true
    private var rootView: View? = null
    private var player: MediaPlayer? = null
    private val timeoutRunnable = Runnable { leave() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest theme (Theme.ARMSX2.Boot) already paints the window black,
        // matching the video's black FrameLayout — no per-theme override, so a
        // light-mode device never flashes white before the first decoded frame.
        super.onCreate(savedInstanceState)
        applyImmersiveUi()

        preview = intent?.getBooleanExtra(BootIntro.EXTRA_PREVIEW, false) == true
        val prefs = getSharedPreferences("ARMSX2", MODE_PRIVATE)
        val bootLogoEnabled = prefs.getBoolean("ui.bootLogo", true)
        if (!preview && (!bootLogoEnabled || playedThisProcess)) {
            leave()
            return
        }
        if (!preview) playedThisProcess = true

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        setContentView(R.layout.activity_boot_splash)
        rootView = findViewById(R.id.boot_splash_root)
        val textureView = findViewById<TextureView?>(R.id.boot_splash_video)
        rootView?.apply {
            setOnClickListener { leave() }
            postDelayed(timeoutRunnable, HARD_TIMEOUT_MS)
        }
        if (textureView == null) {
            leave()
            return
        }

        textureView.setOnClickListener { leave() }
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                startPlayback(textureView, Surface(st), width, height)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                applyScale(textureView, width, height)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun startPlayback(view: TextureView, surface: Surface, width: Int, height: Int) {
        val custom = BootIntro.installed(this)
        // The bundled intro is square with the mark centred, so it is cropped to fill. A user's
        // video is shown whole: its edges may carry text, and on a black splash the bars vanish.
        fillScreen = custom == null
        try {
            player = MediaPlayer().apply {
                // The intro has an AAC track and it is meant to be heard. Declare
                // the usage explicitly rather than relying on MediaPlayer's default
                // stream, and take transient audio focus so whatever the user had
                // playing ducks for the ~3s sting instead of both running at once.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()
                )
                if (custom != null) {
                    setDataSource(custom.absolutePath)
                } else {
                    setDataSource(
                        this@BootSplashActivity,
                        Uri.parse("android.resource://$packageName/${R.raw.boot_intro}"),
                    )
                }
                setSurface(surface)
                setVolume(1f, 1f)
                isLooping = false
                setOnPreparedListener {
                    applyScale(view, width, height)
                    extendTimeout(it.duration)
                    requestAudioFocus()
                    start()
                }
                setOnCompletionListener { leave() }
                setOnErrorListener { _, _, _ ->
                    leave()
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            // Bad codec, missing resource, anything: never strand on black.
            leave()
        }
    }

    /**
     * Scale the bundled video to FILL the view, cropping the overflow, or fit a user's
     * video inside it (see [fillScreen]).
     *
     * The intro is square. A fit-inside policy (which is all VideoView can do)
     * pillarboxes it on every non-square screen — that was the black bars either
     * side of the logo. Scaling by the LARGER of the two ratios fills the display
     * and crops the excess instead, and since the mark is centred in the frame it
     * survives the crop.
     */
    private fun applyScale(view: TextureView, viewWidth: Int, viewHeight: Int) {
        val mp = player ?: return
        val videoWidth = mp.videoWidth.takeIf { it > 0 } ?: return
        val videoHeight = mp.videoHeight.takeIf { it > 0 } ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return

        val widthRatio = viewWidth.toFloat() / videoWidth
        val heightRatio = viewHeight.toFloat() / videoHeight
        val scale = if (fillScreen) maxOf(widthRatio, heightRatio) else minOf(widthRatio, heightRatio)

        // TextureView stretches its content to the view box by default, so the
        // matrix is expressed relative to that: undo the stretch, then apply our
        // own uniform scale about the centre.
        Matrix().apply {
            setScale(
                videoWidth * scale / viewWidth,
                videoHeight * scale / viewHeight,
                viewWidth / 2f,
                viewHeight / 2f,
            )
            view.setTransform(this)
        }
    }

    /**
     * The hard timeout was sized for the bundled ~3s sting, and a user's intro can run longer.
     * Once the real length is known the deadline moves to just past the end of the video, so
     * a stalled decoder still falls through to the app. Tapping and Back skip as before.
     */
    private fun extendTimeout(durationMs: Int) {
        if (durationMs <= 0) return
        rootView?.apply {
            removeCallbacks(timeoutRunnable)
            postDelayed(timeoutRunnable, durationMs + COMPLETION_GRACE_MS)
        }
    }

    @Suppress("DEPRECATION")
    private fun requestAudioFocus() {
        runCatching {
            val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
            am.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun releasePlayer() {
        player?.runCatching { stop() }
        player?.release()
        player = null
    }

    // Hand focus back. A Preview plays over the library, and library music pauses on the
    // transient loss and resumes only when focus returns, so a splash that kept it would
    // leave the music paused.
    @Suppress("DEPRECATION")
    private fun abandonAudioFocus() {
        runCatching {
            (getSystemService(AUDIO_SERVICE) as? AudioManager)?.abandonAudioFocus(null)
        }
    }

    override fun onDestroy() {
        rootView?.removeCallbacks(timeoutRunnable)
        releasePlayer()
        abandonAudioFocus()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveUi()
    }

    private fun applyImmersiveUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun leave() {
        if (leaving) return
        leaving = true
        rootView?.removeCallbacks(timeoutRunnable)
        if (preview) {
            // Main is already running underneath; closing returns to App settings.
            finish()
            return
        }
        val launch = Intent(this, Main::class.java)
        intent?.let { source ->
            launch.action = source.action
            if (source.data != null || source.type != null) launch.setDataAndType(source.data, source.type)
            source.categories?.forEach(launch::addCategory)
            source.extras?.let(launch::putExtras)
            source.clipData?.let(launch::setClipData)
            launch.addFlags(
                source.flags and (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                    ),
            )
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(launch)
        finish()
        // overrideActivityTransition is API 34 (Android 14); on 13 and below it
        // throws NoSuchMethodError (crashed the splash on the Retroid). Fall back to
        // the deprecated overridePendingTransition there.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private companion object {
        var playedThisProcess = false
        const val HARD_TIMEOUT_MS = 6000L
        const val COMPLETION_GRACE_MS = 2000L
    }
}
