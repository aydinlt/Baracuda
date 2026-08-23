package com.aydin.biyohack.ui.twin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.aydin.biyohack.data.repository.TwinRepository
import com.aydin.biyohack.twin.TwinOutput
import com.aydin.biyohack.twin.Trigger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TwinUiState(
    val isLoading: Boolean = false,
    val output: TwinOutput? = null,
    val error: String? = null
)

@HiltViewModel
class TwinViewModel @Inject constructor(
    private val twinRepository: TwinRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(TwinUiState())
    val ui: StateFlow<TwinUiState> = _ui.asStateFlow()

    fun runMorningProtocol() = run { twinRepository.runProtocol(Trigger.MORNING_PROTOCOL) }
    fun runManual() = run { twinRepository.runProtocol(Trigger.MANUAL) }
    fun runWeeklyReview() = run { twinRepository.runWeeklyReview() }

    private fun run(call: suspend () -> Result<TwinOutput>) {
        viewModelScope.launch {
            _ui.update { it.copy(isLoading = true, error = null) }
            val result = call()
            _ui.update {
                it.copy(
                    isLoading = false,
                    output = result.getOrNull() ?: it.output,
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }
}

@Composable
fun TwinScreen(onBack: () -> Unit, viewModel: TwinViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("İkiz") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("← Geri") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = viewModel::runMorningProtocol, enabled = !ui.isLoading, modifier = Modifier.fillMaxWidth()) {
                Text("Sabah protokolünü çalıştır")
            }
            Button(onClick = viewModel::runManual, enabled = !ui.isLoading, modifier = Modifier.fillMaxWidth()) {
                Text("Manuel iste")
            }
            Button(onClick = viewModel::runWeeklyReview, enabled = !ui.isLoading, modifier = Modifier.fillMaxWidth()) {
                Text("Haftalık seyir analizi")
            }

            if (ui.isLoading) CircularProgressIndicator()
            ui.error?.let { Text("Hata: $it", color = MaterialTheme.colorScheme.error) }

            ui.output?.let { output ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(output.headline, style = MaterialTheme.typography.titleLarge)
                        Text(output.brief)
                    }
                }

                if (output.actions.isNotEmpty()) {
                    Text("Aksiyonlar", style = MaterialTheme.typography.titleMedium)
                    output.actions.forEach { a ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text("${a.time} — ${a.action}", style = MaterialTheme.typography.bodyLarge)
                                Text(a.why, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                if (output.clinicalFlags.isNotEmpty()) {
                    Text("Klinik bayraklar", style = MaterialTheme.typography.titleMedium)
                    output.clinicalFlags.forEach { f ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(f.finding, style = MaterialTheme.typography.bodyLarge)
                                Text(f.status, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                if (output.deferred.isNotEmpty()) {
                    Text("Ötelenenler", style = MaterialTheme.typography.titleMedium)
                    output.deferred.forEach { (item, reason) -> Text("• $item — $reason") }
                }

                if (output.dataGaps.isNotEmpty()) {
                    Text("Veri boşlukları", style = MaterialTheme.typography.titleMedium)
                    output.dataGaps.forEach { Text("• $it") }
                }
            }
        }
    }
}
