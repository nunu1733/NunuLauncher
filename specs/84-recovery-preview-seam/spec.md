---
issue: "#84"
status: draft
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

`LayoutApplicationModule` は現在、状態を変更する `apply(ValidatedLayoutPlan)` と `recover(RecoveryRequest)` を公開している。しかし、復旧ポイントが現在安全に復旧可能か、また確認後にどの安全な効果が見込まれるかを、書込みや lifecycle 遷移を起こさずに確認する application-owned な操作がない。UI または coordinator が `RecoveryStorePort` を読むことは内部 store・manifest・revision を漏らし、単に状態を知るために `recover(RecoveryRequest)` を呼ぶことは `RESTORING` などの mutation を伴うため許容されない。[Spec 13](../13-safe-layout-application/spec.md) の revision-bound recovery を、明示確認より前に安全に利用できる境界が必要である。

## Outcome

Layout Application module は、**一つの read-only inspection seam** である `inspectRecovery(RecoveryPreviewRequest)` を提供する。呼出し側は application-owned な current-context capability と recovery point ID だけを渡し、型付けされた restorable / non-restorable / unavailable / busy / concurrent の結果を受け取る。成功した inspection は確認用の opaque capability を返すが、layout revision、recovery record、manifest、DB row、payload、item identity を UI-facing code へ公開しない。確認時は既存の `recover(RecoveryRequest)` が依然として全 revision/precondition を再検証するため、preview は recovery を認可しない。

> **Recovery preview** とは、明示的 recovery の前に recovery point と freshly captured current context を read-only に照合し、復旧を試行できる条件と安全なユーザー向け要約だけを返す application operation である。これは backup、undo、または recovery の実行ではない。

## Scope

- `LayoutApplicationModule` が所有する narrow read-only inspection API、closed request/result values、および opaque confirmation capability を追加する。
- canonical current capture が発行する opaque context を用い、inspection 時に non-blocking writer lease 下で current revision と lock-state availability を再確認する。
- recovery-store の availability、record、tombstone、checksum、format、lifecycle を内部で読取り、missing、expired、corrupt、incompatible、already-restored、unresolved を精密に分類する。
- `#52` が preview を表示し、確認時に既存 recovery path へ渡せる最小の安全な handoff を定義する。
- recovery-store mutation、layout write、model reload、lifecycle transition、recovery authorization、および diagnostic event を一切行わないことを test で証明する。

## Non-goals

- `RecoveryStorePort`、`StoredRecord`、recovery manifest、digest、DB row、raw revision、item/profile identity、payload を UI または coordinator の public surface へ公開しない。
- `recover(RecoveryRequest)` の request/result、stale/precondition validation、writer serialization、lifecycle、recovery transaction を変更しない。
- 第2の recovery DB、writer coordinator、backup-based undo、background retry、automatic recovery、retention cleanup を追加しない。
- #52 の UI、orchestration、localized text、diagnostics run-event を実装しない。
- inspection を recovery authorization、checkpoint 生成、model reload、restart reconciliation の代替にしない。

## Domain language

`CONTEXT.md` の **recovery point**、**Layout Snapshot**、および **revision** の意味を変更しない。実装上の `RecoveryPreview` は追加の永続ドメイン概念ではなく、既存 recovery point と current context を照合する一時的な read-only application result であるため、`CONTEXT.md` の更新は不要である。

## Public inspection contract

### Request, context, and confirmation capability

```text
RecoveryPreviewRequest {
    pointId: RecoveryPointId
    context: RecoveryPreviewContext
}

RecoveryPreviewContext
    // opaque, immutable, application-issued capability
    // no public revision, manifest, digest, row, or layout accessor

RecoveryPreviewConfirmation
    // opaque, immutable, single-preview capability
    // retains pointId and the captured expected-current-revision internally
    // no public revision, manifest, digest, row, or layout accessor
```

