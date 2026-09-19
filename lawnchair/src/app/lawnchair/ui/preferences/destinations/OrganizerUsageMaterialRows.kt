/*
 * Copyright 2022, Lawnchair
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

package app.lawnchair.ui.preferences.destinations

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.lawnchair.organizer.integration.UsageAccess
import app.lawnchair.preferences.getAdapter
import app.lawnchair.preferences2.preferenceManager2
import app.lawnchair.ui.preferences.components.controls.ClickablePreference
import app.lawnchair.ui.preferences.components.controls.SwitchPreference
import com.android.launcher3.R

/**
 * Issue #366: the personalization material rows — the launcher-origin
 * recording toggle and the usage access row — shared by Home screen settings
 * (Personalization group) and the Organizer hub (materials group). Both
 * surfaces must read and write the same preference and the same app-op state;
 * a second persistence would be a second truth (spec #203 U-2).
 */
@Composable
fun OrganizerUsageMaterialRows() {
    val context = LocalContext.current
    SwitchPreference(
        adapter = preferenceManager2().organizerPersonalizationRecording.getAdapter(),
        label = stringResource(id = R.string.organizer_personalization_recording_label),
        description = stringResource(id = R.string.organizer_personalization_recording_description),
    )
    // Spec #203 U-2: the state is re-read on every resume so returning
    // from the system usage-access settings refreshes the row. The
    // predicate is the shared app-op semantics (2026-09-15 re-review
    // Blocking 1).
    val lifecycleOwner = LocalLifecycleOwner.current
    var usageAccessGranted by remember { mutableStateOf(UsageAccess.isGranted(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) usageAccessGranted = UsageAccess.isGranted(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    ClickablePreference(
        label = stringResource(id = R.string.organizer_personalization_usage_access_label),
        subtitle = stringResource(
            id = if (usageAccessGranted) {
                R.string.organizer_personalization_usage_access_granted
            } else {
                R.string.organizer_personalization_usage_access_not_granted
            },
        ),
        onClick = {
            context.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
        },
    )
}
