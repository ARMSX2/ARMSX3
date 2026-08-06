package net.rpcsx

import android.content.Intent
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
 * Boot splash: plays the bundled ARMSX3 intro video (res/raw/boot_intro.mp4) once per
 * process, then hands off to Main. Tapping, the Back button, a hard timeout, and any
 * playback error all fall through to the app so a bad codec or slow decode never
 * strands the user on a black screen. The splash is opt-out via the "ui.bootLogo"
 * preference (App settings, default on) — when disabled it launches Main immediately.
 */
class BootSplashActivity : ComponentActivity() {
    private var launchedMain = false
    private var rootView: View? = null
    private var player: MediaPlayer? = null
    private val timeoutRunnable = Runnable { launchMainAndFinish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The manifest theme (Theme.ARMSX3.Boot) already paints the window black,
        // matching the video's black FrameLayout — no per-theme override, so a
        // light-mode device never flashes white before the first decoded frame.
        super.onCreate(savedInstanceState)
        applyImmersiveUi()

        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val bootLogoEnabled = prefs.getBoolean("ui.bootLogo", true)
        if (!bootLogoEnabled || playedThisProcess) {
            launchMainAndFinish()
            return
        }
        playedThisProcess = true

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = launchMainAndFinish()
        })

        setContentView(R.layout.activity_boot_splash)
        rootView = findViewById(R.id.boot_splash_root)
        val textureView = findViewById<TextureView?>(R.id.boot_splash_video)
        rootView?.apply {
            setOnClickListener { launchMainAndFinish() }
            postDelayed(timeoutRunnable, HARD_TIMEOUT_MS)
        }

        if (textureView == null) {
            launchMainAndFinish()
            return
        }

        textureView.setOnClickListener { launchMainAndFinish() }
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                startPlayback(textureView, Surface(st), width, height)
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
                applyCenterCrop(textureView, width, height)
            }

            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                releasePlayer()
                return true
            }

            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }

    private fun startPlayback(view: TextureView, surface: Surface, width: Int, height: Int) {
        try {
            player = MediaPlayer().apply {
                setDataSource(
                    this@BootSplashActivity,
                    Uri.parse("android.resource://$packageName/${R.raw.boot_intro}")
                )
                setSurface(surface)
                isLooping = false
                setOnPreparedListener {
                    applyCenterCrop(view, width, height)
                    start()
                }
                setOnCompletionListener { launchMainAndFinish() }
                setOnErrorListener { _, _, _ ->
                    launchMainAndFinish()
                    true
                }
                prepareAsync()
            }
        } catch (_: Exception) {
            // Bad codec, missing resource, anything: never strand on black.
            launchMainAndFinish()
        }
    }

    /**
     * Scale the video to FILL the view, cropping the overflow.
     *
     * The intro is 1080x1080. A fit-inside policy (which is all VideoView can
     * do) pillarboxes it on every non-square screen - that was the black bars
     * either side of the logo. Scaling by the LARGER of the two ratios fills the
     * display instead and crops the excess, and since the mark is centred in the
     * frame it survives the crop.
     */
    private fun applyCenterCrop(view: TextureView, viewWidth: Int, viewHeight: Int) {
        val mp = player ?: return
        val videoWidth = mp.videoWidth.takeIf { it > 0 } ?: return
        val videoHeight = mp.videoHeight.takeIf { it > 0 } ?: return
        if (viewWidth <= 0 || viewHeight <= 0) return

        val scale = maxOf(
            viewWidth.toFloat() / videoWidth,
            viewHeight.toFloat() / videoHeight
        )

        val scaledWidth = videoWidth * scale
        val scaledHeight = videoHeight * scale

        // TextureView stretches its content to the view box by default, so the
        // matrix is expressed relative to that: undo the stretch, then apply our
        // own uniform scale about the centre.
        Matrix().apply {
            setScale(
                scaledWidth / viewWidth,
                scaledHeight / viewHeight,
                viewWidth / 2f,
                viewHeight / 2f
            )
            view.setTransform(this)
        }
    }

    private fun releasePlayer() {
        player?.runCatching {
            if (isPlaying) stop()
            release()
        }
        player = null
    }

    override fun onDestroy() {
        releasePlayer()
        rootView?.removeCallbacks(timeoutRunnable)
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

    private fun launchMainAndFinish() {
        if (launchedMain) return
        launchedMain = true
        rootView?.removeCallbacks(timeoutRunnable)
        val launch = Intent(this, MainActivity::class.java)
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
    }
}
