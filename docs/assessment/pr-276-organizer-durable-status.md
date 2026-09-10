# Independent audit: PR #276 durable organizer status projection for re-opened settings (#271)

> Status: accepted
> Audit date: 2026-09-10

- Auditor: 独立監査セッション (ZCode/GLM general-purpose subagent、実装セッションとは別の作業主体。solo保守の独立session規定に基づく)
- PR: https://github.com/nunu1733/NunuLauncher/pull/276
- Head SHA: 34ca55823423dcd76c5f3d16953bf319e7acf815
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34484392559
- Criteria: specs/271-organizer-durable-status-projection/spec.md — DS-AC-01, DS-AC-02, DS-AC-03, DS-AC-04, DS-AC-05, DS-AC-06, DS-AC-07, DS-AC-08

## Scope

対象diffは PR #276 の `6b6bf8dd9f..34ca558234` (base `main`)。20 files changed, +1482/-7。

production code の変更は次の 7 file に限定される:

- `lawnchair/src/app/lawnchair/organizer/application/public/OrganizerDurableStatus.kt` (新規: 閉じた enum `NEVER_ORGANIZED` / `ORGANIZED_RESTORABLE` / `UNRESOLVED` / `RESTORED_OR_EXPIRED` / `UNAVAILABLE`)
- `lawnchair/src/app/lawnchair/organizer/application/lifecycle/OrganizerDurableStatusDeriver.kt` (新規: 純粋導出)
- `lawnchair/src/app/lawnchair/organizer/application/protocol/Ports.kt` (`RecoveryStorePort.readInspectionSnapshot()` + `InspectionSnapshotRead` 追加)
- `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryStore.kt` (同port実装)
- `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` (`durableOrganizerStatus()`)
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` (façade `ManualOrganizationApplication` への読み取り専用 delegate 追加)
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` (Idle/Cancelled での render mapping)

残りは新規/拡張 test (`OrganizerDurableStatusDeriverTest`、`OrganizerDurableStatusInstrumentationTest`、`ManualOrganizationPreferencesInstrumentationTest`、既存 test の façade 実装追加のみ)、EN/ja strings 3 件ずつ、`CONTEXT.md` / `DESIGN.md`、spec/plan。

本PRは `organizer/application/**` (高リスクpath一覧の `lawnchair/src/app/lawnchair/organizer/application/**` に該当) を変更するため、高リスク独立エビデンス契約の適用対象である。監査では次の観点を対象headのdiffと実装に対して独立に確認した:

1. 読み取り専用性: 新規読み取り経路が SQLite open / 書込み / lifecycle遷移 / tombstone purge / journal発行を行わないこと。
2. #89 fence 境界と run-mutex 直列化の維持。
3. deriver の spec 13 retention 語義と plan の導出rule 1–9 の一致。
4. 診断への新規 field / event 追加の不在。
5. projection 型の identity / payload 非漏えい。
6. UI が `ManualOrganizationApplication` façade 経由の閉じた enum のみを読むこと。

### 監査確認結果

**(1) 読み取り専用性 — PASS。** `RecoveryStore.readInspectionSnapshot()` (`RecoveryStore.kt:447-481`) は `snapshotFence.state()` と `snapshotPublisher.reader().read()` のみを通る。`RecoveryInspectionSnapshotReader` は snapshot file の `FileInputStream` 読み取りのみで、SQLite open / probe / write / purge の code path を持たない (source 確認)。`LayoutApplicationModule.durableOrganizerStatus()` (`LayoutApplicationModule.kt:246-278`) は `readInspectionSnapshot()` と `OrganizerDurableStatusDeriver.derive()` のみを呼び、全 failure 経路を `UNAVAILABLE` へ写像し、書込み・発行を行わない。

**(2) #89 fence 境界と run-mutex — PASS。** `readInspectionSnapshot()` は既存 `readInspectionProjection(pointId)` (`RecoveryStore.kt:432-448`) と同一の fence `VALID` + snapshot generation 一致 + 存在しない/stale snapshot は `Unavailable` の pattern そのものである (fence `INCOMPATIBLE` も `Unavailable` へ fail-closed。`InspectionSnapshotRead` には Incompatible variant がなく、spec の fail-closed 語義に合致)。`durableOrganizerStatus()` は module の同一 run mutex (`ordinaryMutex` = apply/recovery protocol と共有の constructor param `mutex`) を `tryAcquire` で非ブロッキング取得し、`finally` で確実に release する。writer 競合は `UNAVAILABLE` (fail-closed)。`readinessGate.runWhenReady(unavailable = ...)` により startup reconciliation 完了前 (`IDLE`/`RECONCILING`/`FAILED`) も fail-closed。

