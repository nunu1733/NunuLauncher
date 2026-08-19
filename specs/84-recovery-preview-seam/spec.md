---
issue: "#84"
status: accepted
requirements:
  - FR-004
  - FR-005
  - FR-006
  - NFR-001
  - NFR-002
  - NFR-007
  - NFR-011
risk:
  - layout-data
updated: 2026-08-19
---

# Read-only revision-bound recovery preview

## Problem

`LayoutApplicationModule` は現在、状態を変更する `apply(ValidatedLayoutPlan)` と `recover(RecoveryRequest)` を公開している。しかし、復旧ポイントが現在安全に復旧可能か、明示確認後にどの安全な効果が見込まれるかを、書込みや lifecycle 遷移を起こさずに確認する application-owned な操作がない。UI または coordinator が `RecoveryStorePort` を読むことは内部 store・manifest・revision を漏らし、単に状態を知るために `recover(RecoveryRequest)` を呼ぶことは `RESTORING` などの mutation を伴うため許容されない。[Spec 13](../13-safe-layout-application/spec.md) の revision-bound recovery を、明示確認より前に安全に利用できる境界が必要である。

## Outcome

Layout Application module は、**一つの read-only inspection seam** である `inspectRecovery(pointId: RecoveryPointId)` を提供する。この operation 自身が non-blocking writer serialization lease を取得して current revision を内部 capture し、型付けされた restorable / non-restorable / unavailable / busy / concurrent の結果を返す。成功結果は confirmation capability を含むが、layout revision、recovery record、manifest、DB row、payload、item identity を UI-facing code へ公開しない。

confirmation capability は application 内部でのみ消費され、その場で既存の `RecoveryRequest(pointId, expectedCurrentRevision)` を生成して、**変更しない LayoutApplicationModule の application-level recovery behavior** へ delegation する。変換後の request は coordinator/UI へ返さない。この behavior は public `recover(RecoveryRequest)` と同じ readiness/reconciliation gate、`RECOVERY_REQUESTED`、`RecoveryProtocol.recover`、terminal recovery diagnostics を所有する。したがって preview は recovery を認可せず、確認時の recovery は従来どおり current revision、retention、完全な precondition を再検証する。

> **Recovery preview** とは、明示的 recovery の前に recovery point と、operation 自身が writer serialization 下で取得した current context を read-only に照合し、復旧を試行できる条件と安全なユーザー向け要約だけを返す application operation である。これは backup、undo、または recovery の実行ではない。

## Scope

- `LayoutApplicationModule` が所有する narrow read-only inspection API、closed result values、および opaque confirmation capability を追加する。
- inspection が non-blocking organizer writer lease 下で authoritative current capture を一度だけ行い、その revision を confirmation capability 内に閉じ込める。
- recovery-store の availability、record、tombstone、checksum、format、lifecycle、retention age を内部で読取り、missing、expired、corrupt、unavailable、already-restored、unresolved を精密に分類する。
- verified record の 24-hour expiry と tombstone の 24-hour retention expiry を、`Clock` と pure `RetentionPolicy.actionFor()` により**書込みなし**で論理的に評価する。
- `#52` が preview を表示し、確認時に既存 recovery path へ渡せる最小の安全な internal handoff を定義する。
- recovery-store mutation、layout write、model reload、lifecycle transition、retention cleanup、recovery authorization、diagnostic event を一切行わないことを test で証明する。

## Non-goals

- `RecoveryStorePort`、`StoredRecord`、recovery manifest、digest、DB row、raw revision、item/profile identity、payload を UI または coordinator の public surface へ公開しない。
- `recover(RecoveryRequest)` の public request/result、writer serialization、transaction、stale/precondition validation を変更しない。既存 retention contract を実装で再評価することは、この固定済み意味を正しく適用するための内部補正である。
- 第2の recovery DB、writer coordinator、backup-based undo、background retry、automatic recovery、retention cleanup を追加しない。
- #52 の UI、orchestration、localized text、diagnostics run-event を実装しない。
- inspection を recovery authorization、checkpoint 生成、model reload、restart reconciliation の代替にしない。

## Domain language

