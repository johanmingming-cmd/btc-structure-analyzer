package com.btcstructureanalyzer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * User-enabled foreground scanner for public Binance Spot market data.
 * It scans on a 10-minute cycle and posts notifications for newly detected
 * signals. It never submits orders.
 */
class FuturesAutoScanService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scanJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(ONGOING_ID, buildOngoingNotification("Menyiapkan pemindaian Spot…"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("btc_structure", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("background_monitoring", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (scanJob?.isActive != true) {
            scanJob = serviceScope.launch {
                while (true) {
                    try {
                        updateOngoing("Memindai Binance Spot • 1H / 15M / 5M")
                        val results = CoinScanner(BinanceRepository()).scan(15)
                        notifyNewSignals(results)
                        val stamp = System.currentTimeMillis()
                        prefs.edit().putLong("spot_last_scan", stamp).putInt("spot_last_count", results.size).remove("spot_last_error").apply()
                        updateOngoing("Spot aktif • ${results.size} sinyal • scan berikutnya sekitar 10 menit")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        prefs.edit().putString("spot_last_error", e.message ?: "Kesalahan scan").apply()
                        updateOngoing("Scan Spot gagal • periksa internet, akan mencoba lagi")
                    }
                    delay(SCAN_INTERVAL_MS)
                }
            }
        }
        return START_STICKY
    }

    private fun notifyNewSignals(results: List<ScannedCoin>) {
        val prefs = getSharedPreferences("btc_structure", Context.MODE_PRIVATE)
        val current = results.map { "${it.symbol}|${signalDirection(it)}" }.toSet()
        val previous = prefs.getStringSet("spot_previous_signals", emptySet()).orEmpty()
        val newSignals = current - previous
        prefs.edit().putStringSet("spot_previous_signals", current).apply()
        if (newSignals.isEmpty()) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val details = results.filter { "${it.symbol}|${signalDirection(it)}" in newSignals }
            .take(5)
            .joinToString("\n") { "${it.symbol}: ${signalDirection(it)} • skor ${it.score}" }
        val extra = (newSignals.size - 5).coerceAtLeast(0)
        val body = if (extra > 0) "$details\n+ $extra sinyal lainnya" else details
        manager.notify(SIGNAL_ID, NotificationCompat.Builder(this, SIGNAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_btc)
            .setContentTitle("BTC Structure • Sinyal Spot baru")
            .setContentText("${newSignals.size} sinyal baru terdeteksi")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$body\nAnalisis saja • tidak ada order otomatis"))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build())
    }

    private fun signalDirection(coin: ScannedCoin): String =
        if (coin.trend1h == Trend.BULLISH && coin.trend15m == Trend.BULLISH) "BULLISH"
        else if (coin.trend1h == Trend.BEARISH && coin.trend15m == Trend.BEARISH) "BEARISH"
        else "KONFIRMASI"

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL, "Auto Scan Spot", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Status layanan pemindaian Spot yang diaktifkan pengguna"
            })
            manager.createNotificationChannel(NotificationChannel(SIGNAL_CHANNEL, "Sinyal Spot", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Notifikasi saat scanner menemukan sinyal Spot baru"
            })
        }
    }

    private fun buildOngoingNotification(message: String): Notification =
        NotificationCompat.Builder(this, ONGOING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_btc)
            .setContentTitle("BTC Structure • Auto Scan aktif")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$message\nBinance Spot • tanpa auto entry"))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateOngoing(message: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(ONGOING_ID, buildOngoingNotification(message))
    }

    override fun onDestroy() {
        scanJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val SCAN_INTERVAL_MS = 10 * 60 * 1000L
        private const val ONGOING_CHANNEL = "spot_auto_scan_status"
        private const val SIGNAL_CHANNEL = "spot_new_signals"
        private const val ONGOING_ID = 4200
        private const val SIGNAL_ID = 4201
    }
}