**(3) deriver mapping — PASS。** `OrganizerDurableStatusDeriver.derive()` は plan の導出rule 1–9 と優先順序どおり: (1) 非final lifecycle `{CREATING, READY, APPLYING, COMMITTED_UNVERIFIED, RESTORING}` → `UNRESOLVED`、(2) `CORRUPT`/`INCOMPATIBLE` row → `UNRESOLVED`、(3) `VERIFIED` + checksum 不正 → `UNRESOLVED`、(4) `VERIFIED` かつ `nowMs < createdAtMs + RetentionPolicy.RETENTION_MILLIS` → `ORGANIZED_RESTORABLE`、(5) lapse 後 `VERIFIED` row → `RESTORED_OR_EXPIRED`、(6) `RESTORED`/`EXPIRED` row → `RESTORED_OR_EXPIRED`、(7) retained tombstone `ALREADY_RESTORED`/`EXPIRED` (`expiresAtMs > nowMs`) → `RESTORED_OR_EXPIRED`、(8) retained `CORRUPT`/`INCOMPATIBLE_VERSION` tombstone → `UNRESOLVED`、(9) それ以外 (`PRUNED_UNUSED`/`QUARANTINED` tombstone を含む) → `NEVER_ORGANIZED`。`RetentionPolicy.RETENTION_MILLIS = 86_400_000` (24h, spec 13) を単一sourceとして参照し、literal を複製しない。境界は `nowMs < createdAt + 24h` の排他比較で、`createdAt + 24h` ちょうどでは restorable にならない (DS-AC-04 の語義どおり)。

**(4) 診断 field / event — PASS。** production diff を grep した結果、`RunEvent` / diagnostics / journal への新規 field・phase・発行は存在しない (該当 hit は spec/plan 文書内の記載と既存 test のみ)。

**(5) projection 型の非漏えい — PASS。** `OrganizerDurableStatus` は field を持たない閉じた enum。module 境界を越えるのは enum 値のみで、`DurableRecord`/`DurableTombstone` (pointId / formatVersion を含む snapshot record) は protocol→deriver の内部入力にとどまり、UI / public seam には現れない。

**(6) UI の読み取り経路 — PASS。** UI は `ManualOrganizationRun.readDurableOrganizerStatus()` → `ManualOrganizationApplication` façade → `ProductionManualOrganizationApplication` → module という既存 seam のみを使い、recovery store に直接触れない。render は Idle/Cancelled の場合のみ (`showDurableStatus` を key にした `LaunchedEffect` で状態遷移のたびに再読み取り) で、`ORGANIZED_RESTORABLE` / `RESTORED_OR_EXPIRED` / `UNRESOLVED` のみ行を render し、`NEVER_ORGANIZED` / `UNAVAILABLE` は何も render しない。`UNRESOLVED` は既存 safe-support 行 (`manual_organization_safe_terminal` + diagnostics entry) を再利用。run 中の状態では durable row を render しない。新規文字列は EN/ja 両方に存在する。

## Criteria check

- **DS-AC-01 (PASS)** — `OrganizerDurableStatusInstrumentationTest.verifiedPointIsRestorableAfterProcessDeath` (close/reopen + module-level restart reconciliation 後に `ORGANIZED_RESTORABLE`)。監査セッションで同一 class を emulator 上に独立再実行し 8/8 green。
- **DS-AC-02 (PASS)** — 同 class の `restoredRowPresentsRestoredOrExpiredAfterProcessDeath` (fresh `RESTORED` row) と `expiredRowsEvictedToTombstonesPresentRestoredOrExpired` (eviction 後の `ALREADY_RESTORED`/`EXPIRED` tombstone)。deriver unit test でも両 tombstone reason を直接検証。
- **DS-AC-03 (PASS)** — 同 class の `corruptRowPresentsUnresolvedAfterProcessDeath` / `unreadableVerifiedRowPresentsUnresolvedAfterProcessDeath`; 非final lifecycle と `INCOMPATIBLE` row は deriver unit test (`nonFinalLifecyclesDeriveUnresolved`, `finalCorruptAndIncompatibleRowsDeriveUnresolved`) で網羅。
- **DS-AC-04 (PASS)** — `OrganizerDurableStatusDeriverTest` 16 test: `verifiedAtExactRetentionBoundaryIsNoLongerRestorable` (ちょうど `createdAt + 24h`)、`expiredTombstoneOutsideRetentionDerivesNeverOrganized`、`prunedAndQuarantinedTombstonesDeriveNeverOrganized`、`corruptAndIncompatibleTombstonesDeriveUnresolved`、優先順序 test (`unresolvedRowOutranksARestorableVerifiedRow` 等) を含む。
- **DS-AC-05 (PASS)** — unit: `removingTheRecordInvalidatesTheRestorableProjection`; instrumentation: `prunedCheckpointPresentsNeverOrganized` (record消失後の再導出で restorable にならない) / `unreadableStoreBeforeReconciliationFailsClosed`; UI: `failClosedUnavailableRendersNoDurableRow`。読み取り経路は (1)(2) の確認どおり書込み・journal発行なし。
- **DS-AC-06 (PASS)** — 型は field-free enum (上記 (5))、新規 `RunEvent`/diagnostics 発行なし (上記 (4))、既存 diagnostics 関連 test は diff で触れられていない (façade 実装追加のみ)。
- **DS-AC-07 (PASS)** — `ManualOrganizationPreferencesInstrumentationTest` 38 test (新規 5: `durableRestorableStatusRendersWhileIdle`、`durableUnresolvedStatusRendersSafeSupportGuidance`、`neverOrganizedRendersNoDurableRow`、`failClosedUnavailableRendersNoDurableRow`、`durableStatusIsHiddenWhileARunIsActive`)。監査セッションで独立再実行し 38/38 green。
- **DS-AC-08 (PASS)** — 同一 PR diff に `CONTEXT.md` (「organizer durable status (永続整理状態)」entry)、`DESIGN.md` §4.2 (module が projection seam を所有する旨の1行追記) を含む。

