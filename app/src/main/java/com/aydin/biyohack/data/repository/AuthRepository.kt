package com.aydin.biyohack.data.repository

import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Supabase Auth'un ince sarmalayıcısı — e-posta/şifre ile giriş/kayıt.
 * Tek kullanıcı (Aydın) için tasarlandı ama Supabase Auth zaten tek
 * hesap sınırlaması getirmiyor; ileride ikinci bir kullanıcı gerekirse
 * (ör. hekim erişimi) değişiklik gerekmez.
 */
class AuthRepository(private val auth: Auth) {

    val isSignedIn: Flow<Boolean> = auth.sessionStatus.map { it is SessionStatus.Authenticated }

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

    fun currentUserId(): String? = auth.currentUserOrNull()?.id
}
