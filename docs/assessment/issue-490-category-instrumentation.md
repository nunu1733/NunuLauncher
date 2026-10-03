# Issue #490 category instrumentation diagnosis

**Status:** Investigated<br>
**Confirmed:** 2026-10-03<br>
**Assessment base:** `53f90652cf9f699d7f089c91fd1c6ff9840ca98d`

## Finding

Issue [#490](https://github.com/nunu1733/NunuLauncher/issues/490) describes several workflow failures as though they were all category-override lane failures. The run-level conclusion and the lane-level result differ: category-override passed in runs `36748309850`, `36751599263`, `36754587458`, and `36796217597`; runs `36748309850` and `36751599263` failed in other jobs. The lane failed in all three attempts of `36798059031`. Each row below identifies the workflow run, attempt, run head, and category lane result; these rows do not infer a cause.

| Run | Attempt | Head SHA | Category-override result |
|---|---:|---|---|
| [`36748309850`](https://github.com/nunu1733/NunuLauncher/actions/runs/36748309850) | 1 | `e744031aca6ca140018f0db50c1004826d7efe66` | success |
| [`36751599263`](https://github.com/nunu1733/NunuLauncher/actions/runs/36751599263) | 1 | `59369367598e9abf467fe96e7b4a8719f21b67a6` | success |
| [`36754587458`](https://github.com/nunu1733/NunuLauncher/actions/runs/36754587458) | 1 | `59369367598e9abf467fe96e7b4a8719f21b67a6` | success |
| [`36796217597`](https://github.com/nunu1733/NunuLauncher/actions/runs/36796217597) | 1 | `849aefe40bf7d96337f08f23a47f48ec8948fdf0` | success |
| [`36798059031`](https://github.com/nunu1733/NunuLauncher/actions/runs/36798059031) | 1 | `2a4e1e2aa981df4cd687f7dfb22c85fc45f3e0e4` | failure |
| [`36798059031`](https://github.com/nunu1733/NunuLauncher/actions/runs/36798059031) | 2 | `2a4e1e2aa981df4cd687f7dfb22c85fc45f3e0e4` | failure |
| [`36798059031`](https://github.com/nunu1733/NunuLauncher/actions/runs/36798059031) | 3 | `2a4e1e2aa981df4cd687f7dfb22c85fc45f3e0e4` | failure |
| [`36964928302`](https://github.com/nunu1733/NunuLauncher/actions/runs/36964928302) | 1 | `87a2eb3bb41c70694974ad6acf30ae6a586630c2` | failure |
| [`37064353410`](https://github.com/nunu1733/NunuLauncher/actions/runs/37064353410) | 1 | `53f90652cf9f699d7f089c91fd1c6ff9840ca98d` | success |

Recent main-branch evidence is mixed: `36964928302` fails while running `CustomCategoryPreferencesInstrumentationTest.editorAndDialogTransitionsRestoreInputFocusToTheSummaryNode`, with a 5000 ms Compose wait timeout; the category lane passed in `37064353410` at the assessment base. This is evidence of an intermittent category-lane failure, not evidence that every historical failure has one shared mechanism.

## Captured failure signatures

In `36798059031` attempt 1, `CategoryOverridePreferencesInstrumentationTest.editorIsReadableAtTwoHundredPercentFontScaleAndRestoresFocusAfterSave` reported `ArrayIndexOutOfBoundsException: length=640; index=-341`. The visible first stack frame is `androidx.compose.runtime.SlotWriter.moveSlotGapTo(SlotTable.kt:4351)`. The captured console output stops before a complete stack trace. Attempt 3 reported `length=1280; index=-801` in `CustomCategoryPreferencesInstrumentationTest.editorAndDialogTransitionsRestoreInputFocusToTheSummaryNode`; its visible frames include `SlotWriter.moveSlotGapTo`, `removeSlots`, and `removeGroup`. The `SlotWriter` method signatures do not establish that text measurement caused the failure, nor do the available frames prove a pausable-composition defect.

Attempt 2 of that run failed `CustomCategoryPreferencesInstrumentationTest.renameKeepsTheEntryPresentedAsTheSameCategory` with `ComposeTimeoutException: Condition still not satisfied after 5000 ms`. The test stack reaches `AndroidComposeUiTestImpl.waitUntil(ComposeUiTest.kt:809)`, `AndroidComposeTestRule.waitUntil(...:378)`, and test line 217. Attempt 3 also timed out after 5000 ms in `CategoryOverridePreferencesInstrumentationTest.cancelRestoresFocusAndLongAppLabelRemainsReachableAtTwoHundredPercentFontScale`. The wait location proves which test wait expired, not why the expected UI state was absent.

Failure-time logcat from a focused local reproduction records the create operation completing on worker TID 17416, followed by reload completing as `Loaded(entries=[Commute])` on TID 17430. The semantics snapshot still contains only the two base rows while the test-side catalog reports `[Commute]`; the fresh load did not return an empty catalog afterward. A fast loop over the eight existing category-override UI tests followed by the custom-category create-flow test with temporary create/load probes reproduced the mismatch on the second invocation and again on the fourth. The probes were removed after capture. These observations localize a timing-sensitive symptom to the test/UI observation path; they do not establish a production state-loss or `SlotWriter` root cause.

## Test-harness hypothesis and limits

The exact locally resolved `androidx.compose.ui:ui-test-junit4-android:1.10.1` artifact was inspected from its cached AAR/classes. Its public bytecode exposes both `createComposeRule()` and `createComposeRule(CoroutineContext)`. The no-argument factory passes `EmptyCoroutineContext`. In the 1.10.1 `AndroidComposeUiTestEnvironment` bytecode, a supplied `TestDispatcher` is selected as the composition dispatcher; when no test dispatcher is supplied, the composition dispatcher is created with `UnconfinedTestDispatcher` (using any scheduler supplied in the context). The recomposer context is derived from the effect context plus the test frame-clock interceptor. This verifies that an existing test can inject a `StandardTestDispatcher` without a production seam.

The [official Compose test migration guide](https://developer.android.com/develop/ui/compose/testing/migrate-v2) documents that the v1 test APIs use `UnconfinedTestDispatcher` by default while v2 uses `StandardTestDispatcher`, and that tests relying on immediate execution may require explicit synchronization. The [Compose test API reference](https://developer.android.com/reference/kotlin/androidx/compose/ui/test/junit4/package-summary) documents the `effectContext` overload and its role in composition and effects. With an injected standard test dispatcher, a coroutine suspended in `withContext(Dispatchers.IO)` resumes to its original test dispatcher; queued work and recomposition must then be driven or synchronized before asserting semantics. This makes dispatcher scheduling a test-harness hypothesis worth validating, not a proven cause of the observed mismatch.

The three existing category UI classes are the focused validation candidates: [`CategoryOverridePreferencesInstrumentationTest`](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/CategoryOverridePreferencesInstrumentationTest.kt), [`CustomCategoryPreferencesInstrumentationTest`](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/CustomCategoryPreferencesInstrumentationTest.kt), and [`OrganizerLockScreenTest`](../../tests/organizer-instrumentation/app/lawnchair/organizer/locks/OrganizerLockScreenTest.kt). Keep the existing observable create/rename/accessibility/focus/lock contracts, including the 200% font-scale assertions. The ownership boundary is the real Compose instrumentation surface; fake persistence/coordinator behavior is already covered by unit tests, while those tests cannot replace Compose semantics, focus, or accessibility integration. Do not add a new test, assertion, wait timeout, route, production seam, or CI lane for this diagnosis. Existing CI ownership remains `organizer-instrumentation-category-override-tests` on `surface_organizer_ui` with **Conditional** classification.

Under the six failure classes in [quality strategy](../engineering/quality-strategy.md#intermittent-failure-の分類証拠retry方針), the recent wait-timeout/missing-semantics pattern is a **candidate class 3: test synchronization/test harness defect** because explicit scheduling control is testable at the existing boundary. The `SlotWriter` crashes remain **class 6: unknown/investigation required** because the captured stack is incomplete and no exact runtime fix has been matched to the signature. The evidence does not justify reclassifying every historical crash as a harness failure.

## Dependency candidate considered

At the assessment base, Compose runtime/foundation/UI resolve to 1.10.1 under BOM `2026.01.00`. A broad trial with BOM `2026.03.01` aligns runtime, foundation, and UI to 1.10.6 while leaving Material3 at 1.4.0; it changes 181 managed artifact entries in the BOM and did not eliminate the observed timeout. It was an investigation trial, not the selected final change. The [official Foundation 1.10.6 notes](https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.10.6) describe disabling pausable prefetch by default for stability; the [Runtime 1.10.6 notes](https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.10.6) describe pausable-composition fixes. The [Google Maven BOM 2026.03.01 POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.03.01/compose-bom-2026.03.01.pom) confirms the aligned candidate versions. No captured `PausableComposition`, `PausedComposition`, or `LazyLayoutPrefetch` frame ties the stack to those changes. The cause remains unproven.

The narrower candidate is to run the three existing classes with an explicit `StandardTestDispatcher` and the existing scheduler/frame synchronization while retaining their current assertions and lane. This is a test-harness experiment, not a claim that changing dispatchers repairs a production defect. Because only test dispatcher configuration changes, with no production, dependency, database, or persisted-data change, its assessed risk is **L (maintenance)**; rollback is to the prior test configuration. The broad BOM trial is not the final candidate.

## Test audit record

- **Protected contract:** spec [#99 AC-10](../../specs/99-user-authored-category-overrides/spec.md#acceptance-criteria) and spec [#336 AC-13](../../specs/336-user-defined-categories/spec.md#acceptance-criteria) UI accessibility, focus restoration, keyboard/DPAD, switch-equivalent semantics, and 200% font-scale behavior; spec [#342](../../specs/342-custom-category-ci-lane/spec.md) keeps the custom-category accessibility oracle in the existing category lane. Existing lock UI contract coverage remains owned by `OrganizerLockScreenTest`.
- **Credible regression:** asynchronous authoring/reload completion followed by UI semantics/focus observation can regress if test scheduling does not process the returned coroutine and ensuing composition before observation. This is a test-harness validation target; the collected evidence does not prove the app lost the data.
- **Primary owner and overlap:** the three existing instrumentation classes exercise the real Compose boundary. Unit tests already own fake persistence/coordinator behavior. No new boundary, duplicate test, or production-only hook is proposed.
- **Impact surface and classification:** existing `surface_organizer_ui`, Conditional category-override lane. No test/lane is added, removed, or rerouted.
- **Failure classification:** localized timeout/missing-semantics signature is a class-3 candidate; incomplete `SlotWriter` crashes remain class 6. Rerun-green alone is not root-cause evidence.
- **Validation boundary:** use the existing three class filters and existing lane. Record run IDs, attempts, head SHA, and final outcomes in the tracking Issue/PR; those execution results are not duplicated here.

## Environment evidence and unresolved questions

The captured CI logs report API 36 / Platform 36.1, `android-36;google_apis;x86_64`, a Pixel 7 Pro AVD, and Android Emulator 37.1.11.0 (build 15917651). This logged configuration appears in both successful and failing runs. The system-image revision is not pinned in the logs, so image-roll differences are unverified. CI uses x86_64; local ARM64 is an architecture difference, but there is no evidence here that it explains the failures. No proven CI/local SDK, emulator, or architecture cause has been established.

Collected local diagnostic files were `/tmp/issue490-reload-diagnostic-2.log` and `/tmp/issue490-reload-diagnostic-failure-logcat.txt`; they are transient investigation artifacts, not repository evidence. Full rerun outcomes and any updated classification belong in Issue #490 or its PR, with run/attempt/head SHA recorded there.

## Sources

- [GitHub Issue #490](https://github.com/nunu1733/NunuLauncher/issues/490) and the linked Actions runs; collected 2026-10-03.
- Accepted specs: [#99](../../specs/99-user-authored-category-overrides/spec.md), [#336](../../specs/336-user-defined-categories/spec.md), and [#342](../../specs/342-custom-category-ci-lane/spec.md).
- Exact Compose 1.10.1 UI test binary: locally cached `ui-test-junit4-android:1.10.1` and `ui-test-android:1.10.1` AAR bytecode; inspected 2026-10-03. Public API confirmed separately by the linked official API reference.
- Official Compose [testing migration guide](https://developer.android.com/develop/ui/compose/testing/migrate-v2), [Foundation 1.10.6 release notes](https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.10.6), [Runtime 1.10.6 release notes](https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.10.6), and [Google Maven BOM 2026.03.01 POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.03.01/compose-bom-2026.03.01.pom); checked 2026-10-03.
- Repository quality policy: [quality strategy](../engineering/quality-strategy.md), [test-audit skill](../../.agents/skills/test-audit/SKILL.md), and [CI test portfolio](../engineering/ci-test-portfolio.md).