## Executed test surface

監査セッション内で対象 head `34ca55823423dcd76c5f3d16953bf319e7acf815` 上に独立実行:

- `./gradlew spotlessCheck` → BUILD SUCCESSFUL (exit 0)
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` (--rerun で強制再実行) → BUILD SUCCESSFUL (exit 0)。result XML: 89 classes / **981 tests / 0 failures / 0 errors / 0 skipped** (新規 `OrganizerDurableStatusDeriverTest` 16 tests を含む)
- 起動済み emulator (`nunu_qpr2_api36_1` API 36、監査セッションは新規起動せず既存 device を使用) 上に:
  - `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.store.OrganizerDurableStatusInstrumentationTest` → **8 tests / 0 failures / 0 errors** (APK build を含め target head 上で成功)
  - `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.ManualOrganizationPreferencesInstrumentationTest` → **38 tests / 0 failures / 0 errors** (初回試行は test 失敗 0 件のまま BUILD FAILED となった device 側の一過性中断があり、同一 command の再実行で green。test 自体の失敗ではないことを XML で確認)

CI 証拠 (監査実行時点):

- run 34484392559 (CI, `pull_request`, head `34ca558234`): 監査時点で in progress。`check-style` / `organizer-unit-tests` / `build-debug-apk` / `changes` / `validate-repo-contract` は success。instrumentation job 群は pending、`final-status` は未確定。`high-risk-evidence` は本 audit 記録の欠如を理由に fail (本記録の追加がその是正である)。merge operator は同一 commit 上での `CI / final-status` 成功と source job の非skip成功を merge 前に確認すること。

## Findings

- **Blocking: なし。** 監査項目 (1)–(6) と DS-AC-01..08 はすべて対象 head で成立した。
- 参考 (非blocking): spec の frontmatter status は PR head 時点で `accepted` のままである (DS-AC-08 が要求する「status updated in the same PR」の対象を `accepted` への到達と解釈した場合に成立)。workflow の spec status 規則では change を land する PR で `implemented` へ遷移させる先例 (specs/231, specs/89) があるため、merge 時点での `implemented` 遷移を merge operator が確認することが望ましい。
- 参考 (非blocking): deriver の `DurableRecord` は plan の記載より 1 つ多い `updatedAtMs` field を持ち、導出には未使用 (snapshot record からの写像の一貫性のための追加と判断される。境界に影響なし)。
- 未確認範囲: `StrategyPickerInstrumentationTest` / `OrganizerDiagnosticsRouteInstrumentationTest` / `ProductionPublicSeamInstrumentationTest` の instrumentation 再実行は本監査では行っていない (PR 記録の emulator 実行結果と、façade 追加のみの test diff 目視を根拠に扱う)。実機 (物理 device) での process death 再現は未検証 (PR 本文と同様、emulator の close/reopen が restart-equivalent 代理)。

## Overall verdict

**PASS (CI final-status の確定を条件とした承認)** — 対象 head の diff に対し DS-AC-01..08 を独立検証済み。読み取り専用・fail-closed・#89 境界維持・診断不変の主張はいずれも source で成立し、独立再実行 (spotless + 981 unit tests + 46 instrumentation tests) は green。merge gate (`final-status` 成功) と本記録の揃った時点で高リスク独立エビデンス契約を満たす。
