package app.lawnchair.bugreport

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import app.lawnchair.LawnchairApp
import app.lawnchair.util.MainThreadInitializedObject
import app.lawnchair.util.requireSystemService
import com.android.launcher3.BuildConfig
import com.android.launcher3.R
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LawnchairBugReporter(private val context: Context) {

    private val notificationManager: NotificationManager = context.requireSystemService()
    private val logsFolder by lazy { File(context.cacheDir, "logs").apply { mkdirs() } }
    private val appName by lazy { context.getString(R.string.derived_app_name) }

    init {
        notificationManager.createNotificationChannel(
            NotificationChannel(
                BugReportReceiver.NOTIFICATION_CHANNEL_ID,
                context.getString(R.string.bugreport_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                BugReportReceiver.STATUS_CHANNEL_ID,
                context.getString(R.string.status_channel_name),
                NotificationManager.IMPORTANCE_NONE,
            ),
        )

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            dispatchUncaughtException(
                defaultHandler,
                thread,
                throwable,
                preHandler = { sendNotification(throwable) },
                onPreHandlerFailure = { failure ->
                    Log.w(TAG, "Uncaught exception pre-handler error", failure)
                },
            )
        }

        removeDismissedLogs()
    }

    private fun removeDismissedLogs() {
        val activeIds = notificationManager.activeNotifications
            .mapTo(mutableSetOf()) { String.format("%x", it.id) }
        logsFolder.listFiles().orEmpty()
            .asSequence()
            .filter { it.name !in activeIds }
            .forEach { it.deleteRecursively() }
    }

    private fun sendNotification(throwable: Throwable) {
        val bugReport = Report(BugReport.TYPE_UNCAUGHT_EXCEPTION, throwable)
            .generateBugReport() ?: return

        val notifications = notificationManager.activeNotifications
        val hasNotification = notifications.any { it.id == bugReport.id }
        if (hasNotification || notifications.size > 3) {
            return
        }
        BugReportReceiver.notify(context, bugReport)
    }

    inner class Report(val error: String, val throwable: Throwable? = null) {

        private val fileName = buildReportFileName(appName, Date())

        fun generateBugReport(): BugReport? {
            val contents = writeContents()
            val contentsWithHeader = "$fileName\n$contents"
            val id = contents.hashCode()
            val reportFile = save(contentsWithHeader, id)

            return BugReport(id, error, getDescription(throwable ?: return null), contentsWithHeader, reportFile)
        }

        private fun getDescription(throwable: Throwable): String {
            return "${throwable::class.java.name}: ${throwable.message}"
        }

        private fun save(contents: String, id: Int): File? {
            val dest = File(logsFolder, String.format("%x", id))
            return writeReportFile(dest, fileName, contents)
        }

        private fun writeContents() = StringBuilder()
            .appendLine("version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            .appendLine("commit: ${BuildConfig.COMMIT_HASH}")
            .appendLine("build.brand: ${Build.BRAND}")
            .appendLine("build.device: ${Build.DEVICE}")
            .appendLine("build.display: ${Build.DISPLAY}")
            .appendLine("build.fingerprint: ${Build.FINGERPRINT}")
            .appendLine("build.hardware: ${Build.HARDWARE}")
            .appendLine("build.id: ${Build.ID}")
            .appendLine("build.manufacturer: ${Build.MANUFACTURER}")
            .appendLine("build.model: ${Build.MODEL}")
            .appendLine("build.security.level: ${Build.VERSION.SECURITY_PATCH}")
            .appendLine("build.product: ${Build.PRODUCT}")
            .appendLine("build.type: ${Build.TYPE}")
            .appendLine("version.codename: ${Build.VERSION.CODENAME}")
            .appendLine("version.incremental: ${Build.VERSION.INCREMENTAL}")
            .appendLine("version.release: ${Build.VERSION.RELEASE}")
            .appendLine("version.sdk_int: ${Build.VERSION.SDK_INT}")
            .appendLine("display.density_dpi: ${context.resources.displayMetrics.densityDpi}")
            .appendLine("isRecentsEnabled: ${LawnchairApp.isRecentsEnabled}")
            .appendLine()
            .appendLine("error: $error")
            .also {
                if (throwable != null) {
                    it
                        .appendLine()
                        .appendLine(Log.getStackTraceString(throwable))
                }
            }
            .toString()
    }

    companion object {
        val INSTANCE = MainThreadInitializedObject(::LawnchairBugReporter)

        private const val TAG = "LawnchairBugReporter"
    }
}

/**
 * Locale-independent, filesystem-safe report file name. The current-locale
 * date-time instance can emit path separators (e.g. "2026/09/07" in ja), which
 * makes File.createNewFile() throw; Issue #242.
 */
internal fun buildReportFileName(appName: String, date: Date): String = "$appName bug report ${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(date)}"

/**
 * Writes the report file under [dest] using the same [fileName] that headed the
 * report contents. Returns null on any IOException (e.g. unwritable dest) or on
 * createNewFile() == false (same id collision), degrading to the file-less
 * notification path instead of throwing from the crash pre-handler.
 */
internal fun writeReportFile(dest: File, fileName: String, contents: String): File? = try {
    dest.mkdirs()
    val file = File(dest, "$fileName.txt")
    if (file.createNewFile()) {
        file.writeText(contents)
        file
    } else {
        null
    }
} catch (ignored: IOException) {
    null
}

/**
 * Runs the crash [preHandler] work in isolation and guarantees exactly one
 * delegation of the original [throwable] to [defaultHandler]: preHandler throws
 * are handed to [onPreHandlerFailure], and a throw from the failure logger
 * itself is swallowed (recording is best effort) so delegation still wins.
 */
internal fun dispatchUncaughtException(
    defaultHandler: Thread.UncaughtExceptionHandler?,
    thread: Thread,
    throwable: Throwable,
    preHandler: () -> Unit,
    onPreHandlerFailure: (Throwable) -> Unit = {},
) {
    try {
        try {
            preHandler()
        } catch (t: Throwable) {
            onPreHandlerFailure(t)
        }
    } catch (ignored: Throwable) {
        // Nothing safe can record a failure of the failure logger; delegation wins.
    } finally {
        defaultHandler?.uncaughtException(thread, throwable)
    }
}
