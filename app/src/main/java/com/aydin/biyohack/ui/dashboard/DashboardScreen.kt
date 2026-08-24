package com.aydin.biyohack.ui.dashboard

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aydin.biyohack.data.BodyMetric
import com.aydin.biyohack.data.DailySnapshot
import com.aydin.biyohack.data.IntakeKind
import com.aydin.biyohack.data.IntakeRecord
import com.aydin.biyohack.data.repository.AuthRepository
import com.aydin.biyohack.data.repository.HealthSyncRepository
import com.aydin.biyohack.data.repository.ProfileRepository
import com.aydin.biyohack.health.HealthConnectAvailability
import com.aydin.biyohack.health.HealthConnectManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DashboardUiState(
    val today: DailySnapshot? = null,
    val recent: List<DailySnapshot> = emptyList(),
    val todayIntake: List<IntakeRecord> = emptyList(),
    val latestBodyMetric: BodyMetric? = null,
    val waterTargetMl: Int = 4000,
    val proteinTargetMinG: Int = 140,
    val proteinTargetMaxG: Int = 170,
    val isSyncing: Boolean = false,
    val permissionsGranted: Boolean = false,
    val healthConnectAvailability: HealthConnectAvailability = HealthConnectAvailability.INSTALLED,
    val error: String? = null
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: HealthSyncRepository,
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    val healthConnectManager: HealthConnectManager
) : ViewModel() {

    private val _ui = MutableStateFlow(DashboardUiState())
    val ui: StateFlow<DashboardUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeRecentSnapshots(limit = 7).collect { snapshots ->
                _ui.update { it.copy(recent = snapshots, today = snapshots.firstOrNull()) }
            }
        }
        viewModelScope.launch {
            repository.observeTodayIntake().collect { intake ->
                _ui.update { it.copy(todayIntake = intake) }
            }
        }
        // Önceden kilo/bel çevresi yalnızca ayrı BodyMetricScreen'de görünürdü —
        // en son ölçümü görmek için oraya gitmek gerekiyordu. "Bu gece" kartıyla
        // aynı yerde, ana ekranda gösterilmesi kullanıcının her seferinde
        // gezinmesini gereksiz kılıyor.
        viewModelScope.launch {
            repository.observeRecentBodyMetrics(limit = 1).collect { list ->
                _ui.update { it.copy(latestBodyMetric = list.firstOrNull()) }
            }
        }
        viewModelScope.launch {
            _ui.update {
                it.copy(
                    permissionsGranted = healthConnectManager.hasAllPermissions(),
                    // Önceden cihazda Health Connect kurulu değilse (ör. eski bir telefon,
                    // provider güncellemesi gerekiyor) "İzin ver" butonu hiçbir işe yaramayan
                    // bir izin akışı başlatmaya çalışıyordu — kullanıcıya ne yapması
                    // gerektiğini söyleyen bir yol hiç yoktu.
                    healthConnectAvailability = healthConnectManager.availability()
                )
            }
        }
        // İlk girişte Supabase'de profil satırı yoksa varsayılanlarla oluşturur.
        viewModelScope.launch {
            authRepository.currentUserId()?.let { userId ->
                profileRepository.ensureLoaded(userId)
                profileRepository.observe(userId).collect { profile ->
                    profile?.let { p ->
                        _ui.update {
                            it.copy(
                                waterTargetMl = p.waterTargetMl,
                                proteinTargetMinG = p.proteinTargetMinG,
                                proteinTargetMaxG = p.proteinTargetMaxG
                            )
                        }
                    }
                }
            }
        }
    }

    fun onPermissionsResult(granted: Set<String>) {
        val allGranted = granted.containsAll(HealthConnectManager.PERMISSIONS)
        _ui.update { it.copy(permissionsGranted = allGranted) }
        if (allGranted) syncNow()
    }

    fun syncNow() {
        viewModelScope.launch {
            _ui.update { it.copy(isSyncing = true, error = null) }
            val result = repository.syncAll()
            _ui.update {
                it.copy(
                    isSyncing = false,
                    error = result.exceptionOrNull()?.message
                )
            }
        }
    }

    fun logWater(ml: Double) {
        viewModelScope.launch {
            repository.logIntake(IntakeKind.WATER, "Su", ml, "ml")
        }
    }

    /** Yanlış dokunulmuş bir log satırını siler — bkz. HealthSyncRepository.deleteIntake. */
    fun deleteIntake(id: String) {
        viewModelScope.launch { repository.deleteIntake(id) }
    }

    /**
     * Health Connect'te bu gece için kayıt yoksa (cihaz takılmadı, izin yok,
     * senkronizasyon henüz çalışmadı) kullanıcı uyku süresini elle girebilir
     * — önceden "Bu gece" kartı bu durumda süresiz "Veri yok" kalıyordu.
     */
    fun logManualSleep(hours: Int, minutes: Int) {
        viewModelScope.launch {
            _ui.update { it.copy(error = null) }
            val result = repository.logManualSnapshot(
                date = java.time.LocalDate.now(),
                asleepMin = hours * 60 + minutes
            )
            _ui.update { it.copy(error = result.exceptionOrNull()?.message) }
        }
    }
}

