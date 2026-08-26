# Bu dosya app/build.gradle.kts'deki release.proguardFiles(...) tarafından
# referans alınıyordu ama hiç var olmamıştı — isMinifyEnabled = false olduğu
# için R8 şu an bu dosyayı hiç okumuyor, bu yüzden eksikliği sessiz kalıyordu.
# Ama isMinifyEnabled bir gün gerçek bir release build için true'ya çevrilirse
# (APK boyutunu küçültmek/obfuscate etmek için standart adım), Android Gradle
# Plugin burada referans verilen dosyayı bulamayınca build'i doğrudan
# başarısız kılar ("proguard-rules.pro (No such file or directory)").
#
# Şimdilik proje-özel bir keep kuralına ihtiyaç yok:
# - Room (KSP ile derleme zamanında kod üretir) reflection kullanmaz.
# - kotlinx-serialization-json 1.7.3, @Serializable sınıfları için gerekli
#   keep kurallarını kendi consumer-rules.pro'su ile zaten otomatik taşıyor
#   (data/remote/SupabaseDto.kt'deki *Row DTO'ları buna dahil).
# minifyEnabled açıldığında yeni bir ProGuard/R8 hatası çıkarsa kural
# buraya eklenir.

# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.kts.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
