# 看路提醒 Heads Up

现代化 Material You 设计风格的 Android 看路提醒应用。走路（含上下楼梯）时玩手机，自动提醒你抬头看路。


## 功能

- **本机步态检测**：`STEP_DETECTOR` / `STEP_COUNTER` / 加速度计三源融合，不依赖 GMS
- **防误触判据**：连续 N 步节律一致（350~1500ms 步频带 + 稳定度校验）才触发；
  幅度带过滤微振与剧烈晃动，停步 3s 清零
- **三种提醒方式**：浮动通知 / 悬浮窗弹窗 / 全屏提醒
- **保活**：开机自启、前台服务、厂商自启动引导、电池白名单、WorkManager 巡检
- **实时状态卡**：服务心跳 / 连贯步数 / 传感器 / GMS 状态，附"模拟步行"端到端测试

## 权限

身体活动、通知、位置（可选）、悬浮窗（仅弹窗模式）、
忽略电池优化、开机启动、前台服务。

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
├── data/Prefs.kt              # 开关/模式/间隔/灵敏度
├── detection/WalkDetector.kt  # 步态检测核心（节律一致性+幅度门控）
├── service/
│   ├── HeadsUpService.kt      # 前台服务：检测调度/GMS加速通道/保活
│   ├── ActivityUpdateReceiver.kt
│   └── BootReceiver.kt        # 开机自启/被杀拉活
├── reminder/
│   ├── ReminderManager.kt     # 三种提醒方式
│   └── ReminderActivity.kt    # 全屏提醒页
├── worker/KeepAliveWorker.kt
└── util/                      # 权限/电池白名单/厂商自启动
```

## 说明

提醒不能替代注意力，走路时请尽量少看手机。
