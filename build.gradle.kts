// Root build dosyası — tüm modüller için ortak plugin sürümlerini pinler,
// hiçbir modülde uygulanmaz (apply false), her modül ihtiyacına göre kendi
// build.gradle.kts'inde plugin id'sini uygular.
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
}

// NOT: Yukarıdaki sürümler bu paketin yazıldığı tarih itibarıyla stabil
// sürümlerdir. Android Studio "Upgrade" önerisi verirse AGP/Kotlin/KSP
// sürümlerini birlikte (uyumluluk tablosuna göre) güncelle, tek tek değil.
