package com.notedrop.demo.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 应用更新后把长连接服务拉起来。
 *
 * 没有这个接收器的话，用户重启一次手机，长连接就永久断了 ——
 * 表现就是"对方发的纸条我再也收不到"，而且完全看不出原因。
 *
 * MY_PACKAGE_REPLACED 也要处理：覆盖安装会杀掉服务，
 * 如果不重新拉起，用户升级一次 App 也会静默失去接收能力。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        AppConfig.load(context)
        if (!AppConfig.isRegistered) {
            Log.i(TAG, "尚未注册身份，跳过自启")
            return
        }

        Log.i(TAG, "收到 $action，启动长连接服务")
        NoteSocketService.start(context)
    }

    private companion object {
        const val TAG = "NoteDrop"
    }
}
