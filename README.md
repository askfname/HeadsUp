# 看路提醒 Heads Up

现代化 Material You 设计风格的 Android 看路提醒应用。走路（含上下楼梯）时玩手机，自动提醒你抬头看路。


## 功能

- **本机步态检测**：`STEP_DETECTOR` 主链路，加速度/陀螺仪幅度门 + 距离感应器口袋门，不依赖 GMS
- **防误触判据**：连续 N 步节律一致（步频带 + 稳定度校验）才触发；
  锁屏/灭屏/口袋不累计，停走延迟清零；灵敏度三档联动全部阈值
- **三种提醒方式**：浮动通知 / 悬浮窗弹窗 / 全屏提醒；
  弹窗模式无悬浮窗权限时不切换选项，授权后自动切入
- **室内不提醒**（默认关）：GPS 判室内则抑制提醒，描述“即使在室内也要当心被家具等物品绊倒哦”；
  开关关闭时完全不用 GPS
- **户外判断**：定位星（usedInFix）+ 可见强星双计数迟滞判定，GPS 好精度优先判户外，
  室内结论短效缓存 3 分钟（只缓存室内，过期重测），未知按户外放行
- **GPS 省电策略**：主页实时；后台只在“行走中且提醒冷却已过”时按需采样 + 被动复用；
  息屏/锁屏/定位总开关关闭即断连，60s 看门狗治断流卡死
- **权限跟随 UI**：位置需“始终允许”（无该选项的系统以前台为准）；
  长期拒绝后“去开启”直接引导去设置；守护开关/室内勾选拒绝即保持关闭，授权后自动补开
- **保活**：开机自启、前台服务、厂商自启动引导、电池白名单、WorkManager 巡检
- **实时状态卡**：服务心跳 / 连贯步数 / 传感器 / 室内 / 卫星（定位/可见/总数）/ GMS 状态，
  附“模拟步行”端到端测试

## 权限

身体活动、通知、位置（室内判断需“始终允许”，不区分的系统以前台为准）、
悬浮窗（仅弹窗模式）、忽略电池优化、开机启动、前台服务。

## 构建

```bash
./gradlew assembleDebug
```

要求：JDK 17，Android SDK（含 `platforms;android-34` / `build-tools;34.0.0`），
`local.properties` 中配置 `sdk.dir`。

## 目录

```
app/src/main/java/com/playlab/headsup/
├── MainActivity.kt            # Material You 单页：开关/提醒方式/权限/保活/状态卡
├── data/Prefs.kt              # 开关/模式/间隔/灵敏度/室内不提醒/权限申请记录
├── detection/WalkDetector.kt  # 步态检测核心（节律一致性+幅度门控）
├── service/
│   ├── HeadsUpService.kt      # 前台服务：检测调度/GMS加速通道/保活/GPS启停
│   ├── ActivityUpdateReceiver.kt
│   └── BootReceiver.kt        # 开机自启/被杀拉活
├── reminder/
│   ├── ReminderManager.kt     # 三种提醒方式（含室内抑制门控）
│   └── ReminderActivity.kt    # 全屏提醒页
├── worker/KeepAliveWorker.kt
└── util/
    ├── IndoorDetector.kt      # GPS 室内判断（双计数迟滞+缓存+省电调度）
    ├── PermissionHelper.kt    # 权限状态查询（前台/后台/始终允许）
    └── KeepAliveHelper.kt     # 电池白名单/厂商自启动
```

## 说明

提醒不能替代注意力，走路时请尽量少看手机。
