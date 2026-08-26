package com.aydin.biyohack.data.repository

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Oturum durumu — [SessionStatus.Initializing] AYRI bir dal olarak tutulur.
 * Önceden `isSignedIn: Flow<Boolean>`, Initializing'i de (henüz bilinmiyor)
 * NotAuthenticated ile aynı şekilde `false`'a eşliyordu. Supabase Auth,
 * diskteki kalıcı oturumu okurken (uygulama her soğuk açılışında) kısa bir
 * süre Initializing durumunda kalır — bu sürede MainActivity zaten oturum
 * açmış bir kullanıcıya bile AuthScreen'i (giriş formu) gösterip, gerçek
 * durum netleşince Dashboard'a geçiyordu. Sonuç: her soğuk açılışta görünür
 * bir "giriş ekranı çakması" (flash of wrong content).
 */
enum class AuthState { INITIALIZING, SIGNED_IN, SIGNED_OUT }

/**
 * Supabase Auth'un ince sarmalayıcısı — e-posta/şifre ile giriş/kayıt.
 * Tek kullanıcı (Aydın) için tasarlandı ama Supabase Auth zaten tek
 * hesap sınırlaması getirmiyor; ileride ikinci bir kullanıcı gerekirse
 * (ör. hekim erişimi) değişiklik gerekmez.
 */
class AuthRepository(private val auth: Auth) {

    val authState: Flow<AuthState> = auth.sessionStatus.map {
        when (it) {
            is SessionStatus.Authenticated -> AuthState.SIGNED_IN
            is SessionStatus.Initializing -> AuthState.INITIALIZING
            else -> AuthState.SIGNED_OUT
        }
    }

    suspend fun signIn(email: String, password: String): Result<Unit> = runCatching {
        auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signUp(email: String, password: String): Result<Unit> = runCatching {
        auth.signUpWith(Email) {
            this.email = email
            this.password = password
        }
    }

    suspend fun signOut(): Result<Unit> = runCatching { auth.signOut() }

    /**
     * Şifre sıfırlama bağlantısını e-postayla gönderir. Daha önce hiç
     * sıfırlama yolu yoktu — şifresini unutan kullanıcı kalıcı olarak
     * dışarıda kalırdı. Redirect URL kasıtlı olarak verilmedi: uygulamanın
     * şifre-sıfırlama deep link'ini yakalayacak bir ekranı yok, bu yüzden
     * kullanıcı Supabase'in varsayılan sıfırlama sayfasını (proje Auth
     * ayarlarındaki Site URL) kullanıp ardından uygulamada yeni şifreyle
     * tekrar giriş yapar.
     */
    suspend fun sendPasswordReset(email: String): Result<Unit> = runCatching {
        auth.resetPasswordForEmail(email)
    }

    fun currentUserId(): String? = auth.currentUserOrNull()?.id
}
