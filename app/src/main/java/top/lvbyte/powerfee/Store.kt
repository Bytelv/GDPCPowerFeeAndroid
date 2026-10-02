package top.lvbyte.powerfee

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地存储（SharedPreferences）。刻意不用数据库：数据量极小，且这样能保持零额外依赖。
 * 存的东西：房间选择、阈值等设置、最近一次读数、状态与提醒时间、以及最多 600 个历史采样点。
 */
class Store(context: Context) {

    private val sp = context.applicationContext.getSharedPreferences("powerfee", Context.MODE_PRIVATE)

    // ---------------- 房间与设置 ----------------

    var roomNum: String
        get() = sp.getString(KEY_ROOM_NUM, "") ?: ""
        set(v) = sp.edit().putString(KEY_ROOM_NUM, v).apply()

    var campusName: String
        get() = sp.getString(KEY_CAMPUS, "") ?: ""
        set(v) = sp.edit().putString(KEY_CAMPUS, v).apply()

    var buildingName: String
        get() = sp.getString(KEY_BUILDING, "") ?: ""
        set(v) = sp.edit().putString(KEY_BUILDING, v).apply()

    var roomName: String
        get() = sp.getString(KEY_ROOM, "") ?: ""
        set(v) = sp.edit().putString(KEY_ROOM, v).apply()

    /** 低于此值即告警（度） */
    var threshold: Double
        get() = sp.getFloat(KEY_THRESHOLD, 20f).toDouble()
        set(v) = sp.edit().putFloat(KEY_THRESHOLD, v.toFloat()).apply()

    /** 低于 阈值 × 此倍数 时进入预警（黄） */
    var warnRatio: Double
        get() = sp.getFloat(KEY_WARN_RATIO, 2f).toDouble()
        set(v) = sp.edit().putFloat(KEY_WARN_RATIO, v.toFloat()).apply()

    /** 同一档位的重复提醒间隔（分钟） */
    var cooldownMinutes: Int
        get() = sp.getInt(KEY_COOLDOWN, 180)
        set(v) = sp.edit().putInt(KEY_COOLDOWN, v).apply()

    /** 后台查询间隔（分钟），下限 15 —— 这是 Android 周期任务的硬下限 */
    var intervalMinutes: Int
        get() = sp.getInt(KEY_INTERVAL, 30)
        set(v) = sp.edit().putInt(KEY_INTERVAL, v).apply()

    /** 只在 WiFi 下查询（省流量；学校接口一次返回全校，响应约 940 KB） */
    var wifiOnly: Boolean
        get() = sp.getBoolean(KEY_WIFI_ONLY, false)
        set(v) = sp.edit().putBoolean(KEY_WIFI_ONLY, v).apply()

    /** 进入预警区（黄）是否也弹通知；关掉后就与托盘程序一致（黄只改颜色不打扰） */
    var notifyWarn: Boolean
        get() = sp.getBoolean(KEY_NOTIFY_WARN, true)
        set(v) = sp.edit().putBoolean(KEY_NOTIFY_WARN, v).apply()

    /** 充值恢复后是否提醒一次 */
    var notifyRecovery: Boolean
        get() = sp.getBoolean(KEY_NOTIFY_RECOVERY, true)
        set(v) = sp.edit().putBoolean(KEY_NOTIFY_RECOVERY, v).apply()

    /** 是否启用"图标随电量变色"（关掉可避免某些桌面重排图标） */
    var dynamicIcon: Boolean
        get() = sp.getBoolean(KEY_DYNAMIC_ICON, true)
        set(v) = sp.edit().putBoolean(KEY_DYNAMIC_ICON, v).apply()

    val configured: Boolean get() = roomNum.isNotEmpty()

    // ---------------- 运行状态 ----------------

    var lastBalance: Double?
        get() = if (sp.contains(KEY_LAST_BALANCE)) sp.getFloat(KEY_LAST_BALANCE, 0f).toDouble() else null
        set(v) {
            val editor = sp.edit()
            if (v == null) editor.remove(KEY_LAST_BALANCE) else editor.putFloat(KEY_LAST_BALANCE, v.toFloat())
            editor.apply()
        }

