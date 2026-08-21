package com.armsx2.ui.home

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.Log
import android.view.TextureView

/**
 * Calum Robinson's Flurry (2002) as a library background.
 *
 * The renderer is his, built as libflurry.so; this is the same TextureView + EGL shell
 * [XmbGlView] uses, so the two backgrounds behave identically to HomeScreen -- including
 * reporting through [onGlStatus] so a device that cannot bring GL up falls back to the 2D
 * backdrop instead of showing a hole.
 *
 * Flurry is BSD-3-clause; see app/src/main/cpp/flurry for the notice and for what the GLES2
 * compatibility layer does and does not emulate.
 */
class FlurryGlView(context: Context, private val preset: Int) :
    TextureView(context), TextureView.SurfaceTextureListener {

    private var thread: RenderThread? = null

    /** True once a frame has presented, false if EGL or Flurry's own init failed. Main thread. */
    var onGlStatus: ((Boolean) -> Unit)? = null

    init {
        surfaceTextureListener = this
        // Same reasoning as XmbGlView: if nothing ever renders, show what is behind rather than
        // a black rectangle.
        isOpaque = false
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        thread = RenderThread(st, w, h, preset) { ok -> post { onGlStatus?.invoke(ok) } }
            .also { it.start() }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
        thread?.resize(w, h)
    }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        thread?.finish()
        thread = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

    private class RenderThread(
        private val surfaceTexture: SurfaceTexture,
        private var width: Int,
        private var height: Int,
        private val preset: Int,
        private val onStatus: (Boolean) -> Unit,
    ) : Thread("flurry-gl") {

        @Volatile private var running = true
        @Volatile private var sizeDirty = true

        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var handle = 0L

        fun resize(w: Int, h: Int) { width = w; height = h; sizeDirty = true }
        fun finish() { running = false; runCatching { join(500) } }

        override fun run() {
            if (!initEgl()) { onStatus(false); teardown(); return }

            handle = runCatching { FlurryNative.nativeCreate(preset) }.getOrDefault(0L)
            if (handle == 0L) {
                Log.w(TAG, "Flurry failed to start")
                onStatus(false); teardown(); return
            }

            var announced = false
            while (running) {
                val frameStart = System.nanoTime()

                if (sizeDirty) {
                    FlurryNative.nativeResize(handle, width, height)
                    sizeDirty = false
                }

                runCatching { FlurryNative.nativeDraw(handle) }

                if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) running = false
                else if (!announced) { announced = true; onStatus(true) }

                // Flurry already refuses to advance faster than 60fps -- above that its additive
                // blending saturates into a white smear -- but without a cap here the loop would
                // still spin at panel rate and do the swap anyway. Same reason XmbGlView caps
                // itself: on a handheld that difference is audible in the fans.
                val leftMs = FRAME_TARGET_MS - (System.nanoTime() - frameStart) / 1_000_000L
                if (leftMs > 1) runCatching { sleep(leftMs) }
            }

            if (handle != 0L) runCatching { FlurryNative.nativeDestroy(handle) }
            handle = 0L
            teardown()
        }

        private fun initEgl(): Boolean {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) return false

            val ver = IntArray(2)
            if (!EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1)) return false

            val attribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                // No depth buffer: Flurry is 2D additive smoke, and asking for one on a tiler
                // costs bandwidth for something never read.
                EGL14.EGL_DEPTH_SIZE, 0,
                EGL14.EGL_NONE,
            )

            val cfg = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, cfg, 0, 1, num, 0) || num[0] == 0) return false

            val ctxAttr = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            eglContext = EGL14.eglCreateContext(eglDisplay, cfg[0], EGL14.EGL_NO_CONTEXT, ctxAttr, 0)
            if (eglContext == EGL14.EGL_NO_CONTEXT) return false

            eglSurface = EGL14.eglCreateWindowSurface(
                eglDisplay, cfg[0], surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0,
            )
            if (eglSurface == EGL14.EGL_NO_SURFACE) return false

            return EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }

        private fun teardown() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                )
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                EGL14.eglTerminate(eglDisplay)
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }
    }

    companion object {
        private const val TAG = "FlurryGlView"
        private const val FRAME_TARGET_MS = 16L
    }
}

/** libflurry.so. Every call needs the EGL context current; RenderThread is the only caller. */
internal object FlurryNative {
    init { System.loadLibrary("flurry") }

    external fun nativeCreate(preset: Int): Long
    external fun nativeCreateCustom(
        streams: Int, colour: Int, thickness: Float, speed: Float, brightness: Float,
    ): Long
    external fun nativeResize(handle: Long, width: Int, height: Int)
    external fun nativeDraw(handle: Long): Boolean
    external fun nativeDestroy(handle: Long)
    external fun nativeContextLost(handle: Long)
}
