# 电量哨兵 (PowerFee Sentry)

广东警官学院校园智能控电系统 —— **宿舍电量后台监控 + 低电量通知**的 Android 客户端。

与 Windows 端 [PowerFeeTray](https://github.com/Bytelv/GDPCPowerFeeTray) 是同一个需求的两个端：
托盘程序管电脑，本应用管手机。

---

## 1. 为什么是 App，而不是网站 / 云函数

这个需求有一条绕不开的硬约束：**学校服务器只在中国大陆网络可达**
（境外 DNS 与 IP 双层不通），而任何"手机推送"方案都需要一个服务端替你在后台轮询。

于是所有"网页 + 云函数"的方案都长成同一个样子：域名 + 备案判断 + 海外边缘 + 境内云函数
+ 推送密钥 + 订阅维护，还要关心"轮询频率会不会惹到学校 WAF"。

**App 直接把这一整条链子砍掉了**：轮询发生在用户自己的手机上、走用户自己的网络，
提醒用系统本地通知——不需要服务器、不需要账号、不需要域名、不需要云函数、不需要密钥。

| | 网页 + 云函数 | 本 App |
|---|---|---|
| 谁在轮询 | 海外边缘 + 境内服务器 | **用户自己的手机** |
| 走谁的网 | 机房 IP | **用户自己的网络** |
| 依赖 | 域名、备案、云函数计费、推送密钥 | **无** |
| 每月成本 | 域名 + 云函数（约 ¥3/年） | **0** |

---

## 2. 功能

- 首次打开：从学校接口读取全校房间列表（约 2077 间），三级下拉选自己的宿舍
- 后台按 15/30/60/120/240 分钟查询余额（可设"仅 WiFi 查询"）
- 低于阈值弹**系统通知**；进入预警区（阈值 × 2）也可提醒；充值恢复提醒一次
- 显示日均用量与"预计还能用几天"（用最近 72 小时采样估算）
- **桌面图标随电量变色**：绿=充足、黄=预警、红=偏低、灰=查询失败
- **桌面小组件**：显示余额并按状态上色（比图标变色更稳，见第 6 节）
- 运行自检：上次成功时间、后台执行次数、通知权限、电池优化状态

---

## 3. 安装（发给同学用）

1. 从 [Releases](../../releases) 下载 `app-release.apk`（或从 Actions 构建产物里取）
2. 手机上打开 APK → 允许"安装未知来源应用"
3. 打开应用 → 选校区 / 楼栋 / 房间 → 保存
4. **允许通知**（Android 13+ 会弹权限框）
5. **允许后台运行**：系统设置 → 电池 → 应用耗电管理 → 本应用 → 允许后台/自启动。
   小米、华为、OPPO、vivo 这一步不做，通知大概率不会来。

> 国内下载 GitHub 可能很慢，可以同时传一份到网盘。

---

## 4. 构建

### 用 GitHub Actions（推荐）

推送到 `main` 或手动触发 `Build APK` 工作流即可，产物在 Actions 的 Artifacts 里。
`ubuntu-latest` 自带 Android SDK，本地不需要装几个 GB 的工具链。

- 若配置了签名密钥（见下），构建 **release** 包；否则降级构建 **debug** 包
- debug 包可以安装，但**签名不同、无法覆盖升级**已安装的 release 包

### 配置签名（建议一开始就做）

升级链依赖同一个签名密钥 —— 中途换密钥，用户必须先卸载再装。所以第一次发版前就生成好，
并把密钥放进 GitHub Secrets：

```bash
keytool -genkeypair -v -keystore release.jks -alias powerfee \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storetype JKS -dname "CN=lvbyte, OU=dev, O=lvbyte, L=Guangzhou, ST=Guangdong, C=CN"

# 转成 base64 用于 GitHub Secrets（Windows: certutil -encode release.jks tmp.b64）
base64 -w0 release.jks
```

在仓库 Settings → Secrets and variables → Actions 里添加：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | `release.jks` 的 base64 |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | `powerfee` |
| `KEY_PASSWORD` | key 口令 |

> `*.jks` 已在 `.gitignore` 里。**密钥一旦丢失就再也无法给已安装用户升级**，务必自己备份。

### 本地构建（可选）

需要 JDK 17 + Android SDK。仓库里没有提交 Gradle Wrapper 的二进制，所以本地请用已安装的
Gradle（8.7）：

```bash
gradle assembleDebug        # 或 assembleRelease（需先设 KEYSTORE_* 环境变量）
```

---

## 5. 开发辅助脚本

```bash
python tools/check-resources.py   # 离线资源校验（XML 格式、@string/R.id 等引用完整性）
python tools/make-icons.py        # 重新生成四种状态的启动图标 PNG
```

`check-resources.py` 的价值：在没有 Android SDK 的环境里，先把"引用了不存在的资源"
这类必然导致编译失败的问题挡掉（它会检查 manifest 组件是否有对应源文件、
图标是否同时具备传统 PNG 与自适应图标等）。

---

## 6. 已知限制（务必知道）

| 限制 | 说明 |
|---|---|
| **后台间隔有硬下限** | Android 周期任务最小 15 分钟，且会被 Doze / 应用待机推迟。本应用承诺的是"通常每 15~60 分钟查一次"，**不是实时告警** |
| **国产 ROM 会杀后台** | 必须允许自启动 + 电池优化白名单，否则通知可能根本不来。自检页的"后台执行次数"就是给用户看这个的 |
| **图标变色有副作用** | Android 没有官方"动态改图标"接口，靠切换四个 activity-alias 实现；个别第三方桌面会重排甚至移除图标。不放心就关掉该选项，改用小组件 |
| **流量** | 学校接口一次返回全校房间，响应约 940 KB。默认 30 分钟一次 ≈ 每月 45 MB；建议打开"仅 WiFi 查询" |
| **仅 Android** | iOS 无法安装 APK。iPhone 同学只能继续用小程序的查询页面 |
| **接口依赖** | 学校接口一旦改字段或收紧访问，两边（本应用与托盘程序）都要跟着改 |

---

## 7. 接口说明（逆向结论）

原页面：`https://yktxyk.gdppla.edu.cn/user/powerfee/index?from=wxminiprogram&token=<TOKEN>`

本应用只用**免登录**的房间列表接口：

```
POST /user/powerfee/getRoomInfo?from=wxminiprogram&implType=CGCOMMON0001&buyMark=
Content-Type: application/x-www-form-urlencoded
body: implType=CGCOMMON0001
```

一次返回全校 2077 个房间的当前余额（`obj[].powerBalance`，字符串、可为负）。
`token` 参数服务端完全忽略，所以源码里没有任何令牌。

> ⚠️ **请求头里绝对不要加 `Origin`**：实测学校 WAF 见到 `Origin` 一律返回 403。
> `HttpURLConnection` 原生不发 `Origin`，这正是本应用能在手机流量下直连的原因；
> 而浏览器必然携带 `Origin`，所以"网页直接查"这条路走不通（详见托盘程序仓库 README 2.1 节）。

---

## 8. 目录结构

```
app/src/main/
├─ AndroidManifest.xml          4 个 launcher 别名（图标变色）+ 小组件声明
├─ java/top/lvbyte/powerfee/
│  ├─ Level.kt                  状态判定与统计（纯逻辑，无 Android 依赖，与托盘程序同构）
│  ├─ SchoolApi.kt              接口客户端（HttpURLConnection + org.json，零第三方库）
│  ├─ Store.kt                  本地存储（SharedPreferences）
│  ├─ PollWorker.kt             后台查询任务
│  ├─ Scheduler.kt              周期任务调度（WorkManager）
│  ├─ Notifier.kt               本地通知
│  ├─ IconSwitcher.kt           图标变色（切换 activity-alias）
│  ├─ PowerWidget.kt            桌面小组件
│  ├─ MainActivity.kt           主界面 + 运行自检
│  └─ SetupActivity.kt          房间选择/阈值/间隔设置
└─ res/                         布局、颜色、图标（含 4 状态 × 5 密度的 PNG）
tools/
├─ check-resources.py           离线资源校验
└─ make-icons.py                图标生成
```

## 9. 与托盘程序的关系

判定逻辑（等级划分、冷却、恢复提醒、日均用量估算）刻意与
[PowerFeeTray](https://github.com/Bytelv/GDPCPowerFeeTray) 保持同构，
两边都改的时候请对照修改。差异在于：

- 托盘程序：弹自绘窗口或系统托盘气泡，颜色画在托盘图标上
- 本应用：系统本地通知，颜色画在桌面图标与小组件上

## 10. 许可与声明

个人自用的小工具，与学校官方无关；只读查询，不做任何充值或写操作。
