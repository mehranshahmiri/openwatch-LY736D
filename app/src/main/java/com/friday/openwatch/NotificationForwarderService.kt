package com.friday.openwatch

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationForwarderService : NotificationListenerService() {

    private val tag = "NotifForwarder"

    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLog.log(this, tag, "listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        AppLog.log(this, tag, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return // don't forward our own status notification

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()

        val combined = buildString {
            if (!title.isNullOrBlank()) append(title).append(": ")
            if (!text.isNullOrBlank()) append(text)
        }.trim()

        if (combined.isEmpty()) return

        val icon = Protocol.iconForPackage(pkg)
        AppLog.log(this, tag, "forwarding from $pkg: '$combined' icon=$icon")
        WatchBleService.pushNotification(this, combined, icon)
    }
}
