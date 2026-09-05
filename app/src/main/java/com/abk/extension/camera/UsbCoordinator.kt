package com.abk.extension.camera

import android.util.Log

/**
 * Runtime USB coordination.
 *
 * This device uses sys.usb.configfs=2, so the Android framework owns the
 * gadget.  The framework Gadget HAL (V1.1) cannot mount UVC itself, and
 * `svc usb setFunctions uvc` leaves the gadget in an ADB-only state.
 *
 * Instead the kernel module injects the vendor-preconfigured uvc.0 function
 * during the configfs bind path.  The app only enables/disables that kernel
 * flag and then forces a harmless framework function toggle to trigger a
 * rebind:
 *
 *     svc usb setFunctions none -> svc usb setFunctions mtp
 *
 * The final config is MTP + ADB + kernel-injected UVC.
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
            return triggerRebind()
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

    fun isUvcConfigured(): Boolean =
        CameraKernelBridge.readUsbState().trim() == "uvc_configured"

    fun isMtpLinked(): Boolean = hasFunctionLink("mtp")
    fun isAdbLinked(): Boolean = hasFunctionLink("adb")

    fun applyPriority(original: String): UsbCoordinatorState {
        if (!CameraKernelBridge.isPresent()) {
            return UsbCoordinatorState(false, false, "kernel_driver_missing")
        }

        val mode = readConfigfsMode()
        val uvcOnline = if (mode == "2") applyFrameworkUvc() else applyVendorUvc()

        if (!uvcOnline) {
            Log.w(TAG, "UVC did not come up on configfs=$mode; restoring USB")
            restoreUsbConfig(original)
            return UsbCoordinatorState(false, false, "uvc_failed")
        }

        val mtp = isMtpLinked()
        val adb = isAdbLinked()
        val state = when {
            mtp && adb -> "uvc_mtp_adb"
            adb -> "uvc_adb"
            else -> "uvc"
        }
        return UsbCoordinatorState(true, mtp, state)
    }

    private fun triggerRebind(): RootShell.CommandResult =
        RootShell.run("svc usb setFunctions none && sleep 1 && svc usb setFunctions mtp && sleep 2")

    private fun applyFrameworkUvc(): Boolean {
        val result = triggerRebind()
        if (!result.success) {
            Log.w(TAG, "framework rebind failed")
            return false
        }
        return isUvcConfigured()
    }

    private fun applyVendorUvc(): Boolean {
        val result = setUsbConfig("diag,uvc,adb")
        return result.success && isUvcConfigured() && isAdbLinked()
    }
}

data class UsbCoordinatorState(
    val uvcOnline: Boolean,
    val mtpOnline: Boolean,
    val state: String,
)