`RecoveryPreviewContext` is issued only by the existing canonical application/integration capture boundary from a fresh authoritative `CapturedSnapshot`. The UI receives neither this snapshot nor its `RevisionId`; it can retain the opaque context only long enough to submit `RecoveryPreviewRequest`. The coordinator must obtain a new context for every new recovery-preview attempt and must not cache, serialize, log, or reconstruct one.

`inspectRecovery` compares this expected current context with a fresh authoritative capture taken while a non-blocking organizer writer lease is held. This makes a context captured before a user-visible preview detectably stale without requiring a recovery mutation. The input is intentionally not a bare point ID, because a point is only meaningful relative to the current layout the user is about to replace.

A `Restorable` result carries `RecoveryPreviewConfirmation`. The confirmation capability is opaque to UI-facing code. The application-boundary confirmation adapter converts it immediately to the existing `RecoveryRequest(pointId, expectedCurrentRevision)` and invokes the unchanged `recover(RecoveryRequest)` path. This adapter is not a second recovery protocol and does not relax any recovery validation. A caller must never construct a `RecoveryRequest` from a preview by reading a raw revision.

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
  | STALE_REVISION
  | LOCK_STATE_UNAVAILABLE

RecoveryPreviewUnavailable =
  | RECONCILIATION_PENDING
  | RECOVERY_STORE_UNAVAILABLE
