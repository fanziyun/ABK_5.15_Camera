package com.abk.extension.camera

import android.util.Log

/**
 * Runtime USB coordination.  This device uses sys.usb.configfs=2, which means
 * the Android framework (UsbDeviceManager) owns the gadget.  Writing configfs
 * symlinks by hand races with the framework, so on configfs=2 we ask the
 * framework to mount UVC through the official service call instead.
 *
 * configfs=2 -> svc usb setFunctions uvc (framework mounts UVC via ConfigFS)
 * configfs=1 -> setprop sys.usb.config diag,uvc,adb (vendor Qualcomm path)
 */
internal object UsbCoordinator {
    private const val TAG = "AbkUsbCoordinator"
    private const val UDC = "/config/usb_gadget/g1/UDC"
    private const val CONFIG = "/config/usb_gadget/g1/configs/b.1"

    fun readOriginalConfig(): String =
        RootShell.run("getprop persist.sys.usb.config").stdout.trim().ifBlank { "adb" }

    fun readCurrentConfig(): String =
        RootShell.run("getprop sys.usb.config").stdout.trim()

    fun readConfigfsMode(): String =
        RootShell.run("getprop sys.usb.configfs").stdout.trim().ifBlank { "2" }

    fun readUdc(): String = RootShell.readTextFile(UDC).stdout.trim()

    fun readCurrentSpeed(): String =
        RootShell.run("cat /sys/class/udc/\$(cat $UDC)/current_speed 2>/dev/null").stdout.trim()

    fun setUsbConfig(composite: String): RootShell.CommandResult =
        RootShell.run("setprop sys.usb.config " + composite + " && sleep 1")

    fun restoreUsbConfig(original: String): RootShell.CommandResult {
        if (readConfigfsMode() == "2") {
            return RootShell.run("svc usb setFunctions mtp && sleep 1")
        }
        val safe = if (original.isBlank()) "adb" else original
        return RootShell.run("setprop sys.usb.config " + safe + " && sleep 1")
    }

    fun hasFunctionLink(name: String): Boolean {
        val result = RootShell.run("ls -l " + CONFIG + "/ 2>/dev/null | grep -q '" + name + "'")
        return result.success
    }

    fun isFidoPresent(): Boolean =
        RootShell.run("[ -d /sys/kernel/abk_fido_key ]").success

    fun isFidoOnline(): Boolean {
        val result = RootShell.run("cat /sys/kernel/abk_fido_key/attach_state 2>/dev/null")
        return result.success && result.stdout.contains("online")
    }

    fun isUvcLinked(): Boolean = hasFunctionLink("uvc")
    fun isMtpLinked(): Boolean = hasFunctionLink("mtp")
    fun isAdbLinked(): Boolean = hasFunctionLink("adb")

    fun applyPriority(original: String): UsbCoordinatorState {
        if (!CameraKernelBridge.isPresent()) {
            return UsbCoordinatorState(false, false, "kernel_driver_missing")
        }

        val mode = readConfigfsMode()
        val linked = if (mode == "2") applyFrameworkUvc() else applyVendorUvc()

        if (!linked || !isUvcLinked()) {
            Log.w(TAG, "UVC did not materialize on configfs=$mode; restoring original")
            restoreUsbConfig(original)
            return UsbCoordinatorState(false, false, "uvc_failed")
        }

        val fido = isFidoPresent()
        val suffix = if (fido) "uvc_fido" else "uvc"
        return UsbCoordinatorState(true, isMtpLinked(), suffix + "_adb")
    }

    private fun applyFrameworkUvc(): Boolean {
        // Official Android 14+ DeviceAsWebcam control path.  This mounts the
        // UVC function through the USB Gadget HAL without racing the framework.
        val viaSvc = RootShell.run("svc usb setFunctions uvc && sleep 2")
        if (viaSvc.success && isUvcLinked()) return true

        // Fallback: direct property write (kept for devices that accept it).
        val viaProp = setUsbConfig("uvc")
        return viaProp.success && isUvcLinked()
    }

    private fun applyVendorUvc(): Boolean {
        val result = setUsbConfig("diag,uvc,adb")
        return result.success && isUvcLinked() && isAdbLinked()
    }
}

data class UsbCoordinatorState(
    val uvcOnline: Boolean,
    val mtpOnline: Boolean,
    val state: String,
)
