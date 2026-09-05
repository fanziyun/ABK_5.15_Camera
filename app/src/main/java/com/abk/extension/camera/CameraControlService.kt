package com.abk.extension.camera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * Foreground service which owns the frame source lifecycle and the USB
 * priority/fallback coordinator.  Camera2 capture cannot run in the background
 * without a foreground service, so this service is started by the UI and by the
 * boot receiver.
 */
class CameraControlService : Service() {
    companion object {
        private const val TAG = "AbkCameraService"
        private const val CHANNEL_ID = "abk_uvc_camera"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.abk.extension.camera.action.START"
        const val ACTION_STOP = "com.abk.extension.camera.action.STOP"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_CAMERA = "camera"
    }

    private var source: FrameSource? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Initializing camera output"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startCamera(intent)
            ACTION_STOP -> stopCamera()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startCamera(intent: Intent) {
        if (!RootShell.isRootAvailable()) {
            Log.w(TAG, "root unavailable")
            return
        }
        if (!CameraKernelBridge.isPresent()) {
            Log.w(TAG, "kernel driver missing")
            return
        }
        val original = UsbCoordinator.readOriginalConfig()
        CameraKernelBridge.writeEnabled(true)
        val profile = ProfileModel.fromId(intent.getStringExtra(EXTRA_PROFILE) ?: "mjpeg_1280x720")
        val camera = CameraId.fromSysfs(intent.getStringExtra(EXTRA_CAMERA) ?: "back")
        CameraKernelBridge.writeCamera(camera)
        CameraKernelBridge.writeProfile(profile.id)

        val state = UsbCoordinator.applyPriority(original)
        Log.i(TAG, "usb state: " + state.state)
        if (!state.uvcOnline) {
            CameraKernelBridge.writeEnabled(false)
            updateNotification("Camera aborted: " + state.state)
            stopSelf()
            return
        }

        val backend = Camera2Backend(this)
        source = backend
        backend.start(profile, camera)
        CameraKernelBridge.command("start")
        CameraKernelBridge.writeBackend(backend.id)
        updateNotification("USB: " + state.state + " / profile: " + profile.id)
    }

    private fun stopCamera() {
        source?.stop()
        source = null
        CameraKernelBridge.command("stop")
        val original = UsbCoordinator.readOriginalConfig()
        UsbCoordinator.restoreUsbConfig(original)
        updateNotification("Camera output stopped")
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.service_channel_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun notification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification(text))
    }
}
