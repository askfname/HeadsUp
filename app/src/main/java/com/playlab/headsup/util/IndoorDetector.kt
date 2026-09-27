package com.playlab.headsup.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executor

/**
 * GPS 室内/室外判断
 *
 * 安全策略：
 * - 明确确认室内 -> suppress 提醒
 * - 室外 -> 放行
 * - 数据不足/未知 -> 按室外放行
 */
object IndoorDetector {

    private const val TAG = "IndoorDetector"

    private const val CN0_STRONG = 18f
    private const val VIS_CN0 = 20f

    // GPS 精度 <= 25m 直接判室外
    private const val GPS_GOOD_ACC_M = 25f

    // 室外：强星 >= 6，或定位星 >= 4，或总数 >= 6 且占比 >= 40%
    private const val VIS_OUTDOOR = 6
    private const val FIX_OUTDOOR = 4
    private const val VIS_RATIO_OUTDOOR = 0.4f
    private const val MIN_SATS_FOR_RATIO_OUTDOOR = 6

    // 室内：总数 >= 4 且强星 <= 3 且占比 <= 30%，或总数 <= 3 且强星 <= 3（深室内小样本）
    private const val VIS_INDOOR = 3
    private const val VIS_RATIO_INDOOR = 0.3f
    private const val MIN_SATS_FOR_INDOOR = 4
    private const val LOW_SATS_INDOOR = 3

    // 连续 2 次满足才切换；强室外（强星 >= 8）1 次即切出
    private const val CONFIRM_SAMPLES = 2
    private const val VIS_STRONG_OUTDOOR = 8

    // GNSS 新鲜 15s；GPS 定位有效 20s
    private const val GNSS_VALID_MS = 15_000L
    private const val GPS_FIX_VALID_MS = 20_000L

    // 只缓存已经确认的室内结论（30s：复走直接沿用，过期重测）
    private const val INDOOR_CACHE_MS = 30_000L

    // 短间隔请求，保证 GNSS 回调频率
    private const val LOCATION_INTERVAL_MS = 1_000L

    /**
     * true = 当前认为处于室内
     * false = 室外或者未知
     */
    @Volatile
    var indoor = false
        private set

    @Volatile
    var satInfo = "未知"
        private set

    @Volatile
    private var tracking = false

    /**
     * 检测页面是否前台打开
     * true：持续 GPS/GNSS
     */
    @Volatile
    private var uiOpen = false

    // 后台当前是否处于行走定位窗口
    @Volatile
    private var walkOpen = false

    // 当前 active 定位通道是否存在
    @Volatile
    private var activeOn = false

    // 上次确认室内的时间
    @Volatile
    private var cachedAt = 0L

    /**
     * usedInFix && CN0 >= 18 的卫星数（室外辅助证据）
     */
    private var fixStrong = 0

    // 当前回调 CN0 >= 20 的强星数
    private var visStrongCurrent = 0

    // 当前回调卫星总数
    private var satelliteCountCurrent = 0

    // 连续室内候选次数
    private var indoorConfirmCount = 0

    // 连续室外候选次数
    private var outdoorConfirmCount = 0

    // 上次收到 GnssStatus 的时间
    private var lastGnssAt = 0L

    // 上次收到有效 GPS Location 的时间
    private var lastGpsFixAt = 0L

    // 上一次 GPS 精度
    private var lastGpsAcc = Float.MAX_VALUE


    // ============================================================
    // Location / Thread
    // ============================================================

    private var locationMgr: LocationManager? = null

    private var gpsThread: HandlerThread? = null

    private var gpsHandler: Handler? = null

    // GPS Location 是否成功注册
    private var gpsUpdatesOn = false

    // GNSS Status 是否成功注册
    private var gnssOn = false

    // API 24~29 的 GNSS 注册任务
    private val legacyGnssRegister = Runnable {
        registerGnssLegacy()
    }

    /**
     * 主动 GPS Location 监听
     * 当前只请求 GPS_PROVIDER，不再请求 NETWORK_PROVIDER
     */
    private val activeListener = object : LocationListener {

        override fun onLocationChanged(loc: Location) {
            onFix(loc)
        }

        override fun onProviderDisabled(provider: String) {

            if (provider == LocationManager.GPS_PROVIDER) {

                lastGpsFixAt = 0L
                lastGpsAcc = Float.MAX_VALUE

                resetGnssState()

                refresh()
            }
        }

        override fun onProviderEnabled(provider: String) {
            refresh()
        }

        @Deprecated("老系统兼容")
        override fun onStatusChanged(
            provider: String?,
            status: Int,
            extras: Bundle?
        ) {
            Unit
        }
    }


    // Permission
    private fun hasLocPerm(ctx: Context): Boolean {
        return PermissionHelper.isLocationEnough(ctx)
    }

