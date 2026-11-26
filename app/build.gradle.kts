import groovy.json.JsonSlurper
import java.io.File
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.nikita.voice"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.nikita.voice.offline"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.0.4"
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets["main"].assets.srcDir(rootProject.file("model-assets"))
    packaging.jniLibs.useLegacyPackaging = false
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
}

fun sha256(file: File): String {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { stream ->
        val buffer = ByteArray(1024 * 1024)
        var count = stream.read(buffer)
        while (count >= 0) {
            hash.update(buffer, 0, count)
            count = stream.read(buffer)
        }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

val modelAssets = rootProject.file("model-assets/embedded_models")
val expectedIndex = rootProject.file("tools/release-pack.json")
val verificationStamp = layout.buildDirectory.file("verification/model-pack.txt")
val verifyModelAssets = tasks.register("verifyModelAssets") {
    inputs.files(modelAssets, expectedIndex)
    outputs.file(verificationStamp)
    doLast {
        val index = File(modelAssets, "pack-v1.json")
        check(index.isFile && index.readBytes().contentEquals(expectedIndex.readBytes())) {
            "Prepare model assets first: python tools/prepare_models.py --apk <ShonaVoice-Offline.apk>"
        }
        val pack = JsonSlurper().parse(expectedIndex) as Map<*, *>
        val models = pack["files"] as List<*>
        for (record in models) {
            val entry = record as Map<*, *>
            val file = File(modelAssets, entry["path"] as String)
            check(file.isFile && file.length() == (entry["bytes"] as Number).toLong() &&
                  sha256(file) == entry["sha256"]) {
                "Model verification failed for ${entry["path"]}. Run tools/prepare_models.py again."
            }
        }
        val stamp = verificationStamp.get().asFile
        stamp.parentFile.mkdirs()
        stamp.writeText(sha256(index))
    }
}
tasks.named("preBuild").configure { dependsOn(verifyModelAssets) }
