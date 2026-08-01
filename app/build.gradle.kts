// Explicit import: inside a .gradle.kts script `java` resolves to Gradle's JavaPluginExtension
// accessor, not the java.* package, so `java.security.MessageDigest` fails to compile.
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val openh264Version = "2.6.0"

// SHA-256 of the exact upstream artifacts these tasks fetch. Pinned so a build cannot
// silently consume a substituted or truncated download. Update deliberately, together
// with the version above, and record where the new digest came from.
//   openh264: https://ciscobinary.openh264.org/libopenh264-2.6.0-android-arm64.8.so.bz2
//   opencv  : https://github.com/nihui/opencv-mobile/releases/download/v31/opencv-mobile-4.10.0-android.zip
val openh264Sha256 = "c702d68c9c8db492a43c1d73a497cea5f31ae5d23e330dcb13bd28cab1dbbf2a"
val opencvMobileSha256 = "1fd97600f3ed7a0ea17fbd6d009bb9902eec9d968e7b74d5141285b6d7ce3412"

/**
 * Verify a downloaded artifact against its pinned digest, or fail the build.
 *
 * Deliberately a hard failure. The previous behaviour — catch, print a warning, carry on
 * — is worse than useless for OpenCV in particular: CMake then takes its `HAVE_OPENCV=0`
 * branch and the build SUCCEEDS with motion detection compiled out. A green build that
 * silently dropped a feature is the one outcome CI must never produce.
 */
fun verifyDigest(f: File, expected: String, what: String) {
    if (!f.exists()) throw GradleException("$what: download produced no file at ${f.absolutePath}")
    val actual = MessageDigest.getInstance("SHA-256")
        .digest(f.readBytes())
        .joinToString("") { b -> "%02x".format(b) }
    if (!actual.equals(expected, ignoreCase = true)) {
        f.delete()  // never leave a file that failed verification lying around
        throw GradleException(
            "$what: SHA-256 mismatch — refusing to use this artifact.\n" +
            "  expected $expected\n  actual   $actual\n" +
            "If the upstream release was legitimately re-published, update the pin in " +
            "app/build.gradle.kts in the same commit as the version bump."
        )
    }
    logger.lifecycle("✓ $what: digest verified")
}

// Auto-download OpenH264 from Cisco's official binary releases
tasks.register("downloadOpenH264") {
    val openh264Dir = file("src/main/cpp/openh264")

    doLast {
        // HTTPS, not HTTP. Cisco serves the identical artifact over TLS; the previous
        // cleartext URL made the digest pin the only thing standing between a build and
        // an on-path substitution, and also fails outright in CI images that block
        // cleartext egress.
        val abiMap = mapOf(
            "arm64-v8a" to "https://ciscobinary.openh264.org/libopenh264-${openh264Version}-android-arm64.8.so.bz2"
            // Removed armeabi-v7a to reduce APK size
        )

        abiMap.forEach { (abi, url) ->
            val libDir = file("${openh264Dir}/lib/${abi}")
            libDir.mkdirs()

            val soFile = file("${libDir}/libopenh264.so")
            if (!soFile.exists()) {
                logger.lifecycle("Downloading OpenH264 ${openh264Version} for ${abi}...")
                val bzFile = file("${libDir}/temp.bz2")

                try {
                    ant.invokeMethod("get", mapOf("src" to url, "dest" to bzFile.absolutePath))
                } catch (e: Exception) {
                    throw GradleException("OpenH264 download failed from $url: ${e.message}", e)
                }
                verifyDigest(bzFile, openh264Sha256, "OpenH264 ${openh264Version} ($abi)")

                ant.invokeMethod("bunzip2", mapOf("src" to bzFile.absolutePath))
                val unpacked = file("${libDir}/temp")
                if (!unpacked.renameTo(soFile)) {
                    throw GradleException("OpenH264: could not move ${unpacked} to ${soFile}")
                }
                // The old code left temp.bz2 behind on every fresh build.
                bzFile.delete()
                logger.lifecycle("✓ OpenH264 installed for ${abi}")
            }
        }

        // Download headers from Cisco's GitHub. Not digest-pinned individually — they are
        // fetched from an immutable tag ref and are compile-time only — but a failure here
        // still has to stop the build, because a missing header surfaces as a confusing
        // C++ error hundreds of lines later.
        val includeDir = file("${openh264Dir}/include/wels")
        includeDir.mkdirs()
        listOf("codec_api.h", "codec_app_def.h", "codec_def.h", "codec_ver.h").forEach { h ->
            val f = file("${includeDir}/${h}")
            if (!f.exists()) {
                val url = "https://raw.githubusercontent.com/cisco/openh264/v${openh264Version}/codec/api/wels/${h}"
                try {
                    ant.invokeMethod("get", mapOf("src" to url, "dest" to f.absolutePath))
                } catch (e: Exception) {
                    throw GradleException("OpenH264 header download failed ($h) from $url: ${e.message}", e)
                }
                if (!f.exists() || f.length() == 0L) {
                    throw GradleException("OpenH264 header $h downloaded empty from $url")
                }
            }
        }
    }
}