    // 判断系统定位总开关是否开启
    fun isLocationOn(ctx: Context): Boolean {

        return try {

            val lm =
                ctx.applicationContext
                    .getSystemService(LocationManager::class.java)
                    ?: return false

            lm.isProviderEnabled(
                LocationManager.GPS_PROVIDER
            )

        } catch (_: Exception) {

            false
        }
    }

    // Thread
    private fun wantActive(): Boolean {
        return uiOpen || walkOpen
    }

    private fun ensureThread() {

        if (gpsThread?.isAlive == true) {
            return
        }

        gpsThread =
            HandlerThread("IndoorGps").apply {
                start()
            }

        gpsHandler =
            Handler(gpsThread!!.looper)
    }


    /**
     * 开始整个 IndoorDetector
     *
     * 注意：
     * start() 本身不会主动启动 GPS。
     *
     * 只有：
     * 1. 应用程序前台打开
     * 2. noteWalking()
     *
     * 才会调用 registerActive()
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(ctx: Context) {

        if (tracking) {
            return
        }

        if (!hasLocPerm(ctx)) {
            Log.w(
                TAG,
                "Location permission not available"
            )
            return
        }

        try {

            val lm =
                ctx.applicationContext
                    .getSystemService(
                        LocationManager::class.java
                    )
                    ?: return

            locationMgr = lm

            ensureThread()

            tracking = true

            if (uiOpen) {
                registerActive()
            }

        } catch (e: SecurityException) {

            Log.e(
                TAG,
                "start failed: permission/security",
                e
            )

            tracking = false
        }
    }

    // 后台步态检测确认开始行走：未确认室内才开启 GPS/GNSS（已确认则靠 30s 缓存抑制）
    @Synchronized
    fun noteWalking() {

        if (!tracking || uiOpen) {
            return
        }

        // 已确认室内（含缓存有效期）：抑制已定，不开 GPS
        if (isIndoorNow()) {

            walkOpen = false

            unregisterActive()

            return
        }

        if (!walkOpen) {

            walkOpen = true

            registerActive()
        }
    }


    // 后台无行走：立即关闭 GPS（复走靠 30s 室内缓存零等待 + 按需重确认）
    @Synchronized
    fun noteIdle() {

        if (!tracking || uiOpen || !walkOpen) {
            return
        }

        walkOpen = false

        unregisterActive()
    }

    /**
     * 程序前台打开：持续 GPS/GNSS
     * 页面关闭：立即停止 active 定位
     */
    @Synchronized
    fun setUiOpen(open: Boolean) {

        uiOpen = open

        if (!tracking) {
            return
        }

        // 页面模式与后台行走模式互斥
        walkOpen = false

        if (open) {

            registerActive()

        } else {

            unregisterActive()
        }
    }


    // ============================================================
    // Stop
    // ============================================================

    @Synchronized
    fun stop() {

        if (!tracking) {
            return
        }

        tracking = false
        walkOpen = false
        uiOpen = false

        gpsHandler?.removeCallbacks(
            legacyGnssRegister
        )

        unregisterActive()

        locationMgr = null

        resetGnssState()

        lastGpsFixAt = 0L
        lastGpsAcc = Float.MAX_VALUE

        indoor = false
        cachedAt = 0L

        indoorConfirmCount = 0
        outdoorConfirmCount = 0

        try {
            gpsThread?.quitSafely()
        } catch (_: Exception) {
        }

        gpsThread = null
        gpsHandler = null
    }


    // ============================================================
    // Active Registration
    // ============================================================

