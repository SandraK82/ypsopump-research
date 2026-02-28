package com.ypsopump.test.viewmodel

import androidx.lifecycle.ViewModel
import com.ypsopump.test.key.KeyImporter
import com.ypsopump.test.key.SecureKeyStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.*

data class KeyUiState(
    val hasKey: Boolean = false,
    val keyPreview: String = "",
    val storedAt: String = "",
    val inputText: String = "",
    val statusMessage: String = "",
    val isError: Boolean = false
)

class KeyViewModel(private val keyStore: SecureKeyStore) : ViewModel() {

    private val _uiState = MutableStateFlow(KeyUiState())
    val uiState: StateFlow<KeyUiState> = _uiState.asStateFlow()

    init {
        refreshKeyStatus()
    }

    fun refreshKeyStatus() {
        val hasKey = keyStore.hasKey()
        val keyHex = keyStore.getKeyHex()
        val storedAt = keyStore.getStoredAt()

        _uiState.value = _uiState.value.copy(
            hasKey = hasKey,
            keyPreview = if (keyHex != null) {
                "${keyHex.take(8)}...${keyHex.takeLast(8)}"
            } else "",
            storedAt = if (storedAt > 0) {
                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(storedAt))
            } else ""
        )
    }

    fun updateInput(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text, statusMessage = "", isError = false)
    }

    fun importHexKey() {
        val input = _uiState.value.inputText
        when (val result = KeyImporter.fromHexString(input)) {
            is KeyImporter.ImportResult.Success -> {
                keyStore.storeKey(result.keyHex)
                _uiState.value = _uiState.value.copy(
                    inputText = "",
                    statusMessage = "Key imported successfully!",
                    isError = false
                )
                refreshKeyStatus()
            }
            is KeyImporter.ImportResult.Error -> {
                _uiState.value = _uiState.value.copy(
                    statusMessage = result.message,
                    isError = true
                )
            }
        }
    }

    fun deleteKey() {
        keyStore.deleteKey()
        _uiState.value = KeyUiState(statusMessage = "Key deleted", isError = false)
    }
}
