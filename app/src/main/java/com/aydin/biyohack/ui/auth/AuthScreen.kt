package com.aydin.biyohack.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aydin.biyohack.data.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthMode { SIGN_IN, SIGN_UP }

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val mode: AuthMode = AuthMode.SIGN_IN,
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    fun onEmailChange(value: String) = _ui.update { it.copy(email = value, error = null) }
    fun onPasswordChange(value: String) = _ui.update { it.copy(password = value, error = null) }

    fun toggleMode() = _ui.update {
        it.copy(mode = if (it.mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN, error = null)
    }

    fun submit() {
        val state = _ui.value
        if (state.email.isBlank() || state.password.isBlank()) {
            _ui.update { it.copy(error = "E-posta ve şifre gerekli") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            val result = if (state.mode == AuthMode.SIGN_IN)
                authRepository.signIn(state.email, state.password)
            else
                authRepository.signUp(state.email, state.password)
            _ui.update { it.copy(isLoading = false, error = result.exceptionOrNull()?.message) }
        }
    }
}

@Composable
fun AuthScreen(viewModel: AuthViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Baracuda", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (ui.mode == AuthMode.SIGN_IN) "Giriş yap" else "Hesap oluştur",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedTextField(
                value = ui.email,
                onValueChange = viewModel::onEmailChange,
                label = { Text("E-posta") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = ui.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Şifre") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth()
            )

            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = viewModel::submit,
                enabled = !ui.isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (ui.isLoading) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text(
                    if (ui.isLoading) "..."
                    else if (ui.mode == AuthMode.SIGN_IN) "Giriş yap" else "Kayıt ol"
                )
            }

            TextButton(onClick = viewModel::toggleMode) {
                Text(
                    if (ui.mode == AuthMode.SIGN_IN) "Hesabın yok mu? Kayıt ol"
                    else "Zaten hesabın var mı? Giriş yap"
                )
            }
        }
    }
}
