# Prebuilt native libraries

Not built by Gradle. Gradle only builds the JNI glue (`src/main/cpp`).

| file | origin | why it is here |
|---|---|---|
| `libarmsx3-core.so` | `android/configure.sh` + `ninja rpcsx-android`, then `llvm-strip --strip-unneeded` | The emulator. Needs LLVM and a long build, so it is staged rather than built on every Gradle sync. |
| `librashader.so` | librashader release, arm64-v8a | RetroArch `.slangp` shader chains. MPL-2.0, kept as its own `.so` so nothing MPL links into the GPL-2.0 core. |
| `libc++_shared.so` | NDK 29 sysroot | **`librashader.so` links against it.** Without it librashader fails to `dlopen` and the shader chain silently does nothing — the core and the glue are both `c++_static` and do not need it themselves, which is why its absence is easy to miss. |
| `libEGL_angle.so`, `libGLESv2_angle.so` | ANGLE prebuilts, arm64-v8a (BSD-3-Clause) | The "ANGLE" choice under the OpenGL renderer's GL driver section. `MainActivityRuntime.applyAngleEnv` points `ARMSX2_ANGLE_EGL_LIBRARY` / `ARMSX2_ANGLE_GLES_LIBRARY` at these by absolute path in `nativeLibraryDir`. **Unlike the rest of this table they are tracked in git** — they are redistributable prebuilts, not build output, so `../.gitignore` un-ignores them by name and `verifyAngleLibs` in `app/build.gradle.kts` fails the build if they go missing. |
