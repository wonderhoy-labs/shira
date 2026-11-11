// The phone's side of Shira as a library: a VPN service that waits for a page over adb and carries the phone's traffic
// to it, with the bridge between the page's socket and the tunnel. An app subclasses LinkVpnService with its own
// socket name, words and icon. Shira's app is one; Shiho's is another.
plugins {
	id("com.android.library")
}

android {
	namespace = "com.wonderhoylabs.shira.vpn"
	compileSdk = 37

	defaultConfig {
		minSdk = 29
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}
}

dependencies {
	api(project(":tunnel"))
	implementation("androidx.activity:activity:1.13.0")
	implementation("androidx.core:core-ktx:1.19.1")
	api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
}
