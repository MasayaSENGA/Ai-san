package com.aisan.radio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.aisan.radio.data.Station
import com.aisan.radio.ui.Band
import com.aisan.radio.ui.StationViewModel
import java.math.BigDecimal
import java.math.MathContext

class MainActivity : ComponentActivity() {
    private val viewModel: StationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StationScreen(viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StationScreen(viewModel: StationViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Ai-san") })
        }
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
                    StationCard(station, uiState.band)
                }
                item {
                    DataSourceNotice()
                }
            }
        }
    }
}

@Composable
fun StationCard(station: Station, band: Band) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(station.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(formatFrequency(station.freq_khz, band), style = MaterialTheme.typography.bodyMedium)

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
        "出典: 総務省 電波利用ポータル、EiBi Shortwave Schedules、Wikipedia。" +
            "このサービスは、総務省 電波利用ポータルのWeb-API 機能を利用して取得した情報をもとに作成しているが、" +
            "サービスの内容は総務省によって保証されたものではない。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp)
    )
}

// 空中線電力を "出力 10kW" / "出力 300W" のように表示する
private fun formatPower(powerKw: Double): String {
    val (value, unit) = if (powerKw >= 1.0) powerKw to "kW" else powerKw * 1000.0 to "W"
    val digits = BigDecimal.valueOf(value).round(MathContext(3)).stripTrailingZeros().toPlainString()
    return "出力 $digits$unit"
}

private fun formatFrequency(freqKhz: Double?, band: Band): String {
    if (freqKhz == null) return "-"
    return when (band) {
        Band.FM -> "%.1f MHz".format(freqKhz / 1000.0)
        Band.AM, Band.SW -> "%.0f kHz".format(freqKhz)
    }
}
