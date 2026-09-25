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
 * GPS 室内/室外判断。
 *
 * 核心目标：
 * 1. 室外行走时尽量不要漏掉提醒；
 * 2. 室内尽量抑制提醒；
 * 3. 不因为单次 GNSS 波动立即改变状态；
 * 4. 不依赖其他 GPS App 是否同时运行；
 * 5. 后台尽量降低功耗。
 *
 * 判定依据：
 * - GPS 定位精度；
 * - CN0 强信号卫星数量；
 * - 强信号卫星占比；
 * - usedInFix 仅用于辅助显示，不作为“强星”的必要条件。
 *
 * 未知状态按户外放行。
 */
object IndoorDetector {

    private const val TAG = "IndoorDetector"

    // ============================================================
    // 判定参数
    // ============================================================

    /**
     * 18 dB-Hz：
     * 用于“定位强星”统计，仅供状态页/调试参考。
     */
    private const val CN0_STRONG = 18f

    /**
     * 20 dB-Hz：
     * 用于真正的“可见强星”统计。
     *
     * 不要求 usedInFix。
     */
    private const val VIS_CN0 = 20f

    /**
     * GPS 精度 <= 25m：
     * 作为强室外证据，直接判定室外。
     */
    private const val GPS_GOOD_ACC_M = 25f

    /**
     * 室外判定：
     *
     * 可见强星 >= 8
     * 或
     * 强星占比 >= 45%，同时至少有 6 颗卫星
     */
    private const val VIS_OUTDOOR = 8
    private const val VIS_RATIO_OUTDOOR = 0.45f
    private const val MIN_SATS_FOR_RATIO_OUTDOOR = 6

    /**
     * 室内候选：
     *
     * 总卫星至少 4；
     * 强星 <= 3；
     * 强星占比 <= 18%。
     *
     * 注意：不再要求 fixStrong <= 1，
     * 避免 usedInFix 的设备差异影响室内判定。
     */
    private const val VIS_INDOOR = 3
    private const val VIS_RATIO_INDOOR = 0.18f
    private const val MIN_SATS_FOR_INDOOR = 4

    /**
     * 连续多少次 GNSS 状态满足条件后才切换状态。
     *
     * GNSS Status 通常更新速度明显高于 Location，
     * 3 次可以兼顾响应速度和抗抖。
     */
    private const val CONFIRM_SAMPLES = 3

    /**
     * GNSS 状态滑动窗口。
     */
    private const val SAT_WIN = 3

    /**
     * GNSS 状态最多保持多久算新鲜。
     */
    private const val GNSS_VALID_MS = 60_000L

    /**
     * GPS Location 最多保持多久算新鲜。
     */
    private const val GPS_FIX_VALID_MS = 60_000L

    /**
     * 连续较长时间没有 GPS Fix。
     * 注意：现在不再据此直接判定室内。
     */
    private const val GPS_STALE_MS = 90_000L

    /**
     * 只缓存已经确认的室内结论。
     *
     * 从原来的 3 分钟缩短到 60 秒，
     * 避免刚走出室内时仍长期使用旧结论。
     */
    private const val INDOOR_CACHE_MS = 60_000L

    /**
     * GPS 无数据时的 watchdog。
     */
    private const val WATCHDOG_MS = 60_000L

    /**
     * 连续这么久没有 GNSS / GPS 数据时，
     * 重建一次订阅。
     */
    private const val STALL_MS = 120_000L

    /**
     * Location 更新间隔。
     *
     * GNSS Status 本身并不受此参数直接限制。
     */
    private const val LOCATION_INTERVAL_MS = 10_000L

    /**
     * noteIdle() 后延迟关闭 GPS。
     *
     * 防止步态识别短时间抖动：
     * Walking -> Idle -> Walking
     * 造成反复 register / unregister。
     */
    private const val IDLE_CLOSE_DELAY_MS = 30_000L


    // ============================================================
    // 对外状态
    // ============================================================

    /**
     * true = 当前认为在室内。
     * false = 室外或未知。
     */
    @Volatile
    var indoor = false
        private set

