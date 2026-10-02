package com.playlab.headsup

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
import kotlin.math.abs

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
        IndoorDetector.setUiOpen(false) // 离开主页：GPS 取消追踪
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // 沉浸式：系统栏透明，图标深浅随主题自动适配
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
    var vibrate by remember { mutableStateOf(Prefs.isVibrate(ctx)) }
    var pendingIndoor by remember { mutableStateOf(false) } // 权限不足，授权后自动补开
    var pendingEnable by remember { mutableStateOf(false) } // 开启守护但缺核心权限，等授权结果
    var pendingPopup by remember { mutableStateOf(false) } // 弹窗提醒缺悬浮窗权限，授权后才切换
    var showLocDialog by remember { mutableStateOf(false) } // 引导去开位置权限
    var showCoreDialog by remember { mutableStateOf(false) } // 引导去开身体活动/通知
    var showIndoorHelp by remember { mutableStateOf(false) } // “室内不提醒”功能说明 + 高级设置
    var tick by remember { mutableIntStateOf(0) }
    val scrollState = rememberScrollState()
    val viewConfig = LocalViewConfiguration.current

    // 系统不再弹窗（不再询问）则直接引导
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

    // 开关落盘与服务启停
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
        vibrate = Prefs.isVibrate(ctx)
        // 同步高级设置参数
        IndoorDetector.applyCustom(Prefs.getIndoorCustomOrNull(ctx))
        val enough = PermissionHelper.isLocationEnough(ctx)
        var m = Prefs.isIndoorMute(ctx)
        // 权限被收回则跟随关闭
        if (m && !enough) { m = false; Prefs.setIndoorMute(ctx, false) }
        if (pendingIndoor && enough) {
            m = true; Prefs.setIndoorMute(ctx, true); pendingIndoor = false
        }
        indoorMute = m
        // 悬浮窗已授权才切到弹窗，否则保持原选项
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

    // 系统设置页返回立即刷新
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshAll() }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // 守护开关跟随核心权限
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
        // 室内开关跟随权限；29 并申后仍缺后台看系统能力；拒绝则引导
        if (pendingIndoor) {
            if (PermissionHelper.isLocationEnough(ctx)) {
                indoorMute = true; Prefs.setIndoorMute(ctx, true); pendingIndoor = false
                if (Prefs.isEnabled(ctx)) HeadsUpService.start(ctx)
            } else if (!PermissionHelper.hasForegroundLocation(ctx)) {
                indoorMute = false; Prefs.setIndoorMute(ctx, false)
                showLocDialog = true
            } else if (Build.VERSION.SDK_INT == 29 && !bgRationale()) {
                // 允许一次并申，系统不给后台入口即视为给足
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

    // 每次回到前台重读权限
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

    // 室内勾选跟随权限：够用直接开，否则只走申请流程
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

    // 守护开关跟随核心权限：缺权限走申请
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

    // 沉浸滚动：Scaffold 不预留固定边框，系统边衬随内容一起滚，滚动时内容滑入透明系统栏之下
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
    ) { _ ->
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(scrollState)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 状态卡
            ElevatedCard(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = {}) {
                        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (enabled) "守护中" else "已关闭", style = MaterialTheme.typography.titleLarge)
                        Text(
                            if (enabled) "走路时看手机会提醒你" else "开启守护监测行走状态",
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
                    ModeRow("浮动通知", "顶部横幅，需悬浮通知权限", Prefs.MODE_FLOAT, mode) {
                        mode = it; Prefs.setMode(ctx, it); tick++
                    }
                    ModeRow("弹窗提醒", "悬浮窗卡片，需悬浮窗权限", Prefs.MODE_POPUP, mode) {
                        if (PermissionHelper.hasOverlay(ctx)) {
                            mode = it; Prefs.setMode(ctx, it)
                        } else {
                            // 无权限只跳设置，不切换选项
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
                    // 提醒时震动
                    Row(
                        Modifier.fillMaxWidth()
                            .selectable(
                                selected = vibrate,
                                onClick = {
                                    vibrate = !vibrate
                                    Prefs.setVibrate(ctx, vibrate)
                                }
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = vibrate,
                            onCheckedChange = {
                                vibrate = it
                                Prefs.setVibrate(ctx, it)
                            }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("提醒时震动", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "发出提醒的同时震动",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    // 室内不提醒：勾选态跟随位置权限
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
                                "即使在室内也要当心被家具等物品绊倒",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 功能说明入口
                        IconButton(
                            onClick = { showIndoorHelp = true },
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Icon(Icons.Filled.Info, contentDescription = "功能说明")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("提醒间隔：${cooldown}秒", style = MaterialTheme.typography.bodyMedium)
                    // 方向锁：竖滑接管并转交父滚动，避免误拖滑块
                    Box(
                        Modifier.pointerInput(scrollState, viewConfig) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val init = cooldown
                                var dx = 0f
                                var dy = 0f
                                val slop = viewConfig.touchSlop
                                while (true) {
                                    val e = awaitPointerEvent()
                                    val c = e.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    dx += c.position.x - c.previousPosition.x
                                    dy += c.position.y - c.previousPosition.y
                                    if (abs(dy) > slop / 2 && abs(dy) > abs(dx)) {
                                        // 竖滑：还原按压跳变，手动滚列表
                                        if (cooldown != init) {
                                            cooldown = init
                                            Prefs.setCooldown(ctx, init)
                                        }
                                        val d = c.position.y - c.previousPosition.y
                                        c.consume()
                                        scrollState.dispatchRawDelta(-d)
                                    } else if (abs(dx) > slop && abs(dx) > abs(dy)) {
                                        break // 横滑：交回滑块
                                    }
                                }
                            }
                        }
                    ) {
                        Slider(
                            value = cooldown.toFloat(),
                            onValueChange = {
                                cooldown = it.toInt()
                                Prefs.setCooldown(ctx, it.toInt())
                            },
                            valueRange = 30f..300f, steps = 8
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("灵敏度", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val chipColors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        )
                        FilterChip(
                            selected = sens == 0, onClick = {
                                sens = 0; Prefs.setSensitivity(ctx, 0)
                                if (enabled) HeadsUpService.start(ctx)
                            },
                            label = { Text("灵敏") },
                            colors = chipColors,
                        )
                        FilterChip(
                            selected = sens == 1, onClick = {
                                sens = 1; Prefs.setSensitivity(ctx, 1)
                                if (enabled) HeadsUpService.start(ctx)
                            },
                            label = { Text("标准") },
                            colors = chipColors,
                        )
                        FilterChip(
                            selected = sens == 2, onClick = {
                                sens = 2; Prefs.setSensitivity(ctx, 2)
                                if (enabled) HeadsUpService.start(ctx)
                            },
                            label = { Text("严格") },
                            colors = chipColors,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
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
                        ) "室内不提醒需始终允许" else "室内不提醒需要位置权限",
                        PermissionHelper.isLocationEnough(ctx)
                    ) {
                        requestLocation()
                    }
                    if (mode == Prefs.MODE_POPUP)
                        PermRow("悬浮窗", "弹窗提醒必须", PermissionHelper.hasOverlay(ctx)) {
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

            // 关于
            AboutCard()

            Text(
                "# 提醒不能替代注意力，走路时请尽量少看手机",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 室内不提醒功能说明 + 高级设置
        if (showIndoorHelp) {
            IndoorHelpDialog(
                onDismiss = { showIndoorHelp = false },
                onChanged = { tick++ },
            )
        }

        // 位置引导
        if (showLocDialog) {
            val needAlways = PermissionHelper.requiresAlwaysLocation(ctx)
            AlertDialog(
                onDismissRequest = { showLocDialog = false; pendingIndoor = false },
                title = { Text(if (needAlways) "需要始终允许位置" else "需要位置权限") },
                text = {
                    Text(
                        if (needAlways) "室内不提醒需在后台获取位置，请在应用信息 → 权限 → 位置中选择“始终允许”。"
                        else "室内不提醒需要位置权限，请在应用信息 → 权限中允许位置访问。"
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

        // 核心权限被多次拒绝：系统不再弹窗，引导去设置开启
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

/** 室内不提醒功能说明 + 高级设置：自定义室内阈值，空即默认 */
@Composable
private fun IndoorHelpDialog(
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val d = Prefs.IndoorParams()
    var expanded by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf("") }
    // 输入框初始显示有效参数（无自定义即默认）
    val eff = remember { Prefs.getIndoorParams(ctx) }
    var fVisIn by remember { mutableStateOf(eff.visIndoor.toString()) }
    var fRatioIn by remember { mutableStateOf(eff.ratioIndoor.toString()) }
    var fGpsAcc by remember { mutableStateOf(eff.gpsAcc.toString()) }

    // 空=默认，超范围报错；成功后自动关闭
    fun save() {
        val visIn = fVisIn.trim().let {
            if (it.isEmpty()) null else it.toIntOrNull()?.takeIf { v -> v in 0..30 }
        }
        val ratioIn = fRatioIn.trim().let {
            if (it.isEmpty()) null else it.toFloatOrNull()?.takeIf { v -> v in 0f..1f }
        }
        val gpsAcc = fGpsAcc.trim().let {
            if (it.isEmpty()) null else it.toFloatOrNull()?.takeIf { v -> v in 5f..50f }
        }
        // 非空但非法即报错
        if ((fVisIn.trim().isNotEmpty() && visIn == null) ||
            (fRatioIn.trim().isNotEmpty() && ratioIn == null) ||
            (fGpsAcc.trim().isNotEmpty() && gpsAcc == null)
        ) {
            err = "参数超范围或格式错误，已取消保存"
            return
        }
        Prefs.saveIndoorCustom(ctx, visIn, ratioIn, gpsAcc)
        IndoorDetector.applyCustom(Prefs.getIndoorCustomOrNull(ctx))
        err = ""
        onChanged()
        onDismiss()
    }

    fun reset() {
        Prefs.clearIndoorParams(ctx)
        IndoorDetector.applyCustom(null)
        fVisIn = d.visIndoor.toString()
        fRatioIn = d.ratioIndoor.toString()
        fGpsAcc = d.gpsAcc.toString()
        err = ""
        onChanged()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("功能说明") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "“室内不提醒”功能受限于设备 GPS 硬件和所处环境差异，可能无法正确判断室内外，必要时可使用高级设置手动调整参数：",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(if (expanded) "高级设置 ▾" else "高级设置 ▸")
                }
                if (expanded) {
                    TuneField("强星数", fVisIn, "默认 ${d.visIndoor}（0~30）", KeyboardType.Number) { fVisIn = it }
                    TuneField("强星占比", fRatioIn, "默认 ${d.ratioIndoor}（0~1）", KeyboardType.Decimal) { fRatioIn = it }
                    TuneField("GPS 精度（米）", fGpsAcc, "默认 ${d.gpsAcc}（5~50）", KeyboardType.Decimal) { fGpsAcc = it }
                    Text(
                        "参数说明：强星数/强星占比越大室内不提醒越灵敏，GPS 精度越小室内不提醒越灵敏。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (err.isNotEmpty()) {
                        Text(err, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Button(onClick = ::save, modifier = Modifier.weight(1f)) { Text("保存") }
                        OutlinedButton(onClick = ::reset, modifier = Modifier.weight(1f)) { Text("恢复默认") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/** 高级设置单行输入 */
@Composable
private fun TuneField(
    label: String,
    value: String,
    placeholder: String,
    kb: KeyboardType,
    onValue: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = kb),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
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
            // 室内态跟随位置权限
            val locEnough = PermissionHelper.isLocationEnough(ctx)
            val needAlways = PermissionHelper.requiresAlwaysLocation(ctx) &&
                !Prefs.isLocationCompat(ctx)
            // 直读检测器实时值（快照只在步伐/10s心跳刷新，会滞后）；信号中断不显示旧值
            val tracking = locEnough && IndoorDetector.isLocationOn(ctx)
            val liveSats = IndoorDetector.satInfo
            val satsFresh = tracking && IndoorDetector.isGnssFresh()
            val liveIndoor = if (tracking) IndoorDetector.isIndoorNow() else snap.indoor
            StateRow(
                "室内",
                if (!locEnough && needAlways) "未知（需始终允许位置）"
                else if (!locEnough) "未知（需位置权限）"
                else if (!Prefs.isIndoorMute(ctx)) "未知（未启用）"
                else if (!IndoorDetector.isLocationOn(ctx)) "未知（定位已关闭）"
                else if (snap.indoorPending) "确认中…"
                else if (liveIndoor) "是（抑制提醒）" else "否",
            )
            StateRow(
                "卫星",
                if (!tracking) "未追踪"
                else if (!satsFresh) "等待信号…"
                else "$liveSats（定位/强星/总数）"
            )
            StateRow("GMS 加速", snap.gms)
            // 一键拉起（正常不用点，打开页面会自动拉起）
            if (enabled && !alive) {
                OutlinedButton(
                    onClick = { HeadsUpService.start(ctx) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("重启检测服务") }
            }
            Spacer(Modifier.height(8.dp))
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

/** 关于卡片 */
@Composable
private fun AboutCard() {
    val ctx = LocalContext.current
    var showLicense by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }
    var showDonate by remember { mutableStateOf(false) }
    // 版本号取自 PackageManager，避免开 BuildConfig
    val version = remember {
        try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
            "${pi.versionName} ($code)"
        } catch (_: Exception) { "2.1 (2)" }
    }
    val year = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("关于", style = MaterialTheme.typography.titleMedium)
            Surface(
                onClick = { showDonate = true },
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFFFFE3B3),
                contentColor = Color(0xFF6B4A00),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                Column(
                    Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Favorite, null)
                        Spacer(Modifier.width(8.dp))
                        Text("赞助支持", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "喜欢这个应用？请开发者喝杯咖啡吧",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            // 应用名 + 版本 + 版权
            Column(verticalArrangement = Arrangement.spacedBy(1.8.dp)) {
                Text("看路提醒", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Version: $version",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Copyright © $year PlayLab",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("开发者", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Play 实验室",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AboutRow("官方网站", "https://playlab.eu.org") { openUrl(ctx, "https://playlab.eu.org") }
            AboutRow("GitHub", "查看 Github 仓库") { openUrl(ctx, "https://github.com/askfname/HeadsUp") }
            AboutRow("开源许可协议", "查看开源许可") { showLicense = true }
            AboutRow("使用条款", "查看使用条款") { showTerms = true }
        }
    }

    // 赞助弹窗：按地区二选一
    if (showDonate) {
        AlertDialog(
            onDismissRequest = { showDonate = false },
            title = { Text("赞助支持") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("选择适合你的赞助渠道：")
                    DonateChannel(
                        "爱发电",
                        "国内用户推荐",
                        "https://afdian.com/a/playlab",
                        "前往爱发电"
                    )
                    DonateChannel(
                        "Ko-fi",
                        "海外用户推荐",
                        "https://ko-fi.com/playlaboratory",
                        "前往 Ko-fi"
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showDonate = false }) { Text("关闭") } }
        )
    }

    // 开源许可弹窗
    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text("开源许可协议") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LicenseEntry("Kotlin / Android Gradle Plugin", "Apache License 2.0")
                    LicenseEntry("AndroidX Core / Activity Compose", "Apache License 2.0")
                    LicenseEntry(
                        "Jetpack Compose（UI / Material3 / Icons / BOM 2024.06.00）",
                        "Apache License 2.0"
                    )
                    LicenseEntry("WorkManager 2.9.0", "Apache License 2.0")
                    LicenseEntry("Play Services Location 21.3.0", "Apache License 2.0")
                }
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text("关闭") } }
        )
    }
    // 使用条款弹窗
    if (showTerms) {
        AlertDialog(
            onDismissRequest = { showTerms = false },
            title = { Text("使用条款") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "1. 本应用仅为行走看手机的辅助提醒，不能替代注意力，行走时请遵守交通规则，尽量少看手机；\n" +
                            "2. “室内不提醒”功能受设备 GPS 硬件与所处环境影响，室内外判断结果仅供参考；\n" +
                            "3. 本软件按“原样”提供，不作任何明示或暗示保证，包括但不限于适销性与适用性保证；\n" +
                            "4. 在任何情况下，无论是合同、侵权或其他情形，作者均不对因使用本软件而产生的任何索赔、损害或其他责任承担责任。"
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showTerms = false }) { Text("知道了") } }
        )
    }
}

/** 关于页单行链接 */
@Composable
private fun AboutRow(title: String, desc: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text(
                desc, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TextButton(onClick = onClick) { Text("查看") }
    }
}

/** 许可条目 */
@Composable
private fun LicenseEntry(lib: String, license: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(lib, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            license,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** 赞助渠道 */
@Composable
private fun DonateChannel(title: String, desc: String, url: String, btn: String) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = { openUrl(ctx, url) }, modifier = Modifier.fillMaxWidth()) { Text(btn) }
    }
}

// 浏览器打开链接
private fun openUrl(ctx: Context, url: String) {
    try {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    } catch (_: Exception) { }
}

@Composable
private fun StateRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(
            label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Spacer(Modifier.width(8.dp))
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
        else TextButton(
            onClick = {},
            enabled = false,
            colors = ButtonDefaults.textButtonColors(
                disabledContentColor = MaterialTheme.colorScheme.primary,
            ),
        ) { Text("已允许", fontWeight = FontWeight.Normal) }
    }
}
