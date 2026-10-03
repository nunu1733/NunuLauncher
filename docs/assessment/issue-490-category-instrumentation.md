# Issue #490 category instrumentation assessment

**Status:** Investigated (candidate validation pending)  
**Confirmed:** 2026-10-03  
**Assessment base:** `53f90652cf9f699d7f089c91fd1c6ff9840ca98d`

## Evidence

The issue body conflates workflow-run results with the category-override job result. The category lane was green in runs `36748309850`, `36751599263`, `36754587458`, and `36796217597`; the first two workflow runs failed in other jobs. Run `36798059031` had three failing attempts in the category lane. Each listed attempt used the same head SHA.

| Run | Attempt | Head SHA | Category-override lane |
|---|---:|---|---|
| `36748309850` | 1 | `e744031aca6ca140018f0db50c1004826d7efe66` | success |
| `36751599263` | 1 | `59369367598e9abf467fe96e7b4a8719f21b67a6` | success |
| `36754587458` | 1 | `59369367598e9abf467fe96e7b4a8719f21b67a6` | success |
| `36796217597` | 1 | `849aefe40bf7d96337f08f23a47f48ec8948fdf0` | success |
| `36798059031` | 1 | `2a4e1e2aa981df4cd687f7dfb22c85fc45f3e0e4` | failure |
| `36798059031` | 2 | `2a4e1e2aa981df4cd687f7dfb22c85fc45f3e0e4` | failure |
| `36798059031` | 3 | `2a4e1e2aa981df4cd687f7dfb22c85fc45f3e0e4` | failure |
| `36964928302` | 1 | `87a2eb3bb41c70694974ad6acf30ae6a586630c2` | failure |
| `37064353410` | 1 | `53f90652cf9f699d7f089c91fd1c6ff9840ca98d` | success |

Run `36964928302` is a recent main-branch recurrence: `CustomCategoryPreferencesInstrumentationTest.editorAndDialogTransitionsRestoreInputFocusToTheSummaryNode` timed out after 5000 ms. Run `37064353410`, the assessment base, passed this lane. These observations establish intermittent failures, not their cause.

In run `36798059031`, attempt 1 reported `ArrayIndexOutOfBoundsException: length=640; index=-341` in `CategoryOverridePreferencesInstrumentationTest.editorIsReadableAtTwoHundredPercentFontScaleAndRestoresFocusAfterSave`. The visible stack begins at `androidx.compose.runtime.SlotWriter.moveSlotGapTo(SlotTable.kt:4351)`. Attempt 3 reported `length=1280; index=-801` in `CustomCategoryPreferencesInstrumentationTest.editorAndDialogTransitionsRestoreInputFocusToTheSummaryNode`; visible frames include `SlotWriter.moveSlotGapTo`, `removeSlots`, and `removeGroup`. The available console output does not contain the complete exception stack. The `SlotWriter` signature alone does not establish a text-measurement cause or implicate pausable composition.

Attempt 2 failed `CustomCategoryPreferencesInstrumentationTest.renameKeepsTheEntryPresentedAsTheSameCategory` with `ComposeTimeoutException: Condition still not satisfied after 5000 ms`. Its stack reaches `AndroidComposeUiTestImpl.waitUntil(ComposeUiTest.kt:809)`, `AndroidComposeTestRule.waitUntil(...:378)`, and the test at line 217. Attempt 3 also had a 5000 ms timeout in `CategoryOverridePreferencesInstrumentationTest.cancelRestoresFocusAndLongAppLabelRemainsReachableAtTwoHundredPercentFontScale`.

The available CI logs show API 36 / Platform 36.1, the `android-36;google_apis;x86_64` system image, Pixel 7 Pro AVD, and Android Emulator 37.1.11.0 (build 15917651). The same logged configuration appears in successful and failing runs; the system-image revision is not pinned in the logs, so an image-roll difference cannot be confirmed. CI runs on x86_64; local ARM64 execution remains a possible environment difference, not evidence of cause.

## Candidate and validation state

The resolved Compose runtime/foundation/UI baseline is 1.10.1 under BOM `2026.01.00`. BOM `2026.03.01` aligns runtime, foundation, and UI to 1.10.6 while keeping Material3 at 1.4.0. The official [Foundation 1.10.6 notes](https://developer.android.com/jetpack/androidx/releases/compose-foundation#1.10.6) say pausable composition in prefetch is disabled by default to address stability concerns. The official [Runtime 1.10.6 notes](https://developer.android.com/jetpack/androidx/releases/compose-runtime#1.10.6) describe fixes for two pausable-composition crashes. This makes the patch update a reasonable candidate to validate, but the captured stack does not prove it is the root cause. See the [Google Maven Compose BOM 2026.03.01 POM](https://dl.google.com/dl/android/maven2/androidx/compose/compose-bom/2026.03.01/compose-bom-2026.03.01.pom).

The candidate BOM change is present in the shared working tree; repeat-test results are pending. This is a low-risk dependency patch update with no database or persisted-data changes. Rollback is to BOM `2026.01.00`. Keep the existing tests and `organizer-instrumentation-category-override-tests` lane on `surface_organizer_ui` (Conditional); this assessment proposes no test or lane additions, deletions, or rerouting.

## Sources

- GitHub Issue [#490](https://github.com/nunu1733/NunuLauncher/issues/490) and Actions runs listed above; evidence collected 2026-10-03.
- Repository assessment base: `53f90652cf9f699d7f089c91fd1c6ff9840ca98d`.
- Compose release notes and BOM POM linked above; checked 2026-10-03.
