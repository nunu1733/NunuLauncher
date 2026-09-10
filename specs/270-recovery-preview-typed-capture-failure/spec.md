---
issue: "#270"
status: draft
requirements: [TC-AC-01, TC-AC-02, TC-AC-03, TC-AC-04, TC-AC-05]
risk: []
updated: 2026-09-10
---

# Recovery preview が capture 失敗を untyped 例外ではなく closed 型付き結果として返す

## Problem

[Issue #270](https://github.com/nunu1733/NunuLauncher/issues/270)。#265 の受入済み two-path 再現 ([調査コメント](https://github.com/nunu1733/NunuLauncher/issues/265)) により、次の失敗境界が確定している。

organizer 適用後の folder 子 item をユーザーが workspace へ手動移動すると、`favorites` には span が NULL の desktop row が残る（`ModelWriter.moveItemInDatabase` は SPANX/SPANY を書かない）。この row 形は canonical capture が表現できず、`RowManifestCodec.toCanonical` の `requireNotNull` が `IllegalArgumentException` を throw する。

その capture は recovery preview 内で実行される: `RecoveryPreviewProtocol.inspect()` は organizer writer lease 下で `writer.captureCurrent()` を呼び（`RecoveryPreviewProtocol.kt:80`）、例外を catch しない。例外は `LayoutApplicationModule.inspectRecovery` → `ManualOrganizationRun.beginRecoveryPreview()`（`ManualOrganizationRun.kt:458` の rethrow）を通過し、Settings の `execute {}` coroutine（`scope.launch`）を uncaught で死なせる。ユーザーから見えるのは「Restore layout が無反応で死ぬ」ことであり、型付きの状態は一切表示されない。

preview seam の契約 ([spec 84](../84-recovery-preview-seam/spec.md)) は `inspectRecovery` が closed な `RecoveryPreviewResult` を返すことにあるが、capture 失敗の path だけがこの閉じた表面を通らず例外として漏れている。

## Outcome

`RecoveryPreviewProtocol.inspect()` は capture 失敗を既存の型付き表面へマップし、`Unavailable`（store 不availability と区別できる独立 code）を返す。Settings coroutine は生存し、既存 UI が failure message を描画して cancel を提供する。capture 失敗時は `Restorable` も confirmation capability も発行されないため、recovery protocol は capture 不能な layout に対して restore を試行しない — fail-closed は維持される。

## Scope

- `RecoveryPreviewProtocol` の capture 呼び出し（lease 保持下の単一 capture）を `RuntimeException` に対して catch し、typed result へマップする。新規 code は `RecoveryPreviewUnavailable` へ追加する。
- `RecoveryPreviewUnavailable` の閉じた列挙に 1 value を追加する（public surface 拡張）。既存 2 値の意味は変更しない。
- capture 失敗を決定論的に注入する test fixture hook（`FakeLayoutWriter` の test knob）と、protocol seam の failure-path test を追加する。注入は「capture が unrepresentable row を拒否した」ことを proxy する防御層であり、どの row 表現判断（#269）にも依存しない。
- [spec 84](../84-recovery-preview-seam/spec.md) の result surface 記述へ新 value を反映し、change history に記録する（同一 PR 内。矛盾する正本を残さないため）。

## Non-goals

- `RowManifestCodec` の canonical capture strictness、NULL-span desktop row の表現判断、typed `CaptureFailureCategory` への置換 — issue #269 の spec 判断に属する。
- `RecoveryProtocol.recover` 内の capture（mutation path、`RecoveryProtocol.kt:113`）と `ApplyProtocol` の capture 契約 — preview seam ではない。
- Settings status の persistence、retry UX、`WriterBusy` surface、`ManualOrganizationRun` の例外 rethrow 構造（capture 失敗以外の例外は従来どおり rethrow される）。
- `PlanPreviewProtocol` の変更 — 既に `NotPlannable(CAPTURE_FAILED)` へマップ済みであり leak は存在しない（plan で source-verified して記録する）。
- diagnostics event、log 追加、recovery store / Launcher DB / schema への一切の書込み。

## Domain language

用語の追加・変更なし。`CONTEXT.md` の更新は不要。

## Behavior scenarios

### Scenario: capture 失敗が型付き Unavailable として返る

Given checksum 有効・supported・未期限・`VERIFIED` の recovery point があり、current layout の capture が unrepresentable row を理由に `RuntimeException` で失敗する（#265 の row 形を proxy する注入）,

When `inspectRecovery(pointId)` が呼ばれる,

Then lease は取得・解放され、結果は `Unavailable(pointId, <capture 失敗の新 code>)` であり、`RECOVERY_STORE_UNAVAILABLE` とは区別できる,

And recovery store の読み書き、lifecycle 遷移、layout 書込み、reload、diagnostics は発生しない。

### Scenario: Settings coroutine が生存し failure を描画する

Given 前シナリオと同一の失敗状態がある,

When ユーザーが Restore layout を押して `beginRecoveryPreview()` が実行される,

Then preview は例外を再throw せず、run state は `State.RecoveryPreview(Unavailable(...))` へ遷移する,

And 既存 UI は not-available message を描画し、confirm は表示されず、cancel が提供される。

### Scenario: 正常 capture は従来どおり Restorable

Given capture が成功し、lock state が判明可能な `VERIFIED` point がある,

When `inspectRecovery(pointId)` が呼ばれる,

Then 従来どおり `Restorable` と confirmation capability が返る（非回帰）。

### Scenario: lock state 不明は従来どおり NotRestorable

Given capture は成功するが `OrganizerLockState.UNKNOWN` の item がある,

When `inspectRecovery(pointId)` が呼ばれる,

Then `NotRestorable(LOCK_STATE_UNAVAILABLE)` が返り、capture 失敗の Unavailable code にはならない（両 failure code の区別は維持される）。

## Data and state

- 読み取る data は spec 84 と同一: #89 inspection projection、`Clock`、pure retention policy、1 回の authoritative capture。
- 永続化・migration・rollback への影響なし。全 path で zero write であることを既存の `assertNoInspectionMutation` 型 counter で継続検証する。
- capture 失敗 path では confirmation registry への登録も発生しない（`Restorable` を返さないため）。

## Permissions, privacy, and security

None。新 surface 値は enum 定数であり、例外 message・row 内容・自由文字列を運ばない。spec 84 の closed result 制約（raw revision・manifest・row data を UI へ出さない）は維持される。

## Accessibility and localization

UI 変更なし。`Unavailable` variant は既存の `manual_organization_recovery_not_available` 文言で描画され、 TalkBack / focus 挙動は現行の `State.RecoveryPreview` path と同一である。

## Acceptance criteria

- [ ] TC-AC-01: capture が `RuntimeException` で失敗した `inspectRecovery` は、例外を漏らさず `Unavailable(pointId, <新 code>)` を返す。新 code は `RECOVERY_STORE_UNAVAILABLE` と区別可能である。
- [ ] TC-AC-02: capture 失敗 path で、lease は解放され、recovery store 読み書き・lifecycle 遷移・layout 書込み・reload・retention 操作・diagnostics が 0 回である。
- [ ] TC-AC-03: capture 失敗時は `Restorable` も confirmation capability も発行されない（fail-closed の維持）。
- [ ] TC-AC-04: 正常 capture（`Restorable`）と lock-state 不明（`NotRestorable(LOCK_STATE_UNAVAILABLE)`）の既存 path は不変である。
- [ ] TC-AC-05: 注入は決定論的である（network・時間・scheduler に依存しない JVM test）であり、#269 の row 表現判断に依存しない防御層として spec 84 の result surface 記述へ新 value が反映されている。

## Test oracle

| AC | Evidence |
|---|---|
| TC-AC-01 | `RecoveryPreviewProtocolTest` に capture 失敗注入 case を追加: `Unavailable(pointId, CURRENT_LAYOUT_CAPTURE_UNAVAILABLE)` を assert |
| TC-AC-02 | 同 test で `assertNoInspectionMutation()` と writer counter（lease 解放は `capturedSnapshots` と mutex 解放で確認）を assert |
| TC-AC-03 | 同 test で result variant が `Unavailable` であること（`Restorable` でないこと）を直接 assert |
| TC-AC-04 | 既存 `verifiedUnexpiredPointReturnsSafeRestorablePreviewWithoutMutation` / `unknownLockStateReturnsTypedRejectionWithoutMutation` が変更なしで pass |
| TC-AC-05 | `FakeLayoutWriter` の決定論的 capture-failure knob（lambda hook）で注入; `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.protocol.RecoveryPreviewProtocolTest'` の成功を PR へ記録 |

## Open questions

None。型付き結果の variant は `Unavailable`（capture 失敗は point の非復旧性を主張せず「現状を検証できない」ことを意味する）とする判断は、`NotRestorable` の reason 集合が recovery point 状態の分類に使われている現行意味論と、issue 270 が「code が store unavailability と区別可能」なことだけを要求することに基づく。
