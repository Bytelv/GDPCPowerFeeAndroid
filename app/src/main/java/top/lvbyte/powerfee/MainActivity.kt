package top.lvbyte.powerfee

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 主界面：余额、状态、日均用量/预计可用天数，以及一个"自检"区。
 *
 * 自检区是这个 App 的口碑关键：Android 的后台限制让"到点一定查"无法保证，
 * 所以必须让用户能自己看出——上次成功是什么时候、后台任务实际跑了多少次、
 * 通知权限还在不在、电池优化有没有白名单。看不到这些，用户只会觉得"这 App 不提醒"。
 */
class MainActivity : Activity() {

    private lateinit var store: Store
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var roomTitle: TextView
    private lateinit var balanceValue: TextView
    private lateinit var levelBadge: TextView
    private lateinit var thresholdLine: TextView
    private lateinit var dailyUsage: TextView
    private lateinit var daysLeft: TextView
    private lateinit var pollCount: TextView
    private lateinit var checkText: TextView
    private lateinit var notifyBtn: Button
    private lateinit var chart: HistoryChartView
    private lateinit var chartCaption: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = Store(this)
        Notifier.ensureChannel(this)

        roomTitle = findViewById(R.id.roomTitle)
        balanceValue = findViewById(R.id.balanceValue)
        levelBadge = findViewById(R.id.levelBadge)
        thresholdLine = findViewById(R.id.thresholdLine)
        dailyUsage = findViewById(R.id.dailyUsage)
        daysLeft = findViewById(R.id.daysLeft)
        pollCount = findViewById(R.id.pollCount)
        checkText = findViewById(R.id.checkText)
        notifyBtn = findViewById(R.id.notifyBtn)
        chart = findViewById(R.id.chart)
        chartCaption = findViewById(R.id.chartCaption)

        if (!store.configured) {
            startActivity(Intent(this, SetupActivity::class.java))
            finish()
            return
        }

        // 幂等：每次打开都确保周期任务在跑（设置变了会就地更新）
        Scheduler.schedule(this)

