package com.museroom.app.listener

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.museroom.app.media.NowPlayingRepository
import com.museroom.app.tracking.PlaybackTracker

/**
 * Enabling this component in Settings is what unlocks reading media sessions.
 *
 * It deliberately does almost nothing else. It never inspects notification
 * content, and it filters to the music allowlist before reacting, which is both
 * the privacy promise and the line the Play Store declaration has to defend.
 */
class MediaListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        NowPlayingRepository.start(this)
        PlaybackTracker.start(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NowPlayingRepository.stop()
    }

    /**
     * A posted notification is only ever a hint that sessions may have moved. We
     * never read its content; the media session is the source of truth, and the
     * source registry decides which players count.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        NowPlayingRepository.resync()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        NowPlayingRepository.refresh()
    }
}
