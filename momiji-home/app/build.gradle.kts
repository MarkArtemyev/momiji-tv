plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "app.momiji.home"
    compileSdk = 35
    defaultConfig {
        applicationId = "app.momiji.home"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "0.4.0"
    }
    buildFeatures { buildConfig = true }
    sourceSets.getByName("main").java.srcDir("../../momiji-network-core/src/main/kotlin")
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
