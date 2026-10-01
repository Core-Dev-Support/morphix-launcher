package com.naua_morphix_launcher.app

import android.app.Application
import com.google.android.material.color.DynamicColors
import com.naua_morphix_launcher.app.util.AppLoader

class MorphixApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Включение адаптивных системных цветов Material You (из обоев)
        DynamicColors.applyToActivitiesIfAvailable(this)
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