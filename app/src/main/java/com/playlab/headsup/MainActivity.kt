package com.headsup.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.headsup.app.data.Prefs
import com.headsup.app.detection.DetectState
import com.headsup.app.reminder.ReminderManager
import com.headsup.app.service.HeadsUpService
import kotlinx.coroutines.delay
import com.headsup.app.ui.theme.HeadsUpTheme
import com.headsup.app.util.KeepAliveHelper
import com.headsup.app.util.PermissionHelper

/** 主界面：开关 / 提醒方式 / 权限 / 保活，Material You 单页布局 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HeadsUpTheme { HomeScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(Prefs.isEnabled(ctx)) }
    var mode by remember { mutableStateOf(Prefs.getMode(ctx)) }
    var cooldown by remember { mutableStateOf(Prefs.getCooldown(ctx)) }
    var tick by remember { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        tick++
        // 权限后授予：强制 GMS 重订阅（start 本身已节流订阅）
        if (Prefs.isEnabled(ctx)) HeadsUpService.resubscribe(ctx)
    }

    fun requestCore() {
        val list = mutableListOf(Manifest.permission.ACTIVITY_RECOGNITION)
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(list.toTypedArray())
    }

    fun applyEnabled(on: Boolean) {
        enabled = on
        Prefs.setEnabled(ctx, on)
        if (on) {
            ReminderManager.ensureChannels(ctx)
            HeadsUpService.start(ctx)
            KeepAliveHelper.scheduleKeepAlive(ctx)
        } else {
            HeadsUpService.stop(ctx)
        }
        tick++
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("看路提醒") },
                navigationIcon = {
                    FilledTonalIconButton(onClick = {}, modifier = Modifier.padding(start = 8.dp)) {
                        Icon(Icons.Default.DirectionsWalk, null)
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 状态卡
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (enabled) "守护中" else "已关闭", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (enabled) "走路玩手机时会提醒你" else "打开后在后台检测步行",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = {
                        if (it && !PermissionHelper.coreGranted(ctx)) requestCore()
                        applyEnabled(it)
                    })
                }
            }

            // 提醒方式
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("提醒方式", style = MaterialTheme.typography.titleMedium)
                    ModeRow("浮动通知", "顶部横幅，不打断操作", Prefs.MODE_FLOAT, mode) {
                        mode = it; Prefs.setMode(ctx, it); tick++
                    }
                    ModeRow("弹窗提醒", "悬浮窗卡片，需悬浮窗权限", Prefs.MODE_POPUP, mode) {
                        mode = it; Prefs.setMode(ctx, it)
                        if (!PermissionHelper.hasOverlay(ctx)) {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                data = Uri.parse("package:${ctx.packageName}")
                            })
                        }
                        tick++
                    }
                    ModeRow("全屏提醒", "强制全屏打断，效果最强", Prefs.MODE_FULL, mode) {
                        mode = it; Prefs.setMode(ctx, it); tick++
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("提醒间隔：${cooldown}秒", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = cooldown.toFloat(),
                        onValueChange = {
                            cooldown = it.toInt()
                            Prefs.setCooldown(ctx, it.toInt())
                        },
                        valueRange = 30f..300f, steps = 8
                    )
                    Button(onClick = { ReminderManager.test(ctx) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Notifications, null)
                        Spacer(Modifier.width(8.dp))
                        Text("测试提醒")
                    }
                }
            }

            // 检测状态（实时）
            DetectStatusCard(enabled)

            // 权限
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("权限", style = MaterialTheme.typography.titleMedium)
                    PermRow("身体活动", "检测步行/上下楼", PermissionHelper.hasActivity(ctx)) { requestCore() }
                    PermRow("通知", "发送提醒必备", PermissionHelper.hasNotification(ctx)) {
                        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                        ) else KeepAliveHelper.openAppSettings(ctx)
                    }
                    PermRow("位置（可选）", "判断户外，对标原版", PermissionHelper.hasFineLocation(ctx)) {
                        permLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                    if (mode == Prefs.MODE_POPUP)
                        PermRow("悬浮窗", "弹窗提醒必备", PermissionHelper.hasOverlay(ctx)) {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                data = Uri.parse("package:${ctx.packageName}")
                            })
                        }
                    @Suppress("unused") val _tick = tick // 订阅刷新
                }
            }

            // 保活
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("自启与保活", style = MaterialTheme.typography.titleMedium)
                    Text(
                        KeepAliveHelper.deviceHint(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { KeepAliveHelper.openAutoStart(ctx) },
                            modifier = Modifier.weight(1f)
                        ) { Text("自启动设置") }
                        OutlinedButton(
                            onClick = { KeepAliveHelper.requestBatteryWhitelist(ctx) },
                            modifier = Modifier.weight(1f),
                            enabled = !KeepAliveHelper.ignoringBattery(ctx)
                        ) { Text(if (KeepAliveHelper.ignoringBattery(ctx)) "已忽略电池优化" else "电池白名单") }
                    }
                }
            }

            Text(
                "提醒不能替代注意力，走路时请尽量少看手机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** 实时检测状态卡：每秒刷新服务心跳/步态/传感器/GMS 状态 */