`CONTEXT.md` の **recovery point**、**Layout Snapshot**、および **revision** の意味を変更しない。実装上の `RecoveryPreview` は追加の永続ドメイン概念ではなく、既存 recovery point と current context を照合する一時的な read-only application result であるため、`CONTEXT.md` の更新は不要である。

## Public inspection contract

### Inspection and opaque confirmation

```text
inspectRecovery(pointId: RecoveryPointId) -> RecoveryPreviewResult

RecoveryPreviewConfirmation
    // opaque, immutable, single-preview capability
    // internally retains pointId and a freshly captured expected-current-revision
    // has no public revision, manifest, digest, row, or layout accessor
```

`inspectRecovery` does **not** accept a caller-issued context. `LayoutWriterPort.captureCurrent()` requires a freshly acquired lease, and no accepted preview-capture seam exists outside the application module. The operation therefore acquires the existing organizer serialization lease non-blockingly, performs the authoritative capture itself, and returns `WriterBusy` if it cannot do so.

A `Restorable` result carries `RecoveryPreviewConfirmation`. The capability may be retained by the UI only as an opaque value until explicit confirmation; it must not be serialized, logged, reconstructed, or inspected. `LayoutApplicationModule` consumes it internally, creates the existing `RecoveryRequest` in local scope, and invokes the same private application-level recovery behavior used by public `recover(RecoveryRequest)`. That behavior reapplies readiness/reconciliation gating, emits `RECOVERY_REQUESTED`, runs `RecoveryProtocol.recover`, emits the matching terminal recovery diagnostic, and returns only `RecoveryResult` to the outside caller. The `RecoveryRequest` and its raw expected revision never leave the application boundary through this preview path.

### Result surface

```text
RecoveryPreviewResult =
  | Restorable {
      pointId: RecoveryPointId,
      summary: RecoveryPreviewSummary,
      confirmation: RecoveryPreviewConfirmation
    }
  | NotRestorable {
      pointId: RecoveryPointId,
      reason: RecoveryPreviewRejection
    }
  | Unavailable {
      pointId: RecoveryPointId,
      reason: RecoveryPreviewUnavailable
    }
  | WriterBusy
  | Concurrent

RecoveryPreviewSummary {
    effect: RESTORE_SAVED_LAYOUT
    confirmationRequired: true
    conditionalOnCurrentRevision: true
}

RecoveryPreviewRejection =
  | MISSING
  | EXPIRED
  | CORRUPT
  | INCOMPATIBLE_VERSION
  | ALREADY_RESTORED
  | UNRESOLVED
  | LOCK_STATE_UNAVAILABLE

RecoveryPreviewUnavailable =
  | RECONCILIATION_PENDING
  | RECOVERY_STORE_UNAVAILABLE
```

`RecoveryPreviewSummary` deliberately returns only the stable user-facing effect: a confirmed attempt would seek to restore the saved layout represented by the recovery point. It contains no item count, package/component/title, coordinate, profile, revision, digest, record timestamp, lifecycle value, manifest, or payload. `pointId` is permitted solely as the existing opaque recovery correlation key.

`UNRESOLVED` covers any non-final recovery record whose lifecycle is not independently restorable: `CREATING`, `READY`, `APPLYING`, `COMMITTED_UNVERIFIED`, or `RESTORING`. It does not claim that the point can safely be restored while the existing restart/recovery protocol owns or must reconcile that state. A live `VERIFIED` record is eligible for `Restorable` only before its 24-hour retention boundary.

The inspection result intentionally has no stale variant. Because inspection obtains current state itself under the writer lease, it never accepts a stale caller context. The stale case is instead the required preview-to-confirmation TOCTOU: after a `Restorable` result, a subsequent layout/context change causes the unchanged `recover(RecoveryRequest)` path to return `RecoveryResult.NotRestorable(STALE_REVISION)` before mutation. This is a typed, zero-write outcome and is testable through confirmation delegation.

### Read-only protocol and ordering

Inspection shares the module’s readiness gate and `RunMutex`, but it never joins the mutation protocol.

