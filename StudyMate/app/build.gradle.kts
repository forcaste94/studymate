import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Версия и репозиторий приходят из GitHub Actions (-PappVersionName=1.0.1 и т.д.).
// При локальной сборке используются значения по умолчанию.
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.0.0"
val appVersionCode = ((project.findProperty("appVersionCode") as String?) ?: "10000").toInt()
val updateRepo = (project.findProperty("updateRepo") as String?) ?: "OWNER/REPO"

// Подпись: из переменных окружения (GitHub Actions) или из файла keystore.properties (локально).
val localKeystore = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val ksPath: String? = System.getenv("KEYSTORE_PATH") ?: localKeystore.getProperty("storeFile")
val ksPassword: String? = System.getenv("KEYSTORE_PASSWORD") ?: localKeystore.getProperty("storePassword")
val ksAlias: String? = System.getenv("KEY_ALIAS") ?: localKeystore.getProperty("keyAlias")

android {
    namespace = "com.studymate.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.studymate.app"
        minSdk = 24
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
    }

    signingConfigs {
        if (ksPath != null && ksPassword != null && ksAlias != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = ksPassword
                keyAlias = ksAlias
                keyPassword = ksPassword // для PKCS12 пароль ключа равен паролю хранилища
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.findByName("release")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.webkit:webkit:1.11.0")
}
