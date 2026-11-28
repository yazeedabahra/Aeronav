package com.yvora.app

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

/** Provider output stays a preview until separately approved and started on the target screen. */
class AiLabActivity : LocalizedActivity() {
    private val executor=Executors.newSingleThreadExecutor()
    private var generation=0
    private var pending=false
    private var pausedService: YvoraService?=null
    private var wasCalibrating=false
    private lateinit var keyField:EditText
    private lateinit var result:TextView
    private lateinit var submit:Button
    private lateinit var diagnose:Button
    private lateinit var applyFix:Button
    private var proposed:RepairProposal?=null
    private var plan:List<AiAction>?=null
    private var planAt=0L
    private lateinit var execute:Button
    private lateinit var privacyStatus:TextView
    private var foreground=false
    private fun syncScreenshotProtection() {
        if(!::keyField.isInitialized) return
        val protect=AiCapturePolicy.protect(foreground,keyField.text.isNotEmpty(),keyField.hasFocus())
        if(protect) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if(::privacyStatus.isInitialized) privacyStatus.text=if(protect) localized("Capture protected • clear key and leave field to capture","التصوير محمي • امسح المفتاح واخرج من الحقل للتصوير")
            else localized("Screenshots allowed • key field empty","التصوير مسموح • حقل المفتاح فارغ")
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
        fun color(id:Int)=ContextCompat.getColor(this,id)
        val bg=color(R.color.bg_primary)
        val cardColor=color(R.color.bg_card)
        val ink=color(R.color.text_primary)
        val muted=color(R.color.text_secondary)
        val accent=color(R.color.accent)
        fun rounded(fill:Int,radius:Int=18,stroke:Int?=null)=GradientDrawable().apply{
            setColor(fill);cornerRadius=dp(radius).toFloat()
            if(stroke!=null)setStroke(dp(1),stroke)
        }
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(12),dp(18),dp(24))
            isFocusableInTouchMode=true
        }
        fun line(parent:LinearLayout,value:String,size:Float=14f,tone:Int=ink,bold:Boolean=false)=TextView(this).apply{
            text=value;textSize=size;setTextColor(tone)
            if(bold)setTypeface(typeface,Typeface.BOLD)
            setLineSpacing(dp(3).toFloat(),1f)
            parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(8)})
        }
        fun card(parent:LinearLayout=panel):LinearLayout=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            background=rounded(cardColor,22,color(R.color.divider))
            setPadding(dp(16),dp(16),dp(16),dp(16))
            parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
        }
        fun section(parent:LinearLayout,number:String,title:String,subtitle:String){
            line(parent,number,11f,accent,true)
            line(parent,title,20f,ink,true)
            line(parent,subtitle,13f,muted)
        }
        fun action(parent:LinearLayout,label:String,primary:Boolean=false,enabled:Boolean=true):MaterialButton=MaterialButton(this).apply{
            text=label;isAllCaps=false;textSize=14f;isEnabled=enabled
            setCornerRadius(dp(16));minHeight=dp(48)
            setPaddingRelative(dp(12),dp(10),dp(12),dp(10))
            val states=arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf())
            backgroundTintList=ColorStateList(states,intArrayOf(color(R.color.bg_card_light),if(primary)accent else color(R.color.bg_card_light)))
            setTextColor(ColorStateList(states,intArrayOf(color(R.color.text_dim),if(primary)bg else ink)))
            strokeColor=ColorStateList.valueOf(if(primary)accent else color(R.color.divider))
            strokeWidth=dp(1)
            parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        }
        fun edit(parent:LinearLayout,label:String,hintText:String,secret:Boolean=false,multi:Boolean=false)=EditText(this).apply{
            line(parent,label,13f,muted,true)
            hint=hintText;setTextColor(ink);setHintTextColor(color(R.color.text_dim));textSize=15f
            background=rounded(bg,14,color(R.color.divider))
            setPadding(dp(14),dp(12),dp(14),dp(12));isSaveEnabled=false
            inputType=when{
                secret->InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multi->InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else->InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
            if(secret){
                imeOptions=EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                if(Build.VERSION.SDK_INT>=26)importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            if(multi){minLines=3;maxLines=6;gravity=Gravity.TOP or Gravity.START}else setSingleLine(true)
            if(secret) transformationMethod=android.text.method.PasswordTransformationMethod.getInstance()
            parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
        }
        val header=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            background=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(color(R.color.blue_bg),cardColor)).apply{cornerRadius=dp(24).toFloat()}
            setPadding(dp(20),dp(18),dp(20),dp(18))
            panel.addView(this,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(14)})
        }
        line(header,"YVORA  /  INTELLIGENCE",11f,accent,true)
        line(header,localized("AI Control","التحكم الذكي"),30f,ink,true)
        line(header,localized("Plan locally or connect your preferred model. You decide when to run.","خطط محليًا أو اربط النموذج المفضل لديك. أنت تحدد متى يتم التنفيذ."),14f,muted)
        privacyStatus=line(header,localized("Screenshots allowed • key field empty","التصوير مسموح • حقل المفتاح فارغ"),12f,accent)
        action(header,localized("Back to controls","العودة للتحكم")).setOnClickListener{finish()}
        val composer=card()
        section(composer,"01  /  LOCAL",localized("What should Yvora do?","ماذا تريد من Yvora أن يفعل؟"),localized("Start with local commands. No account or API key needed.","ابدأ بالأوامر المحلية، دون حساب أو مفتاح API."))
        val prompt=edit(composer,localized("Your command","أمرك"),localized("e.g. swipe right, then go back","مثل: اسحب يمين ثم ارجع"),multi=true)
        val examples=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;composer.addView(this)}
        val sample1=action(examples,localized("Swipe right","اسحب يمين"))
        val sample2=action(examples,localized("Go back","ارجع"))
        for(b in listOf(sample1,sample2)) b.layoutParams=LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=dp(4)}
        sample1.setOnClickListener{prompt.setText(localized("swipe right","اسحب يمين"))}
        sample2.setOnClickListener{prompt.setText(localized("go back","ارجع"))}
        val local=action(composer,localized("Preview locally","معاينة محلية"),true)
        val cloud=card()
        section(cloud,"02  /  CLOUD",localized("Connect a model","ربط نموذج"),localized("Optional • send only reviewed requests to the selected provider.","اختياري • أرسل الطلبات التي راجعتها فقط إلى المزود المحدد."))
        val reveal=action(cloud,localized("Show connection settings","عرض إعدادات الربط"))
        val cloudBody=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;visibility=View.GONE;cloud.addView(this)}
        fun showConnection(show:Boolean){
            if(!show){keyField.clearFocus();panel.requestFocus()}
            cloudBody.visibility=if(show)View.VISIBLE else View.GONE
            reveal.text=if(show)localized("Hide connection settings","إخفاء إعدادات الربط")else localized("Show connection settings","عرض إعدادات الربط")
            syncScreenshotProtection()
        }
        val providers=AiProvider.values()
        line(cloudBody,localized("Provider","المزود"),13f,muted,true)
        val spinner=Spinner(this).apply{
            adapter=object:ArrayAdapter<String>(this@AiLabActivity,android.R.layout.simple_spinner_dropdown_item,providers.map{it.title}){
                override fun getView(position:Int,convertView:View?,parent:android.view.ViewGroup):View=
                    super.getView(position,convertView,parent).also{(it as? TextView)?.setTextColor(ink)}
                override fun getDropDownView(position:Int,convertView:View?,parent:android.view.ViewGroup):View=
                    super.getDropDownView(position,convertView,parent).also{it.setBackgroundColor(cardColor);(it as? TextView)?.setTextColor(ink)}
            }
            background=rounded(bg,14,color(R.color.divider))
            setPadding(dp(12),dp(4),dp(12),dp(4))
            cloudBody.addView(this,LinearLayout.LayoutParams(-1,dp(52)).apply{bottomMargin=dp(12)})
        }
        val model=edit(cloudBody,localized("Model ID","معرّف النموذج"),localized("From provider dashboard","من لوحة المزود"))
        keyField=edit(cloudBody,localized("Temporary API key","مفتاح API مؤقت"),localized("Restricted test key","مفتاح تجريبي محدود"),secret=true)
        line(cloudBody,localized("Keys are masked, never saved and cleared after sending. Screenshots are blocked while the key is present or being edited.","المفاتيح مخفية ولا تُحفظ وتُمسح بعد الإرسال. يُمنع التصوير أثناء وجود المفتاح أو تحريره."),12f,muted)
        keyField.setOnFocusChangeListener{_,_->syncScreenshotProtection()}
        keyField.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){syncScreenshotProtection()}
            override fun afterTextChanged(s:Editable?){}
        })
        reveal.setOnClickListener{showConnection(cloudBody.visibility!=View.VISIBLE)}
        submit=action(cloudBody,localized("Ask selected AI provider","اسأل المزود المحدد"),true)
        val clear=action(cloud,localized("Clear key and cancel preview","مسح المفتاح وإلغاء المعاينة"))
        val review=card()
        section(review,"03  /  REVIEW",localized("Plan & response","الخطة والاستجابة"),localized("Nothing runs until you approve the plan and press Run here on your target screen.","لا يُنفّذ شيء حتى تعتمد الخطة وتضغط تنفيذ هنا على الشاشة المستهدفة."))
        result=line(review,localized("Ready. Enter a command or review diagnostics.","جاهز. أدخل أمرًا أو راجع التشخيص."),15f)
        result.background=rounded(bg,14,color(R.color.divider))
        result.setPadding(dp(14),dp(14),dp(14),dp(14))
        result.minHeight=dp(92)
        result.setTextIsSelectable(true)
        result.accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        execute=action(review,localized("Prepare approved actions","تهيئة الأوامر المعتمدة"),true,false)
        val diagnostics=card()
        section(diagnostics,"04  /  DIAGNOSTICS",localized("Engine health","صحة المحرك"),localized("Review a local performance report before asking your model for suggestions.","راجع تقرير الأداء المحلي قبل طلب اقتراحات من النموذج."))
        diagnose=action(diagnostics,localized("Review diagnostic report","مراجعة تقرير التشخيص"))
        applyFix=action(diagnostics,localized("Apply lower camera detail","تطبيق خفض دقة الكاميرا"),false,false)
        val privacy=card()
        line(privacy,localized("Privacy & safety","الخصوصية والأمان"),17f,ink,true)
        line(privacy,localized("Only the command you type, or a diagnostic report you explicitly review and approve, is sent to your selected provider. No camera images, screen text, raw logs or hand coordinates are sent. Use a restricted test API key; do not use a production key. Keys are not saved. API usage may cost money. Production distribution requires a secure backend.","يُرسل فقط الأمر الذي تكتبه أو تقرير التشخيص الذي تراجعه وتوافق عليه. لا تُرسل صور الكاميرا أو نص الشاشة أو السجلات الخام أو إحداثيات اليد. استخدم مفتاح API تجريبيًا محدود الصلاحيات، لا مفتاح الإنتاج. لا تُحفظ المفاتيح وقد تُحتسب تكلفة. التوزيع الإنتاجي يحتاج خادمًا آمنًا."),13f,muted)
        val scroll=ScrollView(this).apply{setBackgroundColor(bg);isFillViewport=true;addView(panel)}
        ViewCompat.setOnApplyWindowInsetsListener(scroll){v,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom)
            insets
        }
        setContentView(scroll)
        panel.requestFocus()
        ViewCompat.requestApplyInsets(scroll)
        result.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                if(foreground) scroll.post { if(!isDestroyed && foreground) scroll.smoothScrollTo(0,review.top) }
            }
            override fun afterTextChanged(s:Editable?){}
        })
        syncScreenshotProtection()
        clear.setOnClickListener {
            generation++
            keyField.text.clear();keyField.clearFocus();panel.requestFocus();syncScreenshotProtection()
            result.text=localized("Cleared. Any already-sent provider request may finish remotely.","تم المسح. قد يستكمل المزود الطلب الذي سبق إرساله.")
            submit.isEnabled=!pending
            diagnose.isEnabled=!pending
            proposed=null
            applyFix.isEnabled=false
            clearPlan()
            YvoraAccessibilityService.stopAi()
        }
        local.setOnClickListener {
            if(pending) return@setOnClickListener
            clearPlan();proposed=null;applyFix.isEnabled=false
            val route=FuzzyAiRouter.route(prompt.text.toString())
            val metrics=String.format(java.util.Locale.ROOT,localized("Local coverage %.2f | lexical similarity %.2f | local membership %.2f","التغطية المحلية %.2f | التشابه اللفظي %.2f | الانتماء المحلي %.2f"),
                route.coverage,route.similarity,route.localDegree)
            val actions=route.actions
            if(actions!=null) {
                plan=actions;planAt=android.os.SystemClock.uptimeMillis();execute.isEnabled=true
                result.text=localized("LOCAL • no API request\n","محلي • لا يوجد طلب API\n")+metrics+"\n"+actions.joinToString("\n"){uiStatus(it.name)}+localized("\nReview before execution.","\nراجع قبل التنفيذ.")
            } else result.text=localized("No exact safe local plan. No request sent.\n","لا توجد خطة محلية آمنة ومطابقة. لم يُرسل طلب.\n")+metrics+
                localized("\nEdit the command or explicitly choose Ask selected AI provider. Similarity alone never authorizes a command.","\nعدّل الأمر أو اختر سؤال المزود صراحةً. التشابه وحده لا يسمح بتنفيذ الأمر.")
        }
        submit.setOnClickListener {
            if(pending) return@setOnClickListener
            val provider=providers[spinner.selectedItemPosition]
            val modelId=model.text.toString().trim()
            val key=keyField.text.toString().trim()
            val command=prompt.text.toString().trim()
            if(modelId.isEmpty() || key.isEmpty() || command.isEmpty()) {
                result.text=localized("Enter model ID, API key, and command.","أدخل معرّف النموذج ومفتاح API والأمر.")
                return@setOnClickListener
            }
            clearPlan();proposed=null;applyFix.isEnabled=false
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(localized("Send this command to ${provider.title}?","إرسال هذا الأمر إلى ${provider.title}؟"))
                .setMessage(command+localized("\n\nOnly this command is sent. API usage may cost money.","\n\nسيُرسل هذا الأمر فقط. قد تُحتسب تكلفة API."))
                .setNegativeButton(localized("Cancel","إلغاء"),null)
                .setPositiveButton(localized("Send","إرسال")) { _,_ ->
            if(pending) return@setPositiveButton
            val ticket=++generation
            pending=true
            submit.isEnabled=false
            diagnose.isEnabled=false
            local.isEnabled=false
            keyField.text.clear();keyField.clearFocus();panel.requestFocus();syncScreenshotProtection()
            result.text=localized("Requesting preview from ${provider.title}…","طلب معاينة من ${provider.title}…")
            executor.execute {
                var actions:List<AiAction>?=null
                val message=try {
                    val response=AiProviderClient.plan(provider,modelId,key,command)
                    actions=AiCommandGate.parse(response)
                    if(actions==null) localized("Unsupported or invalid response. No actions executed.","استجابة غير مدعومة أو غير صالحة. لم تُنفّذ أي أوامر.")
                    else localized("Validated plan:\n","الخطة المقبولة:\n")+actions!!.joinToString("\n") { uiStatus(it.name) }+localized("\nReview before execution.","\nراجع قبل التنفيذ.")
                } catch(e:AiNetworkException) { e.message?.let(::uiStatus) ?: localized("Provider rejected request.","رفض المزود الطلب.") }
                catch(e:IllegalArgumentException) { localized("Invalid configuration or command.","إعدادات أو أمر غير صالح.") }
                catch(e:Exception) { localized("Connection or response error. Check model ID, key, and internet.","خطأ في الاتصال أو الاستجابة. تحقق من معرّف النموذج والمفتاح والإنترنت.") }
                runOnUiThread {
                    pending=false
                    if(!isDestroyed) {
                        if(ticket==generation) {
                            result.text=message
                            val valid=actions?.takeIf { AiCommandGate.executable(it) }
                            plan=valid;planAt=android.os.SystemClock.uptimeMillis()
                            execute.isEnabled=valid!=null
                            if(actions!=null && valid==null) result.append(localized("\nExecution blocked: BACK/HOME must be last.","\nتم منع التنفيذ: يجب أن يكون الرجوع أو الرئيسية آخر أمر."))
                        }
                        submit.isEnabled=true
                        diagnose.isEnabled=true
                        local.isEnabled=true
                    }
                }
            }
                }.show()
        }
        execute.setOnClickListener {
            val approved=plan ?: return@setOnClickListener
            if(pending || android.os.SystemClock.uptimeMillis()-planAt>120000L) {
                clearPlan();result.text=localized("Plan expired. Request a new preview.","انتهت صلاحية الخطة. اطلب معاينة جديدة.");return@setOnClickListener
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(localized("Prepare these navigation actions?","تهيئة أوامر التنقل هذه؟"))
                .setMessage(approved.joinToString("\n") { uiStatus(it.name) }+localized("\n\nChoose the destination app yourself. Optionally Inspect screen locally for structural information, then press Run here on the floating AI control. Stop AI cancels remaining steps. No screen content is sent to the model.","\n\nاختر التطبيق المستهدف بنفسك. يمكنك فحص بنية الشاشة محليًا، ثم الضغط على تنفيذ هنا في النافذة العائمة. إيقاف الذكاء الاصطناعي يلغي الخطوات المتبقية. لا يُرسل محتوى الشاشة للنموذج."))
                .setNegativeButton(localized("Cancel","إلغاء"),null)
                .setPositiveButton(localized("Prepare","تهيئة")) { _,_ ->
                    if(plan!=approved || pending || android.os.SystemClock.uptimeMillis()-planAt>120000L) {
                        clearPlan();result.text=localized("Plan expired.","انتهت صلاحية الخطة.")
                    } else if(YvoraAccessibilityService.prepareAiPlan(approved)) {
                        clearPlan()
                        keyField.text.clear()
                        result.text=localized("Plan prepared. Choose destination and press Run here.","تمت تهيئة الخطة. اختر الوجهة واضغط تنفيذ هنا.")
                        if(!moveTaskToBack(true)) YvoraAccessibilityService.stopAi(localized("Could not leave Yvora. AI cancelled.","تعذر مغادرة Yvora. أُلغي التحكم الذكي."))
                    } else {
                        result.text=localized("Cannot prepare: enable Accessibility, end active drag, or unlock the screen.","تعذر التهيئة: فعّل إمكانية الوصول أو أنهِ السحب أو افتح قفل الشاشة.")
                    }
                }.show()
        }
        diagnose.setOnClickListener {
            if(pending) return@setOnClickListener
            val provider=providers[spinner.selectedItemPosition]
            val modelId=model.text.toString().trim()
            if(modelId.isEmpty() || keyField.text.isBlank()) {
                showConnection(true)
                result.text=localized("Enter model ID and API key first.","أدخل معرّف النموذج ومفتاح API أولًا.")
                return@setOnClickListener
            }
            val state=YvoraService.instance?.diagnosticState() ?: "engine_running=false; live_metrics=unavailable"
            val report="Yvora version 2.0.7-dev\n$state\n"+DiagnosticCounters.report()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(localized("Send only this report to ${provider.title}?","إرسال هذا التقرير فقط إلى ${provider.title}؟"))
                .setMessage(report+localized("\n\nAPI usage may cost money. No screenshots or raw logs are included.","\n\nقد تُحتسب تكلفة API. لا يتضمن التقرير لقطات شاشة أو سجلات خام."))
                .setNegativeButton(localized("Cancel","إلغاء"),null)
                .setPositiveButton(localized("Analyze","تحليل")) { _,_ ->
                    if(!pending) {
                        val key=keyField.text.toString().trim()
                        keyField.text.clear();keyField.clearFocus();panel.requestFocus();syncScreenshotProtection()
                        clearPlan()
                        val ticket=++generation
                        pending=true
                        proposed=null
                        applyFix.isEnabled=false
                        submit.isEnabled=false
                        diagnose.isEnabled=false
                        local.isEnabled=false
                        result.text=localized("Analyzing diagnostics…","جاري تحليل التشخيص…")
                        executor.execute {
                            var repair:RepairProposal?=null
                            val message=try {
                                repair=DiagnosticGate.parse(AiProviderClient.diagnose(provider,modelId,key,report))
                                repair?.let { localized("Suggested action: ${it.action.name}\n${it.explanation}\nNo change applied.","الإجراء المقترح: ${uiStatus(it.action.name)}\n${it.explanation}\nلم يُطبّق تغيير.") }
                                    ?: localized("Invalid diagnostic response. No change applied.","استجابة تشخيص غير صالحة. لم يُطبّق أي تغيير.")
                            } catch(e:AiNetworkException) { e.message?.let(::uiStatus) ?: localized("Provider rejected request.","رفض المزود الطلب.") }
                            catch(e:Exception) { localized("Connection or response error. Check configuration.","خطأ اتصال أو استجابة. تحقق من الإعدادات.") }
                            runOnUiThread {
                                pending=false
                                if(!isDestroyed) {
                                    if(ticket==generation) {
                                        proposed=repair
                                        result.text=message
                                        applyFix.isEnabled=DiagnosticGate.canLowerDetail(
                                            repair?.action ?: RepairAction.KEEP_SETTINGS,
                                            CameraSettings.readDetail(this)==CameraDetail.DISTANCE)
                                    }
                                    submit.isEnabled=true
                                    diagnose.isEnabled=true
                                    local.isEnabled=true
                                }
                            }
                        }
                    }
                }.show()
        }
        applyFix.setOnClickListener {
            val repair=proposed ?: return@setOnClickListener
            if(!DiagnosticGate.canLowerDetail(repair.action,CameraSettings.readDetail(this)==CameraDetail.DISTANCE)) return@setOnClickListener
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(localized("Lower camera detail?","خفض دقة الكاميرا؟"))
                .setMessage(localized("This saves AUTO camera detail for the next engine start. You can restore Distance Detail in settings. No other setting changes.","يحفظ هذا دقة الكاميرا التلقائية عند التشغيل التالي. يمكنك استعادة وضع تفاصيل المسافة من الإعدادات. لا تتغير إعدادات أخرى."))
                .setNegativeButton(localized("Cancel","إلغاء"),null)
                .setPositiveButton(localized("Apply","تطبيق")) { _,_ ->
                    CameraSettings.saveDetail(this,CameraDetail.AUTO)
                    proposed=null
                    applyFix.isEnabled=false
                    result.text=localized("AUTO detail saved. Stop and restart the engine to test. No improvement has been measured yet.","تم حفظ الدقة التلقائية. أعد تشغيل المحرك للاختبار. لم يُقَس أي تحسن بعد.")
                }.show()
        }
    }
    private fun clearPlan() { plan=null;planAt=0L;execute.isEnabled=false }
    override fun onResume() {
        super.onResume()
        foreground=true;syncScreenshotProtection()
        if(YvoraAccessibilityService.aiActive()) YvoraAccessibilityService.stopAi(localized("AI cancelled on return to Yvora","أُلغي التحكم الذكي عند العودة إلى Yvora"))
        pausedService=YvoraService.instance
        wasCalibrating=pausedService?.isCalibrating==true
        pausedService?.setCalibrationMode(true)
    }
    override fun onPause() {
        foreground=false;syncScreenshotProtection()
        if (YvoraService.instance === pausedService) pausedService?.setCalibrationMode(wasCalibrating)
        pausedService=null
        super.onPause()
    }
    override fun onDestroy() {
        generation++
        keyField.text.clear()
        executor.shutdownNow()
        super.onDestroy()
    }
}
