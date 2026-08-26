package com.aydin.biyohack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aydin.biyohack.data.repository.AuthRepository
import com.aydin.biyohack.data.repository.AuthState
import com.aydin.biyohack.ui.AppNavHost
import com.aydin.biyohack.ui.auth.AuthScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TwinNotifier'ın "sabah protokolü" bildirimi buradan açılır — daha önce
        // bildirimde PendingIntent hiç yoktu, dokunmak yalnızca bildirimi kapatıyordu.
        val openTwin = intent?.getBooleanExtra(EXTRA_OPEN_TWIN, false) ?: false
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Önceden yalnızca Authenticated/değil ayrımı vardı — Supabase Auth'un
                    // her soğuk açılışta diskteki oturumu okurken kısa süre geçtiği
                    // Initializing durumu da "oturum yok" ile aynı kabul ediliyordu.
                    // Sonuç: zaten giriş yapmış bir kullanıcı bile her açılışta kısa bir
                    // an için AuthScreen (giriş formu) görüyordu — görünür bir "çakma".
                    // Bkz. AuthRepository.AuthState.
                    val authState by authRepository.authState.collectAsStateWithLifecycle(
                        initialValue = AuthState.INITIALIZING
                    )
                    when (authState) {
                        AuthState.SIGNED_IN -> AppNavHost(openTwin = openTwin)
                        AuthState.SIGNED_OUT -> AuthScreen()
                        AuthState.INITIALIZING -> Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_TWIN = "open_twin"
    }
}
