package com.abk.extension.camera

import android.util.Log

/**
 * Coordinates the Android USB gadget at runtime.  The camera works standalone;
 * FIDO is optional.  When FIDO is present the kernel configfs hook auto-attaches
 * it, so the coordinator only needs to keep the vendor UVC+ADB composite stable.
 *
 * Priority:
 *   UVC + ADB -> try MTP -> MTP failure keeps UVC + ADB.
 *   A missing/offline FIDO no longer blocks the camera.
 */
internal object UsbCoordinator {
    private const val TAG = "AbkUsbCoordinator"

    private const val UDC = "/config/usb_gadget/g1/UDC"
    private const val CONFIG = "/config/usb_gadget/g1/configs/b.1"

    // Vendor init.qcom.usb.rc defines this composite.  FIDO, when compiled in,
    // is injected automatically by the kernel configfs hook.
    private const val UVC_ADB_COMPOSITE = "diag,uvc,adb"

    fun readOriginalConfig(): String =
        RootShell.run("getprop persist.sys.usb.config").stdout.trim().ifBlank { "adb" }

    fun readCurrentConfig(): String =
        RootShell.run("getprop sys.usb.config").stdout.trim()

    fun readUdc(): String = RootShell.readTextFile(UDC).stdout.trim()

    fun readCurrentSpeed(): String =
        RootShell.run("cat /sys/class/udc/\$(cat $UDC)/current_speed 2>/dev/null").stdout.trim()

    fun setUsbConfig(composite: String): RootShell.CommandResult =
        RootShell.run("setprop sys.usb.config " + composite + " && sleep 1")

    fun restoreUsbConfig(original: String): RootShell.CommandResult {
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

        val baseline = setUsbConfig(UVC_ADB_COMPOSITE)
        if (!baseline.success || !isUvcLinked() || !isAdbLinked()) {
            Log.w(TAG, "UVC+ADB composite did not materialize; restoring original")
            restoreUsbConfig(original)
            return UsbCoordinatorState(false, false, "uvc_adb_failed")
        }

        val mtpLinked = tryEnableMtp()
        val stable = mtpLinked && isMtpLinked() && isUvcLinked() && isAdbLinked()
        if (mtpLinked && !stable) {
            disableMtp()
            setUsbConfig(UVC_ADB_COMPOSITE)
        }

        val fido = isFidoPresent()
        val suffix = if (fido) "uvc_fido" else "uvc"
        return if (stable) {
            UsbCoordinatorState(true, true, suffix + "_mtp_adb")
        } else {
            UsbCoordinatorState(true, false, suffix + "_adb")
        }
    }

    private fun tryEnableMtp(): Boolean {
        val script = """
            set -e
            base=/config/usb_gadget/g1
            cfg="${'$'}base/configs/b.1"
            udc="${'$'}(cat "${'$'}base/UDC" 2>/dev/null || true)"
            echo "" > "${'$'}base/UDC" 2>/dev/null || true
            i=0
            while [ -e "${'$'}cfg/function${'$'}i" ]; do i=${'$'}((i + 1)); done
            ln -s ../../../../usb_gadget/g1/functions/ffs.mtp "${'$'}cfg/function${'$'}i"
            if [ -n "${'$'}udc" ]; then echo "${'$'}udc" > "${'$'}base/UDC"; fi
        """.trimIndent()
        val result = RootShell.run(script, timeoutSeconds = 8L)
        return result.success && isMtpLinked()
    }

    private fun disableMtp() {
        RootShell.run("rm -f /config/usb_gadget/g1/configs/b.1/function*ffs.mtp 2>/dev/null; true")
    }
}

data class UsbCoordinatorState(
    val uvcOnline: Boolean,
    val mtpOnline: Boolean,
    val state: String,
)
