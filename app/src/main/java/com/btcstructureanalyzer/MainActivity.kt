package com.btcstructureanalyzer

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
private val Amber = Color(0xFFFFC857)
private val Muted = Color(0xFF9FB2C8)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = darkColorScheme(background = Navy, surface = Panel, primary = Blue, secondary = Green)) { AnalyzerApp(this) } }
    }
}

private data class SavedRun(val time: Long, val price: Double, val bias: String, val note: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalyzerApp(context: Context) {
    val scope = rememberCoroutineScope()
    val repo = remember { BinanceRepository() }
    var selected by remember { mutableStateOf("Home") }
    var timeframe by remember { mutableStateOf("5m") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var price by remember { mutableStateOf<Double?>(null) }
    var candles by remember { mutableStateOf<Map<String, List<Candle>>>(emptyMap()) }
    var results by remember { mutableStateOf<Map<String, StructureResult>>(emptyMap()) }
    var lastRefresh by remember { mutableStateOf<Long?>(null) }
    var autoRefresh by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(emptyList<SavedRun>()) }

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
    LaunchedEffect(Unit) { loadHistory(); refresh() }

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
                    NavigationBarItem(selected == item, onClick = { selected = item },
                        icon = { Icon(icon, contentDescription = item) }, label = { Text(item, fontSize = 9.sp, maxLines = 1, softWrap = false) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Green, selectedTextColor = Green,
                            indicatorColor = Panel2, unselectedIconColor = Muted, unselectedTextColor = Muted))
                }
            }
        },
        topBar = {
            TopAppBar(title = { Column {
                Text("BTC Structure Analyzer", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text("BTC/USDT • Binance Public Data", fontSize = 11.sp, color = Muted)
            } }, actions = {
                IconButton(onClick = { selected = "Notifikasi" }) {
                    Icon(Icons.Default.Notifications, contentDescription = "Notifikasi", tint = Color.White)
                }
                IconButton(onClick = { refresh() }, enabled = !loading) {
                    Icon(Icons.Default.Refresh, contentDescription = "Perbarui data", tint = Green)
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White))
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).background(Navy), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            when (selected) {
                "Home" -> {
                    item { PriceCard(price, loading, error, lastRefresh) }
                    if (error != null) item { ErrorCard(error!!) }
                    item { Section("ARAH UTAMA • 1H"); TrendCard(results["1h"]) }
                    item { Section("RINGKASAN MULTI-TIMEFRAME") }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("5m", "15m", "1h").forEach { tf -> Box(Modifier.weight(1f)) { TrendMiniCard(tf, results[tf]) } }
                    } }
                    item { Section("INTERPRETASI") }
                    item { InterpretationCard(results) }
                    item { Section("STRUKTUR TERBARU • 5M") }
                    item { CandleChart(candles["5m"].orEmpty(), results["5m"]) }
                    item { Button(onClick = { selected = "Chart" }, modifier = Modifier.fillMaxWidth()) { Text("Buka grafik lengkap") } }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { selected = "Detail Struktur" }, modifier = Modifier.weight(1f)) { Text("Detail struktur") }
                        OutlinedButton(onClick = { selected = "Level Kunci" }, modifier = Modifier.weight(1f)) { Text("Level kunci") }
                    } }
                    item { OutlinedButton(onClick = { selected = "Notifikasi" }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Notifications, null); Spacer(Modifier.width(8.dp)); Text("Pusat notifikasi")
                    } }
                }
                "Chart" -> {
                    item { Section("GRAFIK CANDLESTICK") }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("5m", "15m", "1h").forEach { tf ->
                            FilterChip(selected = timeframe == tf, onClick = { timeframe = tf }, label = { Text(tf.uppercase()) })
                        }
                    } }
                    item { CandleChart(candles[timeframe].orEmpty(), results[timeframe], tall = true) }
                    item { LegendRow() }
                    item { TrendCard(results[timeframe]) }
                    item { Text("Swing hanya ditandai setelah pivot memiliki dua candle konfirmasi di sebelah kanan. Candle terakhir yang masih berjalan tidak dipakai untuk konfirmasi.", color = Muted, fontSize = 12.sp) }
                    item { Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text(if (loading) "Memperbarui…" else "Perbarui candle") } }
                    if (error != null) item { ErrorCard(error!!) }
                }
                "Analisis" -> {
                    item { Section("ANALISIS STRUKTUR MULTI-TIMEFRAME") }
                    item { InterpretationCard(results) }
                    listOf("1h", "15m", "5m").forEach { tf ->
                        item { Section("TIMEFRAME ${tf.uppercase()}") }
                        item { TrendCard(results[tf]) }
                        item { DetailCard(results[tf]) }
                    }
                    item { Text("Aturan: 1H adalah bias utama; 15m memberi konteks; 5m menunjukkan gerak jangka pendek. Perbedaan arah 5m tidak otomatis membatalkan tren 1H.", color = Muted, fontSize = 12.sp) }
                }
                "Riwayat" -> {
                    item { Section("RIWAYAT ANALISIS TERSIMPAN") }
                    item { Text("Disimpan lokal di perangkat ini setelah data berhasil diperbarui. Maksimal 100 catatan.", color = Muted, fontSize = 12.sp) }
                    if (history.isEmpty()) item { EmptyCard("Belum ada riwayat. Perbarui data saat koneksi tersedia.") }
                    items(history) { run ->
                        Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(14.dp)) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(SimpleDateFormat("dd MMM yyyy • HH:mm:ss", Locale("id","ID")).format(Date(run.time)), color = Muted, fontSize = 11.sp)
                                Text("$" + "%,.2f".format(Locale.US, run.price), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(run.bias, color = if (run.bias.contains("BULLISH")) Green else if (run.bias.contains("BEARISH")) Red else Amber, fontWeight = FontWeight.Bold)
                                Text(run.note, color = Muted, fontSize = 12.sp)
                            }
                        }
                    }
                    item { OutlinedButton(onClick = {
                        history = emptyList()
                        context.getSharedPreferences("btc_structure", Context.MODE_PRIVATE).edit().remove("history").apply()
                    }, modifier = Modifier.fillMaxWidth()) { Text("Hapus riwayat") } }
                }
                "Detail Struktur" -> {
                    item { Section("DETAIL STRUKTUR • BTC/USDT") }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("5m", "15m", "1h").forEach { tf ->
                            FilterChip(selected = timeframe == tf, onClick = { timeframe = tf }, label = { Text(tf.uppercase()) })
                        }
                    } }
                    item { TrendCard(results[timeframe]) }
                    item { CandleChart(candles[timeframe].orEmpty(), results[timeframe], tall = true) }
                    item { Section("INFORMASI STRUKTUR") }
                    item { DetailCard(results[timeframe]) }
                    item { Section("KONTEKS MULTI-TIMEFRAME") }
                    item { InterpretationCard(results) }
                }
                "Level Kunci" -> {
                    item { Section("LEVEL KUNCI • ${timeframe.uppercase()}") }
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("5m", "15m", "1h").forEach { tf ->
                            FilterChip(selected = timeframe == tf, onClick = { timeframe = tf }, label = { Text(tf.uppercase()) })
                        }
                    } }
                    val swings = results[timeframe]?.swings.orEmpty()
                    val highs = swings.filter { it.isHigh }.takeLast(2).reversed()
                    val lows = swings.filter { !it.isHigh }.takeLast(2).reversed()
                    item { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Resistance", color = Red, fontWeight = FontWeight.Bold)
                            if (highs.isEmpty()) Text("Belum ada swing high terkonfirmasi", color = Muted, fontSize = 12.sp)
                            highs.forEachIndexed { i, s -> Row {
                                Text("R${i+1}", color = Red, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
                                Text("$" + "%,.2f".format(Locale.US, s.price), color = Color.White)
                            } }
                            Divider(color = Panel2)
                            Text("Support", color = Green, fontWeight = FontWeight.Bold)
                            if (lows.isEmpty()) Text("Belum ada swing low terkonfirmasi", color = Muted, fontSize = 12.sp)
                            lows.forEachIndexed { i, s -> Row {
                                Text("S${i+1}", color = Green, fontWeight = FontWeight.Bold, modifier = Modifier.width(40.dp))
                                Text("$" + "%,.2f".format(Locale.US, s.price), color = Color.White)
                            } }
                        }
                    } }
                    item { CandleChart(candles[timeframe].orEmpty(), results[timeframe], tall = true) }
                    item { Text("Level dihitung dari swing yang sudah terkonfirmasi, bukan prediksi harga atau jaminan breakout.", color = Muted, fontSize = 12.sp) }
                }
                "Notifikasi" -> {
                    item { Section("PUSAT NOTIFIKASI") }
                    item { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Ringkasan struktur terbaru", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            Text("Halaman ini merangkum kondisi struktur yang berhasil dimuat. Notifikasi sistem dan pemantauan layar mati belum aktif pada versi ini.", color = Muted, fontSize = 12.sp)
                            Divider(color = Panel2)
                            listOf("1h", "15m", "5m").forEach { tf ->
                                val r = results[tf]
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(when(r?.trend) {
                                        Trend.BULLISH -> Icons.Default.TrendingUp
                                        Trend.BEARISH -> Icons.Default.TrendingDown
                                        else -> Icons.Default.Timeline
                                    }, null, tint = trendColor(r?.trend))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text("${tf.uppercase()} • ${trendLabel(r?.trend)}", color = Color.White, fontWeight = FontWeight.SemiBold)
                                        Text(r?.summary ?: "Belum ada data struktur", color = Muted, fontSize = 12.sp)
                                    }
                                }
                                Divider(color = Panel2)
                            }
                            Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                                Text(if (loading) "Memperbarui…" else "Perbarui struktur")
                            }
                        }
                    } }
                }
                else -> {
                    item { Section("PENGATURAN") }
                    item { Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("Sumber data", color = Muted, fontSize = 12.sp)
                            Text("Binance public market data • BTC/USDT", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Divider(color = Panel2)
                            Text("Pembaruan", color = Muted, fontSize = 12.sp)
                            Text("Untuk saat ini pembaruan dilakukan manual dengan tombol refresh. Pemantauan otomatis saat layar mati belum diaktifkan pada versi ini.", color = Color.White, fontSize = 13.sp)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.BatteryChargingFull, null, tint = Amber)
                                Spacer(Modifier.width(8.dp))
                                Text("Pemantauan latar belakang", modifier = Modifier.weight(1f), color = Color.White)
                                Text("Belum aktif", color = Amber, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                            Divider(color = Panel2)
                            Text("Metode analisis", color = Muted, fontSize = 12.sp)
                            Text("Swing pivot 2 candle kiri + 2 candle kanan; HH/HL/LH/LL. Tidak menggunakan EMA, RSI, atau MACD sebagai penentu arah.", color = Color.White, fontSize = 13.sp)
                            Button(onClick = { refresh() }, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Text("Uji koneksi / perbarui data") }
                            if (error != null) ErrorCard(error!!)
                            Text("Versi 0.2 • Pengujian aktif • Bukan saran transaksi", color = Muted, fontSize = 11.sp)
                        }
                    } }
                }
            }
        }
    }
}

@Composable private fun Section(text: String) { Text(text, color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold) }

@Composable private fun PriceCard(price: Double?, loading: Boolean, error: String?, lastRefresh: Long?) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Harga BTC/USDT", color = Muted, fontSize = 12.sp)
            Text(price?.let { "$" + "%,.2f".format(Locale.US, it) } ?: if (loading) "Memuat data…" else "Belum tersedia",
                fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
    Card(colors = CardDefaults.cardColors(containerColor = Panel), shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Text("OHLC • Swing terkonfirmasi", color = Color.White, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(8.dp))
            Canvas(Modifier.fillMaxWidth().height(if (tall) 340.dp else 210.dp)) {
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
