package com.naua_morphix_launcher.app.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import java.lang.ref.WeakReference

class MorphixAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // События доступности не обрабатываются: служба нужна только ради
        // performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN).
        // Подписка намеренно сужена до typeWindowStateChanged в
        // res/xml/accessibility_service_config.xml — без eventTypes система
        // считала бы подписку typeAllMask и маршалила в лаунчер каждое
        // событие из каждого приложения впустую.
    }

    override fun onInterrupt() {
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instanceRef = WeakReference(this)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instanceRef = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instanceRef = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instanceRef: WeakReference<MorphixAccessibilityService>? = null

        /**
         * Аппаратная блокировка экрана через AccessibilityService
         * (не сбрасывает разблокировку по отпечатку пальца).
         */
        fun lockScreen(): Boolean {
            val service = instanceRef?.get() ?: return false
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            // try/catch: сервис может умереть между чтением instance и вызовом
            return try {
                service.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
            } catch (e: Exception) {
                false
            }
        }

        fun isRunning(): Boolean = instanceRef?.get() != null
    }
}