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

    fun readAssist(context:Context):Boolean = prefs(context).getBoolean("assist_intent",false)
    fun saveAssist(context:Context,enabled:Boolean) { prefs(context).edit().putBoolean("assist_intent",enabled).apply() }
    fun readAssistLevel(context:Context):Int = prefs(context).getInt("assist_level",0).coerceIn(-1,1)
    fun saveAssistLevel(context:Context,level:Int) { prefs(context).edit().putInt("assist_level",level.coerceIn(-1,1)).apply() }

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
