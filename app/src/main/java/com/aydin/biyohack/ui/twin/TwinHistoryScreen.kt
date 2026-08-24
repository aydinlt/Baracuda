package com.aydin.biyohack.ui.twin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aydin.biyohack.data.repository.AuthRepository
import com.aydin.biyohack.data.repository.ProfileRepository
import com.aydin.biyohack.data.repository.TwinOutputHistoryEntry
import com.aydin.biyohack.data.repository.TwinRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class TwinHistoryUiState(
    val entries: List<TwinOutputHistoryEntry> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val morningProtocolTime: String = "07:30"
)

@HiltViewModel
class TwinHistoryViewModel @Inject constructor(
    private val twinRepository: TwinRepository,
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(TwinHistoryUiState())
    val ui: StateFlow<TwinHistoryUiState> = _ui.asStateFlow()

    init {
        refresh()
        // Boş durum metni önceden sabit "07:30" yazıyordu — TwinMorningWorker artık
        // kullanıcının Ayarlar'da belirlediği kalkış hedefi + 30dk'ya göre çalışıyor
        // (bkz. Hafta 17), bu ekran hâlâ eski sabit metni gösteriyordu.
        authRepository.currentUserId()?.let { userId ->
            viewModelScope.launch {
                profileRepository.observe(userId).collect { profile ->
                    profile?.let { p ->
                        val formatter = DateTimeFormatter.ofPattern("HH:mm")
                        _ui.update { it.copy(morningProtocolTime = p.wakeTarget.plusMinutes(30).format(formatter)) }
                    }
                }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            val result = twinRepository.observeHistory()
            _ui.update {
                it.copy(
                    isLoading = false,
                    entries = result.getOrNull() ?: it.entries,
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }
}

private fun triggerLabel(trigger: String) = when (trigger) {
    "MORNING_PROTOCOL" -> "Sabah protokolü"
    "MANUAL" -> "Manuel / haftalık"
    else -> trigger
}

@Composable
fun TwinHistoryScreen(onBack: () -> Unit, viewModel: TwinHistoryViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("İkiz Geçmişi") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Geri") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (ui.isLoading) CircularProgressIndicator()
            ui.error?.let { Text("Hata: $it", color = MaterialTheme.colorScheme.error) }

            if (!ui.isLoading && ui.entries.isEmpty()) {
                Text("Henüz kayıt yok — sabah protokolü her gün ${ui.morningProtocolTime}'da otomatik çalışır.")
            }

            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ui.entries) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${triggerLabel(entry.trigger)} · ${entry.tier}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(entry.headline, style = MaterialTheme.typography.bodyLarge)
                            Text(entry.brief, style = MaterialTheme.typography.bodySmall)
                            Text(entry.createdAt, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