    /**
     * 格式：
     *
     * fixStrong / visStrong / satelliteCount
     *
     * 例如：
     * 5/11/25
     *
     * 5  = usedInFix && CN0 >= 18 的卫星数量
     * 11 = CN0 >= 20 的强信号卫星数量（经过中位数平滑）
     * 25 = 当前可见卫星总数
     */
    @Volatile
    var satInfo = "未知"
        private set

    @Volatile
    private var tracking = false

    @Volatile
    private var uiOpen = false

    @Volatile
    private var walkOpen = false

    /**
     * 当前是否至少有 GPS Location 或 GNSS Status 通道。
     */
    @Volatile
    private var activeOn = false

    /**
     * 室内确认缓存时间。
     */
    @Volatile
    private var cachedAt = 0L


    // ============================================================
    // GNSS / GPS 状态
    // ============================================================

    /**
     * 当前回调中的 usedInFix + CN0 >= 18 数量。
     * 仅辅助显示，不作为核心室内判断依据。
     */
    private var fixStrong = 0

    /**
     * 当前原始 CN0 >= 20 数量。
     */
    private var visStrongCurrent = 0

    /**
     * 当前原始卫星总数。
     */
    private var satelliteCountCurrent = 0

    /**
     * 平滑后的强星数量。
     */
    private var visStrongMedian = 0

    /**
     * 平滑后的卫星总数。
     */
    private var satelliteCountMedian = 0

    private val visWin = ArrayDeque<Int>()
    private val satWin = ArrayDeque<Int>()

    /**
     * 连续室内候选次数。
     */
    private var indoorConfirmCount = 0

    /**
     * 连续室外候选次数。
     */
    private var outdoorConfirmCount = 0

    private var lastGnssAt = 0L
    private var lastGpsFixAt = 0L
    private var lastGpsAcc = Float.MAX_VALUE


    // ============================================================
    // Location / Thread
    // ============================================================

    private var locationMgr: LocationManager? = null

    private var gpsThread: HandlerThread? = null
    private var gpsHandler: Handler? = null

    /**
     * 当前 GPS Location 更新是否成功注册。
     */
    private var gpsUpdatesOn = false

    /**
     * 当前 GNSS Status 是否成功注册。
     */
    private var gnssOn = false


    // ============================================================
    // LocationListener
    // ============================================================

