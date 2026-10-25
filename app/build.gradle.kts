import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "io.github.kvmy666.autoexpand"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.kvmy666.autoexpand"
        minSdk = 35
        targetSdk = 36
        versionCode = 40000
        versionName = "4.0.0"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = file(keystoreProperties.getProperty("storeFile", "../keystore.jks"))
                storePassword = keystoreProperties.getProperty("storePassword", "")
                keyAlias = keystoreProperties.getProperty("keyAlias", "")
                keyPassword = keystoreProperties.getProperty("keyPassword", "")
            } else {
                storeFile = file(System.getenv("KEYSTORE_FILE") ?: "../keystore.jks")
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: ""
                keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            // Fast iteration: no R8/shrink (seconds instead of minutes), and signed with the
            // release key so it installs *over* an existing release build without uninstalling
            // (which would drop the LSPosed scope + prefs).
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // The manifest sets extractNativeLibs="true" so DexKit's libdexkit.so is a real file
        // under the module's lib/arm64/. AGP requires legacy packaging for that layout.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    // Release lint is the biggest single time sink in a release build and catches nothing we need
    // here. Skipping it cuts the loop from minutes to seconds.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    compileOnly(libs.xposed.api)
    implementation(libs.material)
    implementation(libs.appcompat)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.activity.compose)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // Bytecode fallback for OEM ROMs that rename the row/HUD classes. Only initialized when
    // exact-name resolution fails, so the measured OxygenOS path never pays its cost.
    implementation(libs.dexkit)

    // JVM unit tests. Robolectric supplies a real SQLite for ClipboardDatabase; the
    // policy tests need none of it and run on plain JUnit.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
