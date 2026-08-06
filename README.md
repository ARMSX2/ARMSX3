ARMSX3
======

Proof of concept Android port of RPCS3.

This is early work. A game boots and plays, but it is slow and most of it is
untested. It is not a usable emulator yet.

Status
------

Skate 3 boots, loads and reaches gameplay at roughly 20 to 30 fps on a
Snapdragon 8 Gen 2. Rendering, audio, touch controls and physical controllers
work. Almost nothing else has been tested.

Differences from upstream RPCS3
-------------------------------

Some of the fixes here are not in upstream and affect any ARM64 build, not only
Android:

* Shaders declared runtime sized arrays inside uniform blocks, which requires
  VK_EXT_shader_uniform_buffer_unsized_array. Adreno does not support that
  extension, so every game pipeline failed to compile and nothing rendered.
  Concrete array bounds are emitted when the extension is missing.

* The ARM64 SPU block verification checksum folded two thirds of every block
  through an absolute difference. That collides on the near identical job
  binaries an SPU job manager streams through the same local store address, so
  a cached block could end up running against another job's code. It sums now.

* Thread affinity was compiled out on Android, and the core had no ARM
  big.LITTLE topology, so SPU and RSX threads were never placed on the fast
  cores.

* The LLVM JIT target was pinned to cortex-a34, an in order core from 2016. It
  detects the host now.

Building
--------

Only arm64-v8a is supported. You need the Android SDK with NDK r27 or newer,
CMake 3.30 or newer, and a JDK 17. Android Studio ships all of these.

Clone with submodules, then fetch the two third party checkouts that are not
submodules:

    git clone --recursive https://github.com/ARMSX2/ARMSX3.git
    cd ARMSX3
    git clone https://github.com/SnowflakePowered/librashader 3rdparty/librashader
    git clone https://github.com/bylaws/libadrenotools android/armsx3-ui/app/src/main/cpp/libadrenotools

Build the core. This is the long part and produces an unstripped library of
around 1.3 GB:

    export ANDROID_HOME=$HOME/Library/Android/sdk
    cmake -B build-android -G Ninja \
      -DCMAKE_TOOLCHAIN_FILE=$ANDROID_HOME/ndk/<version>/build/cmake/android.toolchain.cmake \
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-31 \
      -DCMAKE_BUILD_TYPE=RelWithDebInfo
    cmake --build build-android --target rpcsx-android -j8

Strip it and put it where the app expects it:

    llvm-strip --strip-unneeded build-android/android/libarmsx3-core.so
    cp build-android/android/libarmsx3-core.so \
       android/armsx3-ui/app/src/main/jniLibs/arm64-v8a/

Then build the app:

    cd android/armsx3-ui
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
    ./gradlew :app:assembleRelease

The apk lands in app/build/outputs/apk/release/.

Note that the core library has to be rebuilt and copied again whenever anything
under rpcs3/ or android/src/ changes. Gradle does not build it for you.

The Discord Social SDK is proprietary and is not redistributed here. Get it from
Discord's developer portal and drop it in app/libs/ and
app/src/main/cpp/discord_sdk/ if you want that feature. The build skips it
otherwise.

Running it needs PS3 firmware, which is not included. 
License
-------

GPL-2.0-only, the same as RPCS3. See LICENSE. Some files may be licensed
differently, check the file headers.

Based on RPCS3, https://github.com/RPCS3/rpcs3
