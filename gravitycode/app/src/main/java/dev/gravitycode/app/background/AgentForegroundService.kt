package dev.gravitycode.app.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder

class AgentForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "GravityCode agent", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps long coding tasks alive while GravityCode is in the background"
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val project = intent?.getStringExtra(EXTRA_PROJECT).orEmpty().ifBlank { "workspace" }
        val task = intent?.getStringExtra(EXTRA_TASK).orEmpty().ifBlank { "Agent task running" }
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("GravityCode · $project")
                .setContentText(task.take(120))
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("GravityCode · $project")
                .setContentText(task.take(120))
                .setOngoing(true)
                .build()
        }
        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "gravitycode-agent"
        private const val NOTIFICATION_ID = 4105
        private const val EXTRA_PROJECT = "project"
        private const val EXTRA_TASK = "task"

        fun start(context: Context, project: String, task: String) {
            val intent = Intent(context, AgentForegroundService::class.java)
                .putExtra(EXTRA_PROJECT, project)
                .putExtra(EXTRA_TASK, task)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }
    }
}
