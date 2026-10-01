package com.naua_morphix_launcher.app.views

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

/**
 * Custom ViewOutlineProvider that provides rounded rectangle outlines.
 * Used by LiquidGlassView for proper clipping with shadows.
 */
class ViewOutlineProviderRounded(private val radius: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}
