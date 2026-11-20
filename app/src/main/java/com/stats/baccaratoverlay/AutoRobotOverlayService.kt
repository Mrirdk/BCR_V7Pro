package com.stats.baccaratoverlay

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.ImageReader
import android.media.ToneGenerator
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.roundToInt

private data class RoadmapScanSnapshot(
    val beads: List<Char>,
    val markers: List<DetectedBeadMarker>,
    val rows: Int,
    val cols: Int,
    val activeHeightRatio: Float,
    val modeLabel: String
)

class AutoRobotOverlayService : Service() {

    companion object {
        const val ACTION_START = "ACTION_START_V7_OVERLAY"
        const val ACTION_STOP = "ACTION_STOP_V7_OVERLAY"
        const val EXTRA_RESULT_CODE = "EXTRA_RESULT_CODE"
        const val EXTRA_DATA = "EXTRA_DATA"
        private const val CHANNEL_ID = "autorobot_v7_hybrid_channel"
        private const val NOTIF_ID = 7001

        private const val SCAN_INTERVAL_MS = 320L
        private const val STABILITY_REQUIRED = 2
    }

    private lateinit var windowManager: WindowManager
    private lateinit var coinManager: AccountCoinManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var toneGenerator: ToneGenerator? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var screenWidth = 720
    private var screenHeight = 1600
    private var screenDensity = 320

    private var robotView: View? = null
    private var targetView: View? = null
    private lateinit var robotParams: WindowManager.LayoutParams
    private lateinit var targetParams: WindowManager.LayoutParams

    private lateinit var roadVisionOverlay: RoadVisionOverlayView
    private lateinit var tvVisionModeTag: TextView

    private lateinit var ivOverlayRobot: ImageView
    private lateinit var viewOverlayRobotAura: View
    private lateinit var viewOverlayRippleRing: View
    private lateinit var layoutExpandableBody: LinearLayout
    private lateinit var layoutRobotBubble: LinearLayout
    private lateinit var tvOverlayCoinBadge: TextView
    private lateinit var tvTimingBadge: TextView
    private lateinit var tvResultAnnouncement: TextView
    private lateinit var viewBannerShimmer: View
    private lateinit var tvRobotStatus: TextView
    private lateinit var tvMainRoadStatus: TextView
    private lateinit var tvMarkovProb: TextView
    private lateinit var viewProbBarBanker: View
    private lateinit var viewProbBarPlayer: View
    private lateinit var tvRecentBeads: TextView
    private lateinit var btnToggleMinimize: Button
    private lateinit var btnToggleScan: Button
    private lateinit var btnToggleTarget: Button
    private lateinit var btnResetRoad: Button

    private var isAutoScanning = false
    private var isTargetLocked = false
    private var isMinimized = false
    private var isOcrBusy = false

    private var stableGridSignature = ""
    private var stableGridHits = 0

    private val capturedHistory = mutableListOf<Char>()
    private var lastGridBeadCount = 0
    private var lastAlertedHandIndex = -1
    private var isBettingWindowOpen = true
    private var lastBannerResultToken = ""
    private var lastScoreboardTotal = -1
    private var displayedProbB = 50

    private var overlayRobotAnim: AnimatorSet? = null

