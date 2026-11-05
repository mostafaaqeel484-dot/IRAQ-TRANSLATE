package com.iraqtranslate

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import java.util.concurrent.atomic.AtomicReference

/** A single non-interactive TV overlay: no microphone, screenshots, or UI interception. */
object OverlayController {
    private val handler = Handler(Looper.getMainLooper())
    private val current = AtomicReference<GlassSubtitle?>(null)

    fun isVisible(): Boolean = current.get() != null
    fun toggle(context: Context) { if (isVisible()) hide(context) else show(context) }

    fun show(context: Context): Boolean {
        val app = context.applicationContext
        if (!Settings.canDrawOverlays(app)) return false
        handler.post {
            if (current.get() != null) return@post
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val style = SubtitleAppearance.get(app)
            val text = GlassSubtitle(app).apply {
                applyStyle(style)
                this.text = RuntimeStatus.caption.ifBlank { "IRAQ TRANSLATE" }
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                y = HubUi.dp(app, style.yDp)
            }
            runCatching {
                wm.addView(text, params)
                current.set(text)
            }
        }
        return true
    }

    fun refresh(context: Context) {
        val app = context.applicationContext
        handler.post {
            val view = current.get() ?: return@post
            val style = SubtitleAppearance.get(app)
            view.applyStyle(style)
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = view.layoutParams as? WindowManager.LayoutParams ?: return@post
            params.y = HubUi.dp(app, style.yDp)
            runCatching { wm.updateViewLayout(view, params) }
        }
    }

    fun hide(context: Context) {
        val app = context.applicationContext
        handler.post {
            val view = current.getAndSet(null) ?: return@post
            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            runCatching { wm.removeView(view) }
        }
    }
    fun clear() {
        RuntimeStatus.publishCaption("")
        handler.post { current.get()?.text = "" }
    }
    fun update(context: Context, value: String) {
        val clean = ArabicTextNormalizer.displayText(value)
        if (clean.isBlank()) return
        RuntimeStatus.publishCaption(clean)
        if (!isVisible()) show(context)
        handler.post { current.get()?.text = clean }
    }
}
