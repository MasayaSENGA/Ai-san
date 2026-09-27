package com.aisan.radio.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisan.radio.data.AppDatabase
import com.aisan.radio.data.Station
import com.aisan.radio.location.CurrentLocation
import com.aisan.radio.location.LocationResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StationUiState(
    val band: Band = Band.FM,
    val query: String = "",
    val results: List<Station> = emptyList(),
    val searched: Boolean = false,
    val errorMessage: String? = null,
    val sortByDistance: Boolean = false,
    val origin: GeoPoint? = null,
    val locating: Boolean = false,
    val locationMessage: String? = null,
    // 位置情報の権限が「今後表示しない」で拒否され、設定アプリでしか許可できない
    val locationPermissionBlocked: Boolean = false
)

class StationViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).stationDao()
    private val prefs = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(
        StationUiState(
            sortByDistance = prefs.getBoolean(KEY_SORT_BY_DISTANCE, false) && CurrentLocation.hasPermission(application)
        )
    )
    val uiState: StateFlow<StationUiState> = _uiState.asStateFlow()

    // DB から出力の大きい順で取得した結果。現在地から近い順はここから並べ替える。
    private var resultsByPower: List<Station> = emptyList()
    private var searchedFreqKhz: Double = 0.0
    private var locationJob: Job? = null

    init {
        // 設定アプリなど、アプリ外で許可された場合も「拒否されたことがある」記録は消す
        if (CurrentLocation.hasPermission(application)) prefs.edit().remove(KEY_LOCATION_DENIED_ONCE).apply()
    }

    fun onBandSelected(band: Band) {
        resultsByPower = emptyList()
        _uiState.update { it.copy(band = band, results = emptyList(), searched = false, errorMessage = null) }
    }

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query, errorMessage = null) }
    }

    fun search() {
        val state = _uiState.value
        val inputValue = state.query.trim().toDoubleOrNull()
        if (inputValue == null) {
            resultsByPower = emptyList()
            _uiState.update { it.copy(results = emptyList(), searched = false, errorMessage = "数値を入力してください") }
            return
        }

        val freqKhz = state.band.toFreqKhz(inputValue)
        viewModelScope.launch {
            resultsByPower = dao.findByFrequency(state.band.dbValue, freqKhz, state.band.toleranceKhz)
            searchedFreqKhz = freqKhz
            _uiState.update { it.copy(results = sorted(it), searched = true, errorMessage = null) }
            if (_uiState.value.sortByDistance) startLocationRefresh()
        }
    }

    // 位置情報の権限が許可されている状態で呼ぶ(オンにする場合)
    fun onSortByDistanceChanged(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SORT_BY_DISTANCE, enabled).apply()
        if (!enabled) {
            locationJob?.cancel()
            locationJob = null
        }
        _uiState.update {
            val next = it.copy(
                sortByDistance = enabled,
                origin = if (enabled) it.origin else null,
                locating = if (enabled) it.locating else false,
                locationMessage = null,
                locationPermissionBlocked = false
            )
            next.copy(results = sorted(next))
        }
        if (enabled) startLocationRefresh()
    }

    fun onLocationPermissionGranted() {
        prefs.edit().remove(KEY_LOCATION_DENIED_ONCE).apply()
        onSortByDistanceChanged(true)
    }

    // rationaleBefore/After: 許可を求める直前・拒否された直後の shouldShowRequestPermissionRationale。
    // 拒否した後に説明が不要(false)になるのは、もう許可ダイアログを出せない場合だが、Android 11以降は
    // ダイアログを閉じただけでも前後とも false になる。そのため、一度明示的に拒否されたことがある場合に限って
    // 設定アプリでしか許可できない状態とみなす。
    fun onLocationPermissionDenied(rationaleBefore: Boolean, rationaleAfter: Boolean) {
        if (rationaleAfter) prefs.edit().putBoolean(KEY_LOCATION_DENIED_ONCE, true).apply()
        val permanently = !rationaleAfter && (rationaleBefore || prefs.getBoolean(KEY_LOCATION_DENIED_ONCE, false))
        prefs.edit().putBoolean(KEY_SORT_BY_DISTANCE, false).apply()
        _uiState.update {
            val next = it.copy(
                sortByDistance = false,
                origin = null,
                locationMessage = if (permanently) MESSAGE_PERMISSION_BLOCKED else MESSAGE_PERMISSION_DENIED,
                locationPermissionBlocked = permanently
            )
            next.copy(results = sorted(next))
        }
    }

    // 画面に戻ってきたとき(設定アプリで位置情報を許可して戻ってきた場合など)
    fun onResume() {
        if (!CurrentLocation.hasPermission(getApplication())) return
        prefs.edit().remove(KEY_LOCATION_DENIED_ONCE).apply()
        val state = _uiState.value
        when {
            state.locationPermissionBlocked -> onLocationPermissionGranted()
            state.locationMessage == MESSAGE_PERMISSION_DENIED -> _uiState.update { it.copy(locationMessage = null) }
        }
    }

    private fun startLocationRefresh() {
        if (locationJob?.isActive == true) return  // 取得中の結果で並べ替える
        locationJob = viewModelScope.launch { refreshLocation() }
    }

    private suspend fun refreshLocation() {
        _uiState.update { it.copy(locating = true, locationMessage = null) }
        val result = CurrentLocation.get(getApplication())
        _uiState.update { state ->
            // 取得中にスイッチがオフにされたら結果は使わない
            if (!state.sortByDistance) return@update state.copy(locating = false)
            val next = when (result) {
                is LocationResult.Found -> state.copy(origin = result.point, locationMessage = null)
                LocationResult.PermissionDenied ->
                    state.copy(sortByDistance = false, origin = null, locationMessage = MESSAGE_PERMISSION_DENIED)
                // 以前の位置で距離を出し続けないよう、取得できなければ出力の大きい順に戻す
                LocationResult.Disabled ->
                    state.copy(origin = null, locationMessage = "端末の位置情報がオフのため、現在地を取得できません")
                LocationResult.Unavailable -> state.copy(origin = null, locationMessage = "現在地を取得できませんでした")
            }.copy(locating = false)
            next.copy(results = sorted(next))
        }
        if (result == LocationResult.PermissionDenied) prefs.edit().putBoolean(KEY_SORT_BY_DISTANCE, false).apply()
    }

    private fun sorted(state: StationUiState): List<Station> {
        val origin = state.origin
        return if (state.sortByDistance && origin != null) {
            sortByDistance(resultsByPower, searchedFreqKhz, origin)
        } else {
            resultsByPower
        }
    }

    private companion object {
        const val KEY_SORT_BY_DISTANCE = "sort_by_distance"
        const val KEY_LOCATION_DENIED_ONCE = "location_denied_once"
        const val MESSAGE_PERMISSION_DENIED = "位置情報の利用が許可されていないため、出力の大きい順で表示しています"
        const val MESSAGE_PERMISSION_BLOCKED =
            "位置情報の利用が許可されていません。設定アプリでこのアプリに位置情報を許可すると、現在地から近い順に並べられます"
    }
}
