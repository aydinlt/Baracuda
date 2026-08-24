package com.aydin.biyohack.ui.log

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.aydin.biyohack.data.IntakeKind
import com.aydin.biyohack.data.repository.HealthSyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LogUiState(val lastLogged: String? = null, val error: String? = null)

@HiltViewModel
class LogViewModel @Inject constructor(
    private val repository: HealthSyncRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(LogUiState())
    val ui: StateFlow<LogUiState> = _ui.asStateFlow()

    fun log(kind: IntakeKind, label: String, amount: Double? = null, unit: String? = null) {
        viewModelScope.launch {
            val result = repository.logIntake(kind, label, amount, unit)
            _ui.update {
                it.copy(
                    lastLogged = if (result.isSuccess) label else it.lastLogged,
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }
}

private data class SupplementPreset(val label: String, val amount: Double? = null, val unit: String? = null)

/** system_twin.md Bölüm E — takviye zamanlama matrisindeki kalemler. */
private val SUPPLEMENT_PRESETS = listOf(
    SupplementPreset("NR/NAD"),
    SupplementPreset("D3+K2"),
    SupplementPreset("Omega-3"),
    SupplementPreset("EGCG"),
    SupplementPreset("AMPK/Berberin"),
    SupplementPreset("Magnezyum glisinat"),
    SupplementPreset("Glisin", amount = 3.0, unit = "g"),
    SupplementPreset("Kreatin", amount = 5.0, unit = "g")
)

@Composable
fun LogScreen(onBack: () -> Unit, viewModel: LogViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showMealDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Hızlı Log") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Geri") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            ui.lastLogged?.let { Text("✓ Kaydedildi: $it", color = MaterialTheme.colorScheme.primary) }
            ui.error?.let { Text("Hata: $it", color = MaterialTheme.colorScheme.error) }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Su", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(250.0, 500.0, 750.0).forEach { ml ->
                        Button(onClick = { viewModel.log(IntakeKind.WATER, "Su", ml, "ml") }) {
                            Text("+${ml.toInt()} ml")
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kahve", style = MaterialTheme.typography.titleMedium)
                Button(
                    onClick = { viewModel.log(IntakeKind.COFFEE, "Kahve") },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Kahve içtim") }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Öğün", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Protein hedefi 140–170 g sabittir (bkz. Ayarlar) — girmek istersen protein sor.",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.log(IntakeKind.MEAL, "Öğün") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Öğün yedim") }
                    OutlinedButton(
                        onClick = { showMealDialog = true },
                        modifier = Modifier.weight(1f)
                    ) { Text("Protein gir") }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Takviye", style = MaterialTheme.typography.titleMedium)
                SUPPLEMENT_PRESETS.forEach { preset ->
                    OutlinedButton(
                        onClick = { viewModel.log(IntakeKind.SUPPLEMENT, preset.label, preset.amount, preset.unit) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(preset.label + (preset.amount?.let { " (${it.toInt()}${preset.unit})" } ?: ""))
                    }
                }
            }
        }
    }

    if (showMealDialog) {
        MealProteinDialog(
            onDismiss = { showMealDialog = false },
            onConfirm = { proteinG ->
                viewModel.log(IntakeKind.MEAL, "Öğün", proteinG, "g protein")
                showMealDialog = false
            }
        )
    }
}

@Composable
private fun MealProteinDialog(onDismiss: () -> Unit, onConfirm: (Double) -> Unit) {
    var protein by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Öğün — protein (g)") },
        text = {
            OutlinedTextField(
                value = protein,
                onValueChange = { protein = it },
                label = { Text("Protein (g)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        },
        confirmButton = {
            Button(
                onClick = { protein.toDoubleOrNull()?.let(onConfirm) },
                enabled = protein.toDoubleOrNull() != null
            ) { Text("Ekle") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}
