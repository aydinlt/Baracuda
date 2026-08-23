import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

// ────────────────────────────────────────────────────────────
// Supabase anahtarları: repoya ASLA commitlenmez. local.properties
// (proje kökünde, .gitignore'da) veya CI ortam değişkeninden okunur.
// local.properties örneği:
//   SUPABASE_URL=https://xxxx.supabase.co
//   SUPABASE_ANON_KEY=eyJ...
// (anon key public/istemci anahtarıdır, RLS ile korunur — service_role
// anahtarı hiçbir zaman istemciye konmaz, yalnızca Edge Function'larda.)
// ────────────────────────────────────────────────────────────
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(key: String): String =
    localProperties.getProperty(key) ?: System.getenv(key) ?: ""

android {
    namespace = "com.aydin.biyohack"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aydin.biyohack"
        minSdk = 28 // Health Connect kayıt tiplerinin tamamı için güvenli alt sınır
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-week1"

        buildConfigField("String", "SUPABASE_URL", "\"${secret("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${secret("SUPABASE_ANON_KEY")}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        // MigrationTest'in eski şema JSON'larını (1.json, 2.json, ...) bulması için.
        // İlk `./gradlew assembleDebug` sonrası app/schemas/ altında üretilirler —
        // build çıktısı değildir, commitlenmesi gerekir (bkz. .gitignore istisnası).
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }
}

ksp {
    // Room şema geçmişini diske yazar — migration testleri Hafta 2'de bunu kullanacak.
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ---- Compose ----
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")

    // ---- Room (yerel önbellek / offline-first) ----
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")

    // ---- Health Connect (Samsung Health verisi de cihazda Health Connect'e
    // senkronize olduğu için tek okuma katmanı burası — bkz. HealthConnectManager.kt) ----
    implementation("androidx.health.connect:connect-client:1.1.0-alpha07")

    // ---- Supabase (Postgrest + Auth) ----
    implementation(platform("io.github.jan-tennert.supabase:bom:3.0.3"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.ktor:ktor-client-okhttp:3.0.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ---- WorkManager (arka planda periyodik Health Connect → Supabase sync) ----
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    // ---- Hilt (DI) ----
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    testImplementation("junit:junit:4.13.2")
    // Android'in org.json'ı JVM birim testlerinde stub'dır (metodları çağırınca patlar) —
    // TwinEngine.parse() gerçek JSON ayrıştırdığı için gerçek bir org.json implementasyonu gerekiyor.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
