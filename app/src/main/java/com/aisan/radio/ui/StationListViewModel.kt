package com.aisan.radio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aisan.radio.data.AppDatabase
import com.aisan.radio.location.CurrentLocation
import com.aisan.radio.location.LocationPermissionMemory
import com.aisan.radio.location.LocationResult
import com.aisan.radio.location.MESSAGE_LOCATION_PERMISSION_BLOCKED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale

data class StationListUiState(
    val loading: Boolean = true,
    val totalCount: Int = 0,
    val filter: StationFilter = StationFilter(),
    val sort: ListSortOrder = ListSortOrder.PREFECTURE,
    val effectiveSort: ListSortOrder = ListSortOrder.PREFECTURE,
    val rows: List<ListRow> = emptyList(),
    val matchCount: Int = 0,
    val counts: FacetCounts = FacetCounts(),
    val languageOptions: List<String> = emptyList(),
    val origin: GeoPoint? = null, // 「現在地から近い順」のときだけ持つ
    val locating: Boolean = false,
    val locationMessage: String? = null,
    val locationRetryable: Boolean = false,
    val locationPermissionBlocked: Boolean = false,
    val expandedIds: Set<String> = emptySet(),
    // 条件・並び順を変えたとき(と、現在地が取れて距離順に並び替わったとき)に増やす。一覧を先頭に戻すのに使う。
    // 現在地の更新だけでは増やさない(タブに戻るたびに先頭へ戻らないように)
    val scrollResetVersion: Int = 0,
)

// 放送局一覧。全件をメモリに読み込み、絞り込み・並べ替えはアプリ側で行う(約2,500件なので十分速い)
class StationListViewModel(application: Application) : AndroidViewModel(application) {

    private val dao = AppDatabase.getInstance(application).stationDao()
    private val permissionMemory = LocationPermissionMemory(application)

    private val _uiState = MutableStateFlow(StationListUiState())
    val uiState: StateFlow<StationListUiState> = _uiState.asStateFlow()

    private var entries: List<StationEntry>? = null
    private var loadJob: Job? = null
    private var computeJob: Job? = null
    private var locationJob: Job? = null
    private var lastQuery: QueryKey? = null
    // 先頭に戻す要求は、途中で取り消された計算の分も含めて、結果を反映できた計算で1回だけ行う
    private var pendingScrollReset = false
    private var shown = false

    private data class QueryKey(val filter: StationFilter, val sort: ListSortOrder, val origin: GeoPoint?)

    // 一覧のタブを開くたびに呼ぶ。最初の1回だけDBから読み込む
    fun onShown() {
        shown = true
        if (entries == null && loadJob == null) {
            loadJob = viewModelScope.launch {
                val stations = dao.getAll()
                val built = withContext(Dispatchers.Default) {
                    buildEntries(stations, Collator.getInstance(Locale.JAPANESE))
                }
                entries = built
                _uiState.update {
                    it.copy(totalCount = built.size, languageOptions = languageOptions(built))
                }
                recompute()
            }
        }
        if (_uiState.value.sort == ListSortOrder.DISTANCE) startLocationRefresh()
    }

    fun onKeywordChanged(keyword: String) = updateFilter { it.copy(keyword = keyword) }
    fun onAreaSelected(area: AreaFilter) = updateFilter { it.copy(area = area) }
    fun onCategorySelected(category: StationCategory?) = updateFilter { it.copy(category = category) }
    fun onTransmitterSelected(transmitter: TransmitterFilter) = updateFilter { it.copy(transmitter = transmitter) }
    fun onBandSelected(band: Band?) = updateFilter { it.copy(band = band) }
    fun onNetworkSelected(network: NetworkFacet?) = updateFilter { it.copy(network = network) }
    fun onLanguageSelected(language: String?) = updateFilter { it.copy(language = language) }

    // 並び順はそのままにして条件だけ戻す
    fun clearFilters() = updateFilter { StationFilter() }

    fun onToggleNote(id: String) {
        _uiState.update { state ->
            state.copy(expandedIds = if (id in state.expandedIds) state.expandedIds - id else state.expandedIds + id)
        }
    }

    // 「現在地から近い順」は位置情報の権限がある状態で呼ぶ
    fun onSortSelected(order: ListSortOrder) {
        val distance = order == ListSortOrder.DISTANCE
        if (!distance) {
            locationJob?.cancel()
            locationJob = null
        }
        _uiState.update {
            it.copy(
                sort = order,
                origin = if (distance) it.origin else null,
                locating = if (distance) it.locating else false,
                locationMessage = null,
                locationRetryable = false,
                locationPermissionBlocked = false,
            )
        }
        recompute()
        if (distance) startLocationRefresh()
    }

