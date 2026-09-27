package com.attentionguard.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.attentionguard.app.CaptureActivity
import com.attentionguard.app.R
import com.attentionguard.app.core.CaptureMode
import com.attentionguard.app.core.Prefs

/**
 * A minimal foreground service whose only job is to keep the app process at
 * foreground importance so MIUI/HyperOS "Greezer" does not freeze the
 * accessibility service (which otherwise dies within seconds — see P1 report).
 * Not a full fix on its own: the user must also grant autostart / no battery
 * restriction, but this holds the process while the app is set up and running.
 */
class KeepAliveService : Service() {

    private val channelId = "attention_guard_keepalive"

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "偷闲观测中", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        startForeground(1, notification())
        CaptureDiagnostics(this).keepAlive(if (nm.areNotificationsEnabled()) "服务已启动" else "服务已启动，通知未授权或已关闭")
    }

    private fun notification(): Notification {
        val intentMode = Prefs(this).captureMode == CaptureMode.INTENT
        return Notification.Builder(this, channelId)
            .setContentTitle(if (intentMode) "偷闲 · 意图分析" else "偷闲 · 事件监测")
            .setContentText(if (intentMode) "仅分析前台可见语境，不保存消息" else "仅处理前台可见会话 · 点按查看运行诊断")
            .setSmallIcon(R.drawable.ag_radio)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, CaptureActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, KeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }
    }
}