| Step | Required behavior | Result on condition | Persistent effect |
|---|---|---|---|
| I0 | Require completed successful startup reconciliation. | `Unavailable(RECONCILIATION_PENDING)` before readiness; `Unavailable(RECOVERY_STORE_UNAVAILABLE)` after a failed gate. | None. |
| I1 | Attempt the shared run mutex without blocking. | `Concurrent` if another inspect/apply/recover operation holds it. | None. |
| I2 | Probe recovery-store availability and read only the requested record/tombstone. Tombstone lookup is an inspection-only readable query with no lazy purge. | `NotRestorable(INCOMPATIBLE_VERSION)` for an incompatible store; `Unavailable(RECOVERY_STORE_UNAVAILABLE)` for a read failure; exact missing/tombstone result otherwise. | None. |
| I3 | Validate checksum, supported format, lifecycle, and logical retention using `Clock` plus pure `RetentionPolicy.actionFor()`. | `EXPIRED` for an aged `VERIFIED` record; `MISSING` for a tombstone past its retained expiry; exact remaining non-restorable reason otherwise. | None. |
| I4 | Acquire the existing organizer writer serialization lease **non-blockingly**. The lease is short-lived and permits only one authoritative current capture. | `WriterBusy` on lease contention. | None. |
| I5 | Under that lease, capture current state once and fail closed if lock state is unavailable/unknown. Build the closed summary and opaque confirmation only for a non-expired `VERIFIED` point. Release the lease and mutex in `finally`. | `NotRestorable(LOCK_STATE_UNAVAILABLE)` or `Restorable`. | None. |

The acquired lease is not merely observed: it is acquired non-blockingly to make the authoritative capture coherent with external writers. While held by inspection it **must not** call `checkpoint`, `markApplying`, `markRestoring`, `advance`, `pruneUnused`, `runRetention`, `applyWriteSet`, `requestCorrelatedReload`, or a diagnostic projection. It must not transfer, queue, or retain the lease after returning.

### Retention and confirmation-time TOCTOU

The recovery retention contract is temporal even when cleanup is lazy. Inspection must not make an aged point appear restorable merely because no writer has run retention.

| Observed read-only state at `Clock.nowMillis()` | Inspection result | Write behavior |
|---|---|---|
| `VERIFIED` with `RetentionPolicy.actionFor(...) == Expire(AGE_RETENTION)` | `NotRestorable(EXPIRED)` | No tombstone write or lifecycle update. |
| Live non-final lifecycle other than eligible `VERIFIED` | `NotRestorable(UNRESOLVED)` | No reconciliation or cleanup. |
| Existing tombstone whose `expiresAtMs <= now` | `NotRestorable(MISSING)` | No purge. |
| Existing tombstone still retained | Matching `EXPIRED`, `CORRUPT`, `INCOMPATIBLE_VERSION`, or `ALREADY_RESTORED` | No purge. |
| `Restorable` confirmation crosses retention boundary before confirm | Existing recovery preflight returns `RecoveryResult.NotRestorable(EXPIRED)` before `RESTORING`/layout mutation. | No lifecycle/layout write. |
| `Restorable` confirmation becomes stale before confirm | Existing recovery preflight/in-transaction validation returns `RecoveryResult.NotRestorable(STALE_REVISION)` before mutation. | No lifecycle/layout write. |

`RecoveryProtocol` must use the same `Clock` and pure retention classification during its preflight, including tombstone expiry. This changes neither the public `RecoveryRequest` nor `RecoveryResult`; it ensures the already accepted 24-hour rule is enforced at the final mutation boundary.

## Behavior scenarios

### Scenario: Verified point inspected under current writer serialization

Given a recovery point whose record is checksum-valid, supported, unexpired, and `VERIFIED`,

When the coordinator calls `inspectRecovery(pointId)` before displaying explicit recovery confirmation,

Then the module non-blockingly acquires the existing writer serialization lease, captures current state internally, and returns `Restorable` with the closed `RESTORE_SAVED_LAYOUT` summary and opaque confirmation capability,

And no recovery-store/lifecycle write, layout write, reload, authorization, retention cleanup, or diagnostic event occurs.

### Scenario: Point is expired, corrupt, unavailable, or already restored

Given the point is absent, has a retained tombstone, has an invalid checksum, has an unsupported format, has lifecycle `RESTORED`, is an aged `VERIFIED` record, has an expired tombstone, or the recovery store cannot be read,

When inspection runs,

Then it returns the matching `NotRestorable` or `Unavailable` variant without exposing store contents,

And it does not advance lifecycle, clean up tombstones, or modify the Launcher layout.

