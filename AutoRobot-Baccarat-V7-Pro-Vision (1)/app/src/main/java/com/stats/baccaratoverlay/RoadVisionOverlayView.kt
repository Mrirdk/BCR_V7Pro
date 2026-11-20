package com.stats.baccaratoverlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

data class DetectedBeadMarker(
    val normX: Float,
    val normY: Float,
    val type: Char
)

class RoadVisionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private var detectedRows = 5
    private var detectedCols = 16
    private var cropBottomRatio = 1.0f
    private var markers: List<DetectedBeadMarker> = emptyList()
    private var isScanning = false
    private var isLocked = false

    private var laserProgress = 0f
    private var cornerGlowAlpha = 220

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        color = Color.argb(65, 0, 240, 255)
    }

    private val cutLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        color = Color.argb(185, 255, 209, 102)
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5.5f
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#FFD166")
    }

    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val markerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f
        color = Color.WHITE
    }

    private var laserAnimator: ValueAnimator? = null
    private var cornerAnimator: ValueAnimator? = null

    init {
        startCornerPulseAnimation()
    }

    fun setScanningState(scanning: Boolean) {
        if (isScanning == scanning) return
        isScanning = scanning
        if (scanning) {
            startLaserAnimation()
        } else {
            laserAnimator?.cancel()
            laserProgress = 0f
            invalidate()
        }
    }

    fun setLockedState(locked: Boolean) {
        isLocked = locked
        cornerPaint.color = if (locked) Color.parseColor("#00F0FF") else Color.parseColor("#FFD166")
        invalidate()
    }

    fun updateVisionTelemetry(
        rows: Int,
        cols: Int,
        activeHeightRatio: Float,
        beadMarkers: List<DetectedBeadMarker>
    ) {
        detectedRows = rows.coerceIn(4, 8)
        detectedCols = cols.coerceIn(4, 28)
        cropBottomRatio = activeHeightRatio.coerceIn(0.45f, 1.0f)
        markers = beadMarkers
        invalidate()
    }

    private fun startLaserAnimation() {
        laserAnimator?.cancel()
        laserAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1250L
            repeatMode = ValueAnimator.RESTART
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                laserProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun startCornerPulseAnimation() {
        cornerAnimator?.cancel()
        cornerAnimator = ValueAnimator.ofInt(130, 255).apply {
            duration = 950L
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                cornerGlowAlpha = it.animatedValue as Int
                cornerPaint.alpha = cornerGlowAlpha
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val activeH = h * cropBottomRatio

        val cellW = w / detectedCols
        val cellH = activeH / detectedRows
        for (c in 1 until detectedCols) {
            val x = c * cellW
            canvas.drawLine(x, 0f, x, activeH, gridPaint)
        }
        for (r in 1 until detectedRows) {
            val y = r * cellH
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        if (cropBottomRatio < 0.96f) {
            canvas.drawLine(0f, activeH, w, activeH, cutLinePaint)
        }

        val dotRadius = (minOf(cellW, cellH) * 0.22f).coerceIn(3.5f, 9f)
        for (m in markers) {
            val cx = m.normX * w
            val cy = m.normY * activeH
            markerFillPaint.color = when (m.type) {
                'B' -> Color.parseColor("#EF4444")
                'P' -> Color.parseColor("#3B82F6")
                else -> Color.parseColor("#10B981")
            }
            canvas.drawCircle(cx, cy, dotRadius, markerFillPaint)
            canvas.drawCircle(cx, cy, dotRadius, markerRingPaint)
        }

        if (isScanning) {
            val laserX = laserProgress * w
            val beamWidth = (w * 0.14f).coerceIn(24f, 68f)
            val left = (laserX - beamWidth).coerceAtLeast(0f)
            val right = laserX.coerceAtMost(w)
            val glowColor = if (isLocked) Color.parseColor("#00F0FF") else Color.parseColor("#FFD166")
            laserPaint.shader = LinearGradient(
                left, 0f, right, 0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.argb(120, Color.red(glowColor), Color.green(glowColor), Color.blue(glowColor))
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(RectF(left, 0f, right, activeH), laserPaint)
        }

        val arm = (minOf(w, h) * 0.18f).coerceIn(14f, 32f)
        val pad = 3f
        canvas.drawLine(pad, pad, pad + arm, pad, cornerPaint)
        canvas.drawLine(pad, pad, pad, pad + arm, cornerPaint)
        canvas.drawLine(w - pad - arm, pad, w - pad, pad, cornerPaint)
        canvas.drawLine(w - pad, pad, w - pad, pad + arm, cornerPaint)
        canvas.drawLine(pad, h - pad - arm, pad, h - pad, cornerPaint)
        canvas.drawLine(pad, h - pad, pad + arm, h - pad, cornerPaint)
        canvas.drawLine(w - pad - arm, h - pad, w - pad, h - pad, cornerPaint)
        canvas.drawLine(w - pad, h - pad - arm, w - pad, h - pad, cornerPaint)
    }

    override fun onDetachedFromWindow() {
        laserAnimator?.cancel()
        cornerAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
