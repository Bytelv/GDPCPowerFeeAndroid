package top.lvbyte.powerfee

import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 跳到厂商的「自启动管理」页面。
 *
 * ⚠️ Android **没有**标准的自启动管理 API：这是各家 ROM 自己加的功能，包名与类名
 * 各不相同，且会随系统升级改名。所以这里只能按候选清单逐个尝试，
 * 每个候选都先 resolve 再启动（避免 ActivityNotFoundException），
 * 全部失败时由调用方退回"应用详情"页兜底。
 *
 * ⚠️ 另一个前提：应用 targetSdk ≥ 30 时受**软件包可见性**限制，
 * 若不在 AndroidManifest 里用 <queries> 声明这些厂商包名，
 * resolveActivity 会一律返回 null —— 表现为"点了没反应"，且极难排查。
 * 清单里的包名必须与 <queries> 中声明的保持一致。
 */
object AutoStartHelper {

    /** 厂商自启动页候选清单，按常见程度排序；"包名/类名"格式，类名以 . 开头表示相对包名 */
    private val CANDIDATES = listOf(
        // 华为 / 荣耀（EMUI、MagicOS 同源）
        "com.huawei.systemmanager/.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager/.appcontrol.activity.StartupAppControlActivity",
        "com.huawei.systemmanager/.optimize.process.ProtectActivity",
        // 小米 / Redmi / POCO
        "com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity",
        // OPPO / 一加 / realme
        "com.coloros.safecenter/.permission.startup.StartupAppListActivity",
        "com.oppo.safe/.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter/.startupapp.StartupAppListActivity",
        "com.oneplus.security/.chainlaunch.view.ChainLaunchAppListActivity",
        // vivo / iQOO
        "com.vivo.permissionmanager/.activity.BgStartUpManagerActivity",
        "com.iqoo.secure/.ui.phoneoptimize.BgStartUpManager",
        "com.iqoo.secure/.ui.phoneoptimize.AddWhiteListActivity",
        // 魅族
        "com.meizu.safe/.permission.SmartBGActivity",
        // 三星
        "com.samsung.android.lool/.activity.BatteryActivity"
    )

    /**
     * 尝试打开厂商自启动页面。
     * @return true = 已跳转到厂商页面；false = 本机没有匹配的入口（调用方应退回应用详情页）
     */
    fun open(context: Context): Boolean {
        for (candidate in CANDIDATES) {
            val slash = candidate.indexOf('/')
            if (slash <= 0) continue
            val pkg = candidate.substring(0, slash)
            val cls = candidate.substring(slash + 1)
            val className = if (cls.startsWith(".")) pkg + cls else cls
            val component = ComponentName(pkg, className)
            val intent = Intent().apply {
                this.component = component
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // 先确认这个组件真的存在（受软件包可见性影响，见类注释）
            val resolvable = try {
                context.packageManager.resolveActivity(intent, 0) != null
            } catch (e: Exception) {
                false
            }
            if (!resolvable) continue
            try {
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                // 组件存在但厂商不允许第三方启动（部分 ROM 会拦），继续试下一个
            }
        }
        return false
    }
}
