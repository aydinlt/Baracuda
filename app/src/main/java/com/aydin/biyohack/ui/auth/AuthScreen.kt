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

enum class AuthMode { SIGN_IN, SIGN_UP, RESET }

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val mode: AuthMode = AuthMode.SIGN_IN,
    val isLoading: Boolean = false,
    val error: String? = null,
    val resetEmailSent: Boolean = false,
    val signUpSucceeded: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(AuthUiState())
    val ui: StateFlow<AuthUiState> = _ui.asStateFlow()

    fun onEmailChange(value: String) = _ui.update { it.copy(email = value, error = null, signUpSucceeded = false) }
    fun onPasswordChange(value: String) = _ui.update { it.copy(password = value, error = null, signUpSucceeded = false) }

    fun toggleMode() = _ui.update {
        it.copy(
            mode = if (it.mode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN,
            error = null,
            signUpSucceeded = false
        )
    }

    /** "Şifremi unuttum" bağlantısı — yalnızca SIGN_IN modundan erişilebilir. */
    fun openReset() = _ui.update { it.copy(mode = AuthMode.RESET, error = null, resetEmailSent = false) }

    fun backToSignIn() = _ui.update { it.copy(mode = AuthMode.SIGN_IN, error = null, resetEmailSent = false) }

    fun submit() {
        val state = _ui.value
        if (state.mode == AuthMode.RESET) {
            submitReset(state)
            return
        }
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
            _ui.update {
                it.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.message,
                    // Supabase projesinde e-posta onayı açıksa signUp başarılı döner ama
                    // oturum hemen açılmaz (AuthState.SIGNED_OUT kalır, MainActivity bu
                    // ekranı kapatmaz) — önceden kullanıcı hiçbir açıklama olmadan
                    // boşlukta kalıyordu, ne olduğunu anlamıyordu.
                    signUpSucceeded = state.mode == AuthMode.SIGN_UP && result.isSuccess
                )
            }
        }
    }

    /**
     * Uygulamanın önceden hiç şifre sıfırlama yolu yoktu — şifresini unutan
     * tek kullanıcı (Aydın) kalıcı olarak dışarıda kalırdı. Supabase Auth'un
     * `resetPasswordForEmail`'i, hesap varsa sıfırlama bağlantısını e-postayla
     * gönderir; var olup olmadığını burada açık etmemek için sonuç mesajı
     * her durumda aynı ("bağlantı gönderildiyse gelen kutunu kontrol et").
     */
    private fun submitReset(state: AuthUiState) {
        if (state.email.isBlank()) {
            _ui.update { it.copy(error = "E-posta gerekli") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            val result = authRepository.sendPasswordReset(state.email)
            _ui.update {
                it.copy(
                    isLoading = false,
                    error = result.exceptionOrNull()?.message,
                    resetEmailSent = result.isSuccess
                )
            }
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

            if (ui.mode == AuthMode.RESET) {
                Text("Şifremi sıfırla", style = MaterialTheme.typography.titleMedium)

                OutlinedTextField(
                    value = ui.email,
                    onValueChange = viewModel::onEmailChange,
                    label = { Text("E-posta") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )

                ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (ui.resetEmailSent) {
                    Text(
                        "Hesap varsa sıfırlama bağlantısı gönderildi — gelen kutunu kontrol et.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = viewModel::submit,
                    enabled = !ui.isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (ui.isLoading) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text(if (ui.isLoading) "..." else "Sıfırlama bağlantısı gönder")
                }

                TextButton(onClick = viewModel::backToSignIn) { Text("← Giriş ekranına dön") }
                return@Scaffold
            }

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
            if (ui.signUpSucceeded) {
                Text(
                    "Kayıt başarılı. Hesabın e-posta onayı gerektiriyorsa gelen kutunu " +
                        "kontrol edip bağlantıya tıkla, ardından giriş yap.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

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

            if (ui.mode == AuthMode.SIGN_IN) {
                TextButton(onClick = viewModel::openReset) { Text("Şifremi unuttum") }
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
