package com.aydin.biyohack

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aydin.biyohack.data.repository.AuthRepository
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
                    val isSignedIn by authRepository.isSignedIn.collectAsStateWithLifecycle(initialValue = false)
                    if (isSignedIn) AppNavHost(openTwin = openTwin) else AuthScreen()
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_TWIN = "open_twin"
    }
}
