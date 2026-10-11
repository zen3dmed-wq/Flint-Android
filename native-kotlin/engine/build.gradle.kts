plugins { id("com.android.library"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "org.amnezia.vpn.util"
    compileSdk = 36
    defaultConfig { minSdk = 30; consumerProguardFiles("consumer-rules.pro") }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    compileOnly(files("libs/libxray.aar"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.annotation:annotation:1.8.2")
}
