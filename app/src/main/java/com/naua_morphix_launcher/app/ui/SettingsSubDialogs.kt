package com.naua_morphix_launcher.app.ui

import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.naua_morphix_launcher.app.R
import com.naua_morphix_launcher.app.databinding.DialogSettingsLockScreenBinding
import com.naua_morphix_launcher.app.databinding.DialogSettingsPermissionsBinding
import com.naua_morphix_launcher.app.databinding.DialogSettingsSecondSpaceBinding
import com.naua_morphix_launcher.app.model.LauncherSettings
import com.naua_morphix_launcher.app.util.PreferencesManager
import com.naua_morphix_launcher.app.databinding.DialogSettingsShapeBinding
import com.naua_morphix_launcher.app.model.IconShape

object SettingsSubDialogs {

    fun showShapeDialog(
        context: Context,
        settings: LauncherSettings,
        onShapeSelected: (IconShape) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val binding = DialogSettingsShapeBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        com.naua_morphix_launcher.app.util.ThemeUtils.applySettingsTheme(binding.root, settings)
        val activityRoot = (context as? android.app.Activity)?.window?.decorView
        if (activityRoot != null) binding.glassSettingsRoot.setupWithRoot(activityRoot)
        binding.glassSettingsRoot.setRadius(settings.blurRadius)
        binding.glassSettingsRoot.setGlassEnabled(settings.isGlassEnabled)

        when (settings.iconShape) {
            IconShape.SQUIRCLE -> binding.radioSquircle.isChecked = true
            IconShape.CIRCLE -> binding.radioCircle.isChecked = true
            IconShape.ROUNDED_SQUARE -> binding.radioRoundedSquare.isChecked = true
            IconShape.TEARDROP -> binding.radioTeardrop.isChecked = true
            IconShape.ORIGINAL -> binding.radioOriginal.isChecked = true
        }

        binding.radioGroupShape.setOnCheckedChangeListener { _, checkedId ->
            val newShape = when (checkedId) {
                R.id.radioSquircle -> IconShape.SQUIRCLE
                R.id.radioCircle -> IconShape.CIRCLE
                R.id.radioRoundedSquare -> IconShape.ROUNDED_SQUARE
                R.id.radioTeardrop -> IconShape.TEARDROP
                else -> IconShape.ORIGINAL
            }
            onShapeSelected(newShape)
            dialog.dismiss()
        }
        
        dialog.show()
    }

    fun showPermissionsDialog(context: Context, settings: LauncherSettings) {
        val dialog = BottomSheetDialog(context)
        val binding = DialogSettingsPermissionsBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        com.naua_morphix_launcher.app.util.ThemeUtils.applySettingsTheme(binding.root, settings)
        val activityRoot2 = (context as? android.app.Activity)?.window?.decorView
        if (activityRoot2 != null) binding.glassSettingsRoot.setupWithRoot(activityRoot2)
        binding.glassSettingsRoot.setRadius(settings.blurRadius)
        binding.glassSettingsRoot.setGlassEnabled(settings.isGlassEnabled)

        fun updateStatus() {
            val isDefault = isDefaultLauncher(context)
            if (isDefault) {
                binding.labelDefaultStatus.text = "Выдано"
                binding.labelDefaultStatus.setTextColor(android.graphics.Color.parseColor("#386A20")) // Green
            } else {
                binding.labelDefaultStatus.text = "Не выдано"
                binding.labelDefaultStatus.setTextColor(android.graphics.Color.parseColor("#B3261E")) // Red
            }
        }

        updateStatus()

        binding.btnDefaultLauncher.setOnClickListener {
            openDefaultLauncherChooser(context)
            dialog.dismiss()
        }

        dialog.show()
    }

