package com.armsx2.ui.home

import android.content.Context
import android.graphics.SurfaceTexture
import android.util.Log
import android.view.TextureView
import java.io.File
import java.io.RandomAccessFile

/**
 * A dynamic PS3 theme played live as the library background: its [ThemeScene], moved by the theme's
 * script and drawn by [ThemeSceneRenderer] on a thread of its own, in the same TextureView shell as
 * [XmbGlView]. [scene] is the theme's unpacked scene, saved at import.
 *
 * Not opaque: until the first frame, and for good if the scene or GL cannot come up, the theme's
 * still behind it shows through. Once it draws, every frame covers the view.
 */
class ThemeSceneView(context: Context, private val scene: File) : TextureView(context), TextureView.SurfaceTextureListener {
    private var thread: RenderThread? = null

    /** True once a frame has presented, false when the scene or GL could not start. Main thread. */
    var onGlStatus: ((Boolean) -> Unit)? = null

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        val hz = display?.refreshRate ?: 60f
        thread = RenderThread(st, w, h, scene, hz) { ok -> post { onGlStatus?.invoke(ok) } }.also {
            it.paused = windowVisibility != VISIBLE
            it.start()
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        thread?.resize(w, h)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        stop()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    /** Hold the scene still while the app is out of sight, rather than drawing frames no one sees. */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        thread?.paused = visibility != VISIBLE
    }

    /** Stop drawing now, without waiting for the surface to go (see [SaverGlView.stop]). */
    fun stop() {
        thread?.finish()
        thread = null
    }

    private class RenderThread(
        private val surfaceTexture: SurfaceTexture,
        @Volatile private var width: Int,
        @Volatile private var height: Int,
        private val file: File,
        private val refreshHz: Float,
        private val onStatus: (Boolean) -> Unit,
    ) : Thread("theme-scene") {
        @Volatile private var running = true
        @Volatile var paused = false

        fun resize(w: Int, h: Int) {
            width = w
            height = h
        }

        fun finish() {
            running = false
            interrupt()
            runCatching { join(JOIN_MS) }
        }

        override fun run() {
            var shown = false
            val egl = SceneEgl()
            try {
                RandomAccessFile(file, "r").use { raf ->
                    val bytes = LibraryBackground.ChannelBytes(raf.channel, raf.length())
                    val scene = RafScene.read(bytes) ?: return
                    val play = ThemeScene(scene)
                    if (!egl.create(surfaceTexture, width, height)) return
                    // About 60 frames a second, what the scripts are written for, on faster panels too.
                    egl.swapInterval((refreshHz / 55f).toInt().coerceIn(1, 4))
                    val renderer = ThemeSceneRenderer(play, bytes)
                    try {
                        renderer.init()
                        var last = System.nanoTime()
                        while (running) {
                            if (paused) {
                                sleep(PAUSED_POLL_MS)
                                last = System.nanoTime()
                                continue
                            }
                            val now = System.nanoTime()
                            play.advance((now - last) / 1e9)
                            last = now
                            renderer.draw(width, height)
                            if (!egl.swap()) break
                            if (!shown) {
                                shown = true
                                onStatus(true)
                            }
                        }
                    } finally {
                        renderer.release()
                        play.scriptError?.let { Log.i(TAG, "script stopped: $it") }
                    }
                }
            } catch (_: InterruptedException) {
                // finish(): leaving anyway.
            } catch (t: Throwable) {
                // Out of memory, a GL failure, a scene that reads wrong: the still stays up instead.
                if (running) Log.w(TAG, "theme scene stopped", t)
            } finally {
                egl.destroy()
                if (!shown) onStatus(false)
            }
        }
    }

    private companion object {
        const val TAG = "ThemeScene"
        const val PAUSED_POLL_MS = 100L
        const val JOIN_MS = 500L
    }
}
