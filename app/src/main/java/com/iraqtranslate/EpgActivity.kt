package com.iraqtranslate

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Bring your own XMLTV guide. Channel selection is explicit; EPG is not tuner access. */
class EpgActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var channels: LinearLayout
    private lateinit var channelFilter: EditText
    private val openGuide = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) io.execute {
            val outcome = runCatching {
                contentResolver.openInputStream(uri)?.use { readBounded(it) } ?: error("تعذر قراءة ملف XMLTV")
            }.mapCatching { EpgRepository.importGuide(this, it) }
            runOnUiThread {
                status.text = outcome.fold({ "تم استيراد ${it.first} قناة و${it.second} برنامج" }, { "تعذّر تحميل XMLTV: ${it.message}" })
                refreshChannels()
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = HubUi.root(this, "دليل البرامج XMLTV", "دليل القنوات من ملفك أو عنوان HTTPS؛ لا يشغّل موالف DVB ولا يتحكم بالموتور")
        val panel = HubUi.panel(this, "دليل البرامج", "اختر القناة الظاهرة فعلياً على جهازك. اسم البرنامج دليل مقترح وليس إثباتاً أنه فيلم")
        status = HubUi.label(this, "لم يتم فحص الدليل بعد", 14f, HubUi.MUTED)
        panel.addView(status)
        panel.addView(HubUi.action(this, "استيراد ملف XMLTV من الجهاز") {
            openGuide.launch(arrayOf("application/xml", "text/xml", "text/*", "application/octet-stream"))
        })
        val url = EditText(this).apply {
            hint = "https://example.org/guide.xml"
            setSingleLine(true)
            setTextColor(HubUi.WHITE)
            setHintTextColor(HubUi.MUTED)
        }
        panel.addView(url)
        panel.addView(HubUi.action(this, "تحديث دليل XMLTV من عنوان HTTPS") {
            val address = url.text.toString().trim()
            status.text = "جارٍ تنزيل الدليل…"
            io.execute {
                val outcome = runCatching {
                    val parsed = URL(address)
                    require(parsed.protocol == "https" && parsed.userInfo == null) { "يسمح بعناوين HTTPS فقط" }
                    val conn = parsed.openConnection() as HttpURLConnection
                    conn.connectTimeout = 12_000
                    conn.readTimeout = 25_000
                    try {
                        check(conn.responseCode in 200..299) { "HTTP ${conn.responseCode}" }
                        val bytes = conn.inputStream.use { readBounded(it) }
                        EpgRepository.importGuide(this, bytes)
                    } finally { conn.disconnect() }
                }
                runOnUiThread {
                    status.text = outcome.fold({ "تم تحديث ${it.first} قناة و${it.second} برنامج" }, { "تعذّر التحديث: ${it.message}" })
                    refreshChannels()
                }
            }
        })
        root.addView(panel)
        channels = HubUi.panel(this, "اختيار القناة الحالية")
        channelFilter = EditText(this).apply {
            hint = "بحث باسم القناة"
            setSingleLine(true)
            setTextColor(HubUi.WHITE)
            setHintTextColor(HubUi.MUTED)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refreshChannels() }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        root.addView(channelFilter)
        root.addView(channels)
        refreshChannels()
    }
    private fun readBounded(stream: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            check(out.size() + n <= 8_000_000) { "حجم دليل XMLTV يتجاوز 8 ميغابايت" }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
    private fun refreshChannels() {
        channels.removeAllViews()
        val guide = EpgRepository.guide(this)
        if (guide == null) {
            channels.addView(HubUi.label(this, "استورد دليلاً أولاً"))
            return
        }
        val selected = EpgRepository.selectedChannel(this)
        channels.addView(HubUi.label(this, "القناة المحددة: ${selected.ifBlank { "لا توجد" }}", 14f))
        channels.addView(HubUi.action(this, "إلغاء اختيار القناة") {
            EpgRepository.clearChannel(this)
            if (MediaSelectionStore.get(this)?.source == "epg") MediaSelectionStore.clear(this)
            if (RuntimeStatus.capturing) startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_REFRESH_SELECTION))
            refreshChannels()
        })
        val filtered = guide.channels.filter { it.name.contains(channelFilter.text.toString().trim(), ignoreCase = true) }.take(120)
        if (filtered.isEmpty()) channels.addView(HubUi.label(this, "لم تتطابق قنوات مع البحث"))
        filtered.forEach { ch ->
            val current = guide.now(ch.id)
            val label = "${ch.name}${if (ch.id == selected) " ✓" else ""} • ${current?.title ?: "لا يوجد برنامج جارٍ"}"
            channels.addView(HubUi.action(this, label) {
                EpgRepository.setChannel(this, ch.id)
                val programme = EpgRepository.currentlyPlaying(this)
                if (programme != null) {
                    MediaSelectionStore.save(this, MediaSelection(
                        title = programme.title, source = "epg", expiresAtMs = programme.endMs
                    ))
                    if (RuntimeStatus.capturing) startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_REFRESH_SELECTION))
                    status.text = "تم اختيار ${ch.name}: ${programme.title}. سيُبحث عن ترجمة، ثم تُزامَن بالصوت عند توفر تطابق."
                } else {
                    if (MediaSelectionStore.get(this)?.source == "epg") MediaSelectionStore.clear(this)
                    if (RuntimeStatus.capturing) startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_REFRESH_SELECTION))
                    status.text = "تم اختيار ${ch.name}، ولا يوجد برنامج جارٍ في الدليل"
                }
                refreshChannels()
            })
        }
    }
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
}
