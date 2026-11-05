package com.iraqtranslate

/** Volatile read-only process state surfaced to the ControlHub. Never invent media/EPG metadata. */
object RuntimeStatus {
    @Volatile var lastAudioChunkElapsedMs: Long = 0L
        private set
    fun audioObserved(elapsedMs: Long) { lastAudioChunkElapsedMs = elapsedMs }
    fun audioHealth(nowElapsedMs: Long): String = when {
        !capturing -> "لا توجد جلسة صوت"
        lastAudioChunkElapsedMs == 0L -> "بانتظار أول عينة صوت"
        nowElapsedMs - lastAudioChunkElapsedMs > 12_000L -> "تحذير: لم تصل عينات صوت خلال 12 ثانية"
        else -> "مجرى الصوت يعمل"
    }
    @Volatile var capturing: Boolean = false
        private set
    @Volatile var source: String = "غير مشغّل"
        private set
    @Volatile var caption: String = ""
        private set
    @Volatile var title: String? = null
        private set
    @Volatile var provider: String? = null
        private set
    @Volatile var confidence: Double? = null
        private set
    @Volatile var alignment: String = "لا توجد ترجمة خارجية"
        private set
    fun started(captureSource: String) {
        source = captureSource
        lastAudioChunkElapsedMs = 0L
        capturing = true
        title = null
        provider = null
        confidence = null
        alignment = "جارٍ التحليل"
    }
    fun stopped() {
        capturing = false
        lastAudioChunkElapsedMs = 0L
        caption = ""
        source = "غير مشغّل"
        title = null
        provider = null
        confidence = null
        alignment = "لا توجد جلسة تشغيل"
    }
    fun publishCaption(value: String) { caption = value.take(900) }
    fun media(name: String?, sourceName: String?, score: Double?, sync: String) {
        title = name
        provider = sourceName
        confidence = score
        alignment = sync.take(350)
    }
}