```

`RecoveryPreviewSummary` deliberately returns only the stable user-facing effect: a confirmed attempt would seek to restore the saved layout represented by the recovery point. It contains no item count, package/component/title, coordinate, profile, revision, digest, record timestamp, lifecycle value, manifest, or payload. `pointId` is permitted solely as the existing opaque recovery correlation key.

`UNRESOLVED` covers any non-final recovery record whose lifecycle is not independently restorable: `CREATING`, `READY`, `APPLYING`, `COMMITTED_UNVERIFIED`, or `RESTORING`. It does not claim that the point can safely be restored while the existing restart/recovery protocol owns or must reconcile that state. A `VERIFIED` record is the only live record eligible for `Restorable`.

### Read-only protocol and ordering

Inspection shares the module’s readiness gate and `RunMutex`, but it never joins the mutation protocol.

| Step | Required behavior | Result on condition | Persistent effect |
|---|---|---|---|
| I0 | Require completed successful startup reconciliation. | `Unavailable(RECONCILIATION_PENDING)` before readiness; `Unavailable(RECOVERY_STORE_UNAVAILABLE)` after a failed gate. | None. |
| I1 | Attempt the shared run mutex without blocking. | `Concurrent` if another inspect/apply/recover operation holds it. | None. |
| I2 | Probe recovery-store availability and read only the requested record/tombstone. | `NotRestorable(INCOMPATIBLE_VERSION)` for an incompatible format; `Unavailable(RECOVERY_STORE_UNAVAILABLE)` for a read failure; exact tombstone/missing result otherwise. | None. |
| I3 | Validate checksum, supported format, and lifecycle internally. | Exact `NotRestorable` reason; non-restorable non-final lifecycle is `UNRESOLVED`. | None. |
| I4 | Attempt the existing organizer writer lease non-blockingly, then take one authoritative current capture and compare its revision with the opaque context. | `WriterBusy` on lease contention; `NotRestorable(STALE_REVISION)` on mismatch; `NotRestorable(LOCK_STATE_UNAVAILABLE)` for unavailable/unknown lock state. | None. |
| I5 | Build the closed summary and opaque confirmation only when the record is `VERIFIED` and all I0–I4 checks pass. | `Restorable`. | None. |

No inspection step may call `checkpoint`, `markApplying`, `markRestoring`, `advance`, `pruneUnused`, `runRetention`, `applyWriteSet`, `requestCorrelatedReload`, or a diagnostic projection. Reading a tombstone must be made observationally read-only for inspection: its current implementation’s lazy tombstone purge must not be invoked through the preview path. The preview protocol must therefore use a dedicated read-only tombstone lookup or an equivalent read-only store port method; it may not treat a write-capable lookup as inspection.

## Behavior scenarios

### Scenario: Verified point with matching fresh context

Given a recovery point whose record is checksum-valid, supported, and `VERIFIED`, and an opaque `RecoveryPreviewContext` issued from the current authoritative layout,

When the coordinator calls `inspectRecovery` before displaying explicit recovery confirmation,

Then it receives `Restorable` with the closed `RESTORE_SAVED_LAYOUT` summary and opaque confirmation capability,

And no recovery-store/lifecycle write, layout write, reload, authorization, or diagnostic event occurs.

### Scenario: Context becomes stale before inspection

Given a coordinator captured a `RecoveryPreviewContext` and the Launcher layout, profile/device context, or lock state changes before inspection obtains its writer lease,

When the coordinator calls `inspectRecovery`,

Then it receives `NotRestorable(STALE_REVISION)` or `NotRestorable(LOCK_STATE_UNAVAILABLE)` as applicable,

And no persistent change is made; the old context and preview are discarded and a fresh capture is required.

### Scenario: Point is expired, corrupt, unavailable, or already restored

Given the point is absent, has a tombstone, has an invalid checksum, has an unsupported format, has lifecycle `RESTORED`, or the recovery store cannot be read,

When inspection runs,

Then it returns the matching `NotRestorable` or `Unavailable` variant without exposing store contents,

And it does not advance lifecycle, clean up tombstones, or modify the Launcher layout.

### Scenario: Point is unresolved or writer state is changing

Given the point is in `CREATING`, `READY`, `APPLYING`, `COMMITTED_UNVERIFIED`, or `RESTORING`, or another organizer/layout writer holds the required serialization,

When inspection runs,

Then it returns `NotRestorable(UNRESOLVED)`, `Concurrent`, or `WriterBusy` without waiting,

And it does not mutate or authorize recovery.

### Scenario: Preview is confirmed after state changes

Given a prior `Restorable` result and its opaque confirmation capability,

When the user explicitly confirms after the layout changes,

Then the confirmation adapter delegates to the unchanged `recover(RecoveryRequest)` using the captured expected current revision,

And `RecoveryProtocol` performs its normal writer-lease and in-transaction revision/precondition validation, returning `RecoveryResult.NotRestorable(STALE_REVISION)` without mutation when the revision no longer matches.

## Data and state

Inspection reads only the recovery store’s existing availability, requested record/tombstone metadata, and canonical authoritative current capture. Recovery manifests, digests, and raw rows remain protocol-private inputs to the existing writer/recovery implementation and are never projected into the public result. The `RecoveryPreviewContext` and `RecoveryPreviewConfirmation` are in-memory, opaque, non-serializable capabilities; they create no recovery-store row, no retention entry, no schema/format version, and no backup/restore artifact.

There is no Launcher schema migration, recovery DB migration, retention change, rollback action, or backup behavior change. If the feature is later reverted, already stored recovery points and the existing `recover(RecoveryRequest)` behavior remain intact. A failed or stale preview leaves both databases, model state, lifecycle, and diagnostics journal unchanged.

## Permissions, privacy, and security

None. This feature introduces no permission, network transport, telemetry, external storage, or export. The result types have no fields capable of carrying recovery manifest content, digest/revision values, row data, package/component/title, profile identity, coordinates, raw exception text, or free-form text. Inspection emits no diagnostics event and does not create a synthetic run or lifecycle transition. If a later product requirement needs preview telemetry, it must first extend the diagnostics contract with an accepted privacy review; this specification does not authorize that change.

## Accessibility and localization

#84 provides typed status only; it does not add UI. Its closed result categories must remain sufficient for #52 to map `Restorable`, stale, unavailable, busy, concurrent, and each non-restorable reason to localized, non-color-only user feedback. `RecoveryPreviewSummary` avoids user-visible sensitive detail and explicitly marks confirmation as required. Focus, TalkBack, font scaling, and localized rendering are owned by #52 and are not implemented here.

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| RP-AC-01 | `spec.md` and `plan.md` define exactly one application-owned read-only inspection operation, its opaque request/context/confirmation handoff, and the closed `RecoveryPreviewResult` surface without changing `apply` or `recover(RecoveryRequest)` semantics. |
| RP-AC-02 | A checksum-valid `VERIFIED` recovery point plus a matching freshly captured context returns `Restorable` with only `RESTORE_SAVED_LAYOUT`, confirmation-required, and revision-conditional metadata. |
| RP-AC-03 | Missing, expired, corrupt, incompatible, already-restored, unresolved, stale, lock-unavailable, store-unavailable, writer-busy, and concurrent cases return their specified non-write typed result. |
| RP-AC-04 | Inspection performs zero recovery-store/lifecycle writes, layout writes, model reloads, recovery authorizations, retention/prune operations, and diagnostic emissions on every result path. |
| RP-AC-05 | Public/UI-facing values cannot access `RecoveryStorePort`, `StoredRecord`, manifest, DB row, payload, digest, raw `RevisionId`, item/profile identity, or a mutable writer/lease capability. |
| RP-AC-06 | A preview confirmation remains conditional: the existing `recover(RecoveryRequest)` path rechecks current revision and exact preconditions after explicit confirmation, rejecting stale state without mutation. |
| RP-AC-07 | Preview only observes an existing writer lease non-blockingly; it does not wait for, create, transfer, or authorize a lease. |
| RP-AC-08 | Repository-contract checks, focused JVM/contract tests, formatting, and the debug build appropriate to changed source pass and are recorded before the implementation PR is proposed. |

## Test oracle

| AC | Automated/manual evidence |
|---|---|
| RP-AC-01 | Public-shape contract test enumerates the new closed preview values and verifies existing `RecoveryRequest`/`RecoveryResult` shapes are unchanged; spec/plan review confirms the only behavioral entry is module-owned inspection. |
| RP-AC-02 | `RecoveryPreviewProtocolTest` seeds a `VERIFIED` fake record and matching canonical context, then asserts `Restorable` and the exact safe summary fields. |
| RP-AC-03 | Parameterized JVM matrix over record, tombstone, store availability, lifecycle, stale context, lock availability, mutex, and writer-lease fixtures. |
| RP-AC-04 | Fake writer/store/diagnostics counters and production-adapter integration evidence assert no calls to write, reload, lifecycle, cleanup, or diagnostics surfaces for every matrix row. |
| RP-AC-05 | Public API/source-boundary test rejects Android/SQLite/internal recovery-store imports and forbidden public fields/accessors; negative serialization/reflection tests verify opaque capabilities reveal no revision/payload data. |
| RP-AC-06 | Confirmation handoff test changes every revision dimension after `Restorable`, then exercises the existing recovery protocol and observes its ordinary stale rejection with zero committed writes. |
| RP-AC-07 | Concurrent mutex and external-lease tests assert immediate `Concurrent` / `WriterBusy` and unchanged counters. |
| RP-AC-08 | Repository validator/self-tests, organizer JVM test filter, `spotlessCheck`, and documented debug assembly output are attached to the implementation PR. |

## Open questions

None. The Stage A decision is that a preview consumes an opaque application-issued current-context capability, returns an opaque confirmation capability, and delegates confirmation to the unchanged recovery protocol. If implementation shows that this cannot be expressed without exposing a raw revision or adding a public mutation protocol, Stage B must stop and open the owning application-contract follow-up rather than weakening this boundary.

## Change history

- 2026-08-19: Drafted for Issue #84 Stage A; production implementation is explicitly blocked pending spec/plan acceptance.

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
