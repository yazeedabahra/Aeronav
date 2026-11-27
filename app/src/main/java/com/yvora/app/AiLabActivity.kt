package com.yvora.app

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
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val panel=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(24,24,24,24)
        }
        fun text(value:String,size:Float=14f)=TextView(this).apply { text=value;textSize=size;panel.addView(this) }
        fun edit(hintText:String,secret:Boolean=false)=EditText(this).apply {
            hint=hintText
            isSaveEnabled=false
            if(secret) {
                inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                if(Build.VERSION.SDK_INT>=26) importantForAutofill=View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            }
            panel.addView(this)
        }
        text(tr("YVORA AI CORE","نواة الذكاء الاصطناعي YVORA"),24f)
        text(tr("AI navigation • review, run and diagnostics","التنقل الذكي • مراجعة وتنفيذ وتشخيص"))
        text(tr("Only the command you type, or a diagnostic report you explicitly review and approve, is sent to your selected provider. No camera images, screen text, raw logs or hand coordinates are sent. Use a restricted test API key; do not use a production key. Keys are not saved. API usage may cost money. Production distribution requires a secure backend.","يُرسل فقط الأمر الذي تكتبه أو تقرير التشخيص الذي تراجعه وتوافق عليه. لا تُرسل صور الكاميرا أو نص الشاشة أو السجلات الخام أو إحداثيات اليد. استخدم مفتاح API تجريبيًا محدود الصلاحيات، لا مفتاح الإنتاج. لا تُحفظ المفاتيح وقد تُحتسب تكلفة. التوزيع الإنتاجي يحتاج خادمًا آمنًا."))
        val providers=AiProvider.values()
        val spinner=Spinner(this).apply {
            adapter=ArrayAdapter(this@AiLabActivity,android.R.layout.simple_spinner_dropdown_item,providers.map { it.title })
            panel.addView(this)
        }
        val model=edit(tr("Model ID from provider dashboard","معرّف النموذج من لوحة المزود"))
        keyField=edit(tr("Temporary / restricted API key","مفتاح API مؤقت أو محدود"),true)
        val prompt=edit(tr("Command (e.g. swipe right, then go back)","الأمر (مثل: اسحب يمين ثم ارجع)"))
        val local=Button(this).apply { text=tr("Preview locally (no API)","معاينة محلية (دون API)");panel.addView(this) }
        submit=Button(this).apply { text=tr("Ask selected AI provider","اسأل مزود الذكاء الاصطناعي المحدد");panel.addView(this) }
        execute=Button(this).apply { text=tr("Prepare approved AI actions","تهيئة الأوامر المعتمدة");isEnabled=false;panel.addView(this) }
        diagnose=Button(this).apply { text=tr("Review diagnostic report","مراجعة تقرير التشخيص");panel.addView(this) }
        applyFix=Button(this).apply { text=tr("Apply suggested lower camera detail","تطبيق خفض دقة الكاميرا المقترح");isEnabled=false;panel.addView(this) }
        val clear=Button(this).apply { text=tr("Clear key and cancel preview","مسح المفتاح وإلغاء المعاينة");panel.addView(this) }
        result=text(tr("No model connected yet.","لم يتم ربط نموذج بعد."))
        val scroll=ScrollView(this).apply { addView(panel) }
        setContentView(scroll)
        clear.setOnClickListener {
            generation++
            keyField.text.clear()
            result.text=tr("Cleared. Any already-sent provider request may finish remotely.","تم المسح. قد يستكمل المزود الطلب الذي سبق إرساله.")
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
            val metrics=String.format(java.util.Locale.ROOT,tr("Local coverage %.2f | lexical similarity %.2f | local membership %.2f","التغطية المحلية %.2f | التشابه اللفظي %.2f | الانتماء المحلي %.2f"),
                route.coverage,route.similarity,route.localDegree)
            val actions=route.actions
            if(actions!=null) {
                plan=actions;planAt=android.os.SystemClock.uptimeMillis();execute.isEnabled=true
                result.text=tr("LOCAL • no API request\n","محلي • لا يوجد طلب API\n")+metrics+"\n"+actions.joinToString("\n"){uiStatus(it.name)}+tr("\nReview before execution.","\nراجع قبل التنفيذ.")
            } else result.text=tr("No exact safe local plan. No request sent.\n","لا توجد خطة محلية آمنة ومطابقة. لم يُرسل طلب.\n")+metrics+
                tr("\nEdit the command or explicitly choose Ask selected AI provider. Similarity alone never authorizes a command.","\nعدّل الأمر أو اختر سؤال المزود صراحةً. التشابه وحده لا يسمح بتنفيذ الأمر.")
        }
        submit.setOnClickListener {
            if(pending) return@setOnClickListener
            val provider=providers[spinner.selectedItemPosition]
            val modelId=model.text.toString().trim()
            val key=keyField.text.toString().trim()
            val command=prompt.text.toString().trim()
            if(modelId.isEmpty() || key.isEmpty() || command.isEmpty()) {
                result.text=tr("Enter model ID, API key, and command.","أدخل معرّف النموذج ومفتاح API والأمر.")
                return@setOnClickListener
            }
            clearPlan();proposed=null;applyFix.isEnabled=false
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(tr("Send this command to ${provider.title}?","إرسال هذا الأمر إلى ${provider.title}؟"))
                .setMessage(command+tr("\n\nOnly this command is sent. API usage may cost money.","\n\nسيُرسل هذا الأمر فقط. قد تُحتسب تكلفة API."))
                .setNegativeButton(tr("Cancel","إلغاء"),null)
                .setPositiveButton(tr("Send","إرسال")) { _,_ ->
            if(pending) return@setPositiveButton
            val ticket=++generation
            pending=true
            submit.isEnabled=false
            diagnose.isEnabled=false
            local.isEnabled=false
            keyField.text.clear()
            result.text=tr("Requesting preview from ${provider.title}…","طلب معاينة من ${provider.title}…")
            executor.execute {
                var actions:List<AiAction>?=null
                val message=try {
                    val response=AiProviderClient.plan(provider,modelId,key,command)
                    actions=AiCommandGate.parse(response)
                    if(actions==null) tr("Unsupported or invalid response. No actions executed.","استجابة غير مدعومة أو غير صالحة. لم تُنفّذ أي أوامر.")
                    else tr("Validated plan:\n","الخطة المقبولة:\n")+actions!!.joinToString("\n") { uiStatus(it.name) }+tr("\nReview before execution.","\nراجع قبل التنفيذ.")
                } catch(e:AiNetworkException) { e.message ?: tr("Provider rejected request.","رفض المزود الطلب.") }
                catch(e:IllegalArgumentException) { tr("Invalid configuration or command.","إعدادات أو أمر غير صالح.") }
                catch(e:Exception) { tr("Connection or response error. Check model ID, key, and internet.","خطأ في الاتصال أو الاستجابة. تحقق من معرّف النموذج والمفتاح والإنترنت.") }
                runOnUiThread {
                    pending=false
                    if(!isDestroyed) {
                        if(ticket==generation) {
                            result.text=uiStatus(message)
                            val valid=actions?.takeIf { AiCommandGate.executable(it) }
                            plan=valid;planAt=android.os.SystemClock.uptimeMillis()
                            execute.isEnabled=valid!=null
                            if(actions!=null && valid==null) result.append(tr("\nExecution blocked: BACK/HOME must be last.","\nتم منع التنفيذ: يجب أن يكون الرجوع أو الرئيسية آخر أمر."))
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
                clearPlan();result.text=tr("Plan expired. Request a new preview.","انتهت صلاحية الخطة. اطلب معاينة جديدة.");return@setOnClickListener
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(tr("Prepare these navigation actions?","تهيئة أوامر التنقل هذه؟"))
                .setMessage(approved.joinToString("\n") { uiStatus(it.name) }+tr("\n\nChoose the destination app yourself. Optionally Inspect screen locally for structural information, then press Run here on the floating AI control. Stop AI cancels remaining steps. No screen content is sent to the model.","\n\nاختر التطبيق المستهدف بنفسك. يمكنك فحص بنية الشاشة محليًا، ثم الضغط على تنفيذ هنا في النافذة العائمة. إيقاف الذكاء الاصطناعي يلغي الخطوات المتبقية. لا يُرسل محتوى الشاشة للنموذج."))
                .setNegativeButton(tr("Cancel","إلغاء"),null)
                .setPositiveButton(tr("Prepare","تهيئة")) { _,_ ->
                    if(plan!=approved || pending || android.os.SystemClock.uptimeMillis()-planAt>120000L) {
                        clearPlan();result.text=tr("Plan expired.","انتهت صلاحية الخطة.")
                    } else if(YvoraAccessibilityService.prepareAiPlan(approved)) {
                        clearPlan()
                        keyField.text.clear()
                        result.text=tr("Plan prepared. Choose destination and press Run here.","تمت تهيئة الخطة. اختر الوجهة واضغط تنفيذ هنا.")
                        if(!moveTaskToBack(true)) YvoraAccessibilityService.stopAi(tr("Could not leave Yvora. AI cancelled.","تعذر مغادرة Yvora. أُلغي التحكم الذكي."))
                    } else {
                        result.text=tr("Cannot prepare: enable Accessibility, end active drag, or unlock the screen.","تعذر التهيئة: فعّل إمكانية الوصول أو أنهِ السحب أو افتح قفل الشاشة.")
                    }
                }.show()
        }
        diagnose.setOnClickListener {
            if(pending) return@setOnClickListener
            val provider=providers[spinner.selectedItemPosition]
            val modelId=model.text.toString().trim()
            if(modelId.isEmpty() || keyField.text.isBlank()) {
                result.text=tr("Enter model ID and API key first.","أدخل معرّف النموذج ومفتاح API أولًا.")
                return@setOnClickListener
            }
            val state=YvoraService.instance?.diagnosticState() ?: "engine_running=false; live_metrics=unavailable"
            val report="Yvora version 2.0.1-dev\n$state\n"+DiagnosticCounters.report()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(tr("Send only this report to ${provider.title}?","إرسال هذا التقرير فقط إلى ${provider.title}؟"))
                .setMessage(report+tr("\n\nAPI usage may cost money. No screenshots or raw logs are included.","\n\nقد تُحتسب تكلفة API. لا يتضمن التقرير لقطات شاشة أو سجلات خام."))
                .setNegativeButton(tr("Cancel","إلغاء"),null)
                .setPositiveButton(tr("Analyze","تحليل")) { _,_ ->
                    if(!pending) {
                        val key=keyField.text.toString().trim()
                        keyField.text.clear()
                        clearPlan()
                        val ticket=++generation
                        pending=true
                        proposed=null
                        applyFix.isEnabled=false
                        submit.isEnabled=false
                        diagnose.isEnabled=false
                        result.text=tr("Analyzing diagnostics…","جاري تحليل التشخيص…")
                        executor.execute {
                            var repair:RepairProposal?=null
                            val message=try {
                                repair=DiagnosticGate.parse(AiProviderClient.diagnose(provider,modelId,key,report))
                                repair?.let { tr("Suggested action: ${it.action.name}\n${it.explanation}\nNo change applied.","الإجراء المقترح: ${uiStatus(it.action.name)}\n${it.explanation}\nلم يُطبّق تغيير.") }
                                    ?: tr("Invalid diagnostic response. No change applied.","استجابة تشخيص غير صالحة. لم يُطبّق أي تغيير.")
                            } catch(e:AiNetworkException) { e.message ?: tr("Provider rejected request.","رفض المزود الطلب.") }
                            catch(e:Exception) { tr("Connection or response error. Check configuration.","خطأ اتصال أو استجابة. تحقق من الإعدادات.") }
                            runOnUiThread {
                                pending=false
                                if(!isDestroyed) {
                                    if(ticket==generation) {
                                        proposed=repair
                                        result.text=uiStatus(message)
                                        applyFix.isEnabled=DiagnosticGate.canLowerDetail(
                                            repair?.action ?: RepairAction.KEEP_SETTINGS,
                                            CameraSettings.readDetail(this)==CameraDetail.DISTANCE)
                                    }
                                    submit.isEnabled=true
                                    diagnose.isEnabled=true
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
                .setTitle(tr("Lower camera detail?","خفض دقة الكاميرا؟"))
                .setMessage(tr("This saves AUTO camera detail for the next engine start. You can restore Distance Detail in settings. No other setting changes.","يحفظ هذا دقة الكاميرا التلقائية عند التشغيل التالي. يمكنك استعادة وضع تفاصيل المسافة من الإعدادات. لا تتغير إعدادات أخرى."))
                .setNegativeButton(tr("Cancel","إلغاء"),null)
                .setPositiveButton(tr("Apply","تطبيق")) { _,_ ->
                    CameraSettings.saveDetail(this,CameraDetail.AUTO)
                    proposed=null
                    applyFix.isEnabled=false
                    result.text=tr("AUTO detail saved. Stop and restart the engine to test. No improvement has been measured yet.","تم حفظ الدقة التلقائية. أعد تشغيل المحرك للاختبار. لم يُقَس أي تحسن بعد.")
                }.show()
        }
    }
    private fun clearPlan() { plan=null;planAt=0L;execute.isEnabled=false }
    override fun onResume() {
        super.onResume()
        if(YvoraAccessibilityService.aiActive()) YvoraAccessibilityService.stopAi(tr("AI cancelled on return to Yvora","أُلغي التحكم الذكي عند العودة إلى Yvora"))
        pausedService=YvoraService.instance
        wasCalibrating=pausedService?.isCalibrating==true
        pausedService?.setCalibrationMode(true)
    }
    override fun onPause() {
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
