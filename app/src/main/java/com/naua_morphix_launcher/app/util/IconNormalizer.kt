package com.naua_morphix_launcher.app.util

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Нормализация оптического размера иконок (паритет с MIUI/HyperOS).
 *
 * Адаптивные иконки (API 26+) содержат собственный фоновый слой: их глиф
 * занимает лишь ~66% квадрата. Легаси-иконки рисуются «от края до края»,
 * и после обрезки сквирклом выглядят заметно крупнее соседних — это и
 * читалось как «где-то иконки как надо, где-то огромные».
 *
 * Решение: легаси-иконку кладём на «тарелку» из усреднённого цвета самого
 * значка и вписываем содержимое в [CONTENT_FRACTION] квадрата, как это
 * делает HyperOS со старыми иконками. Растеризуется только миниатюра
 * 48×48 для снятия среднего цвета — постоянной памяти решение не требует.
 */
object IconNormalizer {

    /** Доля квадрата, которую занимает содержимое легаси-иконки на тарелке. */
    private const val CONTENT_FRACTION = 0.72f

    private val platePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** Пределы светлоты тарелки: чистый белый/чёрный фон съедает глиф. */
    private const val MIN_LIGHTNESS = 0.38f
    private const val MAX_LIGHTNESS = 0.72f
    private val fallbackPlate = 0xFFF3F4F6.toInt()

    fun normalize(source: Drawable): Drawable {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            source is android.graphics.drawable.AdaptiveIconDrawable
        ) {
            // У адаптивных свой слой фона уже в правильных пропорциях.
            return source
        }
        return PlateDrawable(source, extractPlateColor(source))
    }

    /** Усреднённый цвет непрозрачных пикселей рамки значка. */
    private fun extractPlateColor(source: Drawable): Int {
        return try {
            val w = (source.intrinsicWidth.takeIf { it > 0 } ?: 64).coerceIn(1, 512)
            val h = (source.intrinsicHeight.takeIf { it > 0 } ?: 64).coerceIn(1, 512)
            val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val safe = source.constantState?.newDrawable()?.mutate() ?: source
            safe.setBounds(0, 0, w, h)
            canvas.scale(48f / w, 48f / h)
            safe.draw(canvas)

            var r = 0f
            var g = 0f
            var b = 0f
            var n = 0
            // Рамка 48×48 (уголки обычно прозрачные у легаси) — честный
            // цвет тарелки даёт только фон вокруг глифа.
            val margin = 6
            val step = 3
            for (y in 0 until 48 step step) {
                for (x in 0 until 48 step step) {
                    val edge = x < margin || y < margin || x >= 48 - margin || y >= 48 - margin
                    if (!edge) continue
                    val p = bmp.getPixel(x, y)
                    if ((p ushr 24) > 32) {
                        r += ((p shr 16) and 0xFF).toFloat()
                        g += ((p shr 8) and 0xFF).toFloat()
                        b += (p and 0xFF).toFloat()
                        n++
                    }
                }
            }
            bmp.recycle()
            if (n == 0) return fallbackPlate

            return plateFromAverage(
                (r / n).roundToInt(),
                (g / n).roundToInt(),
                (b / n).roundToInt()
            )
        } catch (e: Exception) {
            fallbackPlate
        }
    }

    /** Средний цвет по каналам с тиснением светлоты в читаемый диапазон. */
    private fun plateFromAverage(r: Int, g: Int, b: Int): Int {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val maxc = maxOf(rf, gf, bf)
        val minc = minOf(rf, gf, bf)
        var l = (maxc + minc) / 2f
        val s = if (maxc == minc) 0f else {
            val d = maxc - minc
            if (l > 0.5f) d / (2f - maxc - minc) else d / (maxc + minc)
        }
        l = l.coerceIn(MIN_LIGHTNESS, MAX_LIGHTNESS)
        if (s <= 0.0001f) {
            val v = (l * 255f).roundToInt()
            return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val sect = if (maxc == rf) ((gf - bf) / c) else if (maxc == gf) 2f + (bf - rf) / c else 4f + (rf - gf) / c
        val hp = (sect * 60f + 360f) % 360f
        val hue = hp / 60f % 6f
        val x = c * (1f - kotlin.math.abs(hue % 2f - 1f))
        val m = l - c / 2f
        val (rr, gg, bb) = when (hue.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return (0xFF shl 24) or
            ((m + rr).coerceIn(0f, 1f).let { (it * 255f).roundToInt() } shl 16) or
            ((m + gg).coerceIn(0f, 1f).let { (it * 255f).roundToInt() } shl 8) or
            ((m + bb).coerceIn(0f, 1f).let { (it * 255f).roundToInt() })
    }

    /**
     * Обёртка: рисует тарелку на всю площадь и ужимает исходник в центр.
     * Круглость тарелки даёт клип ShapeableImageView (форма применяемая
     * на view), поэтому здесь достаточно прямоугольника.
     */
    private class PlateDrawable(
        private val base: Drawable,
        private val plateColor: Int
    ) : Drawable() {

        override fun draw(canvas: Canvas) {
            val b = bounds
            if (b.width() == 0 || b.height() == 0) return
            platePaint.color = plateColor
            canvas.drawRect(b, platePaint)

            // Содержимое центрируется в доле CONTENT_FRACTION меньшей стороны.
            // Неквадратные легаси-иконки вписываем с сохранением пропорций —
            // растяжение в квадрат искажало бы знак.
            val minSide = min(b.width(), b.height()).toFloat()
            val iw = base.intrinsicWidth
            val ih = base.intrinsicHeight
            if (iw > 0 && ih > 0 && iw != ih) {
                val scale = minSide * CONTENT_FRACTION / min(iw, ih)
                val cw = (iw * scale).roundToInt().coerceAtMost(b.width())
                val ch = (ih * scale).roundToInt().coerceAtMost(b.height())
                base.setBounds(
                    b.left + (b.width() - cw) / 2,
                    b.top + (b.height() - ch) / 2,
                    b.left + (b.width() + cw) / 2,
                    b.top + (b.height() + ch) / 2
                )
            } else {
                val side = (minSide * CONTENT_FRACTION).roundToInt()
                base.setBounds(
                    b.left + (b.width() - side) / 2,
                    b.top + (b.height() - side) / 2,
                    b.left + (b.width() + side) / 2,
                    b.top + (b.height() + side) / 2
                )
            }
            base.draw(canvas)
        }

        override fun setAlpha(alpha: Int) {
            platePaint.alpha = alpha
            base.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            base.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = base.intrinsicWidth.takeIf { it > 0 } ?: -1
        override fun getIntrinsicHeight(): Int = base.intrinsicHeight.takeIf { it > 0 } ?: -1
    }
}
