package com.aisan.radio

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aisan.radio.location.CurrentLocation
import com.aisan.radio.ui.AreaFilter
import com.aisan.radio.ui.Band
import com.aisan.radio.ui.GeoPoint
import com.aisan.radio.ui.ListRow
import com.aisan.radio.ui.ListSortOrder
import com.aisan.radio.ui.NetworkFacet
import com.aisan.radio.ui.StationCategory
import com.aisan.radio.ui.StationEntry
import com.aisan.radio.ui.StationListUiState
import com.aisan.radio.ui.StationListViewModel
import com.aisan.radio.ui.TransmitterFilter
import com.aisan.radio.ui.areaOptions
import com.aisan.radio.ui.distanceKmFrom
import com.aisan.radio.ui.networkLabel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationListScreen(viewModel: StationListViewModel, bottomBar: @Composable () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()

    var rationaleBeforeRequest by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.onLocationPermissionGranted()
        } else {
            viewModel.onLocationPermissionDenied(rationaleBeforeRequest, context.shouldShowLocationRationale())
        }
    }
    val selectSort: (ListSortOrder) -> Unit = { order ->
        if (order != ListSortOrder.DISTANCE || CurrentLocation.hasPermission(context)) {
            viewModel.onSortSelected(order)
        } else {
            rationaleBeforeRequest = context.shouldShowLocationRationale()
            // 距離順に並べるだけなので、おおよその位置情報で足りる
            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    LaunchedEffect(Unit) { viewModel.onShown() }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("放送局一覧") },
                actions = {
                    if (uiState.filter.isActive) {
                        TextButton(onClick = viewModel::clearFilters) { Text("条件をクリア") }
                    }
                },
                scrollBehavior = scrollBehavior
            )
        },
        bottomBar = bottomBar
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding()
        ) {
            ListHeader(uiState, viewModel, selectSort, onOpenSettings = { context.openAppSettings() })
            StationList(uiState, viewModel, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ListHeader(
    uiState: StationListUiState,
    viewModel: StationListViewModel,
    onSortSelected: (ListSortOrder) -> Unit,
    onOpenSettings: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val filter = uiState.filter
    val counts = uiState.counts

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = filter.keyword,
            onValueChange = viewModel::onKeywordChanged,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("キーワード") },
            placeholder = { Text("局名・地域・コールサイン・言語など") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (filter.keyword.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onKeywordChanged("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "キーワードを消去")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (filter.showsArea) {
                MenuFilterChip(
                    title = "地域",
                    selectedLabel = filter.area.takeIf { it != AreaFilter.All }?.label,
                    options = areaOptions(),
                    optionLabel = { it.menuLabel },
                    indented = { it is AreaFilter.InPrefecture && it.prefecture != "北海道" },
                    isSelected = { it == filter.area },
                    count = { counts.area[it] },
                    onSelected = viewModel::onAreaSelected
                )
            }
            MenuFilterChip(
                title = "種類",
                selectedLabel = filter.category?.label,
                options = listOf<StationCategory?>(null) + StationCategory.entries,
                optionLabel = { it?.menuLabel ?: "すべて" },
                isSelected = { it == filter.category },
                count = { counts.category[it] },
                onSelected = viewModel::onCategorySelected
            )
            if (filter.showsTransmitter) {
                MenuFilterChip(
                    title = "送信所",
                    selectedLabel = filter.transmitter.chipLabel,
                    options = TransmitterFilter.entries,
                    optionLabel = { it.menuLabel },
                    isSelected = { it == filter.transmitter },
                    count = { counts.transmitter[it] },
                    onSelected = viewModel::onTransmitterSelected
                )
            }
            MenuFilterChip(
                title = "バンド",
                selectedLabel = filter.band?.label,
                options = listOf<Band?>(null) + Band.entries,
                optionLabel = { it?.label ?: "すべて" },
                isSelected = { it == filter.band },
                count = { counts.band[it] },
                onSelected = viewModel::onBandSelected
            )
            if (filter.showsNetwork) {
                MenuFilterChip(
                    title = "系列",
                    selectedLabel = filter.network?.label,
                    options = listOf<NetworkFacet?>(null) + NetworkFacet.entries,
                    optionLabel = { it?.label ?: "すべて" },
                    isSelected = { it == filter.network },
                    count = { counts.network[it] },
                    onSelected = viewModel::onNetworkSelected,
                    helper = "民放の系列です。JRNとNRNの両方に加盟する局はどちらにも含まれます"
                )
            }
            if (filter.showsLanguage) {
                MenuFilterChip(
                    title = "言語",
                    selectedLabel = filter.language,
                    options = listOf<String?>(null) + uiState.languageOptions,
                    optionLabel = { it ?: "すべて" },
                    isSelected = { it == filter.language },
                    count = { counts.language[it] },
                    onSelected = viewModel::onLanguageSelected
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            val countLabel = when {
                uiState.loading -> "読み込み中…"
                !filter.isActive -> "全%,d件".format(uiState.totalCount)
                else -> "%,d件(全%,d件中)".format(uiState.matchCount, uiState.totalCount)
            }
            Text(
                countLabel,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
            SortMenu(uiState.sort, onSortSelected)
        }

        val locationStatus = if (uiState.sort == ListSortOrder.DISTANCE && uiState.locating) {
            "現在地を取得中…"
        } else {
            uiState.locationMessage
        }
        if (locationStatus != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    locationStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                if (uiState.locationRetryable && !uiState.locating) {
                    IconButton(onClick = viewModel::onRetryLocation) {
                        Icon(Icons.Filled.Refresh, contentDescription = "現在地を再取得")
                    }
                }
                if (uiState.locationPermissionBlocked) {
                    TextButton(onClick = onOpenSettings) { Text("設定を開く") }
                }
            }
        }
    }
}

// タップすると選択肢のメニューが開くチップ。選択肢ごとに、選んだときの件数を表示する
@Composable
private fun <T> MenuFilterChip(
    title: String,
    selectedLabel: String?,
    options: List<T>,
    optionLabel: (T) -> String,
    isSelected: (T) -> Boolean,
    count: (T) -> Int?,
    onSelected: (T) -> Unit,
    indented: (T) -> Boolean = { false },
    helper: String? = null
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selectedLabel != null,
            onClick = { expanded = true },
            label = { Text(selectedLabel ?: title) },
            trailingIcon = {
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize))
            },
            modifier = Modifier.semantics { contentDescription = "$title: ${selectedLabel ?: "すべて"}" }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (helper != null) {
                Text(
                    helper,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 幅を決めないとメニュー全体がこの1行の長さまで広がる
                    modifier = Modifier
                        .widthIn(max = 280.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            options.forEach { option ->
                val selected = isSelected(option)
                val optionCount = count(option)
                DropdownMenuItem(
                    text = { Text(optionLabel(option), modifier = Modifier.padding(start = if (indented(option)) 24.dp else 0.dp)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    },
                    leadingIcon = {
                        if (selected) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
                    },
                    trailingIcon = {
                        optionCount?.let { Text("%,d".format(it), style = MaterialTheme.typography.labelMedium) }
                    },
                    // 選ぶと0件になる選択肢は選べないようにする
                    enabled = optionCount == null || optionCount > 0 || selected
                )
            }
        }
    }
}

@Composable
private fun SortMenu(sort: ListSortOrder, onSortSelected: (ListSortOrder) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text("並び順: ${sort.label}")
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ListSortOrder.entries.forEach { order ->
                DropdownMenuItem(
                    text = { Text(order.label) },
                    onClick = {
                        expanded = false
                        onSortSelected(order)
                    },
                    leadingIcon = {
                        if (order == sort) Icon(Icons.Filled.Check, contentDescription = null) else Spacer(Modifier.size(24.dp))
                    },
                    trailingIcon = {
                        if (order == ListSortOrder.DISTANCE) Icon(Icons.Filled.LocationOn, contentDescription = null)
                    }
                )
            }
        }
    }
}

@Composable
private fun StationList(uiState: StationListUiState, viewModel: StationListViewModel, modifier: Modifier) {
    val listState = rememberLazyListState()
    // 条件や並び順を変えたときだけ先頭に戻す(タブを切り替えて戻ってきたときは戻さない)
    var seenVersion by rememberSaveable { mutableIntStateOf(uiState.scrollResetVersion) }
    LaunchedEffect(uiState.scrollResetVersion) {
        if (uiState.scrollResetVersion != seenVersion) {
            seenVersion = uiState.scrollResetVersion
            listState.scrollToItem(0)
        }
    }
    val origin = if (uiState.effectiveSort == ListSortOrder.DISTANCE) uiState.origin else null

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(uiState.rows, key = { it.key }, contentType = { it::class }) { row ->
            when (row) {
                is ListRow.Header -> Text(
                    "${row.label}  ${"%,d".format(row.count)}件",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .semantics { heading() }
                )
                is ListRow.Item -> {
                    val id = row.entry.station.id
                    StationListCard(
                        entry = row.entry,
                        origin = origin,
                        expanded = id in uiState.expandedIds,
                        onToggle = { viewModel.onToggleNote(id) }
                    )
                }
            }
        }
        if (!uiState.loading && uiState.matchCount == 0) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("条件に合う放送局はありません", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = viewModel::clearFilters) { Text("条件をクリア") }
                }
            }
        }
        item {
            DataSourceNotice()
        }
    }
}

