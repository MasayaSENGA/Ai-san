package com.aisan.radio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisan.radio.data.AppDatabase
import com.aisan.radio.data.Station
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StationUiState(
    val band: Band = Band.FM,
    val query: String = "",
    val results: List<Station> = emptyList(),
    val searched: Boolean = false,
    val errorMessage: String? = null
)

class StationViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).stationDao()

    private val _uiState = MutableStateFlow(StationUiState())
    val uiState: StateFlow<StationUiState> = _uiState.asStateFlow()

    fun onBandSelected(band: Band) {
        _uiState.value = _uiState.value.copy(band = band, results = emptyList(), searched = false, errorMessage = null)
    }

    fun onQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(query = query, errorMessage = null)
    }

    fun search() {
        val state = _uiState.value
        val inputValue = state.query.trim().toDoubleOrNull()
        if (inputValue == null) {
            _uiState.value = state.copy(results = emptyList(), searched = false, errorMessage = "数値を入力してください")
            return
        }

        val freqKhz = state.band.toFreqKhz(inputValue)
        viewModelScope.launch {
            val results = dao.findByFrequency(state.band.dbValue, freqKhz, state.band.toleranceKhz)
            _uiState.value = _uiState.value.copy(results = results, searched = true, errorMessage = null)
        }
    }
}
