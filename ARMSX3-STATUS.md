# ARMSX3 — bring-up status

Session of 2026-08-05. Read this first.

## TL;DR

The native core builds against **upstream RPCS3**, not RPCSX. Our delta to upstream is
small and entirely guarded, which is the whole point: RPCSX is 1,960 commits and ~16
months behind upstream and is missing every one of whatcookie's 2026 ARM64 commits.
We get those for free and stay rebasable.

The Android app shell is forked from `rpcsx-ui-android` into `android/armsx3-app`,
rebranded, re-themed purple, and the ARMSX2 audio layer is ported in.

---

## The single most useful fact

**The app `dlopen()`s the core and resolves `_rpcsx_*` symbols via `dlsym`.**
`app/src/main/cpp/native-lib.cpp` loads a core `.so` at runtime — that is why
rpcsx-build publishes 7 `-march` variants (armv8-a … armv9.1-a) and the app picks one.

Our core exports **26/26** of the symbols the shell dlsyms. Verified compatible.
So `librpcsx-android.so` drops into the existing shell unchanged — you do not need
any UI work to get a build that boots games.

---

## Upstream files we modified (keep this list near zero)

| File | Lines | Why |
|---|---|---|
| `CMakeLists.txt` | 6 | `if(ANDROID)` include ffmpeg.cmake before 3rdparty; add `android/` subdir |
| `rpcs3/Emu/Io/pad_config_types.h` | 3 | `#ifdef __ANDROID__ virtual_pad` enum value |
| `rpcs3/Emu/Io/pad_config_types.cpp` | 3 | matching `fmt_class_string` case — **without this the value serialises as `unknown` and the handler silently never gets selected** |
| `3rdparty/CMakeLists.txt` | 6 | expose OpenAL headers on Android (see below) |
| `rpcs3/util/media_utils.cpp` | 3 | `#include "util/ffmpeg_compat.h"` |

Everything else is additive: `android/`, `rpcs3/dev/`, `rpcs3/Input/virtual_pad_handler.*`,
`rpcs3/util/ffmpeg_compat.h`.

## Traps found the hard way (do not re-derive these)

1. **NDK 29 / clang 21 is required. NDK 28.2 is clang 19.0.1 and will not build this.**
   `fmt::throw_exception` is a CTAD struct whose ctor *and* dtor are `[[noreturn]]`, not a
   function. clang 19.0.1 fails to propagate that, so every `default:` ending in it trips
   `-Werror,-Wreturn-type`. It killed 4 TUs at 2510/3123 — *after* LLVM had fully built.
   Do **not** silence it with `-Wno-error=return-type`; the code is correct.
   Cheap way to test a toolchain hypothesis without a 30-min rebuild: pull the failing
   compile line out of the ninja log, `sed` the compiler+sysroot to the other NDK, strip
   `-o/-MD/-MT/-MF`, add `-fsyntax-only`. 30 seconds instead of 30 minutes.

2. **Never make `android/` the top-level CMake project** (RPCSX does). Upstream assumes
   `CMAKE_SOURCE_DIR == repo root` in ≥4 places (`FindWolfSSL.cmake`, `FindZLIB.cmake`,
   `3rdparty/protobuf`, `3rdparty/llvm`). Configuring with `-S android` makes them all
   resolve to `android/3rdparty/...`. Configure from root; use `android/configure.sh`.

3. **Upstream pins LLVM 22.x.** RPCSX's prebuilt `llvm-android-arm64-v8a` is 20.1.3 — two
   majors behind, unusable (ORC/JIT API breaks). Must build from source.

4. **Upstream's Android path is half-finished in two places.** Its ffmpeg block is
   `if(NOT ANDROID)` but it aliases `3rdparty::ffmpeg` **unguarded**. Its OpenAL branch
   defines `WITHOUT_OPENAL=1` but exposes no include dir, while `cellMic.h` includes
   `alc.h` unconditionally (the *calls* are guarded — 7 sites in cellMic.cpp — only the
   include is not).

5. **ffmpeg**: upstream targets 7.1+ APIs (`avcodec_get_supported_config`/`AVCodecConfig`,
   const `AVChannelLayout*`). The only Android prebuilt in existence is 5.1. Stopgap is
   `rpcs3/util/ffmpeg_compat.h`, version-gated so it self-disables. **Real fix: cross-compile
   ffmpeg 7.1 for Android, then delete that header and its one `#include`.**

---

## Done

- `agents/ARMSX3` on upstream RPCS3 `652cf60bf`, branch `armsx3-bringup`,
  remotes `upstream`=RPCS3, `rpcsx`=RPCSX.
- Android layer ported: 43/55 of RPCSX's includes resolved untouched, 9 mechanical
  remaps, 3 `rx::` calls → `utils::trap()` / `rpcs3::get_version()`.
- `android/configure.sh` — reproducible configure with every cross-compile override
  documented inline (`USE_NATIVE_INSTRUCTIONS=OFF` matters: it means `-march=native`,
  which probes the *host* Mac).
