import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 서명 정보는 저장소 밖 keystore.properties 에서 읽는다 (없으면 release 도 debug 키로 서명).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.tlsgusdlf33.lottolab"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tlsgusdlf33.lottolab"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.4.0"

        // AdMob: 현재는 Google 공식 테스트 ID. 출시 전 본인 AdMob ID 로 교체.
        manifestPlaceholders["admobAppId"] = "ca-app-pub-3940256099942544~3347511713"
        buildConfigField("String", "BANNER_AD_UNIT", "\"ca-app-pub-3940256099942544/9214589741\"")
        buildConfigField("String", "INTERSTITIAL_AD_UNIT", "\"ca-app-pub-3940256099942544/1033173712\"")
        buildConfigField(
            "String", "DATA_BASE_URL",
            "\"https://raw.githubusercontent.com/tlsgusdlf33/A5/claude/korean-lottery-prediction-1qnjpk/\""
        )
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    // sideload: 폰에 직접 설치하는 테스트용 (앱 안에서 업데이트).
    // play: Google Play 출시용 — Play 정책상 자체 업데이트 기능과 설치 권한을 넣지 않는다.
    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") { dimension = "distribution" }
        create("play") { dimension = "distribution" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.fragment:fragment-ktx:1.8.5")  // AdMob 이 끌어오는 구버전 대체 (ActivityResult API 요구)
    implementation("com.google.android.gms:play-services-ads:23.6.0")
    implementation("com.google.android.ump:user-messaging-platform:3.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
