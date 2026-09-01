package com.volumeboost.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.volumeboost.app.R
import kotlin.math.min

/**
 * Circular boost gauge. Colors come from the active app theme attributes.
 */
class BoostGaugeView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arcBounds = RectF()
    private var animatedProgress = 0f
    private var animator: ValueAnimator? = null

    var progress: Float = 0f
        set(value) {
            val target = value.coerceIn(0f, 1f)
            field = target
            animator?.cancel()
            animator = ValueAnimator.ofFloat(animatedProgress, target).apply {
                duration = 280L
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    animatedProgress = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

    init {
        reloadThemeColors()
    }

    fun reloadThemeColors() {
        trackPaint.color = resolveThemeColor(R.attr.boostTrackColor)
        progressPaint.color = resolveThemeColor(R.attr.boostActiveColor)
        glowPaint.color = resolveThemeColor(R.attr.boostGlowColor)
        invalidate()
    }

    private fun resolveThemeColor(attr: Int): Int {
        val typedValue = TypedValue()
        context.theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val stroke = min(w, h) * 0.08f
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        glowPaint.strokeWidth = stroke * 1.8f
        val pad = stroke * 1.4f
        arcBounds.set(pad, pad, w - pad, h - pad)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sweep = animatedProgress * SWEEP_ANGLE
        canvas.drawArc(arcBounds, START_ANGLE, SWEEP_ANGLE, false, trackPaint)
        if (sweep > 0f) {
            canvas.drawArc(arcBounds, START_ANGLE, sweep, false, glowPaint)
            canvas.drawArc(arcBounds, START_ANGLE, sweep, false, progressPaint)
        }
    }

    companion object {
        private const val START_ANGLE = 135f
        private const val SWEEP_ANGLE = 270f
    }
}
