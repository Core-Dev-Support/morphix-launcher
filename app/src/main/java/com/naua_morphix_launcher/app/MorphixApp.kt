package com.naua_morphix_launcher.app

import android.app.Application
import com.naua_morphix_launcher.app.util.AppLoader

class MorphixApp : Application() {

    /**
     * DynamicColors.applyToActivitiesIfAvailable() НЕ вызывается намеренно.
     *
     * Палитра Material You подставляет непрозрачный android:colorBackground /
     * windowBackground в тему Activity. Для домашнего экрана это ломает главное:
     * окно становится непрозрачным, WindowManagerService перестаёт подкладывать
     * системные обои, и пользователь видит чёрный фон вместо обоев.
     * Своя тема лаунчера (Theme.Morphix.Transparent) задаёт прозрачность явно.
     */
    override fun onCreate() {
        super.onCreate()
    }

    /**
     * Кэш иконок приложений держит до 8 МБ растеризованных Drawable.
     * На устройствах с 4 ГБ ОЗУ систему лучше предупредить заранее,
     * чем получать OutOfMemoryError на рабочем столе.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLoader.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppLoader.onTrimMemory(TRIM_MEMORY_COMPLETE)
    }
}