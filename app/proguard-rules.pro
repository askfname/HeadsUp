# 后台组件（反射/系统实例化，保持入口）
-keep class com.headsup.app.service.** { *; }
-keep class com.headsup.app.reminder.** { *; }
-keep class com.headsup.app.worker.** { *; }
-keep class com.headsup.app.MainActivity { *; }
# GMS/Work/Compose 自带 consumer-rules，无需额外保留
