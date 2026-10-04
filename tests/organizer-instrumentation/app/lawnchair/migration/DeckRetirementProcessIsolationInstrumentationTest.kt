/*
 * Copyright 2026, NunuLauncher
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.lawnchair.migration

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.launcher3.LauncherFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the secondary-process isolation gate for DeckRetirementMigration
 * (AC-009).
 *
 * LawnchairApp.onCreate gates the migration call behind `isDefaultProcess()`.
 * A secondary process such as `:bugReport` must not enter the migration: it
 * neither normalizes the retirement preference store nor deletes recognized
 * historical artifacts.
 *
 * Observable proof: this test plants an exact recognized artifact name in the
 * databases directory AFTER the default process has already started (so the
 * default process will not touch it again), starts the `:bugReport` service so
 * its Application.onCreate runs in the secondary process, and then verifies the
 * planted artifact is still present. If the retirement migration ran in the
 * secondary process, the recognized artifact would have been deleted.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class DeckRetirementProcessIsolationInstrumentationTest {

    @Test
    fun secondaryProcessDoesNotEnterRetirementMigration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES,
        )
        val bugReportService = packageInfo.services?.find { service ->
            service.processName == "${context.packageName}:bugReport"
        }
        assertNotNull(
            ":bugReport process should be declared in the manifest as a service process",
            bugReportService,
        )

        // Plant an exact recognized historical artifact after default-process
        // startup so only a secondary-process migration could remove it.
        val artifactName = "bk_${LauncherFiles.LAUNCHER_DB}"
        val artifactFile = context.getDatabasePath(artifactName)
        artifactFile.parentFile?.mkdirs()
        artifactFile.writeText("deck-retirement-process-isolation-probe")
        try {
            val startOutput = startBugReportProcess(context, bugReportService!!)
            assertTrue(
                "am start-foreground-service must succeed without errors, got: $startOutput",
                !startOutput.contains("Error", ignoreCase = true),
            )
            val processSeen = waitForBugReportProcess(context)
            assertTrue(
                "The :bugReport secondary process must be positively observed running " +
                    "before asserting isolation; without observing it, the artifact " +
                    "assertion proves nothing (AC-009).",
                processSeen,
            )

            assertTrue(
                "Recognized artifact must remain after secondary process start: $artifactName",
                artifactFile.exists(),
            )
        } finally {
            artifactFile.delete()
        }
    }

    @Test
    fun manifestDeclaresBugReportProcess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES,
        )
        val bugReportService = packageInfo.services?.find { service ->
            service.processName == "${context.packageName}:bugReport"
        }
        assertNotNull(
            ":bugReport process should be declared in the manifest as a service process",
            bugReportService,
        )
        assertEquals(
            "Service process name must be the :bugReport secondary process",
            "${context.packageName}:bugReport",
            bugReportService!!.processName,
        )
    }

    /**
     * Starts the declared `:bugReport` service through the instrumentation
     * shell identity. The foreground-service type would reject a direct
     * background startService call from the instrumentation process, while
     * the shell identity starts the component and forces the secondary
     * process (and its Application.onCreate) to be created.
     *
     * Returns the shell command output; callers must treat a reported start
     * error as a failed precondition rather than silently proceeding.
     */
    private fun startBugReportProcess(
        context: Context,
        serviceInfo: android.content.pm.ServiceInfo,
    ): String {
        val component = android.content.ComponentName(context.packageName, serviceInfo.name)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val parcelFileDescriptor = automation.executeShellCommand(
            "am start-foreground-service -n ${component.flattenToString()}",
        )
        return parcelFileDescriptor?.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { input ->
                input.readBytes().decodeToString()
            }
        } ?: ""
    }

    /**
     * Waits bounded until the `:bugReport` process is observed running.
     * Returns true only if the secondary process was positively observed;
     * a timeout returns false so the caller can fail the test (AC-009
     * requires proof under an actually-running secondary process).
     */
    private fun waitForBugReportProcess(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val suffix = ":bugReport"
        val deadline = System.currentTimeMillis() + PROCESS_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val processes = am.runningAppProcesses ?: emptyList()
            if (processes.any { it.processName.endsWith(suffix) }) {
                // Give the secondary Application.onCreate a moment to (not) run
                // its retirement migration before asserting the artifact state.
                Thread.sleep(PROCESS_SETTLE_MS)
                return true
            }
            Thread.sleep(PROCESS_POLL_INTERVAL_MS)
        }
        return false
    }

    private companion object {
        const val PROCESS_WAIT_TIMEOUT_MS = 10_000L
        const val PROCESS_POLL_INTERVAL_MS = 200L
        const val PROCESS_SETTLE_MS = 1_000L
    }
}
