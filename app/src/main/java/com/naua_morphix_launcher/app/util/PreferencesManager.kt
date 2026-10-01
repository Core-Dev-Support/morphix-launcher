package com.naua_morphix_launcher.app.util

import android.content.Context
import android.content.SharedPreferences
import com.naua_morphix_launcher.app.model.DockStyle
import com.naua_morphix_launcher.app.model.FolderItem
import com.naua_morphix_launcher.app.model.IconShape
import com.naua_morphix_launcher.app.model.LauncherSettings
import com.naua_morphix_launcher.app.model.LayoutMode
import com.naua_morphix_launcher.app.model.LockType
import org.json.JSONArray
import org.json.JSONObject

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("morphix_launcher_prefs", Context.MODE_PRIVATE)

    private val orderedPackagesCache = mutableMapOf<Int, List<String>>()
    private val foldersCache = mutableMapOf<Int, Map<String, FolderItem>>()

    fun loadSettings(): LauncherSettings {
        return LauncherSettings(
            isGlassEnabled = prefs.getBoolean("isGlassEnabled", true),
            blurRadius = prefs.getFloat("blurRadius", 18f),
            glassAlpha = prefs.getFloat("glassAlpha", 0.22f),
            iconShape = IconShape.valueOf(prefs.getString("iconShape", IconShape.SQUIRCLE.name) ?: IconShape.SQUIRCLE.name),
            iconScale = prefs.getFloat("iconScale", 1.0f),
            showLabels = prefs.getBoolean("showLabels", true),
            layoutMode = LayoutMode.valueOf(prefs.getString("layoutMode", LayoutMode.DRAWER.name) ?: LayoutMode.DRAWER.name),
            gridColumns = prefs.getInt("gridColumns", 5),
            gridRows = prefs.getInt("gridRows", 9),
            isDockEnabled = prefs.getBoolean("isDockEnabled", true),
            dockStyle = DockStyle.valueOf(prefs.getString("dockStyle", DockStyle.FLOATING.name) ?: DockStyle.FLOATING.name),
            dockIconCount = prefs.getInt("dockIconCount", 5),
            showSearchOnHome = prefs.getBoolean("showSearchOnHome", true),
            showSearchInDrawer = prefs.getBoolean("showSearchInDrawer", true),
            swipeDownToNotifications = prefs.getBoolean("swipeDownToNotifications", true),
            enableTapToLock = prefs.getBoolean("enableTapToLock", true),
            tapsToLockCount = prefs.getInt("tapsToLockCount", 2),
            showClockWidget = prefs.getBoolean("showClockWidget", true),
            lockType = LockType.valueOf(prefs.getString("lockType", LockType.BIOMETRIC_OR_PIN.name) ?: LockType.BIOMETRIC_OR_PIN.name),
            hiddenPackages = prefs.getStringSet("hiddenPackages", emptySet()) ?: emptySet(),
            smoothAnimations = prefs.getBoolean("smoothAnimations", true)
        )
    }

    fun saveSettings(settings: LauncherSettings) {
        prefs.edit()
            .putBoolean("isGlassEnabled", settings.isGlassEnabled)
            .putFloat("blurRadius", settings.blurRadius)
            .putFloat("glassAlpha", settings.glassAlpha)
            .putString("iconShape", settings.iconShape.name)
            .putFloat("iconScale", settings.iconScale)
            .putBoolean("showLabels", settings.showLabels)
            .putString("layoutMode", settings.layoutMode.name)
            .putInt("gridColumns", settings.gridColumns)
            .putInt("gridRows", settings.gridRows)
            .putBoolean("isDockEnabled", settings.isDockEnabled)
            .putString("dockStyle", settings.dockStyle.name)
            .putInt("dockIconCount", settings.dockIconCount)
            .putBoolean("showSearchOnHome", settings.showSearchOnHome)
            .putBoolean("showSearchInDrawer", settings.showSearchInDrawer)
            .putBoolean("swipeDownToNotifications", settings.swipeDownToNotifications)
            .putBoolean("enableTapToLock", settings.enableTapToLock)
            .putInt("tapsToLockCount", settings.tapsToLockCount)
            .putBoolean("showClockWidget", settings.showClockWidget)
            .putString("lockType", settings.lockType.name)
            .putStringSet("hiddenPackages", settings.hiddenPackages)
            .putBoolean("smoothAnimations", settings.smoothAnimations)
            .apply()
    }

    fun getHomeScreenPackages(spaceIndex: Int = 0): Set<String> {
        val key = if (spaceIndex == 0) "homeScreenPackages" else "homeScreenPackages_space_$spaceIndex"
        return prefs.getStringSet(key, null) ?: emptySet()
    }

    companion object {
        const val EMPTY_CELL_KEY = "__empty__"
    }

    fun getHomeScreenOrderedPackages(spaceIndex: Int = 0): List<String> {
        orderedPackagesCache[spaceIndex]?.let { return ArrayList(it) }
        val key = if (spaceIndex == 0) "homeScreenOrderedPackages" else "homeScreenOrderedPackages_space_$spaceIndex"
        val raw = prefs.getString(key, null)
        val list = if (raw != null) {
            raw.split(",").map { if (it == EMPTY_CELL_KEY) "" else it }
        } else {
            getHomeScreenPackages(spaceIndex).toList()
        }
        orderedPackagesCache[spaceIndex] = list
        return ArrayList(list)
    }

    fun getDockPackages(spaceIndex: Int = 0): List<String> {
        val key = if (spaceIndex == 0) "dockPackages" else "dockPackages_space_$spaceIndex"
        val raw = prefs.getString(key, null)
        return if (raw != null) {
            raw.split(",").map { if (it == EMPTY_CELL_KEY) "" else it }
        } else {
            emptyList()
        }
    }

    fun setDockPackages(spaceIndex: Int = 0, packages: List<String>) {
        val key = if (spaceIndex == 0) "dockPackages" else "dockPackages_space_$spaceIndex"
        val raw = packages.joinToString(",") { if (it.isEmpty()) EMPTY_CELL_KEY else it }
        prefs.edit().putString(key, raw).apply()
    }

    fun setHomeScreenOrderedPackages(spaceIndex: Int = 0, packages: List<String>) {
        orderedPackagesCache[spaceIndex] = ArrayList(packages)
        val key = if (spaceIndex == 0) "homeScreenOrderedPackages" else "homeScreenOrderedPackages_space_$spaceIndex"
        val legacyKey = if (spaceIndex == 0) "homeScreenPackages" else "homeScreenPackages_space_$spaceIndex"
        
        val trimmed = packages.toMutableList()
        while (trimmed.isNotEmpty() && (trimmed.last().isEmpty() || trimmed.last() == EMPTY_CELL_KEY)) {
            trimmed.removeAt(trimmed.size - 1)
        }
        val encoded = trimmed.map { if (it.isEmpty()) EMPTY_CELL_KEY else it }
        val activePackages = trimmed.filter { it.isNotEmpty() && it != EMPTY_CELL_KEY && !it.startsWith("folder:") && !it.startsWith("widget:") }.toSet()

        prefs.edit()
            .putString(key, encoded.joinToString(","))
            .putStringSet(legacyKey, activePackages)
            .apply()
    }

    fun setHomeScreenPackages(spaceIndex: Int = 0, packages: Set<String>) {
        val legacyKey = if (spaceIndex == 0) "homeScreenPackages" else "homeScreenPackages_space_$spaceIndex"
        val orderedKey = if (spaceIndex == 0) "homeScreenOrderedPackages" else "homeScreenOrderedPackages_space_$spaceIndex"
        prefs.edit()
            .putStringSet(legacyKey, packages)
            .putString(orderedKey, packages.joinToString(","))
            .apply()
    }

    fun addHomeScreenPackage(spaceIndex: Int = 0, packageName: String) {
        val current = getHomeScreenOrderedPackages(spaceIndex).toMutableList()
        if (!current.contains(packageName)) {
            val firstEmpty = current.indexOfFirst { it.isEmpty() || it == EMPTY_CELL_KEY }
            if (firstEmpty != -1) {
                current[firstEmpty] = packageName
            } else {
                current.add(packageName)
            }
            setHomeScreenOrderedPackages(spaceIndex, current)
        }
    }

    fun removeHomeScreenPackage(spaceIndex: Int = 0, packageName: String) {
        val current = getHomeScreenOrderedPackages(spaceIndex).toMutableList()
        val idx = current.indexOf(packageName)
        if (idx != -1) {
            current[idx] = ""
            setHomeScreenOrderedPackages(spaceIndex, current)
        }
    }

    fun getCurrentSpace(): Int {
        return prefs.getInt("currentSpace", 0)
    }

    fun setCurrentSpace(space: Int) {
        prefs.edit().putInt("currentSpace", space).apply()
    }

    fun getSecondSpacePackages(): Set<String> {
        return prefs.getStringSet("secondSpacePackages", emptySet()) ?: emptySet()
    }

    fun setSecondSpacePackages(packages: Set<String>) {
        prefs.edit().putStringSet("secondSpacePackages", packages).apply()
    }

    fun addSecondSpacePackage(packageName: String) {
        val current = getSecondSpacePackages().toMutableSet()
        current.add(packageName)
        setSecondSpacePackages(current)
    }

    fun removeSecondSpacePackage(packageName: String) {
        val current = getSecondSpacePackages().toMutableSet()
        current.remove(packageName)
        setSecondSpacePackages(current)
    }

    fun isSecondSpaceProtected(): Boolean {
        return prefs.getBoolean("isSecondSpaceProtected", false)
    }

    fun setSecondSpaceProtected(protected: Boolean) {
        prefs.edit().putBoolean("isSecondSpaceProtected", protected).apply()
    }

    fun getAppWidgetIds(): List<Int> {
        val stringSet = prefs.getStringSet("installed_app_widget_ids", emptySet()) ?: emptySet()
        return stringSet.mapNotNull { it.toIntOrNull() }
    }

    fun getWidgetPageMap(): Map<Int, Int> {
        val raw = prefs.getString("page_widgets_map", null) ?: return emptyMap()
        val result = mutableMapOf<Int, Int>()
        try {
            val json = JSONObject(raw)
            for (key in json.keys()) {
                val id = key.toIntOrNull()
                if (id != null) {
                    result[id] = json.getInt(key)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return result
    }

    fun setWidgetPage(id: Int, page: Int) {
        val current = getWidgetPageMap().toMutableMap()
        current[id] = page
        val json = JSONObject()
        for ((k, v) in current) {
            json.put(k.toString(), v)
        }
        prefs.edit().putString("page_widgets_map", json.toString()).apply()
    }

    fun removeWidgetPage(id: Int) {
        val current = getWidgetPageMap().toMutableMap()
        current.remove(id)
        val json = JSONObject()
        for ((k, v) in current) {
            json.put(k.toString(), v)
        }
        prefs.edit().putString("page_widgets_map", json.toString()).apply()
    }

    fun getWidgetsForPage(page: Int): List<Int> {
        val allWidgets = getAppWidgetIds()
        val pageMap = getWidgetPageMap()
        return allWidgets.filter { widgetId ->
            (pageMap[widgetId] ?: 0) == page
        }
    }

    fun addAppWidgetId(id: Int, pageIndex: Int = 0) {
        val current = getAppWidgetIds().toMutableList()
        if (!current.contains(id)) {
            current.add(id)
            prefs.edit().putStringSet("installed_app_widget_ids", current.map { it.toString() }.toSet()).apply()
        }
        setWidgetPage(id, pageIndex)
    }

    fun removeAppWidgetId(id: Int) {
        val current = getAppWidgetIds().toMutableList()
        current.remove(id)
        prefs.edit().putStringSet("installed_app_widget_ids", current.map { it.toString() }.toSet()).apply()
        removeWidgetPage(id)
    }

    fun getFolders(spaceIndex: Int = 0): Map<String, FolderItem> {
        foldersCache[spaceIndex]?.let { return it }
        val key = if (spaceIndex == 0) "folders_data" else "folders_data_space_$spaceIndex"
        val raw = prefs.getString(key, null) ?: return emptyMap()
        val result = mutableMapOf<String, FolderItem>()
        try {
            val jsonArray = JSONArray(raw)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                val name = obj.optString("name", "Папка")
                val pkgs = mutableListOf<String>()
                val arr = obj.optJSONArray("packages")
                if (arr != null) {
                    for (j in 0 until arr.length()) {
                        pkgs.add(arr.getString(j))
                    }
                }
                val size = obj.optString("size", "REGULAR")
                result[id] = FolderItem(id, name, pkgs, size)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        foldersCache[spaceIndex] = result
        return result
    }

    fun saveFolders(spaceIndex: Int = 0, folders: Map<String, FolderItem>) {
        foldersCache[spaceIndex] = folders
        val key = if (spaceIndex == 0) "folders_data" else "folders_data_space_$spaceIndex"
        val jsonArray = JSONArray()
        for ((_, folder) in folders) {
            val obj = JSONObject()
            obj.put("id", folder.id)
            obj.put("name", folder.name)
            obj.put("size", folder.size)
            val arr = JSONArray()
            for (pkg in folder.packageNames) {
                arr.put(pkg)
            }
            obj.put("packages", arr)
            jsonArray.put(obj)
        }
        prefs.edit().putString(key, jsonArray.toString()).apply()
    }

    fun saveFolder(spaceIndex: Int = 0, folder: FolderItem) {
        val folders = getFolders(spaceIndex).toMutableMap()
        folders[folder.id] = folder
        saveFolders(spaceIndex, folders)
    }

    fun deleteFolder(spaceIndex: Int = 0, folderId: String) {
        val folders = getFolders(spaceIndex).toMutableMap()
        folders.remove(folderId)
        saveFolders(spaceIndex, folders)
    }
}
