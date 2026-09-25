# 后台组件（反射/系统实例化，保持入口）
-keep class com.playlab.headsup.service.** { *; }
-keep class com.playlab.headsup.reminder.** { *; }
-keep class com.playlab.headsup.worker.** { *; }
-keep class com.playlab.headsup.MainActivity { *; }
# GMS/Work/Compose 自带 consumer-rules，无需额外保留
