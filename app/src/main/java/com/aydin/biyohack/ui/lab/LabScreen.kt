package com.aydin.biyohack.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aydin.biyohack.data.LabResult
import com.aydin.biyohack.data.repository.HealthSyncRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LabUiState(val results: List<LabResult> = emptyList())

@HiltViewModel
class LabViewModel @Inject constructor(
    private val repository: HealthSyncRepository
) : ViewModel() {

    private val _ui = MutableStateFlow(LabUiState())
    val ui: StateFlow<LabUiState> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeLabResults().collect { results ->
                _ui.update { it.copy(results = results) }
            }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Laboratuvar Seyri") },
                navigationIcon = { TextButton(onClick = onBack) { Text("← Geri") } }
            )
        }
    ) { padding ->
        if (grouped.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                Text("Henüz laboratuvar sonucu yok. Web panelinden gir, senkronla.")
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            grouped.forEach { (panel, results) ->
                item {
                    Text(panel, style = MaterialTheme.typography.titleMedium)
                }
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
                        }
                    }
                }
            }
        }
    }
}
