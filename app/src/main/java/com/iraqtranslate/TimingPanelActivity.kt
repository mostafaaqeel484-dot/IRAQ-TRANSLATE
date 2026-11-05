package com.iraqtranslate

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/** TimingPanel: manual shift and genuine audio re-sync request to running CaptureService. */
class TimingPanelActivity : Activity() {
    private lateinit var current: TextView
    private lateinit var syncText: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = HubUi.root(this, "مزامنة الترجمة", "TimingPanel • يدعم إزاحة SRT وإعادة التقاط نقاط المزامنة من الكلام")
        val panel = HubUi.panel(this, "تقديم وتأخير الترجمة", "الإشارة الموجبة تؤخّر الترجمة، والسالبة تقدّمها. ±15 ثانية")
        current = HubUi.label(this, "", 21f, HubUi.ACCENT, true)
        panel.addView(current)
        panel.addView(HubUi.action(this, "تقديم الترجمة 1 ثانية") { change(-1000L) })
        panel.addView(HubUi.action(this, "تقديم الترجمة 0.5 ثانية") { change(-500L) })
        panel.addView(HubUi.action(this, "تأخير الترجمة 0.5 ثانية") { change(500L) })
        panel.addView(HubUi.action(this, "تأخير الترجمة 1 ثانية") { change(1000L) })
        panel.addView(HubUi.action(this, "إرجاع الإزاحة إلى الصفر") { SubtitleTimingStore.set(this, 0L); showOffset() })
        root.addView(panel)
        val audioPanel = HubUi.panel(this, "المزامنة مع صوت الفيلم", "يحفظ ملف SRT الحالي لكنه يمسح نقاط المحاذاة القديمة، ثم يستمع إلى الكلام التالي لبناء نقاط جديدة")
        syncText = HubUi.label(this, RuntimeStatus.alignment, 14f)
        audioPanel.addView(syncText)
        audioPanel.addView(HubUi.action(this, "إعادة المزامنة مع الكلام التالي") {
            if (!RuntimeStatus.capturing) {
                syncText.text = "ابدأ التقاط الصوت أولاً ثم أعد طلب المزامنة"
            } else {
                startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_RESYNC_SPEECH))
                syncText.text = "تم إرسال طلب إعادة المحاذاة. ستستمر الترجمة الحية حتى يتم العثور على تطابق كلام موثوق."
            }
        })
        root.addView(audioPanel)
        root.addView(HubUi.panel(this, "حدود المزامنة", "لا يمكن ضمان التطابق دون SRT صالح وكلام قابل للتعرف. توقيت مشغل خارجي يتطلب MediaSession موثوقاً أو ربطاً حقيقياً بساعة الفيديو."))
        showOffset()
    }
    private fun change(deltaMs: Long) { SubtitleTimingStore.adjust(this, deltaMs); showOffset() }
    private fun showOffset() { current.text = SubtitleTimingMath.format(SubtitleTimingStore.get(this)) }
}
