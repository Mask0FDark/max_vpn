plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val relayToken = providers.environmentVariable("MAXVPN_RELAY_TOKEN").orElse("").get()

android {
 namespace = "ru.vgastream.maxvpn"
 compileSdk = 35
 buildFeatures { buildConfig = true }
 defaultConfig {
  applicationId = "ru.vgastream.maxvpn"
  minSdk = 24
  targetSdk = 35
  versionCode = 2
  versionName = "0.2.0"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  buildConfigField("String", "RELAY_TOKEN", "\"$relayToken\"")
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
