# Prebuilt native libraries

Not built by Gradle. Gradle only builds the JNI glue (`src/main/cpp`).

| file | origin | why it is here |
|---|---|---|
| `libarmsx3-core.so` | `android/configure.sh` + `ninja rpcsx-android`, then `llvm-strip --strip-unneeded` | The emulator. Needs LLVM and a long build, so it is staged rather than built on every Gradle sync. |
| `librashader.so` | librashader release, arm64-v8a | RetroArch `.slangp` shader chains. MPL-2.0, kept as its own `.so` so nothing MPL links into the GPL-2.0 core. |
| `libc++_shared.so` | NDK 29 sysroot | **`librashader.so` links against it.** Without it librashader fails to `dlopen` and the shader chain silently does nothing — the core and the glue are both `c++_static` and do not need it themselves, which is why its absence is easy to miss. |
