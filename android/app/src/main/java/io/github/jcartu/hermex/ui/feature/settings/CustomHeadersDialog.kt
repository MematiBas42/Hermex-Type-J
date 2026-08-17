package io.github.jcartu.hermex.ui.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jcartu.hermex.core.prefs.EncryptedCustomHeaderRepository
import io.github.jcartu.hermex.core.prefs.ServerConfigRepository
import io.github.jcartu.hermex.net.CustomHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CustomHeadersUiState(
    val headers: List<CustomHeader> = emptyList(),
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val saved: Boolean = false,
)

class CustomHeadersViewModel(
    private val repository: EncryptedCustomHeaderRepository,
    private val serverConfig: ServerConfigRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(CustomHeadersUiState())
    val uiState: StateFlow<CustomHeadersUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.headers.collect { headers -> _uiState.update { it.copy(headers = headers) } }
        }
    }

    fun validate(headers: List<CustomHeader>): List<String> = repository.validate(headers)

    fun save(headers: List<CustomHeader>) = viewModelScope.launch {
        _uiState.update { it.copy(isSaving = true, errorMessage = null, saved = false) }
        try {
            repository.replaceFor(serverConfig.current().baseUrl, headers)
            _uiState.update { it.copy(isSaving = false, saved = true) }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            _uiState.update { it.copy(isSaving = false, errorMessage = error.message ?: "Could not save headers.") }
        }
    }

    fun consumeSaved() = _uiState.update { it.copy(saved = false) }
}

@Composable
fun CustomHeadersDialog(
    state: CustomHeadersUiState,
    validate: (List<CustomHeader>) -> List<String>,
    onSave: (List<CustomHeader>) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember(state.headers) {
        mutableStateListOf<CustomHeader>().apply {
            addAll(state.headers.ifEmpty { listOf(CustomHeader()) })
        }
    }
    val revealed = remember { mutableStateMapOf<Int, Boolean>() }
    val problems = validate(rows)

    LaunchedEffect(state.saved) {
        if (state.saved) onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom request headers") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Encrypted per server origin. Useful for Authentik, Cloudflare Access, or token-gated reverse proxies. Values are never logged.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 500.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(rows, key = { index, _ -> index }) { index, header ->
                        Column {
                            OutlinedTextField(
                                value = header.name,
                                onValueChange = { rows[index] = header.copy(name = it) },
                                label = { Text("Header name") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val show = revealed[index] == true
                                OutlinedTextField(
                                    value = header.value,
                                    onValueChange = { rows[index] = header.copy(value = it) },
                                    label = { Text("Secret value") },
                                    singleLine = true,
                                    visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                                    modifier = Modifier.weight(1f),
                                    trailingIcon = {
                                        IconButton(onClick = { revealed[index] = !show }) {
                                            Icon(if (show) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, if (show) "Hide value" else "Show value")
                                        }
                                    },
                                )
                                IconButton(onClick = { rows.removeAt(index); revealed.remove(index) }) {
                                    Icon(Icons.Filled.Delete, "Delete header")
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { rows += CustomHeader() }) {
                    Icon(Icons.Filled.Add, null); Text("Add header", Modifier.padding(start = 6.dp))
                }
                problems.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(rows.toList()) }, enabled = problems.isEmpty() && !state.isSaving) {
                Text(if (state.isSaving) "Saving…" else "Save encrypted")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
