import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Where the signing key is and its password (see the signing section below).
val keyProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun keyValue(name: String): String? = System.getenv(name) ?: keyProps.getProperty(name)

android {
    namespace = "com.pilchard.player"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        targetSdk = 35
        versionCode = 42
        versionName = "0.6.5"

        // The Shield and most Android TV boxes are ARM. Skipping x86 keeps the APK smaller.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Signing keys are kept out of the source code. The build reads where each key is and
    // its password from environment variables (GitHub secrets on the build server), or from a
    // keystore.properties file next to this one when building at home. Without a key,
    // debug builds use Android's standard debug key and release builds are left unsigned.
    //  - pilchard: the family key. Every family build uses it, so new versions install over old ones.
    //  - upload: the Google Play upload key. Google re-signs the app with its own key for the store.
    signingConfigs {
        keyValue("PILCHARD_KEYSTORE")?.let { path ->
            create("pilchard") {
                storeFile = rootProject.file(path)
                storePassword = keyValue("PILCHARD_KEYSTORE_PASSWORD")
                keyAlias = keyValue("PILCHARD_KEY_ALIAS") ?: "pilchard"
                keyPassword = keyValue("PILCHARD_KEY_PASSWORD") ?: keyValue("PILCHARD_KEYSTORE_PASSWORD")
            }
        }
        keyValue("PILCHARD_UPLOAD_KEYSTORE")?.let { path ->
            create("upload") {
                storeFile = rootProject.file(path)
                storePassword = keyValue("PILCHARD_UPLOAD_KEYSTORE_PASSWORD")
                keyAlias = keyValue("PILCHARD_UPLOAD_KEY_ALIAS") ?: "upload"
                keyPassword = keyValue("PILCHARD_UPLOAD_KEYSTORE_PASSWORD")
            }
        }
    }

    // Two versions are built from the same code:
    //  - family: the one installed at home. Jellyfin for Android TV only reads the stopping
    //    position back from players it recognizes by app ID. Pilchard is built on mpv and
    //    returns results in mpv-android's format, so this version uses mpv-android's ID.
    //    (Real mpv-android can't be installed alongside it.)
    //  - play: the Google Play Store version, with its own permanent app ID. Jellyfin doesn't
    //    recognize it, so ResumeFixer puts back resume points Jellyfin would otherwise wipe.
    // They have different IDs, so both can be installed on the same Shield for testing.
    flavorDimensions += "store"
    productFlavors {
        create("family") {
            dimension = "store"
            applicationId = "is.xyz.mpv"
            buildConfigField("boolean", "FIX_RESUME", "false")
            // Always English audio (the family only reads English).
            buildConfigField("boolean", "FOLLOW_DEVICE_LANGUAGE", "false")
            // English menus only, whatever the Shield is set to.
            resourceConfigurations += "en"
            signingConfigs.findByName("pilchard")?.let { signingConfig = it }
        }
        create("play") {
            dimension = "store"
            // Permanent once uploaded to Google Play.
            applicationId = "sugar.beefsupreme.pilchard"
            buildConfigField("boolean", "FIX_RESUME", "true")
            // Audio and subtitles in the TV's language.
            buildConfigField("boolean", "FOLLOW_DEVICE_LANGUAGE", "true")
            // Menus in these languages (translations in src/play/res), English otherwise.
            resourceConfigurations += listOf("en", "es", "fr", "de", "pt")
            (signingConfigs.findByName("upload") ?: signingConfigs.findByName("pilchard"))
                ?.let { signingConfig = it }
        }
    }

    buildTypes {
        // Release builds are signed by their flavor's key (above).
        release {
            isMinifyEnabled = false
        }
        // Debug builds use the family key too, so a debug copy installs over a release one.
        debug {
            signingConfigs.findByName("pilchard")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // libmpv (the mpv playback engine) packaged for Android.
    implementation("dev.jdtech.mpv:libmpv:1.0.0")
}
