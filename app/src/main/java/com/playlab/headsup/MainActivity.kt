package com.playlab.headsup

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.playlab.headsup.data.Prefs
import com.playlab.headsup.detection.DetectState
import com.playlab.headsup.reminder.ReminderManager
import com.playlab.headsup.service.HeadsUpService
import kotlinx.coroutines.delay
import com.playlab.headsup.ui.theme.HeadsUpTheme
import com.playlab.headsup.util.IndoorDetector
import com.playlab.headsup.util.KeepAliveHelper
import com.playlab.headsup.util.PermissionHelper

/** 主界面：开关 / 提醒方式 / 权限 / 保活，Material You 单页布局 */
class MainActivity : ComponentActivity() {
    private var resumeSeq by mutableIntStateOf(0)

    // 每次回到前台自增，Compose 侧跟随重读系统权限状态
    override fun onResume() {
        super.onResume()
        resumeSeq++
        IndoorDetector.setUiOpen(true) // 主页：GPS 实时追踪，卫星信息实时更新
    }

    override fun onPause() {
        IndoorDetector.setUiOpen(false) // 离开主页：GPS 转后台占空采样
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HeadsUpTheme { HomeScreen(resumeSeq) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(resumeSeq: Int) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(Prefs.isEnabled(ctx)) }
    var mode by remember { mutableStateOf(Prefs.getMode(ctx)) }
    var cooldown by remember { mutableStateOf(Prefs.getCooldown(ctx)) }
    var sens by remember { mutableStateOf(Prefs.getSensitivity(ctx)) }
    var indoorMute by remember { mutableStateOf(Prefs.isIndoorMute(ctx)) }
    var pendingIndoor by remember { mutableStateOf(false) } // 想开但权限不足，授权后自动补开
    var pendingEnable by remember { mutableStateOf(false) } // 想开守护但缺核心权限，等授权结果
    var pendingPopup by remember { mutableStateOf(false) } // 想切弹窗但缺悬浮窗权限，授权后才切换
    var showLocDialog by remember { mutableStateOf(false) } // 引导去开位置权限
    var showCoreDialog by remember { mutableStateOf(false) } // 引导去开身体活动/通知
    var tick by remember { mutableIntStateOf(0) }

    // 系统不再弹窗（不再询问）则直接引导，免得点击无反应
    fun requestRuntime(
        checkPerms: Array<String>,
        onDead: () -> Unit,
        doLaunch: () -> Unit,
    ) {
        val act = ctx as? Activity
        val missing = checkPerms.filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty() && act != null &&
            missing.all { Prefs.wasAsked(ctx, it) && !ActivityCompat.shouldShowRequestPermissionRationale(act, it) }
        ) {
            onDead()
        } else {
            missing.forEach { Prefs.markAsked(ctx, it) }
            doLaunch()
        }
    }