- App shell forked to `android/armsx3-app`, `applicationId = com.armsx3`, name ARMSX3.
- **Purple Material3 theme** seeded from the logo, both light and dark.
- **Audio ported from ARMSX2 mono**: `MenuSfx.kt`, `LibraryMusic.kt`, `PauseMusic.kt`
  + 14 SFX wavs + pause music. `MainActivityRuntime.prefs` → `GeneralSettings.raw`
  (new accessor on the same `app_prefs` store); `EmuState` → RPCSX's `EmulatorState`.
- Your track re-encoded 320kbps/7.2MB → 128k/3.6MB as `res/raw/library_music.m4a`.
- `CoverRepository.kt` written for aldostools covers.

## Covers — measured, not assumed

`https://raw.githubusercontent.com/aldostools/Resources/main/COV/<TITLE_ID>.JPG`
Flat layout, `TITLE_ID` is exactly what PARAM.SFO gives (`BLUS30443`). Verified 200s.

- Covers are **260×300, aspect 0.866** — multiMAN style, **not** the ~0.72 PS3 retail
  sleeve ratio. Draw at `CoverRepository.COVER_ASPECT` or everything letterboxes.
- **There are no 3D covers in that repo**, so that feature is out, as you said.
- Repo is 3.1 GB — fetch per-title, never clone. `CoverRepository` caches to
  `filesDir/covers`, writes a `.miss` marker on 404 so coverless titles aren't
  re-requested forever, and downloads via `.part`+rename so an interrupted fetch
  can't leave a truncated jpg that looks valid.

## Not done — and why

- **Setup/onboarding screen.** rpcsx-ui-android has *none* (no Setup/Onboard/Welcome
  files; `startDestination = "games"`, missing firmware is just a nag dialog in
  GamesScreen). ARMSX2's `ui/onboarding/` is 1,039 lines and depends on `ArmsBackdrop`,
  `ArmsLogo`, `StatusChip`, `padFocusRing`, i18n.
  **This is not a rename job**: PS2 BIOS is a ROM file you point at; PS3 firmware is a
  PUP you *install* and decrypt into `dev_flash`. `BiosInfo` (ROMVER region byte, packed
  version) has no PS3 analogue. The RPCS3 side is `installFw(fd, progressId)` +
  `FirmwareRepository{None,Installed,Compiled}` + `utils::get_firmware_version()`.
- Shaders, controller skins, cover UI wiring, perf tab, save-state UI, touch controls
  reconcile — all mapped below, none ported yet.
- RetroAchievements — **dropped**, RA has no PS3 support.

## Port map (ARMSX2 mono → ARMSX3)

Source root: `agents/armsx2-push-staging/platforms/android/app/src/main/java/com/armsx2`

| Feature | Files |
|---|---|
| Shaders | `ShaderRepo.kt`, `ShaderParams.kt`, `ui/common/ShaderChainSection.kt`, `ShaderManagerSection.kt`, `ShaderParamsEditor.kt` |
| Controller skins | `ControllerSkinStore.kt`, `SkinRepo.kt`, `ui/settings/SkinsTab.kt` |
| Covers UI | `CoverRegionIndex.kt`, `ui/common/GameCoverArt.kt` |
| Custom drivers | `CustomDriver.kt`, `ui/common/DriverManagerSection.kt` — **reconcile, don't port**: RPCSX already has adrenotools + `GpuDriversScreen` |
| Save states | `ui/saves/SaveManagerScreen.kt`, `SaveStatePicker.kt` (core already has savestates) |
| Perf | `ui/settings/PerformanceTab.kt` |
| Touch | `ui/touch/TouchControls.kt`, `GestureLayer.kt`, `LightgunLayer.kt` — reconcile with RPCSX's `PadOverlay*` (7 files) + `OverlayEditActivity` |
| Theme | `ui/theme/Color.kt`, `Theme.kt`, `Type.kt` |
| Settings search | `ui/settingshub/SettingsSearchOverlay.kt` |

## Already free — do not rebuild these

**Core (upstream RPCS3):** Vulkan (29 files), OpenGL (18), `video_renderer{null,opengl,vulkan}`,
save states, and perf knobs incl. **Frame limit** (fps cap), **Sleep Timers Accuracy**,
**Thread Scheduler Mode**, framerate/frametime graphs.

**RPCSX UI:** touch controls (7 files), **layout editor** (5), adrenotools custom-driver
loading + driver UI (6), PS3 firmware install (5), SAF provider, USB, PPU precompile
service, surface handling.

## Open items needing you

1. **Logo** — I can't write an image from chat to disk. Drop the 512×512 at
   `~/Downloads/armsx3-logo.png` and it gets wired to launcher icon + onboarding.
2. **`kr.co.iefriends.pcsx2.NativeApp`** is imported by ARMSX2's `config/Settings.kt` —
   a *different* rights holder from you. Must be replaced before that file crosses over.
   (You confirmed you hold the rest of the ARMSX2 UI rights.)
3. **`Bin2Pbp.7z`** — that's a PS1→PSP EBOOT tool, unrelated to PS3. Left untouched;
   assumed an accidental attach.
4. **Licensing**: RPCS3 is **GPL-2.0-only** (stated in its README). ARMSX2 is GPL-3.0.
   Incompatible. Since you hold the ARMSX2 UI rights, ARMSX3's UI needs to go out under
   GPL-2.0-only to combine with the core.
