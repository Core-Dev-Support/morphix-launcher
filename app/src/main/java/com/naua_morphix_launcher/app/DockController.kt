package com.naua_morphix_launcher.app

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.LinearLayoutManager
import com.naua_morphix_launcher.app.databinding.ActivityMainBinding
import com.naua_morphix_launcher.app.model.AppItem
import com.naua_morphix_launcher.app.model.DockStyle
import com.naua_morphix_launcher.app.model.LayoutMode
import com.naua_morphix_launcher.app.model.LauncherSettings
import com.naua_morphix_launcher.app.ui.DockAdapter
import com.naua_morphix_launcher.app.util.PreferencesManager
import androidx.constraintlayout.widget.ConstraintLayout

/**
 * Док — плавающая стеклянная капсула быстрого доступа внизу рабочего стола.
 *
 * Раньше док был заведён в настройках (`isDockEnabled`, `dockStyle`, `dockIconCount`)
 * и в PreferencesManager (`getDockPackages` / `setDockPackages`), но самого UI
 * в проекте не было — остался комментарий «Dock adapter removed». Здесь док
 * восстановлен.
 *
 * Хранение: список пакетов в prefs, порядок значим. Пустая строка в списке —
 * пустой слот, чтобы позиции не «съезжали» после удаления иконки.
 */
class DockController(
    private val binding: ActivityMainBinding,
    private val prefsManager: PreferencesManager,
    private val launchApp: (AppItem, View?) -> Unit,
    private val onLongPressItem: (AppItem, View) -> Unit,
    private val onStartDragFromDock: (AppItem, View, Float, Float) -> Unit
) {

    val adapter = DockAdapter(
        onAppClick = { item -> launchApp(item, null) },
        onAppLongClick = { item, view -> onLongPressItem(item, view) },
        onDockItemStartDrag = { item, view, x, y -> onStartDragFromDock(item, view, x, y) }
    )

    private var dockPackages: MutableList<String> = mutableListOf()

    private val dockRv get() = binding.dockContainer.dockRecyclerView

    init {
        dockRv.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = this@DockController.adapter
            itemAnimator = null // перестановка в доке не должна мигать
            setHasFixedSize(true)
            isNestedScrollingEnabled = false
        }
    }

    /** Пересобирает док из списка пакетов и текущего набора приложений. */
    fun refresh(
        allApps: List<AppItem>,
        hiddenPackages: Set<String>,
        settings: LauncherSettings
    ) {
        dockPackages = prefsManager.getDockPackages().toMutableList()
        val byPackage = allApps.associateBy { it.packageName }
        val items = dockPackages
            .mapNotNull { pkg -> if (pkg.isEmpty()) null else byPackage[pkg] }
            .filter { it.packageName !in hiddenPackages }

        adapter.updateConfig(settings.iconShape, settings.showLabels)
        adapter.submit(items)
        applyVisibility(settings)
    }

    /**
     * Показывает/скрывает док и применяет стиль.
     *
     * Док скрыт в режиме CLASSIC: там все приложения уже лежат на экранах,
     * а меню приложений не открывается свайпом, поэтому док только занимает место.
     */
    fun applyVisibility(settings: LauncherSettings) {
        val root = binding.dockContainer.root
        val shouldShow = settings.isDockEnabled
                && settings.dockIconCount > 0
                && settings.layoutMode == LayoutMode.DRAWER

        if (shouldShow && root.visibility != View.VISIBLE) {
            root.visibility = View.VISIBLE
            if (settings.smoothAnimations) {
                root.alpha = 0f
                root.scaleX = 0.9f
                root.scaleY = 0.9f
                root.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
        } else if (!shouldShow) {
            root.visibility = View.GONE
        }

        // Стиль: FLOATING = парящая капсула по центру, FULL_WIDTH = панель во всю ширину
        val lp = root.layoutParams as? ConstraintLayout.LayoutParams ?: return
        if (settings.dockStyle == DockStyle.FULL_WIDTH) {
            lp.startToStart = 0
            lp.endToEnd = 0
            lp.width = 0 // MATCH_CONSTRAINT
            lp.leftMargin = (20 * root.resources.displayMetrics.density).toInt()
            lp.rightMargin = (20 * root.resources.displayMetrics.density).toInt()
            root.setPadding(0, 0, 0, 0)
        } else {
            lp.startToStart = ConstraintLayout.LayoutParams.UNSET
            lp.endToEnd = ConstraintLayout.LayoutParams.UNSET
            lp.width = ConstraintLayout.LayoutParams.WRAP_CONTENT
            lp.leftMargin = 0
            lp.rightMargin = 0
        }
        root.layoutParams = lp
    }

    fun packages(): List<String> = dockPackages.toList()

    fun items(): List<AppItem> = adapter.currentItems()

    /** Экранные границы дока для определения попадания при перетаскивании. */
    fun bounds(): Rect? {
        val view = binding.dockContainer.root
        if (view.visibility != View.VISIBLE || view.width == 0) return null
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + view.width, loc[1] + view.height)
    }

    fun contains(rawX: Float, rawY: Float): Boolean = bounds()?.let {
        rawX >= it.left && rawX <= it.right && rawY >= it.top && rawY <= it.bottom
    } ?: false

    /** Добавляет пакет. false — слот занят или лимит исчерпан. */
    fun addPackage(packageName: String, maxSlots: Int): Boolean {
        if (dockPackages.contains(packageName)) return false
        if (dockPackages.count { it.isNotEmpty() } >= maxSlots) return false
        dockPackages.add(packageName)
        persist()
        return true
    }

    fun removePackage(packageName: String): Boolean {
        val idx = dockPackages.indexOf(packageName)
        if (idx == -1) return false
        dockPackages[idx] = ""
        persist()
        return true
    }

    private fun persist() {
        prefsManager.setDockPackages(dockPackages)
    }
}