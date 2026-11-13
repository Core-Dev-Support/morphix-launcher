package com.naua_morphix_launcher.app.util

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/** Opens Android settings with public platform fallbacks for OEM devices. */
object SystemSettingsNavigator {
    fun open(context: Context, vararg candidates: Intent): Boolean {
        for (candidate in candidates) {
            try {
                candidate.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (candidate.resolveActivity(context.packageManager) != null) {
                    context.startActivity(candidate)
                    return true
                }
            } catch (e: Exception) {
                // Try the next public Android/OEM-compatible activity.
            }
        }
        return false
    }

    fun openWallpaper(context: Context): Boolean = open(
        context,
        Intent(Intent.ACTION_SET_WALLPAPER),
        Intent(Settings.ACTION_DISPLAY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )

    fun openAppDetails(context: Context, packageName: String): Boolean = open(
        context,
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        },
        Intent(Settings.ACTION_APPLICATION_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )

    fun openAccessibility(context: Context): Boolean = open(
        context,
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )

    fun openSecurity(context: Context): Boolean = open(
        context,
        Intent(Settings.ACTION_SECURITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )

    fun openDeviceAdmin(context: Context, component: ComponentName, explanation: String): Boolean = open(
        context,
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)
        },
        Intent(Settings.ACTION_SECURITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
}
