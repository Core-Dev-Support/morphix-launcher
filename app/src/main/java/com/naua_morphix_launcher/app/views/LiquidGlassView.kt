package com.naua_morphix_launcher.app.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import com.naua_morphix_launcher.app.R

/**
 * LiquidGlassView - графический эффект «жидкого стекла».
 *
 * Оптимизации ради стабильной работы на слабых устройствах:
 *  - все Paint/Shader/RectF вынесены в поля и пересобираются только в onSizeChanged,
 *    а не на каждом кадре (ранься на каждый draw создавалось ~16 объектов, из них
 *    4 нативных LinearGradient — это постоянное давление на GC и GPU-шейдеры);
 *  - клип по clipPath убран: он совпадал с прямоугольником отрисовки, то есть был
 *    избыточным и на некоторых GPU принудительно поднимал saveLayer;
 *  - число слоёв качества (QUALITY_REDUCED) для слабых GPU: только базовая заливка
 *    и обводка, без градиентных бликов.
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

    private val density = context.resources.displayMetrics.density

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bottomGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fallbackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bevelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    private val rectF = RectF()
    private val borderRect = RectF()
    private val bevelRect = RectF()

    private var fallbackColor = 0xD91A1A22.toInt()

    /**
     * Число градиентных слоёв: 2 = только база и обводка (слабые GPU),
     * 3 = плюс верхний блик, 4 = полный набор с диагональным блеском и отражением.
     */
    private var quality: Int = FULL_QUALITY

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

        overlayPaint.color = overlayColor
        fallbackPaint.color = fallbackColor
        borderPaint.color = borderColor
        borderPaint.strokeWidth = 2f * density
        bevelPaint.strokeWidth = 3f * density
    }

    // Dummy methods to maintain compatibility with existing code
    fun setupWithRoot(@Suppress("UNUSED_PARAMETER") root: View) {}
    fun setupWithActivityRoot() {}
    var viewToExcludeFromCapture: View? = null
    fun updateBackgroundSnapshot() {}

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rectF.set(0f, 0f, w.toFloat(), h.toFloat())

        val borderInset = borderPaint.strokeWidth / 2f
        borderRect.set(borderInset, borderInset, w - borderInset, h - borderInset)

        val bevelInset = bevelPaint.strokeWidth / 2f
        bevelRect.set(bevelInset, bevelInset, w - bevelInset, h - bevelInset)

        rebuildShaders(w, h)
    }

    /** Шейдеры зависят только от размера — пересобираются в onSizeChanged, не в onDraw. */
    private fun rebuildShaders(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val wf = w.toFloat()
        val hf = h.toFloat()

        if (quality <= REDUCED_QUALITY) {
            highlightPaint.shader = null
            sheenPaint.shader = null
            bottomGlowPaint.shader = null
            bevelPaint.shader = null
            return
        }

        // Верхний блик (свет сверху)
        highlightPaint.shader = LinearGradient(
            0f, 0f, 0f, hf * 0.5f,
            0x50FFFFFF, 0x00FFFFFF,
            Shader.TileMode.CLAMP
        )
        // Диагональный блеск
        sheenPaint.shader = LinearGradient(
            0f, 0f, wf, hf,
            intArrayOf(0x00FFFFFF, 0x1AFFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        // Отражение снизу
        bottomGlowPaint.shader = LinearGradient(
            0f, hf * 0.6f, 0f, hf,
            0x00FFFFFF, 0x20FFFFFF,
            Shader.TileMode.CLAMP
        )
        // Внутренний бевель
        bevelPaint.shader = LinearGradient(
            0f, 0f, wf, hf,
            intArrayOf(0x60FFFFFF, 0x00FFFFFF, 0x00000000, 0x30000000),
            floatArrayOf(0.0f, 0.3f, 0.7f, 1.0f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val r = glassCornerRadius
        if (isGlassEnabled) {
            // 1. Базовая полупрозрачная заливка
            canvas.drawRoundRect(rectF, r, r, overlayPaint)

            if (quality >= MEDIUM_QUALITY) {
                // 2. Верхний блик
                canvas.drawRoundRect(rectF, r, r, highlightPaint)
            }

            if (quality >= FULL_QUALITY) {
                // 3. Диагональный блеск
                canvas.drawRoundRect(rectF, r, r, sheenPaint)
                // 4. Отражение снизу
                canvas.drawRoundRect(rectF, r, r, bottomGlowPaint)
            }

            // 5. Чёткая обводка
            canvas.drawRoundRect(borderRect, r, r, borderPaint)

            if (quality >= MEDIUM_QUALITY) {
                // 6. Внутренний бевель
                canvas.drawRoundRect(bevelRect, r, r, bevelPaint)
            }
        } else {
            canvas.drawRoundRect(rectF, r, r, fallbackPaint)
        }

        super.onDraw(canvas)
    }

    // ===== Public API =====

    fun setGlassEnabled(enabled: Boolean) {
        if (this.isGlassEnabled == enabled) return
        this.isGlassEnabled = enabled
        invalidate()
    }

    fun setRadius(@Suppress("UNUSED_PARAMETER") radius: Float) {
        // Blur отключён ради производительности
    }

    fun setZoom(@Suppress("UNUSED_PARAMETER") zoom: Float) {
        // Zoom отключён ради производительности
    }

    fun setGlassColor(color: Int) {
        this.overlayColor = color
        overlayPaint.color = color
        invalidate()
    }

    fun setBorderColor(color: Int) {
        this.borderColor = color
        borderPaint.color = color
        invalidate()
    }

    fun setGlassCornerRadius(radius: Float) {
        if (this.glassCornerRadius == radius) return
        this.glassCornerRadius = radius
        invalidate()
    }

    /**
     * Уровень качества отрисовки. Позволяет на слабых устройствах рисовать
     * 2 слоя вместо 6, убирая 3 нативных шейдера на кадр на каждую вьюху.
     */
    fun setQuality(level: Int) {
        val clamped = level.coerceIn(REDUCED_QUALITY, FULL_QUALITY)
        if (this.quality == clamped) return
        this.quality = clamped
        rebuildShaders(width, height)
        invalidate()
    }

    companion object {
        /** Только базовая заливка + обводка. Для слабых GPU. */
        const val REDUCED_QUALITY = 2

        /** Плюс верхний блик и бевель. */
        const val MEDIUM_QUALITY = 3

        /** Полный набор из 6 слоёв. */
        const val FULL_QUALITY = 4
    }
}