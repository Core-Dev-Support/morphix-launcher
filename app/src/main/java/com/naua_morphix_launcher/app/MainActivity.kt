@file:Suppress("DEPRECATION")
package com.naua_morphix_launcher.app

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Dialog
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.view.inputmethod.InputMethodManager
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import com.naua_morphix_launcher.app.ui.FolderPagerAdapter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.naua_morphix_launcher.app.databinding.ActivityMainBinding
import com.naua_morphix_launcher.app.databinding.DialogAppActionsBinding
import com.naua_morphix_launcher.app.databinding.DialogDesktopMenuBinding
import com.naua_morphix_launcher.app.databinding.DialogWidgetPickerBinding
import com.naua_morphix_launcher.app.databinding.PopupAppQuickActionsBinding
import com.naua_morphix_launcher.app.model.AppItem
import com.naua_morphix_launcher.app.model.DockStyle
import com.naua_morphix_launcher.app.model.LauncherSettings
import com.naua_morphix_launcher.app.model.LayoutMode
import com.naua_morphix_launcher.app.model.AppWidgetGroup
import com.naua_morphix_launcher.app.model.WidgetItem
import com.naua_morphix_launcher.app.receiver.MorphixDeviceAdminReceiver
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import com.naua_morphix_launcher.app.databinding.DialogFolderViewBinding
import com.naua_morphix_launcher.app.model.FolderItem
import com.naua_morphix_launcher.app.service.MorphixAccessibilityService
import com.naua_morphix_launcher.app.service.MorphixNotificationListenerService
import com.naua_morphix_launcher.app.ui.AppsAdapter
import com.naua_morphix_launcher.app.ui.DesktopPagerAdapter
import com.naua_morphix_launcher.app.ui.DockAdapter
import com.naua_morphix_launcher.app.ui.WidgetsAdapter
import com.naua_morphix_launcher.app.ui.SettingsDialog
import com.naua_morphix_launcher.app.views.LiquidGlassView
import com.naua_morphix_launcher.app.util.AppLoader
import com.naua_morphix_launcher.app.util.PreferencesManager