    fun showLockScreenDialog(
        context: Context,
        settings: LauncherSettings,
        prefsManager: PreferencesManager,
        onSettingsUpdated: (LauncherSettings) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val binding = DialogSettingsLockScreenBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        com.naua_morphix_launcher.app.util.ThemeUtils.applySettingsTheme(binding.root, settings)
        val activityRoot3 = (context as? android.app.Activity)?.window?.decorView
        if (activityRoot3 != null) binding.glassSettingsRoot.setupWithRoot(activityRoot3)
        binding.glassSettingsRoot.setRadius(settings.blurRadius)
        binding.glassSettingsRoot.setGlassEnabled(settings.isGlassEnabled)

        if (settings.tapsToLockCount == 2) {
            binding.radio2Taps.isChecked = true
        } else {
            binding.radio3Taps.isChecked = true
        }

        binding.radioGroupTaps.setOnCheckedChangeListener { _, checkedId ->
            val updated = settings.copy(
                tapsToLockCount = if (checkedId == R.id.radio3Taps) 3 else 2
            )
            prefsManager.saveSettings(updated)
            onSettingsUpdated(updated)
        }

        fun updateStatuses() {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val compName = ComponentName(context, "com.naua_morphix_launcher.app.util.LockScreenAdminReceiver")
            val isAdmin = dpm.isAdminActive(compName)

            if (isAdmin) {
                binding.labelDeviceAdminStatus.text = "Выдано"
                binding.labelDeviceAdminStatus.setTextColor(android.graphics.Color.parseColor("#386A20"))
            } else {
                binding.labelDeviceAdminStatus.text = "Не выдано"
                binding.labelDeviceAdminStatus.setTextColor(android.graphics.Color.parseColor("#B3261E"))
            }

            // Accessibility Check
            val isAccessibilityEnabled = isAccessibilityServiceEnabled(context)
            if (isAccessibilityEnabled) {
                binding.labelAccessibilityStatus.text = "Выдано"
                binding.labelAccessibilityStatus.setTextColor(android.graphics.Color.parseColor("#386A20"))
            } else {
                binding.labelAccessibilityStatus.text = "Не выдано"
                binding.labelAccessibilityStatus.setTextColor(android.graphics.Color.parseColor("#B3261E"))
            }
        }

        updateStatuses()

        binding.btnDeviceAdmin.setOnClickListener {
            try {
                val compName = ComponentName(context, "com.naua_morphix_launcher.app.util.LockScreenAdminReceiver")
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
                intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Разрешение требуется для двойного тапа по экрану")
                context.startActivity(intent)
                dialog.dismiss()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        binding.btnAccessibility.setOnClickListener {
            try {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                dialog.dismiss()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        dialog.show()
    }

    fun showSecondSpaceDialog(context: Context, settings: LauncherSettings, prefsManager: PreferencesManager) {
        val dialog = BottomSheetDialog(context)
        val binding = DialogSettingsSecondSpaceBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        com.naua_morphix_launcher.app.util.ThemeUtils.applySettingsTheme(binding.root, settings)
        val activityRoot4 = (context as? android.app.Activity)?.window?.decorView
        if (activityRoot4 != null) binding.glassSettingsRoot.setupWithRoot(activityRoot4)
        binding.glassSettingsRoot.setRadius(settings.blurRadius)
        binding.glassSettingsRoot.setGlassEnabled(settings.isGlassEnabled)

        binding.switchEnableProtection.isChecked = prefsManager.isSecondSpaceProtected()

        binding.switchEnableProtection.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.setSecondSpaceProtected(isChecked)
        }

        binding.btnSystemSecuritySettings.setOnClickListener {
            try {
                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                dialog.dismiss()
            } catch (e: Exception) {
                Toast.makeText(context, "Откройте Настройки -> Безопасность", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun isDefaultLauncher(context: Context): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveActivity(
                intent,
                android.content.pm.PackageManager.ResolveInfoFlags.of(android.content.pm.PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolveInfo?.activityInfo?.packageName == context.packageName
    }

    private fun openDefaultLauncherChooser(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
            if (roleManager?.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) == true &&
                !roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
            ) {
                val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)
                try {
                    context.startActivity(intent)
                    return
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        try {
            context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(context, "Откройте Настройки -> Приложения по умолчанию", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val accessibilityEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED, 0
        )
        if (accessibilityEnabled == 1) {
            val services = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (services != null) {
                return services.contains(context.packageName)
            }
        }
        return false
    }
}
