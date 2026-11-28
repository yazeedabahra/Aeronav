package com.yvora.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class YvoraApplication:Application() {
    override fun onCreate() {
        super.onCreate()
        AppLanguage.load(this)
    }
}
internal object AppLanguage {
    @Volatile var arabic=false
        private set
    fun load(context:Context) { arabic=context.getSharedPreferences("app_language",Context.MODE_PRIVATE).getBoolean("arabic",false) }
    fun select(context:Context,value:Boolean):Boolean {
        if(arabic==value) return false
        context.getSharedPreferences("app_language",Context.MODE_PRIVATE).edit().putBoolean("arabic",value).apply()
        arabic=value
        return true
    }
    fun wrap(context:Context):Context {
        val locale=Locale.forLanguageTag(if(arabic) "ar" else "en")
        val config=Configuration(context.resources.configuration)
        config.setLocale(locale);config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
internal fun localized(en:String,ar:String):String=if(AppLanguage.arabic) ar else en
abstract class LocalizedActivity:AppCompatActivity() {
    override fun attachBaseContext(newBase:Context) { super.attachBaseContext(AppLanguage.wrap(newBase)) }
}

// Presentation-only status localization; protocol tokens and state comparisons remain English.
private val statusTranslations=linkedMapOf(
"Swipe: open palm to re-arm" to "السحب: افتح الكف لإعادة التهيئة",
"Knife hand: confirming" to "حافة الكف: جاري التأكيد",
"Motion: " to "الحركة: ",
" stroke completed" to " اكتملت الإيماءة",
" stroke cancelled by Android" to " ألغى Android الإيماءة",
"AI owns input; use Stop AI or a manual pinch to interrupt" to "الذكاء الاصطناعي يتحكم؛ اضغط إيقاف أو اقرص الأصابع للمقاطعة",
"Transform rejected (busy or accessibility unavailable)" to "رُفض التحويل (اللمس مشغول أو إمكانية الوصول غير متاحة)",
"Zoom gesture accepted; open palms to repeat" to "قُبل التكبير؛ افتح الكفين للتكرار",
"Rotate while keeping hand separation steady" to "أدر اليدين مع تثبيت المسافة بينهما",
"Recording motion; release pinch to finish" to "جاري التسجيل؛ افتح الأصابع للإنهاء",
"Rotation accepted; open palms to re-arm" to "قُبل التدوير؛ افتح الكفين لإعادة التهيئة",
"AI paused for screen or thermal safety" to "أُوقف التحكم الذكي لحماية الشاشة أو الحرارة",
"Ambiguous competing actions: abstain" to "أوامر متعارضة ملتبسة: لا تنفيذ",
"Identity ambiguous: input suspended" to "هوية ملتبسة: اللمس متوقف",
"Separate hands; open palm to re-arm" to "أبعد اليدين وافتح الكف لإعادة التهيئة",
"Tracking uncertain; open palm again" to "التتبع غير مؤكد؛ افتح الكف مجددًا",
"Both hands must be tracked reliably" to "يجب تتبع اليدين بشكل موثوق",
"Both hands must be reliably tracked" to "يجب تتبع اليدين بشكل موثوق",
"Rotate the line between your hands" to "أدر الخط الواصل بين يديك",
"Motion too short or small; retry" to "الحركة قصيرة أو صغيرة جدًا؛ أعد المحاولة",
"No unambiguous saved trajectory" to "لا يوجد مسار محفوظ مميز",
"Open palms and separate hands" to "افتح الكفين وأبعد اليدين",
"AI stopped by manual gesture" to "أُوقف التحكم الذكي بإيماءة يدوية",
"Move hands apart or together" to "أبعد اليدين أو قرّبهما",
"Drag interrupted by Android" to "قطع Android السحب المستمر",
"Screen off • Camera paused" to "الشاشة مطفأة • الكاميرا متوقفة",
"Thermal status unavailable" to "حالة الحرارة غير متاحة",
"Hold: waiting for Android" to "ضغط مستمر: بانتظار Android",
"Open both palms to re-arm" to "افتح الكفين لإعادة التهيئة",
"Pinch both hands together" to "اقرص الأصابع في اليدين معًا",
"Cooling • Camera paused" to "تبريد • الكاميرا متوقفة",
"Custom gesture rejected" to "رُفضت الإيماءة المخصصة",
"Motion too long; re-arm" to "الحركة طويلة جدًا؛ أعد التهيئة",
"Ready: pinch both hands" to "جاهز: اقرص الأصابع في اليدين",
"Knife hand: swipe ready" to "حافة الكف: السحب جاهز",
"Provider returned HTTP " to "أعاد المزود HTTP ",
"Open both palms to arm" to "افتح الكفين للاستعداد",
"; open palm and repeat" to "؛ افتح الكف وكرر",
"Ready: pinch and draw" to "جاهز: اقرص الأصابع وارسم",
"Dragging: touch held" to "السحب: اللمس مستمر",
"Yvora engine stopped" to "توقف محرك Yvora",
"Hold both palms open" to "أبقِ الكفين مفتوحين",
"Open palm to re-arm" to "افتح الكف لإعادة التهيئة",
"Custom trajectories" to "مسارات مخصصة",
"LOWER_CAMERA_DETAIL" to "خفض دقة الكاميرا",
"Tracking timed out" to "انتهت مهلة التتبع",
"Identity uncertain" to "الهوية غير مؤكدة",
"Response too large" to "الاستجابة كبيرة جدًا",
"No active capture" to "لا يوجد تسجيل نشط",
"Safe calibration" to "معايرة آمنة",
"Open palm to arm" to "افتح الكف للاستعداد",
"INVESTIGATE_CODE" to "فحص الكود",
"Severe or higher" to "شديدة أو أعلى",
"Analysis target " to "معدل التحليل ",
"Invalid model ID" to "معرّف نموذج غير صالح",
"Motion captured" to "تم تسجيل الحركة",
"Invalid API key" to "مفتاح API غير صالح",
"No command yet" to "لا يوجد أمر بعد",
"Tap dispatched" to "أُرسل النقر",
"Hold palm open" to "أبقِ الكف مفتوحًا",
"AI navigation" to "تنقل ذكي",
"Drag rejected" to "رُفض السحب المستمر",
"KEEP_SETTINGS" to "الإبقاء على الإعدادات",
"Tap rejected" to "رُفض النقر",
"Calibration" to "معايرة",
"SWIPE_RIGHT" to "سحب لليمين",
" dispatched" to " أُرسل",
"SWIPE_LEFT" to "سحب لليسار",
"SWIPE_DOWN" to "سحب لأسفل",
"Right hand" to "اليد اليمنى",
"Thermal: " to "الحرارة: ",
"Fingers: " to "الأصابع: ",
"Left hand" to "اليد اليسرى",
"Captured " to "سُجل ",
" rejected" to " رُفض",
"SWIPE_UP" to "سحب لأعلى",
"Battery " to "البطارية ",
"Moderate" to "متوسطة",
"Assist: " to "المساعدة: ",
"Matched " to "مطابق ",
"Pointer" to "مؤشر",
"Camera " to "الكاميرا ",
"Hands: " to "اليدان: ",
"Armed: " to "جاهز: ",
"Pinch: " to "القرص: ",
"NORMAL" to "عادي",
"ROTATE" to "تدوير",
"CUSTOM" to "مخصص",
        "UNIFIED" to "موحّد",
        "Unified pair" to "إيماءة موحّدة باليدين",
        "Unified motion" to "مسار موحّد",
        "Unified input suspended" to "التحكم الموحّد متوقف",
"Normal" to "طبيعية",
"Edge: " to "الحافة: ",
"Light" to "خفيفة",
"BACK" to "رجوع",
"HOME" to "الرئيسية",
"ZOOM" to "تكبير",
"DRAG" to "سحب مستمر",
"DROP" to "إفلات",
"None" to "لا يوجد",
"Auto" to "تلقائي",
"Idle" to "خامل",
"TAP" to "نقر"
)
internal fun uiStatus(value:String):String {
    if(!AppLanguage.arabic) return value
    var localized=value
    for((key,translation) in statusTranslations) localized=localized.replace(key,translation)
    return localized
}
