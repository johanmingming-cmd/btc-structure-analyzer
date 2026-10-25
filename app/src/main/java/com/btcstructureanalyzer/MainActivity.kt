package com.btcstructureanalyzer

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val Navy = Color(0xFF07111F)
private val Panel = Color(0xFF101E2D)
private val Panel2 = Color(0xFF142538)
private val Green = Color(0xFF00D68F)
private val Red = Color(0xFFFF5364)
private val Blue = Color(0xFF1684FF)
private val Amber = Color(0xFFFFC857)
private val Muted = Color(0xFF9FB2C8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Restore the user-enabled scanner when Android recreates the activity.
        // The preference can remain ON even if Android has killed the foreground service.
        val prefs = getSharedPreferences("btc_structure", Context.MODE_PRIVATE)
        if (prefs.getBoolean("background_monitoring", false)) {
            ContextCompat.startForegroundService(this, Intent(this, FuturesAutoScanService::class.java))
        }
        setContent { MaterialTheme(colorScheme = darkColorScheme(background = Navy, surface = Panel, primary = Blue, secondary = Green)) { AnalyzerApp(this) } }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val EXTRA_SPOT_SIGNAL_NOTIFICATION = "spot_signal_notification"
    }
}

private data class SavedRun(val time: Long, val price: Double, val bias: String, val note: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalyzerApp(context: Context) {
    val scope = rememberCoroutineScope()
    val repo = remember { BinanceRepository() }
    var selected by remember { mutableStateOf(
        (context as? Activity)?.intent?.getStringExtra(MainActivity.EXTRA_OPEN_TAB) ?: "Home"
    ) }
    var notificationDetails by remember {
        mutableStateOf(
            context.getSharedPreferences("btc_structure", Context.MODE_PRIVATE)
                .getString("spot_notification_details", null)
        )
    }
    var notificationTime by remember {
        mutableStateOf(
            context.getSharedPreferences("btc_structure", Context.MODE_PRIVATE)
                .getLong("spot_notification_time", 0L)
        )
    }
    var timeframe by remember { mutableStateOf("5m") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var price by remember { mutableStateOf<Double?>(null) }
    var candles by remember { mutableStateOf<Map<String, List<Candle>>>(emptyMap()) }
    var results by remember { mutableStateOf<Map<String, StructureResult>>(emptyMap()) }
    var lastRefresh by remember { mutableStateOf<Long?>(null) }
    var autoRefresh by remember { mutableStateOf(context.getSharedPreferences("btc_structure", Context.MODE_PRIVATE).getBoolean("background_monitoring", false)) }
    var history by remember { mutableStateOf(emptyList<SavedRun>()) }
    var signalHistory by remember { mutableStateOf(emptyList<SignalRecord>()) }
    var historyRefreshLoading by remember { mutableStateOf(false) }
    var historyRefreshError by remember { mutableStateOf<String?>(null) }
    var exportLoading by remember { mutableStateOf(false) }
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            scope.launch {
                exportLoading = true
                exportMessage = null
                exportError = null
                try {
                    withContext(Dispatchers.IO) {
                        val recordsForExport = SignalJournal.read(context)
                        val output = context.contentResolver.openOutputStream(uri, "w")
                            ?: throw IllegalStateException("Tidak bisa membuka file tujuan")
                        output.use { SignalExport.writeZip(it, recordsForExport) }
                    }
                    exportMessage = "Export ZIP berhasil disimpan. Jurnal lokal tidak diubah."
                } catch (e: Exception) {
                    exportError = e.message ?: "Export gagal disimpan"
                } finally {
                    exportLoading = false
                }
            }
        }
    }
    var scanLoading by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var scanDiagnostics by remember { mutableStateOf<ScanDiagnostics?>(null) }
    var scannedCoins by remember { mutableStateOf(emptyList<ScannedCoin>()) }
    var scanCompleted by remember { mutableStateOf(false) }
    var scanCompletedAt by remember { mutableStateOf<Long?>(null) }

    fun loadHistory() {
        val raw = context.getSharedPreferences("btc_structure", Context.MODE_PRIVATE).getString("history", "") ?: ""
        history = raw.lines().filter { it.isNotBlank() }.mapNotNull { line ->
            val p = line.split("|", limit = 4)
            if (p.size == 4) runCatching { SavedRun(p[0].toLong(), p[1].toDouble(), p[2], p[3]) }.getOrNull() else null
        }
    }
    fun refresh() {
        scope.launch {
            loading = true
            error = null
            try {
                val frames = listOf("5m", "15m", "1h")
                val data = linkedMapOf<String, List<Candle>>()
                val analyzed = linkedMapOf<String, StructureResult>()
                for (tf in frames) {
                    val cs = repo.candles(tf, 120)
                    data[tf] = cs
                    analyzed[tf] = StructureAnalyzer.analyze(tf, cs)
                }
                candles = data
                results = analyzed
                price = data["5m"]?.lastOrNull()?.close
                lastRefresh = System.currentTimeMillis()
                val bias = trendLabel(analyzed["1h"]?.trend)
                val note = analyzed["1h"]?.summary ?: "Belum ada ringkasan"
                val item = SavedRun(lastRefresh!!, price ?: 0.0, bias, note)
                history = (listOf(item) + history).take(100)
                val encoded = history.joinToString("\n") { "${it.time}|${it.price}|${it.bias}|${it.note.replace("|", "/")}" }
                context.getSharedPreferences("btc_structure", Context.MODE_PRIVATE).edit().putString("history", encoded).apply()
            } catch (e: Exception) {
                error = e.message ?: "Tidak dapat mengambil data"
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { loadHistory(); signalHistory = SignalJournal.read(context); refresh() }

    Scaffold(
        containerColor = Navy,
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0A1422), tonalElevation = 0.dp) {
                listOf("Home", "Chart", "Analisis", "Riwayat", "Pengaturan").forEach { item ->
                    val icon = when(item) {
                        "Home" -> Icons.Default.Home
                        "Chart" -> Icons.Default.CandlestickChart
                        "Analisis" -> Icons.Default.Analytics
                        "Riwayat" -> Icons.Default.History
                        else -> Icons.Default.Settings
                    }
                    NavigationBarItem(selected == item, onClick = { selected = item },
                        icon = { Icon(icon, contentDescription = item) },
                        label = { Text(item, fontSize = 10.sp, maxLines = 1, softWrap = false) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Green, selectedTextColor = Green,
                            indicatorColor = Color(0xFF123044), unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        },
        topBar = {
            Column(Modifier.fillMaxWidth().background(Navy)) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("PREDICTION LAB", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                        Text("MULTI-COIN FUTURES ANALYZER", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = .5.sp)
                    }
                    IconButton(onClick = { selected = "Notifikasi" }) {
                        Icon(Icons.Default.NotificationsNone, contentDescription = "Notifikasi", tint = Color.White)
                    }
                    IconButton(onClick = { refresh() }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Perbarui data", tint = Green)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.size(7.dp).background(if (error == null) Green else Red, RoundedCornerShape(50)))
                        Text(if (error != null) "KONEKSI TERGANGGU" else if (loading) "MENGAMBIL DATA" else "BINANCE PUBLIC DATA",
                            color = if (error != null) Red else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(lastRefresh?.let { "UPDATE " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)) } ?: "MENUNGGU DATA",
                        color = Muted, fontSize = 10.sp)
                }
                Divider(color = Color(0xFF1B2B3D), thickness = 1.dp)
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).background(Navy),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (selected) {
                "Home" -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("MARKET OVERVIEW", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.4.sp)
                            Text("Market Opportunity Dashboard", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                            Text("Struktur harga multi-timeframe", color = Muted, fontSize = 12.sp)
                        }
                    }
                    item { PriceCard(price, loading, error, lastRefresh) }
                    if (error != null) item { ErrorCard(error!!) }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            listOf("1h", "15m", "5m").forEach { tf ->
                                val result = results[tf]
                                Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Panel),
                                    shape = RoundedCornerShape(16.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF20374B))) {
                                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(tf.uppercase(), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Icon(when(result?.trend) {
                                            Trend.BULLISH -> Icons.Default.TrendingUp
                                            Trend.BEARISH -> Icons.Default.TrendingDown
                                            else -> Icons.Default.Timeline
                                        }, null, tint = trendColor(result?.trend), modifier = Modifier.size(23.dp))
                                        Text(trendLabel(result?.trend), color = trendColor(result?.trend), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                                        Text(result?.swings?.takeLast(2)?.joinToString(" / ") { it.label } ?: "Belum ada swing",
                                            color = Color.White, fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2032)),
                            shape = RoundedCornerShape(20.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Blue.copy(alpha = .45f))) {
                            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AccountTree, null, tint = Blue)
                                    Spacer(Modifier.width(8.dp))
                                    Text("STRUKTUR UTAMA • 1H", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = .8.sp)
                                }
                                Text(trendLabel(results["1h"]?.trend), color = trendColor(results["1h"]?.trend), fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                                Text(results["1h"]?.summary ?: "Menunggu struktur candle terkonfirmasi",
                                    color = Color.White, fontSize = 13.sp)
                                Divider(color = Color(0xFF254057))
                                Text("Interpretasi multi-timeframe", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(when {
                                    results.isEmpty() -> "Data belum tersedia. Perbarui data untuk membaca struktur."
                                    else -> "1H menentukan bias utama • 15M memberi konteks • 5M menunjukkan pergerakan jangka pendek."
                                }, color = Muted, fontSize = 12.sp)
                                PredictionSummary(candles, results)
                            }
                        }
                    }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("PRICE STRUCTURE", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Text("BTC/USDT • 5 MINUTES", color = Muted, fontSize = 10.sp)
                            }
                            TextButton(onClick = { timeframe = "5m"; selected = "Chart" }) { Text("BUKA CHART  →", color = Blue, fontSize = 11.sp) }
                        }
                    }
                    item { CandleChart(candles["5m"].orEmpty(), results["5m"]) }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { selected = "Detail Struktur" }, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.AccountTree, null); Spacer(Modifier.width(6.dp)); Text("Detail Struktur") }
                            OutlinedButton(onClick = { selected = "Level Kunci" }, modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp)) { Icon(Icons.Default.Layers, null); Spacer(Modifier.width(6.dp)); Text("Level Kunci") }
                        }
                    }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.fillMaxWidth().clickable { selected = "Notifikasi" }.padding(15.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.NotificationsActive, null, tint = Amber)
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Pusat Notifikasi", color = Color.White, fontWeight = FontWeight.Bold)
                                    Text("Lihat ringkasan kondisi struktur", color = Muted, fontSize = 11.sp)
                                }
                                Icon(Icons.Default.ChevronRight, null, tint = Muted)
                            }
                        }
                    }
                }
                "Chart" -> {
                    item {
                        Text("CHART ANALYZER", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Candlestick OHLC dan swing terkonfirmasi", color = Muted, fontSize = 12.sp)
                    }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("5m", "15m", "1h").forEach { tf ->
                            FilterChip(selected = timeframe == tf, onClick = { timeframe = tf },
                                label = { Text(tf.uppercase(), fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Color(0xFF123B54), selectedLabelColor = Blue))
                        }
                    } }
                    item { CandleChart(candles[timeframe].orEmpty(), results[timeframe], tall = true) }
                    item { LegendRow() }
                    item { TrendCard(results[timeframe]) }
                    item { Text("Swing ditandai setelah pivot memiliki dua candle konfirmasi di sebelah kanan.", color = Muted, fontSize = 12.sp) }
                    item { Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)) { Text(if (loading) "Memperbarui…" else "PERBARUI CANDLE") } }
                    if (error != null) item { ErrorCard(error!!) }
                }
                "Analisis" -> {
                    item {
                        Text("ANALISIS STRUKTUR", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Pembacaan 1H • 15M • 5M", color = Muted, fontSize = 12.sp)
                    }
                    if (notificationDetails != null) {
                        item {
                            val detailLines = notificationDetails.orEmpty().lines()
                            val detectedLine = detailLines.firstOrNull().orEmpty()
                            val filterLine = detailLines.firstOrNull { it.startsWith("Filter:") }.orEmpty()
                            val signalLines = detailLines.filter { it.contains(":") && (it.contains("BULLISH") || it.contains("BEARISH")) }
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF062B27)),
                                shape = RoundedCornerShape(20.dp),
                                border = androidx.compose.foundation.BorderStroke(1.5.dp, Green.copy(alpha = .9f))
                            ) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.NotificationsActive, null, tint = Green, modifier = Modifier.size(24.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("SINYAL SPOT DARI NOTIFIKASI", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                                    }
                                    Text(detectedLine, color = Muted, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    if (notificationTime > 0L) {
                                        val ageMinutes = ((System.currentTimeMillis() - notificationTime) / 60_000L).coerceAtLeast(0L)
                                        val ageLabel = when {
                                            ageMinutes < 60L -> "$ageMinutes menit"
                                            ageMinutes < 24L * 60L -> "${ageMinutes / 60L} jam ${ageMinutes % 60L} menit"
                                            else -> "${ageMinutes / (24L * 60L)} hari ${(ageMinutes % (24L * 60L)) / 60L} jam"
                                        }
                                        val stale = ageMinutes >= 60L
                                        Text(
                                            if (stale) "STATUS: SINYAL LAMA • umur $ageLabel" else "STATUS: SINYAL TERBARU • umur $ageLabel",
                                            color = if (stale) Amber else Green,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 13.sp
                                        )
                                    }
                                    signalLines.forEach { line ->
                                        val bullish = line.contains("BULLISH")
                                        val directionColor = if (bullish) Green else Red
                                        val symbol = line.substringBefore(":").trim()
                                        val score = Regex("skor\\s+(\\d+)").find(line)?.groupValues?.getOrNull(1) ?: "—"
                                        val signalPrice = Regex("harga\\s+([0-9.,]+)").find(line)?.groupValues?.getOrNull(1) ?: "—"
                                        val volume = Regex("volume 24J\\s+([0-9.,]+)").find(line)?.groupValues?.getOrNull(1) ?: "—"
                                        val srParts = detailLines.firstOrNull { it.startsWith("SR|$symbol|") }?.split("|").orEmpty()
                                        val supportValues = (2..4).map { srParts.getOrNull(it) ?: "—" }
                                        val resistanceValues = (5..7).map { srParts.getOrNull(it) ?: "—" }
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Card(
                                                colors = CardDefaults.cardColors(containerColor = Color(0xFF06231F)),
                                                shape = RoundedCornerShape(14.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.5.dp, directionColor)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.Center
                                                ) {
                                                    Icon(if (bullish) Icons.Default.TrendingUp else Icons.Default.TrendingDown, null,
                                                        tint = directionColor, modifier = Modifier.size(42.dp))
                                                    Spacer(Modifier.width(12.dp))
                                                    Text(
                                                        if (bullish) "UP / BULLISH" else "DOWN / BEARISH",
                                                        color = directionColor, fontWeight = FontWeight.Black, fontSize = 28.sp
                                                    )
                                                }
                                            }
                                            Text(symbol, color = Color.White, fontWeight = FontWeight.Black, fontSize = 25.sp)
                                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.fillMaxWidth()) {
                                                listOf(Triple("SKOR", score, true), Triple("HARGA", signalPrice, false), Triple("VOLUME 24J", volume, false)).forEach { metric ->
                                                    Card(
                                                        modifier = Modifier.weight(if (metric.first == "SKOR") 0.8f else 1.2f),
                                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF092721)),
                                                        shape = RoundedCornerShape(11.dp),
                                                        border = androidx.compose.foundation.BorderStroke(1.dp, directionColor.copy(alpha = .55f))
                                                    ) {
                                                        Column(
                                                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 5.dp),
                                                            horizontalAlignment = Alignment.CenterHorizontally,
                                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                                        ) {
                                                            Text(metric.first, color = Muted, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                                            Text(metric.second, color = if (metric.third) directionColor else Color.White,
                                                                fontWeight = FontWeight.Black, fontSize = if (metric.third) 27.sp else 15.sp,
                                                                maxLines = 2)
                                                            if (metric.first == "HARGA") Text("USDT", color = Muted, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                                            if (metric.first == "VOLUME 24J") Text("USDT", color = Muted, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                                                        }
                                                    }
                                                }
                                            }
                                            Card(
                                                modifier = Modifier.fillMaxWidth(),
                                                colors = CardDefaults.cardColors(containerColor = Color(0xFF062B27)),
                                                shape = RoundedCornerShape(12.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, Green.copy(alpha = .55f))
                                            ) {
                                                Column(
                                                    Modifier.fillMaxWidth().padding(10.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Icon(Icons.Default.BarChart, null, tint = Green, modifier = Modifier.size(20.dp))
                                                        Spacer(Modifier.width(7.dp))
                                                        Text("SUPPORT & RESISTANCE", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                                                    }
                                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                            Text("SUPPORT (S)", color = Green, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                                            repeat(3) { idx ->
                                                                Row(
                                                                    Modifier.fillMaxWidth().background(Color(0xFF063B2D), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 7.dp),
                                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Text("S${idx + 1}", color = Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                                    Text(supportValues[idx], color = Green, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, maxLines = 1)
                                                                }
                                                            }
                                                        }
                                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                                            Text("RESISTANCE (R)", color = Red, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                                            repeat(3) { idx ->
                                                                Row(
                                                                    Modifier.fillMaxWidth().background(Color(0xFF3A2028), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 7.dp),
                                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Text("R${idx + 1}", color = Muted, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                                    Text(resistanceValues[idx], color = Red, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, maxLines = 1)
                                                                }
                                                            }
                                                        }
                                                    }
                                                    Text("Level dihitung dari swing terkonfirmasi timeframe 15M. — berarti level belum tersedia.", color = Muted, fontSize = 10.sp)
                                                }
                                            }
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Icon(Icons.Default.FilterAlt, null, tint = Muted, modifier = Modifier.size(22.dp))
                                        Text(filterLine.removePrefix("Filter:").trim(), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                        Card(
                                            modifier = Modifier.weight(1f),
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFF082F25)),
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Green)
                                        ) {
                                            Row(Modifier.fillMaxWidth().padding(9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.TrendingUp, null, tint = Green, modifier = Modifier.size(22.dp))
                                                Spacer(Modifier.width(5.dp))
                                                Text("UP / BULLISH", color = Green, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                            }
                                        }
                                        Card(
                                            modifier = Modifier.weight(1f),
                                            colors = CardDefaults.cardColors(containerColor = Color(0xFF30191F)),
                                            shape = RoundedCornerShape(10.dp),
                                            border = androidx.compose.foundation.BorderStroke(1.dp, Red)
                                        ) {
                                            Row(Modifier.fillMaxWidth().padding(9.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.TrendingDown, null, tint = Red, modifier = Modifier.size(22.dp))
                                                Spacer(Modifier.width(5.dp))
                                                Text("DOWN / BEARISH", color = Red, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                            }
                                        }
                                    }
                                    Divider(color = Green.copy(alpha = .45f))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Info, null, tint = Muted, modifier = Modifier.size(22.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Analisis saja • bukan perintah transaksi.", color = Muted, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    }
                                    Divider(color = Green.copy(alpha = .35f))
                                    Text("Sinyal disimpan lokal dan tetap tersedia di sini sampai notifikasi sinyal berikutnya menggantikannya.", color = Muted, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    item { InterpretationCard(results) }
                    item { PredictionSummary(candles, results) }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF20374B))) {
                            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("USDT FUTURES OPPORTUNITIES • TOP 15", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                                Text("Memeringkat koin USDT Futures berdasarkan likuiditas, tren 1H/15M, dan momentum 5M. Data harga/candle dari Binance Spot. Analisis saja; tidak menempatkan order.", color = Muted, fontSize = 11.sp)
                                Button(onClick = {
                                    scope.launch {
                                        scanLoading = true
                                        scanCompleted = false
                                        scanError = null
                                        scannedCoins = emptyList()
                                        val scanner = CoinScanner(repo, context)
                                         try { scannedCoins = scanner.scan(15) }
                                        catch (e: Exception) { scanError = e.message ?: "Scanner gagal mengambil data" }
                                        finally { scanDiagnostics = scanner.lastDiagnostics; signalHistory = SignalJournal.read(context); scanLoading = false; scanCompleted = true; scanCompletedAt = System.currentTimeMillis() }
                                    }
                                }, enabled = !scanLoading, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                                    Text(if (scanLoading) "MEMINDAI MARKET…" else "PINDAI PELUANG FUTURES")
                                }
                                if (scanLoading) Text("Memeriksa likuiditas dan candle kandidat. Proses pertama dapat memerlukan waktu.", color = Muted, fontSize = 11.sp)
                                scanError?.let { Text(it, color = Red, fontSize = 12.sp) }
                                scanDiagnostics?.let { d ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B202B)),
                                        shape = RoundedCornerShape(10.dp),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF294457))
                                    ) {
                                        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                            Text("DIAGNOSTIK PEMINDAIAN", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                            Text(scanCompletedAt?.let { "SCAN TERAKHIR " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(it)) } ?: "BELUM ADA HASIL SCAN DI SESI INI", color = Green, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            Text("Pasangan Spot: ${d.spotPairs} • Daftar Futures: ${d.futuresWhitelist}", color = Muted, fontSize = 11.sp)
                                            Text("Lolos volume/spread/perubahan: ${d.liquidCandidates}", color = Muted, fontSize = 11.sp)
                                            Text("Candle berhasil diperiksa: ${d.candlesChecked} • Tren searah: ${d.trendConfirmed}", color = Muted, fontSize = 11.sp)
                                            Text("Tren 1H — naik: ${d.oneHourBullish}, turun: ${d.oneHourBearish}, sideways: ${d.oneHourSideways}, belum terkonfirmasi: ${d.oneHourUnconfirmed}", color = Muted, fontSize = 11.sp)
                                            Text("Tren 15M — naik: ${d.fifteenMinuteBullish}, turun: ${d.fifteenMinuteBearish}, sideways: ${d.fifteenMinuteSideways}, belum terkonfirmasi: ${d.fifteenMinuteUnconfirmed}", color = Muted, fontSize = 11.sp)
                                            Text("RINCIAN TREN KANDIDAT", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            d.candidateTrendDetails.forEach { detail -> Text(detail, color = Muted, fontSize = 10.sp) }
                                            Text("Tren bullish: ${d.bullishTrendPairs} • bearish: ${d.bearishTrendPairs} • campuran/belum jelas: ${d.unalignedTrendPairs}", color = Muted, fontSize = 11.sp)
                                            Text("Momentum 5M lolos: ${d.momentumConfirmed} • ditolak: ${d.momentumRejected}", color = Muted, fontSize = 11.sp)
                                            Text("Ditolak: level lawan berada di rentang entry: ${d.nearbyLevelRejected}", color = Muted, fontSize = 11.sp)
                                            Text("Error data candle: ${d.dataErrors}", color = if (d.dataErrors > 0) Amber else Muted, fontSize = 11.sp)
                                            d.lastError?.let { Text("Error: $it", color = Red, fontSize = 11.sp) }
                                        }
                                    }
                                }
                                if (scannedCoins.isEmpty() && !scanLoading && scanError == null) Text(if (scanCompleted) "Pemindaian Futures selesai, tetapi belum ada koin yang memenuhi semua konfirmasi 1H • 15M • 5M. Coba lagi nanti." else "Tekan Pindai untuk mencari kandidat terbaru.", color = if (scanCompleted) Color(0xFFFFC857) else Muted, fontSize = 12.sp)
                                scannedCoins.forEachIndexed { index, coin ->
                                    Divider(color = Color(0xFF20374B))
                                    val isLong = coin.trend1h == Trend.BULLISH && coin.trend15m == Trend.BULLISH
                                    val direction = if (isLong) "LONG" else "SHORT"
                                    val pad = coin.price * 0.00025
                                    val entryLow = coin.price - pad
                                    val entryHigh = coin.price + pad
                                    val stop = if (isLong) (coin.supportLevels.firstOrNull()?.takeIf { it < entryLow } ?: coin.price * 0.996)
                                        else (coin.resistanceLevels.firstOrNull()?.takeIf { it > entryHigh } ?: coin.price * 1.004)
                                    val target1 = if (isLong) (coin.resistanceLevels.firstOrNull { it > entryHigh } ?: coin.price * 1.005)
                                        else (coin.supportLevels.firstOrNull() ?: coin.price * 0.995)
                                    val target2 = if (isLong) maxOf(coin.price * 1.008, target1 * 1.001) else minOf(coin.price * 0.992, target1 * 0.999)
                                    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text("${index + 1}. ${coin.symbol.removeSuffix("USDT")}/USDT  •  $direction", color = if (isLong) Green else Red, fontWeight = FontWeight.Bold)
                                                Text("1H ${trendLabel(coin.trend1h)} • 15M ${trendLabel(coin.trend15m)} • 5M ${trendLabel(coin.trend5m)}", color = Muted, fontSize = 10.sp)
                                                Text(coin.reason, color = Muted, fontSize = 10.sp)
                                                Text("Volume 24J: $" + "%,.0f".format(Locale.US, coin.quoteVolume), color = Muted, fontSize = 10.sp)
                                            }
                                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                                Text("$" + SignalJournal.fmt(coin.price), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                                Text("Skor ${coin.score}", color = if (coin.score >= 65) Green else Amber, fontWeight = FontWeight.ExtraBold)
                                            }
                                        }
                                        Text("Area entry: ${SignalJournal.fmt(entryLow)} – ${SignalJournal.fmt(entryHigh)}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                        Text("SL: ${SignalJournal.fmt(stop)}  •  TP1: ${SignalJournal.fmt(target1)}  •  TP2: ${SignalJournal.fmt(target2)}", color = Muted, fontSize = 10.sp)
                                        Text("SNR • S1: ${coin.supportLevels.getOrNull(0)?.let { SignalJournal.fmt(it) } ?: "—"}  S2: ${coin.supportLevels.getOrNull(1)?.let { SignalJournal.fmt(it) } ?: "—"}  S3: ${coin.supportLevels.getOrNull(2)?.let { SignalJournal.fmt(it) } ?: "—"}", color = Color(0xFF80C8FF), fontSize = 10.sp)
                                        Text("R1: ${coin.resistanceLevels.getOrNull(0)?.let { SignalJournal.fmt(it) } ?: "—"}  R2: ${coin.resistanceLevels.getOrNull(1)?.let { SignalJournal.fmt(it) } ?: "—"}  R3: ${coin.resistanceLevels.getOrNull(2)?.let { SignalJournal.fmt(it) } ?: "—"}", color = Color(0xFFFFC77A), fontSize = 10.sp)
                                    }
                                }
                                if (scannedCoins.isNotEmpty()) Text("Skor adalah peringkat heuristik, bukan probabilitas menang. Hasil bukan saran transaksi.", color = Muted, fontSize = 10.sp)
                            }
                        }
                    }
                    listOf("1h", "15m", "5m").forEach { tf ->
                        item { Section("TIMEFRAME ${tf.uppercase()}") }
                        item { TrendCard(results[tf]) }
                        item { DetailCard(results[tf]) }
                    }
                }
                "Riwayat" -> {
                    item {
                        Text("RIWAYAT SINYAL SEMUA KOIN", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Maksimal 500 sinyal • tersimpan lokal • hasil berdasarkan perubahan harga", color = Muted, fontSize = 12.sp)
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    historyRefreshLoading = true
                                    historyRefreshError = null
                                    try {
                                        val currentRecords = SignalJournal.read(context)
                                        val symbols = currentRecords.map { it.symbol }.toSet()
                                        if (symbols.isNotEmpty()) {
                                            val latestPrices = repo.latestPrices(symbols)
                                            SignalJournal.updatePrices(context, latestPrices)
                                        }
                                        // Resolve due 15/30/60-minute horizons from historical 1m Spot candles,
                                        // never from the current live ticker. Keep individual failures retryable.
                                        val dueEvaluations = SignalJournal.pendingEvaluations(context)
                                        for (pending in dueEvaluations) {
                                            runCatching {
                                                val historical = repo.historicalSpotClose(pending.symbol, pending.targetTime)
                                                if (historical != null) {
                                                    SignalJournal.updateHistoricalEvaluation(
                                                        context,
                                                        pending.signalId,
                                                        pending.horizonMinutes,
                                                        historical.first,
                                                        historical.second
                                                    )
                                                }
                                            }
                                        }
                                        // Evaluate TP/SL separately from directional 15/30/60m results.
                                        // Only signals with a verified entry are scored as hypothetical trades.
                                        val dueTradeOutcomes = SignalJournal.pendingTradeOutcomes(context)
                                        for (record in dueTradeOutcomes) {
                                            runCatching {
                                                val bars = repo.historicalSpotBars(record.symbol, record.time, record.time + 60 * 60_000L)
                                                val outcome = SignalJournal.evaluateTradeOutcome(record, bars)
                                                SignalJournal.updateTradeOutcome(context, record.id, outcome)
                                            }
                                        }
                                        signalHistory = SignalJournal.read(context)
                                    } catch (e: Exception) {
                                        historyRefreshError = e.message ?: "Gagal memperbarui evaluasi riwayat"
                                        signalHistory = SignalJournal.read(context)
                                    } finally {
                                        historyRefreshLoading = false
                                    }
                                }
                            },
                            enabled = !historyRefreshLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (historyRefreshLoading) "MEMPERBARUI HARGA & EVALUASI…" else "SEGARKAN RIWAYAT")
                        }
                        historyRefreshError?.let { Text("Riwayat belum berhasil diperbarui: $it", color = Red, fontSize = 11.sp) }
                        OutlinedButton(
                            onClick = { exportLauncher.launch("btc-structure-analysis.zip") },
                            enabled = !exportLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (exportLoading) "MENYIAPKAN EXPORT…" else "EXPORT RIWAYAT (ZIP CSV + JSON)")
                        }
                        exportMessage?.let { Text(it, color = Green, fontSize = 11.sp) }
                        exportError?.let { Text("Export gagal: $it", color = Red, fontSize = 11.sp) }
                    }
                    if (signalHistory.isEmpty()) item { EmptyCard("Belum ada sinyal tercatat. Jalankan pemindaian scanner untuk mulai mengisi riwayat.") }
                    items(signalHistory, key = { it.id }) { run ->
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF203044))) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(SimpleDateFormat("dd MMM yyyy • HH:mm:ss", Locale("id","ID")).format(Date(run.time)), color = Muted, fontSize = 11.sp)
                                Text("${run.symbol}  •  ${run.direction}", color = if (run.direction == "LONG") Green else Red, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                                Text("Harga sinyal: ${SignalJournal.fmt(run.price)}  •  Skor ${run.score}", color = Color.White, fontSize = 12.sp)
                                Text("Area entry: ${SignalJournal.fmt(run.entryLow)} – ${SignalJournal.fmt(run.entryHigh)}", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                Text("Status entry: ${run.entryStatus}", color = if (run.entryStatus == "TERLEWAT") Red else if (run.entryStatus == "MASIH DALAM RANGE") Green else Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text("SL ${SignalJournal.fmt(run.stopLoss)} • TP1 ${SignalJournal.fmt(run.takeProfit1)} • TP2 ${SignalJournal.fmt(run.takeProfit2)}", color = Muted, fontSize = 11.sp)
                                Text("S1–S3: ${run.supports.joinToString(" / ") { SignalJournal.fmt(it) }.ifBlank { "—" }}", color = Color(0xFF80C8FF), fontSize = 10.sp)
                                Text("R1–R3: ${run.resistances.joinToString(" / ") { SignalJournal.fmt(it) }.ifBlank { "—" }}", color = Color(0xFFFFC77A), fontSize = 10.sp)
                                Divider(color = Color(0xFF20374B))
                                Text("15 menit • arah harga: ${run.result15m} ${run.price15m?.let { "(${SignalJournal.fmt(it)})" } ?: ""}", color = if (run.result15m == "ARAH_POSITIF") Green else if (run.result15m == "ARAH_NEGATIF") Red else Muted, fontSize = 11.sp)
                                Text("30 menit • arah harga: ${run.result30m} ${run.price30m?.let { "(${SignalJournal.fmt(it)})" } ?: ""}", color = if (run.result30m == "ARAH_POSITIF") Green else if (run.result30m == "ARAH_NEGATIF") Red else Muted, fontSize = 11.sp)
                                Text("1 jam • arah harga: ${run.result60m} ${run.price60m?.let { "(${SignalJournal.fmt(it)})" } ?: ""}", color = if (run.result60m == "ARAH_POSITIF") Green else if (run.result60m == "ARAH_NEGATIF") Red else Muted, fontSize = 11.sp)
                                Text("Hasil TP/SL historis: ${run.tradeOutcome}", color = when (run.tradeOutcome) { "TP1_TERCAPAI", "TP2_TERCAPAI" -> Green; "SL_TERSENTUH", "ENTRY_TERLEWAT" -> Red; else -> Muted }, fontSize = 11.sp)
                            }
                        }
                    }
                    item { Text("Evaluasi 15/30/60 menit menunjukkan arah harga, bukan hasil TP/SL atau bukti profit. Tidak termasuk biaya dan slippage.", color = Muted, fontSize = 10.sp) }
                }
                "Detail Struktur" -> {
                    item {
                        Text("DETAIL STRUKTUR", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                        Text("BTC/USDT • struktur swing terkonfirmasi", color = Muted, fontSize = 12.sp)
                    }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("5m", "15m", "1h").forEach { tf ->
                            FilterChip(selected = timeframe == tf, onClick = { timeframe = tf }, label = { Text(tf.uppercase()) })
                        }
                    } }
                    item { TrendCard(results[timeframe]) }
                    item { CandleChart(candles[timeframe].orEmpty(), results[timeframe], tall = true) }
                    item { Section("URUTAN SWING TERKONFIRMASI") }
                    item { DetailCard(results[timeframe]) }
                    item { Section("KONTEKS MULTI-TIMEFRAME") }
                    item { InterpretationCard(results) }
                }
                "Level Kunci" -> {
                    item {
                        Text("LEVEL KUNCI", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Level berdasarkan swing yang sudah dikonfirmasi", color = Muted, fontSize = 12.sp)
                    }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("5m", "15m", "1h").forEach { tf ->
                            FilterChip(selected = timeframe == tf, onClick = { timeframe = tf }, label = { Text(tf.uppercase()) })
                        }
                    } }
                    val swings = results[timeframe]?.swings.orEmpty()
                    val highs = swings.filter { it.isHigh }.takeLast(3).reversed()
                    val lows = swings.filter { !it.isHigh }.takeLast(3).reversed()
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Text("RESISTANCE", color = Red, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                    if (highs.isEmpty()) Text("Belum tersedia", color = Muted, fontSize = 11.sp)
                                    highs.forEachIndexed { i, s ->
                                        Text("R${i+1}  $" + "%,.2f".format(Locale.US, s.price), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                    Text("SUPPORT", color = Green, fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
                                    if (lows.isEmpty()) Text("Belum tersedia", color = Muted, fontSize = 11.sp)
                                    lows.forEachIndexed { i, s ->
                                        Text("S${i+1}  $" + "%,.2f".format(Locale.US, s.price), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                    item { CandleChart(candles[timeframe].orEmpty(), results[timeframe], tall = true) }
                    item { Text("Level ini berasal dari swing historis, bukan jaminan harga akan berbalik.", color = Muted, fontSize = 12.sp) }
                }
                "Notifikasi" -> {
                    item {
                        Text("NOTIFICATION CENTER", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Ringkasan struktur pasar terbaru", color = Muted, fontSize = 12.sp)
                    }
                    item { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.NotificationsActive, null, tint = Amber, modifier = Modifier.size(26.dp))
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text("Pusat notifikasi struktur", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text(if (autoRefresh) "Auto scan Spot aktif • target setiap 10 menit" else "Auto scan Spot tidak aktif", color = if (autoRefresh) Green else Amber, fontSize = 11.sp)
                                }
                            }
                            Divider(color = Color(0xFF24364A))
                            listOf("1h", "15m", "5m").forEach { tf ->
                                val r = results[tf]
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(40.dp).background(Color(0xFF17283B), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                                        Icon(when(r?.trend) { Trend.BULLISH -> Icons.Default.TrendingUp; Trend.BEARISH -> Icons.Default.TrendingDown; else -> Icons.Default.Timeline },
                                            null, tint = trendColor(r?.trend))
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("${tf.uppercase()} • ${trendLabel(r?.trend)}", color = Color.White, fontWeight = FontWeight.Bold)
                                        Text(r?.summary ?: "Belum ada data", color = Muted, fontSize = 11.sp)
                                    }
                                }
                                Divider(color = Color(0xFF24364A))
                            }
                            Text(if (autoRefresh) "Layanan aktif dengan notifikasi permanen. Android tetap dapat membatasi layanan karena baterai atau kebijakan sistem." else "Aktifkan auto scan di Pengaturan. Notifikasi dikirim saat ada sinyal baru; tidak ada order otomatis.", color = Muted, fontSize = 11.sp)
                            Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)) { Text(if (loading) "MEMPERBARUI…" else "PERBARUI STRUKTUR") }
                        }
                    } }
                }
                else -> {
                    item {
                        Text("PENGATURAN", color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                        Text("Preferensi aplikasi dan koneksi data", color = Muted, fontSize = 12.sp)
                    }
                    item { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CloudDone, null, tint = Green, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Sumber data", color = Muted, fontSize = 11.sp)
                                    Text("Binance Spot • pasangan USDT", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                            Divider(color = Color(0xFF24364A))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, null, tint = Blue, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Pembaruan data", color = Muted, fontSize = 11.sp)
                                    Text("Scan manual + auto scan Spot setiap 10 menit saat layanan aktif", color = Color.White, fontWeight = FontWeight.SemiBold)
                                }
                            }
                            Divider(color = Color(0xFF24364A))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.BatteryChargingFull, null, tint = if (autoRefresh) Green else Amber, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Pemantauan latar belakang", color = Muted, fontSize = 11.sp)
                                    Text(if (autoRefresh) "Aktif • cek berkala" else "Tidak aktif", color = if (autoRefresh) Green else Amber, fontWeight = FontWeight.Bold)
                                }
                                Switch(checked = autoRefresh, onCheckedChange = { enabled ->
                                    autoRefresh = enabled
                                    context.getSharedPreferences("btc_structure", Context.MODE_PRIVATE).edit().putBoolean("background_monitoring", enabled).apply()
                                    if (enabled) {
                                        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && context is Activity) {
                                            ActivityCompat.requestPermissions(context, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
                                        }
                                        ContextCompat.startForegroundService(context, android.content.Intent(context, FuturesAutoScanService::class.java))
                                    } else {
                                        context.stopService(android.content.Intent(context, FuturesAutoScanService::class.java))
                                    }
                                })
                            }
                            Divider(color = Color(0xFF24364A))
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("METODE STRUKTUR", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Text("Swing pivot 2 candle kiri + 2 candle kanan. Tidak menggunakan EMA, RSI, atau MACD sebagai penentu arah.", color = Color.White, fontSize = 12.sp)
                            }
                            Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)) { Text(if (loading) "MENGUJI…" else "UJI KONEKSI / PERBARUI DATA") }
                            if (error != null) ErrorCard(error!!)
                            Text("BTC STRUCTURE ANALYZER • V0.3 • MODE PENGUJIAN", color = Muted, fontSize = 10.sp)
                        }
                    } }
                }
            }
        }
    }
}

