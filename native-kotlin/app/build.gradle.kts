plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "app.flint.prototype"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.flint.vpn"
        minSdk = 30
        targetSdk = 36
        versionCode = 3005
        versionName = "8.11.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    flavorDimensions += "screen"
    productFlavors {
        create("phone") {
            dimension = "screen"
            buildConfigField("boolean", "IS_TV", "false")
            manifestPlaceholders["launcherCategory"] = "android.intent.category.LAUNCHER"
            manifestPlaceholders["isTvRequired"] = "false"
            manifestPlaceholders["widgetEnabled"] = "true"
            manifestPlaceholders["distribution"] = "phone"
        }
        create("tv") {
            dimension = "screen"
            buildConfigField("boolean", "IS_TV", "true")
            manifestPlaceholders["launcherCategory"] = "android.intent.category.LEANBACK_LAUNCHER"
            manifestPlaceholders["isTvRequired"] = "true"
            manifestPlaceholders["widgetEnabled"] = "false"
            manifestPlaceholders["distribution"] = "tv"
        }
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        debug { ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64") } }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            ndk { abiFilters += setOf("arm64-v8a", "armeabi-v7a") }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["test"].java.srcDir("../tests/imports")
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    implementation(project(":engine"))
    implementation(files("../engine/libs/libxray.aar"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