    /**
     * 注册 GPS Location + GNSS Status
     * 只有 uiOpen 或 walkOpen 时才应调用
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    private fun registerActive() {

        if (!tracking) {
            return
        }

        if (!wantActive()) {
            return
        }

        val lm =
            locationMgr
                ?: return

        val looper =
            gpsThread?.looper
                ?: return

        // GPS Location
        if (!gpsUpdatesOn) {

            try {

                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    LOCATION_INTERVAL_MS,
                    0f,
                    activeListener,
                    looper
                )

                gpsUpdatesOn = true

            } catch (e: Exception) {

                gpsUpdatesOn = false

                Log.w(
                    TAG,
                    "register GPS location failed",
                    e
                )
            }
        }

        // GNSS Status
        if (!gnssOn) {

            if (Build.VERSION.SDK_INT >= 30) {

                try {

                    val registered =
                        lm.registerGnssStatusCallback(
                            Executor { command ->
                                gpsHandler?.post(command)
                            },
                            gnssCallback
                        )

                    gnssOn = registered

                    if (!registered) {

                        Log.w(
                            TAG,
                            "register GNSS callback returned false"
                        )
                    }

                } catch (e: Exception) {

                    gnssOn = false

                    Log.w(
                        TAG,
                        "register GNSS callback failed",
                        e
                    )
                }

            } else {

                // Android 7~10：在 GPS HandlerThread 上执行注册
                gpsHandler?.removeCallbacks(
                    legacyGnssRegister
                )

                gpsHandler?.post(
                    legacyGnssRegister
                )
            }
        }

        activeOn =
            gpsUpdatesOn || gnssOn
    }


    // ============================================================
    // Legacy GNSS Registration
    // ============================================================

    @Suppress("DEPRECATION")
    @Synchronized
    private fun registerGnssLegacy() {

        if (!tracking || !wantActive()) {
            return
        }

        if (gnssOn) {
            return
        }

        try {

            val registered =
                locationMgr?.registerGnssStatusCallback(
                    gnssCallback
                ) == true

            gnssOn = registered

            activeOn =
                gpsUpdatesOn || gnssOn

            if (!registered) {

                Log.w(
                    TAG,
                    "legacy GNSS registration returned false"
                )
            }

        } catch (e: Exception) {

            gnssOn = false

            activeOn =
                gpsUpdatesOn || gnssOn

            Log.w(
                TAG,
                "legacy GNSS registration failed",
                e
            )
        }
    }


    // ============================================================
    // Active Unregister
    // ============================================================

    @Synchronized
    private fun unregisterActive() {

        gpsHandler?.removeCallbacks(
            legacyGnssRegister
        )

        if (gpsUpdatesOn) {

            try {
                locationMgr?.removeUpdates(
                    activeListener
                )
            } catch (_: Exception) {
            }

            gpsUpdatesOn = false
        }

        if (gnssOn) {

            if (Build.VERSION.SDK_INT >= 24) {

                try {
                    locationMgr?.unregisterGnssStatusCallback(
                        gnssCallback
                    )
                } catch (_: Exception) {
                }
            }

            gnssOn = false
        }

        activeOn = false
    }


    // ============================================================
    // GNSS Callback
    // ============================================================

    private val gnssCallback =
        object : GnssStatus.Callback() {

            override fun onSatelliteStatusChanged(
                status: GnssStatus
            ) {

                var fix = 0
                var vis = 0

                val total =
                    status.satelliteCount

                for (i in 0 until total) {

                    val cn0 =
                        status.getCn0DbHz(i)

                    // 防止异常 CN0 污染统计
                    if (cn0.isNaN() || cn0 < 0f) {
                        continue
                    }

                    // 辅助统计： usedInFix + CN0 >= 18
                    if (
                        status.usedInFix(i) &&
                        cn0 >= CN0_STRONG
                    ) {
                        fix++
                    }

                    // 真正的可见强星：只依据 CN0
                    if (cn0 >= VIS_CN0) {
                        vis++
                    }
                }

                fixStrong = fix

                visStrongCurrent = vis

                satelliteCountCurrent = total

                // 显示：定位星/强星/总数（均为当前值）
                satInfo =
                    "$fix/$vis/$total"

                lastGnssAt =
                    SystemClock.elapsedRealtime()

                refresh()
            }
        }


    /**
     * true：确认室内，抑制提醒
     * false：室外或未知，允许提醒
     */
    fun isIndoorNow(): Boolean {

        val now =
            SystemClock.elapsedRealtime()

        // 高精度 GPS： 直接放行室外
        val gpsGood =
            lastGpsFixAt > 0L &&
                now - lastGpsFixAt < GPS_FIX_VALID_MS &&
                lastGpsAcc <= GPS_GOOD_ACC_M

        if (gpsGood) {
            return false
        }

        // GNSS 新鲜（含 0 星深室内）直接沿用 verdict；过期走室内缓存
        val gnssLive =
            lastGnssAt > 0L &&
                now - lastGnssAt < GNSS_VALID_MS

        if (gnssLive) {
            return indoor
        }

        /**
         * 没有新鲜 GNSS：
         * 只有之前已经确认室内， 且缓存还没过期时才继续抑制
         */
        if (indoor && cachedAt > 0L) {

            val cacheAge =
                now - cachedAt

            if (cacheAge < INDOOR_CACHE_MS) {
                return true
            }

            // 缓存过期：未知按室外放行
            indoor = false
            cachedAt = 0L
        }

        return false
    }


    // ============================================================
    // Location Fix
    // ============================================================

    /**
     * 处理 GPS Location。
     * 只接受 GPS_PROVIDER
     */
    private fun onFix(loc: Location) {

        if (
            loc.provider !=
            LocationManager.GPS_PROVIDER
        ) {
            return
        }

        val age =
            loc.ageMs()

        // 旧 Location 不接受
        if (
            age < 0L ||
            age > GPS_FIX_VALID_MS
        ) {
            return
        }

        val now =
            SystemClock.elapsedRealtime()

        lastGpsFixAt =
            now - age

        lastGpsAcc =
            if (loc.hasAccuracy()) {
                loc.accuracy
            } else {
                Float.MAX_VALUE
            }

        refresh()
    }

