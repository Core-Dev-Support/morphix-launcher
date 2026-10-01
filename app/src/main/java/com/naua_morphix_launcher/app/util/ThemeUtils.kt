package com.naua_morphix_launcher.app.util

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.button.MaterialButton
import com.naua_morphix_launcher.app.R
import com.naua_morphix_launcher.app.model.LauncherSettings

object ThemeUtils {

    // Цвета вынесены в константы: Color.parseColor в цикле по дереву —
    // это десятки аллокаций на каждое открытие диалога.
    private val SUBTITLE_COLOR = Color.parseColor("#CCFFFFFF")
    private val GLASS_BUTTON_TINT = ColorStateList.valueOf(Color.parseColor("#40FFFFFF"))
    private val SOLID_BUTTON_TINT = ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))

    /** Прототипы drawable-ов: декодировать ресурс каждый раз дорого. */
    private val drawableProtos = HashMap<Int, Drawable>()

    private fun backgroundFor(view: View, resId: Int): Drawable {
        val proto = drawableProtos.getOrPut(resId) {
            AppCompatResources.getDrawable(view.context, resId)
                ?: error("Drawable not found for resource $resId")
        }
        // newDrawable().mutate() — иначе все view разделяли бы один Drawable
        // и его состояние (например, bounds) лазило бы между ними.
        return proto.constantState?.newDrawable(view.resources)?.mutate() ?: proto
    }

    /**
     * Рекурсивно применяет тему (Glass / Material3) ко всем элементам view-дерева.
     */
    fun applySettingsTheme(view: View, settings: LauncherSettings) {
        applySettingsThemeInternal(view, settings.isGlassEnabled, HashMap())
    }

    /**
     * [seen] защищает от повторной установки того же фона на ту же вьюху —
     * раньше setBackgroundResource вызывался на каждом узле при каждом
     * переключении тумблера в настройках.
     */
    private fun applySettingsThemeInternal(view: View, isGlass: Boolean, seen: HashMap<View, Int>) {
        val tName = view.transitionName

        val bgRes = when (tName) {
            "surface" -> if (isGlass) R.drawable.glass_drawer_background else R.drawable.bg_settings_dialog_surface
            "card" -> if (isGlass) R.drawable.glass_group_card_background else R.drawable.bg_settings_card
            "iconBg" -> if (isGlass) R.drawable.glass_subitem_background else R.drawable.bg_settings_icon
            else -> 0
        }
        if (bgRes != 0 && seen[view] != bgRes) {
            seen[view] = bgRes
            view.background = backgroundFor(view, bgRes)
        }

        when (tName) {
            "textTitle" -> if (view is TextView) view.setTextColor(Color.WHITE)
            "textSub" -> if (view is TextView) view.setTextColor(SUBTITLE_COLOR)
        }

        if (view is MaterialButton) {
            view.backgroundTintList = if (isGlass) GLASS_BUTTON_TINT else SOLID_BUTTON_TINT
            view.setTextColor(Color.WHITE)
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                applySettingsThemeInternal(view.getChildAt(i), isGlass, seen)
            }
        }
    }
}