tasks.matching { it.name.contains("CMake") || it.name.contains("ExternalNative") }.configureEach {
    dependsOn("downloadOpenH264", "downloadOpenCV")
}

// ==================== Tunnel binaries ====================
// tailscaled / cloudflared / zrok / sing-box. These are CLI executables, not libraries:
// Android extracts lib/<abi>/*.so from the APK to nativeLibraryDir with the execute bit
// set, so shipping them under .so names is how the app gets runnable binaries onto the
// device. That part of the design is sound and unchanged.
//
// What changed is where they come from. They used to be ~40 MB of prebuilt blobs
// committed to this repository, and an audit could not verify them: all four UPX-packed,
// three with the PackHeader stripped so `upx -d` refuses them outright, and the versions
// they self-reported were developer builds that matched no upstream release. They are now
// produced by .github/workflows/tunnel-binaries.yml — official upstream release artifacts
// where they exist, built from source where they don't — and fetched here by digest.
//
// The digests below are the contract. If upstream re-publishes, or the release assets are
// tampered with, the build fails rather than silently shipping something else.
val tunnelBinariesRelease = "tunnel-binaries-2026.08.01"
val tunnelBinariesBaseUrl =
    "https://github.com/shauneccles/Overdrive-release/releases/download/$tunnelBinariesRelease"

// filename to SHA-256 of the packed artifact published by that release.
val tunnelBinaries = mapOf(
    "libtailscale.so" to "b52b3e5715cafc135fe032bd4672e1f6a01992502206c98c4324359356d3d95d",
    "libcloudflared.so" to "458c61bc15d33d5d79e3acc4e4bac9efecc934435f1504a55b7f6971ee2cc7dd",
    "libzrok.so" to "199629a6395d1b78850c555e2971bd76ed598ac094e101190e50581628c0ff1e",
    "libsingbox.so" to "5642eaf957aebaba6061d2ace38e929b3fc1dad690834aedcad316a29cdf203b"
)

tasks.register("downloadTunnelBinaries") {
    description = "Fetches the tunnel executables published by the tunnel-binaries workflow."
    val outDir = file("src/main/jniLibs/arm64-v8a")

    doLast {
        outDir.mkdirs()
        tunnelBinaries.forEach { (name, sha) ->
            val dest = file("$outDir/$name")
            // Re-verify on every build, not just on download. A cached or hand-edited file
            // is exactly the case this exists to catch, and a stat is free next to an
            // 18 MB fetch.
            if (dest.exists()) {
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(dest.readBytes())
                    .joinToString("") { b -> "%02x".format(b) }
                if (actual.equals(sha, ignoreCase = true)) return@forEach
                logger.lifecycle("$name: digest mismatch on the cached copy — refetching")
                dest.delete()
            }
            val url = "$tunnelBinariesBaseUrl/$name"
            logger.lifecycle("Downloading $name from $url")
            try {
                ant.invokeMethod("get", mapOf("src" to url, "dest" to dest.absolutePath))
            } catch (e: Exception) {
                throw GradleException("Tunnel binary download failed for $name from $url: ${e.message}", e)
            }
            verifyDigest(dest, sha, "tunnel binary $name")
        }
    }
}

