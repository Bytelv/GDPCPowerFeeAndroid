package top.lvbyte.powerfee

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import java.util.Locale

/**
 * 桌面小组件：显示余额并按电量状态上色。
 * 这是"图标随电量变色"的更稳替代方案（Android 原生机制，不受桌面重排影响）。
 *
 * 文案设计的两条硬规则：
 *  1. **绝不能出现"电量偏低"这种不带主语的措辞**。组件就贴在系统电池图标旁边，
 *     那样写会被读成"手机电量偏低"。所以第二行一律以「宿舍」开头。
 *  2. 低电量时给**可执行的结论**（「宿舍电费需要充值」），而不是只描述状态（「低于阈值」）。
 *     注意用词分工：度数用「电量」（数量），充值说「电费」（钱）——中文语感里这样才自然。
 *     第一行数字带单位「度」，进一步保证不会被当成手机电量百分比。
 */
class PowerWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        updateAll(context)
    }

    override fun onEnabled(context: Context) {
        // 第一个小组件被添加时，顺手刷新一次，避免显示空态
        updateAll(context)
    }

    companion object {

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, PowerWidget::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context, Store(context))
            for (id in ids) {
                try {
                    manager.updateAppWidget(id, views)
                } catch (e: Exception) {
                    // 单个小组件更新失败不影响其它
                }
            }
        }

        private fun buildViews(context: Context, store: Store): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_power)
            val balance = store.lastBalance

            if (balance == null) {
                // 没有数据时不显示单位，避免出现"-- 度"这种别扭组合
                views.setTextViewText(R.id.widget_balance, "--")
                views.setViewVisibility(R.id.widget_unit, View.GONE)
            } else {
                views.setTextViewText(R.id.widget_balance, String.format(Locale.US, "%.2f", balance))
                views.setViewVisibility(R.id.widget_unit, View.VISIBLE)
            }
            views.setTextViewText(R.id.widget_hint, hintText(store))

            val background = when (store.lastLevel) {
                Level.OK -> R.drawable.widget_bg_ok
                Level.WARN -> R.drawable.widget_bg_warn
                Level.LOW -> R.drawable.widget_bg_low
                Level.UNKNOWN -> R.drawable.widget_bg_unknown
            }
            views.setInt(R.id.widget_root, "setBackgroundResource", background)

            val intent = Intent(context, MainActivity::class.java)
            val pending = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pending)
            return views
        }

        /**
         * 第二行文案。顺序按"信息价值"排：
         *   状态（一定显示） → 还能用几天（仅预警/低电量时） → 数据更新时间（能打消"数据是不是停了"的疑虑）
         */
        private fun hintText(store: Store): String {
            val status = when (store.lastLevel) {
                Level.OK -> "宿舍电量充足"
                Level.WARN -> "宿舍电量接近阈值"
                Level.LOW -> "宿舍电费需要充值"
                Level.UNKNOWN -> if (store.lastError.isEmpty()) "宿舍数据未获取" else "宿舍数据获取失败"
            }
            val parts = ArrayList<String>()
            if (store.lastLevel == Level.WARN || store.lastLevel == Level.LOW) {
                daysLeftText(store)?.let { parts.add(it) }
            }
            ageText(store)?.let { parts.add(it) }
            return if (parts.isEmpty()) status else status + " · " + parts.joinToString(" · ")
        }

        /** 「约 2.3 天」——只有采样足够时才有意义，否则不显示 */
        private fun daysLeftText(store: Store): String? {
            val balance = store.lastBalance ?: return null
            val now = System.currentTimeMillis() / 1000
            val daysLeft = LevelLogic.computeStats(store.history(), balance, now).daysLeft ?: return null
            return String.format(Locale.US, "约 %.1f 天", daysLeft)
        }

        /**
         * 数据新鲜度。超过 2 倍查询间隔（且至少 90 分钟）就认为可能已过期，
         * 措辞换成「N 小时前未更新」，让用户能自己判断是不是后台被系统掐了。
         */
        private fun ageText(store: Store): String? {
            val lastOk = store.lastOkAt
            if (lastOk <= 0L) return null
            val minutes = (System.currentTimeMillis() / 1000 - lastOk) / 60
            val staleAfterMinutes = maxOf(store.intervalMinutes.toLong() * 2, 90L)
            return when {
                minutes < 2 -> "刚刚更新"
                minutes < 60 -> "更新于 $minutes 分钟前"
                minutes < staleAfterMinutes -> "更新于 ${minutes / 60} 小时前"
                else -> "${minutes / 60} 小时前未更新"
            }
        }
    }
}
