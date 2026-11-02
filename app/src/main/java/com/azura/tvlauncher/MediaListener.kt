package com.azura.tvlauncher

import android.service.notification.NotificationListenerService

/**
 * Exists only so the launcher may read the list of active media sessions (Android ties
 * MediaSessionManager.getActiveSessions to a notification-listener component). It ignores
 * notifications: nothing is read, stored or forwarded.
 */
class MediaListener : NotificationListenerService()
