package com.naua_morphix_launcher.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.naua_morphix_launcher.app.model.IconShape
import kotlin.math.min
import kotlin.math.roundToInt

object IconMaskUtil {

    /** Целевой размер растеризованной иконки в пикселях. */
    private const val TARGET_PX = 192

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val srcInPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }
    private val srcInBitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    }
    private val canvas = Canvas()
    private val path = Path()
    private val rectF = RectF()

    /**
     * Преобразование любого Drawable в Bitmap.
     *
     * Размер нормализуется до TARGET_PX: раньше брались intrinsicWidth/Height,
     * а для adaptive-иконок на xxxhdpi это 432×432×4 = 746 КБ на иконку,
     * что на прокрутке сетки из 100+ иконок давало OOM на 4 ГБ устройстве.
     */
    fun drawableToBitmap(drawable: Drawable, targetPx: Int = TARGET_PX): Bitmap {
        (drawable as? BitmapDrawable)?.bitmap?.let { return it }

        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: targetPx
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: targetPx

        val scale = min(targetPx.toFloat() / w, targetPx.toFloat() / h)
        val bw = (w * scale).roundToInt().coerceIn(1, targetPx)
        val bh = (h * scale).roundToInt().coerceIn(1, targetPx)

        val bitmap = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        canvas.setBitmap(bitmap)
        canvas.drawColor(0)
        canvas.save()
        canvas.translate((targetPx - bw) / 2f, (targetPx - bh) / 2f)
        // setBounds на общем Drawable из кэша AppLoader «съезжал» бы
        // у всех остальных пользователей иконки, поэтому рисуем копию
        val safe = drawable.constantState?.newDrawable()?.mutate() ?: drawable
        safe.setBounds(0, 0, w, h)
        canvas.scale(scale, scale)
        safe.draw(canvas)
        canvas.restore()
        canvas.setBitmap(null)
        return bitmap
    }

    /**
     * Наложение формы (Squircle, Circle, Rounded Square, Teardrop, Original) на иконку
     */
    fun maskIcon(source: Drawable, shape: IconShape): Bitmap {
        val srcBitmap = drawableToBitmap(source)
        if (shape == IconShape.ORIGINAL) {
            return srcBitmap
        }

        val size = min(srcBitmap.width, srcBitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        canvas.setBitmap(output)
        canvas.drawColor(0)
        rectF.set(0f, 0f, size.toFloat(), size.toFloat())

        path.reset()
        when (shape) {
            IconShape.CIRCLE -> {
                path.addOval(rectF, Path.Direction.CW)
            }
            IconShape.SQUIRCLE -> {
                // Суперэллипс / сквиркл со скруглением 28%
                val radius = size * 0.28f
                path.addRoundRect(rectF, radius, radius, Path.Direction.CW)
            }
            IconShape.ROUNDED_SQUARE -> {
                // Мягкий квадрат со скруглением 16%
                val radius = size * 0.16f
                path.addRoundRect(rectF, radius, radius, Path.Direction.CW)
            }
            IconShape.TEARDROP -> {
                // Форма капли (три угла скруглены, один более острый)
                val r35 = size * 0.35f
                val r08 = size * 0.08f
                val radii = floatArrayOf(r35, r35, r35, r35, r08, r08, r35, r35)
                path.addRoundRect(rectF, radii, Path.Direction.CW)
            }
            IconShape.ORIGINAL -> {
                path.addRect(rectF, Path.Direction.CW)
            }
        }

        canvas.drawPath(path, paint)

        // Исходник вписывается в квадрат по меньшей стороне и центрируется:
        // раньше drawBitmap без матрицы обрезал низ у неквадратных иконок
        val scale = size.toFloat() / min(srcBitmap.width, srcBitmap.height)
        canvas.save()
        canvas.translate(
            (size - srcBitmap.width * scale) / 2f,
            (size - srcBitmap.height * scale) / 2f
        )
        canvas.scale(scale, scale)
        canvas.drawBitmap(srcBitmap, 0f, 0f, srcInBitmapPaint)
        canvas.restore()
        canvas.setBitmap(null)

        return output
    }
}