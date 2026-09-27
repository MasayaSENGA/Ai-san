package com.aisan.radio

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.aisan.radio.data.Station
import com.aisan.radio.location.CurrentLocation
import com.aisan.radio.ui.Band
import com.aisan.radio.ui.GeoPoint
import com.aisan.radio.ui.StationListViewModel
import com.aisan.radio.ui.StationViewModel
import com.aisan.radio.ui.distanceKmFrom
import java.math.BigDecimal
import java.math.MathContext
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    private val viewModel: StationViewModel by viewModels()
    private val listViewModel: StationListViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AiSanApp(viewModel, listViewModel)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
        listViewModel.onResume()
    }
}

enum class AppDestination(val label: String, val icon: ImageVector) {
    SEARCH("周波数で検索", Icons.Filled.Search),
    LIST("放送局一覧", Icons.AutoMirrored.Filled.List),
}

@Composable
fun AiSanApp(searchViewModel: StationViewModel, listViewModel: StationListViewModel) {
    var destination by rememberSaveable { mutableStateOf(AppDestination.SEARCH) }
    // タブを切り替えても、それぞれの画面のスクロール位置などを保つ
    val stateHolder = rememberSaveableStateHolder()
    BackHandler(enabled = destination != AppDestination.SEARCH) { destination = AppDestination.SEARCH }
    val bottomBar: @Composable () -> Unit = { AppNavigationBar(destination) { destination = it } }
    stateHolder.SaveableStateProvider(destination.name) {
        when (destination) {
            AppDestination.SEARCH -> StationScreen(searchViewModel, bottomBar)
            AppDestination.LIST -> StationListScreen(listViewModel, bottomBar)
        }
    }
}

@Composable
fun AppNavigationBar(current: AppDestination, onSelect: (AppDestination) -> Unit) {
    NavigationBar {
        AppDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == current,
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationScreen(viewModel: StationViewModel, bottomBar: @Composable () -> Unit = {}) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var rationaleBeforeRequest by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.onLocationPermissionGranted()
        } else {
            viewModel.onLocationPermissionDenied(rationaleBeforeRequest, context.shouldShowLocationRationale())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Ai-san") })
        },
        bottomBar = bottomBar
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                Band.entries.forEachIndexed { index, band ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = Band.entries.size),
                        onClick = { viewModel.onBandSelected(band) },
                        selected = uiState.band == band
                    ) {
                        Text(band.label)
                    }
                }
            }

            OutlinedTextField(
                value = uiState.query,
                onValueChange = viewModel::onQueryChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("周波数(${uiState.band.unit})") },
                placeholder = { Text(uiState.band.hint) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(
                    onSearch = { viewModel.search() }
                ),
                isError = uiState.errorMessage != null,
                supportingText = {
                    uiState.errorMessage?.let { Text(it) }
                }
            )

            Button(
                onClick = viewModel::search,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("検索")
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SortByDistanceSwitch(
                    checked = uiState.sortByDistance,
                    onCheckedChange = { enabled ->
                        if (!enabled || CurrentLocation.hasPermission(context)) {
                            viewModel.onSortByDistanceChanged(enabled)
                        } else {
                            rationaleBeforeRequest = context.shouldShowLocationRationale()
                            // 距離順に並べるだけなので、おおよその位置情報で足りる
                            permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    }
                )
                val locationStatus = if (uiState.sortByDistance && uiState.locating) "現在地を取得中…" else uiState.locationMessage
                locationStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (uiState.locationPermissionBlocked) {
                    TextButton(onClick = { context.openAppSettings() }) {
                        Text("設定を開く")
                    }
                }
            }

            if (uiState.searched && uiState.results.isEmpty()) {
                Text(
                    "該当する放送局が見つかりませんでした",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(uiState.results) { station ->
                    StationCard(station, uiState.band, uiState.origin)
                }
                item {
                    DataSourceNotice()
                }
            }
        }
    }
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun Context.shouldShowLocationRationale(): Boolean {
    val activity = findActivity() ?: return false
    return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
}

// このアプリの詳細設定画面(権限の許可はここから行う)
internal fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

@Composable
fun SortByDistanceSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("現在地から近い順に並べる", style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun StationCard(station: Station, band: Band, origin: GeoPoint?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(station.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            val distance = origin?.let { station.distanceKmFrom(it) }?.let(::formatDistance)
            Text(
                listOfNotNull(formatFrequency(station.freq_khz, band), distance).joinToString(" ・ "),
                style = MaterialTheme.typography.bodyMedium
            )

            val subInfo = listOfNotNull(
                station.area,
                station.callsign,
                station.network,
                station.power_kw?.let(::formatPower)
            ).joinToString(" ・ ")
            if (subInfo.isNotEmpty()) {
                Text(subInfo, style = MaterialTheme.typography.bodySmall)
            }

            station.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// 総務省 Web-API の利用規約(第3条)で表示が求められている文言を含む
@Composable
fun DataSourceNotice() {
    Text(
        "出典: 総務省 電波利用ポータル、EiBi Shortwave Schedules、Wikipedia、Wikidata。" +
            "このサービスは、総務省 電波利用ポータルのWeb-API 機能を利用して取得した情報をもとに作成しているが、" +
            "サービスの内容は総務省によって保証されたものではない。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

// 空中線電力を "出力 10kW" / "出力 300W" のように表示する
internal fun formatPower(powerKw: Double): String {
    val (value, unit) = if (powerKw >= 1.0) powerKw to "kW" else powerKw * 1000.0 to "W"
    val digits = BigDecimal.valueOf(value).round(MathContext(3)).stripTrailingZeros().toPlainString()
    return "出力 $digits$unit"
}

// 局の座標は送信所の市区町村の代表点なので、km単位の目安として表示する
internal fun formatDistance(km: Double): String =
    if (km < 1.0) "現在地から1km未満" else "現在地から約%,dkm".format(km.roundToInt())

internal fun formatFrequency(freqKhz: Double?, band: Band): String {
    if (freqKhz == null) return "-"
    return when (band) {
        Band.FM -> "%.1f MHz".format(freqKhz / 1000.0)
        Band.AM, Band.SW -> "%.0f kHz".format(freqKhz)
    }
}
