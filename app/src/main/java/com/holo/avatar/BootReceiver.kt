package com.holo.avatar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机自启。Android 10+ 对后台启动 Activity 有限制，
 * 在定制 ROM 上通常需要「自启动白名单」或「悬浮窗权限」才能生效，
 * 详见 README 的部署章节。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        Log.i(TAG, "boot event: $action, launching MainActivity")
        try {
            val i = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                }
            }
            context.startActivity(i)
        } catch (e: Exception) {
            Log.e(TAG, "failed to launch on boot", e)
        }
    }

    companion object {
        private const val TAG = "HoloBoot"
    }
}
