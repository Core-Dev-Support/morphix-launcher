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

object IconMaskUtil {

    /**
     * Преобразование любого Drawable в Bitmap с безопасными размерами
     */
    fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return drawable.bitmap
        }

        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
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

        val size = srcBitmap.width.coerceAtMost(srcBitmap.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rectF = RectF(0f, 0f, size.toFloat(), size.toFloat())

        val path = Path()
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
                val radii = floatArrayOf(
                    size * 0.35f, size * 0.35f,
                    size * 0.35f, size * 0.35f,
                    size * 0.08f, size * 0.08f,
                    size * 0.35f, size * 0.35f
                )
                path.addRoundRect(rectF, radii, Path.Direction.CW)
            }
            IconShape.ORIGINAL -> {
                path.addRect(rectF, Path.Direction.CW)
            }
        }

        canvas.drawPath(path, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(srcBitmap, 0f, 0f, paint)

        return output
    }
}
