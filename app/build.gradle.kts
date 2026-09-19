import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing reads from a local, untracked keystore.properties (see keystore.properties.example)
// so the real keystore path/passwords never end up committed. Missing file = unsigned release build,
// which is fine until you're ready to sign (Android Studio's "Generate Signed Bundle" wizard works too).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

// Production backend for purchase verification. Set `backendUrl=https://...` in gradle.properties
// (or pass -PbackendUrl=...); release builds refuse anything that isn't https.
val backendUrl = (project.findProperty("backendUrl") as String?).orEmpty()

android {
    namespace = "com.humanrewrite.keyboard"
    compileSdk = 35
    // r28+ aligns native libraries to 16 KB pages, which Android 15+ devices and emulators require.
    ndkVersion = "28.2.13676358"

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        applicationId = "com.humanrewrite.keyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "BACKEND_URL", "\"$backendUrl\"")

        // Real phones are arm64. x86_64 (emulators) is added for debug only, below: the x86_64
        // build assumes AVX2 and would crash on an x86 device without it.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                // Even debug builds compile llama.cpp optimized; unoptimized inference is ~10x slower.
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            ndk { abiFilters += "x86_64" }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // Without a signingConfig, this is an unsigned release build (fine for local testing;
            // Play Console needs a signed one — see docs/play-launch-checklist.md).
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.android.billingclient:billing-ktx:7.1.1")
    // Proves to the backend that a purchase-verification request came from an unmodified copy of
    // this app, not a patched APK with the entitlement check removed — see IntegrityGateway.kt.
    implementation("com.google.android.play:integrity:1.6.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
    sourceCompatibility = "17"
    targetCompatibility = "17"
}
