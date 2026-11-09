plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wsfisch.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wsfisch.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0-alpha"
    }
}

dependencies {
    implementation(project(":scriptengine"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