### Scenario: Point is unresolved or writer state is changing

Given the point is in `CREATING`, `READY`, `APPLYING`, `COMMITTED_UNVERIFIED`, or `RESTORING`, or another organizer/layout writer holds the required serialization,

When inspection runs,

Then it returns `NotRestorable(UNRESOLVED)`, `Concurrent`, or `WriterBusy` without waiting,

And it does not mutate or authorize recovery.

### Scenario: Preview is confirmed after retention or state changes

Given a prior `Restorable` result and its opaque confirmation capability,

When the user explicitly confirms after the 24-hour retention boundary or after the layout/context changes,

Then `LayoutApplicationModule` consumes the capability and uses the same private application-level recovery behavior as public `recover(RecoveryRequest)`, without exposing the locally constructed request,

And that behavior re-applies readiness/reconciliation and existing requested/terminal recovery diagnostics before `RecoveryProtocol` re-evaluates retention and current revision/preconditions, returning `NotRestorable(EXPIRED)` or `NotRestorable(STALE_REVISION)` without lifecycle or layout mutation as applicable.

## Data and state

Inspection reads only the recovery store’s existing availability, requested record/tombstone metadata, `Clock`, pure retention policy, and one authoritative current capture. Recovery manifests, digests, and raw rows remain protocol-private inputs to the existing writer/recovery implementation and are never projected into the public result. `RecoveryPreviewConfirmation` is an in-memory, opaque, non-serializable capability; it creates no recovery-store row, no retention entry, no schema/format version, and no backup/restore artifact.

There is no Launcher schema migration, recovery DB migration, retention-policy change, rollback action, or backup behavior change. The implementation adds a read-only tombstone lookup and makes existing recovery preflight enforce the already accepted retention boundary. A failed, expired, stale, or busy preview leaves both databases, model state, lifecycle, and diagnostics journal unchanged.

## Permissions, privacy, and security

None. This feature introduces no permission, network transport, telemetry, external storage, or export. The result types have no fields capable of carrying recovery manifest content, digest/revision values, row data, package/component/title, profile identity, coordinates, raw exception text, or free-form text. Inspection emits no diagnostics event and does not create a synthetic run or lifecycle transition. If a later product requirement needs preview telemetry, it must first extend the diagnostics contract with an accepted privacy review; this specification does not authorize that change.

## Accessibility and localization

#84 provides typed status only; it does not add UI. Its closed result categories must remain sufficient for #52 to map `Restorable`, expiry, unavailable, busy, concurrent, and each non-restorable reason to localized, non-color-only user feedback. `RecoveryPreviewSummary` avoids user-visible sensitive detail and explicitly marks confirmation as required. Focus, TalkBack, font scaling, and localized rendering are owned by #52 and are not implemented here.

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| RP-AC-01 | `spec.md` and `plan.md` define exactly one application-owned read-only inspection operation, its opaque confirmation handoff, and the closed `RecoveryPreviewResult` surface without changing the public `apply` or `recover(RecoveryRequest)` contracts. |
| RP-AC-02 | A checksum-valid, supported, unexpired `VERIFIED` recovery point returns `Restorable` with only `RESTORE_SAVED_LAYOUT`, confirmation-required, and revision-conditional metadata. |
| RP-AC-03 | Missing, expired live record, expired tombstone, corrupt, incompatible, already-restored, unresolved, store-unavailable, writer-busy, concurrent, and lock-unavailable cases return their specified non-write typed result. |
| RP-AC-04 | Inspection performs zero recovery-store/lifecycle writes, retention/prune operations, layout writes, model reloads, recovery authorizations, and diagnostic emissions on every result path. |
| RP-AC-05 | Public/UI-facing values cannot access `RecoveryStorePort`, `StoredRecord`, manifest, DB row, payload, digest, raw `RevisionId`, item/profile identity, or a mutable writer/lease capability; confirmation delegation never returns `RecoveryRequest`. |
| RP-AC-06 | A preview confirmation remains conditional: the same private application-level recovery behavior used by public `recover(RecoveryRequest)` re-applies readiness/reconciliation and existing recovery diagnostics, then re-evaluates retention and rechecks current revision/exact preconditions after explicit confirmation, rejecting expired or stale state without mutation. |
| RP-AC-07 | Inspection non-blockingly acquires the existing writer serialization lease solely for one authoritative capture, then releases it; it never waits, queues, transfers, or uses that lease for a write/reload/store lifecycle operation. |
| RP-AC-08 | Repository-contract checks, focused JVM/contract tests, formatting, and the debug build appropriate to changed source pass and are recorded before the implementation PR is proposed. |

