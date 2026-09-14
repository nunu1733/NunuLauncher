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
package app.lawnchair.organizer.application.protocol

/**
 * Issue #299 / CI-AC-08: closed set of capture-side invariant violations, so
 * a failed authoritative capture can name the violated invariant without
 * carrying any layout content. Constant names are surfaced verbatim in the
 * bounded debug diagnostics line; adding constants is the only allowed
 * extension (journal vocabulary is unchanged).
 */
enum class CaptureInvariantCategory {
    /**
     * A persisted widget row reaches the capture codec without a bound
     * [android.appwidget.AppWidgetManager] id (`appWidgetId < 0` is stored
     * null-free by the codec projection) or without its provider component.
     * This is the pending-restore state Nova backup restore commits and the
     * loader's next repair generation resolves (bind or delete).
     */
    INVALID_WIDGET_ROW,

    /**
     * An authoritative capture rejected persisted layout state through an
     * untyped [IllegalArgumentException]. The specific invariant is not
     * exposed because its exception text may contain unbounded layout data.
     */
    INVALID_CAPTURE_STATE,
}

/**
 * Thrown by the capture codec when a persisted row violates a capture
 * invariant. Extends [IllegalArgumentException] so the shipped diagnostics
 * identity (`exceptionClass=IllegalArgumentException`) observed on the
 * original issue sessions stays stable; the [invariant] category is the
 * added bounded field. Untyped [IllegalArgumentException] failures use
 * [CaptureInvariantCategory.INVALID_CAPTURE_STATE].
 */
class CaptureInvariantViolationException(
    val invariant: CaptureInvariantCategory,
    message: String,
) : IllegalArgumentException(message)
