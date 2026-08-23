package com.aydin.biyohack.data.remote

import com.aydin.biyohack.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json

/**
 * Tek Supabase istemcisi — Hilt tarafından @Singleton olarak enjekte edilir
 * (bkz. di/AppModule.kt). Burada yalnızca ANON KEY kullanılır; Row Level
 * Security (supabase/schema.sql) her isteği auth.uid() ile sınırlar.
 * service_role anahtarı istemciye ASLA konmaz — yalnızca
 * supabase/functions/twin/index.ts gibi Edge Function secret'larında yaşar.
 */
fun createBiyohackSupabaseClient(): SupabaseClient = createSupabaseClient(
    supabaseUrl = BuildConfig.SUPABASE_URL,
    supabaseKey = BuildConfig.SUPABASE_ANON_KEY
) {
    install(Postgrest)
    install(Auth)
    defaultSerializer = KotlinXSerializer(
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    )
}
