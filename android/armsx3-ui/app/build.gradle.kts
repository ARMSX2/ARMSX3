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
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.1-alpha"

        // ARMSX2's UI reads these. STORAGE_ALL_FILES gates the all-files
        // storage path in onboarding; IN_APP_UPDATER gates self-update (off:
        // ARMSX3 updates come from its own release channel, and shipping an
        // in-app APK installer is a Play-policy problem).
        buildConfigField("boolean", "STORAGE_ALL_FILES", "true")
        buildConfigField("boolean", "IN_APP_UPDATER", "false")

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
