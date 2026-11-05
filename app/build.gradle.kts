plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.iraqtranslate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.iraqtranslate"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "1.2.0"
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val sherpaAarFile = layout.projectDirectory.file("libs/sherpa-onnx-static-link-onnxruntime-1.12.15.aar").asFile
val sherpaAarSha256 = "350d4a42a7ccde5a3b7dda207f954d6d57de981fe747106b0e3091e7b947c236"
fun sha256(file: java.io.File): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }

val downloadSherpaAar by tasks.registering {
    outputs.file(sherpaAarFile)
    outputs.upToDateWhen { sherpaAarFile.isFile && sha256(sherpaAarFile) == sherpaAarSha256 }
    doLast {
        if (!sherpaAarFile.isFile || sha256(sherpaAarFile) != sherpaAarSha256) {
            sherpaAarFile.parentFile.mkdirs()
            val partial = java.io.File(sherpaAarFile.parentFile, sherpaAarFile.name + ".part")
            partial.delete()
            val aarUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-libs/resolve/dea0ed4bc892fe6725c87874bea5c2e7cc946132/android/aar/1.12.15/sherpa-onnx-static-link-onnxruntime-1.12.15.aar?download=true"
            val connection = java.net.URI(aarUrl).toURL().openConnection() as java.net.HttpURLConnection
            try {
                connection.connectTimeout = 20_000
                connection.readTimeout = 180_000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "IRAQ-TRANSLATE-Gradle/1.0.9")
                check(connection.responseCode in 200..299) { "Sherpa AAR download failed: HTTP ${connection.responseCode}" }
                connection.inputStream.use { input ->
                    partial.outputStream().use { output -> input.copyTo(output) }
                }
                check(sha256(partial) == sherpaAarSha256) { "Sherpa-ONNX AAR SHA-256 mismatch" }
                sherpaAarFile.delete()
                check(partial.renameTo(sherpaAarFile)) { "Could not install Sherpa-ONNX AAR" }
            } catch (t: Throwable) {
                partial.delete()
                throw t
            } finally {
                connection.disconnect()
            }
        }
    }
}

tasks.named("preBuild").configure { dependsOn(downloadSherpaAar) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    // Decoder-frame PTS callback bridge for an IPTV host built on Media3 ExoPlayer.
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    // Exact Sherpa-ONNX 1.12.15 Android AAR is downloaded and SHA-256 verified by preBuild.
    implementation(files(layout.projectDirectory.file("libs/sherpa-onnx-static-link-onnxruntime-1.12.15.aar")))
    // ADB TLS pairing / shell bridge used by the reference application.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
}
