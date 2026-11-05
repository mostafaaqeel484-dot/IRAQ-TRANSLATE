package com.iraqtranslate

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.TextView

/** Original-inspired adjustable caption appearance, not copied bytecode. */
object SubtitleAppearance {
    private const val PREFS = "iraq_subtitle_appearance"
    data class Style(val sizeSp: Int, val yDp: Int, val opacity: Int, val strokeDp: Int)
    fun get(context: Context): Style {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Style(
            prefs.getInt("size", 25).coerceIn(14, 48),
            prefs.getInt("y", 55).coerceIn(0, 260),
            prefs.getInt("opacity", 145).coerceIn(0, 245),
            prefs.getInt("stroke", 2).coerceIn(0, 5)
        )
    }
    fun save(context: Context, style: Style) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("size", style.sizeSp.coerceIn(14, 48))
            .putInt("y", style.yDp.coerceIn(0, 260))
            .putInt("opacity", style.opacity.coerceIn(0, 245))
            .putInt("stroke", style.strokeDp.coerceIn(0, 5))
            .apply()
    }
}

/** Outlined Arabic-friendly text with translucent glass card. */
class GlassSubtitle(context: Context) : TextView(context) {
    private var borderPx = 0f
    fun applyStyle(style: SubtitleAppearance.Style) {
        textSize = style.sizeSp.toFloat()
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        maxLines = 4
        includeFontPadding = true
        borderPx = HubUi.dp(context, style.strokeDp).toFloat()
        setPadding(HubUi.dp(context, 18), HubUi.dp(context, 10), HubUi.dp(context, 18), HubUi.dp(context, 10))
        background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.argb(style.opacity / 2, 25, 37, 54), Color.argb(style.opacity, 5, 12, 24))
        ).apply { cornerRadius = HubUi.dp(context, 12).toFloat() }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (borderPx <= 0f) {
            super.onDraw(canvas)
            return
        }
        val p = paint
        val previous = p.style
        val previousColor = p.color
        val previousWidth = p.strokeWidth
        // TextView's layout renders twice: black stroke then the normal glyph fill.
        p.style = Paint.Style.STROKE
        p.strokeWidth = borderPx * 2f
        p.strokeJoin = Paint.Join.ROUND
        p.color = Color.BLACK
        super.onDraw(canvas)
        p.style = Paint.Style.FILL
        p.color = previousColor
        super.onDraw(canvas)
        p.strokeWidth = previousWidth
        p.style = previous
        p.color = previousColor
    }
}
