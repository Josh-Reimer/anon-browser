plugins {
    id("com.android.application") version "8.6.1"
    id("org.jetbrains.kotlin.android") version "2.0.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21"
}

android {
    namespace = "com.joshreimer.anonbrowser"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.joshreimer.anonbrowser"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        }
        // tor-android ships libtor.so per-ABI; make sure it isn't stripped/compressed
        // in a way that breaks exec-from-nativeLibraryDir.
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Pinned above what Compose 2024.09.02 pulls in transitively (1.0.1) — 1.1.0 is built
    // 16KB-page-aligned, 1.0.1 isn't (flagged by Android's debug-build compatibility warning).
    implementation("androidx.graphics:graphics-path:1.1.0")

    // Chromium WebView proxy control (routes all WebView traffic through Tor's SOCKS5 port).
    implementation("androidx.webkit:webkit:1.17.1")

    // Bundled tor daemon binary (libtor.so per ABI, placed under nativeLibraryDir so it's
    // executable under Android 10+'s W^X restrictions).
    implementation("info.guardianproject:tor-android:0.4.8.16")

    // Pluggable transports (obfs4/webtunnel/meek_lite) for Tor bridges — the same library
    // Orbot uses. Snowflake/dnstt are also in here but aren't wired up (see Bridges.kt).
    implementation("com.netzarchitekten:IPtProxy:5.5.1")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
