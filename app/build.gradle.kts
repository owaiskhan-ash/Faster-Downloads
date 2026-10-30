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
        versionCode = 8
        versionName = "0.4.1"
        vectorDrawables { useSupportLibrary = true }
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // The BitTorrent library pulls in several Apache-style licenses/notices.
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/INDEX.LIST"
            // Keep META-INF/services/*: bt discovers its modules through ServiceLoader.
        }
    }
}

configurations.configureEach {
    resolutionStrategy {
        // Whatever a transitive dependency asks for, Guice 5 must not reach the APK.
        force("com.google.inject:guice:4.2.3")
        force("com.google.inject.extensions:guice-multibindings:4.2.3")
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

    // BitTorrent (magnet links): pure-Java library, DHT + trackers + peer wire protocol.
    implementation("com.github.atomashpolskiy:bt-core:1.10") {
        // Guice 5 is not usable on Android - see the pin below.
        exclude(group = "com.google.inject", module = "guice")
    }
    implementation("com.github.atomashpolskiy:bt-dht:1.10")
    implementation("com.github.atomashpolskiy:bt-http-tracker-client:1.10")

    // bt 1.10 asks for Guice 5.0.1, whose BindingSourceRestriction calls
    // Class.getAnnotatedSuperclass(). Android's java.lang.Class does not implement that
    // method at ANY API level, so building the torrent client threw
    // "No virtual method getAnnotatedSuperclass()Ljava/lang/reflect/AnnotatedType;"
    // and every magnet download failed on device. Guice 4.2.3 has no such class.
    // In 4.x multibindings live in their own artifact (bt uses Multibinder/MapBinder).
    implementation("com.google.inject:guice:4.2.3")
    implementation("com.google.inject.extensions:guice-multibindings:4.2.3")

    testImplementation("junit:junit:4.13.2")
}
