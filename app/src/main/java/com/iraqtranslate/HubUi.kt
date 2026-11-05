package com.iraqtranslate

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** TV-remote-friendly native panels. No dependency on unsupported/unknown reference Compose internals. */
object HubUi {
    const val BACK = 0xFF0B1423.toInt()
    const val PANEL = 0xFF162438.toInt()
    const val ACCENT = 0xFF1CC7AF.toInt()
    const val WHITE = Color.WHITE
    const val MUTED = 0xFFB6CBD6.toInt()

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun background(color: Int = PANEL, stroke: Int = 0xFF314759.toInt(), corner: Float = 16f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            setCornerRadius(corner)
            setStroke(1, stroke)
        }

    fun column(context: Context, padding: Int = 14): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(context, padding), dp(context, padding), dp(context, padding), dp(context, padding))
    }

    fun root(activity: Activity, title: String, description: String? = null): LinearLayout {
        val content = column(activity, 18)
        content.setBackgroundColor(BACK)
        content.addView(label(activity, title, 28f, WHITE, true))
        if (description != null) content.addView(label(activity, description, 14f, MUTED))
        activity.setContentView(ScrollView(activity).apply {
            isFillViewport = true
            addView(content)
            setBackgroundColor(BACK)
        })
        return content
    }

    fun label(context: Context, value: String, size: Float = 16f, color: Int = WHITE, bold: Boolean = false): TextView = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(context, 6), dp(context, 8), dp(context, 6), dp(context, 8))
    }

    fun panel(context: Context, title: String, subtitle: String? = null): LinearLayout = column(context, 14).apply {
        background = HubUi.background()
        val margin = dp(context, 10)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = margin
        }
        addView(label(context, title, 19f, WHITE, true))
        if (subtitle != null) addView(label(context, subtitle, 13f, MUTED))
    }

    fun action(context: Context, title: String, click: () -> Unit): Button = Button(context).apply {
        text = title
        textSize = 15f
        setAllCaps(false)
        isFocusable = true
        isFocusableInTouchMode = false
        setTextColor(WHITE)
        background = HubUi.background(0xFF22415A.toInt(), ACCENT, 12f)
        minHeight = dp(context, 50)
        val spacing = dp(context, 5)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = spacing
            bottomMargin = spacing
        }
        setOnClickListener { click() }
    }

    fun spacer(context: Context, height: Int = 12): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, height))
    }
}
