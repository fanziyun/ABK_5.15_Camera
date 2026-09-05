package com.abk.extension.camera

import android.util.Log

internal object CameraKernelBridge {
    private const val TAG = "AbkCameraBridge"
    const val SYSFS_BASE = "/sys/kernel/abk_uvc_camera"

    const val ENABLED = "$SYSFS_BASE/enabled"
    const val MODE = "$SYSFS_BASE/mode"
    const val CAMERA = "$SYSFS_BASE/camera"
    const val PROFILE = "$SYSFS_BASE/profile"
    const val BACKEND = "$SYSFS_BASE/backend"
    const val STREAM_STATE = "$SYSFS_BASE/stream_state"
    const val USB_STATE = "$SYSFS_BASE/usb_state"
    const val FIDO_STATE = "$SYSFS_BASE/fido_state"
    const val SUPPORTED_PROFILES = "$SYSFS_BASE/supported_profiles"
    const val LAST_ERROR = "$SYSFS_BASE/last_error"
    const val LAST_TRACE = "$SYSFS_BASE/last_trace"
    const val COMMAND = "$SYSFS_BASE/command"

    fun isPresent(): Boolean = RootShell.run("[ -d $SYSFS_BASE ]").success

    fun readEnabled(): Boolean? = readInt(ENABLED)?.let { it == 1 }
    fun writeEnabled(value: Boolean) = RootShell.writeTextFile(ENABLED, if (value) "1\n" else "0\n")

    fun readCamera(): String = readText(CAMERA).trim().ifBlank { "back" }
    fun writeCamera(value: CameraId) = RootShell.writeTextFile(CAMERA, value.sysfs + "\n")

    fun readProfile(): String = readText(PROFILE).trim()
    fun writeProfile(id: String) = RootShell.writeTextFile(PROFILE, id + "\n")

    fun readBackend(): String = readText(BACKEND).trim()
    fun writeBackend(value: String) = RootShell.writeTextFile(BACKEND, value + "\n")

    fun readStreamState(): String = readText(STREAM_STATE).trim()
    fun readUsbState(): String = readText(USB_STATE).trim()
    fun readFidoState(): String = readText(FIDO_STATE).trim()
    fun readLastError(): String = readText(LAST_ERROR).trim()
    fun readLastTrace(): String = readText(LAST_TRACE).trim()
    fun readSupportedProfiles(): String = readText(SUPPORTED_PROFILES)

    fun command(value: String): RootShell.CommandResult = RootShell.writeTextFile(COMMAND, value + "\n")
    fun start() = command("start")
    fun stop() = command("stop")
    fun restoreUsb() = command("restore_usb")
    fun testPattern(on: Boolean) = command(if (on) "test_pattern on" else "test_pattern off")

    private fun readText(path: String): String {
        val result = RootShell.readTextFile(path)
        if (!result.success) Log.w(TAG, "read $path failed: ${result.stdout}")
        return result.stdout
    }

    private fun readInt(path: String): Int? = readText(path).trim().toIntOrNull()
}
