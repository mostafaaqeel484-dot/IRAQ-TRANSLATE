package com.iraqtranslate

import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors

/** Pick a title -> persist media identity -> request SRT lookup in CaptureService. */
class SearchActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var currentMedia: TextView
    private lateinit var resultPanel: LinearLayout
    private lateinit var season: EditText
    private lateinit var episode: EditText
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadSrt(uri)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = HubUi.root(this, "البحث عن الفيلم والترجمة", "اختيار العمل يرسله إلى محرك البحث عن SRT، وليس إلى متصفح IMDb")
        val searchPanel = HubUi.panel(this, "اختيار فيلم أو مسلسل", "للمسلسلات أضف رقم الموسم والحلقة قبل اختيار نتيجة IMDb")
        currentMedia = HubUi.label(this, mediaStatus(), 14f, HubUi.MUTED)
        searchPanel.addView(currentMedia)
        val query = EditText(this).apply {
            hint = "اسم الفيلم أو المسلسل"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            setTextColor(HubUi.WHITE)
            setHintTextColor(HubUi.MUTED)
        }
        season = numberField("رقم الموسم (اختياري)")
        episode = numberField("رقم الحلقة (اختياري)")
        searchPanel.addView(query)
        searchPanel.addView(season)
        searchPanel.addView(episode)
        searchPanel.addView(HubUi.action(this, "بحث IMDb") { lookup(query.text.toString()) })
        searchPanel.addView(HubUi.action(this, "مسح اختيار الفيلم والعودة إلى الاكتشاف من الحوار") {
            MediaSelectionStore.clear(this)
            if (RuntimeStatus.capturing) startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_REFRESH_SELECTION))
            currentMedia.text = mediaStatus()
        })
        resultPanel = HubUi.column(this, 4)
        searchPanel.addView(resultPanel)
        root.addView(searchPanel)
        val srtPanel = HubUi.panel(this, "استيراد ملف SRT عربي", "يُطبَّق في جلسة الالتقاط التالية؛ الأولوية للملف المحلي")
        status = HubUi.label(this, manualStatus(), 14f, HubUi.MUTED)
        srtPanel.addView(status)
        srtPanel.addView(HubUi.action(this, "اختيار ملف SRT من الجهاز") {
            openDocument.launch(arrayOf("text/*", "application/x-subrip", "application/octet-stream"))
        })
        srtPanel.addView(HubUi.action(this, "إلغاء SRT المحلي والعودة للبحث التلقائي") {
            ManualSubtitleRepository.clear(this)
            status.text = manualStatus() + " • أعد بدء الالتقاط"
        })
        root.addView(srtPanel)
    }
    private fun numberField(hintText: String): EditText = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_NUMBER
        setTextColor(HubUi.WHITE)
        setHintTextColor(HubUi.MUTED)
    }
    private fun mediaStatus() = MediaSelectionStore.get(this)?.let {
        "العمل المختار: ${it.title} • ${it.imdbId.ifBlank { it.source }}"
    } ?: "لا يوجد فيلم مختار؛ يعتمد المحرك على التعرف من الحوار أو دليل القنوات عند توفره"
    private fun manualStatus() = ManualSubtitleRepository.name(this)?.let { "ملف SRT المختار: $it" }
        ?: "لا يوجد ملف SRT محلي"
    private fun choose(hit: ImdbTitleLookup.Hit) {
        val s = season.text.toString().toIntOrNull()?.takeIf { it in 1..99 } ?: -1
        val e = episode.text.toString().toIntOrNull()?.takeIf { it in 1..999 } ?: -1
        MediaSelectionStore.save(this, MediaSelection(hit.title, hit.id, hit.year, s, e))
        currentMedia.text = mediaStatus()
        if (RuntimeStatus.capturing) startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_REFRESH_SELECTION))
        resultPanel.removeAllViews()
        resultPanel.addView(HubUi.label(this, "تم الاختيار: ${hit.title}. يبدأ البحث عن الترجمة في الخدمة إذا كانت تعمل. ستنتظر الترجمة الصوت للتزامن قبل العرض."))
    }
    private fun lookup(raw: String) {
        val value = raw.trim().take(90)
        if (value.length < 2) {
            resultPanel.removeAllViews()
            resultPanel.addView(HubUi.label(this, "أدخل حرفين على الأقل"))
            return
        }
        resultPanel.removeAllViews()
        resultPanel.addView(HubUi.label(this, "جارٍ البحث…"))
        io.execute {
            val results = runCatching { ImdbTitleLookup.search(value) }
            runOnUiThread {
                resultPanel.removeAllViews()
                results.fold(onSuccess = { items ->
                    if (items.isEmpty()) resultPanel.addView(HubUi.label(this, "لم يتم العثور على نتائج"))
                    items.forEach { hit ->
                        resultPanel.addView(HubUi.action(this, "اختيار ${hit.title} • ${hit.year}") { choose(hit) })
                    }
                }, onFailure = {
                    resultPanel.addView(HubUi.label(this, "تعذّر البحث: ${it.message}"))
                })
            }
        }
    }
    private fun loadSrt(uri: Uri) {
        status.text = "جارٍ فحص ملف SRT…"
        io.execute {
            val result = runCatching {
                val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "ترجمة محلية"
                val bytes = requireNotNull(contentResolver.openInputStream(uri)).use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(4096)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        check(out.size() + n <= 5_000_000) { "تجاوز الملف 5 ميغابايت" }
                        out.write(buf, 0, n)
                    }
                    out.toByteArray()
                }
                val count = ManualSubtitleRepository.save(this, name, bytes)
                "تم حفظ $count توقيتاً من $name. أعد تشغيل الالتقاط لتطبيقها."
            }.getOrElse { "تعذّر الاستيراد: ${it.message}" }
            runOnUiThread { status.text = result }
        }
    }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
}
