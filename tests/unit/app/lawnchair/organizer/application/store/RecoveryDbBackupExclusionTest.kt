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
package app.lawnchair.organizer.application.store

import com.android.launcher3.LauncherFiles
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The organizer recovery database must never join the launcher backup
 * allowlist: it is app-private per-recovery state (ADR-0009) and leaking it
 * into LawnchairBackup would both expose it and let a restore resurrect a
 * stale recovery store.
 *
 * Move boundary from
 * `tests/organizer-instrumentation/com/android/launcher3/organizer/BackupExclusionTest.java`
 * (Issue #458 R-15): the assertion is a static list check on JVM-compilable
 * sources, so the permanent `organizer-unit-tests` gate is the canonical,
 * faster owner.
 */
class RecoveryDbBackupExclusionTest {

    @Test
    fun recoveryDatabaseIsAbsentFromLauncherBackupAllowlist() {
        assertFalse(LauncherFiles.ALL_FILES.contains(RecoveryDbSchema.FILE_NAME))
    }
}
