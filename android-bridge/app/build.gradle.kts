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
        versionCode = 37
        versionName = "1.0.37"
    }

    signingConfigs {
        // Keystore fixa commitada no repositório — garante que TODOS os builds
        // (CI, local, release sem keyserver) gerem APKs com a MESMA assinatura.
        // Sem isso o Gradle gera um debug keystore novo a cada run do CI e o
        // Android recusa instalar por cima ("assinatura diferente").
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
                // Caminho resolvido a partir da raiz do projeto (não do módulo
                // app/) — assim funciona igual no CI e em qualquer máquina.
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
            // Mesmo esquema da release quando há keystore de produção disponível
            // (local via keystore.properties ou CI via secrets). Assim o debug
            // instala por cima do app de release sem erro de assinatura.
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
            // android.util.Log etc. não existem na JVM: devolve valores padrão
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
    // org.json real nos testes unitários (o do android.jar é só stub)
    testImplementation("org.json:json:20231013")
}
