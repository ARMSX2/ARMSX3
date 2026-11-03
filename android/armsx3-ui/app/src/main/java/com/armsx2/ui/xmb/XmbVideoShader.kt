package com.armsx2.ui.xmb

import android.view.Surface

/**
 * RetroArch (.slangp) shaders on a video (libxmbvideo, src/main/cpp/xmbvideo). The decoder writes
 * into a stage's [Stage.input] instead of the screen, and the stage draws each frame through the
 * picked preset onto the player's own surface: the system's Vulkan, and the librashader.so games
 * use. [open] gives null where either is missing, and the video plays as it does without.
 */
internal object XmbVideoShader {
    private val loaded = runCatching { System.loadLibrary("xmbvideo") }.isSuccess
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** A video's shader stage while it is open, drawing onto [output] from what reaches [input]. */
    class Stage internal constructor(private var handle: Long, val input: Surface, val output: Surface, val rgba: Boolean) {
        /** True once the stage cannot go on (no Vulkan, a preset that will not build or run). */
        val failed: Boolean get() = handle == 0L || nativeFailed(handle)

        fun preset(path: String, params: Map<String, Float>) {
            if (handle != 0L) nativePreset(handle, path, params.keys.toTypedArray(), params.values.toFloatArray())
        }

        /** The turn a video's frames need to stand upright, in degrees clockwise. */
        fun rotation(degrees: Int) {
            if (handle != 0L) nativeRotation(handle, degrees)
        }

        /** The last frame drawn again, for a surface that changed size with the video paused. */
        fun redraw() {
            if (handle != 0L) nativeRedraw(handle)
        }

        /**
         * Its thread stopped and its Vulkan let go, off the main thread (a preset still being built
         * finishes first); [onClosed] on the main thread once it no longer draws onto [output]. The
         * decoder must be off [input] before.
         */
        fun close(onClosed: () -> Unit = {}) {
            val h = handle
            handle = 0L
            if (h == 0L) {
                onClosed()
                return
            }
            Thread({
                nativeClose(h)
                input.release()
                main.post(onClosed)
            }, "xmb-video-shader-close").start()
        }
    }

    /**
     * A stage drawing onto [output] through [preset], [params] its settings as the game settings
     * tune it. [rgba] for the core's FFmpeg player, which writes RGBA a frame at a time; Android's
     * decoders write their own YUV.
     */
    fun open(output: Surface, preset: String, params: Map<String, Float>, rgba: Boolean, width: Int, height: Int): Stage? {
        if (!loaded) return null
        val h = runCatching { nativeOpen(output, rgba, width, height) }.getOrDefault(0L)
        if (h == 0L) return null
        val input = nativeInput(h)
        if (input == null) {
            nativeClose(h)
            return null
        }
        return Stage(h, input, output, rgba).also { it.preset(preset, params) }
    }

    @JvmStatic private external fun nativeOpen(output: Surface, rgba: Boolean, width: Int, height: Int): Long
    @JvmStatic private external fun nativeInput(handle: Long): Surface?
    @JvmStatic private external fun nativePreset(handle: Long, path: String, names: Array<String>, values: FloatArray)
    @JvmStatic private external fun nativeRotation(handle: Long, degrees: Int)
    @JvmStatic private external fun nativeRedraw(handle: Long)
    @JvmStatic private external fun nativeFailed(handle: Long): Boolean
    @JvmStatic private external fun nativeClose(handle: Long)
}
