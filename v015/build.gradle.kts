plugins { id("com.android.application") }

android {
    namespace = "com.moaqeel.aitranslator.v015"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.moaqeel.aitranslator.v015"
        minSdk = 29
        targetSdk = 34
        versionCode = 17
        versionName = "0.15.2"
    }
    flavorDimensions += "device"
    productFlavors {
        create("mobile") {
            dimension = "device"
            applicationIdSuffix = ".mobile"
            ndk { abiFilters += listOf("arm64-v8a") }
            buildConfigField("boolean", "TV_MODE", "false")
            resValue("string", "app_name", "AI Translate V0.15.2 Mobile")
        }
        create("mt5889") {
            dimension = "device"
            applicationIdSuffix = ".mt5889"
            ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }
            buildConfigField("boolean", "TV_MODE", "true")
            resValue("string", "app_name", "AI Translate V0.15.2 MT5889 TV")
        }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
}
