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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aydin.biyohack.data.ClinicalFlagRecord
import com.aydin.biyohack.data.LabResult
import com.aydin.biyohack.data.LabResultTemplate
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
    val flags: List<ClinicalFlagRecord> = emptyList(),
    val templates: List<LabResultTemplate> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class LabViewModel @Inject constructor(
    private val repository: HealthSyncRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(LabUiState())
    val ui: StateFlow<LabUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                repository.observeLabResults(),
                repository.observeUnresolvedFlags(),
                repository.observeLabResultTemplates()
            ) { results, flags, templates -> Triple(results, flags, templates) }
                .collect { (results, flags, templates) ->
                    _ui.update { it.copy(results = results, flags = flags, templates = templates) }
                }
        }
    }

    // Önceden bu dört fonksiyondan hiçbiri hatayı UI'a taşımıyordu — özellikle
    // deleteResult önce Supabase'den silmeyi denediği için (bkz. HealthSyncRepository.
    // deleteLabResult), offline'ken "Sil" butonuna basmak sessizce hiçbir şey
    // yapmıyormuş gibi görünüyordu, kullanıcı ne olduğunu anlamıyordu.

    fun addResult(
        panel: String, marker: String, value: Double, unit: String?,
        refLow: Double?, refHigh: Double?, takenAt: LocalDate
    ) {
        viewModelScope.launch {
            val result = repository.addLabResult(panel, marker, value, unit, refLow, refHigh, takenAt)
            _ui.update { it.copy(error = result.exceptionOrNull()?.message) }
        }
    }

    fun addFlag(finding: String, status: String) {
        viewModelScope.launch {
            val result = repository.addClinicalFlag(finding, status)
            _ui.update { it.copy(error = result.exceptionOrNull()?.message) }
        }
    }

    fun resolveFlag(id: String) {
        viewModelScope.launch {
            val result = repository.resolveClinicalFlag(id)
            _ui.update { it.copy(error = result.exceptionOrNull()?.message) }
        }
    }

    fun deleteResult(id: String) {
        viewModelScope.launch {
            val result = repository.deleteLabResult(id)
            _ui.update { it.copy(error = result.exceptionOrNull()?.message) }
        }
    }

    fun addTemplate(panel: String, marker: String, unit: String?, refLow: Double?, refHigh: Double?) {
        viewModelScope.launch {
            val result = repository.addLabResultTemplate(panel, marker, unit, refLow, refHigh)
            _ui.update { it.copy(error = result.exceptionOrNull()?.message) }
        }
    }

    fun updateTemplate(
        original: LabResultTemplate,
        panel: String,
        marker: String,
        unit: String?,
        refLow: Double?,
        refHigh: Double?
    ) {
        viewModelScope.launch {
            val result = repository.updateLabResultTemplate(original, panel, marker, unit, refLow, refHigh)
            _ui.update { it.copy(error = result.exceptionOrNull()?.message) }
        }
    }

    fun deleteTemplate(id: String) {
        viewModelScope.launch {
            val result = repository.deleteLabResultTemplate(id)
            _ui.update { it.copy(error = result.exceptionOrNull()?.message) }
        }
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
    var showTemplateDialog by remember { mutableStateOf(false) }
    // Bir "sık tekrarlanan panel" şablonuna dokunulduğunda AddResultDialog'u
    // bu değerlerle önceden doldurulmuş açmak için — null ise FAB'dan boş açılır.
    var resultPrefill by remember { mutableStateOf<LabResultTemplate?>(null) }
    // "Düzenle" ile açıldığında AddLabTemplateDialog'u bu şablonla önceden
    // doldurulmuş açmak için — null ise "+ Şablon" ile boş açılır.
    var editingTemplate by remember { mutableStateOf<LabResultTemplate?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Laboratuvar Seyri") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Geri") } }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                resultPrefill = null
                showResultDialog = true
            }) { Text("+") }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ui.error?.let { error ->
                item { Text("Hata: $error", color = MaterialTheme.colorScheme.error) }
            }

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

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Şablonlar", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        editingTemplate = null
                        showTemplateDialog = true
                    }) { Text("+ Şablon") }
                }
            }
            if (ui.templates.isEmpty()) {
                item {
                    Text(
                        "Her tahlilde tekrarlanan bir marker'ı (ör. eGFR — BÖBREK) şablon " +
                            "olarak kaydedebilirsin; dokunduğunda panel/marker/birim/referans " +
                            "önceden doldurulmuş açılır, yalnızca değeri girmen yeter.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            items(ui.templates) { template ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = {
                            resultPrefill = template
                            showResultDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("${template.panel} — ${template.marker}") }
                    TextButton(onClick = {
                        editingTemplate = template
                        showTemplateDialog = true
                    }) { Text("Düzenle") }
                    TextButton(onClick = { viewModel.deleteTemplate(template.id) }) { Text("Sil") }
                }
            }

            item { Text("Sonuçlar", style = MaterialTheme.typography.titleMedium) }
            if (grouped.isEmpty()) {
                item { Text("Henüz laboratuvar sonucu yok.", style = MaterialTheme.typography.bodySmall) }
            }
            grouped.forEach { (panel, results) ->
                item { Text(panel, style = MaterialTheme.typography.titleSmall) }
                items(results.sortedByDescending { it.takenAt }) { r ->
                    // Ekranın adı "Laboratuvar Seyri" (seyir = trend) ama önceden hiçbir
                    // sonuç bir öncekiyle karşılaştırılmıyordu, yalnızca tek tek değerler
                    // listeleniyordu — trend görmek için kullanıcının kendi kendine geçmiş
                    // kayıtları karşılaştırması gerekiyordu.
                    val previous = results.filter { it.marker == r.marker && it.takenAt < r.takenAt }
                        .maxByOrNull { it.takenAt }
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
                                previous?.let { p ->
                                    val delta = r.value - p.value
                                    Text(
                                        "${if (delta >= 0) "+" else ""}${"%.1f".format(delta)} " +
                                            "(önceki ${p.takenAt}: ${p.value})",
                                        style = MaterialTheme.typography.labelSmall
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
            initial = resultPrefill,
            onDismiss = {
                showResultDialog = false
                resultPrefill = null
            },
            onConfirm = { panel, marker, value, unit, refLow, refHigh, takenAt ->
                viewModel.addResult(panel, marker, value, unit, refLow, refHigh, takenAt)
                showResultDialog = false
                resultPrefill = null
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

    if (showTemplateDialog) {
        AddLabTemplateDialog(
            initial = editingTemplate,
            onDismiss = {
                showTemplateDialog = false
                editingTemplate = null
            },
            onConfirm = { panel, marker, unit, refLow, refHigh ->
                val current = editingTemplate
                if (current != null) {
                    viewModel.updateTemplate(current, panel, marker, unit, refLow, refHigh)
                } else {
                    viewModel.addTemplate(panel, marker, unit, refLow, refHigh)
                }
                showTemplateDialog = false
                editingTemplate = null
            }
        )
    }
}

@Composable
private fun AddResultDialog(
    initial: LabResultTemplate? = null,
    onDismiss: () -> Unit,
    onConfirm: (
        panel: String, marker: String, value: Double, unit: String?,
        refLow: Double?, refHigh: Double?, takenAt: LocalDate
    ) -> Unit
) {
    // Bir şablondan açıldıysa panel/marker/birim/referans önceden dolu gelir —
    // kullanıcının yalnızca değeri (ve gerekirse tarihi) girmesi yeterli olur.
    var panel by remember { mutableStateOf(initial?.panel ?: "") }
    var marker by remember { mutableStateOf(initial?.marker ?: "") }
    var value by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf(initial?.unit ?: "") }
    var refLow by remember { mutableStateOf(initial?.refLow?.toString() ?: "") }
    var refHigh by remember { mutableStateOf(initial?.refHigh?.toString() ?: "") }
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

@Composable
private fun AddLabTemplateDialog(
    initial: LabResultTemplate? = null,
    onDismiss: () -> Unit,
    onConfirm: (panel: String, marker: String, unit: String?, refLow: Double?, refHigh: Double?) -> Unit
) {
    var panel by remember { mutableStateOf(initial?.panel ?: "") }
    var marker by remember { mutableStateOf(initial?.marker ?: "") }
    var unit by remember { mutableStateOf(initial?.unit ?: "") }
    var refLow by remember { mutableStateOf(initial?.refLow?.toString() ?: "") }
    var refHigh by remember { mutableStateOf(initial?.refHigh?.toString() ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial != null) "Panel şablonunu düzenle" else "Panel şablonu ekle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = panel, onValueChange = { panel = it }, label = { Text("Panel (ör. BÖBREK)") })
                OutlinedTextField(value = marker, onValueChange = { marker = it }, label = { Text("Marker (ör. eGFR)") })
                OutlinedTextField(value = unit, onValueChange = { unit = it }, label = { Text("Birim (opsiyonel)") })
                OutlinedTextField(value = refLow, onValueChange = { refLow = it }, label = { Text("Ref alt (opsiyonel)") })
                OutlinedTextField(value = refHigh, onValueChange = { refHigh = it }, label = { Text("Ref üst (opsiyonel)") })
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(
                        panel.trim(), marker.trim(),
                        unit.trim().ifBlank { null },
                        refLow.toDoubleOrNull(), refHigh.toDoubleOrNull()
                    )
                },
                enabled = panel.isNotBlank() && marker.isNotBlank()
            ) { Text("Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}
