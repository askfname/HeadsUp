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
import com.playlab.headsup.data.Prefs
import java.util.concurrent.Executor

/**
 * GPS 室内判断：
 * 仅在证据明确时确认室内或室外，边界信号保持未知
 */
object IndoorDetector {

    private const val TAG = "IndoorDetector"

    private const val CN0_STRONG = 18f

    // 强星
    private const val VIS_CN0 = 25f

    // GPS 精度
    private const val GPS_GOOD_ACC_M = 10f
    // GPS 精度确认次数（单次可能是室内漂移虚 fix）
    private const val GPS_GOOD_NEED = 2

    // 大样本使用强星比例为主、按卫星规模归一化后的强星数为辅
    private const val VIS_INDOOR = 7
    private const val VIS_RATIO_INDOOR = 0.4f
    private const val BIG_TOTAL = 8
    private const val STRONG_COUNT_REFERENCE_SATS = 24

    // 室外确认滞后：必须同时具备足够的强星数量和比例
    private const val EXIT_VIS_MARGIN = 3
    private const val EXIT_RATIO_MARGIN = 0.2f
    private const val EXIT_RATIO_MAX = 0.9f

    // 连续满足/不满足确认数后切换
    private const val CONFIRM_SAMPLES = 2

    // 独立 GNSS 样本：回调密集时只采一份，避免重复状态快速形成结论
    private const val GNSS_SAMPLE_MIN_INTERVAL_MS = 1_000L
    private const val GNSS_SAMPLE_WINDOW_MS = 8_000L
    private const val MIN_GNSS_SAMPLE_SPAN_MS = 2_000L

    // 无行走后延迟关闭：短暂停走不断流，复走结论仍新鲜
    private const val LINGER_MS = 10_000L

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
    private var outdoor = false

    @Volatile
    var satInfo = ""
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

    // 自定义参数：null 用默认；通过高级设置下发
    @Volatile
    var customParams: Prefs.IndoorParams? = null
        private set

    // 应用自定义，CN0 改变后旧窗口无法重算，必须重新收集样本
    @Synchronized
    fun applyCustom(p: Prefs.IndoorParams?) {
        if (customParams == p) return
        customParams = p
        indoor = false
        outdoor = false
        cachedAt = 0L
        lastGpsFixAt = 0L
        lastGpsAcc = Float.MAX_VALUE
        resetGnssState()
    }

    // 最近独立 GNSS 样本的时间，必须填满滑窗且覆盖足够时间才允许判决
    private val sampleTimes = ArrayDeque<Long>()

    // 位置结论三态：挂起提醒用
    enum class Verdict { INDOOR, OUTDOOR, UNKNOWN }

    // 本轮好定位连续次数（否决用，防单次漂移）
    @Volatile
    private var gpsStreak = 0

    // 上次确认室内的时间
    @Volatile
    private var cachedAt = 0L

    // 强星、有效 CN0 星、报告总星和定位星滑动窗口：中位数抗单次波动
    private const val SAT_WIN = 3
    private val visWin = ArrayDeque<Int>()
    private val validWin = ArrayDeque<Int>()
    private val reportedWin = ArrayDeque<Int>()
    private val fixWin = ArrayDeque<Int>()

    // 连续室内候选次数
    @Volatile
    private var indoorConfirmCount = 0

    // 连续室外候选次数
    @Volatile
    private var outdoorConfirmCount = 0

    // 上次收到 GnssStatus 的时间
    @Volatile
    private var lastGnssAt = 0L

    // 上次收到有效 GPS Location 的时间
    @Volatile
    private var lastGpsFixAt = 0L

    // 上一次 GPS 精度
    @Volatile
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

    // 无行走延迟关闭任务：到期仍无人用才真正断流
    private val lingerStop = Runnable {
        stopIfIdle()
    }

    @Synchronized
    private fun stopIfIdle() {
        if (!tracking || wantActive()) {
            return
        }
        unregisterActive()
    }

    // 调度/取消延迟关闭
    @Synchronized
    private fun scheduleLinger() {
        val h = gpsHandler ?: return
        h.removeCallbacks(lingerStop)
        h.postDelayed(lingerStop, LINGER_MS)
    }

