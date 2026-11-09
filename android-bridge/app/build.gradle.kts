import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Chave de assinatura (opcional).
// Copie keystore.properties.template -> keystore.properties e aponte para o .jks.
// Sem esse arquivo o build usa a assinatura de debug.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKeystore = keystoreProps.getProperty("storeFile")
    ?.let { file(it).exists() } == true

android {
    namespace = "com.orangepi.wabridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.orangepi.wabridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.0.7"
    }

    if (hasReleaseKeystore) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
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

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    testOptions {
        unitTests {
            // android.util.Log etc. não existem na JVM: devolve valores padrão
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    // org.json real nos testes unitários (o do android.jar é só stub)
    testImplementation("org.json:json:20231013")
}
