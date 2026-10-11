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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.playlab.headsup.data.Prefs
import com.playlab.headsup.R
import com.playlab.headsup.detection.DetectState
import com.playlab.headsup.detection.WalkDetector
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
        if (Prefs.isEnabled(this)) HeadsUpService.cancelSimulation(this)
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
    var showHighBgDialog by remember { mutableStateOf(false) } // 忽略电池优化后引导高后台耗电
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

    // 开关落盘与服务启停
    fun applyEnabled(on: Boolean) {
        enabled = on
        Prefs.setEnabled(ctx, on)
        if (on) {
            ReminderManager.ensureChannels(ctx)
            HeadsUpService.start(ctx)
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
        if (Prefs.isEnabled(ctx)) HeadsUpService.refreshDetection(ctx)
        tick++
    }

    // 系统设置页返回立即刷新
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { refreshAll() }

    // 电池白名单返回：已忽略则弹窗引导高后台耗电 + 锁定任务卡片
    val batteryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        refreshAll()
        if (KeepAliveHelper.ignoringBattery(ctx)) showHighBgDialog = true
    }

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
        // 权限后重新注册传感器并订阅 GMS 辅助通道。
        if (Prefs.isEnabled(ctx)) {
            HeadsUpService.refreshDetection(ctx)
        }
        // 室内开关跟随权限；29 并申后仍缺后台看系统能力；拒绝则引导
        if (pendingIndoor) {
            if (PermissionHelper.isLocationEnough(ctx)) {
                indoorMute = true; Prefs.setIndoorMute(ctx, true); pendingIndoor = false
                if (Prefs.isEnabled(ctx)) HeadsUpService.start(ctx)
            } else if (!PermissionHelper.hasForegroundLocation(ctx)) {
                indoorMute = false; Prefs.setIndoorMute(ctx, false)
                showLocDialog = true
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

    // 用户回到前台时可安全恢复已开启的监测
    LaunchedEffect(Unit) {
        if (Prefs.isEnabled(ctx)) {
            HeadsUpService.start(ctx)
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
                    FilledTonalIconButton(
                        onClick = {},
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    ) {
                        Icon(painterResource(R.drawable.ic_notify), null, Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (enabled) stringResource(R.string.home_status_on) else stringResource(R.string.home_status_off),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            if (enabled) stringResource(R.string.home_status_on_desc) else stringResource(R.string.home_status_off_desc),
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
                    Text(stringResource(R.string.section_reminder_method), style = MaterialTheme.typography.titleMedium)
                    ModeRow(
                        stringResource(R.string.mode_popup_title),
                        stringResource(R.string.mode_popup_desc),
                        Prefs.MODE_FLOAT, mode
                    ) {
                        mode = it; Prefs.setMode(ctx, it); tick++
                    }
                    ModeRow(
                        stringResource(R.string.mode_float_title),
                        stringResource(R.string.mode_float_desc),
                        Prefs.MODE_POPUP, mode
                    ) {
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
                    ModeRow(
                        stringResource(R.string.mode_full_title),
                        stringResource(R.string.mode_full_desc),
                        Prefs.MODE_FULL, mode
                    ) {
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
                            Text(stringResource(R.string.vibrate_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.vibrate_desc),
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
                            Text(stringResource(R.string.indoor_mute_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.indoor_mute_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // 功能说明入口
                        IconButton(
                            onClick = { showIndoorHelp = true },
                            modifier = Modifier.padding(end = 4.dp),
                        ) {
                            Icon(Icons.Filled.Info, contentDescription = stringResource(R.string.indoor_help_title))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.cooldown_format, cooldown), style = MaterialTheme.typography.bodyMedium)
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
                    Text(stringResource(R.string.sensitivity_title), style = MaterialTheme.typography.bodyMedium)
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
                            label = { Text(stringResource(R.string.sens_low)) },
                            colors = chipColors,
                        )
                        FilterChip(
                            selected = sens == 1, onClick = {
                                sens = 1; Prefs.setSensitivity(ctx, 1)
                                if (enabled) HeadsUpService.start(ctx)
                            },
                            label = { Text(stringResource(R.string.sens_mid)) },
                            colors = chipColors,
                        )
                        FilterChip(
                            selected = sens == 2, onClick = {
                                sens = 2; Prefs.setSensitivity(ctx, 2)
                                if (enabled) HeadsUpService.start(ctx)
                            },
                            label = { Text(stringResource(R.string.sens_high)) },
                            colors = chipColors,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { ReminderManager.test(ctx) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Notifications, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.test_reminder))
                    }
                }
            }

            // 检测状态（实时）
            DetectStatusCard(enabled)

            // 权限
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.perm_title), style = MaterialTheme.typography.titleMedium)
                    PermRow(
                        stringResource(R.string.perm_activity_title),
                        stringResource(R.string.perm_activity_desc),
                        PermissionHelper.hasActivity(ctx)
                    ) { requestCore() }
                    PermRow(
                        stringResource(R.string.perm_notif_title),
                        stringResource(R.string.perm_notif_desc),
                        PermissionHelper.hasNotification(ctx)
                    ) {
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
                        stringResource(R.string.perm_location_title),
                        if (PermissionHelper.requiresAlwaysLocation(ctx)) stringResource(R.string.perm_location_desc_always)
                        else stringResource(R.string.perm_location_desc_fg),
                        PermissionHelper.isLocationEnough(ctx)
                    ) {
                        requestLocation()
                    }
                    if (mode == Prefs.MODE_POPUP)
                        PermRow(
                            stringResource(R.string.perm_overlay_title),
                            stringResource(R.string.perm_overlay_desc),
                            PermissionHelper.hasOverlay(ctx)
                        ) {
                            try { settingsLauncher.launch(overlayIntent()) } catch (_: Exception) { }
                        }
                    @Suppress("unused") val _tick = tick // 订阅刷新
                }
            }

            // 保活
            Card(Modifier.fillMaxWidth()) {
                @Suppress("unused") val _keepTick = tick // 跟随电池白名单状态（允许/撤销）刷新
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.keepalive_title), style = MaterialTheme.typography.titleMedium)
                    // 已知厂商才显示厂商名行，未知厂商仅显示下方两条通用路径
                    KeepAliveHelper.deviceVendor(ctx)?.let { vendor ->
                        Text(
                            vendor,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        KeepAliveHelper.deviceHint(ctx),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        KeepAliveHelper.highBgHint(ctx),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { KeepAliveHelper.openAutoStart(ctx) },
                            modifier = Modifier.weight(1f)
                        ) {
                            // 长文案/大字体下单行省略，防止换行撑高按钮
                            Text(
                                stringResource(R.string.autostart_settings),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                // 已忽略则弹解除后台限制弹窗；未允许/被撤销则走电池白名单申请
                                if (KeepAliveHelper.ignoringBattery(ctx)) showHighBgDialog = true
                                else try {
                                    batteryLauncher.launch(KeepAliveHelper.batteryWhitelistIntent(ctx))
                                } catch (_: Exception) {
                                    KeepAliveHelper.requestBatteryWhitelist(ctx)
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                if (KeepAliveHelper.ignoringBattery(ctx)) stringResource(R.string.high_bg_action)
                                else stringResource(R.string.battery_whitelist),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }

            // 关于
            AboutCard()

            Text(
                stringResource(R.string.footer_disclaimer),
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
                title = {
                    Text(
                        if (needAlways) stringResource(R.string.loc_dialog_need_always_title)
                        else stringResource(R.string.loc_dialog_need_perm_title)
                    )
                },
                text = {
                    Text(
                        if (needAlways) stringResource(R.string.loc_dialog_need_always_msg)
                        else stringResource(R.string.loc_dialog_need_perm_msg)
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showLocDialog = false
                        try {
                            settingsLauncher.launch(KeepAliveHelper.appDetailsIntent(ctx))
                        } catch (_: Exception) { pendingIndoor = false }
                    }) { Text(stringResource(R.string.dialog_go_settings)) }
                },
                dismissButton = {
                    TextButton(onClick = { showLocDialog = false; pendingIndoor = false }) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                }
            )
        }

        // 核心权限被多次拒绝：系统不再弹窗，引导去设置开启
        if (showCoreDialog) {
            AlertDialog(
                onDismissRequest = { showCoreDialog = false; pendingEnable = false },
                title = { Text(stringResource(R.string.core_dialog_title)) },
                text = { Text(stringResource(R.string.core_dialog_msg)) },
                confirmButton = {
                    TextButton(onClick = {
                        showCoreDialog = false
                        try {
                            settingsLauncher.launch(KeepAliveHelper.appDetailsIntent(ctx))
                        } catch (_: Exception) { pendingEnable = false }
                    }) { Text(stringResource(R.string.dialog_go_settings)) }
                },
                dismissButton = {
                    TextButton(onClick = { showCoreDialog = false; pendingEnable = false }) {
                        Text(stringResource(R.string.dialog_cancel))
                    }
                }
            )
        }

        // 忽略电池优化后：已知厂商给直达入口，未知仅文字引导手动设置
        if (showHighBgDialog) {
            val highBgKnown = KeepAliveHelper.isHighBgSupported()
            AlertDialog(
                onDismissRequest = { showHighBgDialog = false },
                title = { Text(stringResource(R.string.high_bg_title)) },
                text = { Text(stringResource(R.string.high_bg_msg)) },
                confirmButton = {
                    if (highBgKnown) {
                        TextButton(onClick = {
                            showHighBgDialog = false
                            if (!KeepAliveHelper.openHighBackground(ctx)) {
                                try {
                                    settingsLauncher.launch(KeepAliveHelper.appDetailsIntent(ctx))
                                } catch (_: Exception) { }
                            }
                        }) { Text(stringResource(R.string.dialog_go_settings)) }
                    } else {
                        TextButton(onClick = { showHighBgDialog = false }) {
                            Text(stringResource(R.string.dialog_know))
                        }
                    }
                },
                dismissButton = {
                    if (highBgKnown) {
                        TextButton(onClick = { showHighBgDialog = false }) {
                            Text(stringResource(R.string.dialog_know))
                        }
                    }
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
    var fVisCn0 by remember { mutableStateOf(eff.visCn0.toString()) }

    // 空=默认，超范围报错；成功后自动关闭
    fun save() {
        val visIn = fVisIn.trim().let {
            if (it.isEmpty()) null else it.toIntOrNull()?.takeIf { v -> v in 3..10 }
        }
        val ratioIn = fRatioIn.trim().let {
            if (it.isEmpty()) null else it.toFloatOrNull()?.takeIf { v -> v.isFinite() && v in 0.2f..0.6f }
        }
        val gpsAcc = fGpsAcc.trim().let {
            if (it.isEmpty()) null else it.toFloatOrNull()?.takeIf { v -> v.isFinite() && v in 5f..20f }
        }
        val visCn0 = fVisCn0.trim().let {
            if (it.isEmpty()) null else it.toFloatOrNull()?.takeIf { v -> v.isFinite() && v in 20f..32f }
        }
        // 非空但非法即报错
        if ((fVisIn.trim().isNotEmpty() && visIn == null) ||
            (fRatioIn.trim().isNotEmpty() && ratioIn == null) ||
            (fGpsAcc.trim().isNotEmpty() && gpsAcc == null) ||
            (fVisCn0.trim().isNotEmpty() && visCn0 == null)
        ) {
            err = ctx.getString(R.string.tune_error)
            return
        }
        Prefs.saveIndoorCustom(ctx, visIn, ratioIn, gpsAcc, visCn0)
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
        fVisCn0 = d.visCn0.toString()
        err = ""
        onChanged()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.indoor_help_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.indoor_help_desc),
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(
                    onClick = { expanded = !expanded },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(
                        if (expanded) stringResource(R.string.indoor_advanced_hide)
                        else stringResource(R.string.indoor_advanced_show)
                    )
                }
                if (expanded) {
                    TuneField(
                        stringResource(R.string.tune_vis_cn0),
                        fVisCn0,
                        stringResource(
                            R.string.tune_default_format,
                            d.visCn0.toString(),
                            stringResource(R.string.tune_range_cn0)
                        ),
                        KeyboardType.Decimal
                    ) { fVisCn0 = it }
                    TuneField(
                        stringResource(R.string.tune_strong_count),
                        fVisIn,
                        stringResource(
                            R.string.tune_default_format,
                            d.visIndoor.toString(),
                            stringResource(R.string.tune_range_int)
                        ),
                        KeyboardType.Number
                    ) { fVisIn = it }
                    TuneField(
                        stringResource(R.string.tune_strong_ratio),
                        fRatioIn,
                        stringResource(
                            R.string.tune_default_format,
                            d.ratioIndoor.toString(),
                            stringResource(R.string.tune_range_ratio)
                        ),
                        KeyboardType.Decimal
                    ) { fRatioIn = it }
                    TuneField(
                        stringResource(R.string.tune_gps_acc),
                        fGpsAcc,
                        stringResource(
                            R.string.tune_default_format,
                            d.gpsAcc.toString(),
                            stringResource(R.string.tune_range_acc)
                        ),
                        KeyboardType.Decimal
                    ) { fGpsAcc = it }
                    Text(
                        stringResource(R.string.tune_hint),
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
                        Button(onClick = ::save, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.tune_save))
                        }
                        OutlinedButton(onClick = ::reset, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.tune_reset))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_close)) }
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
        0 -> stringResource(R.string.sens_desc_sensitive)
        2 -> stringResource(R.string.sens_desc_strict)
        else -> stringResource(R.string.sens_desc_standard)
    }
    // heartbeat 与 elapsedRealtime 同基准（心跳已降频至 10s，Doze 下更稀）
    val alive = snap.heartbeat > 0 &&
        android.os.SystemClock.elapsedRealtime() - snap.heartbeat < 45_000
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.detect_status_title), style = MaterialTheme.typography.titleMedium)
            StateRow(
                stringResource(R.string.state_service),
                if (!enabled) stringResource(R.string.state_off)
                else if (!snap.screenOn) stringResource(R.string.state_standby_screen_off)
                else if (!snap.unlocked) stringResource(R.string.state_standby_locked)
                else if (snap.pocketed) stringResource(R.string.state_standby_pocket)
                else if (alive) stringResource(R.string.state_running)
                else stringResource(R.string.state_dormant_unknown),
            )
            // 真实提醒后 8s 内显示“已提醒”，与批量投递下整串步伐一次处理完保持同步
            val firedAgoSec = if (snap.lastTriggerAt > 0)
                (android.os.SystemClock.elapsedRealtime() - snap.lastTriggerAt) / 1000
            else Long.MAX_VALUE
            StateRow(
                stringResource(R.string.state_gait),
                if (snap.walking && snap.runLen >= 3) stringResource(R.string.gait_walking_format, snap.walkElapsedSec)
                else if (firedAgoSec < 8) stringResource(R.string.gait_reminded_format, firedAgoSec.toInt())
                else stringResource(R.string.gait_still),
            )
            StateRow(
                stringResource(R.string.state_run_steps),
                stringResource(R.string.run_steps_format, snap.runLen, snap.runNeed) +
                    if (snap.batched) stringResource(R.string.gait_batched) else stringResource(R.string.gait_rhythmic),
            )
            StateRow(
                stringResource(R.string.state_window_steps),
                stringResource(R.string.window_steps_format, snap.stepsInWindow)
            )
            StateRow(
                stringResource(R.string.state_screen),
                if (!snap.screenOn) stringResource(R.string.screen_off)
                else if (!snap.unlocked) stringResource(R.string.screen_on_locked)
                else stringResource(R.string.screen_on_unlocked)
            )
            StateRow(
                stringResource(R.string.state_proximity),
                if (snap.pocketed) stringResource(R.string.proximity_yes) else stringResource(R.string.proximity_no)
            )
            StateRow(
                stringResource(R.string.state_sensor),
                when (snap.sensorBackend) {
                    WalkDetector.SensorBackend.STEP_DETECTOR -> stringResource(R.string.sensor_step)
                    WalkDetector.SensorBackend.STEP_COUNTER -> stringResource(R.string.sensor_counter)
                    WalkDetector.SensorBackend.GMS_ACCELEROMETER -> stringResource(R.string.sensor_gms_accelerometer)
                    else -> stringResource(R.string.sensor_none)
                },
            )
            StateRow(stringResource(R.string.sensitivity_title), sensDesc)
            // 室内态跟随位置权限
            val locEnough = PermissionHelper.isLocationEnough(ctx)
            val needAlways = PermissionHelper.requiresAlwaysLocation(ctx)
            // 直读检测器实时值（快照只在步伐/10s心跳刷新，会滞后）；信号中断不显示旧值
            val tracking = locEnough && IndoorDetector.isLocationOn(ctx)
            val liveSats = IndoorDetector.satInfo
            val satsFresh = tracking && IndoorDetector.isGnssFresh()
            val liveVerdict = if (tracking) IndoorDetector.verdict() else null
            StateRow(
                stringResource(R.string.state_indoor),
                if (!locEnough && needAlways) stringResource(R.string.indoor_unknown_always)
                else if (!locEnough) stringResource(R.string.indoor_unknown_need_perm)
                else if (!Prefs.isIndoorMute(ctx)) stringResource(R.string.indoor_unknown_disabled)
                else if (!IndoorDetector.isLocationOn(ctx)) stringResource(R.string.indoor_unknown_loc_off)
                else if (snap.indoorPending || liveVerdict == IndoorDetector.Verdict.UNKNOWN) stringResource(R.string.indoor_confirming)
                else if (liveVerdict == IndoorDetector.Verdict.INDOOR || (!tracking && snap.indoor)) stringResource(R.string.indoor_yes)
                else stringResource(R.string.indoor_no),
            )
            StateRow(
                stringResource(R.string.state_satellite),
                if (!tracking) stringResource(R.string.sat_not_tracking)
                else if (!satsFresh) stringResource(R.string.sat_waiting)
                else if (liveSats.isBlank()) stringResource(R.string.sat_waiting)
                else stringResource(R.string.sat_value_format, liveSats)
            )
            val gmsText = when (snap.gms) {
                WalkDetector.GmsStatus.AVAILABLE -> stringResource(R.string.gms_available)
                WalkDetector.GmsStatus.UNAVAILABLE -> stringResource(R.string.gms_unavailable)
                WalkDetector.GmsStatus.NO_PERM -> stringResource(R.string.gms_no_perm)
                else -> stringResource(R.string.generic_unknown)
            }
            StateRow(stringResource(R.string.state_gms), gmsText)
            // 一键拉起（正常不用点，打开页面会自动拉起）
            if (enabled && !alive) {
                OutlinedButton(
                    onClick = { HeadsUpService.start(ctx) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.restart_service)) }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { HeadsUpService.simulate(ctx) },
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled && !snap.simulating,
            ) {
                Text(
                    if (!enabled) stringResource(R.string.simulate_need_enable)
                    else if (snap.simulating) stringResource(R.string.simulating)
                    else stringResource(R.string.simulate_walk)
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
            Text(stringResource(R.string.about_title), style = MaterialTheme.typography.titleMedium)
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
                        Text(stringResource(R.string.donate_title), style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        stringResource(R.string.donate_desc),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            // 应用名 + 版本 + 版权
            Column(verticalArrangement = Arrangement.spacedBy(1.8.dp)) {
                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.version_format, version),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    stringResource(R.string.copyright_format, year),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.developer_title),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(R.string.developer_name),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AboutRow(
                stringResource(R.string.about_website),
                "https://playlab.eu.org"
            ) { openUrl(ctx, "https://playlab.eu.org") }
            AboutRow(
                stringResource(R.string.about_github),
                stringResource(R.string.about_github_desc)
            ) { openUrl(ctx, "https://github.com/askfname/HeadsUp") }
            AboutRow(
                stringResource(R.string.about_license),
                stringResource(R.string.about_license_desc)
            ) { showLicense = true }
            AboutRow(
                stringResource(R.string.about_terms),
                stringResource(R.string.about_terms_desc)
            ) { showTerms = true }
        }
    }

    // 赞助弹窗：按地区二选一
    if (showDonate) {
        AlertDialog(
            onDismissRequest = { showDonate = false },
            title = { Text(stringResource(R.string.donate_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.donate_choose))
                    DonateChannel(
                        stringResource(R.string.donate_afdian),
                        stringResource(R.string.donate_afdian_desc),
                        "https://afdian.com/a/playlab",
                        stringResource(R.string.donate_afdian_btn)
                    )
                    DonateChannel(
                        stringResource(R.string.donate_kofi),
                        stringResource(R.string.donate_kofi_desc),
                        "https://ko-fi.com/playlaboratory",
                        stringResource(R.string.donate_kofi_btn)
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showDonate = false }) { Text(stringResource(R.string.dialog_close)) } }
        )
    }

    // 开源许可弹窗
    if (showLicense) {
        AlertDialog(
            onDismissRequest = { showLicense = false },
            title = { Text(stringResource(R.string.license_title)) },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LicenseEntry("Kotlin / Android Gradle Plugin", "Apache License 2.0")
                    LicenseEntry("AndroidX Core / Activity Compose", "Apache License 2.0")
                    LicenseEntry(
                        "Jetpack Compose (UI / Material3 / Icons / BOM 2024.06.00)",
                        "Apache License 2.0"
                    )
                    LicenseEntry("Play Services Location 21.3.0", "Apache License 2.0")
                }
            },
            confirmButton = { TextButton(onClick = { showLicense = false }) { Text(stringResource(R.string.dialog_close)) } }
        )
    }
    // 使用条款弹窗
    if (showTerms) {
        AlertDialog(
            onDismissRequest = { showTerms = false },
            title = { Text(stringResource(R.string.terms_title)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.terms_content))
                }
            },
            confirmButton = { TextButton(onClick = { showTerms = false }) { Text(stringResource(R.string.dialog_know)) } }
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
        TextButton(onClick = onClick) { Text(stringResource(R.string.about_view)) }
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
        if (!ok) TextButton(onClick = onFix) { Text(stringResource(R.string.perm_open)) }
        else TextButton(
            onClick = {},
            enabled = false,
            colors = ButtonDefaults.textButtonColors(
                disabledContentColor = MaterialTheme.colorScheme.primary,
            ),
        ) { Text(stringResource(R.string.perm_granted), fontWeight = FontWeight.Normal) }
    }
}
