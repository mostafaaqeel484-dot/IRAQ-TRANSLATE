package com.iraqtranslate

import android.app.Activity
import android.os.Bundle
import android.widget.SeekBar
import android.widget.TextView

/** Adjusts actual OverlayController window style and a live in-app preview. */
class SubtitleStyleActivity : Activity() {
    private lateinit var preview: GlassSubtitle
    private var style: SubtitleAppearance.Style by kotlin.properties.Delegates.observable(
        SubtitleAppearance.Style(25, 55, 145, 2)
    ) { _, _, new ->
        if (::preview.isInitialized) preview.applyStyle(new)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        style = SubtitleAppearance.get(this)
        val root = HubUi.root(this, "مظهر الترجمة", "GlassSubtitle • SubtitleOverlay • PreviewCard")
        val card = HubUi.panel(this, "معاينة حية", "التحكم في الحجم والشفافية والحدود والبعد عن أسفل الشاشة")
        preview = GlassSubtitle(this).apply {
            text = "هذه معاينة عربية واضحة على التلفاز"
            applyStyle(style)
        }
        card.addView(preview)
        slider(card, "حجم الخط", style.sizeSp, 14, 48) { style = style.copy(sizeSp = it) }
        slider(card, "بعد الترجمة عن أسفل الشاشة (dp)", style.yDp, 0, 260) { style = style.copy(yDp = it) }
        slider(card, "شفافية الخلفية", style.opacity, 0, 245) { style = style.copy(opacity = it) }
        slider(card, "سماكة حدود الحروف", style.strokeDp, 0, 5) { style = style.copy(strokeDp = it) }
        card.addView(HubUi.action(this, "حفظ المظهر وتحديث ترجمة الشاشة") {
            SubtitleAppearance.save(this, style)
            OverlayController.refresh(this)
            android.widget.Toast.makeText(this, "تم حفظ المظهر", android.widget.Toast.LENGTH_SHORT).show()
        })
        card.addView(HubUi.action(this, "استعادة الإعدادات الافتراضية") {
            style = SubtitleAppearance.Style(25, 55, 145, 2)
            SubtitleAppearance.save(this, style)
            OverlayController.refresh(this)
            recreate()
        })
        root.addView(card)
    }

    private fun slider(
        parent: android.widget.LinearLayout,
        label: String,
        initial: Int,
        min: Int,
        max: Int,
        onChanged: (Int) -> Unit
    ) {
        val title = HubUi.label(this, "$label: $initial", 14f)
        val control = SeekBar(this).apply {
            this.max = max - min
            progress = initial - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val value = min + progress
                    title.text = "$label: $value"
                    onChanged(value)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        parent.addView(title)
        parent.addView(control)
    }
}
