plugins {
    id("com.android.library")
    id("kotlin-android")
    id("kotlin-kapt")
    id("android-module-dependencies")
    id("all-open-dependencies")
    id("test-module-dependencies")
}

android {
    namespace = "app.aaps.pump.ypsopump"
    defaultConfig {
        minSdk = 31
    }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:libraries"))
    implementation(project(":core:objects"))
    implementation(project(":core:ui"))
    implementation(project(":core:utils"))
    implementation(project(":core:validators"))

    // Lazysodium for XChaCha20-Poly1305 + Curve25519
    implementation("com.goterl:lazysodium-android:5.1.0")
    implementation("net.java.dev.jna:jna:5.14.0@aar")

    // Dagger
    kapt(libs.dagger.android.processor)
    kapt(libs.dagger.compiler)

    testImplementation(project(":shared:tests"))
}
