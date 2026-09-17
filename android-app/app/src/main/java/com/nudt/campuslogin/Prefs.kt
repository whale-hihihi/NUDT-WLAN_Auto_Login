package com.nudt.campuslogin

import android.content.Context

/** 配置存储(SharedPreferences) + 应用内日志环形缓冲 */
object Prefs {
    const val DEFAULT_PORTAL = "http://10.20.69.103"
    const val DEFAULT_ACID = "3"

    data class Config(
        val username: String,
        val password: String,
        val portalBase: String,
        val acId: String,
        val autoEnabled: Boolean,
    )

    fun ready(ctx: Context): Boolean = read(ctx).let { it.username.isNotEmpty() && it.password.isNotEmpty() }

    fun read(ctx: Context): Config {
        val sp = ctx.getSharedPreferences("campus_login", Context.MODE_PRIVATE)
        return Config(
            username = sp.getString("username", "") ?: "",
            password = sp.getString("password", "") ?: "",
            portalBase = sp.getString("portal_base", DEFAULT_PORTAL) ?: DEFAULT_PORTAL,
            acId = sp.getString("ac_id", DEFAULT_ACID) ?: DEFAULT_ACID,
            autoEnabled = sp.getBoolean("auto_enabled", false),
        )
    }

    fun save(ctx: Context, cfg: Config) {
        ctx.getSharedPreferences("campus_login", Context.MODE_PRIVATE).edit()
            .putString("username", cfg.username)
            .putString("password", cfg.password)
            .putString("portal_base", cfg.portalBase)
            .putString("ac_id", cfg.acId)
            .putBoolean("auto_enabled", cfg.autoEnabled)
            .apply()
    }

    // ---------------- 应用内日志(内存环形 + 落盘尾部) ----------------

    private val logBuffer = ArrayDeque<String>(200)
    private val lock = Any()

    fun log(ctx: Context, msg: String) {
        val line = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date()) + " " + msg
        synchronized(lock) {
            if (logBuffer.size >= 200) logBuffer.removeFirst()
            logBuffer.addLast(line)
            try {
                ctx.getFileStreamPath("login.log").let { }
                ctx.openFileOutput("login.log", Context.MODE_APPEND).use {
                    it.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) {
            }
        }
    }

    fun logs(): List<String> = synchronized(lock) { logBuffer.toList() }
}
