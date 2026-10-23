package com.btcstructureanalyzer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF07111F)
private val Panel = Color(0xFF101E2D)
private val Panel2 = Color(0xFF142538)
private val Green = Color(0xFF00D68F)
private val Red = Color(0xFFFF5364)
private val Blue = Color(0xFF1684FF)
private val Muted = Color(0xFF9FB2C8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(
            background = Navy, surface = Panel, primary = Blue, secondary = Green
        )) { AnalyzerApp() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalyzerApp() {
    val scope = rememberCoroutineScope()
    val repo = remember { BinanceRepository() }
    var selected by remember { mutableStateOf("Home") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var price by remember { mutableStateOf<Double?>(null) }
    var results by remember { mutableStateOf<Map<String, StructureResult>>(emptyMap()) }
    var lastRefresh by remember { mutableStateOf<Long?>(null) }

    fun refresh() {
        scope.launch {
            loading = true; error = null
            try {
                val frames = listOf("5m", "15m", "1h")
                val updated = frames.associateWith { tf -> StructureAnalyzer.analyze(tf, repo.candles(tf)) }
                results = updated
                price = repo.candles("5m", 2).lastOrNull()?.close
                lastRefresh = System.currentTimeMillis()
            } catch (e: Exception) {
                error = e.message ?: "Gagal mengambil data"
            } finally { loading = false }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        containerColor = Navy,
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                listOf("Home", "Chart", "Analisis", "Riwayat", "Pengaturan").forEach { item ->
                    val icon = when(item) {
                        "Home" -> Icons.Default.Home
                        "Chart" -> Icons.Default.ShowChart
                        "Analisis" -> Icons.Default.Analytics
                        "Riwayat" -> Icons.Default.History
                        else -> Icons.Default.Settings
                    }
                    NavigationBarItem(
                        selected = selected == item, onClick = { selected = item },
                        icon = { Icon(icon, contentDescription = item) },
                        label = { Text(item, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Green, selectedTextColor = Green,
                            indicatorColor = Panel2, unselectedIconColor = Muted,
                            unselectedTextColor = Muted
                        )
                    )
                }
            }
        },
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("BTC Structure Analyzer", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Text("BTC/USDT • Binance Public Data", fontSize = 11.sp, color = Muted)
                }
            }, actions = {
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Perbarui data", tint = Green)
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White))
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).background(Navy),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Harga BTC/USDT", color = Muted, fontSize = 12.sp)
                        Text(price?.let { "$" + "%,.2f".format(Locale.US, it) } ?: "Memuat data…",
                            fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudDone, null, tint = if (error == null) Green else Red, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (error != null) "Koneksi bermasalah" else if (loading) "Memperbarui data…" else "Data publik Binance",
                                color = if (error != null) Red else Muted, fontSize = 12.sp)
                        }
                        lastRefresh?.let {
                            Text("Update: " + SimpleDateFormat("HH:mm:ss", Locale("id","ID")).format(Date(it)),
                                color = Muted, fontSize = 10.sp)
                        }
                    }
                }
            }
            if (error != null) item {
                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF351A25))) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, null, tint = Red)
                        Spacer(Modifier.width(8.dp))
                        Text("$error. Tekan refresh untuk mencoba lagi.", color = Color.White, fontSize = 12.sp)
                    }
                }
            }
            item {
                Text("ARAH UTAMA • 1H", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                TrendCard(results["1h"])
            }
            item {
                Text("RINGKASAN MULTI-TIMEFRAME", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("5m", "15m", "1h").forEach { tf ->
                        Box(Modifier.weight(1f)) { TrendMiniCard(tf, results[tf]) }
                    }
                }
            }
            item {
                Text("INTERPRETASI", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val a = results["5m"]?.trend
                        val b = results["15m"]?.trend
                        val c = results["1h"]?.trend
                        val message = when {
                            a == null || b == null || c == null -> "Menunggu hasil analisis struktur."
                            a != c && b == c -> "Timeframe 5m berlawanan dengan bias 1H. Ini bisa menjadi koreksi jangka pendek; reversal belum terkonfirmasi."
                            a == b && b == c -> "Ketiga timeframe searah. Struktur lebih selaras, tetapi ini bukan jaminan pergerakan berikutnya."
                            else -> "Struktur timeframe belum selaras. Tunggu konfirmasi; sistem tidak memaksakan arah."
                        }
                        Text(message, color = Color.White, fontSize = 13.sp)
                        Text("Analisis menggunakan struktur swing HH, HL, LH, LL. Bukan saran transaksi.",
                            color = Muted, fontSize = 11.sp)
                    }
                }
            }
            item {
                Text("GRAFIK STRUKTUR TERBARU", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                StructureChart(results["5m"])
            }
            item {
                Button(onClick = { refresh() }, modifier = Modifier.fillMaxWidth(), enabled = !loading) {
                    Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp))
                    Text(if (loading) "Memperbarui…" else "Perbarui Analisis")
                }
            }
        }
    }
}

