# مقارنة قدرات AI-Translator APK المرجعي مقابل IRAQ-TRANSLATE 1.2.0

| قدرة مرجعية ظهرت في تقرير الهندسة العكسية | حالة 1.2.0 | مستوى الإثبات |
|---|---|---|
| ControlHub / ConnectionRow / PreviewCard | لوحة عربية وحالة اتصال ومعاينة | موجود بالكود؛ لم يُختبر على TV |
| PairingPanel وADB mDNS | PairingPanelActivity + AdbBridge | موجود بالكود؛ نجاح الاقتران مرتبط بجهازك |
| GlassSubtitle وSubtitleOverlay | GlassSubtitle + OverlayController | إعادة تنفيذ وظيفية، ليست نسخة Compose مطابقة |
| TimingPanel • ±timing | توقيت SRT مخزن ومطبق داخل cueAt | اختبار وحدة ناجح |
| TimingPanel • Re-sync next words | ACTION_RESYNC_SPEECH يحذف anchors لا SRT | مربوط بالكود؛ اختباره على الفيلم مطلوب |
| IMDb Suggestions / اختيار الفيلم | نتائج IMDb تحفظ imdbId ثم تحميل SRT | مربوط بالكود؛ خدمة IMDb غير مختبرة حياً |
| DvbEpg / EPG_ONLINE | استيراد XMLTV محلي/HTTPS واختيار قناة/البرنامج الحالي | XMLTV محلي يعمل؛ لا تحكم بموالف DVB |
| ChannelPlayer | لا يوجد player خاص | غير منفّذ؛ المصدر يعتمد على مشغّل خارجي |
| البحث عن SRT: Wyzie/OpenSubtitles/SubDL/SubtitleCat | موجود في المصدر ومستخدم للمحتوى المختار | بعض endpoints غير موثقة/مجرّبة |
| SubSource /subtitles | مسارات API الرسمية مع X-API-Key | تجريبي؛ parsing الفعلي غير مؤكد |
| مزامنة العربية مع الحوار | SubtitleAlignmentEngine مع منع عرض SRT قبل anchor | محرك واجتياز اختبارات وحدة؛ لا اختبار end-to-end |
| توقيت mediaSession/PTS | ADB dumpsys MediaSession + PTS callback نظري | مشروط بمشغّل/دعم جهاز، وPTS ليس سحرياً |
| Stop / boot / key / HeartbeatReceiver | stop / BootReceiver تذكير / hotkey / PCM health | جزئي؛ لا تشغيل تلقائي غير مسموح |
| TranslatorService / OfflineTranslationService | CaptureService + TranslationEngine السحابي | غير مكافئ offline |
| ActivationPanel / UpdatePanel | شاشة معلوماتية فقط | لا backend ترخيص/تحديث منسوخ |
| DiSEqC, USALS, Motor, Blind scan | غير موجودة | لم تظهر أدلة موثوقة بأنها في APK المرجعي |

**قاعدة المقارنة:** بيانات APK المموّهة والأسماء المستنتجة من DEX ليست دليلاً على التنفيذ الدقيق أو الأداء. القيم في العمود الثاني تصف السورس 1.2.0 فقط.
