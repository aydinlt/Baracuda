package com.aydin.biyohack.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aydin.biyohack.data.ClinicalFlagRecord
import com.aydin.biyohack.data.LabResult
import com.aydin.biyohack.data.repository.HealthSyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class LabUiState(
    val results: List<LabResult> = emptyList(),
    val flags: List<ClinicalFlagRecord> = emptyList()
)

@HiltViewModel
class LabViewModel @Inject constructor(
    private val repository: HealthSyncRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(LabUiState())
    val ui: StateFlow<LabUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeLabResults()
                .combine(repository.observeUnresolvedFlags()) { results, flags -> results to flags }
                .collect { (results, flags) -> _ui.update { it.copy(results = results, flags = flags) } }
        }
    }

    fun addResult(
        panel: String, marker: String, value: Double, unit: String?,
        refLow: Double?, refHigh: Double?, takenAt: LocalDate
    ) {
        viewModelScope.launch {
            repository.addLabResult(panel, marker, value, unit, refLow, refHigh, takenAt)
        }
    }

    fun addFlag(finding: String, status: String) {
        viewModelScope.launch { repository.addClinicalFlag(finding, status) }
    }

    fun resolveFlag(id: String) {
        viewModelScope.launch { repository.resolveClinicalFlag(id) }
    }

    fun deleteResult(id: String) {
        viewModelScope.launch { repository.deleteLabResult(id) }
    }
}

/** Referans aralığının dışında mı — aralık bilinmiyorsa (null) değerlendirme yapılmaz. */
private fun LabResult.isOutOfRange(): Boolean {
    val low = refLow
    val high = refHigh
    return (low != null && value < low) || (high != null && value > high)
}

@Composable
fun LabScreen(onBack: () -> Unit, viewModel: LabViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val grouped = ui.results.groupBy { it.panel }.toSortedMap()

    var showResultDialog by remember { mutableStateOf(false) }
    var showFlagDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Laboratuvar Seyri") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Geri") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showResultDialog = true }) { Text("+") }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Klinik bayraklar", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { showFlagDialog = true }) { Text("+ Bayrak") }
                }
            }
            if (ui.flags.isEmpty()) {
                item { Text("Açık bayrak yok.", style = MaterialTheme.typography.bodySmall) }
            }
            items(ui.flags) { flag ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(flag.finding, style = MaterialTheme.typography.bodyLarge)
                            Text(flag.status, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { viewModel.resolveFlag(flag.id) }) { Text("Çözüldü") }
                    }
                }
            }

            item { Text("Sonuçlar", style = MaterialTheme.typography.titleMedium) }
            if (grouped.isEmpty()) {
                item { Text("Henüz laboratuvar sonucu yok.", style = MaterialTheme.typography.bodySmall) }
            }
            grouped.forEach { (panel, results) ->
                item { Text(panel, style = MaterialTheme.typography.titleSmall) }
                items(results.sortedByDescending { it.takenAt }) { r ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(r.marker, style = MaterialTheme.typography.bodyLarge)
                                Text(r.takenAt.toString(), style = MaterialTheme.typography.bodySmall)
                            }
                            Column {
                                val outOfRange = r.isOutOfRange()
                                Text(
                                    "${r.value}${r.unit?.let { " $it" } ?: ""}",
                                    color = if (outOfRange) Color(0xFFB3261E) else Color.Unspecified,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (r.refLow != null || r.refHigh != null) {
                                    Text(
                                        "ref ${r.refLow ?: "—"}–${r.refHigh ?: "—"}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                            TextButton(onClick = { viewModel.deleteResult(r.id) }) { Text("Sil") }
                        }
                    }
                }
            }
        }
    }

    if (showResultDialog) {
        AddResultDialog(
            onDismiss = { showResultDialog = false },
            onConfirm = { panel, marker, value, unit, refLow, refHigh, takenAt ->
                viewModel.addResult(panel, marker, value, unit, refLow, refHigh, takenAt)
                showResultDialog = false
            }
        )
    }

    if (showFlagDialog) {
        AddFlagDialog(
            onDismiss = { showFlagDialog = false },
            onConfirm = { finding, status ->
                viewModel.addFlag(finding, status)
                showFlagDialog = false
            }
        )
    }
}

@Composable
private fun AddResultDialog(
    onDismiss: () -> Unit,
    onConfirm: (
        panel: String, marker: String, value: Double, unit: String?,
        refLow: Double?, refHigh: Double?, takenAt: LocalDate
    ) -> Unit
) {
    var panel by remember { mutableStateOf("") }
    var marker by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var refLow by remember { mutableStateOf("") }
    var refHigh by remember { mutableStateOf("") }
    // Varsayılan bugün — ama lab raporları genelde kan alımından günler sonra elde
    // ediyor, önceden bu alan hiç sorulmuyordu ve her sonuç sessizce "bugün" tarihiyle
    // kaydediliyordu (bkz. Hafta 18 commit notu).
    var takenAt by remember { mutableStateOf(LocalDate.now().toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Laboratuvar sonucu ekle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = panel, onValueChange = { panel = it }, label = { Text("Panel (ör. BÖBREK)") })
                OutlinedTextField(value = marker, onValueChange = { marker = it }, label = { Text("Marker (ör. eGFR)") })
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("Değer") })
                OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Birim (opsiyonel)") })
                OutlinedTextField(value = refLow, onValueChange = { refLow = it }, label = { Text("Ref alt (opsiyonel)") })
                OutlinedTextField(value = refHigh, onValueChange = { refHigh = it }, label = { Text("Ref üst (opsiyonel)") })
                OutlinedTextField(
                    value = takenAt,
                    onValueChange = { takenAt = it },
                    label = { Text("Tahlil tarihi (YYYY-AA-GG)") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val v = value.toDoubleOrNull() ?: return@Button
                    val date = runCatching { LocalDate.parse(takenAt) }.getOrNull() ?: return@Button
                    onConfirm(
                        panel.trim(), marker.trim(), v,
                        unit.trim().ifBlank { null },
                        refLow.toDoubleOrNull(), refHigh.toDoubleOrNull(),
                        date
                    )
                },
                enabled = panel.isNotBlank() && marker.isNotBlank() &&
                    value.toDoubleOrNull() != null &&
                    runCatching { LocalDate.parse(takenAt) }.isSuccess
            ) { Text("Ekle") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}

@Composable
private fun AddFlagDialog(onDismiss: () -> Unit, onConfirm: (finding: String, status: String) -> Unit) {
    var finding by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Klinik bayrak ekle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = finding, onValueChange = { finding = it }, label = { Text("Bulgu") })
                OutlinedTextField(value = status, onValueChange = { status = it }, label = { Text("Durum") })
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(finding.trim(), status.trim()) },
                enabled = finding.isNotBlank() && status.isNotBlank()
            ) { Text("Ekle") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}
