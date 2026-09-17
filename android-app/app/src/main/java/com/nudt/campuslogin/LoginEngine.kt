package com.nudt.campuslogin

import android.content.Context

/**
 * 登录编排: 与 PC 版 campus_login.py 同一套判定逻辑。
 * 单次 ensureOnline(): 已联网→跳过; 门户不可达→不在校园网; 未认证→登录→复查。
 * 由 UI 手动按钮 / WorkManager 后台任务 共同调用, 自身无常驻状态。
 */
class LoginEngine(private val ctx: Context) {

    enum class State { CHECKING, ONLINE, LOGGED_IN, AUTHED_NO_NET, NOT_ON_CAMPUS, NO_CONFIG, FAILED }

    data class Result(val state: State, val message: String)

    /** 单轮检测+登录(最多 attempts 次, 门户不可达时给网络一点就绪时间) */
    fun ensureOnline(reason: String, attempts: Int = 2): Result {
        val cfg = Prefs.read(ctx)
        if (cfg.username.isEmpty() || cfg.password.isEmpty()) {
            return Result(State.NO_CONFIG, "未配置账号密码")
        }
        log("触发[$reason] 检测开始")

        // 绑定 WiFi 网络: 避免移动数据在时误判/请求走蜂窝
        val wifi = SrunApi.findWifiNetwork(ctx)
        if (wifi == null) {
            log("无 WiFi 连接, 跳过")
            return Result(State.NOT_ON_CAMPUS, "WiFi 未连接")
        }
        SrunApi.wifiNetwork = wifi

        // 1. WiFi 已被系统验证有互联网 → 结束(不看蜂窝, 蜂窝有网不代表校园网通了)
        if (SrunApi.wifiValidated(ctx) == true && SrunApi.isInternetOk()) {
            log("WiFi 已联网, 无需操作")
            return Result(State.ONLINE, "已联网")
        }

        var lastMsg = ""
        repeat(attempts) { attempt ->
            try {
                val ip = SrunApi.localIp()
                if (ip == null) {
                    lastMsg = "无 IPv4 地址"
                    log("$lastMsg, 等待重试(${attempt + 1}/$attempts)")
                    Thread.sleep(3000)
                    return@repeat
                }
                val raw = SrunApi.radUserInfo(cfg.portalBase)
                val online = SrunApi.parseOnline(raw)
                if (online != null) {
                    // 门户显示已在线但204不通, 可能刚登录完网络未生效
                    log("门户显示已在线(${online.username}), 但外网未通")
                    return Result(State.AUTHED_NO_NET, "已认证但外网未通, 等待网络生效")
                }
                // 2. 未认证 → 登录
                val res = SrunApi.login(cfg.portalBase, cfg.username, cfg.password, ip, cfg.acId)
                val ok = res.optString("error") == "ok"
                val msg = res.optString("suc_msg").ifEmpty { res.optString("error") }
                log("登录响应: $msg (ecode=${res.optString("ecode")})")
                if (ok) {
                    Thread.sleep(1500)
                    return if (SrunApi.isInternetOk()) {
                        log("登录成功, 已联网")
                        Result(State.LOGGED_IN, "自动登录成功")
                    } else {
                        log("登录返回ok但外网未通")
                        Result(State.AUTHED_NO_NET, "已提交, 等待网络生效")
                    }
                }
                lastMsg = "登录失败: $msg"
                Thread.sleep(3000)
            } catch (e: Exception) {
                // 门户不可达 = 不在校园网(WiFi没连上/在别处), 静默结束
                lastMsg = "门户不可达或网络异常: ${e.message?.take(80)}"
                if (attempt < attempts - 1) Thread.sleep(4000)
            }
        }
        val notOnCampus = !portalReachableQuick(cfg.portalBase)
        return if (notOnCampus) {
            log("门户不可达, 不在校园网, 跳过")
            Result(State.NOT_ON_CAMPUS, "不在校园网")
        } else {
            log(lastMsg)
            Result(State.FAILED, lastMsg)
        }
    }

    private fun portalReachableQuick(base: String): Boolean = try {
        SrunApi.radUserInfo(base).let { true }
    } catch (_: Exception) {
        false
    }

    fun log(msg: String) = Prefs.log(ctx, msg)
}
