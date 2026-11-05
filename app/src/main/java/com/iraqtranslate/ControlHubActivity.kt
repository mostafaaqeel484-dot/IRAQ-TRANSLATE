package com.iraqtranslate

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/** Arabic RTL TV ControlHub. The proven audio/speech/translation engine remains untouched. */
class ControlHubActivity : ComponentActivity() {
    private lateinit var captureState: TextView
    private lateinit var connectionState: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var syncBadge: TextView
    private lateinit var preview: GlassSubtitle
    private lateinit var permissions: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateCards()
            handler.postDelayed(this, 1100L)
        }
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Notification permission is optional; boot reminder works only when allowed. */ }
    private val audioPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        if (allowed) requestPlaybackConsent() else message("يلزم إذن الصوت لالتقاط صوت تشغيل الوسائط، دون استخدام الميكروفون")
    }
    private val projectionConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startForegroundService(Intent(this, CaptureService::class.java).apply {
                action = CaptureService.ACTION_START
                putExtra(CaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(CaptureService.EXTRA_RESULT_DATA, result.data)
            })
            message("تم إرسال طلب بدء التقاط صوت الوسائط، راجع حالة التشغيل")
        } else message("لم تُمنح موافقة التقاط صوت التشغيل")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = HubUi.root(this, "IRAQ TRANSLATE", "لوحة التحكم • ترجمة صوت التلفاز إلى العربية")

        val statusPanel = HubUi.panel(this, "حالة الترجمة")
        captureState = HubUi.label(this, "جارٍ فحص حالة المحرك…", 16f)
        connectionState = HubUi.label(this, "اتصال ADB: غير محدد", 14f, HubUi.MUTED)
        statusPanel.addView(captureState)
        statusPanel.addView(connectionState)
        statusPanel.addView(HubUi.action(this, "بدء ترجمة الصوت عبر ADB") { startAdb() })
        statusPanel.addView(HubUi.action(this, "بدء الترجمة عبر إذن Android") { startProjection() })
        statusPanel.addView(HubUi.action(this, "إيقاف الترجمة") {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            RuntimeStatus.stopped()
            updateCards()
        })
        root.addView(statusPanel)

        val playback = HubUi.panel(this, "المحتوى الجاري والمزامنة", "تُعرض البيانات عند تأكيدها من محرك التعرف/الترجمة فقط")
        nowPlaying = HubUi.label(this, "لا يوجد عنوان معروف حالياً", 15f)
        syncBadge = HubUi.label(this, "المزامنة: غير متاحة", 13f, HubUi.MUTED)
        playback.addView(nowPlaying)
        playback.addView(syncBadge)
        root.addView(playback)

        val previewPanel = HubUi.panel(this, "معاينة الترجمة الزجاجية")
        preview = GlassSubtitle(this).apply {
            applyStyle(SubtitleAppearance.get(this@ControlHubActivity))
            text = "ستظهر الترجمة العربية هنا"
            setPadding(HubUi.dp(this@ControlHubActivity, 24), HubUi.dp(this@ControlHubActivity, 16), HubUi.dp(this@ControlHubActivity, 24), HubUi.dp(this@ControlHubActivity, 16))
        }
        previewPanel.addView(preview)
        previewPanel.addView(HubUi.action(this, "عرض / إخفاء ترجمة الشاشة") {
            if (Settings.canDrawOverlays(this)) OverlayController.toggle(this)
            else requestOverlay()
        })
        root.addView(previewPanel)

        val navigation = HubUi.panel(this, "المكونات والتحكم")
        navigation.addView(HubUi.action(this, "اتصال التلفاز وإقران ADB") { open(PairingPanelActivity::class.java) })
        navigation.addView(HubUi.action(this, "اختيار لغة الصوت • ChoicePanel") { open(ChoicePanelActivity::class.java) })
        navigation.addView(HubUi.action(this, "تصميم الترجمة • GlassSubtitle") { open(SubtitleStyleActivity::class.java) })
        navigation.addView(HubUi.action(this, "تقديم وتأخير الترجمة • TimingPanel") { open(TimingPanelActivity::class.java) })
        navigation.addView(HubUi.action(this, "البحث عن فيلم واختيار SRT") { open(SearchActivity::class.java) })
        navigation.addView(HubUi.action(this, "دليل القنوات والبرامج • XMLTV EPG") { open(EpgActivity::class.java) })
        navigation.addView(HubUi.action(this, "الإعدادات المتقدمة والنماذج ومفاتيح المزودين") { open(MainActivity::class.java) })
        root.addView(navigation)

        val system = HubUi.panel(this, "الصلاحيات وإدارة النظام")
        permissions = HubUi.label(this, "", 13f, HubUi.MUTED)
        system.addView(permissions)
        system.addView(HubUi.action(this, "السماح بظهور الترجمة فوق التطبيقات") { requestOverlay() })
        system.addView(HubUi.action(this, "إعداد اختصار جهاز التحكم") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        system.addView(HubUi.action(this, "معلومات النسخة والتحديث والتفعيل") { open(AppInfoPanelActivity::class.java) })
        val onBoot = CheckBox(this).apply {
            setTextColor(HubUi.WHITE)
            text = "إظهار تذكير بعد تشغيل الجهاز (لا يشغّل الالتقاط تلقائياً)"
            isChecked = getSharedPreferences("iraq_hub", MODE_PRIVATE).getBoolean("boot_reminder", false)
            setOnCheckedChangeListener { _, enabled ->
                getSharedPreferences("iraq_hub", MODE_PRIVATE).edit().putBoolean("boot_reminder", enabled).apply()
                if (enabled && Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        system.addView(onBoot)
        root.addView(system)
    }

    private fun updateCards() {
        captureState.text = if (RuntimeStatus.capturing)
            "الترجمة تعمل • ${RuntimeStatus.source} • ${RuntimeStatus.audioHealth(android.os.SystemClock.elapsedRealtime())}"
        else "الترجمة متوقفة"
        connectionState.text = "اتصال ADB: " + if (runCatching { AdbBridge.get(this).isConnected() }.getOrDefault(false)) "متصل" else "غير متصل"
        val title = RuntimeStatus.title ?: MediaSelectionStore.get(this)?.title
        nowPlaying.text = if (title != null) "${title} • ${RuntimeStatus.provider ?: "مصدر غير معروف"}" else "لم يتم التعرف على اسم الفيلم/القناة"
        syncBadge.text = "المزامنة: ${RuntimeStatus.alignment}"
        preview.text = RuntimeStatus.caption.ifBlank { "هذه معاينة لشكل الترجمة العربية" }
        preview.applyStyle(SubtitleAppearance.get(this))
        permissions.text = "الظهور فوق التطبيقات: ${if (Settings.canDrawOverlays(this)) "مسموح" else "غير مسموح"}  •  " +
            "إذن الصوت: ${if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "مسموح" else "غير مسموح"}"
    }

    private fun validateModel(): Boolean {
        val auto = getSharedPreferences("iraq_translate", Context.MODE_PRIVATE).getBoolean("auto_language", true)
        val ready = if (auto) LanguageIdModelInstaller(this).isInstalled() else {
            val installer = SpeechModelInstaller(this)
            installer.isInstalled(installer.selectedModel())
        }
        if (!ready) message("النموذج الصوتي المحدد غير مثبّت. افتح الإعدادات المتقدمة لتحميله")
        return ready
    }

    private fun startAdb() {
        if (!validateModel()) return
        if (Build.VERSION.SDK_INT < 34) {
            message("مسار Echo Reference الحالي في هذا المشروع يتطلب Android 14+. استخدم بديل التقاط الصوت")
            return
        }
        if (!AdbBridge.get(this).isConnected()) {
            message("اربط التلفاز من لوحة إقران ADB أولاً")
            return
        }
        startForegroundService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START_ADB))
    }

    private fun startProjection() {
        if (!validateModel()) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AlertDialog.Builder(this).setTitle("إذن التقاط صوت الوسائط")
                .setMessage("يستخدم Android إذن RECORD_AUDIO للسماح بالتقاط الصوت الذي ينشره مشغل الوسائط. لا يستخدم التطبيق مدخل الميكروفون ولا يلتقط صور الشاشة.")
                .setPositiveButton("موافقة") { _, _ -> audioPermission.launch(Manifest.permission.RECORD_AUDIO) }
                .setNegativeButton("إلغاء", null).show()
        } else requestPlaybackConsent()
    }
    private fun requestPlaybackConsent() {
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionConsent.launch(manager.createScreenCaptureIntent())
    }
    private fun requestOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }
    private fun <T : android.app.Activity> open(activity: Class<T>) = startActivity(Intent(this, activity))
    private fun message(text: String) = AlertDialog.Builder(this).setMessage(text).setPositiveButton("حسناً", null).show()
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
}
