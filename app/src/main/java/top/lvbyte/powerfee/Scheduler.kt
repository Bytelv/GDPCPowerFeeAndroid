package top.lvbyte.powerfee

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** 后台查询的调度：注册/取消周期任务，以及"立即查询一次"。 */
object Scheduler {

    private const val UNIQUE_NAME = "powerfee-periodic-poll"

    /** Android 周期任务的硬下限就是 15 分钟，小于它的设置在系统侧会被拉到 15 分钟 */
    const val MIN_INTERVAL_MINUTES = 15L

    val INTERVAL_OPTIONS = intArrayOf(15, 30, 60, 120, 240)

    fun schedule(context: Context) {
        val store = Store(context)
        val minutes = maxOf(MIN_INTERVAL_MINUTES, store.intervalMinutes.toLong())

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(
                if (store.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .build()

        val request = PeriodicWorkRequest.Builder(PollWorker::class.java, minutes, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .addTag(UNIQUE_NAME)
            .build()

        // UPDATE：设置变了就地替换，不会重复排队
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    /** 立即跑一次（不等待周期到点）。WorkManager 会保证设备唤醒后执行。 */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequest.Builder(PollWorker::class.java)
            .addTag("powerfee-now")
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
