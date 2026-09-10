package com.armsx2

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20

/**
 * Detects the physical GPU's GL_RENDERER string (e.g. "Adreno (TM) 740",
 * "Mali-G715", "PowerVR ...") via a throwaway offscreen GLES2 context, and maps
 * Adreno models to a recommended in-app driver source — the "driver update
 * assistant" idea (recommend a Turnip pack by GPU, like Eden).
 *
 * The probe reads the SYSTEM GL driver (not a loaded adrenotools Vulkan driver),
 * which is exactly what we want to key the recommendation on. Result is cached.
 */
object GpuInfo {
    @Volatile private var cached: String? = null
    @Volatile private var probed = false

    /** GL_RENDERER of the system GPU, or null if it couldn't be read. Cached. */
    fun rendererName(): String? {
        if (probed) return cached
        synchronized(this) {
            if (probed) return cached
            cached = runCatching { probe() }.getOrNull()?.takeIf { it.isNotBlank() }
            probed = true
            return cached
        }
    }

    private fun probe(): String? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val ver = IntArray(2)
        if (!EGL14.eglInitialize(display, ver, 0, ver, 1)) return null
        try {
            val cfgAttrs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val cfgs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            if (!EGL14.eglChooseConfig(display, cfgAttrs, 0, cfgs, 0, 1, num, 0) || num[0] == 0) return null
            val ctxAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val ctx = EGL14.eglCreateContext(display, cfgs[0], EGL14.EGL_NO_CONTEXT, ctxAttrs, 0)
            if (ctx == EGL14.EGL_NO_CONTEXT) return null
            val pbAttrs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            val surf = EGL14.eglCreatePbufferSurface(display, cfgs[0], pbAttrs, 0)
            try {
                if (!EGL14.eglMakeCurrent(display, surf, surf, ctx)) return null
                return GLES20.glGetString(GLES20.GL_RENDERER)
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surf)
                EGL14.eglDestroyContext(display, ctx)
            }
        } finally {
            EGL14.eglTerminate(display)
        }
    }

    data class Recommendation(val sourceLabel: String, val reason: String)

    /**
     * Suggested driver source for [renderer], or null when no custom driver
     * applies (custom Turnip packs are Adreno-only; Mali/Xclipse/PowerVR run the
     * built-in system driver). [sourceLabel] should match a DriverSource name in
     * CustomDriver.SOURCES so the user can find it in the list -- it is displayed
     * verbatim. (It previously said it matched friendlyDriverSource(), which does
     * not exist anywhere in the tree.)
     */
    fun recommendation(renderer: String?): Recommendation? {
        val r = renderer ?: return null
        if (!r.contains("Adreno", ignoreCase = true)) return null
        val model = Regex("""(\d{3,4})""").find(r.substringAfter("Adreno", ""))?.value?.toIntOrNull()
        return when {
            // Adreno 8xx: StevenMXZ's gen8 Turnip builds, v35 or newer.
            //
            // Measured on a Snapdragon 8 Elite / Adreno 830 against Batman: Arkham City, which
            // reproducibly lost the device mid-scene on both the stock Qualcomm blob and gen8 v34
            // (QueueSignalReleaseImageANDROID -4 -> VK_ERROR_DEVICE_LOST). v35 clears the same
            // scene with none of it. Same build, same config, only the driver changed.
            //
            // Deliberately does NOT single out a version. An earlier draft said "avoid v34: device
            // loss on 830", which generalised ONE title's behaviour (Batman: Arkham City) into a
            // device-wide verdict -- the same tester played two hours of Web of Shadows on v34
            // without trouble, and the Arkham device loss was later traced to our own command
            // stream rather than to the driver version. Recommend the source; let the user pick.
            //
            // Worth knowing when triaging: gen8 v34 and v35 both report driver "26.2.99" and are
            // distinguishable only by the Mesa git hash in the "Vulkan driver identity" log line.
            model != null && model >= 800 -> Recommendation("StevenMXZ · Adreno-Tools",
                "Turnip gen8 builds for Snapdragon 8 Elite / Adreno 8xx.")
            model != null && model >= 700 -> Recommendation("MrPurple · purple-turnip", "purple-turnip builds for Adreno 7xx")
            model != null && model in 600..699 -> Recommendation("AdrenoToolsDrivers", "AdrenoTools Turnip for Adreno 6xx")
            else -> Recommendation("AdrenoToolsDrivers", "AdrenoTools Turnip (Adreno)")
        }
    }
}