    private val scanLoopRunnable = object : Runnable {
        override fun run() {
            if (isAutoScanning) {
                performLockedRoiAndTimingScan()
                mainHandler.postDelayed(this, SCAN_INTERVAL_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        coinManager = AccountCoinManager(this)
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        } catch (_: Exception) {
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        startAndroid14ProjectionForeground()
        setupOverlayWindows()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_DATA)
                }
                if (resultCode == Activity.RESULT_OK && data != null) {
                    initMediaProjection(resultCode, data)
                }
            }
        }
        return START_STICKY
    }

    private fun startAndroid14ProjectionForeground() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "AutoRobot Baccarat V7 Vision+ Scanner",
            NotificationManager.IMPORTANCE_LOW
        )
        manager.createNotificationChannel(channel)

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AutoRobot Baccarat V7 Vision+")
            .setContentText("Hệ thống thị giác bảng cầu & AI V7 đang hoạt động...")
            .setSmallIcon(R.drawable.ic_robot_cutout)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        releaseProjection()
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(resultCode, data)
        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                isAutoScanning = false
                mainHandler.removeCallbacks(scanLoopRunnable)
            }
        }, mainHandler)

        imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "AutoRobotV7VisionCapture",
            screenWidth,
            screenHeight,
            screenDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface,
            null,
            mainHandler
        )
    }

    private fun setupOverlayWindows() {
        val inflater = LayoutInflater.from(this)

        val defaultBoxW = (screenWidth * 0.625f).toInt().coerceAtLeast(dpToPx(160))
        val defaultBoxH = (screenHeight * 0.088f).toInt().coerceAtLeast(dpToPx(64))
        targetView = inflater.inflate(R.layout.scan_target_layout, null)
        targetParams = WindowManager.LayoutParams(
            defaultBoxW,
            defaultBoxH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth * 0.291f).toInt()
            y = (screenHeight * 0.695f).toInt()
        }
        windowManager.addView(targetView, targetParams)
        setupTargetDragAndResize()

        robotView = inflater.inflate(R.layout.robot_overlay_layout, null)
        robotParams = WindowManager.LayoutParams(
            dpToPx(290),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(10)
            y = dpToPx(34)
        }
        windowManager.addView(robotView, robotParams)

        bindOverlayViews()
        startOverlayRobotAnimations()
    }

    private fun bindOverlayViews() {
        val tRoot = targetView ?: return
        roadVisionOverlay = tRoot.findViewById(R.id.roadVisionOverlay)
        tvVisionModeTag = tRoot.findViewById(R.id.tvVisionModeTag)

        val root = robotView ?: return
        ivOverlayRobot = root.findViewById(R.id.ivOverlayRobot)
        viewOverlayRobotAura = root.findViewById(R.id.viewOverlayRobotAura)
        viewOverlayRippleRing = root.findViewById(R.id.viewOverlayRippleRing)
        layoutExpandableBody = root.findViewById(R.id.layoutExpandableBody)
        layoutRobotBubble = root.findViewById(R.id.layoutRobotBubble)
        tvOverlayCoinBadge = root.findViewById(R.id.tvOverlayCoinBadge)
        tvTimingBadge = root.findViewById(R.id.tvTimingBadge)
        tvResultAnnouncement = root.findViewById(R.id.tvResultAnnouncement)
        viewBannerShimmer = root.findViewById(R.id.viewBannerShimmer)
        tvRobotStatus = root.findViewById(R.id.tvRobotStatus)
        tvMainRoadStatus = root.findViewById(R.id.tvMainRoadStatus)
        tvMarkovProb = root.findViewById(R.id.tvMarkovProb)
        viewProbBarBanker = root.findViewById(R.id.viewProbBarBanker)
        viewProbBarPlayer = root.findViewById(R.id.viewProbBarPlayer)
        tvRecentBeads = root.findViewById(R.id.tvRecentBeads)
        btnToggleMinimize = root.findViewById(R.id.btnToggleMinimize)
        btnToggleScan = root.findViewById(R.id.btnToggleScan)
        btnToggleTarget = root.findViewById(R.id.btnToggleTarget)
        btnResetRoad = root.findViewById(R.id.btnResetRoad)

        val user = coinManager.getCurrentUser()
        val coins = coinManager.getCoins(user)
        tvOverlayCoinBadge.text = if (coinManager.isAdmin(user)) "ADMIN ∞" else "$coins Xu"

        listOf(btnToggleScan, btnToggleTarget, btnResetRoad, btnToggleMinimize).forEach { btn ->
            btn.setOnTouchListener { v, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.93f).scaleY(0.93f).setDuration(80L).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
                }
                false
            }
        }

        val header = root.findViewById<View>(R.id.robotHeader)
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        header.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = robotParams.x
                    initialY = robotParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    robotParams.x = (initialX + (event.rawX - initialTouchX).toInt()).coerceIn(0, screenWidth - dpToPx(100))
                    robotParams.y = (initialY + (event.rawY - initialTouchY).toInt()).coerceIn(0, screenHeight - dpToPx(80))
                    windowManager.updateViewLayout(robotView, robotParams)
                    true
                }
                else -> false
            }
        }

        btnToggleMinimize.setOnClickListener {
            isMinimized = !isMinimized
            if (isMinimized) {
                layoutExpandableBody.animate()
                    .alpha(0f)
                    .scaleY(0.85f)
                    .setDuration(150L)
                    .withEndAction { layoutExpandableBody.visibility = View.GONE }
                    .start()
                btnToggleMinimize.text = "+"
            } else {
                layoutExpandableBody.visibility = View.VISIBLE
                layoutExpandableBody.alpha = 0f
                layoutExpandableBody.scaleY = 0.85f
                layoutExpandableBody.animate()
                    .alpha(1f)
                    .scaleY(1f)
                    .setDuration(200L)
                    .setInterpolator(OvershootInterpolator())
                    .start()
                btnToggleMinimize.text = "—"
            }
        }

        root.findViewById<Button>(R.id.btnCloseOverlay).setOnClickListener {
            stopSelf()
        }

        btnToggleScan.setOnClickListener {
            isAutoScanning = !isAutoScanning
            roadVisionOverlay.setScanningState(isAutoScanning)
            if (isAutoScanning) {
                btnToggleScan.text = "TẠM DỪNG"
                btnToggleScan.setBackgroundResource(R.drawable.bg_btn_gold)
                btnToggleScan.setTextColor(Color.parseColor("#090D16"))
                tvTimingBadge.text = "🟢 ĐANG SOI BẢNG CẦU..."
                viewOverlayRobotAura.setBackgroundResource(R.drawable.bg_robot_aura_gold)
                triggerRippleShockwave()
                mainHandler.removeCallbacks(scanLoopRunnable)
                mainHandler.post(scanLoopRunnable)
            } else {
                btnToggleScan.text = "BẮT ĐẦU"
                btnToggleScan.setBackgroundResource(R.drawable.bg_btn_emerald)
                btnToggleScan.setTextColor(Color.WHITE)
                tvTimingBadge.text = "⏸ ĐÃ TẠM DỪNG"
                viewOverlayRobotAura.setBackgroundResource(R.drawable.bg_robot_aura)
                mainHandler.removeCallbacks(scanLoopRunnable)
            }
        }

        btnToggleTarget.setOnClickListener {
            isTargetLocked = !isTargetLocked
            val resizeBtn = tRoot.findViewById<View>(R.id.btnResizeHandle)
            roadVisionOverlay.setLockedState(isTargetLocked)

            if (isTargetLocked) {
                btnToggleTarget.text = "MỞ KHUNG"
                tRoot.setBackgroundResource(R.drawable.bg_scan_target_locked)
                resizeBtn.visibility = View.GONE
                targetParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            } else {
                btnToggleTarget.text = "KHÓA KHUNG"
                tRoot.setBackgroundResource(R.drawable.bg_scan_target)
                resizeBtn.visibility = View.VISIBLE
                targetParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            }
            windowManager.updateViewLayout(tRoot, targetParams)
        }

        btnResetRoad.setOnClickListener {
            capturedHistory.clear()
            lastGridBeadCount = 0
            lastAlertedHandIndex = -1
            stableGridSignature = ""
            stableGridHits = 0
            lastBannerResultToken = ""
            lastScoreboardTotal = -1
            roadVisionOverlay.updateVisionTelemetry(5, 16, 1.0f, emptyList())
            tvResultAnnouncement.setBackgroundResource(R.drawable.bg_result_banner)
            tvResultAnnouncement.text = "ĐÃ LÀM MỚI DỮ LIỆU BÀN MỚI"
            tvRobotStatus.text = "Sẵn sàng bắt cầu bàn mới!"
            tvMainRoadStatus.text = "Thế cầu: Đang chờ dữ liệu"
            tvMarkovProb.text = "Tỷ lệ: BANKER 50% | PLAYER 50%"
            animateProbabilityBar(50)
            tvRecentBeads.text = "Chuỗi bắt được: --"
            triggerBannerShimmer()
            playChimeAndVibrate(isAlert = false)
        }
    }

    private fun setupTargetDragAndResize() {
        val tView = targetView ?: return
        val resizeHandle = tView.findViewById<View>(R.id.btnResizeHandle)

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f

        tView.setOnTouchListener { _, event ->
            if (isTargetLocked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = targetParams.x
                    startY = targetParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    targetParams.x = (startX + (event.rawX - touchX).toInt())
                        .coerceIn(0, (screenWidth - targetParams.width).coerceAtLeast(0))
                    targetParams.y = (startY + (event.rawY - touchY).toInt())
                        .coerceIn(0, (screenHeight - targetParams.height).coerceAtLeast(0))
                    windowManager.updateViewLayout(tView, targetParams)
                    true
                }
                else -> false
            }
        }

        var startW = 0
        var startH = 0
        resizeHandle.setOnTouchListener { _, event ->
            if (isTargetLocked) return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startW = targetParams.width
                    startH = targetParams.height
                    touchX = event.rawX
                    touchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    targetParams.width = (startW + (event.rawX - touchX).toInt())
                        .coerceIn(dpToPx(90), screenWidth - targetParams.x)
                    targetParams.height = (startH + (event.rawY - touchY).toInt())
                        .coerceIn(dpToPx(55), screenHeight - targetParams.y)
                    windowManager.updateViewLayout(tView, targetParams)
                    true
                }
                else -> false
            }
        }
    }

    private fun startOverlayRobotAnimations() {
        val floatY = ObjectAnimator.ofFloat(ivOverlayRobot, View.TRANSLATION_Y, -4.5f, 4.5f).apply {
            duration = 1350L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val tiltRot = ObjectAnimator.ofFloat(ivOverlayRobot, View.ROTATION, -4f, 4f).apply {
            duration = 1900L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
        }
        val auraScaleX = ObjectAnimator.ofFloat(viewOverlayRobotAura, View.SCALE_X, 0.86f, 1.18f).apply {
            duration = 1050L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        val auraScaleY = ObjectAnimator.ofFloat(viewOverlayRobotAura, View.SCALE_Y, 0.86f, 1.18f).apply {
            duration = 1050L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
        }
        overlayRobotAnim = AnimatorSet().apply {
            playTogether(floatY, tiltRot, auraScaleX, auraScaleY)
            start()
        }
    }

    private fun triggerRobotAlertBounce() {
        ivOverlayRobot.animate()
            .scaleX(1.28f)
            .scaleY(1.28f)
            .rotationBy(20f)
            .setDuration(220L)
            .setInterpolator(OvershootInterpolator())
            .withEndAction {
                ivOverlayRobot.animate().scaleX(1.0f).scaleY(1.0f).rotation(0f).setDuration(200L).start()
            }
            .start()

        triggerRippleShockwave()
        triggerBannerShimmer()

        if (!isMinimized) {
            layoutRobotBubble.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pop_in))
            tvResultAnnouncement.startAnimation(AnimationUtils.loadAnimation(this, R.anim.pop_in))
        }
    }

    private fun triggerRippleShockwave() {
        viewOverlayRippleRing.alpha = 0.9f
        viewOverlayRippleRing.scaleX = 0.8f
        viewOverlayRippleRing.scaleY = 0.8f
        viewOverlayRippleRing.animate()
            .scaleX(1.75f)
            .scaleY(1.75f)
            .alpha(0f)
            .setDuration(520L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun triggerBannerShimmer() {
        val bannerW = tvResultAnnouncement.width.takeIf { it > 0 } ?: dpToPx(260)
        viewBannerShimmer.translationX = -dpToPx(40).toFloat()
        viewBannerShimmer.alpha = 0.75f
        viewBannerShimmer.animate()
            .translationX(bannerW.toFloat())
            .alpha(0f)
            .setDuration(550L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun animateProbabilityBar(targetProbB: Int) {
        val clampedB = targetProbB.coerceIn(5, 95)
        val animator = ValueAnimator.ofInt(displayedProbB, clampedB).apply {
            duration = 320L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                val curB = anim.animatedValue as Int
                displayedProbB = curB
                val pB = viewProbBarBanker.layoutParams as LinearLayout.LayoutParams
                val pP = viewProbBarPlayer.layoutParams as LinearLayout.LayoutParams
                pB.weight = curB.toFloat()
                pP.weight = (100 - curB).toFloat()
                viewProbBarBanker.layoutParams = pB
                viewProbBarPlayer.layoutParams = pP
            }
        }
        animator.start()
    }

    private fun performLockedRoiAndTimingScan() {
        val reader = imageReader ?: return
        val tView = targetView ?: return
        val image = try {
            reader.acquireLatestImage()
        } catch (_: Exception) {
            null
        } ?: return

        try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val fullBitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            fullBitmap.copyPixelsFromBuffer(buffer)

            val inset = dpToPx(3)
            val loc = IntArray(2)
            tView.getLocationOnScreen(loc)
            val roiLeft = (loc[0] + inset).coerceIn(0, fullBitmap.width - 16)
            val roiTop = (loc[1] + inset).coerceIn(0, fullBitmap.height - 16)
            val roiWidth = (tView.width - inset * 2).coerceIn(16, fullBitmap.width - roiLeft)
            val roiHeight = (tView.height - inset * 2).coerceIn(16, fullBitmap.height - roiTop)

            val roiBitmap = Bitmap.createBitmap(fullBitmap, roiLeft, roiTop, roiWidth, roiHeight)
            val snapshot = analyzeRoadmapVision(roiBitmap)
            roiBitmap.recycle()

            roadVisionOverlay.updateVisionTelemetry(
                rows = snapshot.rows,
                cols = snapshot.cols,
                activeHeightRatio = snapshot.activeHeightRatio,
                beadMarkers = snapshot.markers
            )
            tvVisionModeTag.text = "${snapshot.modeLabel} (${snapshot.rows}x${snapshot.cols})"

            val signature = snapshot.beads.joinToString("")
            if (signature == stableGridSignature) {
                stableGridHits++
            } else {
                stableGridSignature = signature
                stableGridHits = 1
            }

            if (stableGridHits == STABILITY_REQUIRED && snapshot.beads.isNotEmpty()) {
                processStableRoadmapSnapshot(snapshot.beads)
            }

            if (!isOcrBusy) {
                isOcrBusy = true
                val ocrTop = (screenHeight * 0.34f).toInt().coerceIn(0, fullBitmap.height - 20)
                val ocrBottom = (screenHeight * 0.68f).toInt().coerceIn(ocrTop + 20, fullBitmap.height)
                val ocrLeft = (screenWidth * 0.02f).toInt().coerceIn(0, fullBitmap.width - 20)
                val ocrWidth = (screenWidth * 0.96f).toInt().coerceIn(20, fullBitmap.width - ocrLeft)

                val ocrBitmap = Bitmap.createBitmap(fullBitmap, ocrLeft, ocrTop, ocrWidth, ocrBottom - ocrTop)
                runTableAndCounterOcr(ocrBitmap)
            }

            fullBitmap.recycle()
        } catch (_: Exception) {
            isOcrBusy = false
        } finally {
            image.close()
        }
    }

    private fun analyzeRoadmapVision(roi: Bitmap): RoadmapScanSnapshot {
        val cutY = detectDerivedRoadSeparatorY(roi)
        val activeH = if (cutY in (roi.height * 0.45f).toInt()..(roi.height * 0.85f).toInt()) cutY else roi.height
        val activeHeightRatio = activeH.toFloat() / roi.height.toFloat()
        val mainRoi = if (activeH < roi.height) Bitmap.createBitmap(roi, 0, 0, roi.width, activeH) else roi

        val w = mainRoi.width
        val h = mainRoi.height
        val aspect = w.toFloat() / h.toFloat()

        val detectedRows = detectOptimalRowCount(mainRoi)
        val isSolidBeadPlate = detectIsSolidBeadPlate(mainRoi, detectedRows)

        val orderedBeads = mutableListOf<Char>()
        val markers = mutableListOf<DetectedBeadMarker>()

        if (isSolidBeadPlate) {
            val cols = (aspect * detectedRows).roundToInt().coerceIn(4, 12)
            val cellW = w.toFloat() / cols
            val cellH = h.toFloat() / detectedRows

            for (c in 0 until cols) {
                for (r in 0 until detectedRows) {
                    val bead = classifyCellBead(mainRoi, c * cellW, r * cellH, cellW, cellH, isBigRoad = false)
                    if (bead != null) {
                        orderedBeads.add(bead)
                        markers.add(
                            DetectedBeadMarker(
                                normX = (c + 0.5f) / cols,
                                normY = (r + 0.5f) / detectedRows,
                                type = bead
                            )
                        )
                    }
                }
            }
            if (mainRoi !== roi) mainRoi.recycle()
            return RoadmapScanSnapshot(orderedBeads, markers, detectedRows, cols, activeHeightRatio, "BẢNG HẠT")
        } else {
            val estCellH = h.toFloat() / detectedRows
            val cols = (w.toFloat() / estCellH).roundToInt().coerceIn(6, 26)
            val cellW = w.toFloat() / cols
            val cellH = estCellH

            val grid = Array(cols) { Array<Char?>(detectedRows) { null } }
            for (c in 0 until cols) {
                for (r in 0 until detectedRows) {
                    grid[c][r] = classifyCellBead(mainRoi, c * cellW, r * cellH, cellW, cellH, isBigRoad = true)
                }
            }

            var c = 0
            while (c < cols) {
                val topBead = grid[c][0]
                if (topBead == null) {
                    c++
                    continue
                }
                if (topBead == 'T') {
                    orderedBeads.add('T')
                    markers.add(DetectedBeadMarker((c + 0.5f) / cols, 0.5f / detectedRows, 'T'))
                    c++
                    continue
                }

                var streak = 0
                for (r in 0 until detectedRows) {
                    val cell = grid[c][r]
                    if (cell == topBead) {
                        streak++
                        markers.add(DetectedBeadMarker((c + 0.5f) / cols, (r + 0.5f) / detectedRows, topBead))
                    } else if (cell == 'T') {
                        markers.add(DetectedBeadMarker((c + 0.5f) / cols, (r + 0.5f) / detectedRows, 'T'))
                        continue
                    } else {
                        break
                    }
                }

                var tailCols = 0
                val bottomRow = detectedRows - 1
                if (streak >= detectedRows) {
                    var nextC = c + 1
                    while (nextC < cols && grid[nextC][0] == null && grid[nextC][bottomRow] == topBead) {
                        streak++
                        tailCols++
                        markers.add(
                            DetectedBeadMarker(
                                (nextC + 0.5f) / cols,
                                (bottomRow + 0.5f) / detectedRows,
                                topBead
                            )
                        )
                        nextC++
                    }
                }

                repeat(streak) { orderedBeads.add(topBead) }
                c += 1 + tailCols
            }

            if (mainRoi !== roi) mainRoi.recycle()
            return RoadmapScanSnapshot(orderedBeads, markers, detectedRows, cols, activeHeightRatio, "CẦU CHÍNH")
        }
    }

    private fun detectDerivedRoadSeparatorY(roi: Bitmap): Int {
        val w = roi.width
        val h = roi.height
        if (h < 60) return h

        val startY = (h * 0.45f).toInt()
        val endY = (h * 0.82f).toInt()
        var bestY = h
        var lowestLuma = 255f

        for (y in startY until endY) {
            var darkCount = 0
            var sampleCount = 0
            var sumLuma = 0f
            var x = 4
            while (x < w - 4) {
                val px = roi.getPixel(x, y)
                val luma = (Color.red(px) + Color.green(px) + Color.blue(px)) / 3f
                sumLuma += luma
                if (luma < 195f) darkCount++
                sampleCount++
                x += 3
            }
            if (sampleCount > 0) {
                val darkRatio = darkCount.toFloat() / sampleCount
                val avgLuma = sumLuma / sampleCount
                if (darkRatio >= 0.48f && avgLuma < lowestLuma) {
                    lowestLuma = avgLuma
                    bestY = y
                }
            }
        }

        if (bestY in startY until endY) {
            val checkY = (bestY * 0.5f).toInt().coerceAtLeast(2)
            var whiteHits = 0
            var total = 0
            var x = 4
            while (x < w - 4) {
                val px = roi.getPixel(x, checkY)
                if (Color.red(px) > 195 && Color.green(px) > 195 && Color.blue(px) > 195) {
                    whiteHits++
                }
                total++
                x += 4
            }
            if (total > 0 && whiteHits.toFloat() / total >= 0.40f) {
                return bestY
            }
        }
        return h
    }

    private fun detectOptimalRowCount(mainRoi: Bitmap): Int {
        val w = mainRoi.width
        val h = mainRoi.height
        if (h < 40) return 5

        val lineYs = mutableListOf<Int>()
        var lastLineY = -20
        for (y in 4 until h - 4) {
            var gridGrayHits = 0
            var samples = 0
            var x = 6
            while (x < w - 6) {
                val px = mainRoi.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                val luma = (r + g + b) / 3
                if (luma in 185..242 && abs(r - b) < 22) {
                    gridGrayHits++
                }
                samples++
                x += 4
            }
            if (samples > 0 && gridGrayHits.toFloat() / samples >= 0.58f) {
                if (y - lastLineY >= (h / 8)) {
                    lineYs.add(y)
                    lastLineY = y
                }
            }
        }

        if (lineYs.size >= 2) {
            val diffs = mutableListOf<Int>()
            for (i in 1 until lineYs.size) {
                diffs.add(lineYs[i] - lineYs[i - 1])
            }
            val avgStep = diffs.average().toFloat()
            if (avgStep > 10f) {
                val estRows = (h / avgStep).roundToInt()
                if (estRows in 5..6) return estRows
            }
        }

        return if (w.toFloat() / h.toFloat() >= 2.95f) 5 else 6
    }

    private fun detectIsSolidBeadPlate(mainRoi: Bitmap, rows: Int): Boolean {
        val w = mainRoi.width
        val h = mainRoi.height
        val aspect = w.toFloat() / h.toFloat()
        if (aspect > 1.35f) return false

        val cols = (aspect * rows).roundToInt().coerceIn(4, 8)
        val cellW = w.toFloat() / cols
        val cellH = h.toFloat() / rows
        var solidCenterHits = 0
        var hollowRingHits = 0

        for (c in 0 until cols) {
            for (r in 0 until rows) {
                val cx = ((c + 0.5f) * cellW).toInt().coerceIn(0, w - 1)
                val cy = ((r + 0.5f) * cellH).toInt().coerceIn(0, h - 1)
                val px = mainRoi.getPixel(cx, cy)
                val red = Color.red(px)
                val green = Color.green(px)
                val blue = Color.blue(px)

                val isCenterColored =
                    (red > 135 && red - green > 35 && red - blue > 35) ||
                    (blue > 135 && blue - red > 35) ||
                    (green > 130 && green - red > 35 && green - blue > 35)

                if (isCenterColored) {
                    solidCenterHits++
                } else if (red > 200 && green > 200 && blue > 200) {
                    if (classifyCellBead(mainRoi, c * cellW, r * cellH, cellW, cellH, isBigRoad = true) != null) {
                        hollowRingHits++
                    }
                }
            }
        }
        return solidCenterHits > hollowRingHits
    }

    private fun classifyCellBead(
        roi: Bitmap,
        left: Float,
        top: Float,
        cellW: Float,
        cellH: Float,
        isBigRoad: Boolean
    ): Char? {
        val marginX = cellW * 0.14f
        val marginY = cellH * 0.14f
        val startX = (left + marginX).toInt().coerceIn(0, roi.width - 1)
        val endX = (left + cellW - marginX).toInt().coerceIn(startX + 1, roi.width)
        val startY = (top + marginY).toInt().coerceIn(0, roi.height - 1)
        val endY = (top + cellH - marginY).toInt().coerceIn(startY + 1, roi.height)

        var redHits = 0
        var blueHits = 0
        var greenHits = 0
        var totalSamples = 0

        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                val px = roi.getPixel(x, y)
                val r = Color.red(px)
                val g = Color.green(px)
                val b = Color.blue(px)
                totalSamples++

                if (r > 130 && (r - g) > 34 && (r - b) > 34) {
                    redHits++
                } else if (b > 130 && (b - r) > 34 && (b - g) > 12) {
                    blueHits++
                } else if (g > 125 && (g - r) > 36 && (g - b) > 36) {
                    greenHits++
                }
                x++
            }
            y++
        }

        if (totalSamples == 0) return null
        val redRatio = redHits.toFloat() / totalSamples
        val blueRatio = blueHits.toFloat() / totalSamples
        val greenRatio = greenHits.toFloat() / totalSamples
        val minRingThreshold = if (isBigRoad) 0.038f else 0.12f

        return when {
            redRatio >= minRingThreshold && redRatio >= blueRatio -> 'B'
            blueRatio >= minRingThreshold && blueRatio > redRatio -> 'P'
            greenRatio >= 0.15f && greenRatio > redRatio && greenRatio > blueRatio -> 'T'
            else -> null
        }
    }

    private fun processStableRoadmapSnapshot(gridBeads: List<Char>) {
        val isNewHandAdded = gridBeads.size > lastGridBeadCount && lastGridBeadCount > 0
        val isInitialLoad = lastGridBeadCount == 0 && gridBeads.isNotEmpty()

        capturedHistory.clear()
        capturedHistory.addAll(gridBeads)
        lastGridBeadCount = gridBeads.size

        val report = BaccaratV7HybridEngine.evaluate(capturedHistory)
        updateHudWithReport(report)

        if (isNewHandAdded) {
            val newestBead = capturedHistory.last()
            announceRoundResult(newestBead, report)
        } else if (isInitialLoad) {
            tvResultAnnouncement.setBackgroundResource(R.drawable.bg_result_banner)
            tvResultAnnouncement.text = "ĐÃ ĐỒNG BỘ ${capturedHistory.size} TAY CẦU TRÊN BẢNG"
            triggerBannerShimmer()
            if (report.recommendedSide != "WAIT" && lastAlertedHandIndex != capturedHistory.size) {
                lastAlertedHandIndex = capturedHistory.size
                playChimeAndVibrate(isAlert = true)
                triggerRobotAlertBounce()
            }
        }
    }

    private fun runTableAndCounterOcr(ocrBitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(ocrBitmap, 0)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val rawText = visionText.text.uppercase()

                val bettingOpenKeywords = listOf(
                    "BẮT ĐẦU ĐẶT CƯỢC", "BAT DAU DAT CUOC", "PLACE YOUR BET", "ĐẶT CƯỢC", "DAT CUOC"
                )
                val bettingClosedKeywords = listOf(
                    "NGỪNG ĐẶT CƯỢC", "NGUNG DAT CUOC", "NGỪNG CƯỢC", "NO MORE BET", "CHIA BÀI", "DEALING"
                )

                if (bettingOpenKeywords.any { rawText.contains(it) }) {
                    if (!isBettingWindowOpen) {
                        isBettingWindowOpen = true
                        tvTimingBadge.text = "🟢 MỞ CƯỢC — VÀO LỆNH NGAY!"
                        tvTimingBadge.setTextColor(Color.parseColor("#10B981"))

                        val report = BaccaratV7HybridEngine.evaluate(capturedHistory)
                        if (report.recommendedSide != "WAIT" && lastAlertedHandIndex != capturedHistory.size) {
                            lastAlertedHandIndex = capturedHistory.size
                            playChimeAndVibrate(isAlert = true)
                            triggerRobotAlertBounce()
                        }
                    }
                } else if (bettingClosedKeywords.any { rawText.contains(it) }) {
                    isBettingWindowOpen = false
                    tvTimingBadge.text = "🔴 ĐÓNG CƯỢC — CHỜ MỞ BÀI..."
                    tvTimingBadge.setTextColor(Color.parseColor("#EF4444"))
                }

                val explicitOutcome: Char? = when {
                    rawText.contains("BANKER WIN") || rawText.contains("NHÀ CÁI THẮNG") ||
                            rawText.contains("NHA CAI THANG") || rawText.contains("CÁI THẮNG") -> 'B'
                    rawText.contains("PLAYER WIN") || rawText.contains("NHÀ CON THẮNG") ||
                            rawText.contains("NHA CON THANG") || rawText.contains("CON THẮNG") -> 'P'
                    rawText.contains("TIE WIN") || rawText.contains("VÁN HÒA") ||
                            rawText.contains("VAN HOA") || rawText.contains("KẾT QUẢ HÒA") -> 'T'
                    else -> null
                }

                if (explicitOutcome != null) {
                    val token = "${capturedHistory.size}_$explicitOutcome"
                    if (token != lastBannerResultToken) {
                        lastBannerResultToken = token
                        val report = BaccaratV7HybridEngine.evaluate(capturedHistory)
                        announceRoundResult(explicitOutcome, report)
                    }
                }

                val totalMatch = Regex("""TOTAL\s*[:\-]?\s*(\d{1,3})""").find(rawText)
                if (totalMatch != null) {
                    val totalVal = totalMatch.groupValues[1].toIntOrNull() ?: -1
                    if (totalVal > 0) {
                        if (lastScoreboardTotal > 0 && totalVal == lastScoreboardTotal + 1 &&
                            capturedHistory.size == lastGridBeadCount && lastGridBeadCount > 0
                        ) {
                            val token = "${totalVal}_T"
                            if (token != lastBannerResultToken) {
                                lastBannerResultToken = token
                                val report = BaccaratV7HybridEngine.evaluate(capturedHistory)
                                announceRoundResult('T', report)
                            }
                        }
                        lastScoreboardTotal = totalVal
                    }
                }
            }
            .addOnCompleteListener {
                ocrBitmap.recycle()
                isOcrBusy = false
            }
    }

    private fun announceRoundResult(outcome: Char, report: V7HybridReport) {
        when (outcome) {
            'B' -> {
                tvResultAnnouncement.setBackgroundResource(R.drawable.bg_result_banker)
                tvResultAnnouncement.text = "🔴 KẾT QUẢ VÁN VỪA RỒI: BANKER (CÁI) THẮNG!"
            }
            'P' -> {
                tvResultAnnouncement.setBackgroundResource(R.drawable.bg_result_player)
                tvResultAnnouncement.text = "🔵 KẾT QUẢ VÁN VỪA RỒI: PLAYER (CON) THẮNG!"
            }
            'T' -> {
                tvResultAnnouncement.setBackgroundResource(R.drawable.bg_result_tie)
                tvResultAnnouncement.text = "🟢 KẾT QUẢ VÁN VỪA RỒI: TIE (HÒA)!"
            }
        }
        tvTimingBadge.text = "🟢 VỪA RA KẾT QUẢ → VÀO LỆNH VÁN MỚI!"
        tvTimingBadge.setTextColor(Color.parseColor("#FFD166"))

        updateHudWithReport(report)
        playChimeAndVibrate(isAlert = true)
        triggerRobotAlertBounce()
    }

    private fun updateHudWithReport(report: V7HybridReport) {
        tvRobotStatus.text = report.robotCallout
        tvMainRoadStatus.text = "${report.patternName} | ${report.derivedRoadStatusText}"
        tvMarkovProb.text = "Tỷ lệ: BANKER ${report.probB}% | PLAYER ${report.probP}%"
        animateProbabilityBar(report.probB)

        val recentStr = if (capturedHistory.isEmpty()) {
            "--"
        } else {
            capturedHistory.takeLast(12).joinToString(" → ")
        }
        tvRecentBeads.text = "12 tay gần nhất (${capturedHistory.size}): $recentStr"
        if (!isMinimized) {
            tvRecentBeads.animate().scaleX(1.04f).scaleY(1.04f).setDuration(120L).withEndAction {
                tvRecentBeads.animate().scaleX(1f).scaleY(1f).setDuration(120L).start()
            }.start()
        }
    }

    private fun playChimeAndVibrate(isAlert: Boolean) {
        try {
            if (isAlert) {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, 260)
            } else {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 140)
            }
        } catch (_: Exception) {
        }

        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(
                VibrationEffect.createOneShot(if (isAlert) 240L else 90L, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (_: Exception) {
        }
    }

    private fun releaseProjection() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()
        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        isAutoScanning = false
        mainHandler.removeCallbacks(scanLoopRunnable)
        overlayRobotAnim?.cancel()
        toneGenerator?.release()
        releaseProjection()
        robotView?.let { windowManager.removeView(it) }
        targetView?.let { windowManager.removeView(it) }
        super.onDestroy()
    }
}
