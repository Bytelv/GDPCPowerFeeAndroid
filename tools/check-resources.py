#!/usr/bin/env python
"""
离线资源校验 —— 在没有 Android SDK、无法编译的情况下，先把必然导致编译失败的
"引用不存在的资源"挡掉。

    python tools/check-resources.py

检查项：
  1. app/src/main 下所有 XML 是否格式良好（well-formed）
  2. XML 里的 @string/ @color/ @drawable/ @layout/ @mipmap/ @xml/ @style/ 引用是否都能解析
  3. Kotlin 里的 R.<type>.<name> 引用是否都能解析
  4. layout 里定义的 android:id 是否覆盖了 Kotlin 里用到的所有 R.id.*
  5. manifest 里引用的图标是否同时具备"传统 PNG（各密度）"与"自适应图标（v26）"
  6. AndroidManifest 中声明的 Activity/Receiver 类是否有对应 Kotlin 源文件

退出码非 0 表示发现问题。
"""
import os
import re
import sys
import glob
import xml.etree.ElementTree as ET

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MAIN = os.path.join(ROOT, "app", "src", "main")
RES = os.path.join(MAIN, "res")
JAVA = os.path.join(MAIN, "java")
MANIFEST = os.path.join(MAIN, "AndroidManifest.xml")

problems = []
checks = 0


def ok(msg):
    global checks
    checks += 1


def bad(msg):
    problems.append(msg)
    print("  [X] " + msg)


# ---------------------------------------------------------------- 收集资源

VALUE_TYPES = ("string", "color", "dimen", "style", "integer", "bool", "array")

# 每种类型 -> {资源名: 定义位置}
defined = {t: {} for t in VALUE_TYPES}
# 文件型资源：drawable/layout/mipmap/xml/menu/anim
FILE_TYPES = ("drawable", "layout", "mipmap", "xml", "menu", "anim", "animator", "font", "raw")
file_resources = {t: {} for t in FILE_TYPES}   # name -> set(限定符目录)

for path in glob.glob(os.path.join(RES, "**", "*"), recursive=True):
    if os.path.isdir(path):
        continue
    folder = os.path.basename(os.path.dirname(path))
    base = os.path.basename(path)
    name, ext = os.path.splitext(base)
    if folder == "values" or folder.startswith("values-"):
        if ext != ".xml":
            continue
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as e:
            bad("XML 格式错误：%s（%s）" % (os.path.relpath(path, ROOT), e))
            continue
        for child in root:
            tag = child.tag
            res_name = child.get("name")
            if tag in VALUE_TYPES and res_name:
                defined[tag][res_name] = os.path.relpath(path, ROOT)
        ok("")
        continue
    # 文件型资源：drawable-xxxhdpi/foo.png、layout/bar.xml、mipmap-anydpi-v26/baz.xml
    kind = folder.split("-")[0]
    if kind in FILE_TYPES:
        if kind == "mipmap" and ext == ".png" or True:
            file_resources[kind].setdefault(name, set()).add(folder)

# 自适应图标 / 密度桶分别记录，便于第 5 项检查
mipmap_anydpi = set()
mipmap_png = set()
for path in glob.glob(os.path.join(RES, "mipmap-*", "*")):
    folder = os.path.basename(os.path.dirname(path))
    name, ext = os.path.splitext(os.path.basename(path))
    if folder == "mipmap-anydpi-v26" and ext == ".xml":
        mipmap_anydpi.add(name)
    elif ext == ".png" and folder.startswith("mipmap-"):
        mipmap_png.add(name)

# ---------------------------------------------------------------- 检查 1/2：XML

print("[1/6] XML 格式与资源引用")
XML_REF = re.compile(r'@(?!android:|null|\*)(\w+)/(\w+)')
xml_files = glob.glob(os.path.join(MAIN, "**", "*.xml"), recursive=True)
for path in xml_files:
    rel = os.path.relpath(path, ROOT)
    try:
        text = open(path, encoding="utf-8").read()
        ET.fromstring(text)
        ok("")
    except ET.ParseError as e:
        bad("XML 格式错误：%s（%s）" % (rel, e))
        continue
    for kind, name in XML_REF.findall(text):
        if kind in defined:
            if name not in defined[kind]:
                bad("%s 引用了未定义的 @%s/%s" % (rel, kind, name))
            else:
                ok("")
        elif kind in file_resources:
            if name not in file_resources[kind]:
                bad("%s 引用了不存在的 @%s/%s" % (rel, kind, name))
            else:
                ok("")
        # 其它类型（例如 @android: 已排除）不校验

# ---------------------------------------------------------------- 检查 3：Kotlin 的 R.*

print("[2/6] Kotlin 的 R.* 引用")
# 注意排除 android.R.*（平台自带资源，不属于本项目）
KOTLIN_REF = re.compile(r'(?<!android\.)\bR\.(\w+)\.(\w+)')
kt_files = glob.glob(os.path.join(JAVA, "**", "*.kt"), recursive=True)
android_ids = set()

