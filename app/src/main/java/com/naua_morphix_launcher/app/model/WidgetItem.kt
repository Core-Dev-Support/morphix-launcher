package com.naua_morphix_launcher.app.model

import android.appwidget.AppWidgetProviderInfo
import android.graphics.drawable.Drawable

data class WidgetItem(
    val info: AppWidgetProviderInfo,
    val appLabel: String,
    val widgetLabel: String,
    val icon: Drawable?,
    val preview: Drawable? = null,
    val sizeText: String
)

data class AppWidgetGroup(
    val packageName: String,
    val appLabel: String,
    val appIcon: Drawable?,
    val widgets: List<WidgetItem>,
    var isExpanded: Boolean = false
)
