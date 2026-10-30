plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fasterdownloads"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fasterdownloads"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.7.0"
        vectorDrawables { useSupportLibrary = true }

        // libtorrent4j ships one native library per ABI, and no Java fallback exists.
        // Two ABIs cover every phone in use: 64-bit is the default on Android 8+, and
        // armeabi-v7a keeps 32-bit devices working. Emulator-only ABIs are left out to
        // keep the APK from doubling in size.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            val path = System.getenv("KEYSTORE_PATH")
            if (!path.isNullOrBlank() && file(path).exists()) {
                storeFile = file(path)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val path = System.getenv("KEYSTORE_PATH")
            signingConfig = if (!path.isNullOrBlank() && file(path).exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // Extract the torrent engine's native library on install instead of mapping it
        // straight out of the APK: that is the path every Android libtorrent client uses,
        // and it is what keeps the SWIG JNI binding loadable on older/vendor ROMs.
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // The BitTorrent library pulls in several Apache-style licenses/notices.
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/INDEX.LIST"
            // Keep META-INF/services/*: the BitTorrent binder still registers through them.
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.09.03")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // HTTP transport: connection pooling (keep-alive) per radio.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // BitTorrent (magnet links): libtorrent, the C++ engine behind every working Android
    // client (LibreTorrent, FrostWire, TorrDroid). It brings the real thing - UDP trackers
    // (BEP 15), DHT, PeX, LSD, uTP, encryption - none of which can be reimplemented in a
    // Java-only library to the same effect.
    //
    // The core jar holds the SWIG Java bindings; the `-android-<abi>` jars hold the matching
    // `libtorrent4j.so`. Each ABI needs its own artifact, and they must line up with the
    // `abiFilters` above or the JNI load fails with UnsatisfiedLinkError.
    implementation("org.libtorrent4j:libtorrent4j:2.1.0-38")
    implementation("org.libtorrent4j:libtorrent4j-android-arm64:2.1.0-38")
    implementation("org.libtorrent4j:libtorrent4j-android-arm:2.1.0-38")

    testImplementation("junit:junit:4.13.2")
}
