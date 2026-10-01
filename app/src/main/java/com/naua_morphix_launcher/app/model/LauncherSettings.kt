package com.naua_morphix_launcher.app.model

enum class IconShape {
    SQUIRCLE,
    CIRCLE,
    ROUNDED_SQUARE,
    TEARDROP,
    ORIGINAL
}

enum class LayoutMode {
    DRAWER,   // Домашний экран + меню всех приложений свайпом
    CLASSIC   // Классический режим в стиле iOS/MIUI (все приложения на рабочих столах)
}

enum class DockStyle {
    FLOATING,   // Парящая стеклянная капсула
    FULL_WIDTH  // Прижатая монолитная стеклянная панель
}

enum class LockType {
    NONE,
    PIN,
    PATTERN,
    PASSWORD,
    BIOMETRIC,
    BIOMETRIC_OR_PIN
}

data class LauncherSettings(
    // Жидкое стекло (Glassmorphism)
    val isGlassEnabled: Boolean = true,
    val blurRadius: Float = 12f,
    val glassAlpha: Float = 0.22f,

    // Иконки и форма
    val iconShape: IconShape = IconShape.SQUIRCLE,
    val iconScale: Float = 1.0f,
    val showLabels: Boolean = true,

    // Режим лаунчера и сетка
    val layoutMode: LayoutMode = LayoutMode.DRAWER,
    val gridColumns: Int = 5,
    val gridRows: Int = 9,

    // Док
    val isDockEnabled: Boolean = true,
    val dockStyle: DockStyle = DockStyle.FLOATING,
    val dockIconCount: Int = 5,

    // Поиск
    val showSearchOnHome: Boolean = true,
    val showSearchInDrawer: Boolean = true,

    // Жесты
    val swipeDownToNotifications: Boolean = true,
    val enableTapToLock: Boolean = true,
    val tapsToLockCount: Int = 2, // 2 или 3 тапа

    // Виджет часов
    val showClockWidget: Boolean = true,

    // Скрытые приложения и безопасность
    val lockType: LockType = LockType.BIOMETRIC_OR_PIN,
    val hiddenPackages: Set<String> = emptySet(),
    
    // Анимации
    val smoothAnimations: Boolean = true
)

