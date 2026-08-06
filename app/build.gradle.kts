import java.util.Properties
import java.io.FileInputStream

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun prop(key: String, fallback: String = ""): String =
    localProps.getProperty(key) ?: System.getenv(key) ?: fallback

// Escape arbitrary text into a Java string literal for buildConfigField.
// Backslash MUST be escaped before any other char that itself uses backslashes.
fun javaStringLiteral(value: String): String {
    val escaped = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
    return "\"$escaped\""
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.klk.hams"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.klk.hams"
        minSdk = 33
        targetSdk = 35
        // versionCode MUST increase on every build you distribute. Android refuses
        // to install an APK whose versionCode is lower than the one already on the
        // device, and MDM/update flows use it to detect that an update exists.
        // Bump it even for a hotfix; never reuse a number that has left this machine.
        //
        // versionName is the human-readable label. It is what each handset reports
        // to the registry via check_binding, and what shows in the admin unit list.
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // WIALON_TOKEN is a Wialon REST secret used only by the backend / curl —
        // the app does IPS push and never reads it. Kept out of the APK on purpose;
        // it still lives in the *.env profiles for backend/curl use.
        buildConfigField("String", "IPS_HOST",         javaStringLiteral(prop("IPS_HOST", "185.213.1.24")))
        buildConfigField("int",    "IPS_PORT",         prop("IPS_PORT", "20332"))
        buildConfigField("String", "DEVICE_UNIQUE_ID", javaStringLiteral(prop("DEVICE_UNIQUE_ID", "HAMS_TEST_001")))
        buildConfigField("String", "HAMS_CLAIM_SECRET", javaStringLiteral(prop("HAMS_CLAIM_SECRET")))
        buildConfigField("String", "MANUAL_CLAIM_URL", javaStringLiteral(prop("MANUAL_CLAIM_URL")))
        buildConfigField("String", "RELEASE_URL",      javaStringLiteral(prop("RELEASE_URL")))
        buildConfigField("String", "VERIFY_URL",       javaStringLiteral(prop("VERIFY_URL")))
    }

    // Release signing. The keystore itself is NEVER committed - it lives outside
    // the repo and its paths/passwords come from local.properties (gitignored).
    //
    // THIS KEY IS PERMANENT. Android ties the app identity - and the per-app
    // ANDROID_ID that the provisioning registry uses as device_fingerprint - to
    // the signing key. Signing a later build with a different key means every
    // handset must be uninstalled, admin-released and re-paired. Back the
    // keystore up somewhere that survives this laptop.
    //
    // local.properties keys (see local.properties.example):
    //   RELEASE_STORE_FILE, RELEASE_STORE_PASSWORD, RELEASE_KEY_ALIAS, RELEASE_KEY_PASSWORD
    signingConfigs {
        create("release") {
            val storePath = prop("RELEASE_STORE_FILE")
            if (storePath.isNotBlank() && rootProject.file(storePath).exists()) {
                storeFile = rootProject.file(storePath)
                storePassword = prop("RELEASE_STORE_PASSWORD")
                keyAlias = prop("RELEASE_KEY_ALIAS")
                keyPassword = prop("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Only attach the signing config once a keystore is actually configured,
            // so a checkout without local.properties still builds (unsigned) instead
            // of failing. assembleRelease WITHOUT this produces an APK Android will
            // refuse to install - check the log line below before distributing.
            signingConfig = signingConfigs.getByName("release").takeIf {
                it.storeFile?.exists() == true
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// Warn rather than silently shipping an unsigned APK. Checks the file actually
// EXISTS, not merely that the property is set - a typo'd or stale path would
// otherwise fall through to an unsigned build with no warning at all.
run {
    val storePath = prop("RELEASE_STORE_FILE")
    val reason = when {
        storePath.isBlank() -> "RELEASE_STORE_FILE not set in local.properties"
        !rootProject.file(storePath).exists() ->
            "keystore not found at '$storePath' (resolved to ${rootProject.file(storePath).absolutePath})"
        else -> null
    }
    if (reason != null) {
        logger.lifecycle(
            "HAMS: $reason - :app:assembleRelease will produce an UNSIGNED APK " +
                "that Android will refuse to install."
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
