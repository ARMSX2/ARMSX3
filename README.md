ARMSX3
======

Uses the latest RPCS3 upstream code (the recent ARM64 improvements included). 


Building
--------

 arm64-v8a and armv8.2 is supported. You need the Android SDK with NDK r27 or newer,
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
    cmake --build build-android --target armsx3-core -j8

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

Legal
-----

ARMSX3 includes no PS3 firmware, games or other files of Sony's. Install your own
copy of the PS3 firmware and play games you own.

Installing the firmware decrypts Sony's official update and unpacks the console's
internal flash (dev_flash) into ARMSX3's data folder, as RPCS3 does on every platform.
Boot XMB runs the PS3's own system software from there. XMB Mode runs none of Sony's
code: it is ARMSX3's own interface, which reads the PS3's words, icons, fonts, startup
sound and the settings behind its wave from that firmware install. None of those
files ship with ARMSX3.

ARMSX3 is not affiliated with, endorsed by or sponsored by Sony Interactive
Entertainment. PlayStation, PS3 and XMB (XrossMediaBar) are trademarks or registered
trademarks of Sony Interactive Entertainment Inc., used here only to say what ARMSX3
is compatible with.

License
-------

GPL-2.0-only, the same as RPCS3. See LICENSE. Some files may be licensed
differently, check the file headers.

Based on RPCS3, https://github.com/RPCS3/rpcs3
