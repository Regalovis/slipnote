package com.notedrop.demo.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.notedrop.demo.R

/**
 * 长连接服务的前台通知。
 *
 * Android 要求前台服务必须挂一条常驻通知。这里刻意做成"静默的"：
 * 优先级最低、不响铃、不震动 —— 它只是系统要求的凭证，
 * 不应该打扰用户（小纸条本身是安静的媒介）。
 */
object NotificationHelper {

    const val ID = 1001
    private const val CHANNEL = "notedrop_connection"
    /** 收到新纸条的提醒渠道，与常驻的服务通知分开，方便用户单独关闭。 */
    const val CHANNEL_NOTE = "notedrop_notes"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        if (manager.getNotificationChannel(CHANNEL) == null) {
            val channel = NotificationChannel(
                CHANNEL,
                context.getString(R.string.channel_connection),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            manager.createNotificationChannel(channel)
        }

        // 新纸条提醒要能弹出来，否则收不到纸条也不知道
        if (manager.getNotificationChannel(CHANNEL_NOTE) == null) {
            val noteChannel = NotificationChannel(
                CHANNEL_NOTE,
                context.getString(R.string.channel_note),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setShowBadge(true)
            }
            manager.createNotificationChannel(noteChannel)
        }
    }

    fun build(context: Context): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify_paper)
            .setContentTitle(context.getString(R.string.notify_title))
            .setContentText(context.getString(R.string.notify_text))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }
}