## Test oracle

| AC | Automated/manual evidence |
|---|---|
| RP-AC-01 | Public-shape contract test enumerates new closed preview values and verifies existing `RecoveryRequest`/`RecoveryResult` shapes are unchanged; spec/plan review confirms the only new public behavioral entry is module-owned inspection. |
| RP-AC-02 | `RecoveryPreviewProtocolTest` seeds an unexpired `VERIFIED` fake record and asserts `Restorable` plus the exact safe summary fields. |
| RP-AC-03 | Parameterized JVM matrix over record/tombstone retention ages, store availability, lifecycle, checksum/format, lock availability, mutex, and writer-lease fixtures. |
| RP-AC-04 | Fake writer/store/diagnostics counters and production-adapter integration evidence assert no calls to write, reload, lifecycle, cleanup, or diagnostics surfaces for every matrix row. |
| RP-AC-05 | Public API/source-boundary test rejects Android/SQLite/internal recovery-store imports and forbidden public fields/accessors; negative serialization/reflection tests verify confirmation reveals no revision/payload and confirmation never returns `RecoveryRequest`. |
| RP-AC-06 | Confirmation-handoff tests cross the retention cutoff and change every revision dimension after `Restorable`, then observe the shared application-level recovery behavior’s requested/terminal diagnostics and typed expiry/stale rejection with zero committed writes. |
| RP-AC-07 | Held `RunMutex` and refused external lease fixtures assert immediate `Concurrent` / `WriterBusy`; capture-only counter assertions prove lease scope and release. |
| RP-AC-08 | Repository validator/self-tests, organizer JVM test filter, `spotlessCheck`, and documented debug assembly output are attached to the implementation PR. |

## Open questions

None. Stage A decides that inspection self-captures current context under non-blocking writer serialization, keeps the revision inside an opaque confirmation capability, and routes confirmation through the same private application-level recovery behavior used by unchanged public `recover(RecoveryRequest)`. It also decides that preview and confirmation both enforce the accepted retention deadline without a cleanup write. If implementation shows that this cannot be expressed without exposing a raw revision, bypassing readiness/diagnostics, or adding a public mutation protocol, Stage B must stop and open the owning application-contract follow-up rather than weakening this boundary.

## Change history

- 2026-08-19: Drafted for Issue #84 Stage A; production implementation is explicitly blocked pending spec/plan acceptance.
- 2026-08-19: Changes-requested revision: removed the unowned pre-captured context, made the existing writer lease explicitly non-blocking/capture-only, kept `RecoveryRequest` internal during confirmation delegation, and closed read-only expiry/confirm-time retention semantics.
- 2026-08-19: P0 revision: requires confirmation to share the existing application-level recovery behavior, including readiness/reconciliation and requested/terminal diagnostics; direct `RecoveryProtocol` invocation is prohibited.
- 2026-08-19: Stage A accepted by the Issue #84 owner; Stage B may begin only within this specification and plan.

## References

- [Issue #84: read-only revision-bound recovery preview](https://github.com/nunu1733/NunuLauncher/issues/84)
- [AGENTS.md: source-of-truth, safety, and quality rules](../../AGENTS.md)
- [CONTEXT.md: organizer domain language](../../CONTEXT.md)
- [DESIGN.md: Layout Application module and invariants](../../DESIGN.md)
- [Spec 13: safe layout application and recovery](../13-safe-layout-application/spec.md)
- [ADR-0003: separate private recovery-point database](../../docs/adr/0003-organizer-recovery-point-storage.md)
- [Organizer diagnostics contract](../../docs/engineering/organizer-diagnostics.md)
- [Issue #52 working specification](https://github.com/nunu1733/NunuLauncher/blob/issue-52-manual-full-organization-vertical-slice/specs/52-manual-full-organization-vertical-slice/spec.md)
- [Quality strategy](../../docs/engineering/quality-strategy.md)