    var lastLevel: Level
        get() = Level.fromKey(sp.getString(KEY_LAST_LEVEL, null))
        set(v) = sp.edit().putString(KEY_LAST_LEVEL, v.key).apply()

    /** 最近一次查询成功的时间（秒）；0 表示从未成功 */
    var lastOkAt: Long
        get() = sp.getLong(KEY_LAST_OK_AT, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_OK_AT, v).apply()

    /** 最近一次提醒时间（秒） */
    var lastAlertAt: Long
        get() = sp.getLong(KEY_LAST_ALERT_AT, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_ALERT_AT, v).apply()

    /** 最近一次提醒原因（自检页展示） */
    var lastAlertReason: String
        get() = sp.getString(KEY_LAST_ALERT_REASON, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_ALERT_REASON, v).apply()

    /** 最近一次失败原因；空串表示上次是成功的 */
    var lastError: String
        get() = sp.getString(KEY_LAST_ERROR, "") ?: ""
        set(v) = sp.edit().putString(KEY_LAST_ERROR, v).apply()

    /** 后台任务实际被执行的次数（用于判断系统有没有在掐后台） */
    var pollCount: Long
        get() = sp.getLong(KEY_POLL_COUNT, 0L)
        set(v) = sp.edit().putLong(KEY_POLL_COUNT, v).apply()

    /** 当前生效的桌面图标别名（自检页展示用） */
    var currentAlias: String
        get() = sp.getString(KEY_CURRENT_ALIAS, "") ?: ""
        set(v) = sp.edit().putString(KEY_CURRENT_ALIAS, v).apply()

    /** 上一次切换图标的结果（失败原因要能看见，否则用户只会觉得"这功能没用"） */
    var iconStatus: String
        get() = sp.getString(KEY_ICON_STATUS, "尚未切换") ?: "尚未切换"
        set(v) = sp.edit().putString(KEY_ICON_STATUS, v).apply()

    // ---------------- 历史采样 ----------------

    fun history(): MutableList<Pair<Long, Double>> {
        val raw = sp.getString(KEY_HISTORY, null) ?: return ArrayList()
        val out = ArrayList<Pair<Long, Double>>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(o.optLong("t") to o.optDouble("v"))
            }
        } catch (e: Exception) {
            return ArrayList()
        }
        return out
    }

    /** 追加一个采样点，只保留最近 7 天、最多 600 个点 */
    fun appendHistory(ts: Long, balance: Double) {
        val points = history()
        points.add(ts to balance)
        val cutoff = ts - 7 * 86400L
        val trimmed = points.filter { it.first >= cutoff }.takeLast(600)
        val arr = JSONArray()
        for (p in trimmed) {
            arr.put(JSONObject().put("t", p.first).put("v", p.second))
        }
        sp.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private companion object {
        const val KEY_ROOM_NUM = "roomNum"
        const val KEY_CAMPUS = "campus"
        const val KEY_BUILDING = "building"
        const val KEY_ROOM = "room"
        const val KEY_THRESHOLD = "threshold"
        const val KEY_WARN_RATIO = "warnRatio"
        const val KEY_COOLDOWN = "cooldownMinutes"
        const val KEY_INTERVAL = "intervalMinutes"
        const val KEY_WIFI_ONLY = "wifiOnly"
        const val KEY_NOTIFY_WARN = "notifyWarn"
        const val KEY_NOTIFY_RECOVERY = "notifyRecovery"
        const val KEY_DYNAMIC_ICON = "dynamicIcon"
        const val KEY_LAST_BALANCE = "lastBalance"
        const val KEY_LAST_LEVEL = "lastLevel"
        const val KEY_LAST_OK_AT = "lastOkAt"
        const val KEY_LAST_ALERT_AT = "lastAlertAt"
        const val KEY_LAST_ALERT_REASON = "lastAlertReason"
        const val KEY_LAST_ERROR = "lastError"
        const val KEY_POLL_COUNT = "pollCount"
        const val KEY_CURRENT_ALIAS = "currentAlias"
        const val KEY_ICON_STATUS = "iconStatus"
        const val KEY_HISTORY = "history"
    }
}
