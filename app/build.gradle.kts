import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// tasks-vision 0.10.26 es solo arm64. 1.2.3 arrastra tasks-core, cuyo
// libmediapipe_tasks_jni.so sí incluye armeabi-v7a y HandLandmarkerGraph.
val handFullUrl =
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/1/hand_landmarker.task"
val handFullSha256 = "fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1"
val handLiteSha256 = "d40f91342b5bcd27300529ba26476b248bf6ff3570fb28ff9e10bd8256b5dc43"
val handAssetDir = layout.buildDirectory.dir("generated/handAssets")

android {
    namespace = "com.stb.handdraw"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stb.handdraw"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        // El decodificador solo carga userspace de 32 bits. Cualquier .so arm64 queda fuera del APK.
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    signingConfigs {
        create("stbDebug") {
            // Clave de depuración pública y estable para que `adb install -r` sustituya la app.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stbDebug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stbDebug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = false
    }

    sourceSets.getByName("main").assets.srcDir(handAssetDir)

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/license.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/notice.txt",
                "META-INF/INDEX.LIST",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")

    val cameraX = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // 1.2.3: el .so armeabi-v7a vive en tasks-core (libmediapipe_tasks_jni.so) e incluye Hand Landmarker.
    implementation("com.google.mediapipe:tasks-vision:1.2.3")

    testImplementation("junit:junit:4.13.2")
}

val prepareHandModels = tasks.register("prepareHandModels") {
    val lite = rootProject.layout.projectDirectory.file("models/hand_landmarker_lite.task")
    inputs.file(lite)
    inputs.property("fullUrl", handFullUrl)
    inputs.property("fullSha", handFullSha256)
    inputs.property("liteSha", handLiteSha256)
    outputs.dir(handAssetDir)
    doLast {
        val dir = handAssetDir.get().asFile
        dir.mkdirs()

        fun sha256(file: java.io.File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        val liteSrc = lite.asFile
        val liteGot = sha256(liteSrc)
        if (liteGot != handLiteSha256) {
            error("SHA-256 del modelo lite no coincide: $liteGot")
        }
        liteSrc.copyTo(dir.resolve("hand_landmarker_lite.task"), overwrite = true)

        val dest = dir.resolve("hand_landmarker_full.task")
        if (dest.isFile && sha256(dest) == handFullSha256) {
            return@doLast
        }

        var lastError: Exception? = null
        for (attempt in 1..3) {
            val tmp = dir.resolve("hand_landmarker_full.task.partial")
            try {
                val connection = (URI(handFullUrl).toURL().openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 30_000
                    readTimeout = 120_000
                    setRequestProperty("User-Agent", "stb-hand-draw-gradle")
                }
                connection.inputStream.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                connection.disconnect()
                val got = sha256(tmp)
                if (got != handFullSha256) {
                    tmp.delete()
                    error("SHA-256 del modelo full no coincide: $got")
                }
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                lastError = null
                break
            } catch (error: Exception) {
                tmp.delete()
                lastError = error
                Thread.sleep(1500L * attempt)
            }
        }
        if (lastError != null || !dest.isFile) {
            throw lastError ?: IllegalStateException("No se pudo descargar el modelo de manos")
        }
    }
}

tasks.named("preBuild").configure { dependsOn(prepareHandModels) }
tasks.configureEach {
    if (name.startsWith("merge") && name.endsWith("Assets")) {
        dependsOn(prepareHandModels)
    }
}
