package com.beardetector.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.beardetector.notification.NotificationHelper

/**
 * Posts a "tap to resume" notification after a reboot if listening was running when the
 * device went down. We deliberately do NOT start the mic service here — Android 14+ blocks
 * background microphone access from a boot-started service, so the user must reopen the app.
 *
 * Also owns the shared SharedPreferences key tracking whether listening was active,
 * written by [ListenService] and cleared by ListenScreen on explicit Stop.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val PREFS_NAME = "bear_detector_prefs"
        private const val KEY_LISTEN_WAS_RUNNING = "listen_was_running"

        fun setListenWasRunning(context: Context, running: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_LISTEN_WAS_RUNNING, running)
                .apply()
        }

        private fun wasListenRunning(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_LISTEN_WAS_RUNNING, false)
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            if (wasListenRunning(context)) {
                NotificationHelper.createChannel(context)
                NotificationHelper.showResumePrompt(context)
            }
        }
    }
}
