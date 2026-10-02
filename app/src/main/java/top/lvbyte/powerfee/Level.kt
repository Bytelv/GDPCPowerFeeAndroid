package top.lvbyte.powerfee

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 电量状态。语义与托盘程序、以及已搁置的网页版完全一致：
 *  - [OK]      充足（绿）
 *  - [WARN]    进入预警区，低于 阈值 × warnRatio（黄）
 *  - [LOW]     低于阈值（红）
 *  - [UNKNOWN] 查询失败或尚无数据（灰）
 */
enum class Level {
    OK, WARN, LOW, UNKNOWN;

    /** 用于资源名后缀（ic_launcher_ok / widget_bg_low …）与本地存储 */
    val key: String
        get() = when (this) {
            OK -> "ok"
            WARN -> "warn"
            LOW -> "low"
            UNKNOWN -> "unknown"
        }

    companion object {
        fun fromKey(value: String?): Level = when (value) {
            "ok" -> OK
            "warn" -> WARN
            "low" -> LOW
            else -> UNKNOWN
        }
    }
}

/** 日均用量（度）与预计可用天数，数据不足时为 null */
data class Stats(val dailyUsage: Double?, val daysLeft: Double?)

/**
 * 判定逻辑集中在这里，刻意与托盘程序（C#）保持同构，便于两边对照修改。
 * 注意：这里**不能**引入任何 Android 依赖，方便将来直接搬去做单元测试。
 */
object LevelLogic {

    fun levelOf(balance: Double, threshold: Double, warnRatio: Double): Level = when {
        balance < threshold -> Level.LOW
        balance < threshold * warnRatio -> Level.WARN
        else -> Level.OK
    }

    /**
     * 是否需要弹通知。返回提醒原因（便于自检页展示），不需要提醒时返回 null。
     * 规则与托盘程序一致：等级变差立即提醒；持续低电量按冷却时间重复提醒；充值恢复提醒一次。
     */
    fun decideNotify(
        prevLevel: Level,
        prevAlertAt: Long,
        level: Level,
        now: Long,
        cooldownMinutes: Int,
        notifyWarn: Boolean,
        notifyRecovery: Boolean
    ): String? {
        val rank = mapOf(Level.OK to 0, Level.WARN to 1, Level.LOW to 2, Level.UNKNOWN to 0)
        if ((rank[level] ?: 0) > (rank[prevLevel] ?: 0)) {
            if (level == Level.WARN && !notifyWarn) return null
            return "${prevLevel.key}->${level.key}"
        }
        if (level == Level.LOW && now - prevAlertAt >= cooldownMinutes * 60L) return "repeat-low"
        if (notifyRecovery && prevLevel == Level.LOW && level != Level.LOW) return "recovered"
        return null
    }

    /** 用最近 [windowHours] 小时的采样估算日均用量与可用天数（与托盘程序同思路） */
    fun computeStats(
        points: List<Pair<Long, Double>>,
        balance: Double,
        now: Long,
        windowHours: Int = 72
    ): Stats {
        if (points.size < 2) return Stats(null, null)
        val cutoff = now - windowHours * 3600L
        var window = points.filter { it.first >= cutoff }
        if (window.size < 2) window = points.takeLast(8)
        val first = window.first()
        val last = window.last()
        val hours = (last.first - first.first) / 3600.0
        if (hours < 3) return Stats(null, null)
        val used = first.second - last.second
        if (used <= 0) return Stats(0.0, null)
        val daily = used / hours * 24.0
        val daysLeft = max(0.0, balance) / daily
        return Stats(
            dailyUsage = (daily * 100).roundToInt() / 100.0,
            daysLeft = (daysLeft * 10).roundToInt() / 10.0
        )
    }
}