@Composable
private fun DetectStatusCard(enabled: Boolean) {
    val ctx = LocalContext.current
    var snap by remember { mutableStateOf(DetectState.snap) }
    var sens by remember { mutableStateOf(Prefs.getSensitivity(ctx)) }
    LaunchedEffect(Unit) {
        while (true) {
            snap = DetectState.snap
            delay(1000)
        }
    }
    // heartbeat 与 elapsedRealtime 同基准
    val alive = snap.heartbeat > 0 &&
        android.os.SystemClock.elapsedRealtime() - snap.heartbeat < 15_000
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("检测状态", style = MaterialTheme.typography.titleMedium)
            StateRow("服务", if (!enabled) "未开启" else if (alive) "运行中" else "异常（被系统杀死？）")
            StateRow(
                "步态",
                if (snap.walking) "疑似行走 · 已持续 ${snap.walkElapsedSec}s" else "静止",
            )
            StateRow("连贯步数", "${snap.runLen}/${snap.runNeed}（节律一致才累计）")
            StateRow("窗口步数", "${snap.stepsInWindow}（10s 窗口，仅参考）")
            StateRow("屏幕", if (snap.screenOn) "亮（用机中）" else "灭")
            StateRow(
                "传感器",
                buildString {
                    if (snap.hasStepDetector) append("步伐 ")
                    if (snap.hasStepCounter) append("计步 ")
                    if (snap.accelOn) append("加速度")
                    if (isEmpty()) append("无（每秒刷新，服务运行时才有值）")
                },
            )
            StateRow("GMS 加速", snap.gms)
            Spacer(Modifier.height(4.dp))
            Text("灵敏度", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = sens == 0, onClick = {
                        sens = 0; Prefs.setSensitivity(ctx, 0)
                        if (enabled) HeadsUpService.start(ctx)
                    },
                    label = { Text("快速·约8s") },
                )
                FilterChip(
                    selected = sens == 1, onClick = {
                        sens = 1; Prefs.setSensitivity(ctx, 1)
                        if (enabled) HeadsUpService.start(ctx)
                    },
                    label = { Text("标准·约12s") },
                )
            }
            OutlinedButton(
                onClick = { HeadsUpService.simulate(ctx) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
            ) { Text(if (enabled) "模拟步行（走真实检测通路）" else "先打开总开关再模拟") }
        }
    }
}

@Composable
private fun StateRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ModeRow(title: String, desc: String, value: Int, current: Int, onPick: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .selectable(selected = current == value, onClick = { onPick(value) })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = current == value, onClick = { onPick(value) })
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title)
            Text(
                desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PermRow(title: String, desc: String, ok: Boolean, onFix: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ok) Icons.Default.CheckCircle else Icons.Default.Warning, null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!ok) TextButton(onClick = onFix) { Text("去开启") }
        else Text(
            "已允许", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
