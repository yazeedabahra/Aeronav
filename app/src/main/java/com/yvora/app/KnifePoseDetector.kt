package com.yvora.app

import kotlin.math.abs
import kotlin.math.sqrt

/** Palm plane is calculated in 3D; face-on and edge-on are different poses. */
internal class KnifePoseDetector {
    private val points = FloatArray(63)
    private var armed = false
    private var enterCount = 0
    private var exitCount = 0
    var candidate = false
        private set
    var edgeScore = 0f
        private set
    fun set(i: Int, x: Float, y: Float, z: Float) {
        points[3*i]=x; points[3*i+1]=y; points[3*i+2]=z
    }
    private fun delta(a: Int,b: Int,k: Int) = points[3*b+k]-points[3*a+k]
    private fun dist(a: Int,b: Int): Float {
        val x=delta(a,b,0); val y=delta(a,b,1); val z=delta(a,b,2)
        return sqrt(x*x+y*y+z*z)
    }
    private fun straight(a:Int,b:Int,c:Int): Float {
        val u=dist(a,b); val v=dist(b,c)
        if(u < 0.00001f || v < 0.00001f) return -1f
        val d=dist(a,c)
        return ((d*d-u*u-v*v)/(2*u*v)).coerceIn(-1f,1f)
    }
    fun update(): Boolean {
        val ax=delta(0,9,0); val ay=delta(0,9,1); val az=delta(0,9,2)
        val bx=delta(17,5,0); val by=delta(17,5,1); val bz=delta(17,5,2)
        val nx=ay*bz-az*by; val ny=az*bx-ax*bz; val nz=ax*by-ay*bx
        val norm=sqrt(nx*nx+ny*ny+nz*nz)
        val len=dist(0,9); val width=dist(5,17)
        if (!norm.isFinite() || len < 0.00001f || width < 0.00001f || norm < len*width*0.25f) {
            reset()
            return false
        }
        edgeScore=(1f-abs(nz)/norm).coerceIn(0f,1f)
        var extended=0
        for (base in 5..17 step 4) {
            if (straight(base,base+1,base+2) > 0.35f &&
                straight(base+1,base+2,base+3) > 0.20f &&
                dist(base,base+3) > len*0.42f) extended++
        }
        val together=dist(8,12)+dist(12,16)+dist(16,20)
        val valid=extended>=3 && together <= width*1.40f && edgeScore >= (if (armed) 0.43f else 0.63f)
        candidate = valid
        if (valid) {
            exitCount=0
            if (++enterCount>=2) armed=true
        } else {
            enterCount=0
            if (++exitCount>=2) armed=false
        }
        return armed
    }
    fun reset() { armed=false; enterCount=0; exitCount=0; edgeScore=0f; candidate=false }
}
