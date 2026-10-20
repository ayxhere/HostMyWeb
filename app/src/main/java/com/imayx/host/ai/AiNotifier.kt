package com.imayx.host.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.imayx.host.MainActivity
import com.imayx.host.R
import com.imayx.host.graph

/** Heads-up style notifications for AI fixes, route updates and network events (separate from the ongoing status one). */
object AiNotifier {
    private const val CHANNEL = "imayx_events"

    fun post(ctx: Context, title: String, text: String, id: Int = (System.currentTimeMillis() % 100000).toInt() + 10000) {
        try {
            if (!ctx.graph.settings.notificationsEnabled) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Fixes & events", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "AI auto-fix results, route updates, network changes"
                    }
                )
            }
            val open = PendingIntent.getActivity(
                ctx, id,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_host)
                .setContentTitle(title)
                .setContentText(text.lineSequence().first().take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1500)))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(id, n)
        } catch (_: Exception) {
            // Notification permission denied / not allowed: the same text is already in Logs.
        }
    }
}
