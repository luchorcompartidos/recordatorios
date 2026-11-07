plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Número de compilación de GitHub Actions: hace que cada APK nuevo sea una actualización del anterior.
val numeroCompilacion = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val rutaFirma: String? = System.getenv("KEYSTORE_PATH")

android {
    namespace = "com.misrecordatorios.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.misrecordatorios.app"
        minSdk = 34          // Android 14
        targetSdk = 35
        versionCode = numeroCompilacion
        versionName = "2.3.$numeroCompilacion"
    }

    signingConfigs {
        create("fija") {
            if (rutaFirma != null) {
                storeFile = file(rutaFirma)
                storePassword = System.getenv("KEYSTORE_PASS")
                keyAlias = "recordatorios"
                keyPassword = System.getenv("KEYSTORE_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Con el secreto cargado se firma siempre con la misma clave (permite actualizar sin desinstalar).
            signingConfig = if (rutaFirma != null) signingConfigs.getByName("fija")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
