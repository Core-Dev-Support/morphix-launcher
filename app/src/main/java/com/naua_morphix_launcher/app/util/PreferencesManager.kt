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
import java.util.concurrent.ConcurrentHashMap

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("morphix_launcher_prefs", Context.MODE_PRIVATE)

    private val orderedPackagesCache = ConcurrentHashMap<Int, List<String>>()
    private val foldersCache = ConcurrentHashMap<Int, Map<String, FolderItem>>()

    private var widgetPageMapCache: MutableMap<Int, Int>? = null
    private val dockPackagesCache = ConcurrentHashMap<Int, List<String>>()

    /**
     * enumValueOf бросает IllegalArgumentException на любую неизвестную строку.
     * Для домашнего экрана это = «лаунчер вообще не запускается», поэтому
     * любое нераспознанное значение (старый бэкап, ручной edit prefs, kill -9
     * во время apply()) должно молча откатываться к дефолту.
     */
    private inline fun <reified T : Enum<T>> safeEnum(raw: String?, default: T): T =
        if (raw == null) default else enumValues<T>().firstOrNull { it.name == raw } ?: default

    fun loadSettings(): LauncherSettings {
        return LauncherSettings(
            isGlassEnabled = prefs.getBoolean("isGlassEnabled", true),
            blurRadius = prefs.getFloat("blurRadius", 18f),
            glassAlpha = prefs.getFloat("glassAlpha", 0.22f),
            iconShape = safeEnum(prefs.getString("iconShape", null), IconShape.SQUIRCLE),
            iconScale = prefs.getFloat("iconScale", 1.0f),
            showLabels = prefs.getBoolean("showLabels", true),
            layoutMode = safeEnum(prefs.getString("layoutMode", null), LayoutMode.DRAWER),
            gridColumns = prefs.getInt("gridColumns", 5).coerceIn(3, 8),
            gridRows = prefs.getInt("gridRows", 9).coerceIn(3, 12),
            isDockEnabled = prefs.getBoolean("isDockEnabled", true),
            dockStyle = safeEnum(prefs.getString("dockStyle", null), DockStyle.FLOATING),
            dockIconCount = prefs.getInt("dockIconCount", 5),
            showSearchOnHome = prefs.getBoolean("showSearchOnHome", true),
            showSearchInDrawer = prefs.getBoolean("showSearchInDrawer", true),
            swipeDownToNotifications = prefs.getBoolean("swipeDownToNotifications", true),
            enableTapToLock = prefs.getBoolean("enableTapToLock", true),
            tapsToLockCount = prefs.getInt("tapsToLockCount", 2),
            showClockWidget = prefs.getBoolean("showClockWidget", true),
            lockType = safeEnum(prefs.getString("lockType", null), LockType.BIOMETRIC_OR_PIN),
            // getStringSet отдаёт ВНУТРЕННИЙ экземпляр prefs — его нельзя мутировать
            hiddenPackages = prefs.getStringSet("hiddenPackages", emptySet())?.toSet() ?: emptySet(),
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
            .putStringSet("hiddenPackages", settings.hiddenPackages.toSet())
            .putBoolean("smoothAnimations", settings.smoothAnimations)
            .apply()
    }

    fun getHomeScreenPackages(spaceIndex: Int = 0): Set<String> {
        val key = if (spaceIndex == 0) "homeScreenPackages" else "homeScreenPackages_space_$spaceIndex"
        // .toSet() — getStringSet возвращает внутренний экземпляр SharedPreferences
        return prefs.getStringSet(key, null)?.toSet() ?: emptySet()
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
        dockPackagesCache[spaceIndex]?.let { return ArrayList(it) }
        val raw = prefs.getString(key, null)
        val list = if (raw != null) {
            raw.split(",").map { if (it == EMPTY_CELL_KEY) "" else it }
        } else {
            emptyList()
        }
        dockPackagesCache[spaceIndex] = list
        return ArrayList(list)
    }

    fun setDockPackages(spaceIndex: Int = 0, packages: List<String>) {
        val key = if (spaceIndex == 0) "dockPackages" else "dockPackages_space_$spaceIndex"
        dockPackagesCache[spaceIndex] = ArrayList(packages)
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
        // sortedBy гарантирует стабильный порядок: из Set иначе получился бы
        // произвольный порядок хеш-таблицы, и иконки «прыгали» бы при перезапуске
        val ordered = packages.sorted()
        orderedPackagesCache[spaceIndex] = ordered
        prefs.edit()
            .putStringSet(legacyKey, packages.toSet())
            .putString(orderedKey, ordered.joinToString(","))
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
        return prefs.getStringSet("secondSpacePackages", emptySet())?.toSet() ?: emptySet()
    }

    fun setSecondSpacePackages(packages: Set<String>) {
        prefs.edit().putStringSet("secondSpacePackages", packages.toSet()).apply()
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
        widgetPageMapCache?.let { return it }
        val result = mutableMapOf<Int, Int>()
        val raw = prefs.getString("page_widgets_map", null)
        if (raw != null) {
            try {
                val json = JSONObject(raw)
                for (key in json.keys()) {
                    val id = key.toIntOrNull() ?: continue
                    // optInt вместо getInt: одно нечисловое значение раньше
                    // прерывало весь цикл и теряло остальные привязки
                    val page = json.optInt(key, -1)
                    if (page >= 0) result[id] = page
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        widgetPageMapCache = result
        return result
    }

    fun setWidgetPage(id: Int, page: Int) {
        val current = getWidgetPageMap().toMutableMap()
        current[id] = page
        widgetPageMapCache = current
        val json = JSONObject()
        for ((k, v) in current) {
            json.put(k.toString(), v)
        }
        prefs.edit().putString("page_widgets_map", json.toString()).apply()
    }

    fun removeWidgetPage(id: Int) {
        val current = getWidgetPageMap().toMutableMap()
        current.remove(id)
        widgetPageMapCache = current
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
        val raw = prefs.getString(key, null)
        val result = mutableMapOf<String, FolderItem>()
        if (raw != null) {
            try {
                val jsonArray = JSONArray(raw)
                for (i in 0 until jsonArray.length()) {
                    // optJSONObject + continue вместо getString: раньше один битый
                    // элемент обрывал цикл и кэшировалась неполная карта папок
                    val obj = jsonArray.optJSONObject(i) ?: continue
                    val id = obj.optString("id").takeIf { it.isNotEmpty() } ?: continue
                    val pkgs = mutableListOf<String>()
                    val arr = obj.optJSONArray("packages")
                    if (arr != null) {
                        for (j in 0 until arr.length()) {
                            arr.optString(j)?.takeIf { it.isNotEmpty() }?.let { pkgs.add(it) }
                        }
                    }
                    result[id] = FolderItem(
                        id,
                        obj.optString("name", "Папка"),
                        pkgs,
                        obj.optString("size", "REGULAR")
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        foldersCache[spaceIndex] = result
        return result
    }

    fun saveFolders(spaceIndex: Int = 0, folders: Map<String, FolderItem>) {
        // копия, чтобы вызывающий код не мутировал наш кэш
        val snapshot = folders.toMap()
        foldersCache[spaceIndex] = snapshot
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
