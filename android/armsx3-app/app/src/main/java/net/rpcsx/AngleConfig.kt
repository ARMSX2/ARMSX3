package net.rpcsx

import android.system.Os
import android.util.Log
import java.io.File

/**
 * ARMSX3: selects ANGLE (GLES-on-Vulkan) for the OpenGL renderer.
 *
 * The whole hook is two environment variables. `gl::es::egl_initialize()` in the
 * core reads `ARMSX3_ANGLE_EGL_LIBRARY` with getenv, in this same process, and
 * dlopen()s that instead of the system libEGL. It has to be a private soname -
 * Android resolves `libEGL.so` and `libGLESv2.so` through the public-library
 * namespace, so an APK physically cannot shadow them; that is why the bundled
 * files are named libEGL_angle.so / libGLESv2_angle.so.
 *
 * Must run before the GS thread creates its context, i.e. before a game boots.
 *
 * On the diagnostics below: ARMSX2 shipped an APK whose ANGLE libraries had been
 * eaten by a blanket `*.so` gitignore rule. The core fell back to the system GLES
 * driver silently, users reported "ANGLE is broken", and no log said otherwise.
 * Three separate things now make that impossible to repeat: jniLibs/.gitignore
 * un-ignores the two files by name, the `verifyAngleLibs` Gradle task fails the
 * build if they are not in the APK, and both this class and the core log loudly
 * when ANGLE was asked for and could not be had.
 */
object AngleConfig {
    private const val TAG = "ARMSX3"
    private const val EGL_LIB = "libEGL_angle.so"
    private const val GLES_LIB = "libGLESv2_angle.so"

    const val ENV_EGL = "ARMSX3_ANGLE_EGL_LIBRARY"
    const val ENV_GLES = "ARMSX3_ANGLE_GLES_LIBRARY"

    /** True when both ANGLE libraries are actually present in the APK. */
    fun isAvailable(nativeLibraryDir: String): Boolean =
        File(nativeLibraryDir, EGL_LIB).isFile && File(nativeLibraryDir, GLES_LIB).isFile

    /**
     * @param enabled  the user's "use ANGLE for OpenGL" setting
     * @param renderer the selected renderer; ANGLE only affects the OpenGL one
     */
    fun apply(nativeLibraryDir: String, enabled: Boolean, renderer: String?) {
        val wanted = enabled && renderer.equals("opengl", ignoreCase = true)

        if (!wanted) {
            runCatching { Os.unsetenv(ENV_EGL) }
            runCatching { Os.unsetenv(ENV_GLES) }
            Log.i(TAG, "@@ANGLE@@ off (renderer=$renderer enabled=$enabled)")
            return
        }

        val egl = File(nativeLibraryDir, EGL_LIB)
        val gles = File(nativeLibraryDir, GLES_LIB)

        if (!egl.isFile || !gles.isFile) {
            // Distinguish "the user never picked ANGLE" from "the user picked ANGLE
            // and the APK does not contain it". Only the second is a bug, and it is
            // invisible without this line.
            runCatching { Os.unsetenv(ENV_EGL) }
            runCatching { Os.unsetenv(ENV_GLES) }
            Log.e(
                TAG,
                "@@ANGLE@@ MISSING_LIBS dir=$nativeLibraryDir egl=${egl.isFile} gles=${gles.isFile} " +
                    "-> falling back to the system GLES driver"
            )
            return
        }

        runCatching {
            Os.setenv(ENV_EGL, egl.absolutePath, true)
            Os.setenv(ENV_GLES, gles.absolutePath, true)
            Log.i(TAG, "@@ANGLE@@ enabled egl=${egl.absolutePath}")
        }.onFailure {
            Log.e(TAG, "@@ANGLE@@ error ${it.javaClass.simpleName}: ${it.message}")
        }
    }
}
