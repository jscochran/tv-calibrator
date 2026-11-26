package tvcal.android.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import tvcal.android.MainActivity
import tvcal.android.R
import java.time.Duration

/** What the guided flow needs from notifications (a fake in JVM tests). */
interface Reminders {
    /** After a finished calibration: remind in six months to re-check this screen (replacing any earlier one). */
    fun scheduleRecheck(tvID: String, tvName: String)

    /** A screen was deleted. */
    fun cancelRecheck(tvID: String)
}

/**
 * Opt-in re-check reminders (Swift Notifications). Video pushes (APNs) are dropped on Android:
 * [pushConfigured] is false, so Settings never shows the "New Slidebean videos" toggle.
 * Reminder: WorkManager OneTimeWorkRequest<RecheckWorker>, delay [recheckAfter], unique work
 * "recheck-<tvID>" (REPLACE), tag [RECHECK_TAG]; the worker posts on channel [CHANNEL_ID].
 * tvID strings are UUID uppercase (TVRecord.id.upper).
 *
 * Status follows iOS: NOT_DETERMINED until the user has answered the "Turn on notifications" button once
 * ("notify.asked"), even below API 33 where there is no runtime permission. So, as on the iPhone, the app
 * asks only after a finished calibration (or from Settings) and never schedules a reminder nobody opted in to.
 */
