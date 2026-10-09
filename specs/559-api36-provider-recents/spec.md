---
issue: "#559"
status: accepted
requirements: []
updated: 2026-10-09
---

# Exact API36 provider direct app-to-overview wire repair

Risk tier: H. Conservative vendored SystemUI wire bridge. Not a layout-data/migration audit path. DB/model/loader untouched.

## Problem / Outcome

Exact API36 Google emulator provider (`google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`, sdk 36) expects `startRecentsTransition` as transaction 6 with five args and no WCT payload. Existing BP2A/BD1A AOSP shim sends transaction 5 (this server's `getRunningTasks`). Direct app to overview fails; Oct 7 HOME pass vs direct FAIL is a same-state entry contrast only (comment 6065462874) and does NOT disprove env drift as history identity. Fix restores direct app to overview on this exact provider only.

## Scope

- Exact fingerprint + sdk 36 only. Five-arg serialization, transaction 6, keep fail-closed non-null WCT reject.
- Reuse existing SystemUiProxy initial writer; add selector distinct from Nothing path.
- Preserve: BP2A/BD1A AOSP txn 5, Nothing txn 6 + Nothing-specific callback, API37 modern WCT path, all other BE2A/unknown fingerprints. No family generalization.
- Exact scope code paths: `quickstep/src/com/android/quickstep/SystemUiProxy.kt` (+ `Compatibility.kt` only if needed). Test boundary: SystemUiProxy consumer seam; no new CI lane/permanent surface.

## Non-goals

- No maxSdk 37 change, no thumbnail #555 fix, no SDK-family broadening, no new perms/deps/DB/layout writes, no AIDL revendor/new module.

## Prior art

- Installed DEX first-party trace (strongest): `docs/assessment/559-api36-provider-evidence/wire-contract/README.md` + `dex-excerpts.txt`, SystemUI SHA `a895f88ae...d14f5`. Server txn 6 five args; client six args; AOSP shim 5 wrong here.
- Scoped source already exists (AIDL Bundle arg shape): https://developer.android.com/guide/components/aidl (2026-10-09, adopted for arg-shape only, not as wire claim).
- Prior incorrect root report is not cited as fact.

## Behavior scenarios

1. Given exact BE2A API36 provider, when direct APP_SWITCH from app, then overview opens with verified card and matching task return; parcel 0 / FATAL 0 / timeout 0.
2. Given same provider, when gesture from app, then same overview result.
3. Given HOME-adjacent, older SDK/fingerprint, Nothing, non-null WCT, or API37 probe, then behavior unchanged; API37 wire probe is control.
4. Given candidate2 APK `5098ceb4d7af93c90684e07b1bdbe01408a92356631923fdde367a5fbc213278` on same install, then Oct 7 HOME pass / direct FAIL contrast is reestablished as entry-state signal only. Fixed-APK hash + source SHA (from base `f131e8e09441dc2d10801d4619b671b0da15a6ee`) + unchanged-config provider receipts + independent observation recorded separately; the probe is not a faulty oracle.

## Acceptance criteria

- [ ] AC-1: Direct Settings to overview, verified card to matching task return; parcel 0 / FATAL 0 / timeout 0.
- [ ] AC-2: Gesture from app gives same result as AC-1.
- [ ] AC-3: HOME-adjacent + old SDK/fingerprint/Nothing/non-null WCT unchanged, incl. API37 wire-probe control.
- [ ] AC-4: Candidate2 control (`5098ceb4...bc213278`) reestablishes HOME pass / direct FAIL on same install.
- [ ] AC-5: #554 API36 evidence linked; existing decoders unregressed; no main-merge claim.

## Test oracle

Owner: SystemUiProxy consumer seam. Required: actual production-consumer wire probe RED to GREEN proving descriptor `com.android.wm.shell.recents.IRecentTasks`, txn 6, FLAG_ONEWAY, five args (PendingIntent, Intent, Bundle, caller binder, Runner binder) fully consumed with enforceNoDataAvail. Non-null WCT is rejected with no Binder transaction. Selector-only fallback (routing assertion without wire bytes) is NOT allowed. Derive concrete invocation from existing `tools/diagnostics/RecentsParcelProbe.java` (+ `verify_recents_provider.py`) before design; do not invent commands.
Credible: wrong txn / wrong payload / wrong routing + non-null WCT reject. RED before production, GREEN + real surface after. No new CI lane/permanent surface if manual diagnostic probe suffices; record reason. Test-audit gate answers recorded in implementation PR.

STOP rule: writer probe GREEN but APP_SWITCH/gesture still fails (callback decode error or 5000ms timeout) means STOP, capture evidence, amend spec and re-review before further production. No callback suppression or schema edit is approved under this spec.

## Open questions

None blocking. Closure rule (single, consistent): #559 remains open until cutover; close-possible stage means verified source + independent review handoff ready, not closed. Docs PR uses `Refs #559`. Start-gate review and scope acceptance: [Issue record](https://github.com/nunu1733/NunuLauncher/issues/559#issuecomment-6065882809). This is not implementation-completion approval. Truth point: both Issue exit criteria; source change is bug fix, not new feature.

## Change history

- 2026-10-09: Proposed for #559. Spec truth: exact-wire repair only.
- 2026-10-09: Accepted after independent start-gate review; [decision](https://github.com/nunu1733/NunuLauncher/issues/559#issuecomment-6065882809).
