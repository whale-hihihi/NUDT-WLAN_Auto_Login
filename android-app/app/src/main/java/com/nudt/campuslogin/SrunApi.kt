package com.nudt.campuslogin

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/** 深澜门户 HTTP 协议层, 与 PC 版 srun.py 行为一致。仅用 HttpURLConnection。 */
object SrunApi {

    /** 国内 generate_204 联网探测点(微软探测点在校园网内不可达) */
    val CHECK_URLS = listOf(
        "http://connect.rom.miui.com/generate_204",
        "http://wifi.vivo.com.cn/generate_204"
    )

    private const val UA = "Mozilla/5.0 (Linux; Android) CampusAutoLogin/1.0"

    /**
     * WiFi 网络绑定: 手机常同时开移动数据, 系统默认网络可能是蜂窝,
     * 不绑定的话探测/登录会走到 5G, 导致误判"已联网"或"门户不可达"。
     * 每次操作前由 LoginEngine 设为当前 WiFi 网络。
     */
    @Volatile
    var wifiNetwork: android.net.Network? = null

    fun findWifiNetwork(ctx: android.content.Context): android.net.Network? {
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) return net
        }
        return null
    }

    /** WiFi 是否已被系统判定有互联网(VALIDATED); 无 WiFi 返回 null。忽略蜂窝状态。 */
    fun wifiValidated(ctx: android.content.Context): Boolean? {
        val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }
        return null
    }

    fun httpGet(url: String, timeoutMs: Int = 6000): Pair<Int, String> {
        val conn = (wifiNetwork?.openConnection(URL(url)) ?: URL(url).openConnection())
            as HttpURLConnection
        return try {
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", UA)
            val code = conn.responseCode
            val body = (if (code in 200..399) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            code to body
        } finally {
            conn.disconnect()
        }
    }

    /** 去掉 JSONP 包裹 */
    private fun parseJsonp(text: String): JSONObject {
        val trimmed = text.trim()
        val m = Regex("\\((\\{.*\\})\\)\\s*;?\\s*$").find(trimmed)
        return JSONObject(m?.groupValues?.get(1) ?: trimmed)
    }

    private fun urlEncode(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    fun getChallenge(base: String, username: String, ip: String): String {
        val qs = "callback=sdf&username=${urlEncode(username)}&ip=${urlEncode(ip)}"
        val (_, body) = httpGet("$base/cgi-bin/get_challenge?$qs")
        val res = parseJsonp(body)
        if (res.optString("error") != "ok" || res.optString("challenge").isEmpty()) {
            throw RuntimeException("get_challenge 失败: $res")
        }
        return res.getString("challenge")
    }

    /** 登录; 返回门户 JSON 响应 */
    fun login(base: String, username: String, password: String, ip: String, acId: String): JSONObject {
        val token = getChallenge(base, username, ip)
        val hmd5 = SrunCrypto.hmacMd5Hex(password, token)
        val info = SrunCrypto.encodeInfo(username, password, ip, acId, token)
        val chk = SrunCrypto.sha1Hex(
            token + username + token + hmd5 + token + acId + token + ip +
                token + "200" + token + "1" + token + info
        )
        val params = listOf(
            "callback" to "sdf",
            "action" to "login",
            "username" to username,
            "password" to "{MD5}$hmd5",
            "os" to "Android",
            "name" to "Android",
            "double_stack" to "0",
            "chksum" to chk,
            "info" to info,
            "ac_id" to acId,
            "ip" to ip,
            "n" to "200",
            "type" to "1"
        ).joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
        val (_, body) = httpGet("$base/cgi-bin/srun_portal?$params", 8000)
        return parseJsonp(body)
    }

    fun logout(base: String, username: String, ip: String, acId: String): JSONObject {
        val qs = "callback=sdf&action=logout&username=${urlEncode(username)}" +
            "&ip=${urlEncode(ip)}&ac_id=${urlEncode(acId)}"
        val (_, body) = httpGet("$base/cgi-bin/srun_portal?$qs")
        return parseJsonp(body)
    }

    /** rad_user_info: 已认证返回 CSV(首字段用户名, 第9字段IP), 未认证返回空 */
    fun radUserInfo(base: String): String {
        val (_, body) = httpGet("$base/cgi-bin/rad_user_info")
        return body.trim()
    }

    data class OnlineInfo(val username: String, val onlineIp: String)

    fun parseOnline(text: String?): OnlineInfo? {
        if (text.isNullOrEmpty()) return null
        val parts = text.split(",")
        if (parts.size >= 9 && parts[0].isNotEmpty()) {
            return OnlineInfo(parts[0], parts[8])
        }
        return null
    }

    /** 任一探测点返回 204 即已联网 */
    fun isInternetOk(timeoutMs: Int = 4000): Boolean {
        for (url in CHECK_URLS) {
            try {
                if (httpGet(url, timeoutMs).first == 204) return true
            } catch (_: Exception) {
            }
        }
        return false
    }

    /** 本机 IPv4(wlan 网卡的私网地址), 找不到返回 null */
    fun localIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { nif -> nif.interfaceAddresses.asSequence().map { nif to it } }
                .filter { (_, addr) -> addr.address is Inet4Address }
                .map { (_, addr) -> addr.address.hostAddress ?: "" }
                .firstOrNull { it.startsWith("10.") || it.startsWith("192.168.") || it.startsWith("172.") }
        } catch (_: Exception) {
            null
        }
    }
}
