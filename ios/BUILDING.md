# Building ARMSX3 for iOS

These steps build the iOS app from a fresh checkout into an IPA you can sideload. Every command runs from the repository root. The build happens in three parts: the dependencies, the emulator core, and the app itself.

## What you need

A Mac with Xcode and its iOS SDK, and these tools on your PATH:

- CMake and Ninja, for the core and LLVM.
- XcodeGen, which writes the Xcode project from ios/project.yml.
- Rust through rustup, with the aarch64-apple-ios target, for librashader and the JIT enabler.
- git and curl.

With Homebrew and rustup that is:

```sh
brew install cmake ninja xcodegen
rustup target add aarch64-apple-ios
```

The LLVM build is the slow part and needs a lot of free disk space. It only has to run once.

## Getting the source

Clone the repository without its submodules, then fetch only the ones the iOS build uses. The `--with-llvm` flag adds the LLVM checkout, which the default configuration needs.

```sh
git clone https://github.com/ARMSX2/ARMSX3.git
cd ARMSX3
bash ios/scripts/bootstrap-submodules.sh --with-llvm
```

## Dependencies

LLVM, FFmpeg and librashader are built for iOS outside the main build, and MoltenVK is a pinned release download. All four land in ios/build-deps or ios/3rdparty, which git ignores. FFmpeg's source, librashader's source and crates, MoltenVK and the JIT enabler's sources come from the network, each checked against a pinned hash or commit.

```sh
bash ios/scripts/build-llvm-ios.sh
bash ios/scripts/build-ffmpeg-ios.sh
bash ios/scripts/build-librashader-ios.sh
bash ios/scripts/fetch-moltenvk.sh
```

The JIT enabler needs its vendored sources and a Rust library, idevice-ffi. Fetch the first, then build the second:

```sh
bash ios/jit-enabler/fetch-sources.sh
bash ios/jit-enabler/build-idevice.sh
```

The LLVM, FFmpeg and MoltenVK scripts keep a stamp of what they built, so running them again does nothing unless the pin changed.

## The emulator core

Configure the core, build its three libraries, then merge every archive they produced into the single one the app links. The first configure downloads Abseil from GitHub once, because no Abseil is installed for iOS for it to find.

```sh
bash ios/configure.sh ios-llvm
cmake --build ios/build/ios-llvm --target rpcs3_emu armsx3_bridge armsx3_ios_render -- -j 8
bash ios/scripts/collect-ios-archives.sh ios-llvm
```

The build tree is ios/build/ios-llvm, and the merged archive is ios/build/ios-llvm/libarmsx3_ios_core.a. After you change the core, run the last two commands again. Run configure again only when a CMake file or ios/configure.sh changed.

## The IPA

For an IPA that testers re-sign with AltStore or SideStore, build with `SIGN=0`:

```sh
SIGN=0 bash ios/scripts/build-ios-ipa.sh
```

The script generates the Xcode project, builds the app in Release, signs it ad hoc so the re-signer keeps its entitlements, and checks the result. It never installs anything on a device. Its last line starts with `@@IPA@@` and gives the IPA's path, which is ios/build/ipa/ARMSX3-Release-ios-llvm-unsigned.ipa.

If you have your own Apple developer team, leave `SIGN` unset or set it to 1. The script then signs with the team from your keychain, or with `TEAM_ID` if you set it, and writes ios/build/ipa/ARMSX3-Release-ios-llvm.ipa. `CONFIGURATION=Debug` builds a Debug app instead.

To work in Xcode, `bash ios/scripts/generate.sh` writes ios/ARMSX3.xcodeproj without building it. The project is generated, so edit ios/project.yml rather than the project.

## The interpreter-only core

There is also an `ios-nollvm` configuration without LLVM, built by using `ios-nollvm` in place of `ios-llvm` in the core commands and passing `ARMSX3_CMAKE_CONFIG=ios-nollvm` to build-ios-ipa.sh; it still needs JIT.
