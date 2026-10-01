package com.naua_morphix_launcher.app.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.naua_morphix_launcher.app.R

/**
 * LiquidGlassView - Optimized purely graphical glass effect.
 * Zero screenshots, zero lag. Runs at full FPS even on Android 8.
 */
class LiquidGlassView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    private var overlayColor = 0x50FFFFFF // Translucent milky white
    private var borderColor = 0x40FFFFFF // Translucent border
    private var glassCornerRadius = 0f
    
    var isGlassEnabled = true
        private set

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * context.resources.displayMetrics.density
    }
    private val clipPath = Path()
    private val rectF = RectF()

    private var fallbackColor = 0xD91A1A22.toInt()

    init {
        setWillNotDraw(false)
        clipToPadding = false

        context.obtainStyledAttributes(attrs, R.styleable.LiquidGlassView).apply {
            overlayColor = getColor(R.styleable.LiquidGlassView_lg_overlayColor, overlayColor)
            glassCornerRadius = getDimension(R.styleable.LiquidGlassView_lg_cornerRadius, glassCornerRadius)
            if (hasValue(R.styleable.LiquidGlassView_lg_fallbackColor)) {
                fallbackColor = getColor(R.styleable.LiquidGlassView_lg_fallbackColor, fallbackColor)
            }
            recycle()
        }

        borderPaint.color = borderColor
    }

    // Dummy methods to maintain compatibility with existing code
    fun setupWithRoot(@Suppress("UNUSED_PARAMETER") root: View) {}
    fun setupWithActivityRoot() {}
    var viewToExcludeFromCapture: View? = null
    fun updateBackgroundSnapshot() {}

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rectF.set(0f, 0f, w.toFloat(), h.toFloat())
        clipPath.reset()
        clipPath.addRoundRect(rectF, glassCornerRadius, glassCornerRadius, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        if (isGlassEnabled) {
            val w = width.toFloat()
            val h = height.toFloat()

            canvas.save()
            canvas.clipPath(clipPath)

            // 1. Base translucent overlay
            val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = overlayColor }
            canvas.drawRoundRect(rectF, glassCornerRadius, glassCornerRadius, overlayPaint)

            // 2. Top Specular Highlight (light from top)
            val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            highlightPaint.shader = LinearGradient(
                0f, 0f, 0f, h * 0.5f,
                0x50FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rectF, glassCornerRadius, glassCornerRadius, highlightPaint)

            // 3. Diagonal sheen
            val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            sheenPaint.shader = LinearGradient(
                0f, 0f, w, h,
                intArrayOf(0x00FFFFFF, 0x1AFFFFFF, 0x00FFFFFF),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rectF, glassCornerRadius, glassCornerRadius, sheenPaint)

            // 4. Bottom Reflection
            val bottomGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
            bottomGlowPaint.shader = LinearGradient(
                0f, h * 0.6f, 0f, h,
                0x00FFFFFF, 0x20FFFFFF,
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(rectF, glassCornerRadius, glassCornerRadius, bottomGlowPaint)
            
            canvas.restore()

            // 5. Crisp Rim Border
            val inset = borderPaint.strokeWidth / 2f
            val borderRect = RectF(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(borderRect, glassCornerRadius, glassCornerRadius, borderPaint)

            // 6. Inner Elevation Bevel
            val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f * context.resources.displayMetrics.density
                shader = LinearGradient(
                    0f, 0f, w, h,
                    intArrayOf(0x60FFFFFF, 0x00FFFFFF, 0x00000000, 0x30000000),
                    floatArrayOf(0.0f, 0.3f, 0.7f, 1.0f),
                    Shader.TileMode.CLAMP
                )
            }
            val bevelInset = bevelPaint.strokeWidth / 2f
            val bevelRect = RectF(bevelInset, bevelInset, w - bevelInset, h - bevelInset)
            canvas.drawRoundRect(bevelRect, glassCornerRadius, glassCornerRadius, bevelPaint)
        } else {
            val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fallbackColor }
            canvas.drawRoundRect(rectF, glassCornerRadius, glassCornerRadius, fallbackPaint)
        }

        super.onDraw(canvas)
    }

    // ===== Public API =====
    fun setGlassEnabled(enabled: Boolean) {
        this.isGlassEnabled = enabled
        invalidate()
    }

    fun setRadius(@Suppress("UNUSED_PARAMETER") radius: Float) {
        // Blur is disabled for performance, this does nothing now
    }

    fun setZoom(@Suppress("UNUSED_PARAMETER") zoom: Float) {
        // Zoom is disabled for performance
    }

    fun setGlassColor(color: Int) {
        this.overlayColor = color
        invalidate()
    }

    fun setGlassCornerRadius(radius: Float) {
        this.glassCornerRadius = radius
        invalidate()
    }
}
