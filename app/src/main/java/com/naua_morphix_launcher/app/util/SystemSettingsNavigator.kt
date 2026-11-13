package com.naua_morphix_launcher.app.util

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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

    fun openDefaultHome(context: Context): Boolean {
        // 1. Попытка открыть системный RoleManager на API 29+ (ТОЛЬКО через startActivityForResult)
        if (context is Activity && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
                if (roleManager?.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) == true &&
                    !roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
                ) {
                    val roleIntent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)
                    context.startActivityForResult(roleIntent, 1001)
                    return true
                }
            } catch (e: Exception) {
                // Fallback to standard intents
            }
        }

        // 2. Системные и OEM экраны выбора рабочего стола по умолчанию
        return open(
            context,
            Intent(Settings.ACTION_HOME_SETTINGS),
            Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
            Intent().setComponent(ComponentName("com.miui.home", "com.miui.home.launcher.setting.DefaultHomeActivity")),
            Intent().setComponent(ComponentName("com.miui.home", "com.miui.home.launcher.setting.HomeSettingActivity")),
            Intent("miui.intent.action.PREFERRED_APP_SETTINGS"),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$ManageDefaultAppsSettingsActivity")),
            Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$HomeSettingsActivity")),
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")),
            Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.appmanager.AppManagerMainActivity")),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
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
        Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.DeviceAdminSettings")),
        Intent("android.settings.DEVICE_ADMIN_SETTINGS"),
        Intent(Settings.ACTION_SECURITY_SETTINGS),
        Intent(Settings.ACTION_SETTINGS)
    )
}
