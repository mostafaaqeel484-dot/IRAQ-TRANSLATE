package com.iraqtranslate

import android.app.Activity
import android.os.Bundle

/** Honest, local-only panels: the reference activation/update servers are not our endpoints. */
class AppInfoPanelActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = HubUi.root(this, "التفعيل والتحديث", "ActivationPanel • UpdatePanel")
        root.addView(HubUi.panel(this, "النسخة الجديدة من IRAQ TRANSLATE", "1.1.0-source • تطبيق مستقل بحزمة com.iraqtranslate"))
        root.addView(HubUi.panel(this, "ActivationPanel", "لا يوجد تفعيل مدفوع في هذه النسخة. لم ننقل مفاتيح أو خوادم الترخيص الخاصة بالتطبيق المرجعي"))
        root.addView(HubUi.panel(this, "UpdatePanel", "لا يوجد حالياً خادم تحديث رسمي للمشروع. حدّث التطبيق بواسطة APK موقّع تبنيه أنت من سورسك"))
        root.addView(HubUi.panel(this, "نطاق الهندسة العكسية", "واجهة عربية وعناصر اتصال وترجمة ومزامنة مُعادة التنفيذ بصورة مستقلة. دعم DVB/USALS/DiSEqC غير مُثبت وغير مضاف"))
    }
}
