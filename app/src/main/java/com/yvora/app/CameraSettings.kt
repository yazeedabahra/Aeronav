package com.yvora.app

import android.content.Context
import android.util.Size

internal enum class CameraDetail {
    AUTO, DISTANCE;

    fun requestedSize(lowRam: Boolean): Size = when (this) {
        AUTO -> if (lowRam) Size(240, 320) else Size(360, 480)
        DISTANCE -> if (lowRam) Size(360, 480) else Size(480, 640)
    }
}

internal object CameraSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("camera_calibration", Context.MODE_PRIVATE)

    fun readRanges(context:Context):HandRanges {
        val p=prefs(context)
        fun read(prefix:String,fallback:ControlRange)=ControlRange(
            p.getInt(prefix+"width",fallback.widthPercent).coerceIn(10,100),
            p.getInt(prefix+"height",fallback.heightPercent).coerceIn(10,100),
            p.getInt(prefix+"center_x",fallback.horizontalCenter).coerceIn(0,100),
            p.getInt(prefix+"center_y",fallback.verticalCenter).coerceIn(0,100)
        )
        val shared=read("",ControlRange())
        return HandRanges(p.getBoolean("ranges_linked",true),shared,read("left_",shared),read("right_",shared))
    }
    fun readRange(context:Context):ControlRange=readRanges(context).shared
    fun saveRanges(context:Context,value:HandRanges) {
        val e=prefs(context).edit().putBoolean("ranges_linked",value.linked)
        fun write(prefix:String,r:ControlRange) {
            e.putInt(prefix+"width",r.widthPercent).putInt(prefix+"height",r.heightPercent)
                .putInt(prefix+"center_x",r.horizontalCenter).putInt(prefix+"center_y",r.verticalCenter)
        }
        write("",value.shared);write("left_",value.left);write("right_",value.right);e.apply()
    }

    fun readPersonalPose(context:Context,slot:Int):PoseFeatures? {
        val p=prefs(context);val prefix="personal_pose_"+slot+"_"
        if(!p.getBoolean(prefix+"saved",false)) return null
        return PoseFeatures(p.getFloat(prefix+"edge",0f),p.getFloat(prefix+"extension",0f),
            p.getFloat(prefix+"together",0f)).takeIf{it.eligible()}
    }
    fun savePersonalPose(context:Context,slot:Int,value:PoseFeatures?) {
        val prefix="personal_pose_"+slot+"_";val e=prefs(context).edit()
        if(value==null || !value.eligible()) {
            e.remove(prefix+"saved").remove(prefix+"edge").remove(prefix+"extension").remove(prefix+"together")
        } else e.putBoolean(prefix+"saved",true).putFloat(prefix+"edge",value.edge)
            .putFloat(prefix+"extension",value.extension).putFloat(prefix+"together",value.together)
        e.apply()
    }

    fun readCanvasRenderer(context:Context):Boolean=prefs(context).getBoolean("canvas_renderer",true)
    fun saveCanvasRenderer(context:Context,enabled:Boolean){prefs(context).edit().putBoolean("canvas_renderer",enabled).apply()}

    fun readResponsive(context:Context):Boolean=prefs(context).getBoolean("responsive_cursor",true)
    fun saveResponsive(context:Context,enabled:Boolean){prefs(context).edit().putBoolean("responsive_cursor",enabled).apply()}

    fun readAssist(context:Context):Boolean = prefs(context).getBoolean("assist_intent",false)
    fun saveAssist(context:Context,enabled:Boolean) { prefs(context).edit().putBoolean("assist_intent",enabled).apply() }
    fun readAssistLevel(context:Context):Int = prefs(context).getInt("assist_level",0).coerceIn(-1,1)
    fun saveAssistLevel(context:Context,level:Int) { prefs(context).edit().putInt("assist_level",level.coerceIn(-1,1)).apply() }

    fun readDualMode(context:Context):DualMode {
        val p=prefs(context)
        val fallback=if(p.getBoolean("zoom_mode",false)) DualMode.ZOOM else DualMode.NORMAL
        return DualMode.values().firstOrNull{it.name==p.getString("dual_transform_mode",fallback.name)} ?: fallback
    }
    fun saveDualMode(context:Context,value:DualMode) {
        prefs(context).edit().putString("dual_transform_mode",value.name).putBoolean("zoom_mode",value==DualMode.ZOOM).apply()
    }

    fun readMotionSlot(context:Context)=prefs(context).getInt("motion_slot",0).coerceIn(0,1)
    fun saveMotionSlot(context:Context,slot:Int){prefs(context).edit().putInt("motion_slot",slot.coerceIn(0,1)).apply()}
    fun readMotion(context:Context,slot:Int,action:AiAction):MotionPattern? {
        val raw=prefs(context).getString("motion_${slot}_${action.name}",null) ?: return null
        val parts=raw.split(',')
        if(parts.size!=33) return null
        val duration=parts[0].toLongOrNull() ?: return null
        val values=parts.drop(1).map{it.toFloatOrNull() ?: return null}
        return MotionPattern(values,duration).takeIf{it.valid()}
    }
    fun saveMotion(context:Context,slot:Int,action:AiAction,value:MotionPattern?) {
        val e=prefs(context).edit();val key="motion_${slot}_${action.name}"
        if(value==null || !value.valid() || action !in trajectoryActions) e.remove(key)
        else e.putString(key,value.duration.toString()+","+value.values.joinToString(","))
        e.apply()
    }
    fun readMotions(context:Context,slot:Int)=trajectoryActions.mapNotNull{a->readMotion(context,slot,a)?.let{a to it}}.toMap()

    fun readDualHand(context: Context): Boolean = prefs(context).getBoolean("dual_hand", true)

    fun saveDualHand(context: Context, enabled:Boolean) { prefs(context).edit().putBoolean("dual_hand",enabled).apply() }

    fun readDetail(context: Context): CameraDetail =
        if (prefs(context).getBoolean("distance", false)) CameraDetail.DISTANCE else CameraDetail.AUTO

    fun saveRange(context:Context,range:ControlRange) {
        saveRanges(context,readRanges(context).copy(shared=range))
    }

    fun saveDetail(context: Context, detail: CameraDetail) {
        prefs(context).edit().putBoolean("distance", detail == CameraDetail.DISTANCE).apply()
    }
}
