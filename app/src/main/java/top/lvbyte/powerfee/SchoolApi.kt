package top.lvbyte.powerfee

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** 学校系统里的一个房间（含当前余额，可能为负） */
data class Room(
    val roomNum: String,
    val room: String,
    val building: String,
    val campus: String,
    val balance: Double?
) {
    /** 展示用名称，如「学生宿舍1号楼 1A101」 */
    val displayName: String
        get() = listOf(building, room).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { roomNum }
}

/**
 * 学校接口客户端。**零第三方依赖**（HttpURLConnection + org.json 均为平台自带）。
 */
object SchoolApi {

    private const val ENDPOINT =
        "https://yktxyk.gdppla.edu.cn/user/powerfee/getRoomInfo?from=wxminiprogram&implType=CGCOMMON0001&buyMark="

    class ApiException(message: String) : Exception(message)

    /**
     * 一次请求返回全校房间（约 2077 间、响应约 940 KB —— 所以后台查询间隔别设太短，
     * 或打开"仅 WiFi 查询"）。
     *
     * ⚠️ 请求形状必须与托盘程序一致，尤其**不要添加 Origin 头**：
     * 实测学校 WAF 见到 Origin 一律返回 403；HttpURLConnection 原生不发 Origin，
     * 这正是本 App 能在手机流量下直连的原因（浏览器则必然带 Origin，所以网页方案走不通）。
     */
    fun fetchRooms(): List<Room> {
        val text = post()
        val root = JSONObject(text)
        if (!root.optBoolean("ret")) {
            throw ApiException("接口返回失败：" + root.optString("msg").ifEmpty { "(无 msg)" })
        }
        val arr = root.optJSONArray("obj") ?: throw ApiException("返回里没有房间列表")
        val rooms = ArrayList<Room>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            rooms.add(
                Room(
                    roomNum = o.optString("roomNum"),
                    room = o.optString("room"),
                    building = o.optString("building"),
                    campus = o.optString("schoolArea"),
                    balance = o.optString("powerBalance").toDoubleOrNull()
                )
            )
        }
        if (rooms.isEmpty()) throw ApiException("房间列表为空")
        return rooms
    }

    /** 取指定房间的当前余额 */
    fun fetchBalance(roomNum: String): Double {
        val rooms = fetchRooms()
        val room = rooms.firstOrNull { it.roomNum == roomNum }
            ?: throw ApiException("房间 $roomNum 不在学校返回的列表里（可能在调整编号）")
        return room.balance ?: throw ApiException("该房间没有余额字段")
    }

    private fun post(): String {
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("User-Agent", "PowerFeeSentry/1.0")
            conn.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            conn.outputStream.use { it.write("implType=CGCOMMON0001".toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                // 403 通常意味着请求被 WAF 拦了（例如你的网络环境给请求加了 Origin）
                throw ApiException("HTTP $code" + if (code == 403) "（请求被拒绝，检查是否被加了额外请求头）" else "")
            }
            return conn.inputStream.bufferedReader().use(BufferedReader::readText)
        } finally {
            conn.disconnect()
        }
    }
}
