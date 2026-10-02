package top.lvbyte.powerfee

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.Locale

/**
 * 后台查询任务：查一次余额 → 判阈值 →（必要时）弹通知 → 更新图标与小组件。
 *
 * 关于可靠性（必须如实告诉用户）：WorkManager 的周期任务**最小间隔是 15 分钟**，
 * 且会被 Doze / 应用待机推迟；国产 ROM 的省电策略还可能直接掐掉。所以本 App 承诺的是
 * "通常每 15~60 分钟查一次"，而不是实时告警。自检页会显示上次成功时间与执行次数，
 * 让用户自己判断后台有没有被系统限制。
 */
class PollWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val store = Store(context)
        store.pollCount = store.pollCount + 1

        if (!store.configured) {
            // 还没选房间：什么都不做（用户可能只是装了还没设置）
            return Result.success()
        }

        return try {
            val balance = SchoolApi.fetchBalance(store.roomNum)
            val now = System.currentTimeMillis() / 1000
            val threshold = store.threshold
            val level = LevelLogic.levelOf(balance, threshold, store.warnRatio)
            val prevLevel = store.lastLevel

            val reason = LevelLogic.decideNotify(
                prevLevel = prevLevel,
                prevAlertAt = store.lastAlertAt,
                level = level,
                now = now,
                cooldownMinutes = store.cooldownMinutes,
                notifyWarn = store.notifyWarn,
                notifyRecovery = store.notifyRecovery
            )

            store.lastBalance = balance
            store.lastLevel = level
            store.lastOkAt = now
            store.lastError = ""
            store.appendHistory(now, balance)

            if (reason != null) {
                val stats = LevelLogic.computeStats(store.history(), balance, now)
                store.lastAlertAt = now
                store.lastAlertReason = reason
                Notifier.post(context, titleFor(level, reason), bodyFor(store, balance, threshold, stats, reason))
            }

            IconSwitcher.apply(context, level).also { store.iconStatus = it }
            PowerWidget.updateAll(context)
            Result.success()
        } catch (e: Exception) {
            // 查询失败：图标转灰（与托盘程序一致），并让 WorkManager 稍后重试
            store.lastError = e.message ?: e.toString()
            store.lastLevel = Level.UNKNOWN
            IconSwitcher.apply(context, Level.UNKNOWN).also { store.iconStatus = it }
            PowerWidget.updateAll(context)
            Result.retry()
        }
    }

    private fun titleFor(level: Level, reason: String): String = when {
        reason == "recovered" -> "✅ 电量已恢复"
        level == Level.LOW -> "⚠️ 宿舍电量不足"
        else -> "🔔 宿舍电量预警"
    }

    private fun bodyFor(
        store: Store,
        balance: Double,
        threshold: Double,
        stats: Stats,
        reason: String
    ): String {
        val where = store.roomName.ifEmpty { "宿舍" }
        if (reason == "recovered") {
            return String.format(
                Locale.US,
                "%s 当前 %.2f 度，已回到阈值 %.1f 度以上",
                where, balance, threshold
            )
        }
        val tail = stats.daysLeft?.let { String.format(Locale.US, "，约可用 %.1f 天", it) } ?: ""
        return String.format(
            Locale.US,
            "%s 剩余 %.2f 度（阈值 %.1f 度）%s",
            where, balance, threshold, tail
        )
    }
}
