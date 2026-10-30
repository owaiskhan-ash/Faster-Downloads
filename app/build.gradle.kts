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
        versionCode = 9
        versionName = "0.4.2"
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
    // Guice is excluded from every route into bt-core: the version bt asks for cannot run
    // on Android at all (see the pin below), so this project supplies its own.
    implementation("com.github.atomashpolskiy:bt-core:1.10") {
        exclude(group = "com.google.inject", module = "guice")
    }
    implementation("com.github.atomashpolskiy:bt-dht:1.10") {
        exclude(group = "com.google.inject", module = "guice")
    }
    implementation("com.github.atomashpolskiy:bt-http-tracker-client:1.10") {
        exclude(group = "com.google.inject", module = "guice")
    }

    // Guice, pinned by hand for one reason: the popular builds of it cannot start a
    // torrent client on Android.
    //
    //  * Guice 5.0.1 (what bt 1.10 asks for) added BindingSourceRestriction, which calls
    //    Class.getAnnotatedSuperclass(). Android's java.lang.Class does not implement that
    //    method at ANY API level -> "No virtual method getAnnotatedSuperclass()...".
    //  * Guice 4.2.3 (the obvious downgrade) generates a FastClass with a bundled cglib for
    //    every @Provides method, and Android refuses the generated class
    //    ("Unable to load cache item" / "can't load this type of class file").
    //
    // The "no_aop" classifier, which Maven itself ships and runs on, is the only build that
    // works: no BindingSourceRestriction and no cglib, so @Provides falls back to plain
    // reflection. Multibindings live in their own artifact before 5.0 and bt uses
    // Multibinder/MapBinder, hence the companion dependency.
    implementation("com.google.inject:guice:4.2.3:no_aop")
    implementation("com.google.inject.extensions:guice-multibindings:4.2.3") {
        // This artifact depends on the plain guice jar; keep it out so only the no_aop
        // build is on the classpath (two jars of the same classes fail dex merging).
        exclude(group = "com.google.inject", module = "guice")
    }

    testImplementation("junit:junit:4.13.2")
}
