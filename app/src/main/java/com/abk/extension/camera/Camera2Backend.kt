package com.abk.extension.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import java.io.ByteArrayOutputStream

/**
 * Camera2 fallback backend.  This keeps the USB gadget and UVC interface
 * stable while swapping only the frame source, as required for front/back
 * switching without re-enumeration.
 *
 * YUY2 profiles are converted from the YUV_420_888 stream.  MJPEG profiles are
 * compressed with YuvImage.  H.264 output is deliberately left experimental
 * because the descriptor/mux path is not wired in the first version.
 */
internal class Camera2Backend(
    private val context: Context,
) : FrameSource {
    override val id: String = "camera2"
    private val tag = "AbkCamera2Backend"

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var profile: Profile? = null
    private var camera: CameraId = CameraId.BACK
    private val latest = object {
        @Volatile var frame: ByteArray? = null
    }

    override fun start(profile: Profile, camera: CameraId) {
        stopInternal()
        this.profile = profile
        this.camera = camera
        openCamera(profile, camera)
    }

    override fun stop() = stopInternal()

    override fun nextFrame(): ByteArray? = latest.frame

    private fun openCamera(profile: Profile, cameraId: CameraId) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lens = if (cameraId == CameraId.FRONT) {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        val id = manager.cameraIdList.firstOrNull { candidate ->
            val characteristics = manager.getCameraCharacteristics(candidate)
            characteristics.get(CameraCharacteristics.LENS_FACING) == lens
        } ?: run {
            Log.w(tag, "no camera for " + cameraId.sysfs)
            return
        }

        val size = Size(profile.width, profile.height)
        val thread = HandlerThread("abk-camera2").also { it.start() }
        this.thread = thread
        this.handler = Handler(thread.looper)
        val imageReader = ImageReader.newInstance(profile.width, profile.height, ImageFormat.YUV_420_888, 2)
        this.reader = imageReader
        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                latest.frame = when (profile.format) {
                    CameraFormat.YUY2 -> yuv420ToYuyv(image)
                    CameraFormat.MJPEG -> yuv420ToJpeg(image)
                    CameraFormat.H264 -> null
                }
            } finally {
                image.close()
            }
        }, handler)

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                device = camera
                createSession(camera, imageReader, profile)
            }

            override fun onDisconnected(camera: CameraDevice) {
                camera.close()
                if (device == camera) device = null
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.w(tag, "camera error " + error)
                camera.close()
                if (device == camera) device = null
            }
        }, handler)
    }

    private fun createSession(camera: CameraDevice, imageReader: ImageReader, profile: Profile) {
        val surface = imageReader.surface
        camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                }.build()
                runCatching { s.setRepeatingRequest(request, null, handler) }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                Log.w(tag, "capture session failed")
            }
        }, handler)
    }

    private fun stopInternal() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
        runCatching { thread?.quitSafely() }
        thread = null
        handler = null
        latest.frame = null
    }

    private fun yuv420ToYuyv(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height * 2)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yStride = y.rowStride
        val uStride = u.rowStride
        val vStride = v.rowStride
        val yBuffer = y.buffer
        val uBuffer = u.buffer
        val vBuffer = v.buffer
        val uvPixelStride = u.pixelStride
        val vPixelStride = v.pixelStride

        for (row in 0 until height) {
            val yRowStart = row * yStride
            val uvRowStart = (row / 2) * uStride
            for (col in 0 until width) {
                val y0 = yBuffer.get(yRowStart + col).toInt() and 0xFF
                val uvIndex = uvRowStart + (col / 2) * uvPixelStride
                val u0 = uBuffer.get(uvIndex).toInt() and 0xFF
                val v0 = vBuffer.get(uvIndex).toInt() and 0xFF
                val outIndex = (row * width + col) * 2
                out[outIndex] = y0.toByte()
                out[outIndex + 1] = if ((col and 1) == 0) u0.toByte() else v0.toByte()
            }
        }
        return out
    }

    private fun yuv420ToJpeg(image: Image): ByteArray {
        val nv21 = yuv420ToNv21(image)
        val yuvImage = android.graphics.YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val stream = ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, image.width, image.height), 80, stream)
        return stream.toByteArray()
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height * 3 / 2)
        val y = image.planes[0]
        val u = image.planes[1]
        val v = image.planes[2]
        val yStride = y.rowStride
        val uStride = u.rowStride
        val vStride = v.rowStride
        val yBuffer = y.buffer
        val uBuffer = u.buffer
        val vBuffer = v.buffer
        val uvPixelStride = u.pixelStride

        for (row in 0 until height) {
            yBuffer.position(row * yStride)
            yBuffer.get(out, row * width, width)
        }
        val uvSize = width * height / 4
        val uvRowStride = uStride
        for (row in 0 until height / 2) {
            val uRowStart = row * uvRowStride
            for (col in 0 until width / 2) {
                val uvIndex = uRowStart + col * uvPixelStride
                val outIndex = width * height + row * width + col * 2
                out[outIndex] = vBuffer.get(uvIndex)
                out[outIndex + 1] = uBuffer.get(uvIndex)
            }
        }
        return out
    }
}
