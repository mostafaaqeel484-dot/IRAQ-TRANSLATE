# IRAQ TRANSLATE — سورس 1.1.0 المبني على نتائج الهندسة العكسية

## الهدف
إعادة تنفيذ طبقة الواجهة والتحكم المستدل عليها في `AI-Translator(2).apk` (وليس نسخ التطبيق الأصلي أو الكود المموّه)، بالاعتماد على سورس IRAQ-TRANSLATE 1.0.9. تحافظ هذه النسخة على محرك الالتقاط والتعرف والترجمة والمزامنة السابق.

**الحزمة:** `com.iraqtranslate` — **الإصدار:** 1.1.0 — **Minimum SDK:** 29 — **Target SDK:** 35.

## ما أُضيف فعلياً

| المكوّن المرجعي | إعادة التنفيذ | رابط الوظيفة |
|---|---|---|
| ControlHub | `ControlHubActivity.kt` | لوحة عربية RTL، أزرار تشغيل التقاط الصوت وإيقافه، الاتصال والحالة والمعاينة |
| ChoicePanel | `ChoicePanelActivity.kt` | تفضيلات اللغة ونماذج Vosk الحقيقية التي يقرأها CaptureService |
| PairingPanel / ConnectionRow | `PairingPanelActivity.kt` | اكتشاف mDNS لـ `_adb-tls-pairing._tcp` و`_adb-tls-connect._tcp`، إقران TLS وربط ADB باستخدام AdbBridge القائم |
| GlassSubtitle / SubtitleOverlay | `GlassSubtitle.kt` + تعديل `OverlayController.kt` | نص عربي مع حدود/ظل، خلفية شبه شفافة، حجم وموضع قابلان للتغيير، نافذة overlay غير تفاعلية |
| PreviewCard | `SubtitleStyleActivity.kt` | معاينة مباشرة للخط والخلفية والحجم |
| TimingPanel | `TimingPanelActivity.kt` + `SubtitleTimingMath/Store.kt` | ±0.5 و±1 ثانية، حتى ±15 ثانية؛ يطبّق داخل AutomaticSubtitleManager.cueAt |
| NowPlayingCard / SyncBadge | `RuntimeStatus.kt` + `ControlHubActivity.kt` | يعرض الاسم والمصدر والمزامنة فقط عند وجود بيانات فعلية من manager |
| SearchActivity | `SearchActivity.kt` + `ManualSubtitleRepository.kt` | بحث عناوين عبر IMDb واقتناء SRT من المستخدم؛ تفعيل SRT المحلي في الجلسة التالية |
| BootReceiver | `BootReceiver.kt` | تذكير اختياري فقط بعد إقلاع الجهاز، **لا** يبدأ التقاط الصوت بالخلفية |
| ActivationPanel / UpdatePanel | `AppInfoPanelActivity.kt` | معلومات صريحة عن الحالة. **لا توجد منظومة تفعيل أو تحديث خادم** |

## مسار التشغيل

1. افتح ControlHub. من «اختيار لغة الصوت» حدد اللغة، أو فعّل كشف Sherpa-ONNX التلقائي.
2. نزّل النموذج من «الإعدادات المتقدمة» عند الحاجة. ملفات نماذج التعرف ليست مضمّنة في ZIP.
3. لمنفذ ADB: فعّل Wireless debugging في التلفاز، واستخدم شاشة الإقران. اكتشاف mDNS يعمل عندما تدعم الشبكة نشر الخدمات؛ يمكن كتابة IP/ports يدوياً.
4. خيار `ADB Echo Reference` في هذا الكود مقيّد حالياً بـ Android 14+، ويحتاج صلاحيات/أجهزة تدعم مسار الصوت هذا. بديله «Android playback capture» يتطلب موافقة النظام، وقد تمنع التطبيقات الأخرى التقاط صوتها.
5. فعّل سماح الظهور فوق التطبيقات. اختر تصميم الترجمة من الشاشة المخصصة.
6. لتقديم/تأخير **ملف SRT** افتح توقيت الترجمة. القيمة الموجبة تؤخر النص، والسالبة تقدمه.
7. يمكن اختيار ملف SRT محلي من البحث. سيُقرأ تلقائياً عند بدء جلسة التقاط جديدة، ويلغي البحث التلقائي عن ملف خارجي مؤقتاً؛ إلغاء الاختيار يعيد البحث التلقائي في الجلسة التالية.

