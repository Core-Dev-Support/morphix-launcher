package com.naua_morphix_launcher.app.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.naua_morphix_launcher.app.R
import com.naua_morphix_launcher.app.databinding.DialogMorphixSettingsBinding
import com.naua_morphix_launcher.app.model.DockStyle
import com.naua_morphix_launcher.app.model.IconShape
import com.naua_morphix_launcher.app.model.LauncherSettings
import com.naua_morphix_launcher.app.model.LayoutMode
import com.naua_morphix_launcher.app.util.PreferencesManager
import com.naua_morphix_launcher.app.util.ThemeUtils
import kotlin.math.roundToInt

class SettingsDialog(
    private val context: Context,
    private val onSwitchSpaceRequested: (() -> Unit)? = null,
    private val onSettingsUpdated: (LauncherSettings) -> Unit
) {
    private val dialog = BottomSheetDialog(context)
    private val binding: DialogMorphixSettingsBinding
    private val prefsManager = PreferencesManager(context)
    private var settings: LauncherSettings = prefsManager.loadSettings()
    private val initialSettings: LauncherSettings = settings
    private var isExplicitlySaved: Boolean = false

    init {
        binding = DialogMorphixSettingsBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(binding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        ThemeUtils.applySettingsTheme(binding.root, settings)
        
        // Use the ACTIVITY's decor view as glass root, NOT the dialog's.
        // BottomSheetDialog has its own window/decorView, so setupWithActivityRoot()
        // would capture the dialog's own content recursively.
        val activityRoot = (context as? android.app.Activity)?.window?.decorView
        if (activityRoot != null) {
            binding.glassSettingsRoot.setupWithRoot(activityRoot)
        }
        binding.glassSettingsRoot.setRadius(settings.blurRadius)
        binding.glassSettingsRoot.setGlassEnabled(settings.isGlassEnabled)

        dialog.setOnDismissListener {
            if (!isExplicitlySaved) {
                val updated = buildUpdatedSettings()
                if (updated != initialSettings) {
                    prefsManager.saveSettings(updated)
                    onSettingsUpdated(updated)
                }
            }
        }
        
        setupUI()
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener {
            dialog.dismiss()
        }

        // Оформление
        binding.switchGlass.isChecked = settings.isGlassEnabled

        binding.switchGlass.setOnCheckedChangeListener { _, isChecked ->
            settings = settings.copy(isGlassEnabled = isChecked)
            ThemeUtils.applySettingsTheme(binding.root, settings)
            binding.glassSettingsRoot.setGlassEnabled(isChecked)
        }


        // Рабочий стол
        if (settings.layoutMode == LayoutMode.CLASSIC) {
            binding.radioModeClassic.isChecked = true
            binding.labelLayoutModeStatus.text = "Классический"
        } else {
            binding.radioModeDrawer.isChecked = true
            binding.labelLayoutModeStatus.text = "Меню приложений"
        }
        binding.radioGroupLayoutMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.radioModeClassic) {
                binding.labelLayoutModeStatus.text = "Классический"
            } else {
                binding.labelLayoutModeStatus.text = "Меню приложений"
            }
        }
        binding.btnExpandLayoutMode.setOnClickListener {
            val isGone = binding.layoutLayoutModeOptions.visibility == android.view.View.GONE
            if (settings.smoothAnimations) {
                android.transition.TransitionManager.beginDelayedTransition(
                    binding.root as? android.view.ViewGroup ?: binding.layoutLayoutModeOptions.parent as android.view.ViewGroup,
                    android.transition.AutoTransition().apply {
                        duration = 220
                        interpolator = android.view.animation.DecelerateInterpolator()
                    }
                )
                binding.layoutLayoutModeOptions.visibility = if (isGone) android.view.View.VISIBLE else android.view.View.GONE
                binding.iconExpandLayoutMode.animate()
                    .rotation(if (isGone) 90f else 0f)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            } else {
                binding.layoutLayoutModeOptions.visibility = if (isGone) android.view.View.VISIBLE else android.view.View.GONE
                binding.iconExpandLayoutMode.rotation = if (isGone) 90f else 0f
            }
        }

        val safeColumns = settings.gridColumns.coerceIn(3, 10)
        val safeRows = settings.gridRows.coerceIn(4, 10)
        try {
            binding.sliderGridColumns.value = safeColumns.toFloat()
            binding.sliderGridRows.value = safeRows.toFloat()
        } catch (e: Exception) {
            binding.sliderGridColumns.value = 4f
            binding.sliderGridRows.value = 6f
        }
        binding.labelGridColumns.text = "Колонок: $safeColumns"
        binding.labelGridRows.text = "Рядов: $safeRows"
        binding.labelGridStatus.text = "$safeColumns × $safeRows"
        
        binding.sliderGridColumns.addOnChangeListener { _, value, _ ->
            binding.labelGridColumns.text = "Колонок: ${value.toInt()}"
            binding.labelGridStatus.text = "${value.toInt()} × ${binding.sliderGridRows.value.toInt()}"
        }
        binding.sliderGridRows.addOnChangeListener { _, value, _ ->
            binding.labelGridRows.text = "Рядов: ${value.toInt()}"
            binding.labelGridStatus.text = "${binding.sliderGridColumns.value.toInt()} × ${value.toInt()}"
        }
        binding.btnExpandGrid.setOnClickListener {
            val isGone = binding.layoutGridSliders.visibility == android.view.View.GONE
            if (settings.smoothAnimations) {
                android.transition.TransitionManager.beginDelayedTransition(
                    binding.root as? android.view.ViewGroup ?: binding.layoutGridSliders.parent as android.view.ViewGroup,
                    android.transition.AutoTransition().apply {
                        duration = 220
                        interpolator = android.view.animation.DecelerateInterpolator()
                    }
                )
                binding.layoutGridSliders.visibility = if (isGone) android.view.View.VISIBLE else android.view.View.GONE
                binding.iconExpandGrid.animate()
                    .rotation(if (isGone) 90f else 0f)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            } else {
                binding.layoutGridSliders.visibility = if (isGone) android.view.View.VISIBLE else android.view.View.GONE
                binding.iconExpandGrid.rotation = if (isGone) 90f else 0f
            }
        }

        binding.switchSmoothAnimations.isChecked = settings.smoothAnimations
        binding.switchSmoothAnimations.setOnCheckedChangeListener { _, isChecked ->
            settings = settings.copy(smoothAnimations = isChecked)
        }

        // Значки
        binding.switchLabels.isChecked = settings.showLabels
        binding.switchLabels.setOnCheckedChangeListener { _, isChecked ->
            settings = settings.copy(showLabels = isChecked)
        }

        val currentScalePct = ((settings.iconScale * 100f).roundToInt() / 5 * 5).coerceIn(80, 130)
        try {
            binding.sliderIconScale.value = currentScalePct.toFloat()
        } catch (e: Exception) {
            binding.sliderIconScale.value = 100f
        }
        binding.labelIconScale.text = "Размер значков: $currentScalePct%"
        binding.sliderIconScale.addOnChangeListener { _, value, _ ->
            binding.labelIconScale.text = "Размер значков: ${value.toInt()}%"
        }

        fun updateShapeStatus(shape: IconShape) {
            binding.labelShapeStatus.text = when(shape) {
                IconShape.SQUIRCLE -> "Squircle"
                IconShape.CIRCLE -> "Circle"
                IconShape.ROUNDED_SQUARE -> "Rounded Square"
                IconShape.TEARDROP -> "Teardrop"
                IconShape.ORIGINAL -> "Original"
            }
        }
        updateShapeStatus(settings.iconShape)
        
        binding.btnOpenShapeDialog.setOnClickListener {
            SettingsSubDialogs.showShapeDialog(context, settings) { newShape ->
                settings = settings.copy(iconShape = newShape)
                updateShapeStatus(newShape)
            }
        }

        // Док
        binding.switchDock.isChecked = settings.isDockEnabled
        binding.radioDockFloating.isChecked = settings.dockStyle != DockStyle.FULL_WIDTH
        binding.radioDockFullWidth.isChecked = settings.dockStyle == DockStyle.FULL_WIDTH
        try {
            binding.sliderDockIconCount.value = settings.dockIconCount.coerceIn(0, 7).toFloat()
        } catch (e: IllegalStateException) {
            // значение вне диапазона слайдера — оставляем дефолт
            binding.sliderDockIconCount.value = 5f
        }
        binding.labelDockIconCount.text = "Иконок в доке: ${binding.sliderDockIconCount.value.toInt()}"
        binding.sliderDockIconCount.addOnChangeListener { _, value, _ ->
            binding.labelDockIconCount.text = "Иконок в доке: ${value.toInt()}"
        }
        binding.switchDock.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutDockOptions.visibility =
                if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }
        binding.layoutDockOptions.visibility =
            if (settings.isDockEnabled) android.view.View.VISIBLE else android.view.View.GONE

        // Жесты
        binding.labelLockScreenStatus.text = "${settings.tapsToLockCount} тапа"
        binding.btnOpenLockScreenDialog.setOnClickListener {
            SettingsSubDialogs.showLockScreenDialog(context, settings, prefsManager) { updated ->
                settings = updated
                binding.labelLockScreenStatus.text = "${settings.tapsToLockCount} тапа"
            }
        }

        // Система и безопасность
        binding.btnOpenSecondSpaceDialog.setOnClickListener {
            SettingsSubDialogs.showSecondSpaceDialog(context, settings, prefsManager)
        }

        binding.btnOpenPermissionsDialog.setOnClickListener {
            SettingsSubDialogs.showPermissionsDialog(context, settings)
        }

        // Применить настройки
        binding.btnSaveSettings.setOnClickListener {
            val updated = buildUpdatedSettings()
            prefsManager.saveSettings(updated)
            onSettingsUpdated(updated)
            isExplicitlySaved = true
            dialog.dismiss()
        }

        attachTouchFeedback(
            binding.btnBack,
            binding.btnSaveSettings,
            binding.btnOpenShapeDialog,
            binding.btnOpenLockScreenDialog,
            binding.btnOpenSecondSpaceDialog,
            binding.btnOpenPermissionsDialog,
            binding.btnExpandLayoutMode,
            binding.btnExpandGrid
        )
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachTouchFeedback(vararg views: android.view.View) {
        for (v in views) {
            v.setOnTouchListener { view, event ->
                if (!settings.smoothAnimations) {
                    view.scaleX = 1.0f
                    view.scaleY = 1.0f
                    return@setOnTouchListener false
                }
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        view.animate()
                            .scaleX(0.96f)
                            .scaleY(0.96f)
                            .setDuration(90)
                            .start()
                    }
                    android.view.MotionEvent.ACTION_UP,
                    android.view.MotionEvent.ACTION_CANCEL -> {
                        view.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(160)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
                            .start()
                    }
                }
                false
            }
        }
    }

    private fun buildUpdatedSettings(): LauncherSettings {
        val selectedLayoutMode = if (binding.radioModeClassic.isChecked) {
            LayoutMode.CLASSIC
        } else {
            LayoutMode.DRAWER
        }

        val selectedDockStyle = if (binding.radioDockFullWidth.isChecked) {
            DockStyle.FULL_WIDTH
        } else {
            DockStyle.FLOATING
        }

        return settings.copy(
            isGlassEnabled = binding.switchGlass.isChecked,
            blurRadius = 12f,
            iconScale = binding.sliderIconScale.value / 100f,
            showLabels = binding.switchLabels.isChecked,
            layoutMode = selectedLayoutMode,
            gridColumns = binding.sliderGridColumns.value.toInt(),
            gridRows = binding.sliderGridRows.value.toInt(),
            isDockEnabled = binding.switchDock.isChecked,
            dockStyle = selectedDockStyle,
            dockIconCount = binding.sliderDockIconCount.value.toInt(),
            smoothAnimations = binding.switchSmoothAnimations.isChecked
        )
    }

    fun show() {
        dialog.show()
    }

    fun dismiss() {
        dialog.dismiss()
    }
}


