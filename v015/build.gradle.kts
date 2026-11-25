plugins { id("com.android.application") }

android {
    namespace = "com.moaqeel.aitranslator.v015"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.moaqeel.aitranslator.v015"
        minSdk = 29
        targetSdk = 34
        versionCode = 15
        versionName = "0.15"
    }
    flavorDimensions += "device"
    productFlavors {
        create("mobile") {
            dimension = "device"
            applicationIdSuffix = ".mobile"
            buildConfigField("boolean", "TV_MODE", "false")
            resValue("string", "app_name", "AI Translate V0.15 Mobile")
        }
        create("mt5889") {
            dimension = "device"
            applicationIdSuffix = ".mt5889"
            buildConfigField("boolean", "TV_MODE", "true")
            resValue("string", "app_name", "AI Translate V0.15 TV")
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
}
