package com.naua_morphix_launcher.app

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
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
            adapter = this@DockController.adapter
            itemAnimator = null // перестановка в доке не должна мигать
            setHasFixedSize(true)
            isNestedScrollingEnabled = false
        }
        applyDockLayout(DockStyle.FLOATING)
    }

    /**
     * Раскладка дока.
     *
     * FLOATING — линейная, иконки идут встык внутри капсулы по центру экрана.
     * FULL_WIDTH — сетка в одну строку на столько колонок, сколько иконок:
     * они распределяются равномерно по всей ширине. ВАЖНО: GridLayoutManager
     * нужен с VERTICAL ориентацией (spanCount = число колонок). С HORIZONTAL
     * spanCount означает число строк, и 4 иконки вставали вертикальным
     * столбцом на пол-экрана.
     */
    private fun applyDockLayout(style: DockStyle, iconCount: Int = 1) {
        val rv = dockRv
        rv.layoutManager = if (style == DockStyle.FULL_WIDTH && iconCount > 1) {
            androidx.recyclerview.widget.GridLayoutManager(rv.context, iconCount)
        } else {
            LinearLayoutManager(rv.context, LinearLayoutManager.HORIZONTAL, false)
        }
        val lp = rv.layoutParams
        lp.width = if (style == DockStyle.FULL_WIDTH) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        rv.layoutParams = lp
    }

    /** Пересобирает док из списка пакетов и текущего набора приложений. */
    fun refresh(
        allApps: List<AppItem>,
        hiddenPackages: Set<String>,
        settings: LauncherSettings
    ) {
        dockPackages = prefsManager.getDockPackages().toMutableList()

        // Ползунок «Иконок в доке» изменили — подгоняем список под него.
        // Делаем это только при смене значения (метка dockSyncedCount):
        // ручное удаление иконки перетаскиванием док сам возвращать не должен.
        // Синхронизацию пропускаем, пока список приложений пуст (первый кадр
        // до загрузки), иначе пустой док «защёлкнется» как синхронизированный.
        if (allApps.isNotEmpty() && prefsManager.getDockSyncedCount() != settings.dockIconCount) {
            syncDockToCount(allApps, hiddenPackages, settings.dockIconCount)
            prefsManager.setDockSyncedCount(count = settings.dockIconCount)
        }

        val byPackage = allApps.associateBy { it.packageName }
        val items = dockPackages
            .mapNotNull { pkg -> if (pkg.isEmpty()) null else byPackage[pkg] }
            .filter { it.packageName !in hiddenPackages }
            .take(settings.dockIconCount.coerceAtLeast(0))

        adapter.updateConfig(settings.iconShape, settings.showLabels)
        adapter.submit(items)
        applyDockLayout(settings.dockStyle, items.size.coerceAtLeast(1))
        applyVisibility(settings)
    }

    /**
     * Добирает stored-список до заданного количества из стандартного набора
     * (телефон, сообщения, камера, ...). Порядок существующих иконок не
     * меняется. Лишнее НЕ удаляем никогда: уменьшение ползунка только прячет
     * хвост на экране (display делает take(N)), и возврат ползунка всё
     * восстанавливает. Иначе обновление затёрло бы иконки тем, у кого их
     * оказалось больше лимита.
     */
    private fun syncDockToCount(allApps: List<AppItem>, hiddenPackages: Set<String>, count: Int) {
        val target = count.coerceIn(0, MAX_DOCK_SLOTS)
        val available = allApps.map { it.packageName }.toSet()
        val compact = dockPackages
            .filter { it.isNotEmpty() && it in available && it !in hiddenPackages }
            .distinct()
            .toMutableList()

        if (compact.size < target) {
            for (pkg in defaultCandidates()) {
                if (compact.size >= target) break
                if (pkg in available && pkg !in compact && pkg !in hiddenPackages) {
                    compact.add(pkg)
                }
            }
        }

        dockPackages = compact
        persist()
    }

    /**
     * Показывает/скрывает док и применяет стиль.
     *
     * Док виден и в CLASSIC, и в DRAWER: в MIUI док есть в обоих режимах.
     * Раньше док прятался в CLASSIC — это и была жалоба «док пропал».
     */
    fun applyVisibility(settings: LauncherSettings) {
        val root = binding.dockContainer.root
        val shouldShow = settings.isDockEnabled
                && settings.dockIconCount > 0

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

        // Стиль: FLOATING = парящая капсула по центру, FULL_WIDTH = панель во всю ширину.
        // ВАЖНО: ограничения start/end задают центровку при WRAP_CONTENT.
        // Сбрасывать их (UNSET) нельзя — док прижимался к левому краю.
        val lp = root.layoutParams as? ConstraintLayout.LayoutParams ?: return
        val marginH = (20 * root.resources.displayMetrics.density).toInt()
        if (settings.dockStyle == DockStyle.FULL_WIDTH) {
            lp.width = 0 // MATCH_CONSTRAINT
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            lp.leftMargin = marginH
            lp.rightMargin = marginH
        } else {
            lp.width = ConstraintLayout.LayoutParams.WRAP_CONTENT
            lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
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

    fun isDockVisible(): Boolean = binding.dockContainer.root.visibility == View.VISIBLE

    /** Добавляет пакет. false — уже в доке или лимит исчерпан. */
    fun addPackage(packageName: String, maxSlots: Int): Boolean {
        if (dockPackages.contains(packageName)) return false
        if (dockPackages.count { it.isNotEmpty() } >= maxSlots.coerceIn(0, MAX_DOCK_SLOTS)) return false
        // занимаем первую пустую дырку от удалённой иконки, а не растём в конец
        val hole = dockPackages.indexOfFirst { it.isEmpty() }
        if (hole != -1) {
            dockPackages[hole] = packageName
        } else {
            dockPackages.add(packageName)
        }
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
        prefsManager.setDockPackages(packages = dockPackages)
    }

    companion object {
        /** Жёсткий потолок слотов дока (совпадает с максимумом ползунка). */
        const val MAX_DOCK_SLOTS = 7

        /**
         * Кандидаты для автозаполнения дока: телефон, сообщения, камера,
         * браузер, галерея и дальше по убыванию «стандартности».
         */
        fun defaultCandidates(): List<String> = listOf(
            "com.android.dialer", "com.android.server.telecom",
            "com.android.contacts", "com.google.android.contacts",
            "com.android.messaging", "com.google.android.apps.messaging",
            "com.android.camera2", "com.android.camera",
            "com.android.chrome", "com.google.android.browser",
            "com.miui.gallery", "com.android.gallery3d",
            "com.android.settings",
            "com.miui.player", "com.android.videoplayer",
            "com.android.calendar",
            "com.miui.notes",
            "com.android.deskclock", "com.android.clock",
            "com.android.fileexplorer", "com.google.android.apps.photos",
            "com.miui.weather",
            "com.android.mms",
            "com.tencent.mm", "com.whatsapp"
        )
    }
}