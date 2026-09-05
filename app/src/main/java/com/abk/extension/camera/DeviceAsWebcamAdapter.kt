package com.abk.extension.camera

import android.content.Context
import android.content.Intent

/**
 * Prefers the system com.android.DeviceAsWebcam package when it can be reached
 * through public intents.  The system service is not modified in place; if no
 * usable entry point is available the coordinator falls back to Camera2.
 */
internal object DeviceAsWebcamAdapter {
    const val PACKAGE = "com.android.DeviceAsWebcam"

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(PACKAGE, 0)
    }.isSuccess

    fun resolveMainActivity(context: Context): Intent? = runCatching {
        val query = Intent(Intent.ACTION_MAIN).setPackage(PACKAGE)
        context.packageManager.queryIntentActivities(query, 0)
            .firstOrNull()
            ?.activityInfo
            ?.let { info ->
                Intent().setClassName(PACKAGE, info.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }.getOrNull()

    fun canControlExternally(): Boolean = false
}
