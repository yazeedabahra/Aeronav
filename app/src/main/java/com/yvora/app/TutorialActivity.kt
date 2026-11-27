package com.yvora.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.core.content.ContextCompat

/** Bilingual onboarding; practice never dispatches accessibility gestures. */
class TutorialActivity:LocalizedActivity() {
    private var page=0
    private var ownsPractice=false
    private var practiceRequested=false
    private val practice=TutorialPractice()
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var panel:LinearLayout
    private lateinit var body:LinearLayout
    private lateinit var progress:ProgressBar
    private lateinit var counter:TextView
    private lateinit var previous:Button
    private lateinit var next:Button
    private var permissionStatus:TextView?=null
    private var practiceStatus:TextView?=null
    private var practiceView:PracticeView?=null
    private val lastPage=8
    private val tick=object:Runnable{
        override fun run(){
            if(page==1)refreshPermissions()
            if(page==4)refreshPractice()
            handler.postDelayed(this,150L)
        }
    }
    private fun words(en:String,ar:String)=localized(en,ar)
    private fun label(parent:LinearLayout,value:String,size:Float=16f)=TextView(this).apply{
        text=value;textSize=size;setPadding(0,8,0,8);parent.addView(this)
    }
    private fun button(parent:LinearLayout,value:String,action:()->Unit)=Button(this).apply{
        text=value;isAllCaps=false;setOnClickListener{action()};parent.addView(this)
    }
    override fun onCreate(state:Bundle?){
        super.onCreate(state)
        page=(state?.getInt("page") ?: 0).coerceIn(0,lastPage)
        panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(20,16,20,16);setBackgroundColor(ContextCompat.getColor(this@TutorialActivity,R.color.bg_primary))}
        label(panel,"YVORA • "+words("GETTING STARTED","دليل البداية"),20f)
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
        counter=TextView(this).apply{textSize=14f}
        header.addView(counter,LinearLayout.LayoutParams(0,-2,1f))
        button(header,"العربية / English"){
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle(words("App language","لغة التطبيق"))
                .setSingleChoiceItems(arrayOf("English","العربية"),if(AppLanguage.arabic)1 else 0){d,w->
                    d.dismiss();if(AppLanguage.select(this,w==1)){stopPractice();recreate()}
                }.setNegativeButton(words("Cancel","إلغاء"),null).show()
        }
        panel.addView(header)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply{max=lastPage+1}
        panel.addView(progress)
        body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        panel.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        previous=Button(this).apply{text=words("Previous","السابق");isAllCaps=false;setOnClickListener{go(page-1)}}
        next=Button(this).apply{isAllCaps=false;setOnClickListener{if(page==lastPage)finishGuide() else go(page+1)}}
        nav.addView(previous,LinearLayout.LayoutParams(0,-2,1f));nav.addView(next,LinearLayout.LayoutParams(0,-2,1f));panel.addView(nav)
        button(panel,words("Skip / return to app","تخطي / العودة للتطبيق")){finishGuide()}
        setContentView(panel)
        render()
    }
    override fun onSaveInstanceState(out:Bundle){out.putInt("page",page);super.onSaveInstanceState(out)}
    override fun onResume(){
        super.onResume()
        YvoraAccessibilityService.stopAi(words("AI paused for tutorial","أُوقف الذكاء الاصطناعي للدليل"))
        YvoraService.instance?.setCalibrationMode(true)
        refreshPermissions()
        handler.post(tick)
    }
    override fun onPause(){
        handler.removeCallbacks(tick);stopPractice()
        super.onPause()
    }
    @Suppress("DEPRECATION")
    override fun onBackPressed(){if(page>0)go(page-1) else finishGuide()}
    private fun finishGuide(){stopPractice();TutorialPrefs.finish(this);finish()}
    private fun go(target:Int){
        if(page==4)stopPractice()
        page=target.coerceIn(0,lastPage);render()
    }
    private fun title(en:String,ar:String){label(body,words(en,ar),25f)}
    private fun paragraph(en:String,ar:String){label(body,words(en,ar))}
    private fun render(){
        body.removeAllViews();permissionStatus=null;practiceStatus=null;practiceView=null
        counter.text=words("Step ${page+1} of ${lastPage+1}","الخطوة ${page+1} من ${lastPage+1}")
        progress.progress=page+1;previous.isEnabled=page>0
        next.text=if(page==lastPage)words("Finish","إنهاء")else words("Next","التالي")
        when(page){
            0->{
                title("Welcome to Yvora","مرحبًا بك في Yvora")
                paragraph("Control your Android phone with hand movements detected by the camera. Your hand is not touching the screen. Start with normal gestures; advanced modes and AI are optional.","تحكم بهاتف Android بحركات اليد التي ترصدها الكاميرا دون لمس الشاشة. ابدأ بالإيماءات العادية، والأوضاع المتقدمة والذكاء الاصطناعي اختيارية.")
                paragraph("Tracking is local. The camera can run while the engine is active in the background. Stop the engine from the main screen or notification when finished.","يُعالج التتبع محليًا. قد تعمل الكاميرا في الخلفية أثناء تشغيل المحرك. أوقفه من الشاشة الرئيسية أو الإشعار عند الانتهاء.")
                paragraph("This guide can be skipped and reopened any time. A running engine stays in safe calibration during the guide.","يمكن تخطي الدليل وفتحه مجددًا في أي وقت. يبقى المحرك الجاري في المعايرة الآمنة أثناء الدليل.")
            }
            1->{
                title("Permissions","الأذونات")
                paragraph("Enable only what you need. Camera detects hands, overlay draws the cursor, and Accessibility allows Android touch actions. You can revoke them in system settings.","فعّل ما تحتاج إليه فقط. ترصد الكاميرا اليد، ويعرض إذن الظهور فوق التطبيقات المؤشر، وتسمح إمكانية الوصول بتنفيذ اللمس على Android. يمكنك إلغاء الأذونات من إعدادات النظام.")
                permissionStatus=label(body,"")
                button(body,words("Allow camera","السماح بالكاميرا")){requestPermissions(arrayOf(Manifest.permission.CAMERA),101)}
                button(body,words("Open overlay settings","إعدادات العرض فوق التطبيقات")){openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))}
                button(body,words("Open Accessibility settings","إعدادات إمكانية الوصول")){openSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}
                if(Build.VERSION.SDK_INT>=33)button(body,words("Allow notifications (recommended)","السماح بالإشعارات (مستحسن)")){requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),102)}
                refreshPermissions()
            }
            2->{
                title("Camera and calibration","الكاميرا والمعايرة")
                paragraph("Use a well-lit room and keep the hand about 50–80 cm from the front camera. On the main screen start the engine with Safe calibration enabled. The diagram shows hand position without injecting touch.","استخدم إضاءة جيدة وضع اليد على بعد نحو 50–80 سم من الكاميرا الأمامية. شغّل المحرك من الرئيسية مع تفعيل المعايرة الآمنة. يعرض المخطط موضع اليد دون تنفيذ اللمس.")
                paragraph("X is horizontal range and Y is vertical range. Smaller ranges need less hand travel but can increase jitter. Link both hands or select each separately, adjust the centers, or start automatic calibration and review the suggested range.","X هو النطاق الأفقي وY العمودي. النطاق الأصغر يقلل حركة اليد المطلوبة لكنه قد يزيد الاهتزاز. اربط اليدين أو اختر كل يد منفردة واضبط المركز، أو ابدأ المعايرة التلقائية وراجع النطاق المقترح.")
                paragraph("Cyan identifies the left cursor, amber the right. If hands overlap and identity becomes uncertain, input stops; separate them and open the palm again.","السماوي لليد اليسرى والكهرماني لليمنى. عند تقاطع اليدين والتباس الهوية يتوقف اللمس؛ أبعدهما وافتح الكف من جديد.")
            }
            3->{
                title("Basic gestures","الإيماءات الأساسية")
                paragraph("Pointer: open the palm and move the hand. Tap: briefly pinch thumb and index, then release. Drag: pinch and hold until the pointer turns green, move, then release. Android must support continuous touch.","المؤشر: افتح الكف وحرّك اليد. النقر: اقرص الإبهام والسبابة سريعًا ثم افتحهما. السحب المستمر: اقرص حتى يصبح المؤشر أخضر، حرّك اليد ثم افتح الأصابع. يتطلب دعم اللمس المستمر.")
                paragraph("Vertical scroll: right hand index only for down; index and middle for up. Horizontal swipe: extend fingers together and turn the edge of either palm toward the camera, then move sideways. Face the palm forward to re-arm.","التمرير العمودي: السبابة وحدها باليمنى لأسفل، والسبابة والوسطى لأعلى. السحب الأفقي: مد الأصابع متقاربة ووجّه حافة أي كف نحو الكاميرا ثم حرّكها جانبيًا. واجه الكاميرا بالكف لإعادة التهيئة.")
                paragraph("Normal mode supports two independent cursors but only one hand owns the active touch stream. Do not expect two simultaneous drags.","يدعم الوضع العادي مؤشرين مستقلين، لكن يدًا واحدة تملك اللمس النشط. لا تتوقع سحب عنصرين في الوقت نفسه.")
            }
            4->{
                title("Safe live practice","تدريب مباشر آمن")
                paragraph("Practice open palm, movement, pinch and release. The camera sample is displayed only in the training area. No tap, drag or swipe is sent to Android. You can continue without passing.","تدرّب على فتح الكف والحركة والقرص والإفلات. تظهر عينة التتبع داخل مساحة التدريب فقط. لا يُرسل نقر أو سحب إلى Android. يمكنك المتابعة دون اجتياز التدريب.")
                practiceView=PracticeView(this).also{body.addView(it,LinearLayout.LayoutParams(-1,(180*resources.displayMetrics.density).toInt()))}
                practiceStatus=label(body,words("Press Start practice when ready.","اضغط بدء التدريب عندما تكون جاهزًا."))
                button(body,words("Start / retry practice","بدء / إعادة التدريب")){startPractice()}
                button(body,words("Stop practice","إيقاف التدريب")){stopPractice();practiceStatus?.text=words("Practice stopped.","توقف التدريب.")}
                paragraph("Camera and overlay permissions are needed for the existing tracking service. Accessibility is not needed for this exercise. The guide will not activate it automatically.","يحتاج محرك التتبع الحالي إذني الكاميرا والعرض فوق التطبيقات. لا يلزم تفعيل إمكانية الوصول لهذا التدريب. لن يفعّلها الدليل تلقائيًا.")
            }
            5->{
                title("Zoom and rotation","التكبير والتدوير")
                paragraph("Enable dual hands and choose Zoom only or Rotate only on the main screen, then restart the engine. Open both palms, pinch both hands, and move them apart/together to zoom, or rotate the line between them while keeping distance steady.","فعّل اليدين واختر التكبير فقط أو التدوير فقط من الرئيسية ثم أعد تشغيل المحرك. افتح الكفين واقرص الأصابع في اليدين، ثم قرّبهما أو أبعدهما للتكبير، أو أدر الخط بينهما مع تثبيت المسافة للتدوير.")
                paragraph("Each accepted gesture executes one discrete two-contact action around the screen center. Only supporting apps respond. Open both palms to repeat. Switch back to Normal and restart to use ordinary taps and drags.","كل إيماءة مقبولة تنفذ إجراء لمس مزدوجًا متقطعًا حول المركز. تستجيب التطبيقات الداعمة فقط. افتح الكفين للتكرار. عد للوضع العادي وأعد التشغيل للنقر والسحب.")
            }
            6->{
                title("Personal learning and Fuzzy Lab","التعلم الشخصي ومختبر الفزي")
                paragraph("Assisted gestures permit more flexible horizontal swipe poses. In Personal Gesture Studio train one pose per hand for 3–10 seconds, review it and save. Enable Assisted gestures and restart to use it.","تتيح الإيماءات المساعدة وضعيات سحب أفقي أكثر مرونة. درّب وضعية لكل يد في استوديو الإيماءات الشخصية لمدة 3–10 ثوانٍ، وراجعها واحفظها. فعّل الإيماءات المساعدة وأعد التشغيل.")
                paragraph("In Custom Trajectory Studio choose a hand and a swipe command, open palm, pinch, draw a repeatable path, release, and record three examples. Save, choose Custom trajectories, and restart. Other ordinary gestures are disabled in this mode.","في استوديو المسارات المخصصة اختر اليد وأمر السحب، افتح الكف واقرص الأصابع وارسم مسارًا قابلًا للتكرار ثم افتحها. سجل ثلاثة أمثلة واحفظها واختر وضع المسارات المخصصة وأعد التشغيل. تتوقف الإيماءات العادية في هذا الوضع.")
                paragraph("Fuzzy Lab displays memberships and lets you experiment with mathematical rules. Sandbox changes do not change runtime rules or execute actions.","يعرض مختبر الفزي درجات الانتماء ويتيح تجربة القواعد الرياضية. تغييرات المختبر لا تعدل قواعد التشغيل ولا تنفذ أوامر.")
            }
            7->{
                title("AI and local commands","الذكاء الاصطناعي والأوامر المحلية")
                paragraph("Open AI CORE. Try a local command such as swipe right or اسحب يمين. Local previews need no API key. For a cloud provider choose a model and a temporary restricted key; review the text before sending. Provider usage may cost money.","افتح نواة الذكاء الاصطناعي وجرب أمرًا محليًا مثل اسحب يمين. لا تحتاج المعاينة المحلية إلى مفتاح API. للمزود الخارجي اختر النموذج ومفتاحًا مؤقتًا محدودًا وراجع النص قبل إرساله؛ قد تُحتسب تكلفة.")
                paragraph("Preview a plan, approve it, choose the target app and press Run here. Stop AI cancels remaining actions; a dispatched short stroke may finish. Inspect screen locally counts exposed elements only, not text or screenshots.","عاين الخطة ووافق عليها واختر التطبيق المستهدف واضغط تنفيذ هنا. يوقف زر إيقاف الذكاء الاصطناعي الخطوات المتبقية؛ قد تكتمل إيماءة قصيرة سبق إرسالها. الفحص المحلي يحصي العناصر الظاهرة للخدمة فقط دون نصوص أو لقطات شاشة.")
            }
            8->{
                title("Ready to use Yvora","جاهز لاستخدام Yvora")
                paragraph("Return to the main screen. Choose Normal mode first. Stop/restart after changing modes. Start the engine, finish safe calibration, then turn Safe calibration off yourself before trying gestures. Keep a physical way to stop the service.","عد للشاشة الرئيسية واختر الوضع العادي أولًا. أعد التشغيل بعد تغيير الأوضاع. شغّل المحرك وأنهِ المعايرة الآمنة ثم أوقفها بنفسك قبل تجربة الإيماءات. أبقِ وسيلة لمس فعلية لإيقاف الخدمة.")
                paragraph("If tracking is unstable: improve light, increase the range, try one hand, or select fewer false swipes. If the device heats up, stop tracking and let it cool. Do not override thermal protection.","إذا كان التتبع غير مستقر، حسّن الإضاءة ووسّع النطاق وجرب يدًا واحدة أو اختر تقليل السحب الخاطئ. إذا ارتفعت حرارة الهاتف أوقف التتبع ودعه يبرد. لا تتجاوز الحماية الحرارية.")
                paragraph("You can reopen this tutorial from the main screen whenever needed.","يمكنك إعادة فتح الدليل من الشاشة الرئيسية في أي وقت.")
            }
        }
    }
    private fun hasCamera()=ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)==PackageManager.PERMISSION_GRANTED
    private fun refreshPermissions(){
        permissionStatus?.text=listOf(
            words("Camera","الكاميرا") to hasCamera(),
            words("Overlay","العرض فوق التطبيقات") to Settings.canDrawOverlays(this),
            words("Accessibility connected","إمكانية الوصول متصلة") to YvoraAccessibilityService.isEnabled()
        ).joinToString("\n"){(name,ok)->name+": "+if(ok)words("Ready","جاهز")else words("Not enabled","غير مفعّل")}
    }
    private fun openSettings(intent:Intent){
        try{startActivity(intent)}catch(_:RuntimeException){Toast.makeText(this,words("Open the permission in system settings.","افتح الإذن من إعدادات النظام."),Toast.LENGTH_LONG).show()}
    }
    private fun startPractice(){
        if(!hasCamera() || !Settings.canDrawOverlays(this)){
            practiceStatus?.text=words("Enable camera and overlay in step 2 first.","فعّل الكاميرا والعرض فوق التطبيقات في الخطوة الثانية أولًا.");return
        }
        YvoraAccessibilityService.stopAi()
        practice.reset();practiceRequested=true
        val running=YvoraService.instance
        if(running!=null){running.setCalibrationMode(true);running.forceHidePointer();return}
        ownsPractice=true
        val intent=Intent(this,YvoraService::class.java).apply{action=YvoraService.ACTION_START;putExtra(YvoraService.EXTRA_CALIBRATION,true)}
        try{if(Build.VERSION.SDK_INT>=26)startForegroundService(intent)else startService(intent)}
        catch(_:RuntimeException){ownsPractice=false;practiceRequested=false;practiceStatus?.text=words("Could not start camera service.","تعذر تشغيل خدمة الكاميرا.")}
    }
    private fun stopPractice(){
        practiceRequested=false;practice.reset()
        if(ownsPractice)stopService(Intent(this,YvoraService::class.java))
        ownsPractice=false
        YvoraService.instance?.setCalibrationMode(true)
        practiceView?.sample=null;practiceView?.invalidate()
    }
    private fun refreshPractice(){
        if(!practiceRequested)return
        val e=YvoraService.instance
        if(e==null){practiceStatus?.text=words("Starting tracking…","جاري تشغيل التتبع…");return}
        if(!e.isCalibrating){e.setCalibrationMode(true);practice.reset()}
        if(e.pauseReason!=null){practice.reset();practiceView?.sample=null;practiceView?.invalidate();practiceStatus?.text=uiStatus(e.pauseReason!!);return}
        val left=e.tutorialSample(0);val right=e.tutorialSample(1)
        practiceView?.sample=left ?: right;practiceView?.invalidate()
        val stage=practice.observe(left,right,SystemClock.uptimeMillis())
        practiceStatus?.text=when(stage){
            PracticeStage.OPEN->words("1/4 Open one palm toward camera.","1/4 افتح كفًا باتجاه الكاميرا.")
            PracticeStage.MOVE->words("2/4 Move the open palm sideways or up/down.","2/4 حرّك الكف المفتوح أفقيًا أو عموديًا.")
            PracticeStage.PINCH->words("3/4 Pinch thumb and index.","3/4 اقرص الإبهام والسبابة.")
            PracticeStage.RELEASE->words("4/4 Release the pinch.","4/4 افتح الأصابع.")
            PracticeStage.DONE->words("Practice complete! Tracking detected your movement and pinch/release. No touch was executed.","اكتمل التدريب! رُصدت حركة اليد والقرص والإفلات دون تنفيذ لمس.")
        }
        if(stage==PracticeStage.DONE){
            practiceRequested=false
            if(ownsPractice){stopService(Intent(this,YvoraService::class.java));ownsPractice=false}
        }
    }
    private class PracticeView(c:android.content.Context):View(c){
        var sample:PracticeSample?=null
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas:Canvas){
            super.onDraw(canvas)
            paint.color=Color.rgb(20,30,42);canvas.drawRoundRect(0f,0f,width.toFloat(),height.toFloat(),16f,16f,paint)
            paint.color=Color.rgb(75,100,120);paint.strokeWidth=2f
            canvas.drawLine(width/2f,12f,width/2f,height-12f,paint);canvas.drawLine(12f,height/2f,width-12f,height/2f,paint)
            sample?.let{val x=it.x.coerceIn(0f,1f)*(width-32)+16f;val y=it.y.coerceIn(0f,1f)*(height-32)+16f
                paint.color=if(it.pinch<.23f)Color.GREEN else Color.CYAN
                canvas.drawCircle(x,y,12f,paint)
            }
        }
    }
}