    // 根据 elapsedRealtimeNanos 计算 Location 年龄
    private fun Location.ageMs(): Long {

        return try {

            val locationNanos =
                elapsedRealtimeNanos

            val nowNanos =
                SystemClock.elapsedRealtimeNanos()

            if (locationNanos <= 0L) {

                Long.MAX_VALUE

            } else if (
                locationNanos > nowNanos
            ) {

                0L

            } else {

                (
                    nowNanos - locationNanos
                ) / 1_000_000L
            }

        } catch (_: Exception) {

            Long.MAX_VALUE
        }
    }


    // 更新室内/室外状态
    private fun refresh() {

        val now =
            SystemClock.elapsedRealtime()

        // GPS 高精度直接放行
        val gpsGood =
            lastGpsFixAt > 0L &&
                now - lastGpsFixAt < GPS_FIX_VALID_MS &&
                lastGpsAcc <= GPS_GOOD_ACC_M

        if (gpsGood) {

            indoor = false

            cachedAt = 0L

            indoorConfirmCount = 0
            outdoorConfirmCount = 0

            return
        }

        // 无新鲜 GNSS 不改状态，isIndoorNow() 处理缓存过期
        val gnssFresh =
            lastGnssAt > 0L &&
                now - lastGnssAt < GNSS_VALID_MS

        if (!gnssFresh) {
            return
        }

        // 直接用当前值判决，无滑动平均延迟
        val total =
            satelliteCountCurrent

        val vis =
            visStrongCurrent

        val fix =
            fixStrong

        val ratio =
            if (total > 0) vis.toFloat() / total.toFloat()
            else 0f

        // 室外：强星多 / 定位星多 / 高占比
        val outdoorEvidence =
            vis >= VIS_OUTDOOR ||
                fix >= FIX_OUTDOOR ||
                (
                    total >= MIN_SATS_FOR_RATIO_OUTDOOR &&
                        ratio >= VIS_RATIO_OUTDOOR
                )

        // 室内：常规弱信号，或小样本深室内（总数<=3 时占比无意义）
        val indoorEvidence =
            (
                total >= MIN_SATS_FOR_INDOOR &&
                    vis <= VIS_INDOOR &&
                    ratio <= VIS_RATIO_INDOOR
            ) ||
                (
                    total <= LOW_SATS_INDOOR &&
                        vis <= VIS_INDOOR &&
                        fix < FIX_OUTDOOR
                )

        // 状态机：室外切室内需 2 连击；室内切室外强信号 1 次即出，其余 2 次
        if (!indoor) {

            // 非室内连续 2 次室内才进入
            if (
                indoorEvidence &&
                !outdoorEvidence
            ) {

                indoorConfirmCount++
                outdoorConfirmCount = 0

                if (
                    indoorConfirmCount >=
                    CONFIRM_SAMPLES
                ) {

                    indoor = true

                    cachedAt = now

                    indoorConfirmCount = 0
                    outdoorConfirmCount = 0

                    Log.d(
                        TAG,
                        "Indoor confirmed: " +
                            "fix=$fix " +
                            "vis=$vis " +
                            "total=$total " +
                            "ratio=${
                                "%.2f".format(ratio)
                            }"
                    )
                }

            } else {

                indoorConfirmCount = 0
                outdoorConfirmCount = 0
            }

        } else {

            // 已室内连续出现室外才切出
            if (outdoorEvidence) {

                // 强室外单次即出，否则需 2 次
                val need =
                    if (vis >= VIS_STRONG_OUTDOOR) 1
                    else CONFIRM_SAMPLES

                outdoorConfirmCount++
                indoorConfirmCount = 0

                if (
                    outdoorConfirmCount >=
                    need
                ) {

                    indoor = false

                    // 确认室外清室内缓存
                    cachedAt = 0L

                    indoorConfirmCount = 0
                    outdoorConfirmCount = 0

                    Log.d(
                        TAG,
                        "Outdoor confirmed: " +
                            "fix=$fix " +
                            "vis=$vis " +
                            "total=$total " +
                            "ratio=${
                                "%.2f".format(ratio)
                            }"
                    )
                }

            } else {

                outdoorConfirmCount = 0

                // GNSS 新鲜则刷新室内缓存
                cachedAt = now
            }
        }
    }


    // ============================================================
    // Reset
    // ============================================================

    private fun resetGnssState() {

        fixStrong = 0

        visStrongCurrent = 0
        satelliteCountCurrent = 0

        lastGnssAt = 0L

        satInfo = "未知"

        indoorConfirmCount = 0
        outdoorConfirmCount = 0
    }
}