package top.lvbyte.powerfee

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import java.util.Locale

/**
 * 桌面小组件：显示余额并按电量状态上色。
 * 这是"图标随电量变色"的更稳替代方案（Android 原生机制，不受桌面重排影响）。
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
            val level = store.lastLevel

            views.setTextViewText(
                R.id.widget_balance,
                if (balance == null) "--" else String.format(Locale.US, "%.1f", balance)
            )
            views.setTextViewText(R.id.widget_hint, label(level) + ageSuffix(store))

            val background = when (level) {
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

        private fun label(level: Level): String = when (level) {
            Level.OK -> "电量充足"
            Level.WARN -> "电量预警"
            Level.LOW -> "电量偏低"
            Level.UNKNOWN -> "尚未获取"
        }

        private fun ageSuffix(store: Store): String {
            val lastOk = store.lastOkAt
            if (lastOk <= 0L) return ""
            val minutes = (System.currentTimeMillis() / 1000 - lastOk) / 60
            return when {
                minutes < 2 -> " · 刚刚"
                minutes < 60 -> " · ${minutes} 分钟前"
                else -> " · ${minutes / 60} 小时前"
            }
        }
    }
}
