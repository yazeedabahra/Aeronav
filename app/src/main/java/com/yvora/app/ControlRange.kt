package com.yvora.app

/** Normalized region of the oriented, mirrored camera frame. */
internal data class ControlRange(
    val widthPercent: Int = 100,
    val heightPercent: Int = 100,
    val horizontalCenter: Int = 50,
    val verticalCenter: Int = 50
) {
    private fun start(span: Float, center: Int): Float =
        (center.coerceIn(0, 100) / 100f - span / 2f).coerceIn(0f, 1f - span)

    private val width = widthPercent.coerceIn(10, 100) / 100f
    private val height = heightPercent.coerceIn(10, 100) / 100f
    val left = start(width, horizontalCenter)
    val top = start(height, verticalCenter)
    val right = left + width
    val bottom = top + height

    fun mapX(x: Float) = ((x - left) / width).coerceIn(0f, 1f)
    fun mapY(y: Float) = ((y - top) / height).coerceIn(0f, 1f)

    // Slightly expand the reachable pointer range without a discontinuous jump.
    fun pointerX(x:Float)=edgeCompensation(mapX(x))
    fun pointerY(y:Float)=edgeCompensation(mapY(y))
    private fun edgeCompensation(value:Float)=((value-.025f)/.95f).coerceIn(0f,1f)
}
