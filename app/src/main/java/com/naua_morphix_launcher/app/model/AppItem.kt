package com.naua_morphix_launcher.app.model

import android.graphics.drawable.Drawable
import android.os.UserHandle

data class AppItem(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable?,
    val userHandle: UserHandle? = null,
    val isHidden: Boolean = false,
    val isSecondSpace: Boolean = false,
    val category: String = "Все",
    val isFolder: Boolean = false,
    val folderId: String? = null,
    val folderApps: List<AppItem> = emptyList(),
    val folderSize: String = "REGULAR",
    val isWidget: Boolean = false,
    val widgetId: Int? = null,
    val widgetSpanX: Int = 4,
    val widgetSpanY: Int = 2
) {
    val isEmpty: Boolean
        get() = packageName.isEmpty()

    companion object {
        fun empty(): AppItem = AppItem(
            label = "",
            packageName = "",
            activityName = "",
            icon = null
        )
    }
}