// Bind to preBuild rather than the CMake tasks: these are packaged, not compiled, so they
// must exist before the merge/package steps, and they are needed even for a build with no
// native compilation to do.
tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("downloadTunnelBinaries")
}

// OpenCV-mobile version for surveillance module (minimal build, ~3MB vs ~20MB)
// https://github.com/nihui/opencv-mobile
val opencvMobileTag = "v31"
val opencvMobileVersion = "4.10.0"
tasks.register("downloadOpenCV") {
    val opencvDir = file("src/main/cpp/opencv")
    
    doLast {
        val libDir = file("${opencvDir}/lib/arm64-v8a")
        libDir.mkdirs()
        val includeDir = file("${opencvDir}/include")
        
        // opencv-mobile uses static library (.a)
        val staticLib = file("${libDir}/libopencv_core.a")
        
        if (!staticLib.exists()) {
            println("Downloading opencv-mobile ${opencvMobileVersion} for Android...")
            
            // Correct URL format: /releases/download/vVERSION/
            val zipUrl = "https://github.com/nihui/opencv-mobile/releases/download/${opencvMobileTag}/opencv-mobile-${opencvMobileVersion}-android.zip"
            val zipFile = file("${opencvDir}/opencv-mobile-android.zip")
            
            logger.lifecycle("Downloading from: $zipUrl")
            try {
                ant.invokeMethod("get", mapOf(
                    "src" to zipUrl,
                    "dest" to zipFile.absolutePath
                ))
            } catch (e: Exception) {
                throw GradleException("opencv-mobile download failed from $zipUrl: ${e.message}", e)
            }
            verifyDigest(zipFile, opencvMobileSha256, "opencv-mobile $opencvMobileVersion")

            logger.lifecycle("Extracting opencv-mobile (${zipFile.length() / 1024 / 1024}MB)...")
            ant.invokeMethod("unzip", mapOf(
                "src" to zipFile.absolutePath,
                "dest" to opencvDir.absolutePath
            ))

            // opencv-mobile extracts to opencv-mobile-VERSION-android/
            val extractedDir = file("${opencvDir}/opencv-mobile-${opencvMobileVersion}-android")
            if (!extractedDir.exists()) {
                throw GradleException(
                    "opencv-mobile: expected $extractedDir after extraction, found " +
                    "${opencvDir.listFiles()?.map { it.name }}. The release layout may have changed."
                )
            }

            // Copy arm64-v8a static libs
            val extractedLibDir = file("${extractedDir}/sdk/native/staticlibs/arm64-v8a")
            if (!extractedLibDir.exists()) {
                throw GradleException("opencv-mobile: static lib dir not found at $extractedLibDir")
            }
            extractedLibDir.listFiles()?.forEach { f ->
                f.copyTo(file("${libDir}/${f.name}"), overwrite = true)
            }

            // Copy headers
            val extractedInclude = file("${extractedDir}/sdk/native/jni/include")
            if (!extractedInclude.exists()) {
                throw GradleException("opencv-mobile: include dir not found at $extractedInclude")
            }
            if (includeDir.exists()) includeDir.deleteRecursively()
            extractedInclude.copyRecursively(includeDir, overwrite = true)

            // Post-condition: this is what CMakeLists actually probes for. Assert it rather
            // than trusting that the copies above did the right thing.
            val coreLib = file("${libDir}/libopencv_core.a")
            if (!coreLib.exists() || !file("${includeDir}/opencv2").exists()) {
                throw GradleException(
                    "opencv-mobile: install incomplete — CMake requires both $coreLib and " +
                    "${includeDir}/opencv2"
                )
            }

            zipFile.delete()
            extractedDir.deleteRecursively()
            logger.lifecycle("✓ opencv-mobile ${opencvMobileVersion} installed")
        } else {
            logger.lifecycle("✓ opencv-mobile found at ${libDir}")
        }
    }
}

// Check surveillance dependencies before build
tasks.register("checkSurveillanceDeps") {
    dependsOn("downloadOpenCV")
}


