# بناء ملف APK — IRAQ-TRANSLATE 1.2.0

> **الحالة:** إعداد البناء فقط. لم يُنتج APK في بيئة إعداد هذه الحزمة لأنها لا تحتوي Android SDK/Gradle ولا تستطيع تنزيلهما. لا يُعد نجاح اختبارات Kotlin المحلية دليلاً على نجاح بناء أندرويد أو على عمل الالتقاط على التلفاز.

## الطريق الأسهل: GitHub Actions

1. أنشئ مستودع GitHub **خاصاً** (Private) لحماية كودك إذا رغبت.
2. ارفع **محتويات هذا المجلد** إلى جذر المستودع، مع إبقاء `.github/workflows/build-debug-apk.yml`.
3. افتح تبويب **Actions**، ثم اختر **Build IRAQ-TRANSLATE Debug APK**، واضغط **Run workflow**. يبدأ البناء أيضاً عند رفع الكود إلى الفرع `main` أو `master`.
4. إذا نجح البناء، افتح التشغيل المكتمل ونزّل ملف **IRAQ_TRANSLATE_1.2.0-debug** من قسم **Artifacts**. داخله ملف `IRAQ_TRANSLATE_1.2.0-debug.apk` وبصمة SHA-256.
5. إذا فشل، راجع سجل **Compile Android debug APK** لمعرفة أخطاء Gradle أو التبعيات أو Kotlin؛ **لم نتحقق مسبقاً من أن التجميع الكامل ينجح**.

## البناء محلياً على Android Studio

- ثبّت JDK 17 وAndroid SDK 35 / Build Tools 35.0.0 وGradle 8.10.2.
- افتح مجلد المشروع في Android Studio واسمح بمزامنة Gradle وتنزيل التبعيات.
- نفّذ `gradle :app:assembleDebug` من جذر المشروع، أو **Build > Build APK(s)** من Android Studio.
- الموقع الافتراضي: `app/build/outputs/apk/debug/app-debug.apk`.
- نظام Linux/macOS: `bash scripts/build_debug_apk.sh`.
- نظام Windows PowerShell: `powershell -ExecutionPolicy Bypass -File scripts/build_debug_apk.ps1`.

## شروط معروفة

- يحتاج البناء إلى وصول فعلي لخوادم **Google Maven** و**Maven Central** و**JitPack** و**Hugging Face**. ينزّل `sherpa-onnx...aar` عبر Gradle ويتحقق من SHA-256 المحددة في `app/build.gradle.kts`.
- أي فشل في التنزيل أو التحقق من SHA سيمنع البناء حتى يُحل من مصدر موثوق.
- هذا **Debug APK** موقَّع تلقائياً بمفتاح تصحيح؛ ليس إصدار متجر، ولا يُضمن أنه سيعمل على كل Android TV أو جهاز استقبال.
- في حال نجاح البناء قد تحتاج منح MediaProjection/Overlay وADB وغيرها من الأذونات يدوياً على الجهاز، كما أن محرك التقاط الصوت والبحث عن الترجمات لم يختبر تكاملياً على جهاز حقيقي.
- **لا** تنسخ ملف APK المرجعي الأصلي على أنه نتيجة البناء. الاسم والحزمة هنا يخصّان تنفيذ IRAQ-TRANSLATE المستقل.
