import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.gestureos"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.gestureos"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Target the tablet's ARM variants; this MediaPipe version has no x86_64 native binary.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true; buildConfig = true }
    androidResources { noCompress += "task" }
    lint {
        warningsAsErrors = true
        abortOnError = true
        // Versions are deliberately pinned to this API-35/JDK-17 toolchain, not latest releases.
        disable += "GradleDependency"
        // Milestone 1 targets the Nokia T20's ARM hardware; ChromeOS is outside this project scope.
        disable += "ChromeOsAbiSupport"
    }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/modelAssets"))
}

// The model is fetched at BUILD time, verified, and bundled in the APK. No runtime network.
val modelUrl = "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
val modelSha256 = "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"
val modelFile = layout.buildDirectory.file("generated/modelAssets/hand_landmarker.task")
fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    .joinToString("") { "%02x".format(it) }

val prepareHandModel by tasks.registering {
    inputs.property("url", modelUrl)
    inputs.property("sha256", modelSha256)
    outputs.file(modelFile)
    outputs.upToDateWhen { modelFile.get().asFile.let { it.isFile && sha256(it) == modelSha256 } }
    doLast {
        val destination = modelFile.get().asFile
        if (destination.isFile && sha256(destination) == modelSha256) return@doLast
        destination.parentFile.mkdirs()
        val temporary = File(destination.parentFile, "hand_landmarker.download")
        try {
            val connection = URI(modelUrl).toURL().openConnection().apply {
                connectTimeout = 30_000
                readTimeout = 60_000
            }
            connection.getInputStream().use { input -> temporary.outputStream().use { input.copyTo(it) } }
            check(sha256(temporary) == modelSha256) { "Hand model checksum mismatch; refusing to package it." }
            temporary.copyTo(destination, overwrite = true)
        } finally {
            temporary.delete()
        }
    }
}
tasks.named("preBuild").configure { dependsOn(prepareHandModel) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    val cameraX = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("com.google.mediapipe:tasks-vision:0.10.21")
    testImplementation("junit:junit:4.13.2")
}
