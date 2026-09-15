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

import android.app.usage.UsageStatsManager
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #203 / spec U-5 probe: records the real `UsageStatsManager` behavior
 * the accepted contract's environment assumptions rest on (probe completion
 * conditions (a)–(c) of the merge gate). It is evidence-gathering, not a
 * behavioral assertion suite: the observations are logged so the probe
 * results can be recorded verbatim in `docs/assessment/`.
 *
 * Probe completion condition (d) — the launcher-origin day anchor under DST /
 * timezone changes — is covered by re-running this probe after changing the
 * device timezone (`adb shell cmd timezone set-timezone ...`), which is
 * recorded in the same evidence document.
 */
@RunWith(AndroidJUnit4::class)
class UsageStatsIntervalProbeTest {

    @Test
    fun probeUsageStatsIntervalSemantics() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val statsManager = context.getSystemService(UsageStatsManager::class.java)
        assertNotNull(statsManager)
        val tag = "U5Probe"
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val anchorDay = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        // 35 days back: observes retention beyond the 30d contract window.
        val begin = anchorDay.minusDays(34).atStartOfDay(zone).toInstant().toEpochMilli()
        val window30Start = anchorDay.minusDays(29).atStartOfDay(zone).toInstant().toEpochMilli()

        Log.i(tag, "zone=$zone anchorEpochDay=$anchorDay now=$now begin=$begin window30Start=$window30Start")
        Log.i(tag, "zoneDST: isDaylightSavings=${zone.rules.isDaylightSavings(Instant.ofEpochMilli(now))} nextTransition=${zone.rules.nextTransition(Instant.ofEpochMilli(now))}")

        val stats = statsManager!!.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, begin, now)
        assertNotNull("queryUsageStats must not return null", stats)
        Log.i(tag, "intervalCount=${stats.orEmpty().size}")

        var oldest = Long.MAX_VALUE
        var crossBegin = 0
        var crossEnd = 0
        var partialIn30dWindow = 0
        var dayAlignedIntervals = 0
        var nonZeroForegroundIntervals = 0
        val intervalsByPackage = mutableMapOf<String, MutableList<Pair<Long, Long>>>()
        for (stat in stats.orEmpty()) {
            val firstDay = Instant.ofEpochMilli(stat.firstTimeStamp).atZone(zone).toLocalDate()
            val lastDay = Instant.ofEpochMilli(stat.lastTimeStamp).atZone(zone).toLocalDate()
            if (firstDay == lastDay) dayAlignedIntervals += 1
            if (stat.firstTimeStamp < begin) crossBegin += 1
            if (stat.lastTimeStamp > now) crossEnd += 1
            val fullyContained = stat.firstTimeStamp >= window30Start && stat.lastTimeStamp <= now
            if (!fullyContained) partialIn30dWindow += 1
            if (stat.totalTimeInForeground > 0) nonZeroForegroundIntervals += 1
            if (stat.firstTimeStamp < oldest) oldest = stat.firstTimeStamp
            intervalsByPackage.getOrPut(stat.packageName) { mutableListOf() }.add(stat.firstTimeStamp to stat.lastTimeStamp)
            Log.i(
                tag,
                "interval pkg=${stat.packageName} first=$firstDay(${stat.firstTimeStamp}) last=$lastDay(${stat.lastTimeStamp}) " +
                    "fg=${stat.totalTimeInForeground} lastUsed=${stat.lastTimeUsed} contained=$fullyContained",
            )
        }
        val retentionDays = if (oldest == Long.MAX_VALUE) -1 else (now - oldest) / (24L * 60 * 60 * 1000)
        // Overlap / consecutiveness measurement per package (sorted by start):
        // overlapping pairs share time; gapped pairs leave unrecorded time.
        var overlappingPairs = 0
        var gappedPairs = 0
        var adjacentPairs = 0
        for ((_, intervals) in intervalsByPackage) {
            val sorted = intervals.sortedBy { it.first }
            for (i in 1 until sorted.size) {
                val previous = sorted[i - 1]
                val current = sorted[i]
                when {
                    current.first < previous.second -> overlappingPairs += 1
                    current.first == previous.second -> adjacentPairs += 1
                    else -> gappedPairs += 1
                }
            }
        }
        Log.i(
            tag,
            "summary dayAligned=$dayAlignedIntervals/${stats.orEmpty().size} crossBegin=$crossBegin crossEnd=$crossEnd " +
                "partialIn30dWindow=$partialIn30dWindow nonZeroFg=$nonZeroForegroundIntervals " +
                "retentionDaysApprox=$retentionDays overlapPairs=$overlappingPairs adjacentPairs=$adjacentPairs gappedPairs=$gappedPairs",
        )
    }
}
