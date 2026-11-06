plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.dkord"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "app.dkord"
        minSdk = 26
        targetSdk = 37
        versionCode = 4
        versionName = "0.3.1"
    }

    // One stable key for every build, so a new APK installs over the old one and keeps user data.
    val keystore = System.getenv("DKORD_KEYSTORE")
    if (keystore != null) signingConfigs.create("dkord") {
        storeFile = file(keystore); storeType = "pkcs12"
        storePassword = System.getenv("DKORD_KEYSTORE_PASS"); keyAlias = "dkord"; keyPassword = System.getenv("DKORD_KEYSTORE_PASS")
    }

    buildTypes {
        debug { signingConfigs.findByName("dkord")?.let { signingConfig = it } }
        release {
            signingConfigs.findByName("dkord")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    androidResources { generateLocaleConfig = false }

    packaging { resources.excludes += setOf("META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES") }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    coreLibraryDesugaring(libs.desugar)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource.okhttp)

    implementation(libs.newpipe.extractor)
    implementation(libs.okhttp)

    implementation(libs.koin.android)
    implementation(libs.koin.compose)

    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime)
    implementation(libs.datastore)
    implementation(libs.security.crypto)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
