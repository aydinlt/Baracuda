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
import com.aydin.biyohack.ui.auth.AuthScreen
import com.aydin.biyohack.ui.dashboard.DashboardScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val isSignedIn by authRepository.isSignedIn.collectAsStateWithLifecycle(initialValue = false)
                    if (isSignedIn) DashboardScreen() else AuthScreen()
                }
            }
        }
    }
}
