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
package app.lawnchair.organizer

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.organizer.integration.AndroidSystemUsageSignalReader
import app.lawnchair.organizer.integration.UsageAccess
import app.lawnchair.organizer.personalization.SystemUsageSection
import app.lawnchair.organizer.personalization.UsageAccessState
import app.lawnchair.organizer.personalization.UsageSignalRequest
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.ProfileId
import com.android.launcher3.pm.UserCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #203: instrumentation evidence for the app-op-based Usage Access
 * predicate (2026-09-15 re-review Blocking 1). Drives the **production**
 * reader through a grant/revoke transition by toggling the
 * `GET_USAGE_STATS` app-op via the instrumentation's shell access, proving
 * `AndroidSystemUsageSignalReader` follows the app-op state (and not just the
 * manifest permission).
 */
@RunWith(AndroidJUnit4::class)
class UsageAccessTransitionProbeTest {

    private fun setUsageAccessOp(mode: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val process = instrumentation.uiAutomation.executeShellCommand(
            "appops set ${context().packageName} GET_USAGE_STATS $mode",
        )
        process.close()
        // The app-op change is asynchronous from the app's point of view.
        Thread.sleep(1000)
    }

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun readNow(): app.lawnchair.organizer.integration.AndroidSystemUsageSignalReader.SystemUsageRead {
        val context = context()
        val userCache = UserCache.INSTANCE.get(context)
        val serial = userCache.getSerialNumberForUser(Process.myUserHandle())
        val profile = ProfileId(serial.toString())
        val request = UsageSignalRequest(
            profiles = setOf(profile),
            launchablePackages = mapOf(profile to setOf(PackageName("com.android.settings"))),
        )
        return AndroidSystemUsageSignalReader(context).read(request, System.currentTimeMillis())
    }

    @Test
    fun productionReaderFollowsTheUsageAccessAppOp() {
        // Sanity: the usage stats manager is reachable once granted.
        setUsageAccessOp("allow")
        val granted = readNow()
        assertEquals(UsageAccessState.GRANTED, granted.usageAccess)
        assertTrue(granted.systemUsage is SystemUsageSection.Available)
        assertTrue(UsageAccess.isGranted(context()))

        // Revoke: the production reader must degrade to NOT_GRANTED without
        // throwing, with the system usage section structurally absent.
        setUsageAccessOp("deny")
        val revoked = readNow()
        assertEquals(UsageAccessState.NOT_GRANTED, revoked.usageAccess)
        assertTrue(revoked.systemUsage is SystemUsageSection.Unavailable)
        assertTrue(!UsageAccess.isGranted(context()))

        // Re-grant restores the GRANTED path (the transition, not the data,
        // is the evidence).
        setUsageAccessOp("allow")
        val reGranted = readNow()
        assertEquals(UsageAccessState.GRANTED, reGranted.usageAccess)
        assertTrue(reGranted.systemUsage is SystemUsageSection.Available)
    }
}