@Composable
private fun TrendCard(result: StructureResult?) {
    val trend = result?.trend
    val color = when(trend) { Trend.BULLISH -> Green; Trend.BEARISH -> Red; else -> Color(0xFFFFC857) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                when(trend) { Trend.BULLISH -> Icons.Default.TrendingUp; Trend.BEARISH -> Icons.Default.TrendingDown; else -> Icons.Default.Timeline },
                null, tint = color, modifier = Modifier.size(36.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(trendLabel(trend), color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(result?.summary ?: "Menunggu data candle", color = Muted, fontSize = 12.sp)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Muted)
        }
    }
}

@Composable
private fun TrendMiniCard(tf: String, result: StructureResult?) {
    val color = when(result?.trend) { Trend.BULLISH -> Green; Trend.BEARISH -> Red; else -> Color(0xFFFFC857) }
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(tf.uppercase(), color = Muted, fontSize = 11.sp)
            Text(trendLabel(result?.trend), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(result?.swings?.takeLast(2)?.joinToString(" + ") { it.label } ?: "—",
                color = Color.White, fontSize = 11.sp)
        }
    }
}

@Composable
private fun StructureChart(result: StructureResult?) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CandlestickChart, null, tint = Blue)
                Spacer(Modifier.width(8.dp))
                Text("5m • Swing terkonfirmasi", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                val grid = Color(0xFF24364A)
                for (i in 1..4) {
                    val y = size.height * i / 5f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
                }
                val swings = result?.swings.orEmpty()
                if (swings.isNotEmpty()) {
                    val min = swings.minOf { it.price }
                    val max = swings.maxOf { it.price }
                    val range = (max - min).takeIf { it > 0 } ?: 1.0
                    val pts = swings.mapIndexed { i, s ->
                        Offset(
                            x = size.width * (i + 0.5f) / swings.size,
                            y = size.height - (((s.price - min) / range).toFloat() * (size.height * 0.76f) + size.height * 0.12f)
                        )
                    }
                    for (i in 0 until pts.lastIndex) drawLine(Blue, pts[i], pts[i + 1], 2.5f, cap = StrokeCap.Round)
                    pts.forEachIndexed { i, p -> drawCircle(if (swings[i].isHigh) Red else Green, 5f, p) }
                } else {
                    drawLine(Muted, Offset(0f, size.height/2), Offset(size.width, size.height/2), 1f)
                }
            }
            if (result?.swings.isNullOrEmpty()) Text("Belum cukup swing terkonfirmasi untuk digambar.", color = Muted, fontSize = 11.sp)
            else Text(result!!.swings.takeLast(6).joinToString("   ") { "${it.label} ${"%.1f".format(Locale.US, it.price)}" },
                color = Muted, fontSize = 10.sp)
        }
    }
}

private fun trendLabel(trend: Trend?): String = when(trend) {
    Trend.BULLISH -> "BULLISH ↑"
    Trend.BEARISH -> "BEARISH ↓"
    Trend.SIDEWAYS -> "CAMPURAN"
    else -> "MENUNGGU"
}