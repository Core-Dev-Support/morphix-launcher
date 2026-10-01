package com.naua_morphix_launcher.app.service

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

class MorphixNotificationListenerService : NotificationListenerService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable { recomputeCounts() }

    companion object {
        // ConcurrentHashMap: пишет служебный поток, читает UI — synchronized
        // на записи не защищал чтение
        private val notificationCounts = ConcurrentHashMap<String, Int>()

        // WeakReference: сервис живёт дольше Activity, и пропущенный
        // unregisterListener держал бы Activity до смерти процесса
        private val listeners = mutableListOf<WeakReference<() -> Unit>>()

        /** Склейка пачки уведомлений: активная пачка из 20 штук
         *  раньше вызывала 20 полных перестроений списка. */
        private const val REFRESH_DEBOUNCE_MS = 250L

        fun getBadgeCount(packageName: String): Int {
            return notificationCounts[packageName] ?: 0
        }

        fun getAllBadgeCounts(): Map<String, Int> {
            return HashMap(notificationCounts)
        }

        @Synchronized
        fun registerListener(listener: () -> Unit) {
            listeners.removeAll { it.get() == null || it.get() == listener }
            listeners.add(WeakReference(listener))
        }

        @Synchronized
        fun unregisterListener(listener: () -> Unit) {
            listeners.removeAll { it.get() == null || it.get() == listener }
        }

        private fun snapshotListeners(): List<() -> Unit> =
            synchronized(this) { listeners.mapNotNull { it.get() } }

        fun isNotificationAccessGranted(context: Context): Boolean {
            val component = ComponentName(context, MorphixNotificationListenerService::class.java)
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?: return false
            val target = component.flattenToString()
            // точное сравнение по элементам списка, а не contains по подстроке
            return flat.split(':').any { it == target || it == target.substringBefore('/') }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.post(refreshRunnable)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        // иначе на иконках навсегда остаются бейджи отключённых уведомлений
        mainHandler.removeCallbacks(refreshRunnable)
        notificationCounts.clear()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        scheduleRefresh()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        scheduleRefresh()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(refreshRunnable)
        notificationCounts.clear()
        synchronized(this) { listeners.clear() }
        super.onDestroy()
    }

    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(refreshRunnable)
        mainHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS)
    }

    private fun recomputeCounts() {
        // getActiveNotifications() — синхронный binder-вызов в system_server,
        // возвращающий пересобранные SBN для всех уведомлений
        val active = try {
            activeNotifications ?: return
        } catch (e: Exception) {
            e.printStackTrace()
            return
        }

        val counts = HashMap<String, Int>()
        for (sbn in active) {
            if (!sbn.isOngoing) {
                val pkg = sbn.packageName ?: continue
                counts[pkg] = (counts[pkg] ?: 0) + 1
            }
        }

        if (counts == notificationCounts) return

        notificationCounts.clear()
        notificationCounts.putAll(counts)

        for (listener in snapshotListeners()) {
            // runCatching: одно исключение из слушателя раньше прерывало
            // обход остальных
            runCatching { listener() }
        }
    }
}