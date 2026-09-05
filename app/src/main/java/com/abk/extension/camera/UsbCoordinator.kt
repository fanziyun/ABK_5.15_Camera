package com.abk.extension.camera

import android.util.Log

/**
 * Coordinates the Android USB gadget at runtime without modifying the vendor
 * init.rc.  The fixed priority is:
 *
 * FIDO online -> UVC + ADB (vendor composite, FIDO auto-attaches) ->
 * try MTP -> MTP failure keeps UVC + ADB -> FIDO failure restores original.
 *
 * Unlike the previous implementation this never trusts `setprop` alone:
 * every step verifies the actual configfs links before reporting success.
 */
internal object UsbCoordinator {
    private const val TAG = "AbkUsbCoordinator"

    private const val GADGET = "/config/usb_gadget/g1"
    private const val UDC = "/config/usb_gadget/g1/UDC"
    private const val CONFIG = "/config/usb_gadget/g1/configs/b.1"

    // The vendor init.qcom.usb.rc defines this composite and sets up the
    // functionfs ADB daemon + UVC function instance.  FIDO is injected by the
    // kernel hook, so the effective device is diag + UVC + ADB + FIDO.
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

    fun isFidoOnline(): Boolean {
        val result = RootShell.run("cat /sys/kernel/abk_fido_key/attach_state 2>/dev/null")
        return result.success && result.stdout.contains("online")
    }

    fun isUvcLinked(): Boolean = hasFunctionLink("uvc")
    fun isMtpLinked(): Boolean = hasFunctionLink("mtp")
    fun isAdbLinked(): Boolean = hasFunctionLink("adb")

    /**
     * Applies the USB priority and verifies configfs state.  Returns the
     * actual achieved mode rather than assuming `setprop` succeeded.
     */
    fun applyPriority(original: String): UsbCoordinatorState {
        if (!CameraKernelBridge.isPresent()) {
            return UsbCoordinatorState(false, false, "kernel_driver_missing")
        }
        if (!isFidoOnline()) {
            Log.w(TAG, "FIDO is offline; restoring original USB config")
            restoreUsbConfig(original)
            return UsbCoordinatorState(false, false, "fido_offline_restored")
        }

        val baseline = setUsbConfig(UVC_ADB_COMPOSITE)
        if (!baseline.success || !isUvcLinked() || !isAdbLinked()) {
            Log.w(TAG, "UVC+ADB composite did not materialize; restoring original")
            restoreUsbConfig(original)
            return UsbCoordinatorState(false, false, "uvc_adb_failed")
        }

        val mtpLinked = tryEnableMtp()
        return if (mtpLinked && isMtpLinked() && isUvcLinked() && isAdbLinked()) {
            UsbCoordinatorState(true, true, "uvc_fido_mtp_adb")
        } else {
            if (mtpLinked) {
                // MTP link was created but another function dropped out.
                disableMtp()
                setUsbConfig(UVC_ADB_COMPOSITE)
            }
            UsbCoordinatorState(true, false, "uvc_fido_adb")
        }
    }

    /**
     * Best-effort MTP attach using direct configfs links.  MTP+UVC is not a
     * vendor composite, so this unbinds the UDC, links ffs.mtp, and rebinds.
     * Any failure leaves the caller with the stable UVC+ADB baseline.
     */
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
