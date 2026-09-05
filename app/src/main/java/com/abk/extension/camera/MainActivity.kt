package com.abk.extension.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch

class MainActivity : AppCompatActivity() {
    private lateinit var masterSwitch: MaterialSwitch
    private lateinit var statusSummary: TextView
    private lateinit var cameraSummary: TextView
    private lateinit var profileSummary: TextView
    private lateinit var backendSummary: TextView
    private lateinit var statusDetail: TextView

    private var camera = CameraId.BACK
    private var profileId = "mjpeg_1280x720"
    private var backendId = "test_pattern"

    private val cycleProfiles = listOf(
        "mjpeg_1280x720",
        "yuy2_1280x720",
        "mjpeg_640x360",
        "yuy2_640x360",
        "mjpeg_1920x1080_30",
    )
    private val cycleBackends = listOf("test_pattern", "camera2", "none")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        masterSwitch = findViewById(R.id.masterSwitch)
        statusSummary = findViewById(R.id.statusSummary)
        cameraSummary = findViewById(R.id.cameraSummary)
        profileSummary = findViewById(R.id.profileSummary)
        backendSummary = findViewById(R.id.backendSummary)
        statusDetail = findViewById(R.id.statusDetail)

        masterSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) startCamera() else stopCamera()
        }

        findViewById<android.view.View>(R.id.cameraRow).setOnClickListener {
            camera = if (camera == CameraId.BACK) CameraId.FRONT else CameraId.BACK
            CameraKernelBridge.writeCamera(camera)
            renderCamera()
        }

        findViewById<android.view.View>(R.id.profileRow).setOnClickListener {
            val index = cycleProfiles.indexOf(profileId)
            profileId = cycleProfiles[(index + 1).mod(cycleProfiles.size)]
            CameraKernelBridge.writeProfile(profileId)
            renderProfile()
        }

        findViewById<android.view.View>(R.id.backendRow).setOnClickListener {
            val index = cycleBackends.indexOf(backendId)
            backendId = cycleBackends[(index + 1).mod(cycleBackends.size)]
            CameraKernelBridge.writeBackend(backendId)
            CameraKernelBridge.testPattern(backendId == "test_pattern")
            renderBackend()
        }

        findViewById<android.view.View>(R.id.restoreRow).setOnClickListener {
            CameraKernelBridge.restoreUsb()
            refreshStatus()
        }

        requestCameraPermission()
        refresh()
    }

    private fun startCamera() {
        if (!RootShell.isRootAvailable()) {
            statusSummary.text = getString(R.string.root_needed)
            masterSwitch.isChecked = false
            return
        }
        if (!CameraKernelBridge.isPresent()) {
            statusSummary.text = getString(R.string.driver_missing)
            masterSwitch.isChecked = false
            return
        }
        val intent = Intent(this, CameraControlService::class.java).apply {
            action = CameraControlService.ACTION_START
            putExtra(CameraControlService.EXTRA_PROFILE, profileId)
            putExtra(CameraControlService.EXTRA_CAMERA, camera.sysfs)
        }
        ContextCompat.startForegroundService(this, intent)
        statusSummary.text = getString(R.string.start)
        refreshStatus()
    }

    private fun stopCamera() {
        val intent = Intent(this, CameraControlService::class.java).apply {
            action = CameraControlService.ACTION_STOP
        }
        ContextCompat.startForegroundService(this, intent)
        statusSummary.text = getString(R.string.stop)
        refreshStatus()
    }

    private fun renderCamera() {
        cameraSummary.text = if (camera == CameraId.FRONT) getString(R.string.camera_front) else getString(R.string.camera_back)
    }

    private fun renderProfile() {
        profileSummary.text = profileId
    }

    private fun renderBackend() {
        backendSummary.text = when (backendId) {
            "test_pattern" -> getString(R.string.backend_test_pattern)
            "camera2" -> getString(R.string.backend_camera2)
            else -> getString(R.string.backend_none)
        }
    }

    private fun refresh() {
        if (!CameraKernelBridge.isPresent()) {
            statusDetail.text = getString(R.string.driver_missing)
            return
        }
        camera = CameraId.fromSysfs(CameraKernelBridge.readCamera())
        profileId = CameraKernelBridge.readProfile().ifBlank { profileId }
        renderCamera()
        renderProfile()
        renderBackend()
        refreshStatus()
    }

    private fun refreshStatus() {
        val root = if (RootShell.isRootAvailable()) getString(R.string.value_yes) else getString(R.string.value_no)
        val driver = if (CameraKernelBridge.isPresent()) getString(R.string.value_present) else getString(R.string.value_missing)
        val fido = if (UsbCoordinator.isFidoPresent()) getString(R.string.value_present) else getString(R.string.value_missing)
        val usb = CameraKernelBridge.readUsbState().ifBlank { getString(R.string.value_unknown) }
        val stream = CameraKernelBridge.readStreamState().ifBlank { getString(R.string.value_unknown) }
        val fidoState = CameraKernelBridge.readFidoState().ifBlank { getString(R.string.value_unknown) }
        val speed = UsbCoordinator.readCurrentSpeed().ifBlank { getString(R.string.value_unknown) }

        statusDetail.text = buildString {
            append(getString(R.string.status_root)).append(": ").append(root).append("\n")
            append(getString(R.string.status_driver)).append(": ").append(driver).append("\n")
            append(getString(R.string.fido_label)).append(": ").append(fido).append(" / ").append(fidoState).append("\n")
            append(getString(R.string.usb_label)).append(": ").append(usb).append("\n")
            append(getString(R.string.stream_label)).append(": ").append(stream).append("\n")
            append(getString(R.string.speed_label)).append(": ").append(speed)
        }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }
}