@Composable private fun PredictionSummary(candles: Map<String, List<Candle>>, results: Map<String, StructureResult>) {
    val forecast = remember(candles, results) { StructureAnalyzer.forecast(candles["5m"].orEmpty(), results["5m"], results["15m"], results["1h"]) }
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF101E2D)), shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Amber.copy(alpha = .45f))) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoGraph, null, tint = Amber)
                Spacer(Modifier.width(8.dp))
                Text("PROYEKSI 3 CANDLE BERIKUTNYA • 5M", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text(forecast.summary, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                forecast.steps.forEachIndexed { index, step ->
                    Card(Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = Panel2), shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("CANDLE ${index + 1}", color = Muted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Text(step.label, color = if (step.direction == "NAIK") Green else if (step.direction == "TURUN") Red else Amber, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
                            Text("${step.confidence}%", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                        }
                    }
                }
            }
            Text("Estimasi heuristik struktur + momentum, bukan model AI terlatih. Angka belum tervalidasi backtest dan bukan probabilitas akurat.", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable private fun Section(text: String) { Text(text, color = Muted, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) }

@Composable private fun PriceCard(price: Double?, loading: Boolean, error: String?, lastRefresh: Long?) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("HARGA BTC/USDT", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(price?.let { "$" + "%,.2f".format(Locale.US, it) } ?: if (loading) "Memuat data…" else "Belum tersedia",
                fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (error == null) Icons.Default.CloudDone else Icons.Default.CloudOff, null,
                    tint = if (error == null) Green else Red, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (error != null) "Koneksi bermasalah" else if (loading) "Memperbarui data…" else "Data publik Binance",
                    color = if (error != null) Red else Muted, fontSize = 12.sp)
            }
            lastRefresh?.let { Text("Update terakhir: " + SimpleDateFormat("HH:mm:ss", Locale("id","ID")).format(Date(it)), color = Muted, fontSize = 10.sp) }
        }
    }
}
@Composable private fun ErrorCard(error: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF351A25)), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = Red); Spacer(Modifier.width(8.dp))
            Text("$error\nPeriksa koneksi internet lalu tekan refresh.", color = Color.White, fontSize = 12.sp)
        }
    }
}
@Composable private fun TrendCard(result: StructureResult?) {
    val color = trendColor(result?.trend)
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(when(result?.trend) { Trend.BULLISH -> Icons.Default.TrendingUp; Trend.BEARISH -> Icons.Default.TrendingDown; else -> Icons.Default.Timeline },
                null, tint = color, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(trendLabel(result?.trend), color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(result?.summary ?: "Menunggu data candle", color = Muted, fontSize = 12.sp)
            }
        }
    }
}
@Composable private fun TrendMiniCard(tf: String, result: StructureResult?) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(tf.uppercase(), color = Muted, fontSize = 11.sp)
            Text(trendLabel(result?.trend), color = trendColor(result?.trend), fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(result?.swings?.takeLast(2)?.joinToString(" + ") { it.label } ?: "—", color = Color.White, fontSize = 11.sp)
        }
    }
}
@Composable private fun InterpretationCard(results: Map<String, StructureResult>) {
    val a = results["5m"]?.trend; val b = results["15m"]?.trend; val c = results["1h"]?.trend
    val message = when {
        a == null || b == null || c == null -> "Menunggu data candle dari sumber publik. Tanpa data, sistem tidak mengeluarkan bias."
        c == Trend.UNCONFIRMED || c == Trend.SIDEWAYS -> "Bias utama 1H belum terkonfirmasi. Tunggu swing yang lebih jelas; arah tidak dipaksakan."
        a != c && b == c -> "5m berlawanan dengan bias 1H dan 15m. Ini mungkin koreksi jangka pendek; reversal belum terkonfirmasi."
        a == b && b == c -> "Ketiga timeframe searah. Struktur sedang selaras, tetapi ini bukan jaminan pergerakan berikutnya."
        else -> "Struktur antar-timeframe belum selaras. Periksa swing terakhir dan tunggu candle konfirmasi."
    }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = Color.White, fontSize = 13.sp)
            Text("Bias 1H: ${trendLabel(c)}  •  15m: ${trendLabel(b)}  •  5m: ${trendLabel(a)}", color = Muted, fontSize = 11.sp)
            Text("Struktur harga saja. Bukan saran transaksi atau jaminan profit.", color = Muted, fontSize = 11.sp)
        }
    }
}
@Composable private fun DetailCard(result: StructureResult?) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(result?.summary ?: "Belum ada data", color = Color.White, fontWeight = FontWeight.SemiBold)
            val swings = result?.swings.orEmpty()
            if (swings.isEmpty()) Text("Belum ada swing terkonfirmasi.", color = Muted, fontSize = 12.sp)
            swings.takeLast(8).forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.label, color = if (s.label == "HH" || s.label == "HL") Green else if (s.label == "LH" || s.label == "LL") Red else Amber,
                        fontWeight = FontWeight.Bold, modifier = Modifier.width(38.dp))
                    Text("$" + "%,.2f".format(Locale.US, s.price), color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    Text(SimpleDateFormat("HH:mm", Locale.US).format(Date(s.time)), color = Muted, fontSize = 11.sp)
                }
            }
        }
    }
}
@Composable private fun CandleChart(candles: List<Candle>, result: StructureResult?, tall: Boolean = false) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CandlestickChart, null, tint = Blue)
                Spacer(Modifier.width(8.dp))
                Text("OHLC  •  Swing terkonfirmasi", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.height(8.dp))
            Canvas(Modifier.fillMaxWidth().height(if (tall) 390.dp else 250.dp)) {
                val grid = Color(0xFF24364A)
                for (i in 1..4) { val y = size.height * i / 5f; drawLine(grid, Offset(0f,y), Offset(size.width,y), 1f) }
                val data = candles.takeLast(if (tall) 60 else 45)
                if (data.isEmpty()) {
                    drawLine(Muted, Offset(0f,size.height/2), Offset(size.width,size.height/2), 1f)
                } else {
                    val lo = data.minOf { it.low }; val hi = data.maxOf { it.high }
                    val range = (hi-lo).takeIf { it > 0 } ?: 1.0
                    fun y(v: Double) = size.height - (((v-lo)/range).toFloat() * (size.height*0.82f) + size.height*0.09f)
                    val slot = size.width / data.size
                    data.forEachIndexed { i, c ->
                        val x = slot * (i + 0.5f)
                        val up = c.close >= c.open
                        val col = if (up) Green else Red
                        drawLine(col, Offset(x,y(c.high)), Offset(x,y(c.low)), 1.5f)
                        val top = minOf(y(c.open), y(c.close)); val bottom = maxOf(y(c.open), y(c.close))
                        val bodyH = (bottom-top).coerceAtLeast(2f)
                        drawRect(col, topLeft = Offset(x-slot*0.28f, top), size = androidx.compose.ui.geometry.Size(slot*0.56f, bodyH))
                    }
                    val recent = result?.swings.orEmpty()
                    recent.forEach { swing ->
                        val candle = candles.indexOfFirst { it.openTime == swing.time }
                        val visibleStart = candles.size - data.size
                        val local = candle - visibleStart
                        if (local in data.indices) {
                            val c = data[local]; val x = slot * (local + .5f)
                            val yy = y(swing.price)
                            val swingColor = if (swing.isHigh) Red else Green
                            drawCircle(swingColor, 4.5f, Offset(x, yy))
                            val labelY = (yy + if (swing.isHigh) -9f else 18f).coerceIn(12f, size.height - 4f)
                            drawContext.canvas.nativeCanvas.drawText(
                                swing.label, x, labelY,
                                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                    color = swingColor.toArgb()
                                    textSize = 11f
                                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                                    textAlign = android.graphics.Paint.Align.CENTER
                                }
                            )
                        }
                    }
                }
            }
            if (candles.isEmpty()) Text("Grafik menunggu data jaringan.", color = Muted, fontSize = 11.sp)
            else Text("Candle terakhir: ${SimpleDateFormat("HH:mm", Locale.US).format(Date(candles.last().openTime))} • ${candles.size} candle", color = Muted, fontSize = 10.sp)
            result?.swings?.takeLast(8)?.let { swings ->
                if (swings.isNotEmpty()) Text(swings.joinToString("   ") { "${it.label} $" + "%.0f".format(Locale.US, it.price) }, color = Muted, fontSize = 10.sp)
            }
        }
    }
}
@Composable private fun LegendRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("● HH / HL", color = Green, fontSize = 11.sp)
        Text("● LH / LL", color = Red, fontSize = 11.sp)
        Text("● candle naik", color = Green, fontSize = 11.sp)
    }
}
@Composable private fun EmptyCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) { Text(text, Modifier.padding(16.dp), color = Muted, fontSize = 13.sp) }
}
private fun trendColor(trend: Trend?) = when(trend) { Trend.BULLISH -> Green; Trend.BEARISH -> Red; Trend.SIDEWAYS -> Amber; else -> Muted }
private fun trendLabel(trend: Trend?): String = when(trend) {
    Trend.BULLISH -> "BULLISH ↑"; Trend.BEARISH -> "BEARISH ↓"; Trend.SIDEWAYS -> "CAMPURAN"; else -> "MENUNGGU"
}
