package com.naua_morphix_launcher.app.service

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MorphixNotificationListenerService : NotificationListenerService() {

    companion object {
        private val notificationCounts = mutableMapOf<String, Int>()
        private val listeners = mutableListOf<() -> Unit>()

        fun getBadgeCount(packageName: String): Int {
            return notificationCounts[packageName] ?: 0
        }

        fun getAllBadgeCounts(): Map<String, Int> {
            return HashMap(notificationCounts)
        }

        fun registerListener(listener: () -> Unit) {
            if (!listeners.contains(listener)) {
                listeners.add(listener)
            }
        }

        fun unregisterListener(listener: () -> Unit) {
            listeners.remove(listener)
        }

        fun isNotificationAccessGranted(context: Context): Boolean {
            val component = ComponentName(context, MorphixNotificationListenerService::class.java)
            val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
            return flat != null && flat.contains(component.flattenToString())
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        refreshNotifications()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        refreshNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        refreshNotifications()
    }

    private fun refreshNotifications() {
        try {
            val activeNotifications = activeNotifications ?: return
            val counts = mutableMapOf<String, Int>()
            for (sbn in activeNotifications) {
                if (!sbn.isOngoing && sbn.packageName != null) {
                    val count = counts.getOrDefault(sbn.packageName, 0) + 1
                    counts[sbn.packageName] = count
                }
            }
            synchronized(notificationCounts) {
                notificationCounts.clear()
                notificationCounts.putAll(counts)
            }
            for (listener in listeners) {
                listener()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
