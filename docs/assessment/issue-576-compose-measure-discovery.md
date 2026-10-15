# Issue #576: Compose LazyList measurement failure discovery

> Status: proposed (Phase 1 evidence; original cause remains unknown)
> Checked: 2026-10-15
> Risk tier: L (research/docs only; no runtime or CI routing change)

## Scope

[Issue #576](https://github.com/nunu1733/NunuLauncher/issues/576) owns the
unclassified failure from [PR #575](https://github.com/nunu1733/NunuLauncher/pull/575).
This record preserves the observed signature and investigation boundary, not a
fix or a completed classification. The Issue remains open; no automatic closing
keyword is appropriate.

## Original evidence

| Identity | Observed value |
|---|---|
| Run / attempt | [38141049003 / 1](https://github.com/nunu1733/NunuLauncher/actions/runs/38141049003/attempts/1) |
| Head | `000c26903b9f0880ce551721dd89c3bb09da1ad6` |
| Job | [114474913263](https://github.com/nunu1733/NunuLauncher/actions/runs/38141049003/job/114474913263) |
| Report artifact | [11703214323](https://github.com/nunu1733/NunuLauncher/actions/runs/38141049003/artifacts/11703214323) |
| Failure-time artifact | [11703384086](https://github.com/nunu1733/NunuLauncher/actions/runs/38141049003/artifacts/11703384086) |
| XML | `TEST-test(AVD) - 16.xml`: 56 tests, 1 failure, 0 errors, 0 skipped |
| Failing test | `ManualOrganizationPreferencesInstrumentationTest#staleApplyAttemptExplainsOutcomeAndNextStep`, 3.790s |
| XML SHA-256 | `48f070c75c78d2362aae73298d24893f3fcb4fa3ca79b32da84af705f83e88fb` |
| Per-test logcat SHA-256 | `15af0378dabd6434cb67429d5fbe84167d37d000a134a7569f4f34986687c455` |

Downloaded both artifacts on 2026-10-15 using:

```bash
gh run download 38141049003 -R nunu1733/NunuLauncher \
  -n organizer-instrumentation-manual-organization-ui-reports \
  -n manual-organization-ui-failure-time-emulator-evidence -D <evidence-dir>
```

The following is an excerpt of the XML failure, not a replacement for the raw
artifact or a claim that omitted frames were inspected independently:

```text
java.lang.IndexOutOfBoundsException: Index 2, size 2
 at androidx.compose.foundation.internal.InlineClassHelperKt.throwIndexOutOfBoundsException(InlineClassHelper.kt:42)
 at androidx.compose.foundation.lazy.layout.MutableIntervalList.get(IntervalList.kt:227)
 at androidx.compose.foundation.lazy.layout.LazyLayoutIntervalContent.getKey(LazyLayoutIntervalContent.kt:73)
 at androidx.compose.foundation.lazy.LazyListItemProviderImpl.getKey(LazyListItemProvider.kt:86)
 at androidx.compose.foundation.lazy.layout.LazyLayoutMeasureScopeImpl.compose(LazyLayoutMeasureScope.kt:91)
 at androidx.compose.foundation.lazy.layout.LazyLayoutMeasuredItemProvider.getPlaceables-3p2s80s(LazyLayoutMeasuredItem.kt:118)
 at androidx.compose.foundation.lazy.LazyListMeasuredItemProvider.getAndMeasure-0kLqBqw(LazyListMeasuredItemProvider.kt:54)
 at androidx.compose.foundation.lazy.LazyListMeasuredItemProvider.getAndMeasure-0kLqBqw$default(LazyListMeasuredItemProvider.kt:48)
 at androidx.compose.foundation.lazy.LazyListMeasureKt.measureLazyList-pIk1_oM(LazyListMeasure.kt:226)
```

The complete stack ends in Android's main Looper. Per-test logcat starts the test
at 13:10:59.820 and reports failure at 13:11:03.395. Its `TestRunner` PID/TID
5895/5921 is the reporting thread, not proof of the exception's originating
thread. Activity teardown messages before the report do not establish that the
initial exception occurred during teardown.

The [pre-rerun triage](https://github.com/nunu1733/NunuLauncher/pull/575#issuecomment-6109866492)
records a pressure-capture timeout but explicitly does not classify the failure
as environment-only. The [same-head retry result](https://github.com/nunu1733/NunuLauncher/pull/575#issuecomment-6110177334)
does not prove root-cause resolution.

## Canonical owner and hypotheses

At discovery base `c3d75e55f5f17dfb19fac2a202287ef12c725cb0`:

- The existing [connected test](../../tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt)
  at line 2789 owns the [#210 stale outcome wording](../../specs/210-stale-apply-outcome/spec.md).
  It creates a runner with default Direct publication, starts it, awaits Preview,
  confirms a fake stale rejection, then checks the outcome, discard explanation,
  recapture label and recapture summary.
- The [destination](../../lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt)
  collects state at line 160, restores focus/scroll at line 608, and emits dynamic
  lazy content at line 654. Its Applying branch has two top-level lazy items
  including the explainer. This is compatible with the reported size but does
  not prove the crash occurred in that branch.
- [Publication](../../lawnchair/src/app/lawnchair/organizer/ui/RunPublicationThread.kt)
  at line 86 uses main Handler FIFO/block-join semantics in production; Direct
  at line 50 runs on the caller. This fixture difference is a hypothesis, not a
  classification. Handler publication is not an asynchronous fire-and-forget.
- The stack proves an out-of-range lazy key request, not duplicate keys, a
  particular scroll target, a mutable application list, or an upstream fixed bug.
  Stable keys and dependency upgrades are not justified from this evidence.

| Path | Original blob | Discovery-base blob |
|---|---|---|
| Connected test above | `2e8f1c96c1b927ee623a11cda0424367c819a6d8` | `075e5c1c2e1dc9f5197361d1a9595dcbc66c35c9` |
| Destination above | `cb0cfcdf065942e773a4944ec893f33344e0add1` | `7cf449d025928023b3538b29aa81cd8c07b5e504` |
| Publication above | `19c74bdc6a98da8c4dbae6dbba644c7e33f02249` | `19c74bdc6a98da8c4dbae6dbba644c7e33f02249` |

The publication seam predates the original failure. The test and destination
changed since that run, so historical-versus-current success is not a controlled
single-variable comparison. A proposed current-source Direct/Handler experiment
must not be described as reproducing the original CI unless its signature and
causal conditions are actually established.

## Classification and remaining work

Classification remains **6: unknown / investigation required** under the
[quality strategy](../engineering/quality-strategy.md). No existing contract,
assertion, timeout, class filter or lane has been removed or weakened. No new test
or production seam is proposed in this discovery record. The existing
`surface_organizer_ui` / manual-organization-ui Conditional lane remains owner.

The Phase 1 proposed investigation contract will define the controlled experiment
before execution. Non-reproduction is useful bounded evidence but does not meet
the Issue's supported-classification exit condition. Any permanent test or
production change needs new evidence, test-audit justification and review first.

Prior art: 省略（理由: 階層Lの既存ownerを用いた調査・文書化のみ。新規seam、
dependency、test strategyの新設を行わない）。
