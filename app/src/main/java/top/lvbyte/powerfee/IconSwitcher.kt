package top.lvbyte.powerfee

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * 「图标随电量变色」—— 靠启用/禁用 manifest 里的 4 个 activity-alias 实现
 * （Android 没有给普通应用"动态改图标"的官方 API，这是社区通行做法）。
 *
 * ⚠️ 这里有一个非常容易踩的坑，值得写在最前面：
 * **不要用 `packageName` 去拼组件名。**
 * manifest 里的别名是相对名（`.LauncherOk`），AGP 会把它展开成**命名空间**下的
 * 全限定名（`top.lvbyte.powerfee.LauncherOk`），而**不是** applicationId 下的名字。
 * 当构建类型带 `applicationIdSuffix = ".debug"` 时两者不一样：
 *   - 真实组件名：top.lvbyte.powerfee.LauncherOk
 *   - packageName + ".LauncherOk"：top.lvbyte.powerfee.debug.LauncherOk ← 不存在
 * 对着不存在的组件调 setComponentEnabledSetting 会抛异常；如果再把异常吞掉，
 * 症状就是"图标永远不变色，但桌面小组件一切正常"（小组件不依赖组件名）。
 * 所以正确做法是从 PackageManager 里把真实组件名查出来。
 *
 * 已知副作用（系统行为，无法完全避免）：部分第三方桌面在组件启用状态变化时会重建
 * 快捷方式，导致图标位置被重排，少数情况下会把图标从桌面移除（重新拖出来即可）。
 * 因此提供了开关，并额外提供桌面小组件作为更稳的替代方案。
 */
object IconSwitcher {

    private const val TAG = "IconSwitcher"

    /** Level -> manifest 里 activity-alias 的类名后缀 */
    private val SUFFIX: Map<Level, String> = mapOf(
        Level.OK to "LauncherOk",
        Level.WARN to "LauncherWarn",
        Level.LOW to "LauncherLow",
        Level.UNKNOWN to "LauncherUnknown"
    )

    /**
     * 切换到指定状态的图标。
     * @return 结果描述，由调用方写入 Store.iconStatus，在自检页展示
     *         （失败必须可见，否则用户只会觉得"这功能没用"）
     */
    fun apply(context: Context, level: Level): String {
        val store = Store(context)
        if (!store.dynamicIcon) return "已关闭（设置里可开启）"

        val components = findAliases(context)
        if (components.isEmpty()) return "失败：在 PackageManager 里找不到图标别名组件"

        val suffix = SUFFIX[level] ?: SUFFIX.getValue(Level.UNKNOWN)
        val target = components[suffix] ?: return "失败：缺少 $suffix 别名（找到 ${components.size} 个）"

        val pm = context.packageManager
        var rejected = 0

        // 先禁用其它，再启用目标，避免短暂出现两个桌面图标
        for ((key, component) in components) {
            if (key == suffix) continue
            if (!setEnabled(pm, component, false)) rejected++
        }
        if (!setEnabled(pm, target, true)) rejected++

        store.currentAlias = "." + suffix
        return if (rejected == 0) {
            "已切到 $suffix（共找到 ${components.size} 个别名）"
        } else {
            "部分失败：$rejected 个组件设置被系统拒绝（共 ${components.size} 个别名）"
        }
    }

    /**
     * 从 PackageManager 里找出 4 个别名的**真实**组件名。
     * 必须带 GET_DISABLED_COMPONENTS，否则只能看到当前启用的那一个。
     */
    private fun findAliases(context: Context): Map<String, ComponentName> {
        val result = HashMap<String, ComponentName>()
        try {
            @Suppress("DEPRECATION")
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_ACTIVITIES or PackageManager.GET_DISABLED_COMPONENTS
            )
            for (activity in info.activities ?: emptyArray()) {
                val name = activity.name ?: continue
                val key = SUFFIX.values.firstOrNull { name == it || name.endsWith(".$it") } ?: continue
                result[key] = ComponentName(context.packageName, name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取组件列表失败", e)
        }
        return result
    }

    private fun setEnabled(pm: PackageManager, component: ComponentName, enabled: Boolean): Boolean {
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        return try {
            // DONT_KILL_APP：切换图标不重启进程
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            true
        } catch (e: Exception) {
            Log.w(TAG, "设置组件状态被拒绝：" + component.className, e)
            false
        }
    }
}
