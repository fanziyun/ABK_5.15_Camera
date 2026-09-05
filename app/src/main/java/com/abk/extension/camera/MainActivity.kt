package com.abk.extension.camera

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private var camera = CameraId.BACK
    private var profileId = "mjpeg_1280x720"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        root.addView(column)

        column.addView(TextView(this).apply {
            text = getString(R.string.main_title)
            textSize = 22f
        })
        column.addView(TextView(this).apply {
            text = getString(R.string.main_subtitle)
            textSize = 14f
        })

        status = TextView(this).apply {
            text = "root=" + RootShell.isRootAvailable() + " driver=" + CameraKernelBridge.isPresent()
            textSize = 14f
        }
        column.addView(status)

        column.addView(Button(this).apply {
            text = getString(R.string.master_switch_title)
            setOnClickListener { toggle() }
        })

        column.addView(Button(this).apply {
            text = getString(R.string.switch_camera)
            setOnClickListener {
                camera = if (camera == CameraId.BACK) CameraId.FRONT else CameraId.BACK
                CameraKernelBridge.writeCamera(camera)
                refresh()
            }
        })

        column.addView(Button(this).apply {
            text = getString(R.string.restore_usb)
            setOnClickListener {
                CameraKernelBridge.restoreUsb()
                refresh()
            }
        })

        setContentView(root)
        requestCameraPermission()
        refresh()
    }

    private fun toggle() {
        if (!RootShell.isRootAvailable()) {
            status.text = getString(R.string.root_needed)
            return
        }
        if (!CameraKernelBridge.isPresent()) {
            status.text = getString(R.string.driver_missing)
            return
        }
        val intent = Intent(this, CameraControlService::class.java).apply {
            action = CameraControlService.ACTION_START
            putExtra(CameraControlService.EXTRA_PROFILE, profileId)
            putExtra(CameraControlService.EXTRA_CAMERA, camera.sysfs)
        }
        ContextCompat.startForegroundService(this, intent)
        refresh()
    }

    private fun refresh() {
        if (!CameraKernelBridge.isPresent()) return
        status.text = buildString {
            append("usb=").append(CameraKernelBridge.readUsbState()).append("\n")
            append("fido=").append(CameraKernelBridge.readFidoState()).append("\n")
            append("camera=").append(CameraKernelBridge.readCamera()).append("\n")
            append("profile=").append(CameraKernelBridge.readProfile()).append("\n")
            append("stream=").append(CameraKernelBridge.readStreamState()).append("\n")
            append("error=").append(CameraKernelBridge.readLastError())
        }
    }

    private fun requestCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
        }
    }
}
