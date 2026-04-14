package com.example.airscroll

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class HandLandmarkerHelper(
    val context: Context,
    val listener: LandmarkerListener
) {
    private var handLandmarker: HandLandmarker? = null

    companion object {
        private const val TAG = "HandLandmarkerHelper"
        private const val MODEL_PATH = "hand_landmarker.task"
    }

    fun setup() {
        // Check model exists
        try {
            val files = context.assets.list("") ?: emptyArray()
            if (!files.contains("hand_landmarker.task")) {
                val msg = "hand_landmarker.task not found in assets"
                Log.e(TAG, msg)
                listener.onError(msg)
                return
            }
            Log.d(TAG, "Model file confirmed in assets")
        } catch (e: Exception) {
            Log.e(TAG, "Asset check failed: ${e.message}")
        }

        try {
            val baseOptions = BaseOptions.builder()
                .setDelegate(Delegate.CPU)
                .setModelAssetPath(MODEL_PATH)
                .build()

            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setMinHandDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .setMinHandPresenceConfidence(0.5f)
                .setNumHands(1)
                .setRunningMode(RunningMode.LIVE_STREAM)
                .setResultListener(this::onResult)
                .setErrorListener(this::onError)
                .build()

            handLandmarker = HandLandmarker.createFromOptions(context, options)
            Log.d(TAG, "HandLandmarker ready")

        } catch (e: Exception) {
            Log.e(TAG, "Setup failed: ${e.message}", e)
            listener.onError("Setup failed: ${e.message}")
        }
    }

    fun detectLiveStream(imageProxy: ImageProxy, isFrontCamera: Boolean) {
        val frameTime = SystemClock.uptimeMillis()

        try {
            // Always convert through YUV path — safest for all devices
            val bitmap = when (imageProxy.format) {
                ImageFormat.YUV_420_888 -> {
                    convertYuv420ToBitmap(imageProxy)
                }
                ImageFormat.JPEG -> {
                    convertJpegToBitmap(imageProxy)
                }
                else -> {
                    // Try YUV path as fallback for unknown formats
                    Log.w(TAG, "Unknown format ${imageProxy.format}, trying YUV path")
                    convertYuv420ToBitmap(imageProxy)
                }
            }

            imageProxy.close()

            if (bitmap == null) {
                Log.w(TAG, "Bitmap is null after conversion")
                return
            }

            // Rotate and mirror
            val finalBitmap = rotateBitmap(
                bitmap,
                imageProxy.imageInfo.rotationDegrees,
                isFrontCamera
            )

            val mpImage = BitmapImageBuilder(finalBitmap).build()
            handLandmarker?.detectAsync(mpImage, frameTime)

        } catch (e: Exception) {
            Log.e(TAG, "detectLiveStream error: ${e.message}", e)
            try {
                imageProxy.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun convertYuv420ToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val width = imageProxy.width
            val height = imageProxy.height

            val yPlane = imageProxy.planes[0]
            val uPlane = imageProxy.planes[1]
            val vPlane = imageProxy.planes[2]

            val yBuffer: ByteBuffer = yPlane.buffer
            val uBuffer: ByteBuffer = uPlane.buffer
            val vBuffer: ByteBuffer = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val uvRowStride = uPlane.rowStride
            val uvPixelStride = uPlane.pixelStride

            // Build NV21 byte array manually
            val nv21 = ByteArray(width * height * 3 / 2)

            // Copy Y plane row by row
            val yData = ByteArray(yBuffer.remaining())
            yBuffer.get(yData)

            var nv21Index = 0
            for (row in 0 until height) {
                val srcOffset = row * yRowStride
                val copyLen = minOf(width, yData.size - srcOffset)
                if (copyLen <= 0) break
                System.arraycopy(yData, srcOffset, nv21, nv21Index, copyLen)
                nv21Index += width
            }

            // Copy UV planes interleaved as VU (NV21 format)
            val uData = ByteArray(uBuffer.remaining())
            val vData = ByteArray(vBuffer.remaining())
            uBuffer.get(uData)
            vBuffer.get(vData)

            val uvHeight = height / 2
            val uvWidth = width / 2

            for (row in 0 until uvHeight) {
                for (col in 0 until uvWidth) {
                    val uvIndex = row * uvRowStride / uvPixelStride + col
                    if (uvIndex < vData.size && nv21Index + 1 < nv21.size) {
                        nv21[nv21Index] = vData[minOf(
                            row * uvRowStride + col * uvPixelStride,
                            vData.size - 1
                        )]
                        nv21[nv21Index + 1] = uData[minOf(
                            row * uvRowStride + col * uvPixelStride,
                            uData.size - 1
                        )]
                        nv21Index += 2
                    }
                }
            }

            // Convert NV21 to JPEG to Bitmap
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val outputStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, outputStream)
            val jpegBytes = outputStream.toByteArray()

            android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)

        } catch (e: Exception) {
            Log.e(TAG, "YUV conversion failed: ${e.message}", e)
            null
        }
    }

    private fun convertJpegToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val buffer = imageProxy.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            Log.e(TAG, "JPEG conversion failed: ${e.message}", e)
            null
        }
    }

    private fun rotateBitmap(
        bitmap: Bitmap,
        rotationDegrees: Int,
        flipHorizontal: Boolean
    ): Bitmap {
        val matrix = Matrix()

        if (rotationDegrees != 0) {
            matrix.postRotate(rotationDegrees.toFloat())
        }

        if (flipHorizontal) {
            matrix.postScale(-1f, 1f, bitmap.width / 2f, bitmap.height / 2f)
        }

        return if (rotationDegrees == 0 && !flipHorizontal) {
            bitmap
        } else {
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            rotated
        }
    }

    private fun onResult(result: HandLandmarkerResult, input: MPImage) {
        val inferenceTime = SystemClock.uptimeMillis() - result.timestampMs()
        listener.onResults(result, inferenceTime, input.width, input.height)
    }

    private fun onError(error: RuntimeException) {
        Log.e(TAG, "MediaPipe error: ${error.message}", error)
        listener.onError(error.message ?: "Unknown error")
    }

    fun close() {
        try {
            handLandmarker?.close()
            handLandmarker = null
            Log.d(TAG, "HandLandmarker closed")
        } catch (e: Exception) {
            Log.e(TAG, "Close error: ${e.message}", e)
        }
    }

    interface LandmarkerListener {
        fun onResults(
            result: HandLandmarkerResult,
            inferenceTime: Long,
            imageWidth: Int,
            imageHeight: Int
        )

        fun onError(error: String)
    }
}