# 先从 layout 里收集 id
ID_DEF = re.compile(r'android:id="@\+id/(\w+)"')
for path in glob.glob(os.path.join(RES, "layout", "*.xml")):
    text = open(path, encoding="utf-8").read()
    android_ids.update(ID_DEF.findall(text))

for path in kt_files:
    rel = os.path.relpath(path, ROOT)
    text = open(path, encoding="utf-8").read()
    for kind, name in KOTLIN_REF.findall(text):
        if kind == "id":
            if name not in android_ids:
                bad("%s 使用了未在 layout 中定义的 R.id.%s" % (rel, name))
            else:
                ok("")
        elif kind in defined:
            if name not in defined[kind]:
                bad("%s 使用了未定义的 R.%s.%s" % (rel, kind, name))
            else:
                ok("")
        elif kind in file_resources:
            if name not in file_resources[kind]:
                bad("%s 使用了不存在的 R.%s.%s" % (rel, kind, name))
            else:
                ok("")
        else:
            bad("%s 使用了未知资源类型 R.%s.%s" % (rel, kind, name))

# ---------------------------------------------------------------- 检查 4：manifest

print("[3/6] AndroidManifest 引用")
manifest_text = open(MANIFEST, encoding="utf-8").read()
try:
    manifest_root = ET.fromstring(manifest_text)
    ok("")
except ET.ParseError as e:
    bad("AndroidManifest.xml 格式错误：%s" % e)
    manifest_root = None

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def attr(elem, name):
    return elem.get(ANDROID_NS + name)


if manifest_root is not None:
    app = manifest_root.find("application")
    if app is None:
        bad("manifest 缺少 <application>")
    else:
        icon = attr(app, "icon") or ""
        m = re.match(r"@mipmap/(\w+)", icon)
        if m and m.group(1) not in mipmap_png:
            bad("application@icon 指向的 @mipmap/%s 没有传统 PNG（API 24~25 会没有图标）" % m.group(1))
        else:
            ok("")
        # 每个 alias 的 icon
        for alias in app.findall("activity-alias"):
            a_icon = attr(alias, "icon") or ""
            a_name = attr(alias, "name") or ""
            mm = re.match(r"@mipmap/(\w+)", a_icon)
            if mm:
                res = mm.group(1)
                if res not in mipmap_anydpi:
                    bad("%s 的图标 @mipmap/%s 缺少自适应图标（mipmap-anydpi-v26）" % (a_name, res))
                elif res not in mipmap_png:
                    bad("%s 的图标 @mipmap/%s 缺少传统 PNG" % (a_name, res))
                else:
                    ok("")
            if not attr(alias, "targetActivity"):
                bad("%s 没有 targetActivity" % a_name)
            else:
                ok("")

# ---------------------------------------------------------------- 检查 5：类文件存在

print("[4/6] manifest 里的组件是否有对应 Kotlin 源文件")
kt_sources = {}
for path in kt_files:
    kt_sources[os.path.splitext(os.path.basename(path))[0]] = path

if manifest_root is not None:
    app = manifest_root.find("application")
    components = []
    if app is not None:
        components += app.findall("activity")
        components += app.findall("activity-alias")
        components += app.findall("receiver")
        components += app.findall("service")
    for comp in components:
        name = (attr(comp, "name") or "").lstrip(".")
        if not name:
            continue
        short = name.split(".")[-1]
        if comp.tag == "activity-alias":
            target = (attr(comp, "targetActivity") or "").lstrip(".").split(".")[-1]
            if target and target not in kt_sources:
                bad("activity-alias %s 的 targetActivity %s 找不到对应源文件" % (name, target))
            else:
                ok("")
            continue
        if short not in kt_sources:
            bad("manifest 里的 %s 找不到对应源文件（%s.kt）" % (name, short))
        else:
            ok("")

# ---------------------------------------------------------------- 检查 6：图标完整性

print("[5/6] 图标资源完整性")
for state in ("ok", "warn", "low", "unknown"):
    name = "ic_launcher_" + state
    if name not in mipmap_anydpi:
        bad("缺少自适应图标 mipmap-anydpi-v26/%s.xml" % name)
    elif name not in mipmap_png:
        bad("缺少传统 PNG %s.png（各密度）" % name)
    else:
        ok("")

print("[6/6] 关键类清单")
required = [
    "MainActivity", "SetupActivity", "PollWorker", "Scheduler",
    "SchoolApi", "Store", "Level", "Notifier", "IconSwitcher", "PowerWidget",
    "HistoryChartView", "AutoStartHelper",
]
for cls in required:
    if cls not in kt_sources:
        bad("缺少源文件 %s.kt" % cls)
    else:
        ok("")

print("")
print("-" * 50)
print("通过 %d 项检查，发现问题 %d 个" % (checks, len(problems)))
if problems:
    print("")
    for p in problems:
        print("  - " + p)
    sys.exit(1)
print("资源校验全部通过（注意：这不等于能编译，只保证没有明显的资源引用错误）")
