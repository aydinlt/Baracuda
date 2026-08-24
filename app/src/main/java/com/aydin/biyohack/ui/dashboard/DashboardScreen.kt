package com.aydin.biyohack.ui.dashboard

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aydin.biyohack.data.DailySnapshot
import com.aydin.biyohack.data.IntakeKind
import com.aydin.biyohack.data.repository.AuthRepository
import com.aydin.biyohack.data.repository.HealthSyncRepository
import com.aydin.biyohack.data.repository.ProfileRepository
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
    val isSyncing: Boolean = false,
    val permissionsGranted: Boolean = false,
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
            _ui.update { it.copy(permissionsGranted = healthConnectManager.hasAllPermissions()) }
        }
        // İlk girişte Supabase'de profil satırı yoksa varsayılanlarla oluşturur.
        viewModelScope.launch {
            authRepository.currentUserId()?.let { profileRepository.ensureLoaded(it) }
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
}

@Composable
fun DashboardScreen(
    onOpenTwin: () -> Unit = {},
    onOpenLab: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    val permissionContract = remember { viewModel.healthConnectManager.requestPermissionsContract() }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = permissionContract
    ) { granted -> viewModel.onPermissionsResult(granted) }

    LaunchedEffect(Unit) {
        if (!ui.permissionsGranted) permissionLauncher.launch(HealthConnectManager.PERMISSIONS)
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Baracuda — Biyolojik Dijital İkiz", style = androidx.compose.material3.MaterialTheme.typography.titleLarge)

            if (!ui.permissionsGranted) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Health Connect izni verilmedi.")
                        Button(onClick = { permissionLauncher.launch(HealthConnectManager.PERMISSIONS) }) {
                            Text("İzin ver")
                        }
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Bu gece", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                    val snap = ui.today
                    if (snap == null) {
                        Text("Veri yok — senkronize et veya bu gece için Health Connect kaydı yok.")
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

            Button(onClick = { viewModel.syncNow() }, enabled = !ui.isSyncing) {
                if (ui.isSyncing) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text(if (ui.isSyncing) "Senkronize ediliyor..." else "Şimdi Senkronize Et")
            }

            Button(onClick = onOpenTwin, modifier = Modifier.fillMaxWidth()) {
                Text("İkize sor")
            }
            Button(onClick = onOpenLab, modifier = Modifier.fillMaxWidth()) {
                Text("Laboratuvar Seyri")
            }

            ui.error?.let { Text("Hata: $it") }

            Text("Su hızlı log", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(250.0, 500.0, 750.0).forEach { ml ->
                    Button(onClick = { viewModel.logWater(ml) }) { Text("+${ml.toInt()} ml") }
                }
            }

            Text("Son 7 gün", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }
}
