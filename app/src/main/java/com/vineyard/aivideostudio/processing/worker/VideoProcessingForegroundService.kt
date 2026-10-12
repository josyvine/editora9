package com.vineyard.aivideostudio.processing.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.R
import com.vineyard.aivideostudio.MainActivity
import com.vineyard.aivideostudio.core.common.AppConstants

class VideoProcessingForegroundService : Service() {

    companion object {
        const val ACTION_START_FOREGROUND = "com.vineyard.aivideostudio.ACTION_START_FOREGROUND"
        const val ACTION_UPDATE_PROGRESS = "com.vineyard.aivideostudio.ACTION_UPDATE_PROGRESS"
        const val ACTION_STOP_FOREGROUND = "com.vineyard.aivideostudio.ACTION_STOP_FOREGROUND"

        const val EXTRA_NOTIFICATION_TEXT = "extra_notification_text"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_TOTAL = "extra_total"
    }

    private lateinit var notificationManager: NotificationManager
    private var currentMessage: String = "AI Video Studio is processing video stages..."

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_FOREGROUND -> {
                stopForegroundService()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_PROGRESS -> {
                val progress = intent.getIntExtra(EXTRA_PROGRESS, 0)
                val total = intent.getIntExtra(EXTRA_TOTAL, 0)
                updateProgressNotification(progress, total)
                return START_NOT_STICKY
            }
            ACTION_START_FOREGROUND -> {
                currentMessage = intent.getStringExtra(EXTRA_NOTIFICATION_TEXT)
                    ?: "AI Video Studio is processing video stages..."
            }
            else -> {
                // Fallback for default execution without explicit actions
                if (intent?.hasExtra(EXTRA_NOTIFICATION_TEXT) == true) {
                    currentMessage = intent.getStringExtra(EXTRA_NOTIFICATION_TEXT) ?: currentMessage
                }
            }
        }

        val notification = buildNotification(currentMessage)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    AppConstants.NOTIFICATION_ID_PROCESSING,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
                )
            } else {
                startForeground(AppConstants.NOTIFICATION_ID_PROCESSING, notification)
            }
        } else {
            startForeground(AppConstants.NOTIFICATION_ID_PROCESSING, notification)
        }
        return START_NOT_STICKY
    }

    private fun updateProgressNotification(progress: Int, total: Int) {
        val pct = if (total > 0) ((progress.toFloat() / total.toFloat()) * 100).toInt() else 0
        val text = if (total > 0) "$progress / $total frames ($pct%)" else currentMessage

        val intent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(this, AppConstants.NOTIFICATION_CHANNEL_PROCESSING)
            .setContentTitle("Editora Video Studio")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setProgress(total, progress, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

        notificationManager.notify(AppConstants.NOTIFICATION_ID_PROCESSING, notification)
    }

    private fun buildNotification(message: String): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, AppConstants.NOTIFICATION_CHANNEL_PROCESSING)
            .setContentTitle("Editora Video Studio")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AppConstants.NOTIFICATION_CHANNEL_PROCESSING,
                "Video Processing Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of ongoing AI video transformations"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}