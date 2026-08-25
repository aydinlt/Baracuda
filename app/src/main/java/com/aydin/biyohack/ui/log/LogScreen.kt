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
import com.aydin.biyohack.data.QuickTemplate
import com.aydin.biyohack.data.repository.AuthRepository
import com.aydin.biyohack.data.repository.HealthSyncRepository
import com.aydin.biyohack.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LogUiState(
    val lastLogged: String? = null,
    val error: String? = null,
    val proteinTargetMinG: Int = 140,
    val proteinTargetMaxG: Int = 170,
    val creatineFreeDays: Int = 0,
    val templates: List<QuickTemplate> = emptyList()
)

@HiltViewModel
class LogViewModel @Inject constructor(
    private val repository: HealthSyncRepository,
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(LogUiState())
    val ui: StateFlow<LogUiState> = _ui.asStateFlow()

    init {
        // Önceden bu ekran "Protein hedefi 140–170 g sabittir" diye SABİT bir metin
        // gösteriyordu — SettingsScreen bu hedefi Hafta 7'den beri düzenlenebilir
        // yapmıştı, LogScreen hiç güncellenmemişti. Gerçek profil değerine bağlandı.
        authRepository.currentUserId()?.let { userId ->
            viewModelScope.launch {
                profileRepository.observe(userId).collect { profile ->
                    profile?.let { p ->
                        _ui.update { it.copy(proteinTargetMinG = p.proteinTargetMinG, proteinTargetMaxG = p.proteinTargetMaxG) }
                    }
                }
            }
        }
        // creatineFreeDays TwinGuardrails/TwinStateBuilder'a besleniyordu ama hiçbir
        // ekranda gösterilmiyordu — kullanıcı kreatinsiz gün sayacını yalnızca İkiz'e
        // sorup Cistatin C testi bekliyorsa şans eseri görebiliyordu.
        refreshCreatineFreeDays()
        viewModelScope.launch {
            repository.observeQuickTemplates().collect { list ->
                _ui.update { it.copy(templates = list) }
            }
        }
    }

    private fun refreshCreatineFreeDays() {
        viewModelScope.launch {
            val days = repository.creatineFreeDays()
            _ui.update { it.copy(creatineFreeDays = days) }
        }
    }

    fun log(kind: IntakeKind, label: String, amount: Double? = null, unit: String? = null) {
        viewModelScope.launch {
            val result = repository.logIntake(kind, label, amount, unit)
            _ui.update {
                it.copy(
                    lastLogged = if (result.isSuccess) label else it.lastLogged,
                    error = result.exceptionOrNull()?.message
                )
            }
            // Kreatin loglandıysa sayaç sıfırlanır — creatineFreeDays son kreatin
            // logundan bu yana geçen gün sayısıdır (bkz. HealthSyncRepository).
            if (result.isSuccess) refreshCreatineFreeDays()
        }
    }

    /** Kayıtlı bir şablona tek dokunuşla log oluşturur — [log] ile aynı yolu kullanır. */
    fun logTemplate(template: QuickTemplate) = log(template.kind, template.label, template.amount, template.unit)

    fun saveTemplate(kind: IntakeKind, label: String, amount: Double?, unit: String?) {
        viewModelScope.launch {
            val result = repository.addQuickTemplate(kind, label, amount, unit)
            result.exceptionOrNull()?.let { e -> _ui.update { it.copy(error = e.message) } }
        }
    }

    fun deleteTemplate(id: String) {
        viewModelScope.launch {
            val result = repository.deleteQuickTemplate(id)
            result.exceptionOrNull()?.let { e -> _ui.update { it.copy(error = e.message) } }
        }
    }
}

private data class SupplementPreset(val label: String, val amount: Double? = null, val unit: String? = null)

/**
 * system_twin.md Bölüm E — takviye zamanlama matrisindeki kalemler.
 *
 * Uric Acid Support, R-lipoik asit ve Elektrolit önceden eksikti — Bölüm E'de
 * kendi zamanlama/ara kurallarıyla (ör. "Uric Acid Support 1–2 hafta ara")
 * adı geçtikleri, hatta Elektrolit'i TwinGuardrails'in kendisi "sauna
 * planlıysa zorunlu" diye işaretlediği halde loglamanın hiçbir yolu yoktu.
 */
private val SUPPLEMENT_PRESETS = listOf(
    SupplementPreset("NR/NAD"),
    SupplementPreset("D3+K2"),
    SupplementPreset("Omega-3"),
    SupplementPreset("EGCG"),
    SupplementPreset("AMPK/Berberin"),
    SupplementPreset("Magnezyum glisinat"),
    SupplementPreset("Glisin", amount = 3.0, unit = "g"),
    SupplementPreset("Kreatin", amount = 5.0, unit = "g"),
    SupplementPreset("Uric Acid Support"),
    SupplementPreset("R-lipoik asit"),
    SupplementPreset("Elektrolit")
)

@Composable
fun LogScreen(onBack: () -> Unit, viewModel: LogViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showMealDialog by remember { mutableStateOf(false) }
    var showTemplateDialog by remember { mutableStateOf(false) }

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
                Text("Şablonlar / Favoriler", style = MaterialTheme.typography.titleMedium)
                if (ui.templates.isEmpty()) {
                    Text(
                        "Henüz şablon yok. Sık tükettiğin bir kombinasyonu " +
                            "(ör. \"Standart Sabah Kahvesi\") aşağıdan ekleyebilirsin.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    ui.templates.forEach { template ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Button(
                                onClick = { viewModel.logTemplate(template) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(template.label + (template.amount?.let { " (${it.toInt()}${template.unit ?: ""})" } ?: ""))
                            }
                            TextButton(onClick = { viewModel.deleteTemplate(template.id) }) { Text("Sil") }
                        }
                    }
                }
                OutlinedButton(
                    onClick = { showTemplateDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("+ Şablon ekle") }
            }

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
                    "Protein hedefi ${ui.proteinTargetMinG}–${ui.proteinTargetMaxG} g (bkz. Ayarlar) — girmek istersen protein sor.",
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
                Text(
                    "Kreatinsiz gün: ${ui.creatineFreeDays}",
                    style = MaterialTheme.typography.bodySmall
                )
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

    if (showTemplateDialog) {
        AddTemplateDialog(
            onDismiss = { showTemplateDialog = false },
            onConfirm = { kind, label, amount, unit ->
                viewModel.saveTemplate(kind, label, amount, unit)
                showTemplateDialog = false
            }
        )
    }
}

private fun IntakeKind.label() = when (this) {
    IntakeKind.MEAL -> "Öğün"
    IntakeKind.COFFEE -> "Kahve"
    IntakeKind.WATER -> "Su"
    IntakeKind.SUPPLEMENT -> "Takviye"
}

@Composable
private fun AddTemplateDialog(
    onDismiss: () -> Unit,
    onConfirm: (IntakeKind, String, Double?, String?) -> Unit
) {
    var kind by remember { mutableStateOf(IntakeKind.MEAL) }
    var label by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Şablon ekle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IntakeKind.entries.forEach { k ->
                        if (k == kind) {
                            Button(onClick = { kind = k }) { Text(k.label()) }
                        } else {
                            OutlinedButton(onClick = { kind = k }) { Text(k.label()) }
                        }
                    }
                }
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Ad (ör. Standart Sabah Kahvesi)") }
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Miktar (opsiyonel, ör. 500)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text("Birim (opsiyonel, ör. ml, g)") }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(kind, label.trim(), amount.toDoubleOrNull(), unit.trim().ifBlank { null })
                },
                enabled = label.isNotBlank()
            ) { Text("Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
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