    fun onLocationPermissionGranted() {
        permissionMemory.clear()
        onSortSelected(ListSortOrder.DISTANCE)
    }

    // rationaleBefore/After: 許可を求める直前・拒否された直後の shouldShowRequestPermissionRationale
    fun onLocationPermissionDenied(rationaleBefore: Boolean, rationaleAfter: Boolean) {
        val permanently = permissionMemory.recordDenial(rationaleBefore, rationaleAfter)
        _uiState.update {
            it.copy(
                locationMessage = if (permanently) MESSAGE_LOCATION_PERMISSION_BLOCKED else MESSAGE_PERMISSION_DENIED,
                locationRetryable = false,
                locationPermissionBlocked = permanently,
            )
        }
    }

    fun onRetryLocation() {
        if (_uiState.value.sort == ListSortOrder.DISTANCE) startLocationRefresh()
    }

    // 画面に戻ってきたとき(設定アプリで位置情報を許可して戻ってきた場合など)
    fun onResume() {
        if (!CurrentLocation.hasPermission(getApplication())) return
        permissionMemory.clear()
        val state = _uiState.value
        when {
            state.locationPermissionBlocked -> onLocationPermissionGranted()
            state.locationMessage == MESSAGE_PERMISSION_DENIED -> _uiState.update { it.copy(locationMessage = null) }
            shown && state.sort == ListSortOrder.DISTANCE -> startLocationRefresh()
        }
    }

    private fun updateFilter(transform: (StationFilter) -> StationFilter) {
        // 入力欄が遅れないよう、条件はすぐに反映して結果だけ後から計算する
        _uiState.update { it.copy(filter = transform(it.filter).normalized()) }
        recompute()
    }

    private fun recompute() {
        val loaded = entries ?: return
        val state = _uiState.value
        val key = QueryKey(state.filter, state.sort, if (state.sort == ListSortOrder.DISTANCE) state.origin else null)
        val previous = lastQuery
        if (key == previous) return
        lastQuery = key
        pendingScrollReset = pendingScrollReset || previous == null || previous.filter != key.filter ||
            previous.sort != key.sort || (previous.origin == null) != (key.origin == null)
        computeJob?.cancel()
        computeJob = viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                queryStations(loaded, key.filter, key.sort, key.origin, state.languageOptions)
            }
            val resetScroll = pendingScrollReset
            pendingScrollReset = false
            _uiState.update {
                it.copy(
                    loading = false,
                    rows = result.rows,
                    matchCount = result.matchCount,
                    effectiveSort = result.effectiveSort,
                    counts = result.counts,
                    scrollResetVersion = if (resetScroll) it.scrollResetVersion + 1 else it.scrollResetVersion,
                )
            }
        }
    }

    private fun startLocationRefresh() {
        if (locationJob?.isActive == true) return // 取得中の結果で並べ替える
        locationJob = viewModelScope.launch { refreshLocation() }
    }

    private suspend fun refreshLocation() {
        _uiState.update { it.copy(locating = true, locationMessage = null, locationRetryable = false) }
        val result = CurrentLocation.get(getApplication())
        _uiState.update { state ->
            // 取得中に並び順が変えられたら結果は使わない
            if (state.sort != ListSortOrder.DISTANCE) return@update state.copy(locating = false)
            when (result) {
                is LocationResult.Found -> state.copy(origin = result.point)
                LocationResult.PermissionDenied ->
                    state.copy(sort = ListSortOrder.PREFECTURE, origin = null, locationMessage = MESSAGE_PERMISSION_DENIED)
                // 以前の位置で並べ続けないよう、取得できなければ都道府県順に戻す
                LocationResult.Disabled -> state.copy(
                    origin = null,
                    locationMessage = "端末の位置情報がオフのため、現在地を取得できません(都道府県順で表示しています)",
                    locationRetryable = true,
                )
                LocationResult.Unavailable -> state.copy(
                    origin = null,
                    locationMessage = "現在地を取得できませんでした(都道府県順で表示しています)",
                    locationRetryable = true,
                )
            }.copy(locating = false)
        }
        recompute()
    }

    private companion object {
        const val MESSAGE_PERMISSION_DENIED = "位置情報の利用が許可されていないため、現在地から近い順に並べられません"
    }
}