class Notifications(context: Context, private val prefs: Prefs) : Reminders {
    companion object {
        /** No push server on Android. */
        const val pushConfigured = false
        const val videosKey = PrefKeys.NOTIFY_VIDEOS
        const val recheckKey = PrefKeys.NOTIFY_RECHECK
        const val cardDismissedKey = PrefKeys.NOTIFY_CARD_DISMISSED
        /** Six months. */
        val recheckAfter: Duration = Duration.ofDays(182)
        /**
         * IMPORTANCE_HIGH, so the reminder pops up as a heads-up banner with sound, like the iPhone's
         * willPresent [.banner, .sound]. A new id because a channel's importance cannot be raised once created.
         */
        const val CHANNEL_ID = "recheck.banner"
        /** The first build's IMPORTANCE_DEFAULT channel (shade only); removed by [ensureChannel]. */
        private const val OLD_CHANNEL_ID = "recheck"
        const val RECHECK_TAG = "recheck"

        fun workName(tvID: String): String = "recheck-$tvID"

        /** The reminder channel (needed before posting). Safe to call repeatedly. */
        internal fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(OLD_CHANNEL_ID) != null) nm.deleteNotificationChannel(OLD_CHANNEL_ID)
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Re-check reminders", NotificationManager.IMPORTANCE_HIGH),
            )
        }

        /** The system lets this app post: POST_NOTIFICATIONS granted (API 33+) and not blocked in Settings. */
        internal fun allowed(context: Context): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled() && permissionGranted(context)

        private fun permissionGranted(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
    }

    /** UNAuthorizationStatus. */
    enum class Status { NOT_DETERMINED, AUTHORIZED, DENIED }

    private val context: Context = context.applicationContext

    var status: Status by mutableStateOf(Status.NOT_DETERMINED)
        private set

    private var videosState by mutableStateOf(prefs.bool(videosKey, true))
    private var recheckState by mutableStateOf(prefs.bool(recheckKey, true))

    /** Observable; persisted under "notify.videos" (kept for parity; no effect on Android). */
    var videos: Boolean
        get() = videosState
        set(value) {
            videosState = value
            prefs.setBool(videosKey, value)
            // The Swift app then syncs its push registration; there are no pushes on Android.
        }

    /** Observable; persisted under "notify.recheck"; turning it off cancels every pending reminder. */
    var recheck: Boolean
        get() = recheckState
        set(value) {
            recheckState = value
            prefs.setBool(recheckKey, value)
            if (!value) cancelAllRechecks()
        }

    /** At launch: create the notification channel and read the permission. */
    fun start() {
        ensureChannel(context)
        refreshStatus()
    }

    /**
     * AUTHORIZED when notifications are allowed (POST_NOTIFICATIONS granted on API 33+, not blocked in
     * Settings) and the user turned them on here once; DENIED when not allowed after we asked
     * ("notify.asked"), or blocked in Settings with nothing left to ask; else NOT_DETERMINED.
     */
    fun refreshStatus() {
        val asked = prefs.bool(PrefKeys.NOTIFY_ASKED, false)
        val allowed = allowed(context)
        status = when {
            asked && allowed -> Status.AUTHORIZED
            asked -> Status.DENIED
            // Not asked yet: the button can still turn them on (a prompt on API 33+, or nothing to ask).
            allowed || permissionToRequest() != null -> Status.NOT_DETERMINED
            else -> Status.DENIED
        }
    }

    /**
     * The permission the UI must request before [turnOn] (Manifest.permission.POST_NOTIFICATIONS on API 33+
     * when not granted), or null when none is needed. The UI launches it with
     * ActivityResultContracts.RequestPermission and then calls turnOn(granted).
     */
    fun permissionToRequest(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !permissionGranted(context)) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }

    /**
     * The "Turn on notifications" button, after the permission answer: marks "notify.asked", refreshes the
     * status and, when granted, turns [recheck] (and [videos]) on. Returns whether notifications are allowed.
     */
    fun turnOn(granted: Boolean): Boolean {
        prefs.setBool(PrefKeys.NOTIFY_ASKED, true)
        refreshStatus()
        if (!granted || status != Status.AUTHORIZED) return false
        videos = true
        recheck = true
        return true
    }

    // Re-check reminders

    override fun scheduleRecheck(tvID: String, tvName: String) {
        if (!recheck) return
        refreshStatus()
        if (status != Status.AUTHORIZED) return
        val request = OneTimeWorkRequestBuilder<RecheckWorker>()
            .setInitialDelay(recheckAfter)
            .setInputData(workDataOf(RecheckWorker.KEY_TV_ID to tvID, RecheckWorker.KEY_TV_NAME to tvName))
            .addTag(RECHECK_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(tvID), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancelRecheck(tvID: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(tvID))
    }

    private fun cancelAllRechecks() {
        WorkManager.getInstance(context).cancelAllWorkByTag(RECHECK_TAG)
    }

    /** Settings.ACTION_APP_NOTIFICATION_SETTINGS for this app ("Open Settings" when denied). */
    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Posts "Time to re-check <name>" / "TV pictures drift as the panel ages. A quick check takes a few
 * minutes." Tapping it opens the app (MainActivity).
 */
class RecheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_TV_ID = "tvID"
        const val KEY_TV_NAME = "tvName"
    }

    @SuppressLint("MissingPermission")    // allowed() checks the permission just before
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val tvID = inputData.getString(KEY_TV_ID) ?: return Result.success()
        val tvName = inputData.getString(KEY_TV_NAME) ?: ""
        // No longer allowed since it was scheduled: nothing to post.
        if (!Notifications.allowed(ctx)) return Result.success()
        Notifications.ensureChannel(ctx)
        // A launcher-style intent: when the app is still in memory this only brings its task to the front,
        // as it was (the screen the user had open stays); otherwise it starts the app as from the launcher.
        // (FLAG_ACTIVITY_CLEAR_TOP on the standard-launchMode MainActivity would recreate it at Home.)
        val open = Intent(ctx, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val tap = PendingIntent.getActivity(
            ctx, tvID.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val body = "TV pictures drift as the panel ages. A quick check takes a few minutes."
        val n = NotificationCompat.Builder(ctx, Notifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_recheck)
            .setContentTitle("Time to re-check $tvName")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_SOUND)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(ctx).notify(Notifications.workName(tvID), 0, n)
        } catch (e: SecurityException) {
            // The permission was revoked between the check and posting.
        }
        return Result.success()
    }
}
