# Baracuda

Kişisel biyohacking / dijital ikiz Android uygulaması. Kotlin + Jetpack
Compose, Supabase (Postgrest + Auth + Edge Functions), Room (offline-first
yerel önbellek), Health Connect entegrasyonu ve Anthropic API destekli bir
"İkiz" (Twin) katmanından oluşur.

## Mimari (kısaca)

- **`app/`** — Android uygulaması (Kotlin, Jetpack Compose, Hilt DI).
  - `data/` — Room entity/DAO'ları, repository'ler, Supabase DTO'ları.
    `twin/` paketine ASLA bağımlı değildir (bağımlılık yönü tek taraflı:
    twin → data; köprü `twin/IntakeBridge.kt`'dedir).
  - `twin/` — İkiz'in kural motoru (`TwinGuardrails`), durum inşası
    (`TwinStateBuilder`/`TwinState`) ve LLM çağrısı (`TwinEngine`).
  - `sync/` — WorkManager worker'ları (Health Connect senkronu, sabah
    protokolü, haftalık seyir analizi, öğlen hatırlatması).
  - `ui/` — Ekranlar (Dashboard, Log, Lab, Ayarlar, İkiz, Kilo/Bel Çevresi).
- **`supabase/`** — `schema.sql` (Postgrest tabloları + RLS policy'leri) ve
  `functions/twin/` (Deno Edge Function — Anthropic API'ye giden TEK yol;
  API anahtarı yalnızca burada yaşar, istemciye asla gömülmez).

## Kurulum

1. **Supabase projesi** oluştur, `supabase/schema.sql`'i SQL Editor'e
   yapıştırıp çalıştır (ya da `supabase db push`).
2. **Edge Function'ı dağıt:**
   ```
   supabase functions deploy twin
   supabase secrets set ANTHROPIC_API_KEY=sk-ant-...
   ```
   `supabase/functions/twin/index.ts` içindeki kod değişiklikleri otomatik
   dağıtılmaz — her değişiklikten sonra `deploy` tekrar çalıştırılmalı.
3. **`local.properties`** dosyasını proje kökünde `local.properties.example`'ı
   kopyalayarak oluştur, kendi `sdk.dir`/`SUPABASE_URL`/`SUPABASE_ANON_KEY`
   değerlerini gir (`.gitignore`'da — asla commitlenmez).
4. **Build:**
   ```
   ./gradlew assembleDebug
   ```

## Migration testleri — tek seferlik ek adım

`app/src/androidTest/.../MigrationTest.kt`, Room'un `MigrationTestHelper`'ı
ile her şema sürümünün (`v1`→ `v6`) veri kaybetmeden geçtiğini doğrular.
Bunun için `app/schemas/` altında her sürümün JSON export'unun commitlenmiş
olması gerekir (build çıktısı değildir, versiyon kontrollüdür — bkz.
`.gitignore`'daki `!app/schemas/` istisnası). İlk `assembleDebug`'dan sonra:

```
git add app/schemas && git commit -m "app/schemas: şema export'ları"
```

Yeni bir Room migration'ı eklendiğinde bu adım (yalnızca yeni sürümün
JSON'ı için) tekrarlanır.

## Test

```
./gradlew test               # JVM birim testleri (twin/ kural motoru, parser, serializer)
./gradlew connectedAndroidTest # Migration testleri — bağlı cihaz/emülatör + yukarıdaki app/schemas/ adımı gerekir
```
