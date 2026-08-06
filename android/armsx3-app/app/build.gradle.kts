plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    id("org.jetbrains.kotlin.plugin.serialization")
    id("kotlin-parcelize")
}

android {
    namespace = "net.rpcsx"
    compileSdk = 36
    ndkVersion = "29.0.13113456"

    defaultConfig {
        applicationId = "com.armsx3"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "${System.getenv("RX_VERSION") ?: "local"}${if (System.getenv("RX_SHA") != null) "-" + System.getenv("RX_SHA") else ""}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        buildConfigField("String", "Version", "\"v${versionName}\"")
    }

    signingConfigs {
        val keystoreAlias = System.getenv("KEYSTORE_ALIAS") ?: ""
        val keystorePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
        val keystorePath = System.getenv("KEYSTORE_PATH") ?: ""

        if (keystorePath.isNotEmpty() && file(keystorePath).exists() && file(keystorePath).length() > 0) {
            create("custom-key") {
                keyAlias = keystoreAlias
                keyPassword = keystorePassword
                storeFile = file(keystorePath)
                storePassword = keystorePassword
            }
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
            signingConfig = signingConfigs.findByName("custom-key") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.5"
        }
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.15"
    }

    packaging {
        // This is necessary for libadrenotools custom driver loading
        jniLibs.useLegacyPackaging = true
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
// jniLibs/.gitignore now un-ignores the two files by name. This task is the
// second lock: packaging an APK that claims to support ANGLE without shipping
// ANGLE is a build error, not a runtime surprise. The core-side counterpart is
// the loud error in gl::es::egl_initialize() when the override library is
// selected but cannot be dlopen'd.
val verifyAngleLibs by tasks.registering {
    val angleLibs = listOf("libEGL_angle.so", "libGLESv2_angle.so")
    val jniLibDir = file("src/main/jniLibs/arm64-v8a")

    doLast {
        val missing = angleLibs.filter { !File(jniLibDir, it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "ANGLE libraries missing from ${'$'}jniLibDir: ${'$'}{missing.joinToString(", ")}.\n" +
                "The OpenGL renderer's ANGLE option cannot work without them and would " +
                "silently fall back to the system GLES driver.\n" +
                "They are tracked in git - check them out, or remove the ANGLE option."
            )
        }

        angleLibs.forEach {
            logger.lifecycle("ANGLE: packaging ${'$'}it (${'$'}{File(jniLibDir, it).length()} bytes)")
        }
    }
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }
    .configureEach { dependsOn(verifyAngleLibs) }

base.archivesName = "rpcsx"

dependencies {
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.ui.tooling.preview.android)
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.squareup.okhttp3)
    implementation(libs.androidx.documentfile)
    implementation(libs.materialswitch)
}
