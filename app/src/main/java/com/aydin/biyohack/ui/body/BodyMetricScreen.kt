package com.aydin.biyohack.ui.body

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aydin.biyohack.data.BodyMetric
import com.aydin.biyohack.data.repository.HealthSyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BodyMetricUiState(
    val recent: List<BodyMetric> = emptyList(),
    val isSaving: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class BodyMetricViewModel @Inject constructor(
    private val repository: HealthSyncRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(BodyMetricUiState())
    val ui: StateFlow<BodyMetricUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeRecentBodyMetrics().collect { list ->
                _ui.update { it.copy(recent = list) }
            }
        }
    }

    fun log(weightKg: Double?, waistCm: Double?) {
        viewModelScope.launch {
            _ui.update { it.copy(isSaving = true, error = null) }
            val result = repository.logBodyMetric(weightKg, waistCm)
            _ui.update { it.copy(isSaving = false, error = result.exceptionOrNull()?.message) }
        }
    }
}

@Composable
fun BodyMetricScreen(onBack: () -> Unit, viewModel: BodyMetricViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    var weight by remember { mutableStateOf("") }
    var waist by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kilo / Bel Çevresi") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Geri") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                "Bugünkü ölçüm — aynı gün tekrar girersen üzerine yazılır.",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = weight,
                onValueChange = { weight = it },
                label = { Text("Kilo (kg)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            OutlinedTextField(
                value = waist,
                onValueChange = { waist = it },
                label = { Text("Bel çevresi (cm)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Button(
                onClick = { viewModel.log(weight.toDoubleOrNull(), waist.toDoubleOrNull()) },
                enabled = !ui.isSaving && (weight.toDoubleOrNull() != null || waist.toDoubleOrNull() != null),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) {
                Text(if (ui.isSaving) "Kaydediliyor..." else "Kaydet")
            }
            ui.error?.let { Text("Hata: $it", color = MaterialTheme.colorScheme.error) }

            Text(
                "Seyir",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // "Seyir" (LabScreen'deki "Laboratuvar Seyri" ile aynı isimlendirme) ama
                // önceden bu ekran da hiçbir ölçümü bir öncekiyle karşılaştırmıyordu —
                // yalnızca tek tek, birbirinden kopuk değerler listeleniyordu (bkz. Hafta
                // 34 commit notu, LabScreen'de aynı sınıftan eksik için yapılan düzeltme).
                // observeRecentBodyMetrics() epochDay DESC sıralı döndüğü için bir sonraki
                // index'teki kayıt otomatik olarak "bir önceki" (daha eski) ölçümdür.
                itemsIndexed(ui.recent) { index, metric ->
                    val previous = ui.recent.getOrNull(index + 1)
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(metric.date.toString(), style = MaterialTheme.typography.bodySmall)
                            Text(
                                listOfNotNull(
                                    metric.weightKg?.let { "%.1f kg".format(it) },
                                    metric.waistCm?.let { "%.1f cm bel".format(it) }
                                ).joinToString(" · ")
                            )
                            previous?.let { p ->
                                val weightDelta = if (metric.weightKg != null && p.weightKg != null)
                                    metric.weightKg - p.weightKg else null
                                val waistDelta = if (metric.waistCm != null && p.waistCm != null)
                                    metric.waistCm - p.waistCm else null
                                val deltaParts = listOfNotNull(
                                    weightDelta?.let { "${if (it >= 0) "+" else ""}${"%.1f".format(it)} kg" },
                                    waistDelta?.let { "${if (it >= 0) "+" else ""}${"%.1f".format(it)} cm" }
                                )
                                if (deltaParts.isNotEmpty()) {
                                    Text(
                                        "${deltaParts.joinToString(" · ")} (önceki: ${p.date})",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
