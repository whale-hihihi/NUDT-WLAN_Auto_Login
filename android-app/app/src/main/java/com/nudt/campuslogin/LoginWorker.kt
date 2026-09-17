package com.nudt.campuslogin

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * 后台自动登录任务(WorkManager, 无常驻进程):
 *
 *  - wifiChain: 带"非计费网络(WiFi)"约束的一次性任务 —— WiFi 连上/重连时系统唤醒执行;
 *    执行完再排下一个同名任务(自续链), 持续覆盖每次断线重连, 平时不占任何资源。
 *  - periodic: 每 15 分钟兜底(服务器踢线等), 已联网时亚秒级结束。
 */
class LoginWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val reason = inputData.getString("reason") ?: "后台"
        val engine = LoginEngine(applicationContext)
        val res = engine.ensureOnline(reason)
        // 自续链: 无论结果如何都排下一次 WiFi 触发, 保证下次重连仍会被唤醒
        if (Prefs.read(applicationContext).autoEnabled) {
            scheduleNext(applicationContext)
        }
        return Result.success(workDataOf("state" to res.state.name, "message" to res.message))
    }

    companion object {
        private const val UNIQUE_WIFI_CHAIN = "campus_login_wifi_chain"
        private const val UNIQUE_PERIODIC = "campus_login_periodic"

        private fun wifiConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .build()

        /** WiFi 触发的一次性任务(挂到链头) */
        fun scheduleWifiTriggered(ctx: Context, reason: String = "网络变化") {
            val work = OneTimeWorkRequestBuilder<LoginWorker>()
                .setInputData(workDataOf("reason" to reason))
                .setConstraints(wifiConstraints())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(UNIQUE_WIFI_CHAIN, ExistingWorkPolicy.REPLACE, work)
        }

        /** 15 分钟兜底周期任务 */
        fun schedulePeriodic(ctx: Context) {
            val work = PeriodicWorkRequestBuilder<LoginWorker>(15, TimeUnit.MINUTES)
                .setInputData(workDataOf("reason" to "定时兜底"))
                .setConstraints(wifiConstraints())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(
                    UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, work
                )
        }

        /** Worker 自续链(用 KEEP 避免与自己竞争) */
        internal fun scheduleNext(ctx: Context) {
            val work = OneTimeWorkRequestBuilder<LoginWorker>()
                .setInputData(workDataOf("reason" to "网络变化"))
                .setConstraints(wifiConstraints())
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniqueWork(UNIQUE_WIFI_CHAIN, ExistingWorkPolicy.KEEP, work)
        }

        /** 开关自动登录 */
        fun setAutoEnabled(ctx: Context, enabled: Boolean) {
            if (enabled) {
                if (Prefs.ready(ctx)) {
                    scheduleWifiTriggered(ctx, "手动开启")
                    schedulePeriodic(ctx)
                }
            } else {
                WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_WIFI_CHAIN)
                WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_PERIODIC)
            }
        }
    }
}
