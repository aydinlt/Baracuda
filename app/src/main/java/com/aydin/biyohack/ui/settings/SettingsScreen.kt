package com.aydin.biyohack.ui.settings

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.aydin.biyohack.data.Profile
import com.aydin.biyohack.data.repository.AuthRepository
import com.aydin.biyohack.data.repository.HealthSyncRepository
import com.aydin.biyohack.data.repository.ProfileRepository
import com.aydin.biyohack.sync.TwinMorningWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject

data class SettingsUiState(
    val profile: Profile? = null,
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    // null = henüz kontrol edilmedi (ilk açılışta bir kez hesaplanır) — 0'dan ayrı
    // tutulur ki "her şey senkron" ile "henüz bakılmadı" karışmasın.
    val pendingSyncCount: Int? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val authRepository: AuthRepository,
    private val healthSyncRepository: HealthSyncRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _ui = MutableStateFlow(SettingsUiState())
    val ui: StateFlow<SettingsUiState> = _ui.asStateFlow()

    init {
        authRepository.currentUserId()?.let { userId ->
            viewModelScope.launch {
                profileRepository.ensureLoaded(userId)
                profileRepository.observe(userId).collect { profile ->
                    if (profile != null) _ui.update { it.copy(profile = profile) }
                }
            }
        }
        refreshPendingSyncCount()
    }

    /**
     * Henüz Supabase'e itilmemiş kayıt sayısını yeniler (bkz. HealthSyncRepository.
     * pendingSyncCount). Bu bir Flow değil, tek seferlik bir sorgu — "canlı" bir
     * sayaç yerine ekrana her girişte ve "Yenile"ye basınca güncellenen bir
     * durum göstergesi olarak yeterli; sürekli gözlemlemek gereksiz karmaşıklık
     * katardı (senkron zaten arka planda kendiliğinden çalışıyor).
     */
    fun refreshPendingSyncCount() {
        viewModelScope.launch {
            val count = healthSyncRepository.pendingSyncCount()
            _ui.update { it.copy(pendingSyncCount = count) }
        }
    }

    fun save(waterTargetMl: Int, proteinMinG: Int, proteinMaxG: Int, wakeTarget: LocalTime) {
        val current = _ui.value.profile ?: return
        viewModelScope.launch {
            _ui.update { it.copy(isSaving = true, saved = false, error = null) }
            val result = profileRepository.upsert(
                current.copy(
                    waterTargetMl = waterTargetMl,
                    proteinTargetMinG = proteinMinG,
                    proteinTargetMaxG = proteinMaxG,
                    wakeTarget = wakeTarget
                )
            )
            _ui.update {
                it.copy(isSaving = false, saved = result.isSuccess, error = result.exceptionOrNull()?.message)
            }
            // TwinMorningWorker'ın bir sonraki çalışmasını, ertesi güne kadar beklemeden
            // yeni kalkış hedefine göre hemen yeniden zamanlar (bkz. TwinMorningWorker.kt).
            if (result.isSuccess) {
                TwinMorningWorker.scheduleNext(appContext, wakeTarget.plusMinutes(30))
                refreshPendingSyncCount()
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    var waterTarget by remember { mutableStateOf("") }
    var proteinMin by remember { mutableStateOf("") }
    var proteinMax by remember { mutableStateOf("") }
    var wakeTarget by remember { mutableStateOf("") }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }

    // Profil ilk kez yüklendiğinde form alanlarını doldur; sonraki güncellemelerde
    // kullanıcının o an düzenlediği metni ezmemek için yalnızca bir kez çalışır.
    LaunchedEffect(ui.profile != null) {
        ui.profile?.let { p ->
            waterTarget = p.waterTargetMl.toString()
            proteinMin = p.proteinTargetMinG.toString()
            proteinMax = p.proteinTargetMaxG.toString()
            wakeTarget = p.wakeTarget.format(timeFormatter)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ayarlar") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Geri") } }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Hedefler", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = waterTarget,
                onValueChange = { waterTarget = it },
                label = { Text("Su hedefi (ml)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = proteinMin,
                onValueChange = { proteinMin = it },
                label = { Text("Protein alt hedef (g)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = proteinMax,
                onValueChange = { proteinMax = it },
                label = { Text("Protein üst hedef (g)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = wakeTarget,
                onValueChange = { wakeTarget = it },
                label = { Text("Kalkış hedefi (SS:dd, ör. 07:00)") },
                // İkiz'in sabah protokolü artık bu saatten 30dk sonra otomatik çalışıyor
                // (bkz. sync/TwinMorningWorker.kt) — daha önce sabit 07:30'du.
                modifier = Modifier.fillMaxWidth()
            )

            // Önceden bu buton alan içerikleri geçersizken bile her zaman aktifti —
            // tıklamak return@Button'a düşüp sessizce hiçbir şey yapmıyordu, LabScreen'in
            // "Tahlil tarihi" alanındakinin aksine (bkz. Hafta 18) hiçbir görsel geri
            // bildirim yoktu. Ayrıca yalnızca "sayı mı" kontrol ediliyordu — 0 ya da
            // negatif bir hedef de geçerli sayılıyordu, bu da DashboardScreen'deki
            // su/protein ilerleme çubuklarında (miktar / hedef) sıfıra bölme/NaN'a
            // yol açabilirdi. Artık hedeflerin pozitif olması da şart.
            val wakeValid = runCatching { LocalTime.parse(wakeTarget, timeFormatter) }.isSuccess
            val waterValid = (waterTarget.toIntOrNull() ?: 0) > 0
            val proteinMinValid = (proteinMin.toIntOrNull() ?: 0) > 0
            val proteinMaxValid = (proteinMax.toIntOrNull() ?: 0) > 0
            Button(
                onClick = {
                    val w = waterTarget.toIntOrNull()?.takeIf { it > 0 } ?: return@Button
                    val pMin = proteinMin.toIntOrNull()?.takeIf { it > 0 } ?: return@Button
                    val pMax = proteinMax.toIntOrNull()?.takeIf { it > 0 } ?: return@Button
                    val wake = runCatching { LocalTime.parse(wakeTarget, timeFormatter) }.getOrNull() ?: return@Button
                    viewModel.save(w, pMin, pMax, wake)
                },
                enabled = !ui.isSaving && ui.profile != null &&
                    waterValid && proteinMinValid && proteinMaxValid && wakeValid,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (ui.isSaving) "Kaydediliyor..." else "Kaydet")
            }

            if (ui.saved) Text("Kaydedildi.", color = MaterialTheme.colorScheme.primary)
            ui.error?.let { Text("Hata: $it", color = MaterialTheme.colorScheme.error) }

            OutlinedButton(onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth()) {
                Text("Çıkış Yap")
            }

            Text("Senkronizasyon", style = MaterialTheme.typography.titleMedium)
            // Önceden offline kuyruk (PENDING kayıtlar) tamamen görünmezdi — arka planda
            // sessizce çalıştığı için sürekli bir ağ hatası olsa bile kullanıcının fark
            // etmesinin hiçbir yolu yoktu (bkz. HealthSyncRepository.pendingSyncCount).
            Text(
                when (val count = ui.pendingSyncCount) {
                    null -> "Kontrol ediliyor..."
                    0 -> "Her şey senkronize edildi."
                    else -> "$count kayıt senkronize edilmeyi bekliyor."
                },
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedButton(onClick = viewModel::refreshPendingSyncCount, modifier = Modifier.fillMaxWidth()) {
                Text("Yenile")
            }
        }
    }
}
