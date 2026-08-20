plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// ARMSX3 UI module.
//
// ARMSX2's Compose UI running on the RPCS3 core. Deliberately much simpler than
// ARMSX2's own build file, which is replaced wholesale rather than edited:
//
//  * externalNativeBuild builds ONLY the JNI glue (src/main/cpp), which is small.
//    The emulator CORE is a prebuilt libarmsx3-core.so (upstream RPCS3 via
//    android/configure.sh) that the glue dlopen()s at runtime -- building that
//    from Gradle would drag LLVM into every sync.
//  * NO Discord SDK staging. That path requires DISCORD_SDK_DIR pointed at a
//    hand-staged directory or Kotlin will not compile at all, and it is not on
//    the critical path for standing the UI up.
//  * NO product flavors, no PGO, no dual page-size cores - all PCSX2-specific.
//
// The source package stays com.armsx2 on purpose: renaming 129 files buys
// nothing and risks silent breakage. applicationId is what identifies the app.

android {
    namespace = "com.armsx2"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.armsx3"
        // Set per variant by android/build-variants.sh: 33 for the A13 build (NDK 28), 35 for
        // the A15 build (NDK 29). The core is compiled against the matching API, so these must
        // agree -- an APK that installs below its core's target is a dlopen failure at boot.
        minSdk = (project.findProperty("armsx3.minSdk") as String?)?.toInt() ?: 33
        targetSdk = 37
        versionCode = 18
        versionName = "0.9.3"

        // ARMSX2's UI reads these. STORAGE_ALL_FILES gates the all-files storage path in
        // onboarding; IN_APP_UPDATER gates the in-app GitHub-release updater.
        //
        // These are the github values; the play flavor overrides all three below.
        //
        // The warning that used to live here was right and is now acted on: a runtime boolean
        // does nothing about the PERMISSION in the bundle, which is what Play rejects. The
        // permissions have moved into the github flavor's manifest, so the play bundle does not
        // declare them at all.
        buildConfigField("boolean", "STORAGE_ALL_FILES", "true")
        buildConfigField("boolean", "IN_APP_UPDATER", "true")
        buildConfigField("boolean", "FRAME_GENERATION", "true")

        ndk {
            // The core is arm64-only.
            abiFilters.add("arm64-v8a")
        }

        // Builds the JNI glue (libarmsx3-jni.so) only. The emulator core is NOT
        // built here -- it is a prebuilt in jniLibs, produced separately by
        // android/configure.sh + ninja, because it needs LLVM and a ~40 minute
        // build that has no business running on every Gradle sync.
        externalNativeBuild {
            cmake {
                // c++_static, matching the CORE (which is also static) and the
                // 0.1 build that ran on device. The glue and the core exchange
                // std::string/std::string_view across the .so boundary, so this
                // is not a free choice -- mixing STLs there is the officially
                // unsupported case, and the static/static pairing is the one
                // already proven to work.
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    // Two distributions, and they are not interchangeable.
    //
    // github is the sideloaded build: it updates itself from GitHub releases, can be pointed at
    // an arbitrary data folder, and ships frame generation.
    //
    // play is what Google Play will accept. Self-updating is forbidden outright, all-files
    // storage is a policy review it does not need, and frame generation is left out. The
    // applicationId differs so the two install side by side instead of over each other.
    flavorDimensions += "distribution"

    productFlavors {
        create("github") {
            dimension = "distribution"
        }

        create("play") {
            dimension = "distribution"
            applicationId = "com.armsx3.play"

            buildConfigField("boolean", "STORAGE_ALL_FILES", "false")
            buildConfigField("boolean", "IN_APP_UPDATER", "false")
            buildConfigField("boolean", "FRAME_GENERATION", "false")

            // Frame generation is excluded by SOURCE SET, not by a packaging filter: a
            // packaging block inside a flavor is not honoured and silently applied to both,
            // which dropped the library from the github build too. libarmsx3_lsfg.so lives in
            // src/github/jniLibs, so only that flavor bundles it.
            //
            // Excluding the file is the whole exclusion. The shim is dlopen'd by name, and the
            // core already reports frame generation unavailable when the library is absent,
            // which is the same path a device that cannot run it takes.
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.5"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Debug-signed so alpha release builds are sideloadable without the
            // upload key. Swap this for the real config before any public build.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // No kotlinOptions block: it was removed in Kotlin 2.x, and the Kotlin
    // jvmTarget is taken from compileOptions above.

    buildFeatures {
        compose = true
        buildConfig = true
        viewBinding = true
    }

    packaging {
        // libadrenotools' linker-namespace bypass needs the .so files laid out
        // uncompressed rather than extracted by the installer.
        jniLibs.useLegacyPackaging = true
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ARMSX3: fail the build if the bundled ANGLE libraries are not there.
//
// This check exists because of a specific, expensive bug in ARMSX2: the repo's
// blanket `*.so` gitignore rule swallowed the ANGLE prebuilts, they never made it
// into release staging, the APK shipped without them, and the core fell back to
// the system GLES driver in complete silence. Users reported "ANGLE is broken"
// and there was nothing in any log to contradict them.
//
// jniLibs/.gitignore un-ignores the two files by name. This task is the second
// lock: packaging an APK that claims to support ANGLE without shipping ANGLE is a
// build error, not a runtime surprise. The core-side counterpart is the loud error
// in gl::es::egl_initialize() when the override library is selected but cannot be
// dlopen'd; the UI-side counterpart is the MISSING_LIBS line that
// MainActivityRuntime.applyAngleEnv logs when the option is on and the .so is not
// in nativeLibraryDir.
//
// The claim being guarded is live in THIS module: RendererBackendSection ->
// AngleDriverSection writes Settings.useAngleOpenGL, and applyAngleEnv turns it
// into ARMSX2_ANGLE_EGL_LIBRARY. (Both the libraries and this task used to sit in
// the stale android/armsx3-app module, which builds nothing that ships -- so the
// guard could not fire for the APK it was meant to protect.)
val verifyAngleLibs by tasks.registering {
    val angleLibs = listOf("libEGL_angle.so", "libGLESv2_angle.so")
    val jniLibDir = file("src/main/jniLibs/arm64-v8a")

    doLast {
        val missing = angleLibs.filter { !jniLibDir.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "ANGLE libraries missing from $jniLibDir: ${missing.joinToString(", ")}.\n" +
                "The OpenGL renderer's ANGLE option cannot work without them and would " +
                "silently fall back to the system GLES driver.\n" +
                "They are tracked in git - check them out, or remove the ANGLE option."
            )
        }

        angleLibs.forEach {
            logger.lifecycle("ANGLE: packaging $it (${jniLibDir.resolve(it).length()} bytes)")
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(verifyAngleLibs) }

dependencies {
    // Discord Social SDK, staged locally rather than pulled from a repo: it is
    // proprietary and distributed per-application from the developer portal.
    //
    // Its Java classes are resolved by name from the SDK's own native code
    // (com.discord.socialsdk.AuthenticationClientCallback and friends), so
    // without this the :discord process aborts with ClassNotFoundException even
    // though the .so links fine. proguard-rules.pro keeps them from being
    // renamed for the same reason.
    implementation(files("libs/discord_partner_sdk.aar"))

    implementation(libs.androidx.browser)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)

    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.material3)
    implementation(libs.composeIcons.fontAwesome)
    implementation(libs.composeIcons.lineAwesome)

    implementation(libs.kotlin.reflect)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif) // animated GIF / WebP / APNG (library background)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // The net.rpcsx binding layer persists library and firmware state with
    // kotlinx-serialization.
    implementation(libs.kotlinxSerializationJson)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// AGP emits a Compose "group mapping" diagnostic artifact on release builds, which
// needs org.jetbrains.kotlin:compose-group-mapping from the network. It is a
// tooling aid with no effect on the APK, and its absence fails the whole build, so
// turn it off rather than take a network dependency for it.
tasks.matching { it.name.contains("ComposeMapping") }.configureEach { enabled = false }
