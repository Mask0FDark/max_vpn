plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("com.chaquo.python") }

android {
 namespace = "ru.vgastream.maxvpn"
 compileSdk = 35
 buildFeatures { buildConfig = true }
 defaultConfig {
  applicationId = "ru.vgastream.maxvpn"
  minSdk = 24
  targetSdk = 35
  versionCode = 8
  versionName = "0.4.2-chat-fix-alpha"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  ndk { abiFilters += listOf("arm64-v8a") }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}

dependencies {
 implementation(files("libs/hev-socks5-tunnel.aar"))
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 androidTestImplementation("androidx.test:runner:1.6.2")
}

chaquopy { defaultConfig { version = "3.11"; pip { install("maxapi-python==2.4.1") } } }
