package com.nudt.campuslogin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

/** 纯状态检测(IO线程调用), 返回给人看的一句话 */
private fun statusCheck(ctx: android.content.Context, portal: String): String {
    val wifi = SrunApi.findWifiNetwork(ctx)
        ?: return "WiFi 未连接"
    SrunApi.wifiNetwork = wifi  // 绑定 WiFi, 避免走移动数据误判
    return if (SrunApi.wifiValidated(ctx) == true && SrunApi.isInternetOk()) {
        "WiFi 已联网 ✓"
    } else {
        val ip = SrunApi.localIp()
        if (ip == null) {
            "WiFi 已连接但无 IPv4"
        } else {
            val portalReachable = try {
                SrunApi.radUserInfo(portal)
                true
            } catch (e: Exception) {
                false
            }
            if (!portalReachable) {
                "门户不可达, 不在校园网"
            } else {
                val online = SrunApi.parseOnline(SrunApi.radUserInfo(portal))
                if (online != null) "已认证(${online.username}) 但外网未通"
                else "已连校园网, 未认证 → 可点立即登录"
            }
        }
    }
}

@Composable
fun App() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val cfg = remember { Prefs.read(ctx) }

    var username by remember { mutableStateOf(cfg.username) }
    var password by remember { mutableStateOf(cfg.password) }
    var portal by remember { mutableStateOf(cfg.portalBase) }
    var acId by remember { mutableStateOf(cfg.acId) }
    var showPwd by remember { mutableStateOf(false) }
    var autoEnabled by remember { mutableStateOf(cfg.autoEnabled) }

    var statusText by remember { mutableStateOf("点击\"检测状态\"查看") }
    var busy by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(Prefs.logs()) }

    // 界面可见期间每 2 秒刷新日志
    DisposableEffect(Unit) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                logs = Prefs.logs()
                handler.postDelayed(this, 2000)
            }
        }
        handler.postDelayed(r, 2000)
        onDispose { handler.removeCallbacks(r) }
    }

    fun doAction(label: String, block: suspend () -> String) {
        busy = true
        statusText = "$label ..."
        scope.launch {
            val msg = withContext(Dispatchers.IO) { block() }
            statusText = msg
            logs = Prefs.logs()
            busy = false
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("校园网自动登录", style = MaterialTheme.typography.headlineSmall)

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("状态", style = MaterialTheme.typography.titleMedium)
                        Text(statusText, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    doAction("检测中") {
                                        Prefs.log(ctx, "手动检测")
                                        statusCheck(ctx, portal)
                                    }
                                },
                            ) { Text("检测状态") }
                            Button(
                                enabled = !busy,
                                onClick = {
                                    doAction("登录中") {
                                        LoginEngine(ctx).ensureOnline("手动登录").message
                                    }
                                },
                            ) { Text("立即登录") }
                        }
                    }
                }

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("账号") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = if (showPwd) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        TextButton(onClick = { showPwd = !showPwd }) {
                            Text(if (showPwd) "隐藏" else "显示")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = portal,
                        onValueChange = { portal = it },
                        label = { Text("认证服务器") },
                        singleLine = true,
                        modifier = Modifier.weight(2f),
                    )
                    OutlinedTextField(
                        value = acId,
                        onValueChange = { acId = it },
                        label = { Text("ac_id") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Button(
                    enabled = !busy,
                    onClick = {
                        Prefs.save(ctx, Prefs.Config(username, password, portal, acId, autoEnabled))
                        if (autoEnabled) LoginWorker.setAutoEnabled(ctx, true)
                        statusText = "配置已保存"
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存配置") }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = autoEnabled,
                        onCheckedChange = { on ->
                            autoEnabled = on
                            Prefs.save(ctx, Prefs.Config(username, password, portal, acId, on))
                            LoginWorker.setAutoEnabled(ctx, on)
                            Prefs.log(ctx, if (on) "自动登录已开启" else "自动登录已关闭")
                        },
                    )
                    Column(Modifier.padding(start = 8.dp)) {
                        Text("自动登录(断线/开机自动重连)")
                        Text(
                            "开启后建议在系统设置允许本应用自启动和后台运行",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                HorizontalDivider()
                Text("日志", style = MaterialTheme.typography.titleMedium)
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        if (logs.isEmpty()) {
                            Text("暂无日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            logs.takeLast(30).forEach { line ->
                                Text(
                                    line,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = 15.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