## ما لم نثبته أو ننفذه

- **لم تُستخرج** الأجسام الأصلية لملفات Kotlin من APK المُموّه؛ المظهر إعادة تصميم وظيفية وليس نسخة pixel-perfect من Jetpack Compose.
- `DvbEpg`, `ChannelPlayer`, DiSEqC, USALS, Motor وBlind Scan`: لا يوجد API موالف DVB مُوثّق في المواد المتاحة. لم تُضف وظائف وهمية.
- `HeartbeatReceiver`, `CommandReceiver`, `TranslatorService` كخدمة مسجلة منفصلة: لا نقدم ادعاء التكافؤ. `CaptureService` هو الخدمة الفعلية الموجودة في المشروع. لم نضف تشغيل تلقائي للخدمات بالخلفية لأنه يحتاج تصميم قيود Android الحديثة واختبار جهاز.
- التفعيل والتحديث: لا نسخ لمفاتيح أو نقاط نهاية خوادم التطبيق المرجعي، ولا نزعم وجود خادم يعمل للمشروع.
- الترجمة الحية نفسها تستخدم خدمة Google عبر الإنترنت في `TranslationEngine.kt` الأصلية؛ **ليست ترجمة دون إنترنت كاملة**.
- البحث في IMDb يستخدم endpoint عام غير رسمي؛ قد تتغير بنيته. البحث عن الترجمات عبر Wyzie/OpenSubtitles/SubDL/SubtitleCat ما زال ضمن `AutomaticSubtitleManager.kt` الأصلي وبحسب المفاتيح والمتطلبات الفعلية.
- لا يوجد تكامل مضمون لـPTS من مشغّل خارجي دون callback من decoder/Media3، والتعرف التلقائي على العمل قد لا ينجح مع كل بث.

## البناء

1. افتح مجلد `IRAQ_TRANSLATE` في Android Studio بإصدار يدعم Android Gradle Plugin 8.7.3 وKotlin Gradle Plugin 2.0.21.
2. جهّز JDK 17 وAndroid SDK 35، واتصال إنترنت للـdependencies ونموذج Sherpa-ONNX AAR. عند البناء يتحقق Gradle من SHA-256 الخاص بالـAAR قبل استخدامه. لم تتم إضافة مفاتيح API خاصة أو نسخ ملفات من APK الأصلي.
3. `Build > Make Project` ثم `Build APK(s)`. عند استخدام ADB Echo Reference تأكد من تثبيت التطبيق على الجهاز الذي يحوي مسار النظام المطلوب.
4. اختبر على جهاز Android TV/رسيفر فعلي: موافقة MediaProjection، إذن الصوت، overlay، اكتشاف ADB، خدمات الشبكة، تغير تطبيق IPTV، إيقاف/بدء جلسات الالتقاط، ثم توقيت SRT عند pause/seek.

**مهم:** بيئة إعداد هذا السورس لا تحتوي على Android SDK ولا Gradle القابل للتشغيل. نجحت اختبارات Kotlin المستقلة الخمسة في `qa/`، لكن لم يُبنَ APK في هذه البيئة، ولم تختبر الوظائف على جهاز تلفاز؛ لذلك يجب إصلاح أي أخطاء تظهر في Android Studio واختبار التكامل قبل التوزيع.

## اختبار وحدات Kotlin الخمسة

```bash
kotlinc app/src/main/java/com/iraqtranslate/{ArabicTextNormalizer,SubtitleCandidateScorer,SubtitleAlignmentEngine,AudioFingerprintEngine,ReferenceAudioFingerprintMatcher,VideoPtsClock,MediaSessionPositionParser,SubtitleTimingMath}.kt qa/{MediaSessionParserSmoke,SubtitleCandidateScorerSmoke,AdvancedSyncSmoke,AlignmentEngineSmoke,TimingMathSmoke}.kt -include-runtime -d /tmp/iraq_smoke.jar
for t in MediaSessionParserSmoke SubtitleCandidateScorerSmoke AdvancedSyncSmoke AlignmentEngineSmoke TimingMathSmoke; do java -cp /tmp/iraq_smoke.jar "${t}Kt"; done
```
