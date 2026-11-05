package com.iraqtranslate

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.atomic.AtomicReference

/** Owns the single translation overlay window for the whole application process. */
object OverlayController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val currentView = AtomicReference<TextView?>(null)

    fun isVisible(): Boolean = currentView.get() != null

    fun toggle(context: Context) {
        if (isVisible()) hide(context) else show(context)
    }

    fun show(context: Context): Boolean {
        val app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(app)) return false
        if (isVisible()) return true
        mainHandler.post {
            if (currentView.get() != null) return@post
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val text = TextView(app).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0xE6101010.toInt())
                textSize = 22f
                text = "IRAQ TRANSLATE"
                textDirection = TextView.TEXT_DIRECTION_FIRST_STRONG
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                maxLines = 4
                setPadding(22, 14, 22, 14)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            val type = if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP
                y = 64
            }
            runCatching {
                wm.addView(text, params)
                currentView.set(text)
            }
        }
        return true
    }

    fun hide(context: Context) {
        val app = context.applicationContext
        mainHandler.post {
            val text = currentView.getAndSet(null) ?: return@post
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching { wm.removeView(text) }
        }
    }

    fun clear() {
        mainHandler.post { currentView.get()?.text = "" }
    }

    /** Ensures the overlay exists before publishing a translated segment. */
    fun update(context: Context, value: String) {
        val clean = ArabicTextNormalizer.displayText(value)
        if (clean.isBlank()) return
        val app = context.applicationContext
        if (currentView.get() == null) show(app)
        mainHandler.post {
            val text = currentView.get()
            if (text != null) text.text = clean
        }
    }
}
