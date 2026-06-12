import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20"
}

val signingProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.reader(Charsets.UTF_8).use { load(it) }
        val bomStoreFile = remove("\uFEFFstoreFile")
        if (bomStoreFile != null && getProperty("storeFile") == null) {
            setProperty("storeFile", bomStoreFile.toString())
        }
    }
}

var releaseStoreFile = System.getenv("ANDROID_KEYSTORE_FILE") ?: signingProps.getProperty("storeFile")
val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD") ?: signingProps.getProperty("storePassword")
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: signingProps.getProperty("keyAlias")
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: signingProps.getProperty("keyPassword")

val currentGitSha: String = run {
    val envSha = System.getenv("SOURCE_SHA") ?: System.getenv("GITHUB_SHA")
    if (!envSha.isNullOrBlank()) return@run envSha.trim().take(7)
    try {
        val process = ProcessBuilder("git", "rev-parse", "--short=7", "HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        output.ifEmpty { "unknown" }
    } catch (_: Throwable) {
        "unknown"
    }
}

if (releaseStoreFile != null) {
    releaseStoreFile = releaseStoreFile!!.replace("\\", "/")
    val configuredStoreFile = file(releaseStoreFile!!)
    if (!configuredStoreFile.exists()) {
        val fallbackStoreFile = rootProject.file(".tools/signing/${configuredStoreFile.name}")
        if (fallbackStoreFile.exists()) {
            releaseStoreFile = fallbackStoreFile.absolutePath.replace("\\", "/")
        }
    }
}

val releaseSigningReady = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "ru.foric27.cluster"
    compileSdk = 37

    defaultConfig {
        applicationId = "ru.foric27.cluster"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.0.2"
        buildConfigField("String", "GIT_SHA", "\"$currentGitSha\"")
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningReady) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
        compose = true
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/license/**",
                "META-INF/native-image/**",
            )
            pickFirsts += listOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
            )
        }
    }

    lint {
        disable += listOf("ProtectedPermissions")
    }
}

dependencies {
    implementation(libs.bundles.androidx)
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.ftpserver.core)
    implementation(libs.mina.core)
    implementation(libs.slf4j.nop)
    implementation(libs.libsu.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    implementation(libs.okhttp)
    implementation(libs.bundles.security)

    constraints {
        implementation("org.apache.httpcomponents:httpclient:4.5.14") {
            because("CVE-2020-13956: transitive XSS in httpclient < 4.5.13")
        }
    }

    testImplementation(libs.junit)
}
