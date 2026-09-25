package com.playlab.headsup

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
import com.playlab.headsup.data.Prefs
import com.playlab.headsup.detection.DetectState
import com.playlab.headsup.reminder.ReminderManager
import com.playlab.headsup.service.HeadsUpService
import kotlinx.coroutines.delay
import com.playlab.headsup.ui.theme.HeadsUpTheme
import com.playlab.headsup.util.KeepAliveHelper
import com.playlab.headsup.util.PermissionHelper

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
    var sens by remember { mutableStateOf(Prefs.getSensitivity(ctx)) }
    var tick by remember { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        tick++
        // 权限后授予：传感器重注册（缺权限时注册的监听收不到事件）+ GMS 重订阅
        if (Prefs.isEnabled(ctx)) {
            HeadsUpService.reregister(ctx)
            HeadsUpService.resubscribe(ctx)
        }
    }

    // 进程重启后服务可能已死：进前台即拉起
    LaunchedEffect(Unit) {
        if (Prefs.isEnabled(ctx)) {
            try { HeadsUpService.start(ctx) } catch (_: Exception) { }
        }
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
                            if (enabled) "走路看手机时会提醒你" else "打开后在后台检测步行",
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
                    Spacer(Modifier.height(4.dp))
                    Text("灵敏度", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = sens == 0, onClick = {
                                sens = 0; Prefs.setSensitivity(ctx, 0)
                                if (enabled) HeadsUpService.start(ctx)
                            },
                            label = { Text("灵敏") },
                        )
                        FilterChip(
                            selected = sens == 1, onClick = {
                                sens = 1; Prefs.setSensitivity(ctx, 1)
                                if (enabled) HeadsUpService.start(ctx)
                            },
                            label = { Text("标准") },
                        )
                        FilterChip(
                            selected = sens == 2, onClick = {
                                sens = 2; Prefs.setSensitivity(ctx, 2)
                                if (enabled) HeadsUpService.start(ctx)
                            },
                            label = { Text("严格") },
                        )
                    }
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
                    PermRow("位置（可选）", "判断户外", PermissionHelper.hasFineLocation(ctx)) {
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
    LaunchedEffect(Unit) {
        while (true) {
            snap = DetectState.snap
            delay(1000)
        }
    }
    // 灵敏度只读显示，每秒随快照同步刷新
    val sensDesc = when (Prefs.getSensitivity(ctx)) {
        0 -> "灵敏（8步·≤2s/步）"
        2 -> "严格（16步·≤1.5s/步）"
        else -> "标准（12步·≤1.7s/步）"
    }
    // heartbeat 与 elapsedRealtime 同基准（心跳已降频至 10s，Doze 下更稀）
    val alive = snap.heartbeat > 0 &&
        android.os.SystemClock.elapsedRealtime() - snap.heartbeat < 45_000
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("检测状态", style = MaterialTheme.typography.titleMedium)
            StateRow(
                "服务",
                if (!enabled) "未开启"
                else if (!snap.screenOn) "待机（灭屏省电中）"
                else if (!snap.unlocked) "待机（锁屏中）"
                else if (snap.pocketed) "待机（口袋中）"
                else if (alive) "运行中"
                else "休眠中/未知",
            )
            // 真实提醒后 8s 内显示“已提醒”，与批量投递下整串步伐一次处理完保持同步
            val firedAgoSec = if (snap.lastTriggerAt > 0)
                (android.os.SystemClock.elapsedRealtime() - snap.lastTriggerAt) / 1000
            else Long.MAX_VALUE
            StateRow(
                "步态",
                if (snap.walking && snap.runLen >= 3) "疑似行走 · 已持续 ${snap.walkElapsedSec}s"
                else if (firedAgoSec < 8) "已提醒 · ${firedAgoSec}s前"
                else "静止",
            )
            StateRow(
                "连贯步数",
                "${snap.runLen}/${snap.runNeed}" +
                    if (snap.batched) "（批量投递）" else "（节律累计）",
            )
            StateRow("窗口步数", "${snap.stepsInWindow}（10s 窗口）")
            StateRow("屏幕", if (!snap.screenOn) "灭" else if (!snap.unlocked) "亮·锁屏" else "亮·已解锁")
            StateRow("遮挡", if (snap.pocketed) "是（口袋）" else "否")
            StateRow(
                "传感器",
                if (snap.hasStepDetector) "步伐"
                else "无步伐传感器（本机不支持检测）",
            )
            StateRow("灵敏度", sensDesc)
            StateRow("GMS 加速", snap.gms)
            // 一键拉起（正常不用点，打开页面会自动拉起）
            if (enabled && !alive) {
                OutlinedButton(
                    onClick = { HeadsUpService.start(ctx) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重启检测服务") }
            }
            OutlinedButton(
                onClick = { HeadsUpService.simulate(ctx) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !snap.simulating,
            ) {
                Text(
                    if (!enabled) "请先打开总开关再模拟"
                    else if (snap.simulating) "模拟步行中…"
                    else "模拟步行"
                )
            }
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