import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.naua_morphix_launcher.app.ui.MorphixAppWidgetHost
import java.lang.reflect.Method
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executor

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PreferencesManager
    lateinit var currentSettings: LauncherSettings
    private lateinit var drawerAppsAdapter: AppsAdapter
    private lateinit var desktopPagerAdapter: DesktopPagerAdapter
    private lateinit var gestureDetector: GestureDetectorCompat
    private var edgeHoverRunnable: Runnable? = null
    private val edgeHoverHandler = Handler(Looper.getMainLooper())

    private lateinit var appWidgetManager: AppWidgetManager
    private lateinit var appWidgetHost: AppWidgetHost
    private var replacingWidgetId: Int? = null
    private var cachedWidgetGroups: List<AppWidgetGroup>? = null
    private var isHoveringDeletePill: Boolean = false
    private val widgetMetaCache = mutableMapOf<Int, Pair<String, Pair<Int, Int>>>()
    private var isFolderDropArmed = false
    private val folderHoverHandler = Handler(Looper.getMainLooper())
    private var folderHoverRunnable: Runnable? = null
    
    private val liveSwapHandler = Handler(Looper.getMainLooper())
    private var liveSwapRunnable: Runnable? = null
    private var liveSwapTargetItem: AppItem? = null

    private var allApps: List<AppItem> = emptyList()
    private var currentSpace: Int = 0 // 0 = Основное пространство, 1 = Второе пространство
    private var isSecondSpaceActive = false
    private var tapCounter = 0
    private var lastTapTime = 0L
    private var isBlurInitialized = false
    private var lowEndDeviceCache: Boolean? = null

    // ===== Док (плавающая капсула быстрого доступа) =====
    private lateinit var dockController: DockController
    private val dockAdapter get() = dockController.adapter

    /** Иконка вытащена из дока, а не из сетки — влияет на завершение drag. */
    private var isDraggingFromDock = false

    /** Курсор над доком во время перетаскивания — док подсвечен. */
    private var isHoveringDock = false

    /** Док принимает иконку: обычное приложение, есть свободный слот. */
    private fun dockAcceptsDrop(): Boolean {
        if (!::dockController.isInitialized || !dockController.isDockVisible()) return false
        val item = activeDraggedItem ?: return false
        if (item.isFolder || item.isWidget || item.isEmpty) return false
        // иконку, уже лежащую в доке, повторно туда класть нечего
        return dockController.items().none { it.packageName == item.packageName }
    }

    private fun setDockDropHighlight(active: Boolean) {
        val capsule = binding.dockContainer.root
        if (!currentSettings.smoothAnimations) {
            capsule.scaleX = if (active) 1.06f else 1.0f
            capsule.scaleY = if (active) 1.06f else 1.0f
            return
        }
        capsule.animate().cancel()
        if (active) {
            capsule.animate().scaleX(1.06f).scaleY(1.06f).setDuration(140).start()
        } else {
            capsule.animate().scaleX(1.0f).scaleY(1.0f).setDuration(140).start()
        }
    }

    /**
     * Итог перетаскивания над доком: кладём иконку в док и убираем её из сетки.
     * true — дроп обработан здесь, дальше обычная логика сетки не нужна.
     */
    private fun handleDropOnDock(): Boolean {
        val item = activeDraggedItem ?: return false
        if (!dockController.addPackage(item.packageName, currentSettings.dockIconCount)) return false

        // убираем из сетки рабочего стола, если там была
        if (!isDraggingFromDock) {
            val ordered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
            val idx = ordered.indexOf(item.packageName)
            if (idx != -1) {
                ordered[idx] = ""
                prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
            }
        } else {
            dockController.removePackage(item.packageName)
        }

        refreshDock()
        updateHomeScreenApps()
        setDockDropHighlight(false)
        isHoveringDock = false
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        return true
    }

    private fun setupDock() {
        dockController = DockController(
            binding = binding,
            prefsManager = prefsManager,
            launchApp = { item, view ->
                AppLoader.launchApp(this, item, view, smoothAnimations = currentSettings.smoothAnimations)
            },
            onLongPressItem = { item, view ->
                if (isEditMode) {
                    showAppQuickActionsPopup(item, view, isFromHomeScreen = true)
                } else {
                    showAppQuickActionsPopup(item, view, isFromHomeScreen = true)
                }
            },
            onStartDragFromDock = { item, view, x, y ->
                startDesktopDrag(item, view, x, y, fromDock = true)
            }
        )
    }

    private fun refreshDock() {
        if (!::dockController.isInitialized) return
        dockController.refresh(allApps, currentSettings.hiddenPackages, currentSettings)
        updateDockSpacing()
    }

    /**
     * Док перекрывает нижний ряд иконок, поэтому поднимаем рабочий стол
     * на высоту капсулы. Без этого нижние иконки оказывались под доком.
     */
    private fun updateDockSpacing() {
        val dock = binding.dockContainer.root
        val pagerLp = binding.homeViewPager.layoutParams as? androidx.constraintlayout.widget.ConstraintLayout.LayoutParams ?: return
        if (dock.visibility == View.VISIBLE && dock.height > 0) {
            pagerLp.bottomMargin = dock.height + (16 * resources.displayMetrics.density).toInt()
        } else {
            pagerLp.bottomMargin = 0
        }
        binding.homeViewPager.layoutParams = pagerLp
    }

    /**
     * Первый запуск: док пуст. MIUI тоже показывает в нём несколько иконок
     * по умолчанию, поэтому подбираем стандартный набор — телефон, сообщения,
     * камера, браузер, галерея — по первому совпадению в списке установленных
     * приложений. Если ничего не нашли, док останется пустым и это не ошибка.
     */
    private fun seedDockIfEmpty() {
        if (prefsManager.getDockPackages().isNotEmpty()) return

        val seedPackages = listOf(
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
            "com.miui.compass", "com.android.compass",
            "com.android.clock", "com.android.deskclock",
            "com.android.fileexplorer", "com.google.android.apps.photos",
            "com.miui.weather", "com.google.android.weather",
            "com.miui.composer", "com.android.mms",
            "com.tencent.mm", "com.whatsapp"
        )

        val available = allApps.map { it.packageName }.toSet()
        val picked = seedPackages.filter { it in available }
            .distinct()
            .take(currentSettings.dockIconCount.coerceAtLeast(1))

        if (picked.isNotEmpty()) {
            prefsManager.setDockPackages(picked)
        }
    }

    private val APPWIDGET_HOST_ID = 1024
    private val REQUEST_PICK_APPWIDGET = 2001
    private val REQUEST_CREATE_APPWIDGET = 2002
    private val REQUEST_BIND_APPWIDGET = 2003

    private var currentDialog: Dialog? = null
    private var currentPopup: PopupWindow? = null

    private val notificationListener = {
        runOnUiThread {
            val counts = MorphixNotificationListenerService.getAllBadgeCounts()
            desktopPagerAdapter.updateBadgeCounts(counts)
            drawerAppsAdapter.updateBadgeCounts(counts)
            if (::dockAdapter.isInitialized) dockAdapter.updateBadgeCounts(counts)
        }
    }

    private fun dismissActivePopups() {
        try {
            currentPopup?.dismiss()
        } catch (e: Exception) {
            // ignore
        }
        currentPopup = null

        try {
            currentDialog?.dismiss()
        } catch (e: Exception) {
            // ignore
        }
        currentDialog = null

        try {
            currentSettingsDialog?.dismiss()
        } catch (e: Exception) {
            // ignore
        }
        currentSettingsDialog = null
    }


    // Приемник событий установки и удаления приложений в реальном времени
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            cachedWidgetGroups = null
            // сбрасываем кэш иконок именно этого пакета, иначе иконка
            // обновлённого приложения оставалась старой до перезапуска процесса
            intent?.data?.schemeSpecificPart?.let { AppLoader.invalidatePackage(it) }
            scheduleAppReload()
        }
    }

    // loadApps делает кросс-процессные вызовы в system_server (label + иконка
    // каждого приложения). Без дебаунса пачка установок давала серию полных
    // перезагрузок по 1-3 секунды каждая.
    private val appReloadHandler = Handler(Looper.getMainLooper())
    private val appReloadRunnable = Runnable { loadInstalledApps() }
    private var appReloadScheduled = false

    private fun scheduleAppReload() {
        if (appReloadScheduled) return
        appReloadScheduled = true
        appReloadHandler.postDelayed(appReloadRunnable, 400)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Полноэкранный прозрачный режим (Edge-to-Edge) для красивых системных жестов без белых полос
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.navigationBarDividerColor = Color.TRANSPARENT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = false
        insetsController.isAppearanceLightNavigationBars = false

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PreferencesManager(this)
        currentSettings = prefsManager.loadSettings()
        currentSpace = prefsManager.getCurrentSpace()
        isSecondSpaceActive = (currentSpace == 1)

        setupDock()
        refreshDock()

        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost = MorphixAppWidgetHost(this, APPWIDGET_HOST_ID)
        appWidgetHost.startListening()

        binding.layoutTopDeletePill.setOnClickListener {
            val widgetId = activeDraggedItem?.widgetId
            if (widgetId != null) {
                deleteWidgetPermanently(widgetId)
                resetDragState()
            }
        }

        setupGestures()
        setupGlassmorphism()
        setupHomeScreenApps()
        setupAppDrawer()
        setupSpaceSelector()
        setupSearch()
        setupBackNavigation()
        setupEditModeListeners()
        setupSpaceBadge()
        restoreInstalledWidgets()
        loadInstalledApps()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                loadWidgetGroupsBackground()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        checkDefaultLauncherPrompt()

        // Отслеживание удаления и установки приложений в реальном времени
        val pkgFilter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, pkgFilter)
    }

    override fun onStart() {
        super.onStart()
        try {
            appWidgetHost.startListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        MorphixNotificationListenerService.registerListener(notificationListener)
        notificationListener()
    }

    override fun onStop() {
        super.onStop()
        try {
            appWidgetHost.stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        MorphixNotificationListenerService.unregisterListener(notificationListener)
        dismissActivePopups()
    }

    override fun onDestroy() {
        // без снятия отложенной перезагрузки Handler мог сработать на мёртвой Activity
        appReloadHandler.removeCallbacks(appReloadRunnable)
        appReloadScheduled = false
        try {
            unregisterReceiver(packageReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        // кэш host-view виджетов переживал Activity и удерживал целое дерево RemoteViews
        widgetViewCache.keys.toList().forEach { id ->
            try {
                (widgetViewCache.remove(id) as? android.appwidget.AppWidgetHostView)
                    ?.let { (it.parent as? ViewGroup)?.removeView(it) }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        widgetViewCache.clear()
        try {
            appWidgetHost.stopListening()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK) {
            when (requestCode) {
                REQUEST_PICK_APPWIDGET -> {
                    val appWidgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
                    if (appWidgetId != -1) {
                        configureWidgetOrAdd(appWidgetId)
                    }
                }
                REQUEST_BIND_APPWIDGET -> {
                    val appWidgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
                    if (appWidgetId != -1) {
                        val info = appWidgetManager.getAppWidgetInfo(appWidgetId)
                        if (info != null) {
                            proceedWithWidgetConfiguration(appWidgetId, info)
                        } else {
                            appWidgetHost.deleteAppWidgetId(appWidgetId)
                        }
                    }
                }
                REQUEST_CREATE_APPWIDGET -> {
                    val appWidgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
                    if (appWidgetId != -1) {
                        attachWidgetToHome(appWidgetId)
                    }
                }
            }
        } else if (resultCode == RESULT_CANCELED) {
            val appWidgetId = data?.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1) ?: -1
            if (appWidgetId != -1) {
                appWidgetHost.deleteAppWidgetId(appWidgetId)
            }
            replacingWidgetId = null
        }
    }

    private fun setupSpaceBadge() {
        binding.badgeSecondSpace.visibility = if (isSecondSpaceActive) View.VISIBLE else View.GONE
        binding.badgeSecondSpace.setOnClickListener {
            requestSwitchSpace()
        }
    }

    private var currentSettingsDialog: SettingsDialog? = null

    private fun openSettingsDialog() {
        dismissActivePopups()
        val dialog = SettingsDialog(
            context = this,
            onSwitchSpaceRequested = {
                requestSwitchSpace()
            },
            onSettingsUpdated = { updatedSettings ->
                currentSettings = updatedSettings
                applySettingsChanges()
            }
        )
        currentSettingsDialog = dialog
        dialog.show()
    }

    private fun applySettingsChanges() {
        setupGlassmorphism()

        val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            currentSettings.gridColumns + 2
        } else {
            currentSettings.gridColumns
        }

        binding.appsRecyclerView.layoutManager = GridLayoutManager(this, spanCount)

        drawerAppsAdapter.updateConfig(
            shape = currentSettings.iconShape,
            labels = currentSettings.showLabels,
            scale = currentSettings.iconScale,
            smoothAnimations = currentSettings.smoothAnimations
        )

        // При переключении в классический режим скрываем drawer полностью
        if (currentSettings.layoutMode == LayoutMode.CLASSIC) {
            closeAppDrawer()
        }

        // Док зависит от количества иконок, формы и режима раскладки
        refreshDock()

        updateHomeScreenApps()
    }

    /**
     * Определение слабого устройства. Нужно, чтобы не рисовать 6 градиентных
 * слоёв на каждой из ~60 стеклянных вьюх сетки и не держать 5 страниц
 * ViewPager в памяти.
 */
private fun isLowEndDevice(): Boolean {
        if (lowEndDeviceCache != null) return lowEndDeviceCache!!
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memClass = am.memoryClass
        val cores = Runtime.getRuntime().availableProcessors()
        val isLow = memClass <= 128 || cores <= 2
        lowEndDeviceCache = isLow
        return isLow
    }

    /** Рекурсивно собирает все LiquidGlassView в дереве вьюх. */
    private fun collectGlassViews(view: View, out: MutableList<LiquidGlassView> = mutableListOf()): MutableList<LiquidGlassView> {
        if (view is LiquidGlassView) out.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                collectGlassViews(view.getChildAt(i), out)
            }
        }
        return out
    }

    /**
     * Применяет настройку «жидкое стекло» ко всем вьюхам разом.
     *
     * Раньше стекло выставлялось только drawer'у и карточке папки, а кнопки
     * режима редактирования (Обои / Виджеты / Настройки / Удалить / Готово)
     * обновлялись исключительно внутри enterEditMode(). Поэтому после
     * сохранения настройки они оставались в старом состоянии до следующего
     * долгого тапа по экрану.
     */
    private fun applyGlassToAllViews() {
        val enabled = currentSettings.isGlassEnabled
        val quality = if (isLowEndDevice()) {
            LiquidGlassView.REDUCED_QUALITY
        } else {
            LiquidGlassView.FULL_QUALITY
        }
        for (glassView in collectGlassViews(binding.root)) {
            glassView.setQuality(quality)
            glassView.setGlassEnabled(enabled)
        }
        isBlurInitialized = true
    }

    private fun setupGlassmorphism() {
        // Обновляем все стеклянные вьюхи, включая кнопки режима редактирования
        applyGlassToAllViews()
    }

    private fun setupHomeScreenApps() {
        val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            currentSettings.gridColumns + 2
        } else {
            currentSettings.gridColumns
        }

        desktopPagerAdapter = DesktopPagerAdapter(
            pages = emptyList(),
            spanCount = spanCount,
            iconShape = currentSettings.iconShape,
            showLabels = currentSettings.showLabels,
            iconScale = currentSettings.iconScale,
            showClockWidget = currentSettings.showClockWidget,
            smoothAnimations = currentSettings.smoothAnimations,
            onAppClick = { item ->
                if (isEditMode) {
                    if (item.isFolder) {
                        showFolderViewDialog(item)
                    } else if (!item.isWidget) {
                        toggleAppSelection(item)
                    }
                } else if (item.isFolder) {
                    showFolderViewDialog(item)
                } else {
                    AppLoader.launchApp(this, item, smoothAnimations = currentSettings.smoothAnimations)
                }
            },
            onAppClickWithView = { item, view ->
                if (isEditMode) {
                    if (item.isFolder) {
                        showFolderViewDialog(item)
                    } else if (!item.isWidget) {
                        toggleAppSelection(item)
                    }
                } else if (item.isFolder) {
                    showFolderViewDialog(item)
                } else {
                    AppLoader.launchApp(this, item, view, smoothAnimations = currentSettings.smoothAnimations)
                }
            },
            onAppLongClick = { item, anchorView, _ ->
                if (item.isFolder) {
                    showFolderActionsPopup(item, anchorView)
                } else if (item.isWidget) {
                    val loc = IntArray(2)
                    anchorView.getLocationOnScreen(loc)
                    startDesktopDrag(item, anchorView, loc[0] + anchorView.width / 2f, loc[1] + anchorView.height / 2f)
                } else if (isEditMode) {
                    exitEditMode()
                } else {
                    showAppQuickActionsPopup(item, anchorView, isFromHomeScreen = true)
                }
            },
            onEmptyLongClick = {
                enterEditMode()
            },
            onTap = {
                handleScreenTap()
            },
            onSwipeDown = {
                if (currentSettings.swipeDownToNotifications) {
                    expandNotificationShade()
                }
            },
            onSwipeUp = {
                if (currentSettings.layoutMode == LayoutMode.DRAWER) {
                    openAppDrawer()
                }
            },
            onClockClick = { handleClockClick() },
            onDateClick = { handleDateClick() },
            onWeatherClick = { handleWeatherClick() },
            onItemsReordered = { pageIndex, newItems ->
                handleDesktopItemsReordered(pageIndex, newItems)
            },
            onEdgeHover = { direction, pageIndex, itemIndex ->
                handleEdgeHover(direction, pageIndex, itemIndex)
            },
            onEdgeHoverCancel = {
                cancelEdgeHover()
            },
            onAppStartDrag = { item, view, rawX, rawY ->
                startDesktopDrag(item, view, rawX, rawY)
            },
            onBindWidgetsForPage = { pageIndex, container ->
                bindWidgetsForPage(pageIndex, container)
            },
            onGetWidgetHostView = { widgetId, _ ->
                getOrCreateWidgetView(widgetId)
            },
            onSelectToggle = { item ->
                toggleAppSelection(item)
            }
        )

        binding.homeViewPager.adapter = desktopPagerAdapter
        // На слабых устройствах 5 удерживаемых страниц (~300 ячеек) — это
        // лишняя память и лишние layout-проходы; 2 страницы хватает для
        // плавного свайпа (текущая + соседняя + запас).
        binding.homeViewPager.offscreenPageLimit = if (isLowEndDevice()) 1 else 3

        // Dock adapter removed

        // Максимальное ускорение и ультра-плавность свайпа на 120 FPS
        val vpRecyclerView = binding.homeViewPager.getChildAt(0) as? RecyclerView
        vpRecyclerView?.apply {
            isNestedScrollingEnabled = false
            setHasFixedSize(true)
            // offscreenPageLimit = 3 держит до 5 страниц; на 4 ГБ это ~300
            // ячеек в памяти одновременно, поэтому кэш держим скромным
            setItemViewCacheSize(4)
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER

            // Снижаем порог начала свайпа (touch slop) вдвое, чтобы листание начиналось мгновенно
            try {
                val touchSlopField = RecyclerView::class.java.getDeclaredField("mTouchSlop")
                touchSlopField.isAccessible = true
                val slop = touchSlopField.getInt(this)
                touchSlopField.setInt(this, (slop * 0.45f).toInt().coerceAtLeast(4))
            } catch (e: Exception) {
                // ignore
            }
        }

        // Плавная аппаратная трансформация рабочего стола
        binding.homeViewPager.setPageTransformer { page, position ->
            if (currentSettings.smoothAnimations) {
                when {
                    position < -1 -> {
                        page.alpha = 0f
                    }
                    position <= 1 -> {
                        val absPos = kotlin.math.abs(position)
                        val scale = 0.96f + (1f - 0.96f) * (1f - absPos)
                        page.scaleX = scale
                        page.scaleY = scale
                        page.alpha = (1f - absPos * 0.25f).coerceIn(0f, 1f)
                    }
                    else -> {
                        page.alpha = 0f
                    }
                }
            } else {
                page.scaleX = 1f
                page.scaleY = 1f
                page.alpha = 1f
            }
        }

        binding.homeViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updatePageIndicator(position, desktopPagerAdapter.itemCount)
            }
        })
    }

    // Drag & Drop перемещение иконок и создание папок
    private var isDraggingDesktopItem = false
    private var activeDraggedItem: AppItem? = null
    private var activeDraggedGroup: List<AppItem> = emptyList()
    private var dragSourceView: View? = null
    private var dragSourcePageIndex: Int = 0
    private var dragSourceItemIndex: Int = 0
    private var dragSourceFolderId: String? = null
    private var isExtractedFromFolder: Boolean = false
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var hasMovedSignificantDistance = false
    private var currentHighlightedTargetItem: AppItem? = null
    private var currentHighlightedTargetPosition: Int = -1
    private var currentHighlightedTargetAdapter: AppsAdapter? = null
    private var lastPageSwitchTime = 0L

    // Drag & Drop перемещение системных виджетов
    private var isDraggingWidget = false
    private var activeDraggedWidgetId: Int = -1

    private var hasTemporaryDragPage = false
    private val folderDwellHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var folderDwellRunnable: Runnable? = null
    private var folderDwellTargetPos: Int = -1
    private var isFolderDwellArmed: Boolean = false
    private var currentFolderPageDragIndex: Int = -1
    private var activeFolderIdBeingViewed: String? = null

    private fun cancelFolderDwell() {
        folderDwellRunnable?.let { folderDwellHandler.removeCallbacks(it) }
        folderDwellRunnable = null
        folderDwellTargetPos = -1
        isFolderDwellArmed = false
    }

    private fun stripEmptyTrailingPages() {
        val ordered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
        if (ordered.isEmpty()) return

        val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            currentSettings.gridColumns + 2
        } else {
            currentSettings.gridColumns
        }
        val itemsPerPage = (spanCount * currentSettings.gridRows).coerceAtLeast(1)

        while (ordered.size > itemsPerPage) {
            val lastPageStart = ordered.size - itemsPerPage
            val lastPageEmpty = (lastPageStart until ordered.size).all { idx ->
                idx >= ordered.size || ordered[idx].isEmpty() || ordered[idx] == PreferencesManager.EMPTY_CELL_KEY
            }
            if (lastPageEmpty) {
                ordered.subList(lastPageStart, ordered.size).clear()
            } else {
                break
            }
        }
        prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
        updateHomeScreenApps()
    }

    private fun liveSwapInsideFolder(rawX: Float, rawY: Float) {
        val folderOverlay = binding.folderFullscreenOverlay
        val curPage = folderOverlay.vpFolderPages.currentItem
        val folderAdapter = folderOverlay.vpFolderPages.adapter as? FolderPagerAdapter ?: return
        val rv = folderAdapter.getRecyclerViewForPage(curPage) ?: return
        val rvAdapter = folderAdapter.getAdapterForPage(curPage) ?: return

        val loc = IntArray(2)
        rv.getLocationOnScreen(loc)
        val iconYOffset = 17f * resources.displayMetrics.density
        val localX = rawX - loc[0]
        val localY = rawY - iconYOffset - loc[1]

        val child = findChildOrNearest(rv, localX, localY) ?: return
        val targetPos = rv.getChildAdapterPosition(child)
        if (targetPos != RecyclerView.NO_POSITION && currentFolderPageDragIndex != -1 && targetPos != currentFolderPageDragIndex) {
            val items = rvAdapter.getItems()
            if (targetPos in items.indices && currentFolderPageDragIndex in items.indices) {
                val targetItem = items[targetPos]
                if (!targetItem.isEmpty) {
                    rvAdapter.swapItems(currentFolderPageDragIndex, targetPos)
                    currentFolderPageDragIndex = targetPos
                    binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (isDraggingDesktopItem) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    handleDragMove(ev.rawX, ev.rawY)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handleDragEnd(ev.rawX, ev.rawY)
                    return true
                }
            }
        }
        if (isDraggingWidget) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    handleWidgetDragMove(ev.rawX, ev.rawY)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handleWidgetDragEnd()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun cancelLiveSwapHover() {
        liveSwapRunnable?.let { liveSwapHandler.removeCallbacks(it) }
        liveSwapRunnable = null
        liveSwapTargetItem = null
    }

    private fun cancelFolderHover() {
        folderHoverRunnable?.let { folderHoverHandler.removeCallbacks(it) }
        folderHoverRunnable = null
        isFolderDropArmed = false
    }

    private fun startFolderItemDrag(item: AppItem, sourceView: View, rawX: Float, rawY: Float, folderId: String) {
        if (isDraggingDesktopItem) return
        cancelFolderHover()
        cancelLiveSwapHover()
        isDraggingDesktopItem = true
        activeDraggedItem = item
        activeDraggedGroup = listOf(item)
        dragSourceView = sourceView
        dragSourceFolderId = folderId
        isExtractedFromFolder = false
        dragSourcePageIndex = binding.homeViewPager.currentItem
        dragStartRawX = rawX
        dragStartRawY = rawY
        hasMovedSignificantDistance = false
        lastPageSwitchTime = SystemClock.uptimeMillis()

        binding.layoutTopDeletePill.visibility = View.GONE
        binding.tvDragBadgeCount.visibility = View.GONE
        binding.ivFloatingDragIcon.setImageDrawable(item.icon)
        binding.ivFloatingDragIcon.shapeAppearanceModel = AppsAdapter.getShapeModel(currentSettings.iconShape)
        binding.tvFloatingDragLabel.text = item.label
        binding.tvFloatingDragLabel.visibility = if (currentSettings.showLabels) View.VISIBLE else View.GONE

        val dragW = (76 * resources.displayMetrics.density).toInt()
        val dragH = (90 * resources.displayMetrics.density).toInt()
        binding.floatingDragView.layoutParams = FrameLayout.LayoutParams(dragW, dragH)
        binding.floatingDragView.translationX = rawX - dragW / 2f
        binding.floatingDragView.translationY = rawY - dragH / 2f
        binding.floatingDragView.scaleX = 1.12f
        binding.floatingDragView.scaleY = 1.12f
        binding.floatingDragView.alpha = 0.95f

        binding.floatingDragView.visibility = View.VISIBLE
        binding.floatingWidgetDragView.visibility = View.GONE
        binding.dragOverlayContainer.visibility = View.VISIBLE
        if (currentSettings.smoothAnimations) {
            sourceView.animate().alpha(0.25f).setDuration(100).start()
        } else {
            sourceView.alpha = 0.25f
        }
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun startDesktopDrag(
        item: AppItem,
        sourceView: View,
        rawX: Float,
        rawY: Float,
        fromDock: Boolean = false
    ) {
        if (isDraggingDesktopItem) return
        cancelFolderHover()
        cancelLiveSwapHover()
        isDraggingDesktopItem = true
        isDraggingFromDock = fromDock
        activeDraggedItem = item
        
        val dragSelKey = if (item.isFolder) "folder:${item.folderId}" else item.packageName
        if (!fromDock && isEditMode && selectedAppsForDrag.contains(dragSelKey)) {
            val orderedPackages = prefsManager.getHomeScreenOrderedPackages(currentSpace)
            activeDraggedGroup = selectedAppsForDrag.mapNotNull { key -> 
                resolveDesktopItemDirect(key)
            }.sortedBy { ai ->
                val k = if (ai.isFolder) "folder:${ai.folderId}" else ai.packageName
                val idx = orderedPackages.indexOf(k)
                if (idx != -1) idx else Int.MAX_VALUE
            }
        } else {
            activeDraggedGroup = listOf(item)
        }
        
        dragSourceView = sourceView
        dragSourcePageIndex = binding.homeViewPager.currentItem
        dragStartRawX = rawX
        dragStartRawY = rawY
        hasMovedSignificantDistance = false
        lastPageSwitchTime = SystemClock.uptimeMillis()

        desktopPagerAdapter.setDraggedItemPackage(item.packageName)
        // пересобираем сетку только когда тянут из неё: при перетаскивании из дока
        // это лишний полный ребиндинг и визуальное «дёрганье» иконок
        if (!fromDock) {
            updateHomeScreenApps()
        }

        val currentRv = desktopPagerAdapter.getRecyclerViewForPage(dragSourcePageIndex)
        val currentAdapter = currentRv?.adapter as? AppsAdapter
        dragSourceItemIndex = if (fromDock) {
            -1
        } else {
            currentAdapter?.getItems()?.indexOfFirst { it.packageName == item.packageName } ?: 0
        }

        if (item.isWidget) {
            binding.layoutTopDeletePill.visibility = View.VISIBLE
            binding.layoutTopDeletePill.scaleX = 1f
            binding.layoutTopDeletePill.scaleY = 1f
            binding.layoutTopDeletePill.setBackgroundResource(R.drawable.bg_top_delete_pill)
            if (currentSettings.smoothAnimations) {
                binding.layoutTopDeletePill.alpha = 0f
                binding.layoutTopDeletePill.translationY = -24f * resources.displayMetrics.density
                binding.layoutTopDeletePill.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
                    .start()
            } else {
                binding.layoutTopDeletePill.alpha = 1f
                binding.layoutTopDeletePill.translationY = 0f
            }
            isHoveringDeletePill = false

            try {
                val w = sourceView.width.coerceAtLeast(100)
                val h = sourceView.height.coerceAtLeast(100)
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                sourceView.draw(canvas)
                binding.ivFloatingWidgetPreview.setImageBitmap(bitmap)
                val dragW = (w * 0.95f).toInt()
                val dragH = (h * 0.95f).toInt()
                binding.floatingWidgetDragView.layoutParams = FrameLayout.LayoutParams(dragW, dragH)
                binding.floatingWidgetDragView.translationX = rawX - dragW / 2f
                binding.floatingWidgetDragView.translationY = rawY - dragH / 2f
                binding.floatingWidgetDragView.scaleX = 1.05f
                binding.floatingWidgetDragView.scaleY = 1.05f
                binding.floatingWidgetDragView.alpha = 0.92f
            } catch (e: Exception) {
                e.printStackTrace()
            }
            binding.floatingDragView.visibility = View.GONE
            binding.floatingWidgetDragView.visibility = View.VISIBLE
        } else {
            binding.layoutTopDeletePill.visibility = View.GONE
            binding.ivFloatingDragIcon.setImageDrawable(item.icon)
            binding.ivFloatingDragIcon.shapeAppearanceModel = AppsAdapter.getShapeModel(currentSettings.iconShape)
            binding.tvFloatingDragLabel.text = item.label
            binding.tvFloatingDragLabel.visibility = if (currentSettings.showLabels) View.VISIBLE else View.GONE

            val dragW = (76 * resources.displayMetrics.density).toInt()
            val dragH = (90 * resources.displayMetrics.density).toInt()
            binding.floatingDragView.layoutParams = FrameLayout.LayoutParams(dragW, dragH)
            binding.floatingDragView.translationX = rawX - dragW / 2f
            binding.floatingDragView.translationY = rawY - dragH / 2f
            binding.floatingDragView.scaleX = 1.12f
            binding.floatingDragView.scaleY = 1.12f
            binding.floatingDragView.alpha = 0.95f

            val dragCount = activeDraggedGroup.size
            if (dragCount > 1) {
                binding.tvDragBadgeCount.text = "+${dragCount - 1}"
                binding.tvDragBadgeCount.visibility = View.VISIBLE
            } else {
                binding.tvDragBadgeCount.visibility = View.GONE
            }

            binding.floatingDragView.visibility = View.VISIBLE
            binding.floatingWidgetDragView.visibility = View.GONE
        }

        binding.dragOverlayContainer.visibility = View.VISIBLE
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }

    private fun checkDeletePillHover(rawX: Float, rawY: Float): Boolean {
        if (binding.layoutTopDeletePill.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        binding.layoutTopDeletePill.getLocationOnScreen(loc)
        val pillX = loc[0].toFloat()
        val pillY = loc[1].toFloat()
        val pillW = binding.layoutTopDeletePill.width.toFloat()
        val pillH = binding.layoutTopDeletePill.height.toFloat()
        val slop = 24f * resources.displayMetrics.density
        return (rawX >= pillX - slop && rawX <= pillX + pillW + slop &&
                rawY >= pillY - slop && rawY <= pillY + pillH + slop)
    }

    private fun findChildOrNearest(rv: androidx.recyclerview.widget.RecyclerView, x: Float, y: Float): View? {
        val direct = rv.findChildViewUnder(x, y)
        if (direct != null) return direct

        val density = resources.displayMetrics.density
        val tolerance = 16f * density
        val offsets = arrayOf(
            Pair(0f, -tolerance), Pair(0f, tolerance),
            Pair(-tolerance, 0f), Pair(tolerance, 0f),
            Pair(-tolerance, -tolerance), Pair(tolerance, tolerance),
            Pair(-tolerance, tolerance), Pair(tolerance, -tolerance)
        )
        for (offset in offsets) {
            val candidate = rv.findChildViewUnder(x + offset.first, y + offset.second)
            if (candidate != null) return candidate
        }

        var closestChild: View? = null
        var minDistanceSq = Float.MAX_VALUE
        val maxAcceptableDistance = 64f * density
        val maxDistSq = maxAcceptableDistance * maxAcceptableDistance

        for (i in 0 until rv.childCount) {
            val child = rv.getChildAt(i) ?: continue
            val childCenterX = (child.left + child.right) / 2f
            val childCenterY = (child.top + child.bottom) / 2f
            val dx = x - childCenterX
            val dy = y - childCenterY
            val distSq = dx * dx + dy * dy
            if (distSq < minDistanceSq && distSq <= maxDistSq) {
                minDistanceSq = distSq
                closestChild = child
            }
        }
        return closestChild
    }

    private fun handleDragMove(rawX: Float, rawY: Float) {
        if (!isDraggingDesktopItem) return
        val diffX = Math.abs(rawX - dragStartRawX)
        val diffY = Math.abs(rawY - dragStartRawY)
        val threshold = 10 * resources.displayMetrics.density
        if (!hasMovedSignificantDistance && (diffX > threshold || diffY > threshold)) {
            hasMovedSignificantDistance = true
            if (activeDraggedItem?.isWidget != true) {
                if (currentSettings.smoothAnimations) {
                    dragSourceView?.animate()?.alpha(0.25f)?.setDuration(100)?.start()
                } else {
                    dragSourceView?.alpha = 0.25f
                }
            }
        }

        if (activeDraggedItem?.isWidget == true) {
            val dragW = binding.floatingWidgetDragView.width.toFloat().let { if (it > 0) it else 200f }
            val dragH = binding.floatingWidgetDragView.height.toFloat().let { if (it > 0) it else 100f }
            binding.floatingWidgetDragView.translationX = rawX - dragW / 2f
            binding.floatingWidgetDragView.translationY = rawY - dragH / 2f

            val hovering = checkDeletePillHover(rawX, rawY)
            if (hovering != isHoveringDeletePill) {
                isHoveringDeletePill = hovering
                if (hovering) {
                    binding.widgetDropTargetPreview.visibility = View.GONE
                    binding.layoutTopDeletePill.setBackgroundResource(R.drawable.bg_top_delete_pill_hover)
                    if (currentSettings.smoothAnimations) {
                        binding.layoutTopDeletePill.animate().scaleX(1.12f).scaleY(1.12f).setDuration(120).start()
                    } else {
                        binding.layoutTopDeletePill.scaleX = 1.12f
                        binding.layoutTopDeletePill.scaleY = 1.12f
                    }
                    binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                } else {
                    binding.layoutTopDeletePill.setBackgroundResource(R.drawable.bg_top_delete_pill)
                    if (currentSettings.smoothAnimations) {
                        binding.layoutTopDeletePill.animate().scaleX(1.0f).scaleY(1.0f).setDuration(120).start()
                    } else {
                        binding.layoutTopDeletePill.scaleX = 1.0f
                        binding.layoutTopDeletePill.scaleY = 1.0f
                    }
                }
            }
        } else {
            val dragW = binding.floatingDragView.width.toFloat().let { if (it > 0) it else 76f * resources.displayMetrics.density }
            val dragH = binding.floatingDragView.height.toFloat().let { if (it > 0) it else 90f * resources.displayMetrics.density }
            binding.floatingDragView.translationX = rawX - dragW / 2f
            binding.floatingDragView.translationY = rawY - dragH / 2f
        }

        // Detect dragging an icon out of folder
        if (dragSourceFolderId != null && !isExtractedFromFolder) {
            val card = binding.folderFullscreenOverlay.folderCardWindow
            val cardLoc = IntArray(2)
            card.getLocationOnScreen(cardLoc)
            val isInsideFolder = (rawX >= cardLoc[0] && rawX <= cardLoc[0] + card.width &&
                                  rawY >= cardLoc[1] && rawY <= cardLoc[1] + card.height)
            if (!isInsideFolder) {
                isExtractedFromFolder = true
                val currentFolderId = dragSourceFolderId!!
                val folder = prefsManager.getFolders(currentSpace)[currentFolderId]
                if (folder != null) {
                    folder.packageNames.remove(activeDraggedItem?.packageName)
                    prefsManager.saveFolder(currentSpace, folder)
                }
                // Immediately hide folder overlay completely so desktop touch and preview are 100% unblocked
                binding.folderFullscreenOverlay.root.visibility = View.GONE
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            } else {
                liveSwapInsideFolder(rawX, rawY)
            }
        }

        val screenWidth = resources.displayMetrics.widthPixels
        val edgeMargin = 50f * resources.displayMetrics.density
        val now = SystemClock.uptimeMillis()

        // Попадание в док: подсвечиваем капсулу и не занимаемся подсветкой ячеек
        val overDock = dockAcceptsDrop() && dockController.contains(rawX, rawY)
        if (overDock != isHoveringDock) {
            isHoveringDock = overDock
            setDockDropHighlight(overDock)
            if (overDock) {
                clearCurrentHighlight()
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        if (overDock) return

        if (now - lastPageSwitchTime > 550L) {
            val curPage = binding.homeViewPager.currentItem
            val totalPages = desktopPagerAdapter.itemCount
            if (rawX > screenWidth - edgeMargin) {
                if (curPage < totalPages - 1) {
                    lastPageSwitchTime = now
                    binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    binding.homeViewPager.setCurrentItem(curPage + 1, true)
                } else if (!hasTemporaryDragPage) {
                    hasTemporaryDragPage = true
                    lastPageSwitchTime = now
                    val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        currentSettings.gridColumns + 2
                    } else {
                        currentSettings.gridColumns
                    }
                    val itemsPerPage = (spanCount * currentSettings.gridRows).coerceAtLeast(1)
                    val ordered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
                    val neededSize = ordered.size + itemsPerPage
                    while (ordered.size < neededSize) ordered.add("")
                    prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
                    updateHomeScreenApps()
                    binding.homeViewPager.post {
                        binding.homeViewPager.setCurrentItem(totalPages, true)
                    }
                    binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
            } else if (rawX < edgeMargin && curPage > 0) {
                lastPageSwitchTime = now
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                binding.homeViewPager.setCurrentItem(curPage - 1, true)
            }
        }

        if (isExtractedFromFolder || binding.folderFullscreenOverlay.root.visibility != View.VISIBLE) {
            liveSwapWith(rawX, rawY)
        }
    }

    private fun liveSwapWith(rawX: Float, rawY: Float) {
        val currentVisiblePage = binding.homeViewPager.currentItem
        val rv = desktopPagerAdapter.getRecyclerViewForPage(currentVisiblePage) ?: return

        val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            currentSettings.gridColumns + 2
        } else {
            currentSettings.gridColumns
        }
        val rows = currentSettings.gridRows.coerceAtLeast(1)
        val gridW = (rv.width - rv.paddingLeft - rv.paddingRight).toFloat().coerceAtLeast(1f)
        val gridH = (rv.height - rv.paddingTop - rv.paddingBottom).toFloat().coerceAtLeast(1f)
        val cellW = gridW / spanCount
        val cellH = gridH / rows

        val loc = IntArray(2)
        rv.getLocationOnScreen(loc)

        val isWidget = activeDraggedItem?.isWidget == true

        // Widget dragging: calculate blueprint drop target outline preview anchored to widget's top row
        if (isWidget) {
            val wSpanX = (activeDraggedItem?.widgetSpanX ?: spanCount).coerceIn(1, spanCount)
            val wSpanY = (activeDraggedItem?.widgetSpanY ?: 2).coerceIn(1, rows)
            val dragW = binding.floatingWidgetDragView.width.toFloat().let { if (it > 0) it else cellW * wSpanX }
            val dragH = binding.floatingWidgetDragView.height.toFloat().let { if (it > 0) it else cellH * wSpanY }

            // Верхний левый угол перетаскиваемого виджета
            val widgetLeft = rawX - dragW / 2f
            val widgetTop = rawY - dragH / 2f

            // Точка привязки — центр первой (верхней левой) ячейки виджета
            val checkX = widgetLeft + cellW / 2f
            val checkY = widgetTop + cellH / 2f

            val rvX = checkX - loc[0]
            val rvY = checkY - loc[1]

            val col = ((rvX - rv.paddingLeft) / cellW).toInt().coerceIn(0, spanCount - wSpanX)
            val row = ((rvY - rv.paddingTop) / cellH).toInt().coerceIn(0, rows - wSpanY)
            val targetPosition = row * spanCount + col

            val targetAdapter = rv.adapter as? AppsAdapter
            val targetItem = targetAdapter?.getItem(targetPosition) ?: AppItem.empty()

            clearCurrentHighlight()
            currentHighlightedTargetAdapter = targetAdapter
            currentHighlightedTargetPosition = targetPosition
            currentHighlightedTargetItem = targetItem
            currentHighlightMode = HighlightMode.GHOST

            val targetLeft = loc[0] + rv.paddingLeft + col * cellW
            val targetTop = loc[1] + rv.paddingTop + row * cellH
            val targetWidth = (cellW * wSpanX).toInt()
            val targetHeight = (cellH * wSpanY).toInt()

            val dLp = FrameLayout.LayoutParams(targetWidth, targetHeight)
            binding.widgetDropTargetPreview.layoutParams = dLp
            binding.widgetDropTargetPreview.translationX = targetLeft
            binding.widgetDropTargetPreview.translationY = targetTop
            binding.widgetDropTargetPreview.visibility = if (isHoveringDeletePill) View.GONE else View.VISIBLE
            return
        } else {
            binding.widgetDropTargetPreview.visibility = View.GONE
        }

        // Корректировка Y-координаты: при перемещении иконок центр иконки
        // находится выше пальца, т.к. плавающая иконка (90dp) содержит иконку (56dp) вверху.
        // Центр иконки = 28dp от верха, центр view = 45dp → разница = 17dp вверх от пальца.
        val iconYOffset = 17f * resources.displayMetrics.density
        val adjustedRawY = rawY - iconYOffset

        val rvX = rawX - loc[0]
        val rvY = adjustedRawY - loc[1]

        val child = findChildOrNearest(rv, rvX, rvY)
        if (child == null) {
            clearCurrentHighlight()
            return
        }

        val targetIndex = rv.getChildAdapterPosition(child)
        if (targetIndex == androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
            clearCurrentHighlight()
            return
        }

        val targetAdapter = rv.adapter as? AppsAdapter ?: return
        val targetItem = targetAdapter.getItem(targetIndex) ?: run {
            clearCurrentHighlight()
            return
        }

        // Dragging over source position itself on same page
        if (!isExtractedFromFolder && currentVisiblePage == dragSourcePageIndex && targetIndex == dragSourceItemIndex) {
            clearCurrentHighlight()
            return
        }

        // Cannot drop app onto widget
        if (targetItem.isWidget) {
            clearCurrentHighlight()
            return
        }

        // Target is empty cell: show ghost preview
        if (targetItem.isEmpty) {
            highlightItem(targetAdapter, targetIndex, targetItem, HighlightMode.GHOST)
            return
        }

        // Target is existing folder
        if (targetItem.isFolder) {
            if (activeDraggedItem?.isFolder == true) {
                cancelFolderDwell()
                highlightItem(targetAdapter, targetIndex, targetItem, HighlightMode.SWAP)
            } else {
                highlightItem(targetAdapter, targetIndex, targetItem, HighlightMode.FOLDER)
            }
            return
        }

        // Target is an app icon
        val childLoc = IntArray(2)
        child.getLocationOnScreen(childLoc)
        val localX = rawX - childLoc[0]
        val localY = adjustedRawY - childLoc[1]

        val iconView = child.findViewById<View>(R.id.appIcon) ?: child
        val iconCenterX = (iconView.left + iconView.right) / 2f
        val iconCenterY = (iconView.top + iconView.bottom) / 2f
        val distToCenter = Math.hypot((localX - iconCenterX).toDouble(), (localY - iconCenterY).toDouble()).toFloat()

        val density = resources.displayMetrics.density
        val folderRadius = if (currentHighlightMode == HighlightMode.FOLDER && currentHighlightedTargetPosition == targetIndex) {
            44f * density
        } else {
            36f * density
        }

        if (activeDraggedItem?.isFolder == true) {
            cancelFolderDwell()
            highlightItem(targetAdapter, targetIndex, targetItem, HighlightMode.SWAP)
        } else if (distToCenter <= folderRadius) {
            if (currentHighlightMode == HighlightMode.FOLDER && currentHighlightedTargetPosition == targetIndex) {
                // Уже активирован режим папки
            } else if (folderDwellTargetPos != targetIndex) {
                cancelFolderDwell()
                folderDwellTargetPos = targetIndex
                highlightItem(targetAdapter, targetIndex, targetItem, HighlightMode.SWAP)
                folderDwellRunnable = Runnable {
                    isFolderDwellArmed = true
                    highlightItem(targetAdapter, targetIndex, targetItem, HighlightMode.FOLDER)
                    binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                }
                folderDwellHandler.postDelayed(folderDwellRunnable!!, 200L)
            }
        } else {
            cancelFolderDwell()
            highlightItem(targetAdapter, targetIndex, targetItem, HighlightMode.SWAP)
        }
    }

    private fun handleDragEnd(rawX: Float, rawY: Float) {
        if (!isDraggingDesktopItem) return
        isDraggingDesktopItem = false
        desktopPagerAdapter.setDraggedItemPackage(null)
        binding.widgetDropTargetPreview.visibility = View.GONE
        cancelFolderHover()
        cancelLiveSwapHover()

        val sourceItem = activeDraggedItem
        val targetItem = currentHighlightedTargetItem
        val tPos = currentHighlightedTargetPosition
        val dropMode = currentHighlightMode
        clearCurrentHighlight()

        val hoveringPill = isHoveringDeletePill || checkDeletePillHover(rawX, rawY)

        // Дроп в док обрабатываем раньше всего: это самый частый вариант
        // при переносе иконки, и он не должен зависеть от состояния сетки
        val droppingOnDock = isHoveringDock || (dockAcceptsDrop() && dockController.contains(rawX, rawY))
        if (droppingOnDock) {
            isHoveringDock = false
            setDockDropHighlight(false)
            if (handleDropOnDock()) {
                resetDragState()
                return
            }
        }

        // Drop widget on delete pill -> suck-in poof animation + pill pulse
        if (sourceItem?.isWidget == true && hoveringPill) {
            val widgetId = sourceItem.widgetId ?: -1
            if (widgetId != -1) {
                val pillLoc = IntArray(2)
                binding.layoutTopDeletePill.getLocationOnScreen(pillLoc)
                val targetPillCenterX = pillLoc[0] + binding.layoutTopDeletePill.width / 2f
                val targetPillCenterY = pillLoc[1] + binding.layoutTopDeletePill.height / 2f

                if (currentSettings.smoothAnimations) {
                    binding.layoutTopDeletePill.animate()
                        .scaleX(1.22f)
                        .scaleY(1.22f)
                        .setDuration(120)
                        .withEndAction {
                            binding.layoutTopDeletePill.animate()
                                .scaleX(0.9f)
                                .scaleY(0.9f)
                                .alpha(0f)
                                .translationY(-24f * resources.displayMetrics.density)
                                .setDuration(160)
                                .withEndAction {
                                    binding.layoutTopDeletePill.visibility = View.GONE
                                    binding.layoutTopDeletePill.scaleX = 1f
                                    binding.layoutTopDeletePill.scaleY = 1f
                                }
                                .start()
                        }
                        .start()

                    binding.floatingWidgetDragView.animate()
                        .translationX(targetPillCenterX - binding.floatingWidgetDragView.width / 2f)
                        .translationY(targetPillCenterY - binding.floatingWidgetDragView.height / 2f)
                        .scaleX(0.08f)
                        .scaleY(0.08f)
                        .alpha(0f)
                        .setDuration(220)
                        .withEndAction {
                            binding.dragOverlayContainer.visibility = View.GONE
                            binding.floatingWidgetDragView.visibility = View.GONE
                            binding.floatingDragView.visibility = View.GONE
                            deleteWidgetPermanently(widgetId)
                            resetDragState()
                        }
                        .start()
                } else {
                    binding.layoutTopDeletePill.visibility = View.GONE
                    binding.layoutTopDeletePill.scaleX = 1f
                    binding.layoutTopDeletePill.scaleY = 1f
                    binding.dragOverlayContainer.visibility = View.GONE
                    binding.floatingWidgetDragView.visibility = View.GONE
                    binding.floatingDragView.visibility = View.GONE
                    deleteWidgetPermanently(widgetId)
                    resetDragState()
                }
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                return
            }
        }

        binding.dragOverlayContainer.visibility = View.GONE
        binding.floatingDragView.visibility = View.GONE
        binding.floatingWidgetDragView.visibility = View.GONE
        if (currentSettings.smoothAnimations) {
            dragSourceView?.animate()?.alpha(1.0f)?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(100)?.start()
        } else {
            dragSourceView?.alpha = 1.0f
            dragSourceView?.scaleX = 1.0f
            dragSourceView?.scaleY = 1.0f
        }

        if (dragSourceFolderId != null && !isExtractedFromFolder) {
            val folderId = dragSourceFolderId!!
            val folder = prefsManager.getFolders(currentSpace)[folderId]
            if (folder != null) {
                if (hasMovedSignificantDistance) {
                    val folderOverlay = binding.folderFullscreenOverlay
                    val folderAdapter = folderOverlay.vpFolderPages.adapter as? FolderPagerAdapter
                    val allItems = folderAdapter?.getAllItems() ?: emptyList()
                    val newPkgs = allItems.filter { !it.isEmpty }.map { it.packageName }
                    if (newPkgs.isNotEmpty()) {
                        folder.packageNames.clear()
                        folder.packageNames.addAll(newPkgs)
                        prefsManager.saveFolder(currentSpace, folder)
                        updateHomeScreenApps()
                    }
                } else if (sourceItem != null) {
                    showAppInFolderActionsPopup(sourceItem, folder) {
                        val updatedItem = resolveDesktopItemDirect("folder:$folderId")
                        if (updatedItem != null) {
                            showFolderViewDialog(updatedItem)
                        } else {
                            closeFolderView()
                        }
                    }
                }
            }
            dragSourceFolderId = null
            currentFolderPageDragIndex = -1
            if (hasTemporaryDragPage) {
                hasTemporaryDragPage = false
                stripEmptyTrailingPages()
            }
            resetDragState()
            return
        }

        if (sourceItem == null || !hasMovedSignificantDistance) {
            if (sourceItem != null && !hasMovedSignificantDistance && dragSourceFolderId == null) {
                if (sourceItem.isWidget) {
                    showWidgetActionsDialog(sourceItem.widgetId ?: -1, dragSourcePageIndex)
                } else {
                    showAppQuickActionsPopup(sourceItem, dragSourceView ?: binding.root, true)
                }
            }
            if (binding.layoutTopDeletePill.visibility == View.VISIBLE) {
                if (currentSettings.smoothAnimations) {
                    binding.layoutTopDeletePill.animate()
                        .alpha(0f)
                        .translationY(-24f * resources.displayMetrics.density)
                        .setDuration(150)
                        .withEndAction { binding.layoutTopDeletePill.visibility = View.GONE }
                        .start()
                } else {
                    binding.layoutTopDeletePill.visibility = View.GONE
                    binding.layoutTopDeletePill.alpha = 1f
                    binding.layoutTopDeletePill.translationY = 0f
                }
            }
            if (hasTemporaryDragPage) {
                hasTemporaryDragPage = false
                stripEmptyTrailingPages()
            }
            resetDragState()
            binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            return
        }

        // Dragged a widget
        if (sourceItem.isWidget) {
            val widgetId = sourceItem.widgetId ?: -1
            if (currentSettings.smoothAnimations) {
                binding.layoutTopDeletePill.animate()
                    .alpha(0f)
                    .translationY(-24f * resources.displayMetrics.density)
                    .setDuration(150)
                    .withEndAction { binding.layoutTopDeletePill.visibility = View.GONE }
                    .start()
            } else {
                binding.layoutTopDeletePill.visibility = View.GONE
                binding.layoutTopDeletePill.alpha = 1f
                binding.layoutTopDeletePill.translationY = 0f
            }

            if (widgetId != -1) {
                val targetPage = binding.homeViewPager.currentItem
                val spanCount = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                    currentSettings.gridColumns + 2
                } else {
                    currentSettings.gridColumns
                }
                val rows = currentSettings.gridRows.coerceAtLeast(1)
                val itemsPerPage = (spanCount * rows).coerceAtLeast(1)
                val ordered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
                val widgetKey = "widget:$widgetId"
                val sIdx = ordered.indexOf(widgetKey)

                val targetRv = desktopPagerAdapter.getRecyclerViewForPage(targetPage)
                var targetSlot = tPos
                if (targetSlot == -1 && targetRv != null && targetRv.width > 0 && targetRv.height > 0) {
                    val loc = IntArray(2)
                    targetRv.getLocationOnScreen(loc)
                    val wSpanX = (sourceItem.widgetSpanX).coerceIn(1, spanCount)
                    val wSpanY = (sourceItem.widgetSpanY).coerceIn(1, rows)
                    val gridW = (targetRv.width - targetRv.paddingLeft - targetRv.paddingRight).toFloat().coerceAtLeast(1f)
                    val gridH = (targetRv.height - targetRv.paddingTop - targetRv.paddingBottom).toFloat().coerceAtLeast(1f)
                    val cellW = gridW / spanCount
                    val cellH = gridH / rows
                    val dragW = binding.floatingWidgetDragView.width.toFloat().let { if (it > 0) it else cellW * wSpanX }
                    val dragH = binding.floatingWidgetDragView.height.toFloat().let { if (it > 0) it else cellH * wSpanY }
                    val widgetLeft = rawX - dragW / 2f
                    val widgetTop = rawY - dragH / 2f
                    val checkX = widgetLeft + cellW / 2f
                    val checkY = widgetTop + cellH / 2f
                    val rvX = checkX - loc[0]
                    val rvY = checkY - loc[1]
                    val col = ((rvX - targetRv.paddingLeft) / cellW).toInt().coerceIn(0, spanCount - wSpanX)
                    val row = ((rvY - targetRv.paddingTop) / cellH).toInt().coerceIn(0, rows - wSpanY)
                    targetSlot = (row * spanCount + col).coerceIn(0, itemsPerPage - 1)
                }
                if (targetSlot == -1) targetSlot = 0

                val wSpanY = (sourceItem.widgetSpanY).coerceIn(1, rows)
                val wCells = spanCount * wSpanY

                val targetRow = (targetSlot / spanCount).coerceIn(0, rows - wSpanY)
                val alignedTargetSlot = targetRow * spanCount
                val targetStart = targetPage * itemsPerPage + alignedTargetSlot
                val targetEnd = targetStart + wCells

                // 1. Очищаем виджет со старой позиции
                if (sIdx != -1) {
                    ordered[sIdx] = ""
                }

                while (ordered.size < targetEnd) {
                    ordered.add("")
                }

                // 2. Собираем все непустые элементы (иконки, папки, другие виджеты), попадающие под область нового виджета
                val displacedItems = mutableListOf<String>()
                for (idx in targetStart until targetEnd) {
                    val key = ordered[idx]
                    if (key.isNotEmpty() && key != PreferencesManager.EMPTY_CELL_KEY && key != widgetKey) {
                        displacedItems.add(key)
                    }
                    ordered[idx] = ""
                }

                // 3. Устанавливаем перетаскиваемый виджет на целевую позицию
                ordered[targetStart] = widgetKey

                // 4. Эвакуируем все вытесненные элементы в свободные ячейки
                if (displacedItems.isNotEmpty()) {
                    val coveredSlots = getAllWidgetCoveredSlots(ordered, spanCount)
                    val pageStart = targetPage * itemsPerPage
                    val pageEnd = pageStart + itemsPerPage

                    for (displacedKey in displacedItems) {
                        if (displacedKey.startsWith("widget:")) {
                            val otherWId = displacedKey.removePrefix("widget:").toIntOrNull()
                            val otherSpanY = if (otherWId != null) {
                                widgetMetaCache[otherWId]?.second?.second ?: run {
                                    val info = appWidgetManager.getAppWidgetInfo(otherWId)
                                    getWidgetSpan(info, spanCount).second
                                }
                            } else 2
                            val otherWCells = spanCount * otherSpanY

                            var checkRow = 0
                            while (checkRow < 100) {
                                val rowSlot = checkRow * spanCount
                                if (rowSlot !in targetStart until targetEnd) {
                                    var rowCanFit = true
                                    for (c in 0 until otherWCells) {
                                        val s = rowSlot + c
                                        if (s in targetStart until targetEnd || coveredSlots.contains(s) || (s < ordered.size && ordered[s].isNotEmpty() && ordered[s] != PreferencesManager.EMPTY_CELL_KEY)) {
                                            rowCanFit = false
                                            break
                                        }
                                    }
                                    if (rowCanFit) {
                                        while (ordered.size <= rowSlot + otherWCells) ordered.add("")
                                        ordered[rowSlot] = displacedKey
                                        if (otherWId != null) prefsManager.setWidgetPage(otherWId, rowSlot / itemsPerPage)
                                        break
                                    }
                                }
                                checkRow++
                            }
                        } else {
                            var placed = false

                            // Обычное приложение или папка: ищем свободную ячейку
                            // А) Сначала на текущей странице ниже виджета
                            for (slot in targetEnd until pageEnd) {
                                if (slot < ordered.size && (ordered[slot].isEmpty() || ordered[slot] == PreferencesManager.EMPTY_CELL_KEY) && !coveredSlots.contains(slot)) {
                                    ordered[slot] = displacedKey
                                    placed = true
                                    break
                                }
                            }

                            // Б) На текущей странице выше виджета (например, если освободилось старое место виджета)
                            if (!placed) {
                                for (slot in pageStart until targetStart) {
                                    if (slot < ordered.size && (ordered[slot].isEmpty() || ordered[slot] == PreferencesManager.EMPTY_CELL_KEY) && !coveredSlots.contains(slot)) {
                                        ordered[slot] = displacedKey
                                        placed = true
                                        break
                                    }
                                }
                            }

                            // В) На последующих страницах
                            if (!placed) {
                                var searchIdx = pageEnd
                                while (searchIdx < ordered.size) {
                                    if ((ordered[searchIdx].isEmpty() || ordered[searchIdx] == PreferencesManager.EMPTY_CELL_KEY) && !coveredSlots.contains(searchIdx)) {
                                        ordered[searchIdx] = displacedKey
                                        placed = true
                                        break
                                    }
                                    searchIdx++
                                }
                            }

                            // Г) Если все страницы заполнены — добавляем новую ячейку в конец
                            if (!placed) {
                                while (coveredSlots.contains(ordered.size)) {
                                    ordered.add("")
                                }
                                ordered.add(displacedKey)
                            }
                        }
                    }
                }

                prefsManager.setWidgetPage(widgetId, targetPage)
                prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
                updateHomeScreenApps()
                desktopPagerAdapter.refreshWidgets()
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
            if (hasTemporaryDragPage) {
                hasTemporaryDragPage = false
                stripEmptyTrailingPages()
            }
            resetDragState()
            return
        }

        // Multi-select batch drop
        if (activeDraggedGroup.size > 1) {
            val ordered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()

            // Очистка пустой папки-источника
            if (dragSourceFolderId != null) {
                val f = prefsManager.getFolders(currentSpace)[dragSourceFolderId]
                if (f != null && f.packageNames.isEmpty()) {
                    prefsManager.deleteFolder(currentSpace, dragSourceFolderId!!)
                    val fIdx = ordered.indexOf("folder:$dragSourceFolderId")
                    if (fIdx != -1) ordered[fIdx] = ""
                }
                dragSourceFolderId = null
            }

            val targetPage = binding.homeViewPager.currentItem
            val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                currentSettings.gridColumns + 2
            } else {
                currentSettings.gridColumns
            }
            val itemsPerPage = (spanCount * currentSettings.gridRows).coerceAtLeast(1)

            // 1. Собираем ключи группы
            val groupKeys = activeDraggedGroup.map { ai ->
                if (ai.isFolder) "folder:${ai.folderId}" else ai.packageName
            }.filter { it.isNotEmpty() }

            // 2. Убираем все элементы группы из ordered
            for (key in groupKeys) {
                val idx = ordered.indexOf(key)
                if (idx != -1) ordered[idx] = ""
            }

            // 3. Вычисляем целевую позицию
            val targetRv = desktopPagerAdapter.getRecyclerViewForPage(targetPage)
            var targetCellPos = if (tPos != -1) tPos else {
                if (targetRv != null && targetRv.width > 0 && targetRv.height > 0) {
                    val loc = IntArray(2)
                    targetRv.getLocationOnScreen(loc)
                    val rows = currentSettings.gridRows.coerceAtLeast(1)
                    val cellW = (targetRv.width - targetRv.paddingLeft - targetRv.paddingRight).toFloat() / spanCount
                    val cellH = (targetRv.height - targetRv.paddingTop - targetRv.paddingBottom).toFloat() / rows
                    val iconYOffset = 17f * resources.displayMetrics.density
                    val col = ((rawX - loc[0] - targetRv.paddingLeft) / cellW).toInt().coerceIn(0, spanCount - 1)
                    val row = ((rawY - iconYOffset - loc[1] - targetRv.paddingTop) / cellH).toInt().coerceIn(0, rows - 1)
                    (row * spanCount + col).coerceIn(0, itemsPerPage - 1)
                } else 0
            }
            var targetIdx = targetPage * itemsPerPage + targetCellPos

            // 4. Размещаем элементы последовательно
            val coveredSlots = getAllWidgetCoveredSlots(ordered, spanCount)
            for (key in groupKeys) {
                while (ordered.size <= targetIdx) ordered.add("")
                if (ordered[targetIdx].isEmpty() && !coveredSlots.contains(targetIdx)) {
                    ordered[targetIdx] = key
                } else {
                    displaceAndInsert(ordered, key, targetIdx)
                }
                targetIdx++
                while (targetIdx < ordered.size && coveredSlots.contains(targetIdx)) targetIdx++
            }

            prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
            selectedAppsForDrag.clear()
            desktopPagerAdapter.setEditMode(isEditMode, selectedAppsForDrag)
            updateHomeScreenApps()
            if (hasTemporaryDragPage) {
                hasTemporaryDragPage = false
                stripEmptyTrailingPages()
            }
            resetDragState()
            binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            return
        }

        // Normal app or extracted from folder
        val ordered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
        val sourceKey = if (sourceItem.isFolder) "folder:${sourceItem.folderId}" else sourceItem.packageName
        val sourceIdx = ordered.indexOf(sourceKey)

        // Своевременная очистка опустевшей папки прямо в ordered в памяти до сброса и сохранения
        if (dragSourceFolderId != null) {
            val f = prefsManager.getFolders(currentSpace)[dragSourceFolderId]
            if (f != null && f.packageNames.isEmpty()) {
                prefsManager.deleteFolder(currentSpace, dragSourceFolderId!!)
                val fIdx = ordered.indexOf("folder:$dragSourceFolderId")
                if (fIdx != -1) {
                    ordered[fIdx] = ""
                }
            }
            dragSourceFolderId = null
        }

        val targetPage = binding.homeViewPager.currentItem
        val spanCount = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            currentSettings.gridColumns + 2
        } else {
            currentSettings.gridColumns
        }
        val itemsPerPage = (spanCount * currentSettings.gridRows).coerceAtLeast(1)

        val targetRv = desktopPagerAdapter.getRecyclerViewForPage(targetPage)
        var fallbackCellPos = tPos
        if (fallbackCellPos == -1 && targetRv != null && targetRv.width > 0 && targetRv.height > 0) {
            val loc = IntArray(2)
            targetRv.getLocationOnScreen(loc)
            val rows = currentSettings.gridRows.coerceAtLeast(1)
            val gridW = (targetRv.width - targetRv.paddingLeft - targetRv.paddingRight).toFloat().coerceAtLeast(1f)
            val gridH = (targetRv.height - targetRv.paddingTop - targetRv.paddingBottom).toFloat().coerceAtLeast(1f)
            val cellW = gridW / spanCount
            val cellH = gridH / rows
            val iconYOffset = if (!sourceItem.isWidget) 17f * resources.displayMetrics.density else 0f
            val rvX = rawX - loc[0]
            val rvY = rawY - iconYOffset - loc[1]
            val col = ((rvX - targetRv.paddingLeft) / cellW).toInt().coerceIn(0, spanCount - 1)
            val row = ((rvY - targetRv.paddingTop) / cellH).toInt().coerceIn(0, rows - 1)
            fallbackCellPos = (row * spanCount + col).coerceIn(0, itemsPerPage - 1)
        }
        if (fallbackCellPos == -1) fallbackCellPos = 0
        val targetIdxInOrdered = targetPage * itemsPerPage + fallbackCellPos

        val effectiveDropMode = if (dropMode == HighlightMode.FOLDER && sourceItem.isFolder) {
            HighlightMode.SWAP
        } else {
            dropMode
        }
        cancelFolderDwell()

        when (effectiveDropMode) {
            HighlightMode.FOLDER -> {
                if (targetItem != null && !targetItem.isEmpty && targetItem.packageName != sourceItem.packageName && !targetItem.isWidget && !sourceItem.isFolder) {
                    if (targetItem.isFolder) {
                        val folderId = targetItem.folderId!!
                        val folder = prefsManager.getFolders(currentSpace)[folderId]
                        if (folder != null && !folder.packageNames.contains(sourceItem.packageName)) {
                            folder.packageNames.add(sourceItem.packageName)
                            prefsManager.saveFolder(currentSpace, folder)
                            if (sourceIdx != -1 && sourceIdx < ordered.size) {
                                ordered[sourceIdx] = ""
                                prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
                            }
                            updateHomeScreenApps()
                        }
                    } else {
                        createFolderDirectly(targetItem, sourceItem)
                    }
                } else {
                    if (sourceItem.isFolder && targetItem != null && !targetItem.isEmpty) {
                        val targetKey = if (targetItem.isFolder) "folder:${targetItem.folderId}" else targetItem.packageName
                        val targetIdx = ordered.indexOf(targetKey)
                        if (targetIdx != -1 && sourceIdx != -1) {
                            ordered[sourceIdx] = targetKey
                            ordered[targetIdx] = sourceKey
                            prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
                            updateHomeScreenApps()
                        } else {
                            placeItemAtOrdered(ordered, sourceKey, sourceIdx, targetIdxInOrdered)
                        }
                    } else {
                        placeItemAtOrdered(ordered, sourceKey, sourceIdx, targetIdxInOrdered)
                    }
                }
            }
            HighlightMode.SWAP -> {
                if (targetItem != null && !targetItem.isEmpty && targetItem.packageName != sourceItem.packageName) {
                    val targetKey = if (targetItem.isFolder) "folder:${targetItem.folderId}" else targetItem.packageName
                    val targetIdx = ordered.indexOf(targetKey)
                    if (targetIdx != -1) {
                        if (sourceIdx != -1) {
                            ordered[sourceIdx] = targetKey
                            ordered[targetIdx] = sourceKey
                        } else {
                            displaceAndInsert(ordered, sourceKey, targetIdx)
                        }
                        prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
                        updateHomeScreenApps()
                    } else {
                        placeItemAtOrdered(ordered, sourceKey, sourceIdx, targetIdxInOrdered)
                    }
                } else {
                    placeItemAtOrdered(ordered, sourceKey, sourceIdx, targetIdxInOrdered)
                }
            }
            HighlightMode.GHOST -> {
                placeItemAtOrdered(ordered, sourceKey, sourceIdx, targetIdxInOrdered)
            }
            HighlightMode.NONE -> {
                if (isExtractedFromFolder) {
                    placeItemAtOrdered(ordered, sourceKey, sourceIdx, targetIdxInOrdered)
                } else {
                    updateHomeScreenApps()
                }
            }
        }

        if (hasTemporaryDragPage) {
            hasTemporaryDragPage = false
            stripEmptyTrailingPages()
        }

        // Иконку вытащили из дока и положили на сетку — убираем её из дока.
        // sourceIdx == -1 в ветках выше означает, что в ordered её не было,
        // то есть displaceAndInsert уже добавил её на экран.
        if (isDraggingFromDock && sourceItem != null) {
            if (effectiveDropMode != HighlightMode.NONE || sourceIdx == -1) {
                dockController.removePackage(sourceItem.packageName)
                refreshDock()
            }
        }

        resetDragState()
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun placeItemAtOrdered(ordered: MutableList<String>, sourceKey: String, sourceIdx: Int, targetIdx: Int) {
        if (sourceIdx != -1 && sourceIdx < ordered.size) {
            ordered[sourceIdx] = ""
        }
        while (ordered.size <= targetIdx) {
            ordered.add("")
        }
        if (ordered[targetIdx].isEmpty()) {
            ordered[targetIdx] = sourceKey
        } else {
            displaceAndInsert(ordered, sourceKey, targetIdx)
        }
        prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
        updateHomeScreenApps()
    }

    private fun displaceAndInsert(ordered: MutableList<String>, key: String, insertIdx: Int) {
        val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            currentSettings.gridColumns + 2
        } else {
            currentSettings.gridColumns
        }
        val covered = getAllWidgetCoveredSlots(ordered, spanCount)
        while (ordered.size <= insertIdx) {
            ordered.add("")
        }
        var nextEmpty = insertIdx + 1
        while (nextEmpty < ordered.size && (ordered[nextEmpty].isNotEmpty() || covered.contains(nextEmpty))) {
            nextEmpty++
        }
        if (nextEmpty >= ordered.size) {
            ordered.add("")
        }
        for (i in nextEmpty downTo insertIdx + 1) {
            ordered[i] = ordered[i - 1]
        }
        ordered[insertIdx] = key
    }

    private fun moveDesktopItemAcrossPages(
        @Suppress("UNUSED_PARAMETER") fromPageIndex: Int,
        @Suppress("UNUSED_PARAMETER") fromItemIndex: Int,
        targetPageIndex: Int,
        dropRawX: Float,
        dropRawY: Float
    ) {
        val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            currentSettings.gridColumns + 2
        } else {
            currentSettings.gridColumns
        }
        val itemsPerPage = (spanCount * currentSettings.gridRows).coerceAtLeast(1)
        val firstPageCapacity = itemsPerPage

        val currentOrdered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
        if (currentOrdered.isEmpty()) {
            val allVisible = allApps.filter { !currentSettings.hiddenPackages.contains(it.packageName) }
            val spaceApps = if (currentSpace == 1) {
                allVisible.filter { it.isSecondSpace || prefsManager.getSecondSpacePackages().contains(it.packageName) }
            } else {
                allVisible.filter { !it.isSecondSpace }
            }
            val sourceList = if (spaceApps.isNotEmpty()) spaceApps else allVisible
            currentOrdered.addAll(sourceList.map { it.packageName })
        }

        val groupKeys = if (activeDraggedGroup.isNotEmpty()) {
            activeDraggedGroup.map { it.packageName }
        } else {
            listOf(activeDraggedItem?.packageName ?: "")
        }.filter { it.isNotEmpty() && it != PreferencesManager.EMPTY_CELL_KEY }

        if (groupKeys.isEmpty()) return

        val targetCapacity = if (targetPageIndex == 0) firstPageCapacity else itemsPerPage
        val rowsForPage = currentSettings.gridRows.coerceAtLeast(1)

        val targetRv = desktopPagerAdapter.getRecyclerViewForPage(targetPageIndex)
        var targetCellOffset = -1
        var directTargetPos = -1

        if (targetRv != null && targetRv.width > 0 && targetRv.height > 0) {
            val loc = IntArray(2)
            targetRv.getLocationOnScreen(loc)
            val localX = (dropRawX - loc[0]).coerceIn(0f, (targetRv.width - 1).toFloat())
            val localY = (dropRawY - loc[1]).coerceIn(0f, (targetRv.height - 1).toFloat())

            val child = targetRv.findChildViewUnder(localX, localY)
            if (child != null) {
                val holder = targetRv.getChildViewHolder(child)
                val pos = holder?.bindingAdapterPosition ?: -1
                if (pos != -1) {
                    val targetAdapter = targetRv.adapter as? AppsAdapter
                    if (targetAdapter != null && pos in targetAdapter.getItems().indices) {
                        val targetItem = targetAdapter.getItems()[pos]
                        if (!targetItem.isEmpty) {
                            val foundIdx = currentOrdered.indexOf(targetItem.packageName)
                            if (foundIdx != -1) {
                                directTargetPos = foundIdx
                            }
                        }
                    }
                    targetCellOffset = pos
                }
            }
            if (targetCellOffset == -1 && directTargetPos == -1) {
                val cellW = targetRv.width.toFloat() / spanCount
                val cellH = targetRv.height.toFloat() / rowsForPage
                val col = (localX / cellW).toInt().coerceIn(0, spanCount - 1)
                val row = (localY / cellH).toInt().coerceIn(0, rowsForPage - 1)
                targetCellOffset = (row * spanCount + col).coerceIn(0, targetCapacity - 1)
            }
        } else {
            targetCellOffset = 0
        }

        val targetPageStart = if (targetPageIndex == 0) 0 else firstPageCapacity + (targetPageIndex - 1) * itemsPerPage
        var targetPos = if (directTargetPos != -1) directTargetPos else (targetPageStart + targetCellOffset)

        for (itemKey in groupKeys) {
            if (itemKey.isEmpty()) continue
            var pos = currentOrdered.indexOf(itemKey)
            while (pos != -1) {
                currentOrdered[pos] = ""
                pos = currentOrdered.indexOf(itemKey)
            }
        }

        for (itemKey in groupKeys) {
            while (currentOrdered.size <= targetPos) {
                currentOrdered.add("")
            }
            if (currentOrdered[targetPos].isEmpty()) {
                currentOrdered[targetPos] = itemKey
            } else {
                val displaced = currentOrdered[targetPos]
                currentOrdered[targetPos] = itemKey
                var nextEmpty = targetPos + 1
                while (nextEmpty < currentOrdered.size && currentOrdered[nextEmpty].isNotEmpty()) {
                    nextEmpty++
                }
                if (nextEmpty >= currentOrdered.size) {
                    currentOrdered.add(displaced)
                } else {
                    currentOrdered[nextEmpty] = displaced
                }
            }
            targetPos++
        }

        prefsManager.setHomeScreenOrderedPackages(currentSpace, currentOrdered)

        if (isEditMode) {
            selectedAppsForDrag.clear()
            desktopPagerAdapter.setEditMode(isEditMode, selectedAppsForDrag)
        }

        updateHomeScreenApps()
        binding.homeViewPager.setCurrentItem(targetPageIndex, false)
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }


    private enum class HighlightMode { NONE, FOLDER, SWAP, GHOST }
    private var currentHighlightMode: HighlightMode = HighlightMode.NONE

    private fun highlightItem(
        adapter: com.naua_morphix_launcher.app.ui.AppsAdapter,
        position: Int,
        item: com.naua_morphix_launcher.app.model.AppItem,
        mode: HighlightMode
    ) {
        if (currentHighlightedTargetPosition == position &&
            currentHighlightedTargetAdapter == adapter &&
            currentHighlightMode == mode
        ) {
            return
        }
        
        clearCurrentHighlight()
        
        currentHighlightedTargetAdapter = adapter
        currentHighlightedTargetPosition = position
        currentHighlightedTargetItem = item
        currentHighlightMode = mode
        isFolderDropArmed = (mode == HighlightMode.FOLDER)
        
        when (mode) {
            HighlightMode.FOLDER -> {
                adapter.setItemHighlighted(position, true, activeDraggedItem)
            }
            HighlightMode.SWAP -> {
                adapter.setSwapHighlight(position, true)
            }
            HighlightMode.GHOST -> {
                adapter.setEmptyDropGhost(position, activeDraggedItem)
            }
            HighlightMode.NONE -> {}
        }
    }

    private fun clearCurrentHighlight() {
        binding.widgetDropTargetPreview.visibility = View.GONE
        val adapter = currentHighlightedTargetAdapter
        val pos = currentHighlightedTargetPosition
        if (adapter != null && pos != -1) {
            when (currentHighlightMode) {
                HighlightMode.FOLDER -> adapter.setItemHighlighted(pos, false)
                HighlightMode.SWAP -> adapter.setSwapHighlight(pos, false)
                HighlightMode.GHOST -> adapter.setEmptyDropGhost(pos, null)
                HighlightMode.NONE -> {}
            }
            currentHighlightedTargetAdapter = null
            currentHighlightedTargetPosition = -1
            currentHighlightedTargetItem = null
            currentHighlightMode = HighlightMode.NONE
            isFolderDropArmed = false
        }
    }

    private fun handleWidgetDragMove(rawX: Float, rawY: Float) {
        if (!isDraggingWidget) return
        val diffX = Math.abs(rawX - dragStartRawX)
        val diffY = Math.abs(rawY - dragStartRawY)
        val threshold = 12 * resources.displayMetrics.density
        if (!hasMovedSignificantDistance && (diffX > threshold || diffY > threshold)) {
            hasMovedSignificantDistance = true
            if (currentSettings.smoothAnimations) {
                dragSourceView?.animate()?.alpha(0.25f)?.setDuration(100)?.start()
            } else {
                dragSourceView?.alpha = 0.25f
            }
        }

        val dragW = binding.floatingWidgetDragView.width.toFloat().let { if (it > 0) it else 200f }
        val dragH = binding.floatingWidgetDragView.height.toFloat().let { if (it > 0) it else 100f }
        binding.floatingWidgetDragView.translationX = rawX - dragW / 2f
        binding.floatingWidgetDragView.translationY = rawY - dragH / 2f

        // Плавное перелистывание страниц у границ экрана
        val screenWidth = resources.displayMetrics.widthPixels
        val edgeMargin = 50f * resources.displayMetrics.density
        val now = SystemClock.uptimeMillis()

        if (now - lastPageSwitchTime > 550L) {
            val curPage = binding.homeViewPager.currentItem
            val totalPages = desktopPagerAdapter.itemCount
            if (rawX > screenWidth - edgeMargin) {
                if (curPage < totalPages - 1) {
                    lastPageSwitchTime = now
                    binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                    binding.homeViewPager.setCurrentItem(curPage + 1, true)
                } else if (!hasTemporaryDragPage) {
                    hasTemporaryDragPage = true
                    lastPageSwitchTime = now
                    val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        currentSettings.gridColumns + 2
                    } else {
                        currentSettings.gridColumns
                    }
                    val itemsPerPage = (spanCount * currentSettings.gridRows).coerceAtLeast(1)
                    val ordered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
                    val neededSize = ordered.size + itemsPerPage
                    while (ordered.size < neededSize) ordered.add("")
                    prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
                    updateHomeScreenApps()
                    binding.homeViewPager.post {
                        binding.homeViewPager.setCurrentItem(totalPages, true)
                    }
                    binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                }
            } else if (rawX < edgeMargin && curPage > 0) {
                lastPageSwitchTime = now
                binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                binding.homeViewPager.setCurrentItem(curPage - 1, true)
            }
        }
    }

    private fun handleWidgetDragEnd() {
        if (!isDraggingWidget) return
        isDraggingWidget = false
        binding.dragOverlayContainer.visibility = View.GONE
        binding.floatingWidgetDragView.visibility = View.GONE
        if (currentSettings.smoothAnimations) {
            dragSourceView?.animate()?.alpha(1.0f)?.setDuration(100)?.start()
        } else {
            dragSourceView?.alpha = 1.0f
        }

        val widgetId = activeDraggedWidgetId
        activeDraggedWidgetId = -1

        if (widgetId == -1) {
            if (hasTemporaryDragPage) {
                hasTemporaryDragPage = false
                stripEmptyTrailingPages()
            }
            return
        }

        if (!hasMovedSignificantDistance) {
            // Зажали и отпустили без движения — открываем меню действий HyperOS над виджетом
            if (hasTemporaryDragPage) {
                hasTemporaryDragPage = false
                stripEmptyTrailingPages()
            }
            showWidgetActionsDialog(widgetId, dragSourcePageIndex)
            return
        }

        // Бросили на страницу после перелистывания или на текущую страницу
        val targetPageIndex = binding.homeViewPager.currentItem
        prefsManager.setWidgetPage(widgetId, targetPageIndex)
        if (hasTemporaryDragPage) {
            hasTemporaryDragPage = false
            stripEmptyTrailingPages()
        }
        updateHomeScreenApps()
        desktopPagerAdapter.refreshWidgets()
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
    }

    private fun handleEdgeHover(direction: Int, pageIndex: Int, itemIndex: Int) {
        if (edgeHoverRunnable != null) return
        edgeHoverRunnable = Runnable {
            edgeHoverRunnable = null
            performEdgeScreenSwitch(direction, pageIndex, itemIndex)
        }
        edgeHoverHandler.postDelayed(edgeHoverRunnable!!, 450)
    }

    private fun cancelEdgeHover() {
        edgeHoverRunnable?.let { edgeHoverHandler.removeCallbacks(it) }
        edgeHoverRunnable = null
    }

    private fun performEdgeScreenSwitch(direction: Int, pageIndex: Int, itemIndex: Int) {
        val totalPages = desktopPagerAdapter.itemCount
        val targetPageIndex = if (direction > 0) {
            (pageIndex + 1).coerceAtMost((totalPages - 1).coerceAtLeast(0))
        } else {
            (pageIndex - 1).coerceAtLeast(0)
        }
        if (targetPageIndex == pageIndex) return

        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        moveDesktopItemToPage(pageIndex, itemIndex, targetPageIndex)
    }

    private fun moveDesktopItemToPage(fromPageIndex: Int, fromItemIndex: Int, targetPageIndex: Int) {
        val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            currentSettings.gridColumns + 2
        } else {
            currentSettings.gridColumns
        }
        val itemsPerPage = (spanCount * currentSettings.gridRows).coerceAtLeast(1)
        val firstPageCapacity = itemsPerPage

        val currentOrdered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
        if (currentOrdered.isEmpty()) {
            val allVisible = allApps.filter { !currentSettings.hiddenPackages.contains(it.packageName) }
            val spaceApps = if (currentSpace == 1) {
                allVisible.filter { it.isSecondSpace || prefsManager.getSecondSpacePackages().contains(it.packageName) }
            } else {
                allVisible.filter { !it.isSecondSpace }
            }
            val sourceList = if (spaceApps.isNotEmpty()) spaceApps else allVisible
            currentOrdered.addAll(sourceList.map { it.packageName })
        }

        val fromPos = if (fromPageIndex == 0) fromItemIndex else firstPageCapacity + (fromPageIndex - 1) * itemsPerPage + fromItemIndex
        if (fromPos !in currentOrdered.indices) return
        val itemKey = currentOrdered[fromPos]
        if (itemKey.isEmpty() || itemKey == PreferencesManager.EMPTY_CELL_KEY) return

        val targetPageStart = if (targetPageIndex == 0) 0 else firstPageCapacity + (targetPageIndex - 1) * itemsPerPage
        val targetCapacity = if (targetPageIndex == 0) firstPageCapacity else itemsPerPage

        var targetPos = -1
        for (i in 0 until targetCapacity) {
            val idx = targetPageStart + i
            if (idx < currentOrdered.size && (currentOrdered[idx].isEmpty() || currentOrdered[idx] == PreferencesManager.EMPTY_CELL_KEY)) {
                targetPos = idx
                break
            }
        }
        if (targetPos == -1) {
            targetPos = (targetPageStart + targetCapacity - 1).coerceAtLeast(targetPageStart)
        }

        val maxNeededSize = maxOf(fromPos, targetPos) + 1
        while (currentOrdered.size < maxNeededSize) {
            currentOrdered.add("")
        }

        currentOrdered[fromPos] = ""
        currentOrdered[targetPos] = itemKey
        prefsManager.setHomeScreenOrderedPackages(currentSpace, currentOrdered)

        updateHomeScreenApps()
        binding.homeViewPager.setCurrentItem(targetPageIndex, true)
    }

    private fun handleDesktopItemsReordered(pageIndex: Int, newItems: List<AppItem>) {
        val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            currentSettings.gridColumns + 2
        } else {
            currentSettings.gridColumns
        }
        val itemsPerPage = (spanCount * currentSettings.gridRows).coerceAtLeast(1)
        val firstPageCapacity = itemsPerPage
        val currentOrdered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
        val pageStart = if (pageIndex == 0) 0 else firstPageCapacity + (pageIndex - 1) * itemsPerPage

        val newPagePackages = newItems.map { if (it.isEmpty) "" else it.packageName }
        val maxNeeded = pageStart + newPagePackages.size
        while (currentOrdered.size < maxNeeded) {
            currentOrdered.add("")
        }
        for (i in newPagePackages.indices) {
            currentOrdered[pageStart + i] = newPagePackages[i]
        }
        prefsManager.setHomeScreenOrderedPackages(currentSpace, currentOrdered)
    }

    private fun getWidgetSpan(info: android.appwidget.AppWidgetProviderInfo?, spanCount: Int): Pair<Int, Int> {
        if (info == null) return Pair(spanCount, 2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (info.targetCellWidth > 0 && info.targetCellHeight > 0) {
                val spanX = info.targetCellWidth.coerceIn(1, spanCount)
                val spanY = info.targetCellHeight.coerceIn(1, 4)
                return Pair(spanX, spanY)
            }
        }
        val minHeightDp = info.minHeight
        val spanY = ((minHeightDp + 30) / 70).coerceIn(1, 3)
        return Pair(spanCount, spanY)
    }

    private fun getAllWidgetCoveredSlots(ordered: List<String>, spanCount: Int): Set<Int> {
        val covered = mutableSetOf<Int>()
        for (idx in ordered.indices) {
            val key = ordered[idx]
            if (key.startsWith("widget:")) {
                val id = key.removePrefix("widget:").toIntOrNull()
                val spanY = if (id != null) {
                    widgetMetaCache[id]?.second?.second ?: run {
                        val info = appWidgetManager.getAppWidgetInfo(id)
                        getWidgetSpan(info, spanCount).second
                    }
                } else 2
                val wCells = spanCount * spanY
                for (c in 0 until wCells) {
                    covered.add(idx + c)
                }
            }
        }
        return covered
    }

    private var isUpdatingHomeScreen = false
    private var pendingHomeScreenUpdate = false

    private fun updateHomeScreenApps() {
        if (isUpdatingHomeScreen) {
            pendingHomeScreenUpdate = true
            return
        }
        isUpdatingHomeScreen = true

        val currentSpace = this.currentSpace
        val currentSettings = this.currentSettings
        val spanCount = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            currentSettings.gridColumns + 2
        } else {
            currentSettings.gridColumns
        }
        val rowsCount = currentSettings.gridRows
        val itemsPerPage = (spanCount * currentSettings.gridRows).coerceAtLeast(1)
        val firstPageCapacity = itemsPerPage
        
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            try {
                val installedWidgetIds = prefsManager.getAppWidgetIds()
            val currentOrderedPkgs = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
            var orderedChanged = false

            val foldersMap = prefsManager.getFolders(currentSpace).toMutableMap()
            val emptyFolders = foldersMap.filterValues { it.packageNames.isEmpty() }.keys
            if (emptyFolders.isNotEmpty()) {
                emptyFolders.forEach { 
                    foldersMap.remove(it)
                    prefsManager.deleteFolder(currentSpace, it)
                }
            }

            val seenInCurrentOrdered = mutableSetOf<String>()
            for (i in currentOrderedPkgs.indices) {
                val key = currentOrderedPkgs[i]
                if (key.isNotEmpty() && key != PreferencesManager.EMPTY_CELL_KEY) {
                    if (key.startsWith("folder:") && emptyFolders.contains(key.removePrefix("folder:"))) {
                        currentOrderedPkgs[i] = ""
                        orderedChanged = true
                    } else if (seenInCurrentOrdered.contains(key)) {
                        currentOrderedPkgs[i] = ""
                        orderedChanged = true
                    } else {
                        seenInCurrentOrdered.add(key)
                    }
                }
            }

            for (wId in installedWidgetIds) {
                val widgetKey = "widget:$wId"
                if (!currentOrderedPkgs.contains(widgetKey)) {
                    currentOrderedPkgs.add(widgetKey)
                    orderedChanged = true
                }
            }

            val widgetCoveredSlots = getAllWidgetCoveredSlots(currentOrderedPkgs, spanCount)
            val widgetBodySlots = mutableSetOf<Int>()
            for (idx in currentOrderedPkgs.indices) {
                val key = currentOrderedPkgs[idx]
                if (key.startsWith("widget:")) {
                    val id = key.removePrefix("widget:").toIntOrNull()
                    val spanY = if (id != null) {
                        widgetMetaCache[id]?.second?.second ?: run {
                            val info = appWidgetManager.getAppWidgetInfo(id)
                            getWidgetSpan(info, spanCount).second
                        }
                    } else 2
                    val wCells = spanCount * spanY
                    for (c in 1 until wCells) {
                        widgetBodySlots.add(idx + c)
                    }
                }
            }

            // Автоматическое спасение иконок, оказавшихся под виджетами
            for (coveredIdx in widgetBodySlots) {
                if (coveredIdx < currentOrderedPkgs.size) {
                    val key = currentOrderedPkgs[coveredIdx]
                    if (key.isNotEmpty() && key != PreferencesManager.EMPTY_CELL_KEY && !key.startsWith("widget:")) {
                        var targetEmpty = -1
                        for (s in currentOrderedPkgs.indices) {
                            if (!widgetCoveredSlots.contains(s) && (currentOrderedPkgs[s].isEmpty() || currentOrderedPkgs[s] == PreferencesManager.EMPTY_CELL_KEY)) {
                                targetEmpty = s
                                break
                            }
                        }
                        if (targetEmpty != -1) {
                            currentOrderedPkgs[targetEmpty] = key
                        } else {
                            while (widgetCoveredSlots.contains(currentOrderedPkgs.size)) {
                                currentOrderedPkgs.add("")
                            }
                            currentOrderedPkgs.add(key)
                        }
                        currentOrderedPkgs[coveredIdx] = ""
                        orderedChanged = true
                    }
                }
            }
            if (orderedChanged) { prefsManager.setHomeScreenOrderedPackages(currentSpace, currentOrderedPkgs) }

            val allVisible = allApps.filter { !currentSettings.hiddenPackages.contains(it.packageName) }
            val allVisibleMap = allVisible.associateBy { it.packageName }

            fun resolveDesktopItem(key: String): AppItem {
                if (key.isEmpty() || key == PreferencesManager.EMPTY_CELL_KEY) {
                    return AppItem.empty()
                }
                if (key.startsWith("folder:")) {
                    val folderId = key.removePrefix("folder:")
                    val folder = foldersMap[folderId] ?: return AppItem.empty()
                    val folderApps = folder.packageNames.mapNotNull { pkg -> allVisibleMap[pkg] }
                    return AppItem(
                        label = folder.name,
                        packageName = key,
                        activityName = "",
                        icon = null,
                        isFolder = true,
                        folderId = folder.id,
                        folderApps = folderApps,
                        folderSize = folder.size
                    )
                } else if (key.startsWith("widget:")) {
                    val widgetId = key.removePrefix("widget:").toIntOrNull() ?: return AppItem.empty()
                    val cachedMeta = widgetMetaCache[widgetId]
                    val (label, span) = if (cachedMeta != null) {
                        cachedMeta
                    } else {
                        val info = appWidgetManager.getAppWidgetInfo(widgetId) ?: return AppItem.empty()
                        val spanX = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && info.targetCellWidth > 0) info.targetCellWidth.coerceIn(1, spanCount) else spanCount
                        val spanY = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && info.targetCellHeight > 0) info.targetCellHeight.coerceIn(1, 4) else ((info.minHeight + 30) / 70).coerceIn(1, 3)
                        val s = Pair(spanX, spanY)
                        val l = info.loadLabel(packageManager)?.toString() ?: "Виджет"
                        val meta = Pair(l, s)
                        widgetMetaCache[widgetId] = meta
                        meta
                    }
                    return AppItem(
                        label = label,
                        packageName = key,
                        activityName = "",
                        icon = null,
                        isWidget = true,
                        widgetId = widgetId,
                        widgetSpanX = span.first,
                        widgetSpanY = span.second
                    )
                } else {
                    return allVisibleMap[key] ?: AppItem.empty()
                }
            }

            val spaceApps = if (currentSpace == 1) {
                allVisible.filter { it.isSecondSpace || prefsManager.getSecondSpacePackages().contains(it.packageName) }
            } else {
                allVisible.filter { !it.isSecondSpace }
            }
            val sourceList = if (spaceApps.isNotEmpty()) spaceApps else allVisible
            
            if (currentOrderedPkgs.isEmpty()) {
                currentOrderedPkgs.addAll(sourceList.map { it.packageName })
                orderedChanged = true
            }

            val resolvedList = mutableListOf<AppItem>()
            val seenPackages = mutableSetOf<String>()
            
            for (key in currentOrderedPkgs) {
                val item = resolveDesktopItem(key)
                resolvedList.add(item)
                if (!item.isEmpty) {
                    if (item.isFolder) {
                        seenPackages.addAll(item.folderApps.map { it.packageName })
                    } else if (!item.isWidget) {
                        seenPackages.add(item.packageName)
                    }
                }
            }
            
            if (currentSettings.layoutMode == LayoutMode.CLASSIC) {
                var emptySearchStart = 0
                for (app in sourceList) {
                    if (!seenPackages.contains(app.packageName)) {
                        var foundEmpty = -1
                        while (emptySearchStart < resolvedList.size) {
                            if (resolvedList[emptySearchStart].isEmpty && !widgetCoveredSlots.contains(emptySearchStart)) {
                                foundEmpty = emptySearchStart
                                emptySearchStart++
                                break
                            }
                            emptySearchStart++
                        }
                        
                        if (foundEmpty != -1) {
                            currentOrderedPkgs[foundEmpty] = app.packageName
                            resolvedList[foundEmpty] = app
                            seenPackages.add(app.packageName)
                        } else {
                            while (widgetCoveredSlots.contains(currentOrderedPkgs.size)) {
                                currentOrderedPkgs.add("")
                                resolvedList.add(AppItem.empty())
                            }
                            currentOrderedPkgs.add(app.packageName)
                            resolvedList.add(app)
                            seenPackages.add(app.packageName)
                        }
                        orderedChanged = true
                    }
                }
            }
            
            if (orderedChanged) { prefsManager.setHomeScreenOrderedPackages(currentSpace, currentOrderedPkgs) }
            val appsToDisplay: List<AppItem> = resolvedList

            val pages = mutableListOf<List<AppItem>>()
            var pageIndex = 0

            while (true) {
                val startIdx = if (pageIndex == 0) 0 else firstPageCapacity + (pageIndex - 1) * itemsPerPage
                if (startIdx >= appsToDisplay.size && (pageIndex > 0 || appsToDisplay.isEmpty())) {
                    if (pages.isEmpty()) {
                        val emptyPage = mutableListOf<AppItem>()
                        while (emptyPage.size < firstPageCapacity) emptyPage.add(AppItem.empty())
                        pages.add(emptyPage)
                    }
                    break
                }
                val pageCapacity = if (pageIndex == 0) firstPageCapacity else itemsPerPage
                val pageItems = mutableListOf<AppItem>()
                
                var slotsUsed = 0
                var i = startIdx
                while (slotsUsed < pageCapacity && i < appsToDisplay.size) {
                    val item = appsToDisplay[i]
                    if (item.isWidget) {
                        val wSpanX = spanCount
                        val wSpanY = item.widgetSpanY
                        val wCells = wSpanX * wSpanY
                        
                        if (slotsUsed + wCells > pageCapacity) {
                            break 
                        }
                        pageItems.add(item)
                        slotsUsed += wCells
                        i += wCells
                    } else {
                        pageItems.add(item)
                        slotsUsed++
                        i++
                    }
                }
                
                while (slotsUsed < pageCapacity) {
                    pageItems.add(AppItem.empty())
                    slotsUsed++
                }
                pages.add(pageItems)
                pageIndex++
            }
            
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                desktopPagerAdapter.updateData(
                    newPages = pages,
                    span = spanCount,
                    rows = rowsCount,
                    shape = currentSettings.iconShape,
                    labels = currentSettings.showLabels,
                    scale = currentSettings.iconScale,
                    showClock = currentSettings.showClockWidget,
                    smoothAnimations = currentSettings.smoothAnimations
                )
                
                updatePageIndicator(binding.homeViewPager.currentItem, pages.size)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                isUpdatingHomeScreen = false
                if (pendingHomeScreenUpdate) {
                    pendingHomeScreenUpdate = false
                    updateHomeScreenApps()
                }
            }
        }
    }
}

    private fun updatePageIndicator(currentPage: Int, totalPages: Int) {
        binding.homeViewPager.post {
            updateDockSpacing()
            val vpHeight = binding.homeViewPager.height
            if (vpHeight > 0) {
                val availableHeight = vpHeight - (24 * resources.displayMetrics.density).toInt()
                val rowHeight = availableHeight / currentSettings.gridRows.coerceAtLeast(1)
                
                // The boundary between the last row and second-to-last row is exactly 'rowHeight' from the bottom of availableHeight.
                // The ViewPager bottom is padded by 24dp.
                // So the boundary from the absolute bottom of the screen is roughly rowHeight + 12dp.
                // The indicator has marginBottom="24dp". 
                // We want the indicator's center to sit on the boundary.
                val boundaryFromBottom = rowHeight + (12 * resources.displayMetrics.density)
                val indicatorCurrentBottomMargin = 0f
                
                binding.pageIndicatorLayout.translationY = -(boundaryFromBottom - indicatorCurrentBottomMargin)
            }
        }

        if (binding.pageIndicatorLayout.childCount == totalPages) {
            for (i in 0 until totalPages) {
                val dot = binding.pageIndicatorLayout.getChildAt(i) as? ImageView ?: continue
                val isActive = (i == currentPage)
                dot.setImageResource(
                    if (isActive) R.drawable.ic_page_dot_active
                    else R.drawable.ic_page_dot_inactive
                )
                if (currentSettings.smoothAnimations) {
                    dot.animate()
                        .scaleX(if (isActive) 1.35f else 1.0f)
                        .scaleY(if (isActive) 1.35f else 1.0f)
                        .setDuration(180)
                        .start()
                } else {
                    dot.scaleX = if (isActive) 1.35f else 1.0f
                    dot.scaleY = if (isActive) 1.35f else 1.0f
                }
            }
            return
        }

        binding.pageIndicatorLayout.removeAllViews()
        if (totalPages <= 1) {
            binding.pageIndicatorLayout.visibility = View.GONE
            return
        }
        binding.pageIndicatorLayout.visibility = View.VISIBLE

        val dotSize = (6 * resources.displayMetrics.density).toInt()
        val dotMargin = (4 * resources.displayMetrics.density).toInt()

        for (i in 0 until totalPages) {
            val isActive = (i == currentPage)
            val dot = ImageView(this).apply {
                val params = LinearLayout.LayoutParams(dotSize, dotSize).apply {
                    setMargins(dotMargin, 0, dotMargin, 0)
                }
                layoutParams = params
                setImageResource(
                    if (isActive) R.drawable.ic_page_dot_active
                    else R.drawable.ic_page_dot_inactive
                )
                scaleX = if (isActive) 1.35f else 1.0f
                scaleY = if (isActive) 1.35f else 1.0f
            }
            binding.pageIndicatorLayout.addView(dot)
        }
    }

    private fun setupGestures() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleScreenTap()
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 != null) {
                    val diffY = e2.y - e1.y
                    val diffX = e2.x - e1.x

                    if (Math.abs(diffY) > Math.abs(diffX) * 1.2f) {
                        // Свайп вверх: открываем меню приложений (в режиме DRAWER)
                        if (diffY < -60 && velocityY < -60) {
                            if (currentSettings.layoutMode == LayoutMode.DRAWER
                                && binding.glassAppDrawer.visibility != View.VISIBLE
                                && !isEditMode
                            ) {
                                openAppDrawer()
                                return true
                            }
                        }

                        // Свайп вниз. В MIUI это два разных жеста:
                        // по рабочему столу — глобальный поиск, по меню
                        // приложений — шторка уведомлений.
                        if (diffY > 120 && velocityY > 100) {
                            if (binding.glassAppDrawer.visibility == View.VISIBLE) {
                                if (currentSettings.swipeDownToNotifications) {
                                    closeAppDrawer()
                                    expandNotificationShade()
                                    return true
                                }
                            } else if (!isEditMode) {
                                if (currentSettings.showSearchOnHome) {
                                    openGlobalSearch()
                                    return true
                                } else if (currentSettings.swipeDownToNotifications) {
                                    expandNotificationShade()
                                    return true
                                }
                            }
                        }
                    }
                }
                return super.onFling(e1, e2, velocityX, velocityY)
            }

            // Долгий тап по пустому месту рабочего стола открывает режим редактирования Xiaomi
            override fun onLongPress(e: MotionEvent) {
                enterEditMode()
            }
        })

        // Перехват жестов на контейнере рабочего стола
        binding.homeScreenContainer.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
        }
    }

    private fun openAppDrawer() {
        if (binding.glassAppDrawer.visibility == View.VISIBLE) return
        binding.glassAppDrawer.visibility = View.VISIBLE
        // док прячем: он лежит поверх меню приложений и перехватывал бы касания
        setDockInteractionEnabled(false)
        if (currentSettings.smoothAnimations) {
            val screenHeight = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1200f)
            binding.glassAppDrawer.translationY = screenHeight
            binding.glassAppDrawer.animate()
                .translationY(0f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator())
                .start()
            binding.homeViewPager.animate()
                .scaleX(0.94f)
                .scaleY(0.94f)
                .alpha(0.85f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            binding.glassAppDrawer.translationY = 0f
            binding.homeViewPager.scaleX = 0.94f
            binding.homeViewPager.scaleY = 0.94f
            binding.homeViewPager.alpha = 0.85f
        }
    }

    private fun closeAppDrawer() {
        if (binding.glassAppDrawer.visibility != View.VISIBLE) return
        if (currentSettings.smoothAnimations) {
            val screenHeight = resources.displayMetrics.heightPixels.toFloat().coerceAtLeast(1200f)
            binding.glassAppDrawer.animate()
                .translationY(screenHeight)
                .setDuration(200)
                .withEndAction {
                    binding.glassAppDrawer.visibility = View.GONE
                }
                .start()
            binding.homeViewPager.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { setDockInteractionEnabled(true) }
                .start()
        } else {
            binding.glassAppDrawer.visibility = View.GONE
            binding.glassAppDrawer.translationY = 0f
            binding.homeViewPager.scaleX = 1.0f
            binding.homeViewPager.scaleY = 1.0f
            binding.homeViewPager.alpha = 1.0f
            setDockInteractionEnabled(true)
        }
    }

    /** Прячет док на время, когда он перекрыт меню приложений или режимом правки. */
    private fun setDockInteractionEnabled(enabled: Boolean) {
        if (!::dockController.isInitialized) return
        val root = binding.dockContainer.root
        val shouldBeVisible = enabled
                && currentSettings.isDockEnabled
                && currentSettings.layoutMode == LayoutMode.DRAWER
        if (shouldBeVisible) {
            dockController.applyVisibility(currentSettings)
        } else {
            root.visibility = View.GONE
        }
        updateDockSpacing()
    }

    /**
     * Меню рабочего стола в стиле HyperOS / Material Design 3 (BottomSheetDialog)
     */
    private fun showHomeScreenEmptyContextMenu() {
        dismissActivePopups()
        val dialog = BottomSheetDialog(this)
        currentDialog = dialog
        val menuBinding = DialogDesktopMenuBinding.inflate(layoutInflater)
        dialog.setContentView(menuBinding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        com.naua_morphix_launcher.app.util.ThemeUtils.applySettingsTheme(menuBinding.root, currentSettings)

        val spaceTitle = if (currentSpace == 0) "👥 Перейти во Второе пространство" else "👤 Перейти в Основное пространство"
        menuBinding.btnMenuSwitchSpace.text = spaceTitle

        val modeTitle = if (currentSettings.layoutMode == LayoutMode.CLASSIC) "📱 Включить меню приложений" else "📱 Включить классический режим"
        menuBinding.btnMenuLayoutMode.text = modeTitle

        if (isSecondSpaceActive) {
            menuBinding.btnMenuSecondSpaceApps.visibility = View.VISIBLE
            menuBinding.btnMenuSecondSpaceApps.setOnClickListener {
                dialog.dismiss()
                showManageSecondSpaceAppsDialog()
            }
        } else {
            menuBinding.btnMenuSecondSpaceApps.visibility = View.GONE
        }

        menuBinding.btnMenuSettings.setOnClickListener {
            dialog.dismiss()
            openSettingsDialog()
        }

        menuBinding.btnMenuAddWidget.setOnClickListener {
            dialog.dismiss()
            launchWidgetPicker()
        }

        menuBinding.btnMenuSwitchSpace.setOnClickListener {
            dialog.dismiss()
            requestSwitchSpace()
        }

        menuBinding.btnMenuWallpaper.setOnClickListener {
            dialog.dismiss()
            try {
                startActivity(Intent(Intent.ACTION_SET_WALLPAPER))
            } catch (e: Exception) {
                Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
            }
        }

        menuBinding.btnMenuLayoutMode.setOnClickListener {
            dialog.dismiss()
            val newMode = if (currentSettings.layoutMode == LayoutMode.CLASSIC) LayoutMode.DRAWER else LayoutMode.CLASSIC
            currentSettings = currentSettings.copy(layoutMode = newMode)
            prefsManager.saveSettings(currentSettings)
            applySettingsChanges()
            Toast.makeText(this, "Режим: ${if (newMode == LayoutMode.CLASSIC) "Классический" else "Меню приложений"}", Toast.LENGTH_SHORT).show()
        }

        attachTactileFeedback(
            menuBinding.btnMenuSwitchSpace,
            menuBinding.btnMenuLayoutMode,
            menuBinding.btnMenuSecondSpaceApps,
            menuBinding.btnMenuSettings,
            menuBinding.btnMenuAddWidget,
            menuBinding.btnMenuWallpaper
        )

        dialog.show()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun attachTactileFeedback(vararg views: View) {
        for (v in views) {
            v.setOnTouchListener { view, event ->
                if (!currentSettings.smoothAnimations) {
                    view.scaleX = 1.0f
                    view.scaleY = 1.0f
                    return@setOnTouchListener false
                }
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.animate()
                            .scaleX(0.92f)
                            .scaleY(0.92f)
                            .setDuration(90)
                            .start()
                    }
                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
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

    private var isEditMode = false
    val selectedAppsForDrag = mutableSetOf<String>()

    private fun setupEditModeListeners() {
        binding.btnEditDone.setOnClickListener {
            exitEditMode()
        }
        
        binding.btnDeletePage.setOnClickListener {
            val curPage = binding.homeViewPager.currentItem
            val currentOrdered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
            
            val spanCount = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
                currentSettings.gridColumns + 2
            } else {
                currentSettings.gridColumns
            }
            val itemsPerPage = (spanCount * currentSettings.gridRows).coerceAtLeast(1)
            val firstPageCapacity = itemsPerPage
            
            val totalPages = if (currentOrdered.isEmpty()) 1 else {
                if (currentOrdered.size <= firstPageCapacity) 1
                else 1 + Math.ceil((currentOrdered.size - firstPageCapacity).toDouble() / itemsPerPage).toInt()
            }
            
            if (totalPages <= 1) {
                Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val pageStart = if (curPage == 0) 0 else firstPageCapacity + (curPage - 1) * itemsPerPage
            val capacity = if (curPage == 0) firstPageCapacity else itemsPerPage
            
            if (pageStart < currentOrdered.size) {
                val endPos = Math.min(pageStart + capacity, currentOrdered.size)
                currentOrdered.subList(pageStart, endPos).clear()
                prefsManager.setHomeScreenOrderedPackages(currentSpace, currentOrdered)
                updateHomeScreenApps()
                Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
                
                if (curPage > 0) {
                    binding.homeViewPager.setCurrentItem(curPage - 1, false)
                }
            }
        }

        binding.btnEditWallpaper.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_SET_WALLPAPER))
            } catch (e: Exception) {
                Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnEditWidgets.setOnClickListener {
            launchWidgetPicker()
        }

        binding.btnEditSettings.setOnClickListener {
            openSettingsDialog()
        }

        attachTactileFeedback(
            binding.btnEditDone,
            binding.btnDeletePage,
            binding.btnEditWallpaper,
            binding.btnEditWidgets,
            binding.btnEditSettings,
            binding.badgeSecondSpace
        )
    }

    private fun enterEditMode() {
        if (isEditMode) return
        isEditMode = true
        editModeOpenedAt = SystemClock.uptimeMillis()
        tapCounter = 0
        dismissActivePopups()

        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

        // Мягкое уменьшение масштаба рабочего стола (0.92x как в Xiaomi)
        if (currentSettings.smoothAnimations) {
            binding.homeViewPager.animate()
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            binding.homeViewPager.scaleX = 0.92f
            binding.homeViewPager.scaleY = 0.92f
        }

        // Настройка стекла применяется ко всем стеклянным вьюхам в setupGlassmorphism();
        // здесь только показываем кнопки режима редактирования.
        // Док прячем: в режиме правки он мешал бы перетаскиванию иконок с сетки в док.
        setDockInteractionEnabled(false)
        binding.glassEditDone.visibility = View.VISIBLE
        if (currentSettings.smoothAnimations) {
            binding.glassEditDone.alpha = 0f
            binding.glassEditDone.animate().alpha(1f).setDuration(200).start()
        } else {
            binding.glassEditDone.alpha = 1f
        }

        // Показываем кнопку Удалить
        binding.glassDeletePage.visibility = View.VISIBLE
        if (currentSettings.smoothAnimations) {
            binding.glassDeletePage.alpha = 0f
            binding.glassDeletePage.animate().alpha(1f).setDuration(200).start()
        } else {
            binding.glassDeletePage.alpha = 1f
        }

        // Скрываем точки страниц в режиме редактирования
        binding.pageIndicatorLayout.visibility = View.GONE

        // Показываем 3 круглые кнопки внизу: Обои, Виджеты, Настройки
        binding.glassEditModeBottomBar.visibility = View.VISIBLE
        if (currentSettings.smoothAnimations) {
            binding.glassEditModeBottomBar.alpha = 0f
            binding.glassEditModeBottomBar.translationY = 24f * resources.displayMetrics.density
            binding.glassEditModeBottomBar.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            binding.glassEditModeBottomBar.alpha = 1f
            binding.glassEditModeBottomBar.translationY = 0f
        }

        // Включаем отображение круглых чекбоксов выбора на иконках
        desktopPagerAdapter.setEditMode(true, selectedAppsForDrag.toSet())
    }

    private fun toggleAppSelection(item: AppItem) {
        val selKey = if (item.isFolder) "folder:${item.folderId}" else item.packageName
        if (selectedAppsForDrag.contains(selKey)) {
            selectedAppsForDrag.remove(selKey)
        } else {
            selectedAppsForDrag.add(selKey)
        }
        desktopPagerAdapter.setEditMode(isEditMode, selectedAppsForDrag.toSet())
    }

    private fun exitEditMode() {
        if (!isEditMode) return
        isEditMode = false
        editModeOpenedAt = 0L
        tapCounter = 0
        selectedAppsForDrag.clear()
        setDockInteractionEnabled(binding.glassAppDrawer.visibility != View.VISIBLE)

        // Возвращаем масштаб рабочего стола
        if (currentSettings.smoothAnimations) {
            binding.homeViewPager.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()

            // Скрываем кнопку «Готово»
            binding.glassEditDone.animate().alpha(0f).setDuration(160).withEndAction {
                binding.glassEditDone.visibility = View.GONE
            }.start()
            
            binding.glassDeletePage.animate().alpha(0f).setDuration(160).withEndAction {
                binding.glassDeletePage.visibility = View.GONE
            }.start()

            // Скрываем панель кнопок редактирования
            binding.glassEditModeBottomBar.animate()
                .alpha(0f)
                .translationY(24f * resources.displayMetrics.density)
                .setDuration(160)
                .withEndAction {
                    binding.glassEditModeBottomBar.visibility = View.GONE
                }.start()
        } else {
            binding.homeViewPager.scaleX = 1.0f
            binding.homeViewPager.scaleY = 1.0f
            binding.glassEditDone.alpha = 0f
            binding.glassEditDone.visibility = View.GONE
            binding.glassDeletePage.alpha = 0f
            binding.glassDeletePage.visibility = View.GONE
            binding.glassEditModeBottomBar.alpha = 0f
            binding.glassEditModeBottomBar.visibility = View.GONE
            binding.glassEditModeBottomBar.translationY = 0f
        }

        // Возвращаем точки страниц
        binding.pageIndicatorLayout.visibility = View.VISIBLE

        // Выключаем маркеры на иконках
        desktopPagerAdapter.setEditMode(false, emptySet())
    }

    /**
     * Парящее меню быстрых действий в стиле HyperOS непосредственно над иконкой
     */
    private fun showAppQuickActionsPopup(
        item: AppItem,
        anchorView: View,
        @Suppress("UNUSED_PARAMETER") isFromHomeScreen: Boolean
    ) {
        dismissActivePopups()
        
        val popupBinding = PopupAppQuickActionsBinding.inflate(layoutInflater)
        com.naua_morphix_launcher.app.util.ThemeUtils.applySettingsTheme(popupBinding.root, currentSettings)
        val glassView = popupBinding.root
        glassView.setupWithActivityRoot()
        glassView.setRadius(currentSettings.blurRadius)
        glassView.setGlassEnabled(currentSettings.isGlassEnabled)
        popupBinding.tvPopupAppTitle.text = item.label


        // 2. На рабочий стол / Убрать со стола
        popupBinding.btnPopupToggleHome.visibility = View.GONE
        popupBinding.btnPopupMoveToPage.visibility = View.GONE
        popupBinding.btnPopupFolder.visibility = View.GONE

        // 3. Второе пространство
        popupBinding.btnPopupSecondSpace.visibility = View.VISIBLE
        val isItemInSecondSpace = prefsManager.getSecondSpacePackages().contains(item.packageName)
        if (isSecondSpaceActive || isItemInSecondSpace) {
            popupBinding.btnPopupSecondSpace.text = "Копия для 2-го пространства"
            popupBinding.btnPopupSecondSpace.setOnClickListener {
                dismissActivePopups()
                prefsManager.removeSecondSpacePackage(item.packageName)
                loadInstalledApps()
                Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
            }
        } else {
            popupBinding.btnPopupSecondSpace.text = "Копия для 2-го пространства"
            popupBinding.btnPopupSecondSpace.setOnClickListener {
                dismissActivePopups()
                prefsManager.addSecondSpacePackage(item.packageName)
                loadInstalledApps()
                Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
            }
        }

        // 4. О приложении
        popupBinding.btnPopupAppInfo.setOnClickListener {
            dismissActivePopups()
            openAppInfoSmoothly(item)
        }

        // 5. Скрыть в хранилище Vault
        popupBinding.btnPopupHideVault.setOnClickListener {
            dismissActivePopups()
            hideAppWithSecurity(item)
        }

        // 6. Удалить
        popupBinding.btnPopupUninstall.setOnClickListener {
            dismissActivePopups()
            uninstallAppSafely(item)
        }

        val popupWindow = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = 16f * resources.displayMetrics.density
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener {
                if (currentPopup == this) {
                    currentPopup = null
                }
            }
        }

        currentPopup = popupWindow

        // Вычисляем координаты для парящей капсулы
        popupBinding.root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = popupBinding.root.measuredWidth
        val popupHeight = popupBinding.root.measuredHeight

        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val anchorX = location[0]
        val anchorY = location[1]

        val screenWidth = resources.displayMetrics.widthPixels
        val margin = (12 * resources.displayMetrics.density).toInt()

        var posX = anchorX + (anchorView.width - popupWidth) / 2
        posX = posX.coerceIn(margin, screenWidth - popupWidth - margin)

        val posY = if (anchorY - popupHeight - margin > 0) {
            anchorY - popupHeight - (6 * resources.displayMetrics.density).toInt()
        } else {
            anchorY + anchorView.height + (6 * resources.displayMetrics.density).toInt()
        }

        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, posX, posY)
        if (currentSettings.smoothAnimations) {
            popupBinding.root.scaleX = 0.85f
            popupBinding.root.scaleY = 0.85f
            popupBinding.root.alpha = 0f
            popupBinding.root.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(180)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                .start()
        }
    }

    private fun showAppActionsDialog(item: AppItem, isFromHomeScreen: Boolean) {
        showAppQuickActionsPopup(item, binding.root, isFromHomeScreen = isFromHomeScreen)
    }

    private fun showMoveAppToPageDialog(item: AppItem) {
        dismissActivePopups()
        val totalPages = desktopPagerAdapter.itemCount
        val options = mutableListOf<String>()
        for (i in 0 until totalPages) {
            options.add("Экран ${i + 1}")
        }
        options.add("➕ Создать новый экран")

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Переместить «${item.label}»")
            .setItems(options.toTypedArray()) { _, which ->
                val targetPageIndex = if (which < totalPages) which else totalPages
                moveAppToSpecificPage(item, targetPageIndex)
            }
            .setNegativeButton("Отмена", null)
            .create()
        currentDialog = dialog
        dialog.show()
    }

    private fun moveAppToSpecificPage(item: AppItem, targetPageIndex: Int) {
        val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            currentSettings.gridColumns + 2
        } else {
            currentSettings.gridColumns
        }
        val itemsPerPage = (spanCount * currentSettings.gridRows).coerceAtLeast(1)
        val firstPageCapacity = itemsPerPage

        val currentOrdered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
        if (currentOrdered.isEmpty()) {
            val allVisible = allApps.filter { !currentSettings.hiddenPackages.contains(it.packageName) }
            val spaceApps = if (currentSpace == 1) {
                allVisible.filter { it.isSecondSpace || prefsManager.getSecondSpacePackages().contains(it.packageName) }
            } else {
                allVisible.filter { !it.isSecondSpace }
            }
            val sourceList = if (spaceApps.isNotEmpty()) spaceApps else allVisible
            currentOrdered.addAll(sourceList.map { it.packageName })
        }

        val oldIdx = currentOrdered.indexOf(item.packageName)
        if (oldIdx != -1) {
            currentOrdered[oldIdx] = ""
        }

        val targetPageStart = if (targetPageIndex == 0) 0 else firstPageCapacity + (targetPageIndex - 1) * itemsPerPage
        val targetCapacity = if (targetPageIndex == 0) firstPageCapacity else itemsPerPage

        var targetPos = -1
        for (i in 0 until targetCapacity) {
            val idx = targetPageStart + i
            if (idx < currentOrdered.size && (currentOrdered[idx].isEmpty() || currentOrdered[idx] == PreferencesManager.EMPTY_CELL_KEY)) {
                targetPos = idx
                break
            }
        }
        if (targetPos == -1) {
            targetPos = (targetPageStart + targetCapacity - 1).coerceAtLeast(targetPageStart)
        }

        val maxNeeded = targetPos + 1
        while (currentOrdered.size < maxNeeded) {
            currentOrdered.add("")
        }

        currentOrdered[targetPos] = item.packageName
        prefsManager.setHomeScreenOrderedPackages(currentSpace, currentOrdered)

        updateHomeScreenApps()
        binding.homeViewPager.setCurrentItem(targetPageIndex, true)
        Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
    }

    private fun showCreateOrAddToFolderDialog(item: AppItem) {
        dismissActivePopups()
        val existingFolders = prefsManager.getFolders(currentSpace).values.toList()
        val options = mutableListOf<String>()
        options.add("➕ Создать новую папку")
        for (f in existingFolders) {
            options.add("📁 Добавить в «${f.name}» (${f.packageNames.size} прил.)")
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Папки приложений")
            .setItems(options.toTypedArray()) { _, which ->
                if (which == 0) {
                    promptCreateNewFolder(item)
                } else {
                    val targetFolder = existingFolders[which - 1]
                    if (!targetFolder.packageNames.contains(item.packageName)) {
                        targetFolder.packageNames.add(item.packageName)
                        prefsManager.saveFolder(currentSpace, targetFolder)
                        val ordered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
                        ordered.remove(item.packageName)
                        prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
                        updateHomeScreenApps()
Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Отмена", null)
            .create()
        currentDialog = dialog
        dialog.show()
    }

    private fun promptCreateNewFolder(firstApp: AppItem) {
        dismissActivePopups()
        val input = EditText(this).apply {
            hint = "Название папки"
            setText("Новая папка")
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setPadding(40, 20, 40, 20)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Создать папку")
            .setView(input)
            .setPositiveButton("Создать") { _, _ ->
                val name = input.text.toString().trim().ifEmpty { "Новая папка" }
                val folderId = UUID.randomUUID().toString()
                val newFolder = FolderItem(id = folderId, name = name, packageNames = mutableListOf(firstApp.packageName))
                prefsManager.saveFolder(currentSpace, newFolder)

                val ordered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
                val idx = ordered.indexOf(firstApp.packageName)
                if (idx != -1) {
                    ordered[idx] = "folder:$folderId"
                } else {
                    ordered.add("folder:$folderId")
                }
                prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
                updateHomeScreenApps()
Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .create()
        currentDialog = dialog
        dialog.show()
    }

    private fun showFolderViewDialog(folderAppItem: AppItem) {
        dismissActivePopups()
        val folderId = folderAppItem.folderId ?: return
        var currentFolder = prefsManager.getFolders(currentSpace)[folderId] ?: return

        activeFolderIdBeingViewed = folderId

        val overlay = binding.folderFullscreenOverlay

        com.naua_morphix_launcher.app.util.ThemeUtils.applySettingsTheme(overlay.root, currentSettings)

        overlay.etFolderTitle.setText(currentFolder.name)
        overlay.ivRenameHint.setOnClickListener {
            overlay.etFolderTitle.requestFocus()
            overlay.etFolderTitle.selectAll()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(overlay.etFolderTitle, InputMethodManager.SHOW_IMPLICIT)
        }
        overlay.etFolderTitle.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                val newName = overlay.etFolderTitle.text?.toString()?.trim()
                if (!newName.isNullOrEmpty() && newName != currentFolder.name) {
                    currentFolder.name = newName
                    prefsManager.saveFolder(currentSpace, currentFolder)
                    updateHomeScreenApps()
                }
                overlay.etFolderTitle.clearFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(overlay.etFolderTitle.windowToken, 0)
                true
            } else {
                false
            }
        }
        overlay.etFolderTitle.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val newName = overlay.etFolderTitle.text?.toString()?.trim()
                if (!newName.isNullOrEmpty() && newName != currentFolder.name) {
                    currentFolder.name = newName
                    prefsManager.saveFolder(currentSpace, currentFolder)
                    updateHomeScreenApps()
                }
            }
        }
        overlay.etFolderTitle.addTextChangedListener { editable ->
            val newName = editable?.toString()?.trim()
            if (!newName.isNullOrEmpty()) {
                currentFolder.name = newName
            }
        }

        // Разбиваем приложения в папке на страницы максимум 3x3 (9 иконок на страницу)
        val appsList = folderAppItem.folderApps
        val folderPages = if (appsList.isEmpty()) {
            listOf(emptyList())
        } else {
            appsList.chunked(9)
        }

        // Адаптивная высота под количество рядов (1 ряд: 90dp, 2 ряда: 180dp, 3 ряда: 270dp)
        val density = resources.displayMetrics.density
        val rowCount = when {
            appsList.size <= 3 -> 1
            appsList.size <= 6 -> 2
            else -> 3
        }
        val targetHeightPx = (rowCount * 90 * density).toInt()
        val vpLp = overlay.vpFolderPages.layoutParams
        if (vpLp.height != targetHeightPx) {
            vpLp.height = targetHeightPx
            overlay.vpFolderPages.layoutParams = vpLp
        }

        val folderPagerAdapter = FolderPagerAdapter(
            pages = folderPages,
            iconShape = currentSettings.iconShape,
            showLabels = currentSettings.showLabels,
            iconScale = currentSettings.iconScale,
            smoothAnimations = currentSettings.smoothAnimations,
            onAppClick = { app ->
                closeFolderView()
                AppLoader.launchApp(this, app, smoothAnimations = currentSettings.smoothAnimations)
            },
            onAppClickWithView = { app, view ->
                closeFolderView()
                AppLoader.launchApp(this, app, view, smoothAnimations = currentSettings.smoothAnimations)
            },
            onAppLongClick = { app, _ ->
                showAppInFolderActionsPopup(app, currentFolder) {
                    val updatedItem = resolveDesktopItemDirect("folder:$folderId")
                    if (updatedItem != null) {
                        showFolderViewDialog(updatedItem)
                    } else {
                        closeFolderView()
                    }
                }
            },
            onAppStartDrag = { app, view, rawX, rawY ->
                val curFolderPage = overlay.vpFolderPages.currentItem
                val rv = (overlay.vpFolderPages.adapter as? FolderPagerAdapter)?.getRecyclerViewForPage(curFolderPage)
                currentFolderPageDragIndex = rv?.getChildAdapterPosition(view) ?: -1
                startFolderItemDrag(app, view, rawX, rawY, folderId)
            },
            onItemsReordered = { reorderedApps ->
                currentFolder.packageNames.clear()
                currentFolder.packageNames.addAll(reorderedApps.filter { !it.isEmpty }.map { it.packageName })
                prefsManager.saveFolder(currentSpace, currentFolder)
                updateHomeScreenApps()
            }
        )
        overlay.vpFolderPages.adapter = folderPagerAdapter
        overlay.vpFolderPages.setPageTransformer { page, position ->
            if (currentSettings.smoothAnimations) {
                when {
                    position < -1 -> {
                        page.alpha = 0f
                    }
                    position <= 1 -> {
                        val absPos = kotlin.math.abs(position)
                        val scale = 0.95f + (1f - 0.95f) * (1f - absPos)
                        page.scaleX = scale
                        page.scaleY = scale
                        page.alpha = (1f - absPos * 0.35f).coerceIn(0f, 1f)
                    }
                    else -> {
                        page.alpha = 0f
                    }
                }
            } else {
                page.scaleX = 1f
                page.scaleY = 1f
                page.alpha = 1f
            }
        }

        // Индикатор точек для перелистывания страниц внутри папки
        updateFolderPageIndicator(overlay.folderPageIndicator, 0, folderPages.size)
        overlay.vpFolderPages.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateFolderPageIndicator(overlay.folderPageIndicator, position, folderPages.size)
            }
        })

        overlay.btnAddAppToFolder.setOnClickListener {
            showAddAppsToFolderPicker(currentFolder) {
                val updatedItem = resolveDesktopItemDirect("folder:$folderId")
                if (updatedItem != null) {
                    showFolderViewDialog(updatedItem)
                }
            }
        }

        // Закрытие при клике вне карточки папки (по фону)
        overlay.folderFullscreenRoot.setOnClickListener {
            closeFolderView()
        }
        overlay.folderContentContainer.setOnClickListener {
            closeFolderView()
        }
        overlay.folderCardWindow.setOnClickListener {
            // Поглощаем клик внутри карточки
        }

        if (currentSettings.isGlassEnabled) {
            overlay.glassFolderCard.updateBackgroundSnapshot()
        }

        overlay.root.visibility = View.VISIBLE
        if (currentSettings.smoothAnimations) {
            overlay.folderCardWindow.scaleX = 0.82f
            overlay.folderCardWindow.scaleY = 0.82f
            overlay.folderCardWindow.alpha = 0f
            overlay.folderDimOverlay.alpha = 0f

            overlay.folderCardWindow.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(240)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.15f))
                .start()

            overlay.folderDimOverlay.animate()
                .alpha(1.0f)
                .setDuration(220)
                .start()

            binding.homeViewPager.animate()
                .scaleX(0.95f)
                .scaleY(0.95f)
                .setDuration(240)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            overlay.folderCardWindow.scaleX = 1.0f
            overlay.folderCardWindow.scaleY = 1.0f
            overlay.folderCardWindow.alpha = 1.0f
            overlay.folderDimOverlay.alpha = 1.0f
            binding.homeViewPager.scaleX = 0.95f
            binding.homeViewPager.scaleY = 0.95f
        }
    }

    private fun closeFolderView() {
        val overlay = binding.folderFullscreenOverlay
        if (overlay.root.visibility != View.VISIBLE) return

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(overlay.etFolderTitle.windowToken, 0)
        overlay.etFolderTitle.clearFocus()

        val newName = overlay.etFolderTitle.text?.toString()?.trim()
        val folderId = activeFolderIdBeingViewed
        if (folderId != null && !newName.isNullOrEmpty()) {
            val folder = prefsManager.getFolders(currentSpace)[folderId]
            if (folder != null && folder.name != newName) {
                folder.name = newName
                prefsManager.saveFolder(currentSpace, folder)
                updateHomeScreenApps()
            }
        }
        activeFolderIdBeingViewed = null

        if (currentSettings.smoothAnimations) {
            overlay.folderCardWindow.animate()
                .scaleX(0.85f)
                .scaleY(0.85f)
                .alpha(0f)
                .setDuration(170)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    overlay.root.visibility = View.GONE
                }
                .start()

            overlay.folderDimOverlay.animate()
                .alpha(0f)
                .setDuration(170)
                .start()

            binding.homeViewPager.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(200)
                .setInterpolator(DecelerateInterpolator())
                .start()
        } else {
            overlay.root.visibility = View.GONE
            overlay.folderCardWindow.scaleX = 1.0f
            overlay.folderCardWindow.scaleY = 1.0f
            overlay.folderCardWindow.alpha = 1.0f
            overlay.folderDimOverlay.alpha = 0f
            binding.homeViewPager.scaleX = 1.0f
            binding.homeViewPager.scaleY = 1.0f
        }
    }

    private fun updateFolderPageIndicator(container: LinearLayout, currentPage: Int, totalPages: Int) {
        if (totalPages <= 1) {
            container.removeAllViews()
            container.visibility = View.GONE
            return
        }
        container.visibility = View.VISIBLE

        if (container.childCount == totalPages) {
            for (i in 0 until totalPages) {
                val dot = container.getChildAt(i) ?: continue
                val isActive = (i == currentPage)
                if (currentSettings.smoothAnimations) {
                    dot.animate()
                        .scaleX(if (isActive) 1.35f else 1.0f)
                        .scaleY(if (isActive) 1.35f else 1.0f)
                        .alpha(if (isActive) 1.0f else 0.45f)
                        .setDuration(160)
                        .start()
                } else {
                    dot.scaleX = if (isActive) 1.35f else 1.0f
                    dot.scaleY = if (isActive) 1.35f else 1.0f
                    dot.alpha = if (isActive) 1.0f else 0.45f
                }
            }
            return
        }

        container.removeAllViews()
        val density = resources.displayMetrics.density
        for (i in 0 until totalPages) {
            val isActive = (i == currentPage)
            val dot = View(this).apply {
                val size = (6 * density).toInt()
                val lp = LinearLayout.LayoutParams(size, size).apply {
                    val margin = (3 * density).toInt()
                    setMargins(margin, 0, margin, 0)
                }
                layoutParams = lp
                val bg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                background = bg
                alpha = if (isActive) 1.0f else 0.45f
                scaleX = if (isActive) 1.35f else 1.0f
                scaleY = if (isActive) 1.35f else 1.0f
            }
            container.addView(dot)
        }
    }

    private fun resolveDesktopItemDirect(key: String): AppItem? {
        val allVisible = allApps.filter { !currentSettings.hiddenPackages.contains(it.packageName) }
        val foldersMap = prefsManager.getFolders(currentSpace)
        if (key.startsWith("folder:")) {
            val folderId = key.removePrefix("folder:")
            val folder = foldersMap[folderId] ?: return null
            val folderApps = folder.packageNames.mapNotNull { pkg -> allVisible.find { it.packageName == pkg } }
            return AppItem(
                label = folder.name,
                packageName = key,
                activityName = "",
                icon = null,
                isFolder = true,
                folderId = folder.id,
                folderApps = folderApps,
                folderSize = folder.size
            )
        }
        return allVisible.find { it.packageName == key }
    }

    private fun showAddAppsToFolderPicker(folder: FolderItem, onUpdated: () -> Unit) {
        dismissActivePopups()
        val availableApps = allApps.filter { !it.isFolder && !folder.packageNames.contains(it.packageName) }
        if (availableApps.isEmpty()) {
            Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = availableApps.map { it.label }.toTypedArray()
        val checked = BooleanArray(availableApps.size)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Добавить в «${folder.name}»")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Сохранить") { _, _ ->
                val selected = availableApps.filterIndexed { idx, _ -> checked[idx] }.map { it.packageName }
                folder.packageNames.addAll(selected)
                prefsManager.saveFolder(currentSpace, folder)
                val ordered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
                ordered.removeAll(selected)
                prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
                updateHomeScreenApps()
                onUpdated()
            }
            .setNegativeButton("Отмена", null)
            .create()
        currentDialog = dialog
        dialog.show()
    }

    private fun showAppInFolderActionsPopup(app: AppItem, folder: FolderItem, onRefreshFolder: () -> Unit) {
        dismissActivePopups()
        val options = arrayOf(
            "❌ Убрать из папки",
            "ℹ️ О приложении",
            "🗑️ Удалить"
        )
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        folder.packageNames.remove(app.packageName)
                        prefsManager.saveFolder(currentSpace, folder)
                        prefsManager.addHomeScreenPackage(currentSpace, app.packageName)
                        updateHomeScreenApps()
                        Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
                        onRefreshFolder()
                    }
                    1 -> openAppInfoSmoothly(app)
                    2 -> uninstallAppSafely(app)
                }
            }
            .setNegativeButton("Отмена", null)
            .create()
        currentDialog = dialog
        dialog.show()
    }

    private fun showFolderActionsPopup(folderItem: AppItem, anchorView: View) {
        dismissActivePopups()
        val folderId = folderItem.folderId ?: return
        val folder = prefsManager.getFolders(currentSpace)[folderId] ?: return

        val popupBinding = com.naua_morphix_launcher.app.databinding.PopupFolderActionsBinding.inflate(layoutInflater)
        com.naua_morphix_launcher.app.util.ThemeUtils.applySettingsTheme(popupBinding.root, currentSettings)
        val glassView = popupBinding.root
        glassView.setupWithActivityRoot()
        glassView.setRadius(currentSettings.blurRadius)
        glassView.setGlassEnabled(currentSettings.isGlassEnabled)
        popupBinding.tvPopupFolderTitle.text = folder.name

        popupBinding.btnPopupFolderDissolve.setOnClickListener {
            dismissActivePopups()
            dissolveFolder(folder)
        }

        popupBinding.btnPopupFolderDelete.setOnClickListener {
            dismissActivePopups()
            deleteFolderConfirm(folder)
        }

        popupBinding.btnPopupFolderResize.setOnClickListener {
            dismissActivePopups()
            showFolderResizeDialog(folder)
        }

        val popupWindow = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        popupWindow.isOutsideTouchable = true
        popupWindow.elevation = 20f

        currentPopup = popupWindow

        popupBinding.root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupW = popupBinding.root.measuredWidth
        val popupH = popupBinding.root.measuredHeight

        val loc = IntArray(2)
        anchorView.getLocationOnScreen(loc)
        val anchorX = loc[0]
        val anchorY = loc[1]
        val screenWidth = resources.displayMetrics.widthPixels

        var posX = anchorX + (anchorView.width / 2) - (popupW / 2)
        if (posX < 20) posX = 20
        if (posX + popupW > screenWidth - 20) posX = screenWidth - popupW - 20

        var posY = anchorY - popupH - (6 * resources.displayMetrics.density).toInt()
        if (posY < 50) {
            posY = anchorY + anchorView.height + (6 * resources.displayMetrics.density).toInt()
        }

        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, posX, posY)
        if (currentSettings.smoothAnimations) {
            popupBinding.root.scaleX = 0.85f
            popupBinding.root.scaleY = 0.85f
            popupBinding.root.alpha = 0f
            popupBinding.root.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(180)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                .start()
        }
    }

    private fun promptRenameFolder(folder: FolderItem) {
        val input = EditText(this).apply {
            setText(folder.name)
            setSingleLine(true)
            setSelectAllOnFocus(true)
            setPadding(40, 20, 40, 20)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Переименовать папку")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                val newName = input.text.toString().trim().ifEmpty { "Новая папка" }
                folder.name = newName
                prefsManager.saveFolder(currentSpace, folder)
                updateHomeScreenApps()
            }
            .setNegativeButton("Отмена", null)
            .create()
        currentDialog = dialog
        dialog.show()
    }

    private fun showFolderResizeDialog(folder: FolderItem) {
        val sizes = arrayOf("Обычный (1×1)", "Увеличенный (2×2)", "Большой (XXL)")
        val sizeKeys = arrayOf("REGULAR", "ENLARGED", "XXL")
        val currentIndex = sizeKeys.indexOf(folder.size).coerceAtLeast(0)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Размер папки")
            .setSingleChoiceItems(sizes, currentIndex) { d, which ->
                d.dismiss()
                val newSize = sizeKeys[which]
                if (folder.size != newSize) {
                    folder.size = newSize
                    prefsManager.saveFolder(currentSpace, folder)
                    updateHomeScreenApps()
                    Toast.makeText(this, "Размер изменён на «${sizes[which]}»", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .create()
        currentDialog = dialog
        dialog.show()
    }

    private fun dissolveFolder(folder: FolderItem) {
        val ordered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
        val folderKey = "folder:${folder.id}"
        val idx = ordered.indexOf(folderKey)
        if (idx != -1) {
            ordered.removeAt(idx)
            ordered.addAll(idx, folder.packageNames)
        } else {
            ordered.addAll(folder.packageNames)
        }
        prefsManager.deleteFolder(currentSpace, folder.id)
        prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
        updateHomeScreenApps()
Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
    }

    private fun deleteFolderConfirm(folder: FolderItem) {
        val ordered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
        val idx = ordered.indexOf("folder:${folder.id}")
        if (idx != -1) {
            ordered[idx] = ""
        }
        prefsManager.deleteFolder(currentSpace, folder.id)
        prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
        updateHomeScreenApps()
Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
    }

    private fun handleClockClick() {
        try {
            startActivity(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
        } catch (e: Exception) {
            handleScreenTap()
        }
    }

    private fun handleDateClick() {
        try {
            startActivity(Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_APP_CALENDAR) })
        } catch (e: Exception) {
            handleScreenTap()
        }
    }

    private fun handleWeatherClick() {
        try {
            val weatherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage("com.miui.weather2")
            }
            startActivity(weatherIntent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://weather.com")))
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    private fun isDefaultLauncher(): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
        }
        val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.resolveActivity(
                intent,
                android.content.pm.PackageManager.ResolveInfoFlags.of(android.content.pm.PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            packageManager.resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
        }
        return resolveInfo?.activityInfo?.packageName == packageName
    }

    private fun checkDefaultLauncherPrompt() {
        if (!isDefaultLauncher()) {
            binding.root.postDelayed({
                if (!isFinishing && !isDestroyed && !isDefaultLauncher()) {
                    showSetDefaultLauncherDialog()
                }
            }, 1200)
        }
    }

    private fun showSetDefaultLauncherDialog() {
        dismissActivePopups()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Добро пожаловать в Morphix!")
            .setMessage("Пожалуйста, сделайте Morphix Launcher вашим приложением по умолчанию для лучшего опыта.")
            .setPositiveButton("Перейти в настройки") { _, _ ->
                openDefaultLauncherChooser()
            }
            .setNegativeButton("Позже", null)
            .create()
        currentDialog = dialog
        dialog.show()
    }

    private fun openDefaultLauncherChooser() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE) as? android.app.role.RoleManager
            if (roleManager?.isRoleAvailable(android.app.role.RoleManager.ROLE_HOME) == true &&
                !roleManager.isRoleHeld(android.app.role.RoleManager.ROLE_HOME)
            ) {
                val intent = roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_HOME)
                try {
                    startActivity(intent)
                    return
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Откройте Настройки -> Приложения по умолчанию", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun launchWidgetPicker() {
        showWidgetPickerDialog()
    }

    private fun loadWidgetGroupsBackground(): List<AppWidgetGroup> {
        val allProviders = appWidgetManager.installedProviders ?: emptyList()
        val pm = packageManager
        val grouped = allProviders.groupBy { it.provider.packageName }
        val appGroups = mutableListOf<AppWidgetGroup>()
        val densityDpi = resources.displayMetrics.densityDpi

        for ((pkg, providers) in grouped) {
            val appInfo = try {
                pm.getApplicationInfo(pkg, 0)
            } catch (e: Exception) {
                null
            }
            val appLabel = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: pkg
            val appIcon = appInfo?.let { pm.getApplicationIcon(it) }

            val widgetItems = providers.map { info ->
                val widgetLabel = try {
                    info.loadLabel(pm)?.toString()
                } catch (e: Exception) {
                    null
                } ?: appLabel

                val icon = try {
                    info.loadIcon(this, densityDpi)
                } catch (e: Exception) {
                    null
                } ?: appIcon

                val cols = Math.ceil(info.minWidth / 70.0).toInt().coerceIn(1, 4)
                val rows = Math.ceil(info.minHeight / 70.0).toInt().coerceIn(1, 4)
                val sizeText = "$cols \u00d7 $rows"

                WidgetItem(info, appLabel, widgetLabel, icon, null, sizeText)
            }.sortedBy { it.widgetLabel.lowercase(Locale.getDefault()) }

            appGroups.add(
                AppWidgetGroup(
                    packageName = pkg,
                    appLabel = appLabel,
                    appIcon = appIcon,
                    widgets = widgetItems,
                    isExpanded = false
                )
            )
        }

        appGroups.sortBy { it.appLabel.lowercase(Locale.getDefault()) }
        cachedWidgetGroups = appGroups
        return appGroups
    }

    private fun showWidgetPickerDialog() {
        dismissActivePopups()
        val dialog = BottomSheetDialog(this)
        currentDialog = dialog
        val pickerBinding = DialogWidgetPickerBinding.inflate(layoutInflater)
        com.naua_morphix_launcher.app.util.ThemeUtils.applySettingsTheme(pickerBinding.root, currentSettings)
        dialog.setContentView(pickerBinding.root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        pickerBinding.glassSettingsRoot.setupWithActivityRoot()
        pickerBinding.glassSettingsRoot.setRadius(currentSettings.blurRadius)
        pickerBinding.glassSettingsRoot.setGlassEnabled(currentSettings.isGlassEnabled)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true

        val adapter = WidgetsAdapter(smoothAnimations = currentSettings.smoothAnimations) { selectedItem ->
            dialog.dismiss()
            addSelectedWidget(selectedItem.info)
        }

        pickerBinding.rvWidgetsList.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        pickerBinding.rvWidgetsList.adapter = adapter

        fun setupFilterAndDisplay(groups: List<AppWidgetGroup>) {
            pickerBinding.pbWidgetsLoading.visibility = View.GONE
            adapter.submitList(groups)

            if (groups.isEmpty()) {
                pickerBinding.tvWidgetsEmpty.visibility = View.VISIBLE
                pickerBinding.rvWidgetsList.visibility = View.GONE
            } else {
                pickerBinding.tvWidgetsEmpty.visibility = View.GONE
                pickerBinding.rvWidgetsList.visibility = View.VISIBLE
            }

            pickerBinding.etWidgetSearch.addTextChangedListener { editable ->
                val query = editable?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
                val filtered = if (query.isEmpty()) {
                    groups.map { it.copy(isExpanded = false) }
                } else {
                    groups.mapNotNull { group ->
                        val appMatches = group.appLabel.lowercase(Locale.getDefault()).contains(query)
                        val matchingWidgets = group.widgets.filter {
                            it.widgetLabel.lowercase(Locale.getDefault()).contains(query) ||
                            it.appLabel.lowercase(Locale.getDefault()).contains(query)
                        }
                        if (appMatches) {
                            group.copy(widgets = group.widgets, isExpanded = true)
                        } else if (matchingWidgets.isNotEmpty()) {
                            group.copy(widgets = matchingWidgets, isExpanded = true)
                        } else {
                            null
                        }
                    }
                }
                adapter.submitList(filtered)
                pickerBinding.tvWidgetsEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
                pickerBinding.rvWidgetsList.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        val cached = cachedWidgetGroups
        if (cached != null) {
            setupFilterAndDisplay(cached)
        } else {
            pickerBinding.pbWidgetsLoading.visibility = View.VISIBLE
            pickerBinding.rvWidgetsList.visibility = View.GONE
            pickerBinding.tvWidgetsEmpty.visibility = View.GONE

            lifecycleScope.launch(Dispatchers.IO) {
                val loaded = loadWidgetGroupsBackground()
                withContext(Dispatchers.Main) {
                    if (dialog.isShowing) {
                        setupFilterAndDisplay(loaded)
                    }
                }
            }
        }

        dialog.show()
    }

    private fun addSelectedWidget(info: android.appwidget.AppWidgetProviderInfo) {
        val newWidgetId = appWidgetHost.allocateAppWidgetId()
        val canBind = appWidgetManager.bindAppWidgetIdIfAllowed(newWidgetId, info.provider)
        if (!canBind) {
            val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, newWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
            }
            try {
                startActivityForResult(bindIntent, REQUEST_BIND_APPWIDGET)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        proceedWithWidgetConfiguration(newWidgetId, info)
    }

    private fun configureWidgetOrAdd(appWidgetId: Int) {
        val appWidgetInfo = appWidgetManager.getAppWidgetInfo(appWidgetId)
        if (appWidgetInfo == null) {
            appWidgetHost.deleteAppWidgetId(appWidgetId)
            return
        }

        val canBind = appWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, appWidgetInfo.provider)
        if (!canBind) {
            val bindIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, appWidgetInfo.provider)
            }
            try {
                startActivityForResult(bindIntent, REQUEST_BIND_APPWIDGET)
                return
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        proceedWithWidgetConfiguration(appWidgetId, appWidgetInfo)
    }

    private fun proceedWithWidgetConfiguration(appWidgetId: Int, appWidgetInfo: android.appwidget.AppWidgetProviderInfo) {
        if (appWidgetInfo.configure != null) {
            val configIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = appWidgetInfo.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            try {
                startActivityForResult(configIntent, REQUEST_CREATE_APPWIDGET)
            } catch (e: Exception) {
                attachWidgetToHome(appWidgetId)
            }
        } else {
            attachWidgetToHome(appWidgetId)
        }
    }

    private val widgetViewCache = mutableMapOf<Int, AppWidgetHostView>()

    private fun getOrCreateWidgetView(widgetId: Int): AppWidgetHostView? {
        val info = appWidgetManager.getAppWidgetInfo(widgetId) ?: return null
        return widgetViewCache[widgetId] ?: run {
            try {
                val created = appWidgetHost.createView(this, widgetId, info)
                if (created != null) {
                    created.setAppWidget(widgetId, info)
                    val density = resources.displayMetrics.density
                    val padding = (4 * density).toInt()
                    created.setPadding(padding, padding, padding, padding)
                    widgetViewCache[widgetId] = created
                }
                created
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    private fun bindWidgetsForPage(@Suppress("UNUSED_PARAMETER") pageIndex: Int, container: ViewGroup) {
        container.removeAllViews()
        container.visibility = View.GONE
    }

    private fun attachWidgetToHome(appWidgetId: Int) {
        if (appWidgetManager.getAppWidgetInfo(appWidgetId) == null) return
        val oldId = replacingWidgetId
        replacingWidgetId = null

        val currentOrdered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
        val currentPage = binding.homeViewPager.currentItem.coerceAtLeast(0)

        if (oldId != null) {
            val oldKey = "widget:$oldId"
            val oldIndex = currentOrdered.indexOf(oldKey)
            if (oldIndex != -1) {
                currentOrdered[oldIndex] = "widget:$appWidgetId"
            } else {
                currentOrdered.add("widget:$appWidgetId")
            }
            widgetViewCache.remove(oldId)
            try {
                appWidgetHost.deleteAppWidgetId(oldId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            prefsManager.removeAppWidgetId(oldId)
        } else {
            currentOrdered.add("widget:$appWidgetId")
        }

        prefsManager.addAppWidgetId(appWidgetId, currentPage)
        prefsManager.setHomeScreenOrderedPackages(currentSpace, currentOrdered)
        updateHomeScreenApps()
        desktopPagerAdapter.refreshWidgets()

        val msg = if (oldId != null) "Виджет успешно заменен" else "Виджет добавлен на экран ${currentPage + 1}"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun restoreInstalledWidgets() {
        desktopPagerAdapter.refreshWidgets()
    }

    private fun deleteWidgetPermanently(widgetId: Int) {
        val currentOrdered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
        val widgetKey = "widget:$widgetId"
        val idx = currentOrdered.indexOf(widgetKey)
        if (idx != -1) {
            currentOrdered[idx] = ""
        } else {
            currentOrdered.remove(widgetKey)
        }
        widgetViewCache.remove(widgetId)
        widgetMetaCache.remove(widgetId)
        prefsManager.removeAppWidgetId(widgetId)
        prefsManager.setHomeScreenOrderedPackages(currentSpace, currentOrdered)
        updateHomeScreenApps()

        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                appWidgetHost.deleteAppWidgetId(widgetId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun showWidgetQuickActionsPopup(item: AppItem, anchorView: View) {
        dismissActivePopups()
        val widgetId = item.widgetId ?: return
        val popupBinding = com.naua_morphix_launcher.app.databinding.PopupWidgetQuickActionsBinding.inflate(layoutInflater)
        com.naua_morphix_launcher.app.util.ThemeUtils.applySettingsTheme(popupBinding.root, currentSettings)
        val glassView = popupBinding.root
        glassView.setupWithActivityRoot()
        glassView.setRadius(currentSettings.blurRadius)
        glassView.setGlassEnabled(currentSettings.isGlassEnabled)

        val info = appWidgetManager.getAppWidgetInfo(widgetId)
        val widgetLabel = info?.loadLabel(packageManager)?.toString() ?: item.label.ifEmpty { "Виджет" }
        popupBinding.tvPopupWidgetTitle.text = "$widgetLabel (${item.widgetSpanX}x${item.widgetSpanY})"

        // 1. Изменить виджет (удаляет старый и на его место ставит новый)
        popupBinding.btnPopupChangeWidget.setOnClickListener {
            dismissActivePopups()
            replacingWidgetId = widgetId
            launchWidgetPicker()
        }

        // 2. На другой экран...
        popupBinding.btnPopupMoveWidgetToPage.setOnClickListener {
            dismissActivePopups()
            showMoveAppToPageDialog(item)
        }

        // 3. Удалить виджет
        popupBinding.btnPopupRemoveWidget.setOnClickListener {
            dismissActivePopups()
            deleteWidgetPermanently(widgetId)
        }

        val popupWindow = PopupWindow(
            popupBinding.root,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = 16f * resources.displayMetrics.density
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setOnDismissListener {
                if (currentPopup == this) {
                    currentPopup = null
                }
            }
        }

        currentPopup = popupWindow

        popupBinding.root.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = popupBinding.root.measuredWidth
        val popupHeight = popupBinding.root.measuredHeight

        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val anchorX = location[0]
        val anchorY = location[1]

        val screenWidth = resources.displayMetrics.widthPixels
        val margin = (12 * resources.displayMetrics.density).toInt()

        var posX = anchorX + (anchorView.width - popupWidth) / 2
        posX = posX.coerceIn(margin, screenWidth - popupWidth - margin)

        val posY = if (anchorY - popupHeight - margin > 0) {
            anchorY - popupHeight - (6 * resources.displayMetrics.density).toInt()
        } else {
            anchorY + anchorView.height + (6 * resources.displayMetrics.density).toInt()
        }

        popupWindow.showAtLocation(anchorView, Gravity.NO_GRAVITY, posX, posY)
        if (currentSettings.smoothAnimations) {
            popupBinding.root.scaleX = 0.85f
            popupBinding.root.scaleY = 0.85f
            popupBinding.root.alpha = 0f
            popupBinding.root.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .alpha(1.0f)
                .setDuration(180)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                .start()
        }
    }

    private fun showWidgetActionsDialog(appWidgetId: Int, currentPageIndex: Int) {
        val info = appWidgetManager.getAppWidgetInfo(appWidgetId)
        val span = getWidgetSpan(info, 4)
        val item = AppItem(
            label = info?.loadLabel(packageManager)?.toString() ?: "Виджет",
            packageName = "widget:$appWidgetId",
            activityName = "",
            icon = null,
            isWidget = true,
            widgetId = appWidgetId,
            widgetSpanX = span.first,
            widgetSpanY = span.second
        )
        val currentRv = desktopPagerAdapter.getRecyclerViewForPage(currentPageIndex)
        val anchorView = currentRv ?: binding.root
        showWidgetQuickActionsPopup(item, anchorView)
    }

    private fun showMoveWidgetToPageDialog(appWidgetId: Int, @Suppress("UNUSED_PARAMETER") currentPageIndex: Int) {
        val info = appWidgetManager.getAppWidgetInfo(appWidgetId)
        val span = getWidgetSpan(info, 4)
        val item = AppItem(
            label = info?.loadLabel(packageManager)?.toString() ?: "Виджет",
            packageName = "widget:$appWidgetId",
            activityName = "",
            icon = null,
            isWidget = true,
            widgetId = appWidgetId,
            widgetSpanX = span.first,
            widgetSpanY = span.second
        )
        showMoveAppToPageDialog(item)
    }

    /**
     * Переключение Второго пространства в стиле Xiaomi
     */
    private fun requestSwitchSpace() {
        val targetSpace = if (currentSpace == 0) 1 else 0
        if (targetSpace == 1 && prefsManager.isSecondSpaceProtected()) {
            authenticateForSecondSpace {
                performSpaceSwitch(targetSpace)
            }
        } else {
            performSpaceSwitch(targetSpace)
        }
    }

    private fun performSpaceSwitch(targetSpace: Int) {
        currentSpace = targetSpace
        prefsManager.setCurrentSpace(targetSpace)
        isSecondSpaceActive = (targetSpace == 1)

        if (currentSettings.smoothAnimations) {
            if (isSecondSpaceActive) {
                binding.badgeSecondSpace.visibility = View.VISIBLE
                binding.badgeSecondSpace.alpha = 0f
                binding.badgeSecondSpace.scaleX = 0.5f
                binding.badgeSecondSpace.scaleY = 0.5f
                binding.badgeSecondSpace.animate()
                    .alpha(1f)
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(220)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.4f))
                    .start()
            } else {
                binding.badgeSecondSpace.animate()
                    .alpha(0f)
                    .scaleX(0.5f)
                    .scaleY(0.5f)
                    .setDuration(160)
                    .withEndAction {
                        binding.badgeSecondSpace.visibility = View.GONE
                        binding.badgeSecondSpace.scaleX = 1f
                        binding.badgeSecondSpace.scaleY = 1f
                    }
                    .start()
            }
        } else {
            binding.badgeSecondSpace.visibility = if (isSecondSpaceActive) View.VISIBLE else View.GONE
            binding.badgeSecondSpace.alpha = 1f
            binding.badgeSecondSpace.scaleX = 1f
            binding.badgeSecondSpace.scaleY = 1f
        }
        val spaceName = if (isSecondSpaceActive) "Второе пространство" else "Основное пространство"
        Toast.makeText(this, "Переключено: $spaceName", Toast.LENGTH_SHORT).show()

        if (binding.glassAppDrawer.visibility == View.VISIBLE) {
            closeAppDrawer()
        }

        if (currentSettings.smoothAnimations) {
            binding.homeViewPager.animate()
                .alpha(0f)
                .scaleX(0.94f)
                .scaleY(0.94f)
                .setDuration(140)
                .withEndAction {
                    updateHomeScreenApps()
                    filterAppsBySpace(isSecondSpaceActive)
                    binding.homeViewPager.animate()
                        .alpha(1f)
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .setDuration(200)
                        .setInterpolator(DecelerateInterpolator())
                        .start()
                }
                .start()
        } else {
            updateHomeScreenApps()
            filterAppsBySpace(isSecondSpaceActive)
        }
    }

    private fun authenticateForSecondSpace(onSuccess: () -> Unit) {
        val executor: Executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(this@MainActivity, "Авторизация отменена", Toast.LENGTH_SHORT).show()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Второе пространство Morphix")
            .setSubtitle("Подтвердите личность для перехода")
            .setNegativeButtonText("Отмена")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    /**
     * Метку времени, когда долгое нажатие открыло режим редактирования.
     * После отпускания пальца долетает ACTION_UP, который иначе засчитался бы
     * вторым тапом в handleScreenTap() и сразу вышел бы из режима редактирования.
     */
private var editModeOpenedAt = 0L

    private fun handleScreenTap() {
        if (isEditMode) {
            // не реагируем на тап, случившийся в момент открытия режима редактирования
            if (SystemClock.uptimeMillis() - editModeOpenedAt < 700) {
                tapCounter = 0
                return
            }
            exitEditMode()
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastTapTime < 450) {
            tapCounter++
        } else {
            tapCounter = 1
        }
        lastTapTime = now

        if (currentSettings.enableTapToLock && tapCounter >= currentSettings.tapsToLockCount) {
            tapCounter = 0
            executeTapLockAction()
        }
    }

    /**
     * Блокировка экрана: сначала Accessibility (сохраняет отпечаток пальца), затем DeviceAdmin
     */
    private fun executeTapLockAction() {
        // 1. Проверяем службу спец. возможностей (сохраняет биометрию / отпечаток пальца)
        if (MorphixAccessibilityService.lockScreen()) {
            return
        }

        // 2. Проверяем аппаратный Администратор устройства (DeviceAdmin)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, MorphixDeviceAdminReceiver::class.java)
        if (dpm.isAdminActive(adminComponent)) {
            dpm.lockNow()
            return
        }

        // 3. Если разрешение еще не включено, предлагаем активировать в 1 клик
        showLockPermissionDialog(adminComponent)
    }

    private fun showLockPermissionDialog(adminComponent: ComponentName) {
        dismissActivePopups()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Блокировка экрана по тапу")
            .setMessage("Для работы блокировки экрана без отключения сканера отпечатка пальца включите службу «Спец. возможности» для Morphix Launcher.\n\n(На смартфонах Xiaomi: Настройки -> Спец. возможности -> Скачанные приложения -> Morphix Launcher)")
            .setPositiveButton("Включить спец. возможности") { _, _ ->
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (e: Exception) {
                    Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton("Администратор устройства") { _, _ ->
                try {
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                        putExtra(
                            DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                            "Разрешите Morphix выключать экран смартфона по тапу"
                        )
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Отмена", null)
            .create()
        currentDialog = dialog
        dialog.show()
    }

    @SuppressLint("WrongConstant")
    private fun expandNotificationShade() {
        try {
            val statusBarService = getSystemService("statusbar")
            val statusBarManager: Class<*> = Class.forName("android.app.StatusBarManager")
            val expandMethod: Method = statusBarManager.getMethod("expandNotificationsPanel")
            expandMethod.invoke(statusBarService)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupAppDrawer() {
        val columns = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            currentSettings.gridColumns + 2
        } else {
            currentSettings.gridColumns
        }

        binding.appsRecyclerView.layoutManager = GridLayoutManager(this, columns)
        // Без этого каждый бейдж уведомления запускал DefaultItemAnimator
        // с кросс-фейдом и рендером ячейки в offscreen-слой — прямой источник
        // jank на слабом GPU
        binding.appsRecyclerView.itemAnimator = null
        binding.appsRecyclerView.setHasFixedSize(true)
        binding.appsRecyclerView.setItemViewCacheSize(8)

        drawerAppsAdapter = AppsAdapter(
            onAppClick = { item ->
                AppLoader.launchApp(this, item, smoothAnimations = currentSettings.smoothAnimations)
            },
            onAppLongClick = { item, view, _ ->
                showAppQuickActionsPopup(item, view, isFromHomeScreen = false)
            },
            onAppClickWithView = { item, view ->
                AppLoader.launchApp(this, item, view, smoothAnimations = currentSettings.smoothAnimations)
            }
        )
        drawerAppsAdapter.updateConfig(
            shape = currentSettings.iconShape,
            labels = currentSettings.showLabels,
            scale = currentSettings.iconScale,
            smoothAnimations = currentSettings.smoothAnimations
        )
        binding.appsRecyclerView.adapter = drawerAppsAdapter
    }

    private fun setupSpaceSelector() {
        binding.chipGroupSpace.setOnCheckedStateChangeListener { _, checkedIds ->
            val isSecond = checkedIds.contains(binding.chipSecondSpace.id)
            filterAppsBySpace(isSecond)
        }
    }

    private fun filterAppsBySpace(isSecond: Boolean) {
        isSecondSpaceActive = isSecond
        val visibleApps = allApps.filter {
            !currentSettings.hiddenPackages.contains(it.packageName) &&
            (it.isSecondSpace == isSecond || (isSecond && prefsManager.getSecondSpacePackages().contains(it.packageName)))
        }
        drawerAppsAdapter.submitList(visibleApps)
    }

    private fun openAppInfoSmoothly(item: AppItem) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", item.packageName, null)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Безопасное удаление приложений:
     * 1) Распознает системные приложения (MIUI/AOSP) и предлагает скрыть их или открыть системные настройки.
     * 2) 3-уровневый каскад для пользовательских приложений (ACTION_UNINSTALL_PACKAGE -> ACTION_DELETE -> PackageInstaller).
     */
    private fun uninstallAppSafely(item: AppItem) {
        try {
            val appInfo = packageManager.getApplicationInfo(item.packageName, 0)
            val isSystem = (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem) {
                dismissActivePopups()
                val dialog = MaterialAlertDialogBuilder(this)
                    .setTitle("Скрыть приложение")
            .setMessage("Вы уверены, что хотите скрыть это приложение? Оно пропадет с рабочего стола и появится только в Vault.")
            .setPositiveButton("Скрыть") { _, _ ->
                val updatedHidden = currentSettings.hiddenPackages.toMutableSet().apply {
                            add(item.packageName)
                        }
                        currentSettings = currentSettings.copy(hiddenPackages = updatedHidden)
                        prefsManager.saveSettings(currentSettings)
                        loadInstalledApps()
                        Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
                    }
                    .setNeutralButton("Настройки приложения") { _, _ ->
                        openAppInfoSmoothly(item)
                    }
                    .setNegativeButton("Отмена", null)
                    .create()
                currentDialog = dialog
                dialog.show()
                return
            }
        } catch (e: Exception) {
            // Если не удалось прочитать флаги, продолжаем стандартную попытку удаления
        }

        try {
            val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                data = Uri.parse("package:${item.packageName}")
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            startActivity(uninstallIntent)
        } catch (e1: Exception) {
            try {
                val deleteIntent = Intent(Intent.ACTION_DELETE).apply {
                    data = Uri.parse("package:${item.packageName}")
                    putExtra(Intent.EXTRA_RETURN_RESULT, true)
                }
                startActivity(deleteIntent)
            } catch (e2: Exception) {
                try {
                    val packageInstaller = packageManager.packageInstaller
                    val sender = PendingIntent.getBroadcast(
                        this,
                        0,
                        Intent("com.naua_morphix_launcher.ACTION_UNINSTALL_COMPLETE"),
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                    )
                    packageInstaller.uninstall(item.packageName, sender.intentSender)
                } catch (e3: Exception) {
                    Toast.makeText(this, "Не удалось удалить: ${e3.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun hideAppWithSecurity(item: AppItem) {
        val executor: Executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val updatedHidden = currentSettings.hiddenPackages.toMutableSet().apply {
                        add(item.packageName)
                    }
                    currentSettings = currentSettings.copy(hiddenPackages = updatedHidden)
                    prefsManager.saveSettings(currentSettings)
                    loadInstalledApps()
                    Toast.makeText(this@MainActivity, "Приложение «${item.label}» скрыто", Toast.LENGTH_SHORT).show()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    Toast.makeText(this@MainActivity, "Ошибка авторизации: $errString", Toast.LENGTH_SHORT).show()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Защищенное хранилище Morphix")
            .setSubtitle("Подтвердите личность для скрытия ${item.label}")
            .setNegativeButtonText("Отмена")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    /**
 * Глобальный поиск по свайпу вниз по рабочему столу.
     *
     * Открываем уже готовый drawer и сразу ставим фокус в поле поиска: в MIUI
     * свайп вниз открывает именно поиск, а не просто список приложений.
     */
    private fun openGlobalSearch() {
        if (currentSettings.layoutMode != LayoutMode.DRAWER) {
            expandNotificationShade()
            return
        }
        openAppDrawer()
        binding.searchEditText.post {
            binding.searchEditText.requestFocus()
            binding.searchEditText.setSelection(binding.searchEditText.text.length)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.searchEditText, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun setupSearch() {
        binding.searchEditText.addTextChangedListener { editable ->
            val query = editable?.toString()?.trim()?.lowercase(Locale.getDefault()) ?: ""
            filterApps(query)
        }

        binding.searchEditText.setOnFocusChangeListener { view, hasFocus ->
            if (currentSettings.smoothAnimations) {
                view.animate()
                    .scaleX(if (hasFocus) 1.02f else 1.0f)
                    .scaleY(if (hasFocus) 1.02f else 1.0f)
                    .setDuration(180)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                view.scaleX = 1.0f
                view.scaleY = 1.0f
            }
        }

        binding.btnWebSearch.setOnClickListener {
            val query = binding.searchEditText.text.toString().trim()
            if (query.isNotEmpty()) {
                val url = "https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
        attachTactileFeedback(binding.btnWebSearch)
    }

    private fun filterApps(query: String) {
        // читаем prefs один раз, а не на каждый элемент в предикате:
        // filterApps зовётся на каждый символ поиска по 100-300 приложениям
        val hidden = currentSettings.hiddenPackages
        val secondSpacePkgs = prefsManager.getSecondSpacePackages()
        val spaceFiltered = allApps.filter {
            it.packageName !in hidden &&
            (it.isSecondSpace == isSecondSpaceActive || (isSecondSpaceActive && it.packageName in secondSpacePkgs))
        }

        if (query.isEmpty()) {
            drawerAppsAdapter.submitList(spaceFiltered)
            if (binding.btnWebSearch.visibility == View.VISIBLE) {
                if (currentSettings.smoothAnimations) {
                    binding.btnWebSearch.animate()
                        .alpha(0f)
                        .scaleX(0.9f)
                        .scaleY(0.9f)
                        .setDuration(120)
                        .withEndAction {
                            binding.btnWebSearch.visibility = View.GONE
                            binding.btnWebSearch.scaleX = 1f
                            binding.btnWebSearch.scaleY = 1f
                        }
                        .start()
                } else {
                    binding.btnWebSearch.visibility = View.GONE
                }
            }
        } else {
            val filtered = spaceFiltered.filter {
                it.label.lowercase(Locale.getDefault()).contains(query)
            }
            drawerAppsAdapter.submitList(filtered)
            if (filtered.isEmpty()) {
                binding.btnWebSearch.text = "Искать \"$query\" в Google"
                if (binding.btnWebSearch.visibility != View.VISIBLE) {
                    binding.btnWebSearch.visibility = View.VISIBLE
                    if (currentSettings.smoothAnimations) {
                        binding.btnWebSearch.alpha = 0f
                        binding.btnWebSearch.scaleX = 0.9f
                        binding.btnWebSearch.scaleY = 0.9f
                        binding.btnWebSearch.animate()
                            .alpha(1f)
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(160)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.2f))
                            .start()
                    } else {
                        binding.btnWebSearch.alpha = 1f
                        binding.btnWebSearch.scaleX = 1f
                        binding.btnWebSearch.scaleY = 1f
                    }
                }
            } else {
                if (binding.btnWebSearch.visibility == View.VISIBLE) {
                    if (currentSettings.smoothAnimations) {
                        binding.btnWebSearch.animate()
                            .alpha(0f)
                            .scaleX(0.9f)
                            .scaleY(0.9f)
                            .setDuration(120)
                            .withEndAction {
                                binding.btnWebSearch.visibility = View.GONE
                                binding.btnWebSearch.scaleX = 1f
                                binding.btnWebSearch.scaleY = 1f
                            }
                            .start()
                    } else {
                        binding.btnWebSearch.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun autoSeedSecondSpaceIfNeeded() {
        if (prefsManager.getSecondSpacePackages().isEmpty() && allApps.isNotEmpty()) {
            val keywords = listOf(
                "phone", "dialer", "contact", "messag", "mms", "browser", "chrome",
                "camera", "gallery", "photo", "setting", "clock", "calculator", "calendar", "file"
            )
            val seeded = mutableSetOf<String>()
            for (app in allApps) {
                val pkg = app.packageName.lowercase(Locale.getDefault())
                if (keywords.any { pkg.contains(it) }) {
                    seeded.add(app.packageName)
                }
            }
            if (seeded.isEmpty()) {
                seeded.addAll(allApps.take(8).map { it.packageName })
            }
            prefsManager.setSecondSpacePackages(seeded)
        }
    }

    private fun showManageSecondSpaceAppsDialog() {
        dismissActivePopups()
        val availableApps = allApps.filter { !it.isSecondSpace }
        val appLabels = availableApps.map { it.label }.toTypedArray()
        val currentSecondPkgs = prefsManager.getSecondSpacePackages().toMutableSet()
        val checkedItems = BooleanArray(availableApps.size) { i ->
            currentSecondPkgs.contains(availableApps[i].packageName)
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("👥 Приложения во Втором пространстве")
            .setMultiChoiceItems(appLabels, checkedItems) { _, which, isChecked ->
                val pkg = availableApps[which].packageName
                if (isChecked) {
                    currentSecondPkgs.add(pkg)
                } else {
                    currentSecondPkgs.remove(pkg)
                }
            }
            .setPositiveButton("Сохранить") { _, _ ->
                prefsManager.setSecondSpacePackages(currentSecondPkgs)
                loadInstalledApps()
                Toast.makeText(this, "Выполнено", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .create()

        currentDialog = dialog
        dialog.show()
    }

    private var appLoadJob: kotlinx.coroutines.Job? = null

    private fun loadInstalledApps() {
        // предыдущая загрузка ещё шла (1-3 секунды IPC) — отменяем её,
        // иначе результаты приходят в обратном порядке и список «дёргается»
        appLoadJob?.cancel()
        appReloadScheduled = false
        appLoadJob = lifecycleScope.launch {
            allApps = AppLoader.loadApps(applicationContext)
            autoSeedSecondSpaceIfNeeded()
            // док заполняется только после загрузки списка приложений
            seedDockIfEmpty()

            // Проверяем наличие приложений Второго пространства
            val hasSecondSpaceApps = allApps.any { it.isSecondSpace } || prefsManager.getSecondSpacePackages().isNotEmpty()
            binding.chipGroupSpace.visibility = if (hasSecondSpaceApps) View.VISIBLE else View.GONE

            filterAppsBySpace(isSecondSpaceActive)
            updateHomeScreenApps()
            refreshDock()
            notificationListener()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentPopup != null || currentDialog != null) {
                    dismissActivePopups()
                    return
                }
                if (binding.folderFullscreenOverlay.root.visibility == View.VISIBLE) {
                    closeFolderView()
                    return
                }
                if (isEditMode) {
                    exitEditMode()
                    return
                }
                if (binding.glassAppDrawer.visibility == View.VISIBLE) {
                    closeAppDrawer()
                    return
                }
                if (binding.homeViewPager.currentItem != 0) {
                    binding.homeViewPager.setCurrentItem(0, true)
                    return
                }
            }
        })
    }

    private fun getAppCategory(packageName: String): String? {
        return try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                when (appInfo.category) {
                    android.content.pm.ApplicationInfo.CATEGORY_GAME -> "Игры"
                    android.content.pm.ApplicationInfo.CATEGORY_AUDIO -> "Музыка"
                    android.content.pm.ApplicationInfo.CATEGORY_VIDEO -> "Видео"
                    android.content.pm.ApplicationInfo.CATEGORY_IMAGE -> "Фото"
                    android.content.pm.ApplicationInfo.CATEGORY_SOCIAL -> "Соцсети"
                    android.content.pm.ApplicationInfo.CATEGORY_NEWS -> "Новости"
                    android.content.pm.ApplicationInfo.CATEGORY_MAPS -> "Навигация"
                    android.content.pm.ApplicationInfo.CATEGORY_PRODUCTIVITY -> "Инструменты"
                    android.content.pm.ApplicationInfo.CATEGORY_ACCESSIBILITY -> "Спец. возможности"
                    else -> null
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }

    private fun generateDefaultFolderName(targetItem: AppItem, sourceItem: AppItem): String {
        val targetCat = getAppCategory(targetItem.packageName)
        val sourceCat = getAppCategory(sourceItem.packageName)
        if (targetCat != null && (targetCat == sourceCat || sourceCat == null)) {
            return targetCat
        }
        if (sourceCat != null && targetCat == null) {
            return sourceCat
        }
        val existingFolders = prefsManager.getFolders(currentSpace).values.map { it.name }
        if (!existingFolders.contains("Папка")) {
            return "Папка"
        }
        var counter = 1
        while (existingFolders.contains("Папка $counter")) {
            counter++
        }
        return "Папка $counter"
    }

    private fun createFolderDirectly(targetItem: AppItem, sourceItem: AppItem) {
        val folderName = generateDefaultFolderName(targetItem, sourceItem)
        val newFolderId = java.util.UUID.randomUUID().toString()
        val newFolder = com.naua_morphix_launcher.app.model.FolderItem(
            id = newFolderId,
            name = folderName,
            packageNames = mutableListOf(targetItem.packageName, sourceItem.packageName)
        )

        val folders = prefsManager.getFolders(currentSpace).toMutableMap()
        folders[newFolderId] = newFolder
        prefsManager.saveFolder(currentSpace, newFolder)

        val ordered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
        val targetIdx = ordered.indexOf(targetItem.packageName)
        val sourceIdx = ordered.indexOf(sourceItem.packageName)

        if (targetIdx != -1) {
            ordered[targetIdx] = "folder:" + newFolderId
        } else {
            val targetPage = binding.homeViewPager.currentItem
            val spanCount = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                currentSettings.gridColumns + 2
            } else {
                currentSettings.gridColumns
            }
            val itemsPerPage = (spanCount * currentSettings.gridRows).coerceAtLeast(1)
            val pageStart = targetPage * itemsPerPage
            var placed = false
            for (i in 0 until itemsPerPage) {
                val idx = pageStart + i
                if (idx < ordered.size && ordered[idx].isEmpty()) {
                    ordered[idx] = "folder:" + newFolderId
                    placed = true
                    break
                }
            }
            if (!placed) {
                ordered.add("folder:" + newFolderId)
            }
        }
        if (sourceIdx != -1 && sourceIdx < ordered.size) {
            ordered[sourceIdx] = ""
        }

        prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
        updateHomeScreenApps()
        binding.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        Toast.makeText(this, "Создана папка «$folderName»", Toast.LENGTH_SHORT).show()
    }

    private fun resetDragState() {
        dragSourceView?.scaleX = 1.0f
        dragSourceView?.scaleY = 1.0f
        dragSourceView?.alpha = 1.0f
        isDraggingDesktopItem = false
        isDraggingFromDock = false
        isHoveringDock = false
        dragSourceView = null
        dragSourceItemIndex = -1
        dragSourcePageIndex = -1
        dragSourceFolderId = null
        currentFolderPageDragIndex = -1
        isExtractedFromFolder = false
        activeDraggedItem = null
        activeDraggedGroup = emptyList()
        isFolderDropArmed = false
        hasTemporaryDragPage = false
        cancelFolderDwell()
        binding.tvDragBadgeCount.visibility = View.GONE
        binding.widgetDropTargetPreview.visibility = View.GONE
        desktopPagerAdapter.setDraggedItemPackage(null)
        clearCurrentHighlight()
        if (::dockController.isInitialized) setDockDropHighlight(false)
    }

    private fun showCreateFolderDialog(targetItem: com.naua_morphix_launcher.app.model.AppItem, sourceItem: com.naua_morphix_launcher.app.model.AppItem) {
        val dialogView = android.view.LayoutInflater.from(this).inflate(R.layout.dialog_create_folder, null)
        val editText = dialogView.findViewById<android.widget.EditText>(R.id.editTextFolderName)
        
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Создать папку")
            .setView(dialogView)
            .setPositiveButton("Создать") { _, _ ->
                val folderName = editText.text.toString().trim().ifEmpty { "Новая папка" }
                val newFolderId = java.util.UUID.randomUUID().toString()
                val newFolder = com.naua_morphix_launcher.app.model.FolderItem(
                    id = newFolderId,
                    name = folderName,
                    packageNames = mutableListOf(targetItem.packageName, sourceItem.packageName)
                )
                
                val folders = prefsManager.getFolders(currentSpace).toMutableMap()
                folders[newFolderId] = newFolder
                prefsManager.saveFolder(currentSpace, newFolder)
                
                val ordered = prefsManager.getHomeScreenOrderedPackages(currentSpace).toMutableList()
                val targetIdx = ordered.indexOf(targetItem.packageName)
                val sourceIdx = ordered.indexOf(sourceItem.packageName)
                
                if (targetIdx != -1) {
                    ordered[targetIdx] = "folder:" + newFolderId
                }
                if (sourceIdx != -1) {
                    ordered[sourceIdx] = ""
                }
                
                prefsManager.setHomeScreenOrderedPackages(currentSpace, ordered)
                updateHomeScreenApps()
            }
            .setNegativeButton("Отмена", null)
            .create()
            
        currentDialog = dialog
        dialog.show()
    }

}

















