import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing key (optional).
// Copy keystore.properties.template -> keystore.properties and point it at the .jks.
// Without this file the build uses the debug signing.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile")
    ?.let { rootProject.file(it).exists() } == true

android {
    namespace = "com.orangepi.wabridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.orangepi.wabridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 66
        versionName = "1.0.66"
    }

    signingConfigs {
        // Fixed keystore committed to the repository: ensures that ALL builds
        // (CI, local, release without keyserver) produce APKs with the SAME signature.
        // Without this, Gradle generates a new debug keystore on every CI run and
        // Android refuses to install over it ("different signature").
        create("fixed") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                // Path resolved from the project root (not from the module
                // app/), so it works the same way in CI and on any machine.
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("fixed")
            }
        }
        debug {
            // Same scheme as release when a production keystore is available
            // (locally via keystore.properties or in CI via secrets). This way the debug
            // installs over the release app without a signature error.
            signingConfig = if (hasReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("fixed")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    testOptions {
        unitTests {
            // android.util.Log etc. don't exist on the JVM: they return default values
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    // real org.json in unit tests (the one in android.jar is just a stub)
    testImplementation("org.json:json:20231013")
}
