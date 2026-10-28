plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.controlprestamos.consultas"
    compileSdk { version = release(37) }
    defaultConfig {
        applicationId = "com.example.controlprestamos.consultas"
        minSdk = 26
        targetSdk = 37
        versionCode = 5
        versionName = "1.3.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    sourceSets.getByName("main").kotlin.srcDir("../shared/consultas/src/main/java")
    sourceSets.getByName("main").kotlin.srcDir("../shared/presentation/src/main/java")
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
