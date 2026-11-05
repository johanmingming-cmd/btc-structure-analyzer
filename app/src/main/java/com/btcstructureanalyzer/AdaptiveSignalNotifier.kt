package com.btcstructureanalyzer

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts one alert for each scan batch containing newly persisted Adaptive LONG/SHORT decisions.
 * WAIT decisions are deliberately silent. Journal IDs provide deduplication in the caller.
 */
object AdaptiveSignalNotifier {
    private const val CHANNEL_ID = "adaptive_prediction_signals"
    private const val NOTIFICATION_ID = 4301

    fun notifyNewSignals(context: Context, records: List<AdaptivePredictionRecord>) {
        val signals = records.filter { it.decision == "LONG" || it.decision == "SHORT" }
        if (signals.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val systemManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            systemManager.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    "Sinyal Adaptive Prediction",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Peringatan saat Adaptive Prediction mengonfirmasi LONG atau SHORT baru"
                }
            )
        }

        val longCount = signals.count { it.decision == "LONG" }
        val shortCount = signals.count { it.decision == "SHORT" }
        val lines = signals.take(8).map { record ->
            val price = String.format(java.util.Locale.US, "%.8f", record.referencePrice)
                .trimEnd('0').trimEnd('.')
            "${record.symbol} • ${record.decision} • $$price • ${record.setupFamily} • kualitas ${record.qualityScore}/100"
        }
        val extra = (signals.size - lines.size).coerceAtLeast(0)
        val detail = lines.joinToString("\n") +
            if (extra > 0) "\n+ $extra sinyal lainnya" else ""
        val title = when {
            longCount > 0 && shortCount > 0 -> "Adaptive Prediction • LONG + SHORT"
            longCount > 0 -> "Adaptive Prediction • LONG terkonfirmasi"
            else -> "Adaptive Prediction • SHORT terkonfirmasi"
        }
        val summary = buildList {
            if (longCount > 0) add("$longCount LONG")
            if (shortCount > 0) add("$shortCount SHORT")
        }.joinToString(" • ")

        val openDashboard = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_TAB, "Home")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openDashboard,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_btc)
            .setContentTitle(title)
            .setContentText("$summary • ${signals.size} sinyal baru")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$summary\n$detail\nAnalisis saja • tidak ada order otomatis"))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
