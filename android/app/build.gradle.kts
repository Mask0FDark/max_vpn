plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
 namespace = "ru.vgastream.maxvpn"
 compileSdk = 35
 buildFeatures { buildConfig = true }
 defaultConfig {
  applicationId = "ru.vgastream.maxvpn"
  minSdk = 24
  targetSdk = 35
  versionCode = 7
  versionName = "0.4.1-max-phone-alpha"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
