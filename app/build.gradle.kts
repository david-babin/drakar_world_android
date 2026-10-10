plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// version shown in the settings screen: the commit being built (CI) or "dev"
val commit: String = (System.getenv("GITHUB_SHA") ?: "dev").take(7)

android {
    namespace = "tech.drakar.wallpaper"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.drakar.wallpaper"
        minSdk = 29
        targetSdk = 36
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1.$versionCode"
        buildConfigField("String", "COMMIT", "\"$commit\"")
        buildConfigField("String", "SITE", "\"https://world.drakar.tech\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // one fixed key for every build (sideloading: a new build installs over the previous one).
    // Not a secret: a store release would use a private key kept out of the repository.
    signingConfigs {
        create("sideload") {
            storeFile = rootProject.file("signing/sideload.jks")
            storePassword = "drakar-sideload"; keyAlias = "drakar"; keyPassword = "drakar-sideload"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug { signingConfig = signingConfigs.getByName("sideload") }
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.javascriptengine:javascriptengine:1.1.1")
    implementation("androidx.work:work-runtime:2.10.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
