// Shira's network stack as a library: TCP, UDP and DNS ended in Kotlin, and the framing that carries them over a
// socket. Nothing here needs Android, so any app with a TUN device, or any test, can use it.
plugins {
	id("com.android.library")
}

android {
	namespace = "com.wonderhoylabs.shira.tunnel"
	compileSdk = 37

	defaultConfig {
		minSdk = 29
	}

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	testOptions {
		unitTests.isReturnDefaultValues = true
	}
}

dependencies {
	testImplementation("junit:junit:4.13.2")
}
