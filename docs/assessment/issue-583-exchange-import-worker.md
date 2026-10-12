# Scoped Exchange import worker ownership repair

Status: investigation / implementation packet; Phase3 not closed.
Issue: [#583](https://github.com/nunu1733/NunuLauncher/issues/583), Refs #516.
Base: `0ef86713b9414f0aa85c028e2e77d3ec14c0f4db`.

Accepted bug contracts at this exact base: Spec417 AC-6 (same-scope authority), Spec374 DI-AC-01/13 (durable save before success), Spec328 attempt fencing, Spec418 machine worker / lock-free main publication. Their implemented accepted specifications are the bug oracle; no product decision or schema change is proposed. Existing Spec417/418 plans own run-lock→exchange-gate and off-main machine queries. This maintenance narrowly restores those contracts without changing the run interface, persistence or layout writer.

Actual API37 RED: validation source `ae69715379314f24338304ca5a0b2b4f9e9f7c43`, app SHA `9ff6c8483ff74b00ed50ee295d994a001439fa69f22315f0b39af6436b049bd4`. Local redacted CreateDocument request + synthetic one-candidate valid v4 reply through OpenDocument; Import crashed at `settleImport`→`isLiveScopeOwner` because uiDispatcher is publication main. Main15 base has byte-identical affected holder calls. Crash before pending persistence, 500-row DB and prefs remained byte-identical; no layout Apply or external send.

Test audit: existing `ExchangeFlowStateHolderTest` is the primary deterministic owner of live run-in durable import, stale attempt / cancellation / foreign-binding fencing and ownerless projection. Its default same-thread publication accepts every thread, hiding this actual regression. Extend its fixture with production-equivalent distinct publication/worker confinement and exercise method-choice live import success, durable saved record, continuation and no unhandled errors. Lowest faithful JVM boundary observes guard and run-lock ordering; actual Android SAF replay separately observes DocumentsUI/framework integration and cold durable recovery. Reuse organizer unit surface/job; no new class, lane, production seam, CI mapping or permission. Classification Permanent regression in existing owner. Full existing holder tests retain race/negative controls.

Implementation plan: perform both settle ownership reads on worker before main pure projection. Keep attempt-token checks on main after suspension, durable save re-verification under run-lock→gate, and fresh worker connection check. Guard remains unchanged. Migration N/A (none). Rollback is source revert; no durable format change. Validation: intended test RED, focused holder/confinement GREEN, Spotless, affected CI, independent audit/review, then actual16 port SAF import/cold pending/no-layout-write proof.

Validation results so far:
- RED at `dd9b907bdde89b3d4fd1a2cb4a56a8bfb49ba30a`: exact existing method `runInImportShowsTheScopedSummaryAndAttachesOnCta`, one test / one failure / no skip,0.529s. Its explicit no-crash assertion observed `IllegalStateException: organizer run state machine must not run on the publication thread`. Build13m54s; log/XML retained privately. This reproduces the intended thread guard, rather than a timeout.
- Entire affected holder source at main base and16 validation `ae69715379314f24338304ca5a0b2b4f9e9f7c43` is byte-identical (211,023 bytes, SHA-256 `86a6ea5ce8002353495c8a2ae48beeaa37af0c4eac5be659230b8c5a7ba9c4cc`).
- Repository contract validator passed before repair.
- GREEN / affected CI / independent audit and reviews / actual16 repaired SAF import and cold recovery pending.

Risk tier H: same accepted Spec417/418 plan constraints apply to this maintenance of run ownership and durable proposal fencing; use `risk: layout-data` to require the independent evidence gate. No new plan, product decision, layout writer or schema is introduced.
