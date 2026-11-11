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

// The Shield shows one home screen tile per app, so the "Pilchard Live TV" tile is its own
// tiny app. All it does is open live TV inside Pilchard Player.
android {
    namespace = "com.pilchard.livetv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pilchard.livetv"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // The signing key is kept out of the source code. The build reads where it is and its
    // password from environment variables (GitHub secrets on the build server), or from a
    // keystore.properties file next to this one when building at home. Without a key,
    // debug builds use Android's standard debug key and release builds are left unsigned.
    val keystorePath = keyValue("PILCHARD_KEYSTORE")
    if (keystorePath != null) {
        signingConfigs {
            create("pilchard") {
                storeFile = rootProject.file(keystorePath)
                storePassword = keyValue("PILCHARD_KEYSTORE_PASSWORD")
                keyAlias = keyValue("PILCHARD_KEY_ALIAS") ?: "pilchard"
                keyPassword = keyValue("PILCHARD_KEY_PASSWORD") ?: keyValue("PILCHARD_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("pilchard")?.let { signingConfig = it }
        }
        debug {
            signingConfigs.findByName("pilchard")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
