package top.lvbyte.powerfee

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** 本地通知。不需要任何推送服务（FCM/VAPID 都不需要）——提醒完全由本机后台查询触发。 */
object Notifier {

    private const val CHANNEL_ID = "powerfee-alerts"
    private const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(CHANNEL_ID, "电量提醒", NotificationManager.IMPORTANCE_HIGH)
        channel.description = "电量低于阈值、进入预警区或充值恢复时提醒"
        nm.createNotificationChannel(channel)
    }

    /** 发一条通知。方法名刻意不叫 notify，避免与 java.lang.Object#notify 同名带来的歧义。 */
    fun post(context: Context, title: String, body: String) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            legacyBuilder(context)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_stat_bolt)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Android 13+ 未授予通知权限时会走到这里：静默失败即可，自检页会提示去授权
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyBuilder(context: Context) = Notification.Builder(context)
}
