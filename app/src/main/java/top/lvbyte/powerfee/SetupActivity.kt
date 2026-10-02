package top.lvbyte.powerfee

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * 设置界面：首次进入时拉取全校房间列表，让用户用三级下拉选自己的房间；
 * 之后用于修改阈值、查询间隔与提醒方式。
 *
 * 拉取房间列表要下载约 940 KB，必须放在子线程（否则会 ANR）。
 */
class SetupActivity : Activity() {

    private lateinit var store: Store

    private lateinit var campusSpinner: Spinner
    private lateinit var buildingSpinner: Spinner
    private lateinit var roomSpinner: Spinner
    private lateinit var thresholdInput: EditText
    private lateinit var intervalSpinner: Spinner
    private lateinit var wifiOnlyCheck: CheckBox
    private lateinit var notifyWarnCheck: CheckBox
    private lateinit var notifyRecoveryCheck: CheckBox
    private lateinit var dynamicIconCheck: CheckBox
    private lateinit var statusText: TextView
    private lateinit var reloadBtn: Button
    private lateinit var saveBtn: Button

    private var rooms: List<Room> = emptyList()
    private var campuses: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        store = Store(this)

        campusSpinner = findViewById(R.id.campusSpinner)
        buildingSpinner = findViewById(R.id.buildingSpinner)
        roomSpinner = findViewById(R.id.roomSpinner)
        thresholdInput = findViewById(R.id.thresholdInput)
        intervalSpinner = findViewById(R.id.intervalSpinner)
        wifiOnlyCheck = findViewById(R.id.wifiOnlyCheck)
        notifyWarnCheck = findViewById(R.id.notifyWarnCheck)
        notifyRecoveryCheck = findViewById(R.id.notifyRecoveryCheck)
        dynamicIconCheck = findViewById(R.id.dynamicIconCheck)
        statusText = findViewById(R.id.statusText)
        reloadBtn = findViewById(R.id.reloadBtn)
        saveBtn = findViewById(R.id.saveBtn)

        thresholdInput.setText(String.format(Locale.US, "%.1f", store.threshold))
        wifiOnlyCheck.isChecked = store.wifiOnly
        notifyWarnCheck.isChecked = store.notifyWarn
        notifyRecoveryCheck.isChecked = store.notifyRecovery
        dynamicIconCheck.isChecked = store.dynamicIcon

        val intervalLabels = Scheduler.INTERVAL_OPTIONS.map { "$it 分钟" }
        intervalSpinner.adapter = simpleAdapter(intervalLabels)
        val currentIndex = Scheduler.INTERVAL_OPTIONS.indexOf(store.intervalMinutes)
        if (currentIndex >= 0) intervalSpinner.setSelection(currentIndex)

        campusSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                fillBuildings()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        buildingSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                fillRooms()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

        reloadBtn.setOnClickListener { loadRooms() }
        saveBtn.setOnClickListener { save() }

        loadRooms()
    }

    // ---------------- 房间列表 ----------------

    private fun loadRooms() {
        statusText.text = "正在从学校接口读取房间列表（约 940 KB）…"
        reloadBtn.isEnabled = false
        saveBtn.isEnabled = false

        Thread {
            try {
                val fetched = SchoolApi.fetchRooms()
                runOnUiThread {
                    rooms = fetched
                    campuses = fetched.map { it.campus.ifEmpty { "未标注校区" } }.distinct().sorted()
                    campusSpinner.adapter = simpleAdapter(campuses)
                    statusText.text = "共读取到 ${fetched.size} 个房间，请依次选择校区 / 楼栋 / 房间"
                    reloadBtn.isEnabled = true
                    saveBtn.isEnabled = true
                    restoreSelection()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "读取失败：" + (e.message ?: e.toString()) +
                        "\n\n可能原因：网络不通、或请求被学校 WAF 拒绝。可点「重新读取」重试。"
                    reloadBtn.isEnabled = true
                    saveBtn.isEnabled = false
                }
            }
        }.start()
    }

    /** 已经设置过的话，尽量把下拉框定位回原来的选择 */
    private fun restoreSelection() {
        if (store.roomNum.isEmpty()) return
        val campus = store.campusName
        val building = store.buildingName
        val ci = campuses.indexOf(campus)
        if (ci >= 0) campusSpinner.setSelection(ci)
        fillBuildings()
        val bi = currentBuildings().indexOf(building)
        if (bi >= 0) buildingSpinner.setSelection(bi)
        fillRooms()
        val ri = currentRoomsInBuilding().indexOfFirst { it.roomNum == store.roomNum }
        if (ri >= 0) roomSpinner.setSelection(ri)
    }

    private fun currentBuildings(): List<String> {
        val campus = campusSpinner.selectedItem?.toString() ?: return emptyList()
        return rooms.filter { it.campus.ifEmpty { "未标注校区" } == campus }
            .map { it.building.ifEmpty { "未标注楼栋" } }
            .distinct()
            .sorted()
    }

    private fun currentRoomsInBuilding(): List<Room> {
        val campus = campusSpinner.selectedItem?.toString() ?: return emptyList()
        val building = buildingSpinner.selectedItem?.toString() ?: return emptyList()
        return rooms.filter {
            it.campus.ifEmpty { "未标注校区" } == campus && it.building.ifEmpty { "未标注楼栋" } == building
        }.sortedBy { it.room }
    }

    private fun fillBuildings() {
        val buildings = currentBuildings()
        buildingSpinner.adapter = simpleAdapter(buildings)
        fillRooms()
    }

    private fun fillRooms() {
        val list = currentRoomsInBuilding()
        val labels = list.map { room ->
            val balance = room.balance?.let { String.format(Locale.US, "%.1f 度", it) } ?: "—"
            "${room.room}（$balance）"
        }
        roomSpinner.adapter = simpleAdapter(labels)
    }

    private fun simpleAdapter(labels: List<String>): ArrayAdapter<String> {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return adapter
    }

    // ---------------- 保存 ----------------

    private fun save() {
        val room = currentRoomsInBuilding().getOrNull(roomSpinner.selectedItemPosition)
        if (room == null) {
            toast("请先选择房间")
            return
        }

        val threshold = thresholdInput.text.toString().trim().toDoubleOrNull()
        if (threshold == null || threshold <= 0.0 || threshold > 9999.0) {
            toast("提醒阈值请填 0.1 ~ 9999 之间的数字")
            return
        }

        store.roomNum = room.roomNum
        store.campusName = room.campus
        store.buildingName = room.building
        store.roomName = room.displayName
        store.threshold = threshold
        store.intervalMinutes = Scheduler.INTERVAL_OPTIONS[intervalSpinner.selectedItemPosition.coerceIn(0, Scheduler.INTERVAL_OPTIONS.size - 1)]
        store.wifiOnly = wifiOnlyCheck.isChecked
        store.notifyWarn = notifyWarnCheck.isChecked
        store.notifyRecovery = notifyRecoveryCheck.isChecked
        store.dynamicIcon = dynamicIconCheck.isChecked

        Scheduler.schedule(this)
        Scheduler.runNow(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        // 保存后顺手把图标切到当前状态（避免刚设完还是默认绿色而数据是低电量）
        IconSwitcher.apply(this, store.lastLevel)
        PowerWidget.updateAll(this)

        toast("已保存，开始后台监控")
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    private fun openBatterySettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (e: Exception) {
            toast("请手动到系统设置 → 电池 → 应用耗电管理 里允许本应用后台运行")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