    private val activeListener = object : LocationListener {

        override fun onLocationChanged(loc: Location) {
            onFix(loc)
        }

        override fun onProviderDisabled(provider: String) {
            if (provider == LocationManager.GPS_PROVIDER) {
                lastGpsFixAt = 0L
                lastGpsAcc = Float.MAX_VALUE

                // GPS 被关闭后，之前的 GNSS 状态也不应继续参与判断。
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


    // ============================================================
    // Passive Location
    // ============================================================

    /**
     * 被动复用其他 App / 系统已经产生的位置。
     *
     * 不主动启动 GPS。
     *
     * 这里仍保留，但 onFix() 只接受 GPS_PROVIDER，
     * 不再把 NETWORK_PROVIDER 当作室内依据。
     */
    private val passiveListener = object : LocationListener {

        override fun onLocationChanged(loc: Location) {
            onFix(loc)
        }

        override fun onProviderDisabled(provider: String) = Unit

        override fun onProviderEnabled(provider: String) = Unit

        @Deprecated("老系统兼容")
        override fun onStatusChanged(
            provider: String?,
            status: Int,
            extras: Bundle?
        ) {
            Unit
        }
    }


    // ============================================================
    // GNSS Callback
    // ============================================================

    private val gnssCallback = object : GnssStatus.Callback() {

        override fun onSatelliteStatusChanged(status: GnssStatus) {

            var fix = 0
            var vis = 0

            val total = status.satelliteCount

            for (i in 0 until total) {

                val cn0 = status.getCn0DbHz(i)

                // 避免异常值污染统计。
                if (cn0.isNaN() || cn0 < 0f) {
                    continue
                }

                /**
                 * 用于辅助显示。
                 *
                 * 不再把 usedInFix 作为真正“强星”的必要条件。
                 */
                if (status.usedInFix(i) && cn0 >= CN0_STRONG) {
                    fix++
                }

                /**
                 * 真正的可见强星：
                 *
                 * 只看 CN0。
                 */
                if (cn0 >= VIS_CN0) {
                    vis++
                }
            }

            fixStrong = fix
            visStrongCurrent = vis
            satelliteCountCurrent = total

            // ----------------------------------------------------
            // 滑动窗口
            // ----------------------------------------------------

            visWin.addLast(vis)
            while (visWin.size > SAT_WIN) {
                visWin.removeFirst()
            }

            satWin.addLast(total)
            while (satWin.size > SAT_WIN) {
                satWin.removeFirst()
            }

            visStrongMedian = median(visWin)
            satelliteCountMedian = median(satWin)

            satInfo =
                "$fix/$visStrongMedian/$total"

            lastGnssAt = SystemClock.elapsedRealtime()

            refresh()
        }
    }


    // ============================================================
    // Runnables
    // ============================================================

    private val watchdog = Runnable {
        onWatchdog()
    }

    private val delayedIdleClose = Runnable {
        onIdleTimeout()
    }

    /**
     * Android 7~10 的 GNSS 注册需要从指定线程发起。
     */
    private val legacyGnssRegister = Runnable {
        registerGnssLegacy()
    }


    // ============================================================
    // Watchdog
    // ============================================================

    @Synchronized
    private fun onWatchdog() {

        if (!tracking) {
            return
        }

        val now = SystemClock.elapsedRealtime()

        val gnssStalled =
            now - lastGnssAt > STALL_MS

        val gpsStalled =
            now - lastGpsFixAt > STALL_MS

        val stalled =
            gnssStalled && gpsStalled

        if (wantActive()) {

            /**
             * active 通道应该存在。
             *
             * 如果 GPS Location 没注册，
             * 或 GNSS Status 没注册，
             * 尝试补注册。
             */
            if (!gpsUpdatesOn || !gnssOn) {
                registerActive()
            }

            /**
             * 如果两个通道都长时间没有数据，
             * 重新建立订阅。
             */
            else if (activeOn && stalled) {
                Log.w(TAG, "GNSS stalled, rebuilding subscriptions")
                unregisterActive()
                registerActive()
            }

        } else {

            /**
             * 理论上 noteIdle() / setUiOpen(false)
             * 会主动注销，这里再保险一次。
             */
            if (activeOn) {
                unregisterActive()
            }
        }

        gpsHandler?.postDelayed(
            watchdog,
            WATCHDOG_MS
        )
    }


    // ============================================================
    // Permission / Location Switch
    // ============================================================

    private fun hasLocPerm(ctx: Context): Boolean {
        return PermissionHelper.isLocationEnough(ctx)
    }

    /**
     * 位置总开关。
     *
     * 保留原有 API，避免影响其他调用代码。
     */
    fun isLocationOn(ctx: Context): Boolean {

        return try {

            val lm =
                ctx.applicationContext
                    .getSystemService(LocationManager::class.java)
                    ?: return false

            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        } catch (_: Exception) {

            false
        }
    }


    // ============================================================
    // Thread
    // ============================================================

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


    // ============================================================
    // Start
    // ============================================================

    /**
     * 开始追踪。
     *
     * 页面打开：
     *   实时 GPS/GNSS。
     *
     * 页面关闭：
     *   只保持 passive；
     *   等 noteWalking() 后才打开 active GNSS。
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(ctx: Context) {

        if (tracking) {
            return
        }

        if (!hasLocPerm(ctx)) {
            Log.w(TAG, "Location permission not available")
            return
        }

        try {

            val lm =
                ctx.applicationContext
                    .getSystemService(LocationManager::class.java)
                    ?: return

            locationMgr = lm

            ensureThread()

            // ----------------------------------------------------
            // GPS 最近位置预热
            // ----------------------------------------------------

            try {

                lm.getLastKnownLocation(
                    LocationManager.GPS_PROVIDER
                )?.let {

                    val age = it.ageMs()

                    if (age <= GPS_FIX_VALID_MS) {
                        onFix(it)
                    }
                }

            } catch (e: Exception) {
                Log.w(TAG, "getLastKnownLocation failed", e)
            }

            tracking = true

            // ----------------------------------------------------
            // 被动位置
            // ----------------------------------------------------

            registerPassive()

            // ----------------------------------------------------
            // 页面前台：立即启动
            // 页面后台：等 noteWalking()
            // ----------------------------------------------------

            if (uiOpen) {
                registerActive()
            }

            gpsHandler?.removeCallbacks(watchdog)

            gpsHandler?.postDelayed(
                watchdog,
                WATCHDOG_MS
            )

        } catch (e: SecurityException) {

            Log.e(TAG, "start failed: permission/security", e)

            tracking = false
        }
    }


    // ============================================================
    // Walking
    // ============================================================

    /**
     * 步态检测确认用户开始走路。
     *
     * 后台：
     *   开启 GPS/GNSS。
     */
    @Synchronized
    fun noteWalking() {

        if (!tracking || uiOpen) {
            return
        }

        /**
         * 如果之前正在等待 Idle 关闭，
         * 取消延迟关闭。
         */
        gpsHandler?.removeCallbacks(
            delayedIdleClose
        )

        if (!walkOpen) {

            walkOpen = true

            registerActive()
        }
    }


    /**
     * 步态检测暂时没有发现走路。
     *
     * 不立即关闭 GPS，
     * 延迟 30 秒再关闭。
     */
    @Synchronized
    fun noteIdle() {

        if (!tracking || uiOpen || !walkOpen) {
            return
        }

        walkOpen = false

        gpsHandler?.removeCallbacks(
            delayedIdleClose
        )

        gpsHandler?.postDelayed(
            delayedIdleClose,
            IDLE_CLOSE_DELAY_MS
        )
    }


    /**
     * 延迟关闭 GPS。
     */
    @Synchronized
    private fun onIdleTimeout() {

        if (!tracking || uiOpen || walkOpen) {
            return
        }

        unregisterActive()
    }


    // ============================================================
    // UI State
    // ============================================================

    /**
     * 页面前台：
     *   实时定位。
     *
     * 页面后台：
     *   立即关闭 active；
     *   后续 noteWalking() 再打开。
     */
    @Synchronized
    fun setUiOpen(open: Boolean) {

        uiOpen = open

        gpsHandler?.removeCallbacks(
            delayedIdleClose
        )

        if (!tracking) {
            return
        }

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

        gpsHandler?.removeCallbacks(
            watchdog
        )

        gpsHandler?.removeCallbacks(
            delayedIdleClose
        )

        gpsHandler?.removeCallbacks(
            legacyGnssRegister
        )

        unregisterActive()

        try {
            locationMgr?.removeUpdates(
                passiveListener
            )
        } catch (_: Exception) {
        }

        locationMgr = null

        /**
         * stop() 代表整个功能关闭，
         * 所以彻底清除 GNSS 状态和室内缓存。
         *
         * 重新 start 后必须重新确认。
         */
        resetGnssState()

        lastGpsFixAt = 0L
        lastGpsAcc = Float.MAX_VALUE

        indoor = false
        cachedAt = 0L
        indoorConfirmCount = 0
        outdoorConfirmCount = 0

        /**
         * 释放线程。
         */
        try {
            gpsThread?.quitSafely()
        } catch (_: Exception) {
        }

        gpsThread = null
        gpsHandler = null
    }


    // ============================================================
    // Passive
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun registerPassive() {

        val lm =
            locationMgr
                ?: return

        val looper =
            gpsThread?.looper
                ?: return

        try {

            lm.requestLocationUpdates(
                LocationManager.PASSIVE_PROVIDER,
                LOCATION_INTERVAL_MS,
                0f,
                passiveListener,
                looper
            )

        } catch (e: Exception) {

            Log.w(TAG, "register passive failed", e)
        }
    }


    // ============================================================
    // Active Registration
    // ============================================================

    @SuppressLint("MissingPermission")
    private fun registerActive() {

        val lm =
            locationMgr
                ?: return

        val looper =
            gpsThread?.looper
                ?: return

        // --------------------------------------------------------
        // GPS Location
        // --------------------------------------------------------

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

        // --------------------------------------------------------
        // GNSS Status
        // --------------------------------------------------------

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

                /**
                 * API 24~29：
                 * 放到 GPS HandlerThread 再执行注册。
                 */
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
    // Indoor Query
    // ============================================================

    /**
     * 是否抑制提醒。
     *
     * 返回 true：
     *   确认处于室内。
     *
     * 返回 false：
     *   室外 / 未知。
     *
     * 核心安全策略：
     *   未知按室外放行。
     */
    fun isIndoorNow(): Boolean {

        val now =
            SystemClock.elapsedRealtime()

        val gnssLive =
            lastGnssAt > 0L &&
                now - lastGnssAt < GNSS_VALID_MS &&
                satelliteCountMedian > 0

        val gpsGood =
            lastGpsFixAt > 0L &&
                now - lastGpsFixAt < GPS_FIX_VALID_MS &&
                lastGpsAcc <= GPS_GOOD_ACC_M

        /**
         * 高质量 GPS Fix：
         * 直接认为室外。
         *
         * 这里优先级最高。
         */
        if (gpsGood) {
            return false
        }

        /**
         * GNSS 数据新鲜：
         * 直接使用实时 verdict。
         */
        if (gnssLive) {
            return indoor
        }

        /**
         * GNSS 暂时没有数据：
         *
         * 只有之前确认过室内，
         * 且缓存还没过期时，才继续 suppress。
         */
        if (indoor && cachedAt > 0L) {

            val cacheAge =
                now - cachedAt

            if (cacheAge < INDOOR_CACHE_MS) {
                return true
            }

            /**
             * 室内缓存过期。
             *
             * 未知按室外放行。
             */
            indoor = false
            cachedAt = 0L
        }

        return false
    }


    // ============================================================
    // Location Fix
    // ============================================================

    private fun onFix(loc: Location) {

        /**
         * 我们现在只把 GPS_PROVIDER
         * 作为真实 GNSS Fix。
         *
         * NETWORK_PROVIDER 不参与室内判定。
         */
        if (loc.provider != LocationManager.GPS_PROVIDER) {
            return
        }

        val age =
            loc.ageMs()

        /**
         * 旧 Location 不更新 lastGpsFixAt。
         *
         * 防止 PASSIVE_PROVIDER 转交旧数据时，
         * 看起来像“刚刚获得 GPS Fix”。
         */
        if (age < 0L || age > GPS_FIX_VALID_MS) {
            return
        }

        val now =
            SystemClock.elapsedRealtime()

        /**
         * 使用 Location 自己的测量时间，
         * 而不是简单使用回调到达时间。
         */
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


    // ============================================================
    // Location Age
    // ============================================================

    /**
     * 使用 elapsedRealtimeNanos 判断 Location 年龄。
     *
     * 比 wall-clock timestamp 更适合判断
     * “这份定位数据到底是不是刚刚产生的”。
     */
    private fun Location.ageMs(): Long {

        return try {

            val locationNanos =
                elapsedRealtimeNanos

            val nowNanos =
                SystemClock.elapsedRealtimeNanos()

            if (locationNanos <= 0L) {
                Long.MAX_VALUE
            } else if (locationNanos > nowNanos) {
                0L
            } else {
                (nowNanos - locationNanos) / 1_000_000L
            }

        } catch (_: Exception) {

            Long.MAX_VALUE
        }
    }


    // ============================================================
    // Refresh / Decision
    // ============================================================

    private fun refresh() {

        val now =
            SystemClock.elapsedRealtime()

        val gnssFresh =
            lastGnssAt > 0L &&
                now - lastGnssAt < GNSS_VALID_MS &&
                satelliteCountMedian > 0

        /**
         * GPS 精度非常好：
         * 直接判室外。
         */
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

        /**
         * GNSS 不新鲜：
         *
         * 不主动把室内/室外翻转。
         *
         * isIndoorNow() 会负责：
         * - 缓存有效 -> 沿用室内
         * - 缓存过期 -> 按室外放行
         *
         * 这样不会因为某次没有 GNSS callback
         * 就突然改变状态。
         */
        if (!gnssFresh) {
            return
        }

        val total =
            satelliteCountMedian

        val vis =
            visStrongMedian

        if (total <= 0) {
            return
        }

        val ratio =
            vis.toFloat() / total.toFloat()

        // --------------------------------------------------------
        // 强室外证据
        // --------------------------------------------------------

        val outdoorEvidence =
            vis >= VIS_OUTDOOR ||
                (
                    total >= MIN_SATS_FOR_RATIO_OUTDOOR &&
                        ratio >= VIS_RATIO_OUTDOOR
                )

        // --------------------------------------------------------
        // 室内候选证据
        // --------------------------------------------------------

        /**
         * 室内判断比室外判断更严格。
         *
         * 原因：
         *
         * “卫星少”本身不能证明室内。
         *
         * 必须：
         * - 总卫星至少 4；
         * - 强星 <= 3；
         * - 强星比例 <= 18%。
         */
        val indoorEvidence =
            total >= MIN_SATS_FOR_INDOOR &&
                vis <= VIS_INDOOR &&
                ratio <= VIS_RATIO_INDOOR


        // ========================================================
        // 状态机
        // ========================================================

        if (!indoor) {

            /**
             * 当前认为室外/未知。
             *
             * 只有连续 3 次室内证据，
             * 才切入室内。
             */
            if (indoorEvidence && !outdoorEvidence) {

                indoorConfirmCount++
                outdoorConfirmCount = 0

                if (indoorConfirmCount >= CONFIRM_SAMPLES) {

                    indoor = true
                    cachedAt = now

                    indoorConfirmCount = 0
                    outdoorConfirmCount = 0

                    Log.d(
                        TAG,
                        "Indoor confirmed: " +
                            "fix=$fixStrong " +
                            "vis=$vis " +
                            "total=$total " +
                            "ratio=${"%.2f".format(ratio)}"
                    )
                }

            } else {

                indoorConfirmCount = 0
                outdoorConfirmCount = 0
            }

        } else {

            /**
             * 当前已经在室内。
             *
             * 只有连续 3 次明确的室外证据，
             * 才退出室内。
             */
            if (outdoorEvidence) {

                outdoorConfirmCount++
                indoorConfirmCount = 0

                if (outdoorConfirmCount >= CONFIRM_SAMPLES) {

                    indoor = false

                    /**
                     * 非常重要：
                     * 确认室外后立即删除旧室内缓存。
                     *
                     * 防止：
                     * 室内 -> 出门 -> GNSS短暂中断
                     * 仍然因为旧缓存判断室内。
                     */
                    cachedAt = 0L

                    indoorConfirmCount = 0
                    outdoorConfirmCount = 0

                    Log.d(
                        TAG,
                        "Outdoor confirmed: " +
                            "fix=$fixStrong " +
                            "vis=$vis " +
                            "total=$total " +
                            "ratio=${"%.2f".format(ratio)}"
                    )
                }

            } else {

                outdoorConfirmCount = 0

                /**
                 * 只要 GNSS 仍然新鲜，
                 * 室内状态就可以继续刷新缓存。
                 *
                 * 这样停止 active 后短时间内仍可快速复用。
                 */
                cachedAt = now
            }
        }
    }


    // ============================================================
    // Median
    // ============================================================

    private fun median(values: ArrayDeque<Int>): Int {

        if (values.isEmpty()) {
            return 0
        }

        val sorted =
            values.toList().sorted()

        return sorted[
            sorted.size / 2
        ]
    }


    // ============================================================
    // GNSS State Reset
    // ============================================================

    private fun resetGnssState() {

        fixStrong = 0

        visStrongCurrent = 0
        satelliteCountCurrent = 0

        visStrongMedian = 0
        satelliteCountMedian = 0

        visWin.clear()
        satWin.clear()

        lastGnssAt = 0L

        satInfo = "未知"

        indoorConfirmCount = 0
        outdoorConfirmCount = 0
    }
}