        findViewById<Button>(R.id.refreshBtn).setOnClickListener {
            Scheduler.runNow(this)
            toast("已开始查询，几秒后自动刷新")
            handler.postDelayed({ if (!isFinishing) refreshUi() }, 6000)
        }
        findViewById<Button>(R.id.setupBtn).setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java))
        }
        findViewById<Button>(R.id.batteryBtn).setOnClickListener { openBatterySettings() }
        notifyBtn.setOnClickListener { askNotificationPermission() }
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ---------------- 界面刷新 ----------------

    private fun refreshUi() {
        roomTitle.text = store.roomName.ifEmpty { "未命名房间" } + "（" + store.roomNum + "）"

        val balance = store.lastBalance
        balanceValue.text = if (balance == null) "--" else String.format(Locale.US, "%.2f", balance)

        val level = store.lastLevel
        // 措辞与小组件、通知保持一致：低电量直接给结论（需要充值），
        // 不用"电量偏低"这类无主语说法（那会让人以为是手机电量）
        levelBadge.text = when (level) {
            Level.OK -> "充足"
            Level.WARN -> "接近阈值"
            Level.LOW -> "需要充值"
            Level.UNKNOWN -> if (store.lastError.isEmpty()) "未获取" else "获取失败"
        }
        applyBadgeColor(level)

        thresholdLine.text = String.format(
            Locale.US,
            "提醒阈值 %.1f 度 · 低于 %.1f 度转预警 · 冷却 %d 分钟 · 每 %d 分钟查询",
            store.threshold, store.threshold * store.warnRatio, store.cooldownMinutes, store.intervalMinutes
        )

        val points = store.history()
        val now = System.currentTimeMillis() / 1000
        val stats = LevelLogic.computeStats(points, balance ?: 0.0, now)
        dailyUsage.text = stats.dailyUsage?.let { String.format(Locale.US, "%.2f", it) } ?: "--"
        daysLeft.text = stats.daysLeft?.let { String.format(Locale.US, "%.1f", it) } ?: "--"
        pollCount.text = store.pollCount.toString()

        // 曲线：把两条阈值线也画进去，方便判断"还有多少余量"
        chart.setData(points, store.threshold, store.warnRatio)
        chartCaption.text = if (points.size < 2) {
            "采样点不足：目前 ${points.size} 个（每查询一次记 1 个，满 2 个就能出曲线）"
        } else {
            "共 ${points.size} 个采样点 · 最早 ${fmtTime(points.first().first)}（只保留最近 7 天）"
        }

        val notificationsOn = notificationsEnabled()
        notifyBtn.visibility = if (notificationsOn) View.GONE else View.VISIBLE

        val lines = ArrayList<String>()
        lines.add("上次查询成功：" + fmtTime(store.lastOkAt) + freshness(store))
        lines.add("上次提醒：" + (if (store.lastAlertAt > 0) fmtTime(store.lastAlertAt) + "（" + store.lastAlertReason + "）" else "无"))
        lines.add("后台执行次数：" + store.pollCount + " 次（含手动触发）")
        lines.add("通知权限：" + if (notificationsOn) "已开启" else "未开启 ← 点上面的按钮授权")
        lines.add("桌面图标：" + (if (store.dynamicIcon) "随电量变色" else "固定不变") + " —— " + store.iconStatus)
        if (store.lastError.isNotEmpty()) lines.add("上次失败原因：" + store.lastError)
        lines.add("历史采样：" + points.size + " 个点（保留最近 7 天）")
        checkText.text = lines.joinToString("\n")
    }

    private fun applyBadgeColor(level: Level) {
        val colorRes = when (level) {
            Level.OK -> R.color.level_ok
            Level.WARN -> R.color.level_warn
            Level.LOW -> R.color.level_low
            Level.UNKNOWN -> R.color.level_unknown
        }
        val background = GradientDrawable()
        background.shape = GradientDrawable.RECTANGLE
        background.cornerRadius = 999f
        background.setColor(getColor(colorRes))
        levelBadge.background = background
    }

    // ---------------- 权限与系统设置 ----------------

    private fun notificationsEnabled(): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        return nm.areNotificationsEnabled()
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION)
                return
            }
        }
        // 权限在，但系统层面被关掉了：直接跳到本应用的通知设置
        toast("请在系统设置里允许本应用发送通知")
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            startActivity(intent)
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION) {
            refreshUi()
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                toast("通知已开启")
            } else {
                toast("没给通知权限，电量告警将无法弹出")
            }
        }
    }

    private fun openBatterySettings() {
        // 这里刻意不申请 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限（那属于敏感权限），
        // 而是把用户带到系统列表里自己添加，行为等价且更透明。
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            openAppDetails()
        }
    }

    private fun openAppDetails() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(android.net.Uri.parse("package:$packageName"))
            startActivity(intent)
        } catch (e: Exception) {
            toast("请手动到系统设置里配置本应用")
        }
    }

    private fun fmtTime(epochSeconds: Long): String {
        if (epochSeconds <= 0L) return "从未"
        val sdf = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        return sdf.format(Date(epochSeconds * 1000))
    }

    /**
     * 数据新鲜度，形如「（3 小时前）」/「（偏旧：8 小时前）」。
     *
     * 这一页可以放心用相对时间：它每次打开都会重绘（onResume → refreshUi），
     * 所以算出来的差值一定是当前的。桌面小组件不行——它只在轮询成功后和系统 tick 时重绘，
     * 相对时间会永远停在"刚刚更新"，那边因此改用绝对时间。
     */
    private fun freshness(store: Store): String {
        val lastOk = store.lastOkAt
        if (lastOk <= 0L) return ""
        val minutes = (System.currentTimeMillis() / 1000 - lastOk) / 60
        val text = when {
            minutes < 2 -> "2 分钟内"
            minutes < 60 -> "$minutes 分钟前"
            minutes < 60 * 24 -> "${minutes / 60} 小时前"
            else -> "${minutes / (60 * 24)} 天前"
        }
        val staleAfterMinutes = maxOf(store.intervalMinutes.toLong() * 2, 90L)
        return if (minutes >= staleAfterMinutes) "（偏旧：$text）" else "（$text）"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQ_NOTIFICATION = 101
    }
}