// Task to extract web assets to /data/local/tmp/web on device
// Run: ./gradlew :app:extractWebAssets
tasks.register("extractWebAssets") {
    description = "Extracts web assets from APK to /data/local/tmp/web on connected device"
    group = "deployment"
    
    doLast {
        val webSrcDir = file("src/main/assets/web")
        if (!webSrcDir.exists()) {
            println("⚠ No web assets found at ${webSrcDir}")
            return@doLast
        }
        
        println("Extracting web assets to device...")
        
        // Create target directory
        exec {
            commandLine("adb", "shell", "mkdir", "-p", "/data/local/tmp/web/shared")
        }
        exec {
            commandLine("adb", "shell", "mkdir", "-p", "/data/local/tmp/web/local")
        }
        
        // Push files
        webSrcDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relativePath = file.relativeTo(webSrcDir).path
            val targetPath = "/data/local/tmp/web/${relativePath}"
            println("  → ${relativePath}")
            exec {
                commandLine("adb", "push", file.absolutePath, targetPath)
            }
        }
        
        println("✓ Web assets extracted to /data/local/tmp/web/")
    }
}

android {
    signingConfigs {
        create("release") {
            storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "key0"
        }
    }
    namespace = "com.overdrive.app"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.overdrive.app"
        minSdk = 25
        targetSdk = 25
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        // Note: abiFilters removed - using splits.abi instead for size optimization
        
        externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        // This stops the build from failing due to the old targetSdk 28
        checkReleaseBuilds = false
        abortOnError = false
        disable += "ExpiredTargetSdkVersion"
    }
    
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            // Enable minification and shrinking for release builds
            isMinifyEnabled = true
            isShrinkResources = true
            
            // Auto-detect if DaemonLogConfig has any logging flags enabled.
            // When ALL flags are false (production): include proguard-rules-strip-logs.pro
            //   → R8 strips all log calls from bytecode
            // When ANY flag is true (debug build): exclude proguard-rules-strip-logs.pro
            //   → log calls stay in bytecode, DaemonLogConfig controls which tags write to disk
            val logConfigFile = file("src/main/java/com/overdrive/app/logging/DaemonLogConfig.java")
            val loggingEnabled = if (logConfigFile.exists()) {
                val content = logConfigFile.readText()
                val enableAllMatch = Regex("""public static final boolean ENABLE_ALL\s*=\s*true""").containsMatchIn(content)
                val anyFlagTrue = Regex("""public static final boolean (?!ANY_LOGGING_ENABLED)\w+\s*=\s*true""").containsMatchIn(content)
                enableAllMatch || anyFlagTrue
            } else false
            
            val proguardFilesList = mutableListOf(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                file("proguard-rules.pro")
            )
            if (loggingEnabled) {
                // Logging enabled: do NOT include strip-logs → log calls survive R8
                println("⚠ DaemonLogConfig: Logging ENABLED — DaemonLogger file logging kept, console still stripped")
            } else {
                // Production: include strip-logs → R8 removes all log calls
                proguardFilesList.add(file("proguard-rules-strip-logs.pro"))
            }
            proguardFiles(*proguardFilesList.toTypedArray())
            
            signingConfig = signingConfigs.getByName("release")
            
            // Update channel: "alpha" for release builds (checks alpha tag on GitHub)
            buildConfigField("String", "UPDATE_CHANNEL", "\"alpha\"")
            buildConfigField("boolean", "LOG_CAPTURE", "true")
            buildConfigField("String", "LOG_UPLOAD_URL", "\"\"")
        }
        debug {
            isMinifyEnabled = false
            
            // Debug builds also check alpha channel for updates
            buildConfigField("String", "UPDATE_CHANNEL", "\"alpha\"")
            buildConfigField("boolean", "LOG_CAPTURE", "true")
            buildConfigField("String", "LOG_UPLOAD_URL", "\"\"")
        }
    }
    
    // Split APKs by ABI - creates smaller APKs per architecture
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")  // Only arm64 for BYD
            isUniversalApk = false  // Don't create universal APK
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets {
        getByName("main") {
            // Tell Gradle to pick up .so files from your custom download folder
            jniLibs.srcDirs("src/main/cpp/openh264/lib")
        }
    }
    kotlinOptions { jvmTarget = "11" }
    
    packaging {
        resources {
            excludes += listOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/NOTICE.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/*.kotlin_module"
            )
            // Both paho.mqttv3 and paho.mqttv5 jars ship the same i18n
            // bundle.properties — pick first to silence the merge conflict.
            // The file is OSGi metadata, never read at runtime in our setup.
            pickFirsts += listOf(
                "bundle.properties"
            )
        }
        // Exclude unnecessary native libs from dependencies
        jniLibs {
            // CRITICAL: Compresses .so files in the APK (saves ~20MB+)
            useLegacyPackaging = true

            // Do NOT run `strip` over the tunnel executables. They are not libraries with
            // debug symbols to shed — they are UPX-packed self-extracting binaries, and AGP's
            // strip step rewrites them wholesale: a verified 14,412,212-byte libcloudflared.so
            // came out of packaging at 5,938,540 bytes. Two problems with letting that happen:
            //
            //   1. The digest pinned in downloadTunnelBinaries would then describe a file that
            //      is NOT what ships, which makes the pin decorative.
            //   2. Whether a stripped UPX image still self-extracts is not something to
            //      discover on a car.
            //
            // This is also, almost certainly, why three of the four previously-vendored blobs
            // had no UPX PackHeader and could not be unpacked for audit: they had been through
            // this packaging step. Keeping them intact preserves the property that anyone can
            // unpack and inspect exactly what the APK ships.
            keepDebugSymbols += listOf(
                "**/libtailscale.so",
                "**/libcloudflared.so",
                "**/libzrok.so",
                "**/libsingbox.so"
            )

            // Keep only arm64-v8a (You already have this, but good to keep)
            excludes += listOf(
                "lib/armeabi-v7a/**",
                "lib/x86/**",
                "lib/x86_64/**"
            )
        }
    }
}