    // 后台位置还能否弹出系统授权（API29 并申后判断用）
    fun bgRationale(): Boolean {
        val act = ctx as? Activity ?: return true
        return ActivityCompat.shouldShowRequestPermissionRationale(
            act, Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
    }

    // 开关落盘与服务启停（回调里也要用，声明在 launcher 之前）
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

    // 统一刷新：权限以系统为准，室内/守护开关跟随权限
    fun refreshAll() {
        enabled = Prefs.isEnabled(ctx)
        mode = Prefs.getMode(ctx)
        cooldown = Prefs.getCooldown(ctx)
        sens = Prefs.getSensitivity(ctx)
        val enough = PermissionHelper.isLocationEnough(ctx)
        var m = Prefs.isIndoorMute(ctx)
        if (m && !enough) { m = false; Prefs.setIndoorMute(ctx, false) } // 权限被收回则跟随关闭
        if (pendingIndoor && enough) {
            m = true; Prefs.setIndoorMute(ctx, true); pendingIndoor = false
        }
        indoorMute = m
        // 悬浮窗：设置页回来已授权才切到弹窗，否则保持原选项
        if (pendingPopup) {
            pendingPopup = false
            if (PermissionHelper.hasOverlay(ctx)) {
                mode = Prefs.MODE_POPUP; Prefs.setMode(ctx, Prefs.MODE_POPUP)
            }
        }
        // 设置页回来补开守护
        if (pendingEnable && PermissionHelper.coreGranted(ctx)) {
            pendingEnable = false; applyEnabled(true)
        }
        tick++
        if (Prefs.isEnabled(ctx)) {
            try { HeadsUpService.start(ctx) } catch (_: Exception) { }
        }
    }

    // 系统设置页返回立即刷新（悬浮窗/电池/应用详情页靠它，onResume 兜底）
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshAll() }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // 守护开关跟随核心权限：拒绝则保持关闭，不先开
        if (pendingEnable) {
            pendingEnable = false
            if (PermissionHelper.coreGranted(ctx)) applyEnabled(true)
            else applyEnabled(false)
        }
        tick++
        // 权限后授予：传感器重注册（缺权限时注册的监听收不到事件）+ GMS 重订阅
        if (Prefs.isEnabled(ctx)) {
            HeadsUpService.reregister(ctx)
            HeadsUpService.resubscribe(ctx)
        }
        // 室内开关跟随权限：够用即开；29 并申后仍缺后台看系统能力；拒绝则引导
        if (pendingIndoor) {
            if (PermissionHelper.isLocationEnough(ctx)) {
                indoorMute = true; Prefs.setIndoorMute(ctx, true); pendingIndoor = false
                if (Prefs.isEnabled(ctx)) HeadsUpService.start(ctx)
            } else if (!PermissionHelper.hasForegroundLocation(ctx)) {
                indoorMute = false; Prefs.setIndoorMute(ctx, false)
                showLocDialog = true
            } else if (Build.VERSION.SDK_INT == 29 && !bgRationale()) {
                // Q 允许一次并申，系统不给后台入口即视为给足：前台够用，不再打扰
                Prefs.setLocationCompat(ctx, true)
                indoorMute = true; Prefs.setIndoorMute(ctx, true); pendingIndoor = false
                if (Prefs.isEnabled(ctx)) HeadsUpService.start(ctx)
            } else {
                indoorMute = false; Prefs.setIndoorMute(ctx, false)
                showLocDialog = true
            }
        } else if (Prefs.isIndoorMute(ctx) && !PermissionHelper.isLocationEnough(ctx)) {
            indoorMute = false; Prefs.setIndoorMute(ctx, false)
        } else {
            indoorMute = Prefs.isIndoorMute(ctx)
        }
    }

    // 进程重启后服务可能已死：进前台即拉起
    LaunchedEffect(Unit) {
        if (Prefs.isEnabled(ctx)) {
            try { HeadsUpService.start(ctx) } catch (_: Exception) { }
        }
    }

    // 每次回到前台重读权限：去设置页改完/仅此次过期都要跟随
    LaunchedEffect(resumeSeq) {
        if (resumeSeq > 0) refreshAll()
    }

    fun requestCore() {
        val perms = buildList {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        requestRuntime(perms, onDead = { showCoreDialog = true }) {
            permLauncher.launch(perms)
        }
    }

    // 位置申请：Q 允许前后台一次并申（弹窗可直授始终允许）；30+ 先前台，后台走设置
    fun requestLocation() {
        if (Build.VERSION.SDK_INT == 29) {
            requestRuntime(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                onDead = { showLocDialog = true }
            ) {
                // 后台随前台并申，后果在回调里按系统能力判定
                Prefs.markAsked(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                permLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    )
                )
            }
        } else if (!PermissionHelper.hasForegroundLocation(ctx)) {
            requestRuntime(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                onDead = { showLocDialog = true }
            ) {
                permLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
        } else if (!PermissionHelper.hasBackgroundLocation(ctx)) {
            showLocDialog = true
        }
    }

    // 室内勾选跟随权限：够用直接开，否则只走申请流程，不落盘开启
    fun onIndoorCheck(want: Boolean) {
        if (!want) {
            pendingIndoor = false
            indoorMute = false
            Prefs.setIndoorMute(ctx, false)
            if (enabled) HeadsUpService.start(ctx)
            return
        }
        if (PermissionHelper.isLocationEnough(ctx)) {
            indoorMute = true
            Prefs.setIndoorMute(ctx, true)
            if (enabled) HeadsUpService.start(ctx)
        } else {
            pendingIndoor = true
            requestLocation()
        }
    }

    // 守护开关跟随核心权限：缺权限只走申请，不先开
    fun onEnabledCheck(want: Boolean) {
        if (!want) {
            pendingEnable = false
            applyEnabled(false)
            return
        }
        if (PermissionHelper.coreGranted(ctx)) applyEnabled(true)
        else {
            pendingEnable = true
            requestCore()
        }
    }

    fun overlayIntent() = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
        data = Uri.parse("package:${ctx.packageName}")
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
                    Switch(checked = enabled, onCheckedChange = { onEnabledCheck(it) })
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
                        if (PermissionHelper.hasOverlay(ctx)) {
                            mode = it; Prefs.setMode(ctx, it)
                        } else {
                            // 无权限只跳设置，不切换选项，回来授权后才切
                            pendingPopup = true
                            try {
                                settingsLauncher.launch(overlayIntent())
                            } catch (_: Exception) { pendingPopup = false }
                        }
                        tick++
                    }
                    ModeRow("全屏提醒", "强制全屏打断，效果最强", Prefs.MODE_FULL, mode) {
                        mode = it; Prefs.setMode(ctx, it); tick++
                    }
                    Spacer(Modifier.height(4.dp))
                    // 室内不提醒：勾选态跟随位置权限（无后台入口的系统以前台为准），无权限只走申请不生效
                    val indoorChecked = indoorMute && PermissionHelper.isLocationEnough(ctx)
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(selected = indoorChecked, onClick = { onIndoorCheck(!indoorChecked) }),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = indoorChecked,
                            onCheckedChange = { onIndoorCheck(it) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("室内不提醒", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "即使在室内也要当心被家具等物品绊倒哦",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {                        FilterChip(
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
                        if (Build.VERSION.SDK_INT >= 33) requestRuntime(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            onDead = { showCoreDialog = true }
                        ) {
                            permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        } else try {
                            settingsLauncher.launch(KeepAliveHelper.appDetailsIntent(ctx))
                        } catch (_: Exception) { }
                    }
                    PermRow(
                        "位置（可选）",
                        if (PermissionHelper.requiresAlwaysLocation(ctx) &&
                            !Prefs.isLocationCompat(ctx)
                        ) "室内判断需始终允许" else "室内判断需要位置",
                        PermissionHelper.isLocationEnough(ctx)
                    ) {
                        requestLocation()
                    }
                    if (mode == Prefs.MODE_POPUP)
                        PermRow("悬浮窗", "弹窗提醒必备", PermissionHelper.hasOverlay(ctx)) {
                            try { settingsLauncher.launch(overlayIntent()) } catch (_: Exception) { }
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
                            onClick = {
                                try {
                                    settingsLauncher.launch(KeepAliveHelper.batteryWhitelistIntent(ctx))
                                } catch (_: Exception) {
                                    KeepAliveHelper.requestBatteryWhitelist(ctx)
                                }
                            },
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

        // 位置引导：区分的系统要“始终允许”，不区分的系统允许即可
        if (showLocDialog) {
            val needAlways = PermissionHelper.requiresAlwaysLocation(ctx)
            AlertDialog(
                onDismissRequest = { showLocDialog = false; pendingIndoor = false },
                title = { Text(if (needAlways) "需要始终允许位置" else "需要位置权限") },
                text = {
                    Text(
                        if (needAlways) "室内判断需在后台获取位置，请在应用信息 → 权限 → 位置中选择“始终允许”。"
                        else "室内判断需要位置权限，请在应用信息 → 权限中允许位置访问。"
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showLocDialog = false
                        try {
                            settingsLauncher.launch(KeepAliveHelper.appDetailsIntent(ctx))
                        } catch (_: Exception) { pendingIndoor = false }
                    }) { Text("去设置") }
                },
                dismissButton = {
                    TextButton(onClick = { showLocDialog = false; pendingIndoor = false }) { Text("取消") }
                }
            )
        }

        // 核心权限被长期拒绝：系统不再弹窗，引导去设置开启
        if (showCoreDialog) {
            AlertDialog(
                onDismissRequest = { showCoreDialog = false; pendingEnable = false },
                title = { Text("需要权限") },
                text = { Text("看路检测需要身体活动与通知权限，请在应用信息 → 权限/通知中开启。") },
                confirmButton = {
                    TextButton(onClick = {
                        showCoreDialog = false
                        try {
                            settingsLauncher.launch(KeepAliveHelper.appDetailsIntent(ctx))
                        } catch (_: Exception) { pendingEnable = false }
                    }) { Text("去设置") }
                },
                dismissButton = {
                    TextButton(onClick = { showCoreDialog = false; pendingEnable = false }) { Text("取消") }
                }
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
            // 室内态跟随位置权限：够用才判断，否则提示缺的权限
            val locEnough = PermissionHelper.isLocationEnough(ctx)
            val needAlways = PermissionHelper.requiresAlwaysLocation(ctx) &&
                !Prefs.isLocationCompat(ctx)
            StateRow(
                "室内",
                if (!locEnough && needAlways) "未判断（需始终允许位置）"
                else if (!locEnough) "未判断（需位置权限）"
                else if (!Prefs.isIndoorMute(ctx)) "未判断（开关已关）"
                else if (!IndoorDetector.isLocationOn(ctx)) "未判断（定位总开关已关）"
                else if (snap.indoor) "是（抑制提醒）" else "否",
            )
            StateRow(
                "卫星",
                if (locEnough && IndoorDetector.isLocationOn(ctx)) "${snap.sats}（定位/可见/总数）" else "未追踪"
            )
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
