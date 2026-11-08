plugins {
	id("com.android.application")
	id("org.jetbrains.kotlin.plugin.compose")
}

// VERSION at the top of the repo is shared with the web app, which uses it to offer updates.
val appVersion = rootProject.file("../VERSION").readText().trim()
val appVersionCode = appVersion.split(".").map(String::toInt).let { (major, minor, patch) ->
	major * 10_000 + minor * 100 + patch
}

android {
	namespace = "com.wonderhoylabs.shira"
	compileSdk = 37

	defaultConfig {
		applicationId = "com.wonderhoylabs.shira"
		minSdk = 29
		targetSdk = 37
		versionCode = appVersionCode
		versionName = appVersion
		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	// Set these to sign with your own key. Without them a release APK still installs, because it
	// falls back to the debug key, but Android refuses to update an app across different keys.
	signingConfigs {
		System.getenv("SHIRA_KEYSTORE")?.let { keystore ->
			create("release") {
				storeFile = file(keystore)
				storePassword = System.getenv("SHIRA_KEYSTORE_PASSWORD")
				keyAlias = System.getenv("SHIRA_KEY_ALIAS")
				keyPassword = System.getenv("SHIRA_KEY_PASSWORD")
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

	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_17
		targetCompatibility = JavaVersion.VERSION_17
	}

	buildFeatures {
		buildConfig = true
		compose = true
	}

	testOptions {
		unitTests.isReturnDefaultValues = true
	}
}

dependencies {
	implementation(platform("androidx.compose:compose-bom:2026.09.00"))
	implementation("androidx.activity:activity-compose:1.13.0")
	implementation("androidx.compose.foundation:foundation")
	implementation("androidx.compose.material3:material3")
	implementation("androidx.compose.ui:ui")
	implementation("androidx.core:core-ktx:1.19.1")
	implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")

	testImplementation("junit:junit:4.13.2")

	androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
	androidTestImplementation("androidx.compose.ui:ui-test-junit4")
	// Compose's test library brings an older one, which can't run on recent versions of Android.
	androidTestImplementation("androidx.test.espresso:espresso-core:3.8.0-alpha03")
	androidTestImplementation("androidx.test:runner:1.8.0-alpha03")
	debugImplementation("androidx.compose.ui:ui-test-manifest")
}