    @Synchronized
    private fun cancelLinger() {
        gpsHandler?.removeCallbacks(lingerStop)
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


    // GNSS 是否新鲜（状态卡用，避免显示过期数据）
    fun isGnssFresh(): Boolean {
        return lastGnssAt > 0L &&
            SystemClock.elapsedRealtime() - lastGnssAt < GNSS_VALID_MS
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
     * start() 本身不会主动启动 GPS
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

            // 首次启动载入自定义参数
            customParams = Prefs.getIndoorCustomOrNull(ctx)

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

    // 后台步态检测确认开始行走：未确认室内才开启 GPS/GNSS（已确认则靠短效缓存抑制）
    @Synchronized
    fun noteWalking() {

        if (!tracking || uiOpen) {
            return
        }

        cancelLinger()

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


    // 后台无行走：延迟关闭 GPS（短暂停走不断流，复走结论仍新鲜）
    @Synchronized
    fun noteIdle() {

        if (!tracking || uiOpen || !walkOpen) {
            return
        }

        walkOpen = false

        scheduleLinger()
    }

    /**
     * 程序前台打开：持续 GPS/GNSS
     * 页面关闭：延迟停止 active 定位
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

            cancelLinger()

            registerActive()

        } else {

            scheduleLinger()
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
            legacyGnssRegister
        )

        gpsHandler?.removeCallbacks(
            lingerStop
        )

        unregisterActive()

        locationMgr = null

        resetGnssState()

        lastGpsFixAt = 0L
        lastGpsAcc = Float.MAX_VALUE

        indoor = false
        outdoor = false
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

    @SuppressLint("MissingPermission")
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

        sampleTimes.clear()

        gpsStreak = 0

        visWin.clear()
        validWin.clear()
        reportedWin.clear()
        fixWin.clear()
    }


    // ============================================================
    // GNSS Callback
    // ============================================================

    private val gnssCallback =
        object : GnssStatus.Callback() {

            override fun onSatelliteStatusChanged(
                status: GnssStatus
            ) {
                // 整段原子更新，避免与 verdict() 交错读到半更新窗口
                synchronized(this@IndoorDetector) {
                    var fix = 0
                    var vis = 0
                    var valid = 0
                    val total = status.satelliteCount

                    // 强星门限自定义（无自定义用默认）；定位星固定18
                    val visCn0 = customParams?.visCn0 ?: VIS_CN0

                    for (i in 0 until total) {

                        val cn0 =
                            status.getCn0DbHz(i)

                        // 防止异常 CN0 污染统计
                        if (cn0.isNaN() || cn0 < 0f) {
                            continue
                        }
                        valid++

                        // 辅助统计： usedInFix + CN0 >= 18
                        if (
                            status.usedInFix(i) &&
                            cn0 >= CN0_STRONG
                        ) {
                            fix++
                        }

                        // 真正的可见强星：只依据 CN0
                        if (cn0 >= visCn0) {
                            vis++
                        }
                    }

                    val now = SystemClock.elapsedRealtime()
                    lastGnssAt = now

                    // 将高频重复回调合并为独立时间样本
                    if (sampleTimes.isNotEmpty() &&
                        now - sampleTimes.last() < GNSS_SAMPLE_MIN_INTERVAL_MS
                    ) {
                        return
                    }

                    // 滑动窗口
                    pushWin(visWin, vis)
                    pushWin(validWin, valid)
                    pushWin(reportedWin, total)
                    pushWin(fixWin, fix)
                    sampleTimes.addLast(now)
                    trimSampleTimes(now)
                    while (sampleTimes.size > SAT_WIN) {
                        sampleTimes.removeFirst()
                    }

                    // 显示使用报告总星；判定比例使用仅含有效 CN0 的 validWin
                    satInfo =
                        "${median(fixWin)}/${median(visWin)}/${median(reportedWin)}"

                    refresh()
                }
            }
        }


    /**
     * true：确认室内，抑制提醒
     * false：室外或未知
     */
    @Synchronized
    fun isIndoorNow(): Boolean {

        val now =
            SystemClock.elapsedRealtime()

        sweepExpired(now)

        // GNSS 新鲜或缓存有效都沿用结论，其余按室外放行
        return indoor
    }


    /**
     * 三态结论：室内明确抑制；室外发提醒；未知继续等待采样
     */
    @Synchronized
    fun verdict(): Verdict {

        val now =
            SystemClock.elapsedRealtime()

        sweepExpired(now)

        if (indoor) {
            return Verdict.INDOOR
        }
        if (!hasStableGnssSamples(now)) {
            return Verdict.UNKNOWN
        }
        return if (outdoor || gpsGoodNow(now)) Verdict.OUTDOOR else Verdict.UNKNOWN
    }

    // 无新鲜 GNSS 且室内缓存过期时清结论
    @Synchronized
    private fun sweepExpired(now: Long) {
        if (indoor &&
            (lastGnssAt <= 0L || now - lastGnssAt >= GNSS_VALID_MS) &&
            (cachedAt <= 0L || now - cachedAt >= INDOOR_CACHE_MS)
        ) {
            indoor = false
            cachedAt = 0L
        }
    }

    // 高精度 GPS 是否有效：连续好定位才算；卫星明显室内时视为漂移，不判室外
    @Synchronized
    private fun gpsGoodNow(now: Long): Boolean {
        if (gpsStreak < GPS_GOOD_NEED) {
            return false
        }

        val accThr =
            customParams?.gpsAcc ?: GPS_GOOD_ACC_M

        if (lastGpsFixAt <= 0L ||
            now - lastGpsFixAt >= GPS_FIX_VALID_MS ||
            lastGpsAcc > accThr
        ) {
            return false
        }

        // 卫星明显室内则屏蔽 GPS（防室内漂移虚 fix）
        return !satIndoorNow(now)
    }

    // 卫星是否明显室内：与 refresh() 进入条件一致
    @Synchronized
    private fun satIndoorNow(now: Long): Boolean {
        if (lastGnssAt <= 0L || now - lastGnssAt >= GNSS_VALID_MS) {
            return false
        }
        if (!hasStableGnssSamples(now)) {
            return false
        }
        val p = customParams
        val visIn = p?.visIndoor ?: VIS_INDOOR
        val ratioIn = p?.ratioIndoor ?: VIS_RATIO_INDOOR
        val total = median(validWin)
        val vis = median(visWin)
        val fix = median(fixWin)
        val ratio = if (total > 0) vis.toFloat() / total.toFloat() else 0f
        return if (total >= BIG_TOTAL) {
            vis <= indoorStrongLimit(total, visIn) && ratio <= ratioIn
        } else {
            vis <= visIn && fix <= 2
        }
    }


    // ============================================================
    // Location Fix
    // ============================================================

    /**
     * 处理 GPS Location
     * 只接受 GPS_PROVIDER
     */
    @Synchronized
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

        // 好定位连续计数：差定位清零（封顶防无界增长，阈值2不变）
        val accThr =
            customParams?.gpsAcc ?: GPS_GOOD_ACC_M

        gpsStreak =
            if (lastGpsAcc <= accThr) (gpsStreak + 1).coerceAtMost(100)
            else 0

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


    // 更新室内外状态：边界带保持未知，避免将信号冲突误当作室外
    @Synchronized
    private fun refresh() {

        val now =
            SystemClock.elapsedRealtime()

        val p = customParams
        val visIn = p?.visIndoor ?: VIS_INDOOR
        val ratioIn = p?.ratioIndoor ?: VIS_RATIO_INDOOR

        // 必须有满三项、跨足够时间的独立样本，避免冷启动和高频重复回调误判
        if (!hasStableGnssSamples(now)) {
            return
        }

        // GPS 连续高精度才按室外（单次漂移不断 verdict，交给卫星逻辑）
        if (gpsGoodNow(now)) {
            indoor = false
            outdoor = true
            cachedAt = 0L
            indoorConfirmCount = 0
            outdoorConfirmCount = 0
            return
        }

        // 中位数判决，抗单次波动
        val total = median(validWin)

        val vis =
            median(visWin)

        val fix =
            median(fixWin)

        val ratio =
            if (total > 0) vis.toFloat() / total.toFloat()
            else 0f

        // 进入：大样本以比例为主，并按有效卫星规模放宽绝对强星数
        val indoorEvidence =
            if (total >= BIG_TOTAL) {
                vis <= indoorStrongLimit(total, visIn) &&
                    ratio <= ratioIn
            } else {
                vis <= visIn &&
                    fix <= 2
            }

        // 明确室外必须同时满足数量与比例，避免高总星数环境被单一数量压倒
        val outdoorEvidence =
            if (total >= BIG_TOTAL) {
                vis >= outdoorStrongLimit(total, visIn) &&
                    ratio >= minOf(ratioIn + EXIT_RATIO_MARGIN, EXIT_RATIO_MAX)
            } else {
                vis >= visIn + EXIT_VIS_MARGIN && fix >= 3
            }

        if (indoorEvidence) {

            outdoorConfirmCount = 0
            outdoor = false

            if (indoor) {

                // 已室内则刷新缓存
                cachedAt = now

            } else if (
                ++indoorConfirmCount >=
                CONFIRM_SAMPLES
            ) {

                indoor = true

                cachedAt = now

                indoorConfirmCount = 0

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

        } else if (outdoorEvidence) {

            indoorConfirmCount = 0

            // 连续明显室外才退出
            if (
                ++outdoorConfirmCount >=
                CONFIRM_SAMPLES
            ) {

                if (indoor) {
                    Log.d(
                        TAG,
                        "Outdoor: " +
                            "fix=$fix " +
                            "vis=$vis " +
                            "total=$total " +
                            "ratio=${
                                "%.2f".format(ratio)
                            }"
                    )
                }

                indoor = false
                outdoor = true

                cachedAt = 0L

                outdoorConfirmCount = 0
            }

        } else {

            // 中间带：没有明确室内或室外证据时保持未知
            if (!indoor) outdoor = false
            indoorConfirmCount = 0
            outdoorConfirmCount = 0
        }
    }


    // ============================================================
    // Median
    // ============================================================

    @Synchronized
    private fun pushWin(win: ArrayDeque<Int>, v: Int) {
        win.addLast(v)
        while (win.size > SAT_WIN) {
            win.removeFirst()
        }
    }

    @Synchronized
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

    private fun indoorStrongLimit(valid: Int, visIn: Int): Int {
        return maxOf(visIn, kotlin.math.ceil(valid * visIn / STRONG_COUNT_REFERENCE_SATS.toFloat()).toInt())
    }

    private fun outdoorStrongLimit(valid: Int, visIn: Int): Int {
        return indoorStrongLimit(valid, visIn) + EXIT_VIS_MARGIN
    }

    @Synchronized
    private fun trimSampleTimes(now: Long) {
        val cut = now - GNSS_SAMPLE_WINDOW_MS
        while (sampleTimes.isNotEmpty() && sampleTimes.first() < cut) {
            sampleTimes.removeFirst()
        }
    }

    @Synchronized
    private fun hasStableGnssSamples(now: Long): Boolean {
        trimSampleTimes(now)
        return lastGnssAt > 0L &&
            now - lastGnssAt < GNSS_VALID_MS &&
            visWin.size == SAT_WIN &&
            validWin.size == SAT_WIN &&
            fixWin.size == SAT_WIN &&
            sampleTimes.size == SAT_WIN &&
            sampleTimes.last() - sampleTimes.first() >= MIN_GNSS_SAMPLE_SPAN_MS
    }


    // ============================================================
    // Reset
    // ============================================================

    @Synchronized
    private fun resetGnssState() {

        visWin.clear()
        validWin.clear()
        reportedWin.clear()
        fixWin.clear()

        lastGnssAt = 0L

        satInfo = ""
        sampleTimes.clear()

        gpsStreak = 0

        indoorConfirmCount = 0
        outdoorConfirmCount = 0
    }
}
