package com.spotify.adskipper

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Background NotificationListenerService that monitors **only** Spotify notifications
 * and triggers the ad skip sequence when advertisements are detected.
 *
 * STRICT SECURITY / PRIVACY CONSTRAINTS (enforced in code):
 * - Only notifications whose packageName == SPOTIFY_PACKAGE are ever processed.
 * - All other notifications are immediately rejected and ignored (no reading of title/text/extras).
 * - No access to contacts, SMS/MMS content, call logs, or phone state.
 * - No answering or rejecting of phone calls.
 * - No interaction with Do Not Disturb (DND), interruption filter, or notification policy.
 * - No cancellation, snoozing, or modification of any notification (including Spotify's).
 * - No reading of notification content beyond the minimal fields needed to detect the
 *   single keyword "Advertisement" inside a Spotify notification.
 */
class SpotifyAdListener : NotificationListenerService() {

    private companion object {
        const val TAG = "SpotifyAdListener"
        /** The only package this service is allowed to inspect. */
        const val SPOTIFY_PACKAGE = "com.spotify.music"
        const val AD_TITLE_KEYWORD = "Advertisement"
    }

    // Coroutine scope with SupervisorJob to prevent child failures from cancelling the scope
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Flag to prevent concurrent ad skip sequences
    @Volatile
    private var isAdSkipInProgress = false

    /**
     * Called when any notification is posted.
     * Immediately rejects every notification that does not belong to Spotify.
     * For non-Spotify notifications we do not read title, text, extras, or any other data.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // Null-safety + hard package filter – reject everything else before any further work
        if (sbn == null) return
        if (sbn.packageName != SPOTIFY_PACKAGE) {
            // Explicit rejection of every other app's notifications.
            // We deliberately do NOT log content or even the package name of foreign notifications
            // to avoid any accidental data capture.
            return
        }

        // From this point on we are guaranteed to be looking only at Spotify
        val notification = sbn.notification ?: return

        // Minimal debug logging (only for the allowed package)
        val title = notification.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = notification.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val ticker = notification.tickerText?.toString()
        Log.d(TAG, "Spotify notification - Title: '$title', Text: '$text', Ticker: '$ticker'")

        // Check if this is an advertisement
        if (isAdvertisement(notification)) {
            // Prevent concurrent ad skip sequences
            if (isAdSkipInProgress) {
                Log.d(TAG, "Ad skip already in progress, skipping duplicate")
                return
            }

            Log.d(TAG, "Advertisement detected, executing skip sequence")

            // Execute ad skip sequence asynchronously (DO NOT block main thread)
            scope.launch {
                executeAdSkipSequence()
            }
        }
    }

    /**
     * Called when a notification is removed.
     * We still apply the same hard package filter and do nothing for foreign notifications.
     * We never cancel, snooze, or otherwise interact with notifications.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Hard rejection of every notification that is not from the allowed package.
        // No further action is taken even for Spotify removals.
        if (sbn == null || sbn.packageName != SPOTIFY_PACKAGE) return
        // Intentionally empty – we do not react to removal events.
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected – restricted to package: $SPOTIFY_PACKAGE")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "Notification listener disconnected")
    }

    /**
     * Checks if a Spotify notification is an advertisement.
     * Only called after the package filter has already guaranteed this is a Spotify notification.
     *
     * Checks multiple fields solely for the keyword "Advertisement":
     * - Ticker text (most reliable indicator)
     * - Title field
     * - Text/description field
     * - Sub-text field
     * - Info text field
     *
     * No other content is stored or transmitted.
     */
    private fun isAdvertisement(notification: Notification): Boolean {
        val extras = notification.extras ?: return false

        // Check ticker text (most reliable)
        val ticker = notification.tickerText?.toString()
        if (ticker?.contains(AD_TITLE_KEYWORD, ignoreCase = true) == true) {
            return true
        }

        // Check title
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (title?.contains(AD_TITLE_KEYWORD, ignoreCase = true) == true) {
            return true
        }

        // Check text/description
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        if (text?.contains(AD_TITLE_KEYWORD, ignoreCase = true) == true) {
            return true
        }

        // Check sub-text
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        if (subText?.contains(AD_TITLE_KEYWORD, ignoreCase = true) == true) {
            return true
        }

        // Check info text
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
        if (infoText?.contains(AD_TITLE_KEYWORD, ignoreCase = true) == true) {
            return true
        }

        return false
    }

    /**
     * Executes the ad skip sequence asynchronously.
     * This is the only privileged action the service ever performs, and it is
     * strictly limited to the Spotify package.
     */
    private suspend fun executeAdSkipSequence() {
        isAdSkipInProgress = true

        try {
            // Step 1: Wait for Spotify to update queue state after ad starts
            Log.d(TAG, "Waiting for Spotify to update queue state...")
            delay(2500)

            // Step 2: Send Spotify to background (triggers onPause)
            Log.d(TAG, "Sending Spotify to background...")
            val backgroundIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
                addCategory(android.content.Intent.CATEGORY_HOME)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(backgroundIntent)

            // Step 3: Wait for lifecycle callbacks (onPause → onStop)
            delay(1000)

            // Step 4: Finish and remove task (emulates swipe to close)
            when (val result = ShizukuController.finishAndRemoveSpotifyTask(applicationContext)) {
                is Result.Error -> {
                    Log.e(TAG, "Finish and remove task failed", result.exception)
                    return
                }
                is Result.Success -> Log.d(TAG, "Spotify task finished and removed")
            }

            // Step 5: Wait for process termination
            delay(1000)

            // Step 6: Relaunch Spotify
            when (val result = SpotifyController.relaunchSpotify(applicationContext)) {
                is Result.Error -> {
                    Log.e(TAG, "Relaunch failed", result.exception)
                    return
                }
                is Result.Success -> Log.d(TAG, "Spotify relaunched")
            }

            // Step 7: Wait for Spotify to initialize
            delay(3000)

            // Step 8: Send play intent
            Log.d(TAG, "Sending play intent...")
            when (val result = SpotifyController.play(applicationContext)) {
                is Result.Error -> Log.e(TAG, "Play intent failed", result.exception)
                is Result.Success -> Log.d(TAG, "Play intent sent")
            }

            Log.d(TAG, "Ad skip sequence complete")
        } finally {
            isAdSkipInProgress = false
            Log.d(TAG, "Ad skip flag reset, ready for next ad")
        }
    }

    override fun onDestroy() {
        // Cancel all pending coroutines to prevent resource leaks
        scope.cancel()
        super.onDestroy()
        Log.d(TAG, "Service destroyed, coroutines cancelled")
    }

    // -------------------------------------------------------------------------
    // Explicitly disabled / never-used capabilities (defensive documentation)
    // -------------------------------------------------------------------------
    // The following NotificationListenerService capabilities are intentionally
    // NOT implemented and must never be added:
    //
    // - cancelNotification / cancelAllNotifications
    // - snoozeNotification
    // - setNotificationsShown
    // - requestInterruptionFilter / requestListenerHints  (DND / interruption filter)
    // - getActiveNotifications beyond the package filter
    // - any interaction with TelephonyManager, ContactsContract, SmsManager, etc.
    //
    // Any future change that adds the above would violate the privacy contract
    // of this application.
}