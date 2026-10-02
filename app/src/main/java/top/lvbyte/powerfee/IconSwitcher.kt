package top.lvbyte.powerfee

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 「图标随电量变色」—— 靠启用/禁用 manifest 里的 4 个 activity-alias 实现
 * （Android 没有给普通应用"动态改图标"的官方 API，这是社区通行做法）。
 *
 * 已知副作用（系统行为，无法完全避免）：
 *  - 部分第三方桌面在组件启用状态变化时会重建快捷方式，导致图标位置被重排，少数情况下会
 *    把图标从桌面移除（此时从应用列表重新拖出来即可）。
 *  - 因此提供了开关（设置页可关），并额外提供桌面小组件作为更稳的替代方案。
 */
object IconSwitcher {

    private val ALIASES: Map<Level, String> = mapOf(
        Level.OK to ".LauncherOk",
        Level.WARN to ".LauncherWarn",
        Level.LOW to ".LauncherLow",
        Level.UNKNOWN to ".LauncherUnknown"
    )

    fun apply(context: Context, level: Level) {
        val store = Store(context)
        if (!store.dynamicIcon) return

        val target = ALIASES[level] ?: ALIASES.getValue(Level.UNKNOWN)
        val packageName = context.packageName
        val pm = context.packageManager

        // 先禁用其它三个，再启用目标，避免短暂出现两个桌面图标
        for ((_, alias) in ALIASES) {
            if (alias == target) continue
            setEnabled(pm, ComponentName(packageName, packageName + alias), false)
        }
        setEnabled(pm, ComponentName(packageName, packageName + target), true)
        store.currentAlias = target
    }

    private fun setEnabled(pm: PackageManager, component: ComponentName, enabled: Boolean) {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            // DONT_KILL_APP：切换图标不重启进程
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        } catch (e: Exception) {
            // 个别 ROM 会拒绝：忽略即可，功能降级为"图标不变"
        }
    }
}