@Composable
private fun StationListCard(entry: StationEntry, origin: GeoPoint?, expanded: Boolean, onToggle: () -> Unit) {
    val station = entry.station
    val note = station.note?.takeIf { it.isNotBlank() }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (note != null) {
                    Modifier
                        .clip(CardDefaults.shape)
                        .clickable(onClickLabel = if (expanded) "注記をたたむ" else "注記をすべて表示", onClick = onToggle)
                } else {
                    Modifier
                }
            )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(station.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

            val distance = origin?.let { station.distanceKmFrom(it) }?.let(::formatDistance)
            Text(
                listOfNotNull("${entry.band.label} ${formatFrequency(station.freq_khz, entry.band)}", distance)
                    .joinToString(" ・ "),
                style = MaterialTheme.typography.bodyMedium
            )

            val classification = if (entry.category == StationCategory.INTERNATIONAL) {
                listOfNotNull("国際放送", station.network, station.language, station.target_area?.let { "${it}向け" })
            } else {
                listOfNotNull(entry.category.label, networkLabel(station, entry.category), entry.role?.label)
            }
            Text(
                classification.joinToString(" ・ "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val subInfo = listOfNotNull(station.area, station.callsign, station.power_kw?.let(::formatPower))
                .joinToString(" ・ ")
            if (subInfo.isNotEmpty()) {
                Text(subInfo, style = MaterialTheme.typography.bodySmall)
            }

            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
