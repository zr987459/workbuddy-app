package com.workbuddy.app

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger

class GatewayService : Service() {

    companion object {
        const val CHANNEL_ID = "wb_gateway_channel"
        const val NOTIF_ID = 8800
        @Volatile var isRunning = false
    }

    private var server: LocalGatewayServer? = null

    /**
     * 只在「有请求在处理」时持有 WakeLock，空闲立即释放：
     * 熄屏后长思考/长输出不会被挂起，平时也不白白耗电。
     */
    private val activeRequests = AtomicInteger(0)
    private val wakeLock: PowerManager.WakeLock by lazy {
        (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WorkBuddy:gateway")
            .apply { setReferenceCounted(false) }
    }

    private fun onRequestStart() {
        activeRequests.incrementAndGet()
        try {
            wakeLock.acquire(10 * 60 * 1000L) // 每个新请求都续期；10 分钟兜底防泄漏
        } catch (ignored: Exception) {}
    }

    private fun onRequestEnd() {
        if (activeRequests.decrementAndGet() <= 0) {
            activeRequests.set(0)
            try {
                if (wakeLock.isHeld) wakeLock.release()
            } catch (ignored: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AccountVault.init(applicationContext)
        GatewayMetrics.init(applicationContext)
        createNotificationChannel()
        startForegroundServiceNotification() // 必须在 startForegroundService 后 5 秒内调用

        server = LocalGatewayServer(
            applicationContext, 8800,
            onBusy = ::onRequestStart,
            onIdle = ::onRequestEnd
        )
        server?.start()
        isRunning = true
    }

    /** 被系统杀掉后自动拉起。onCreate 只走一次，重复 start 不会重复建 server。 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WorkBuddy 网关守护",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "保持 WorkBuddy 核心网关在后台持续运行"
                setShowBadge(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundServiceNotification() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WorkBuddy 网关运行中")
            .setContentText("本地端口 :8800 已就绪 · 后台持续常驻守护")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        isRunning = false
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (ignored: Exception) {}
    }
}
