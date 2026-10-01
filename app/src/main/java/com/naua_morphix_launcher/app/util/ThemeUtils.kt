package com.naua_morphix_launcher.app.util

import android.app.Activity
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.naua_morphix_launcher.app.R
import com.naua_morphix_launcher.app.model.LauncherSettings

object ThemeUtils {

    /**
     * Рекурсивно применяет тему (Glass / Material3) ко всем элементам view-дерева.
     */
    fun applySettingsTheme(view: View, settings: LauncherSettings) {
        val isGlass = settings.isGlassEnabled

        val tName = view.transitionName

        if (tName == "surface") {
            view.setBackgroundResource(if (isGlass) R.drawable.glass_drawer_background else R.drawable.bg_settings_dialog_surface)
        } else if (tName == "card") {
            view.setBackgroundResource(if (isGlass) R.drawable.glass_group_card_background else R.drawable.bg_settings_card)
        } else if (tName == "iconBg") {
            view.setBackgroundResource(if (isGlass) R.drawable.glass_subitem_background else R.drawable.bg_settings_icon)
        } else if (tName == "textTitle" && view is TextView) {
            view.setTextColor(Color.WHITE)
        } else if (tName == "textSub" && view is TextView) {
            view.setTextColor(Color.parseColor("#CCFFFFFF"))
        }

        // Apply glass effect to material buttons
        if (view is com.google.android.material.button.MaterialButton) {
            if (isGlass) {
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#40FFFFFF"))
            } else {
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
            }
            view.setTextColor(Color.WHITE)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applySettingsTheme(view.getChildAt(i), settings)
            }
        }
    }

}