/*
 * BYD SDK Stubs Architecture:
 * 
 * The classes in android.hardware.bydauto.* are compile-time stubs that allow
 * the code to compile without the actual BYD SDK JAR.
 * 
 * At runtime on BYD devices:
 * - The real BYD SDK classes are loaded by the boot classloader (higher priority)
 * - Our managers (RadarManager, BodyworkManager) use REFLECTION to get instances
 * - Class.forName() returns the real class from the system framework, not our stub
 * - The stubs in our APK are never actually instantiated
 * 
 * This works because:
 * 1. Boot classloader classes take precedence over app classes
 * 2. We use reflection: Class.forName("android.hardware.bydauto.radar.BYDAutoRadarDevice")
 * 3. getInstance() is called via reflection on the real class
 */

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Navigation
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    
    // Lifecycle & ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    
    // QR Code generation
    implementation(libs.zxing.core)

    // MapLibre Native — GPU vector map for the RoadSense map / navigation feature
    implementation(libs.maplibre.android.sdk)

    // RTMP streaming client for pushing to MediaMTX
    implementation(libs.rtmp.client)
    
    // ADB client for daemon launching
    implementation(libs.dadb)
    
    // WebSocket server for zero-latency H.264 streaming
    implementation("org.java-websocket:Java-WebSocket:1.5.4")
    

    
    implementation(libs.androidx.work.runtime.ktx)
    
    // TensorFlow Lite for AI inference (replaces NCNN)
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")  // GPU acceleration
    implementation("org.tensorflow:tensorflow-lite-gpu-api:2.14.0")  // GPU API interfaces
    implementation("org.tensorflow:tensorflow-lite-support:0.4.4")
    
    // OkHttp for Telegram HTTP client with proxy support
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Encrypted SharedPreferences for secure token/owner storage
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    
    // H2 Database - Pure Java embedded SQL (no native dependencies, no .so files)
    // Works for UID 2000 because it's 100% Java bytecode - no Android framework needed
    implementation("com.h2database:h2:2.2.224")

    // Eclipse Paho MQTT - Pure Java MQTT client (no native dependencies)
    // mqttv3 used by HA/Mosquitto publish path (MqttPublisherService).
    // mqttv5 used by BydCloudMqttSubscriber — BYD's EMQ broker only pushes
    // vehicleInfo events to MQTT v5 subscribers; v3.1.1 connects fine but
    // gets zero messages.  The two lib jars use different packages so they
    // coexist cleanly.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5")

    testImplementation(libs.junit)
    // Android's mockable org.json stubs throw in local JVM tests.
    testImplementation("org.json:json:20231013")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