@Composable
fun DashboardScreen(
    onOpenTwin: () -> Unit = {},
    onOpenLab: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenLog: () -> Unit = {},
    onOpenBodyMetric: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var showManualSleepDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val permissionContract = remember { viewModel.healthConnectManager.requestPermissionsContract() }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = permissionContract
    ) { granted -> viewModel.onPermissionsResult(granted) }

    // Health Connect kurulu değilse izin sözleşmesini başlatmanın anlamı yok —
    // ui.healthConnectAvailability yüklendiğinde yeniden değerlendirilir (ViewModel'in
    // ilk state'i iyimser INSTALLED varsayıyor, gerçek değer async yüklenir).
    LaunchedEffect(ui.permissionsGranted, ui.healthConnectAvailability) {
        if (!ui.permissionsGranted && ui.healthConnectAvailability == HealthConnectAvailability.INSTALLED) {
            permissionLauncher.launch(HealthConnectManager.PERMISSIONS)
        }
    }

    // Sabah protokolü bildirimi için — TwinMorningWorker'ın gösterebilmesi bu izne bağlı.
    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* sonucu göz ardı edilir — reddedilirse TwinNotifier sessizce atlar */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Text("Baracuda — Biyolojik Dijital İkiz", style = MaterialTheme.typography.titleLarge) }

            if (!ui.permissionsGranted) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            when (ui.healthConnectAvailability) {
                                HealthConnectAvailability.INSTALLED -> {
                                    Text("Health Connect izni verilmedi.")
                                    Button(onClick = { permissionLauncher.launch(HealthConnectManager.PERMISSIONS) }) {
                                        Text("İzin ver")
                                    }
                                }
                                HealthConnectAvailability.NOT_INSTALLED -> {
                                    Text("Health Connect uygulaması kurulu değil ya da güncellenmesi gerekiyor.")
                                    Button(onClick = {
                                        val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata")
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                        } catch (e: ActivityNotFoundException) {
                                            context.startActivity(
                                                Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
                                                )
                                            )
                                        }
                                    }) {
                                        Text("Play Store'da aç")
                                    }
                                }
                                HealthConnectAvailability.NOT_SUPPORTED -> {
                                    Text(
                                        "Bu cihaz Health Connect'i desteklemiyor — uyku verisi otomatik " +
                                            "senkronize edilemez. \"Bu gece\" kartındaki \"Uyku süresini elle gir\" " +
                                            "ile devam edebilirsin."
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Bu gece", style = MaterialTheme.typography.titleMedium)
                        val snap = ui.today
                        if (snap == null) {
                            Text("Veri yok — senkronize et veya bu gece için Health Connect kaydı yok.")
                            TextButton(onClick = { showManualSleepDialog = true }) {
                                Text("Uyku süresini elle gir")
                            }
                        } else {
                            Text("Uyku: ${snap.asleepMin?.let { "${it / 60}s ${it % 60}d" } ?: "—"}")
                            Text("Verim: ${snap.efficiencyPct?.let { "%$it" } ?: "—"}")
                            Text(
                                "SpO2 ort: ${snap.spo2Avg?.let { "%.1f%%".format(it) } ?: "—"}" +
                                    (snap.minutesBelow90?.let { " • %90 altı ${it}dk (tahmin)" } ?: "")
                            )
                        }
                    }
                }
            }

            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Kilo / Bel Çevresi", style = MaterialTheme.typography.titleMedium)
                        val metric = ui.latestBodyMetric
                        if (metric == null) {
                            Text("Henüz ölçüm yok.")
                        } else {
                            Text(
                                listOfNotNull(
                                    metric.weightKg?.let { "%.1f kg".format(it) },
                                    metric.waistCm?.let { "%.1f cm bel".format(it) }
                                ).joinToString(" · ")
                            )
                            Text(metric.date.toString(), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Button(onClick = { viewModel.syncNow() }, enabled = !ui.isSyncing, modifier = Modifier.fillMaxWidth()) {
                    if (ui.isSyncing) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    Text(if (ui.isSyncing) "Senkronize ediliyor..." else "Şimdi Senkronize Et")
                }
            }

            item {
                Button(onClick = onOpenTwin, modifier = Modifier.fillMaxWidth()) { Text("İkize sor") }
            }
            item {
                Button(onClick = onOpenLab, modifier = Modifier.fillMaxWidth()) { Text("Laboratuvar Seyri") }
            }
            item {
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Ayarlar") }
            }
            item {
                Button(onClick = onOpenLog, modifier = Modifier.fillMaxWidth()) { Text("Hızlı Log (kahve / öğün / takviye)") }
            }
            item {
                Button(onClick = onOpenBodyMetric, modifier = Modifier.fillMaxWidth()) { Text("Kilo / Bel Çevresi") }
            }

            ui.error?.let { error -> item { Text("Hata: $error") } }

            item {
                val waterMl = ui.todayIntake.filter { it.kind == IntakeKind.WATER }.sumOf { it.amount ?: 0.0 }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Su hızlı log", style = MaterialTheme.typography.titleMedium)
                    Text("${waterMl.toInt()} / ${ui.waterTargetMl} ml")
                    LinearProgressIndicator(
                        progress = { (waterMl / ui.waterTargetMl).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(250.0, 500.0, 750.0).forEach { ml ->
                            Button(onClick = { viewModel.logWater(ml) }) { Text("+${ml.toInt()} ml") }
                        }
                    }
                }
            }

            item {
                // MEAL kayıtlarında amount, yalnızca unit="g protein" ile loglananlarda protein gramıdır
                // (bkz. LogScreen "Protein gir" dialog'u) — protein girilmemiş öğün logları toplamaya girmez.
                val proteinG = ui.todayIntake
                    .filter { it.kind == IntakeKind.MEAL && it.unit == "g protein" }
                    .sumOf { it.amount ?: 0.0 }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Protein", style = MaterialTheme.typography.titleMedium)
                    Text("${proteinG.toInt()} g (hedef ${ui.proteinTargetMinG}–${ui.proteinTargetMaxG} g)")
                    LinearProgressIndicator(
                        progress = { (proteinG / ui.proteinTargetMinG).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item { Text("Bugünkü loglar", style = MaterialTheme.typography.titleMedium) }
            if (ui.todayIntake.isEmpty()) {
                item { Text("Bugün henüz log yok.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(ui.todayIntake.sortedByDescending { it.ts }) { entry ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${entry.kind.name} — ${entry.label}" +
                                    (entry.amount?.let { " (${it.toInt()}${entry.unit ?: ""})" } ?: "")
                            )
                            TextButton(onClick = { viewModel.deleteIntake(entry.id) }) { Text("Sil") }
                        }
                    }
                }
            }

            item { Text("Son 7 gün", style = MaterialTheme.typography.titleMedium) }
            items(ui.recent) { snap ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(snap.date.toString())
                        Text(
                            "Uyku ${snap.asleepMin?.let { "${it / 60}s ${it % 60}d" } ?: "—"} " +
                                "· Verim ${snap.efficiencyPct?.let { "%$it" } ?: "—"}"
                        )
                    }
                }
            }
        }
    }

    if (showManualSleepDialog) {
        ManualSleepDialog(
            onDismiss = { showManualSleepDialog = false },
            onConfirm = { hours, minutes ->
                viewModel.logManualSleep(hours, minutes)
                showManualSleepDialog = false
            }
        )
    }
}

@Composable
private fun ManualSleepDialog(onDismiss: () -> Unit, onConfirm: (hours: Int, minutes: Int) -> Unit) {
    var hours by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Uyku süresini elle gir") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Health Connect'te bu gece için kayıt yok — cihaz takılmadıysa ya da " +
                        "senkronize henüz çalışmadıysa yaklaşık süreyi buradan girebilirsin.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = hours,
                    onValueChange = { hours = it },
                    label = { Text("Saat") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { minutes = it },
                    label = { Text("Dakika") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val h = hours.toIntOrNull() ?: return@Button
                    val m = minutes.toIntOrNull() ?: return@Button
                    onConfirm(h, m)
                },
                enabled = hours.toIntOrNull() != null && minutes.toIntOrNull() != null
            ) { Text("Kaydet") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Vazgeç") } }
    )
}
