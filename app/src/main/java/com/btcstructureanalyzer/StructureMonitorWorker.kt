package com.btcstructureanalyzer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class StructureMonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("btc_structure", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("background_monitoring", false)) return Result.success()
        return try {
            val repository = BinanceRepository()
            val results = linkedMapOf<String, StructureResult>()
            for (tf in listOf("5m", "15m", "1h")) results[tf] = StructureAnalyzer.analyze(tf, repository.candles(tf, 120))
            val current = results["1h"]?.trend?.name ?: Trend.UNCONFIRMED.name
            val previous = prefs.getString("last_notified_1h", null)
            if (previous != null && previous != current && current != Trend.UNCONFIRMED.name) notifyBiasChange(previous, current)
            prefs.edit().putString("last_notified_1h", current).putLong("background_last_check", System.currentTimeMillis()).apply()
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }

    private fun notifyBiasChange(previous: String, current: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "btc_structure_changes"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Perubahan struktur BTC", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(R.drawable.ic_stat_btc)
            .setContentTitle("BTC Structure: bias 1H berubah")
            .setContentText("${previous.replace("_", " ")} → ${current.replace("_", " ")}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Struktur 1H berubah dari $previous menjadi $current. Buka aplikasi untuk memeriksa swing terbaru."))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        manager.notify(4101, notification)
    }
}
