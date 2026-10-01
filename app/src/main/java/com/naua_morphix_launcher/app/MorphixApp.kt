package com.naua_morphix_launcher.app

import android.app.Application
import com.google.android.material.color.DynamicColors

class MorphixApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Включение адаптивных системных цветов Material You (из обоев)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
