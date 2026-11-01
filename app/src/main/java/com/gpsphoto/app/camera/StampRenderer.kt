package com.gpsphoto.app.camera

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.StyleSpan
import kotlin.math.ceil
import kotlin.math.max

/** What the stamp says. [map] is drawn only by the Advanced template. */
data class StampContent(val title: String, val lines: List<String>, val map: Bitmap?, val mapAttribution: String)

/**
 * Draws the stamp as a transparent strip as wide as the photo. The live preview and the saved
 * photo use this same code, so what you see on screen is what gets saved. Sizes scale with
 * width (1 unit = width / 360), so it looks the same on a 1080 px screen and a 4000 px photo.
 */
object StampRenderer {
    fun render(width: Int, content: StampContent, template: Template): Bitmap {
        val u = width / 360f
        return if (template == Template.MINIMAL) minimal(width, u, content)
        else panel(width, u, content, if (template == Template.ADVANCED) content.map else null)
    }

    /** Stamps the photo in place (copying first if it is immutable) and returns it. */
    fun stamp(photo: Bitmap, content: StampContent, template: Template): Bitmap {
        val out = if (photo.isMutable) photo else photo.copy(Bitmap.Config.ARGB_8888, true)
        val strip = render(out.width, content, template)
        Canvas(out).drawBitmap(strip, 0f, (out.height - strip.height).toFloat(), null)
        strip.recycle()
        return out
    }

    private fun panel(width: Int, u: Float, content: StampContent, map: Bitmap?): Bitmap {
        val margin = 8 * u
        val pad = 8 * u
        val mapSize = if (map != null) 92 * u else 0f
        val textWidth = (width - 2 * margin - 2 * pad - if (map != null) mapSize + pad else 0f).toInt().coerceAtLeast(1)
        val layout = textLayout(content.title, content.lines, u, textWidth, shadow = false)
        val panelHeight = max(layout.height + 2 * pad, mapSize + 2 * pad)
        val bitmap = Bitmap.createBitmap(width, ceil(panelHeight + margin).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val panelRect = RectF(margin, 0f, width - margin, panelHeight)
        canvas.drawRoundRect(panelRect, 10 * u, 10 * u, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x99000000.toInt() })

        var textLeft = margin + pad
        if (map != null) {
            val dst = RectF(margin + pad, pad, margin + pad + mapSize, pad + mapSize)
            canvas.save()
            canvas.clipPath(Path().apply { addRoundRect(dst, 6 * u, 6 * u, Path.Direction.CW) })
            canvas.drawBitmap(map, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            canvas.restore()
            drawPin(canvas, dst.centerX(), dst.centerY(), u)
            val attribution = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 6 * u
                color = 0xFF000000.toInt()
                setShadowLayer(1.5f * u, 0f, 0f, 0xFFFFFFFF.toInt())
            }
            canvas.drawText(content.mapAttribution, dst.left + 3 * u, dst.bottom - 3 * u, attribution)
            textLeft = dst.right + pad
        }
        canvas.save()
        canvas.translate(textLeft, (panelHeight - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }

    private fun minimal(width: Int, u: Float, content: StampContent): Bitmap {
        val margin = 10 * u
        val layout = textLayout(content.title, content.lines.take(2), u, (width - 2 * margin).toInt(), shadow = true)
        val bitmap = Bitmap.createBitmap(width, ceil(layout.height + 2 * margin).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(margin, margin)
        layout.draw(canvas)
        return bitmap
    }

    private fun textLayout(title: String, lines: List<String>, u: Float, width: Int, shadow: Boolean): StaticLayout {
        val text = SpannableStringBuilder(title)
        text.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.setSpan(AbsoluteSizeSpan((15 * u).toInt()), 0, title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        lines.forEach { text.append('\n').append(it) }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 11 * u
            if (shadow) setShadowLayer(3 * u, 0f, 0f, 0xCC000000.toInt())
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2 * u, 1f)
            .build()
    }

    private fun drawPin(canvas: Canvas, x: Float, y: Float, u: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(x, y, 6.5f * u, paint)
        paint.color = 0xFFE53935.toInt()
        canvas.drawCircle(x, y, 5 * u, paint)
    }
}
