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

    fun readRange(context: Context): ControlRange = prefs(context).let {
        ControlRange(
            it.getInt("width", 100).coerceIn(30, 100),
            it.getInt("height", 100).coerceIn(30, 100),
            it.getInt("center_x", 50).coerceIn(0, 100),
            it.getInt("center_y", 50).coerceIn(0, 100)
        )
    }

    fun readDetail(context: Context): CameraDetail =
        if (prefs(context).getBoolean("distance", false)) CameraDetail.DISTANCE else CameraDetail.AUTO

    fun saveRange(context: Context, range: ControlRange) {
        prefs(context).edit().putInt("width", range.widthPercent)
            .putInt("height", range.heightPercent)
            .putInt("center_x", range.horizontalCenter)
            .putInt("center_y", range.verticalCenter).apply()
    }

    fun saveDetail(context: Context, detail: CameraDetail) {
        prefs(context).edit().putBoolean("distance", detail == CameraDetail.DISTANCE).apply()
    }
}
