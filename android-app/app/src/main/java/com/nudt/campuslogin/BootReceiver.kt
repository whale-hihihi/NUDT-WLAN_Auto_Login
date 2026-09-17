package com.nudt.campuslogin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机/应用更新后自动恢复后台任务链 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (Prefs.read(context).autoEnabled) {
                LoginWorker.scheduleWifiTriggered(context, "开机")
                LoginWorker.schedulePeriodic(context)
            }
        }
    }
}
