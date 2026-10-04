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

import android.app.Application
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnitRunner
import java.io.File
import java.lang.reflect.Proxy

/**
 * Custom instrumentation runner for Deck Retirement smoke tests.
 *
 * Supports four modes via instrumentation argument `deck_retirement_target_mode`:
 * - [MODE_DEFAULT]: No special behavior; natural production no-op.
 * - [MODE_OLD_COMPAT]: No special behavior; does not load any new migration class.
 * - [MODE_NEW_TYPED]: No special behavior; natural production no-op.
 * - [MODE_NEW_PAUSE]: Installs a [DeckRetirementPhaseObserver] before the
 *   Application's onCreate runs, which blocks the migration at
 *   [DeckRetirementMigration.PHASE_AFTER_NORMALIZATION_BEFORE_CLEANUP] until
 *   a release marker file appears. The nonce and control directory are
 *   derived from instrumentation arguments.
 */
class DeckRetirementTestRunner : AndroidJUnitRunner() {

    companion object {
        const val MODE_DEFAULT = "default"
        const val MODE_OLD_COMPAT = "old_compat"
        const val MODE_NEW_TYPED = "new_typed"
        const val MODE_NEW_PAUSE = "new_pause"
        private const val TAG = "DeckRetirementTestRunner"

        private val NONCE_REGEX = Regex("^[0-9a-f]{32}$")
        private const val POLL_INTERVAL_MS = 100L
        private const val DEADLINE_MS = 120_000L
    }

    override fun callApplicationOnCreate(app: Application) {
        val mode = InstrumentationRegistry.getArguments().getString("deck_retirement_target_mode") ?: MODE_DEFAULT

        when (mode) {
            MODE_NEW_PAUSE -> installPauseObserver(app)
            MODE_DEFAULT, MODE_OLD_COMPAT, MODE_NEW_TYPED -> {
                // No observer installed; natural production no-op.
            }
        }

        super.callApplicationOnCreate(app)

        val readyMarker = when (mode) {
            MODE_DEFAULT -> "MODE_READY mode=default typed=true"
            MODE_OLD_COMPAT -> "OLD_COMPAT_READY typed=true"
            MODE_NEW_TYPED -> "NEW_TYPED_READY typed=true"
            MODE_NEW_PAUSE -> "NEW_PAUSE_READY typed=true"
            else -> "MODE_READY mode=$mode typed=true"
        }
        println(readyMarker)
    }

    private fun installPauseObserver(app: Application) {
        val nonce = InstrumentationRegistry.getArguments().getString("deck_retirement_nonce")
        require(nonce != null && NONCE_REGEX.matches(nonce)) {
            "Invalid or missing deck_retirement_nonce: $nonce"
        }

        val controlDir = app.filesDir.parentFile!!
            .resolve("cache/logs/deck-retirement-control")
        controlDir.mkdirs()

        val pausedFile = File(controlDir, "$nonce.paused")
        val releaseFile = File(controlDir, "$nonce.release")
        val ackFile = File(controlDir, "$nonce.ack")

        require(!pausedFile.exists()) { "Stale .paused marker exists: $pausedFile" }
        require(!releaseFile.exists()) { "Stale .release marker exists: $releaseFile" }
        require(!ackFile.exists()) { "Stale .ack marker exists: $ackFile" }

        // Reflection only: the runner must not resolve new migration classes in
        // any other mode, so old-target compatibility runs never load them.
        val phaseObserverInterface = Class.forName("app.lawnchair.migration.DeckRetirementPhaseObserver")
        val observer = Proxy.newProxyInstance(
            app.classLoader,
            arrayOf(phaseObserverInterface),
        ) { _, method, methodArgs ->
            if (method.name == "onPhaseReached") {
                val phase = methodArgs?.firstOrNull() as? String ?: ""
                Log.i(TAG, "PAUSED phase=$phase nonce=$nonce typed=true")
                pausedFile.writeText(nonce)
                awaitReleaseAndAck(releaseFile, ackFile, nonce)
            }
            null
        }

        val migrationClass = Class.forName("app.lawnchair.migration.DeckRetirementMigration")
        val instanceField = migrationClass.getDeclaredField("INSTANCE")
        instanceField.isAccessible = true
        val migrationInstance = instanceField.get(null)
        val observerField = migrationClass.getDeclaredField("phaseObserver")
        observerField.isAccessible = true
        observerField.set(migrationInstance, observer)
    }

    private fun awaitReleaseAndAck(releaseFile: File, ackFile: File, nonce: String) {
        val deadline = System.currentTimeMillis() + DEADLINE_MS
        while (System.currentTimeMillis() < deadline) {
            if (releaseFile.exists()) {
                releaseFile.delete()
                ackFile.writeText(nonce)
                Log.i(TAG, "ACK_RECEIVED nonce=$nonce typed=true")
                return
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }

        Log.w(TAG, "PAUSE_TIMEOUT nonce=$nonce typed=true")
    }
}