package com.iraqtranslate

import android.app.Activity
import android.os.Bundle
import android.widget.CheckBox
import android.widget.TextView

/** The language choice writes the same preferences already consumed by CaptureService. */
class ChoicePanelActivity : Activity() {
    private lateinit var selected: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val installer = SpeechModelInstaller(this)
        val root = HubUi.root(this, "اختيار لغة الصوت", "ChoicePanel • يقرأ إعدادات المحرك الحالي دون تعديل طريقة التقاط الصوت")
        val panel = HubUi.panel(this, "الكشف التلقائي واللغة", "يحتاج الكشف التلقائي نموذج Sherpa-ONNX؛ ويحتاج التعرف المباشر نموذج Vosk المناسب")
        selected = HubUi.label(this, "", 14f, HubUi.ACCENT)
        val auto = CheckBox(this).apply {
            text = "اكتشاف اللغة تلقائياً"
            setTextColor(HubUi.WHITE)
            isChecked = getSharedPreferences("iraq_translate", MODE_PRIVATE).getBoolean("auto_language", true)
            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences("iraq_translate", MODE_PRIVATE).edit().putBoolean("auto_language", enabled).apply()
                showSelection(installer)
            }
        }
        panel.addView(auto)
        panel.addView(selected)
        SpeechModelInstaller.MODELS.forEach { spec ->
            val installed = if (installer.isInstalled(spec)) "مثبّت" else "غير مثبّت"
            panel.addView(HubUi.action(this, "${spec.label} (${spec.id}) • $installed") {
                installer.selectModel(spec.id)
                showSelection(installer)
            })
        }
        panel.addView(HubUi.action(this, "تحميل النماذج (الإعدادات المتقدمة)") {
            startActivity(android.content.Intent(this, MainActivity::class.java))
        })
        root.addView(panel)
        showSelection(installer)
    }
    private fun showSelection(installer: SpeechModelInstaller) {
        val choice = installer.selectedModel()
        selected.text = "النموذج المختار: ${choice.label} • ${if (installer.isInstalled(choice)) "جاهز" else "يحتاج تحميل"}"
    }
}
