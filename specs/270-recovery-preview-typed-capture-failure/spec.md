---
issue: "#270"
status: implemented
requirements: [TC-AC-01, TC-AC-02, TC-AC-03, TC-AC-04, TC-AC-05, TC-AC-06]
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
- [spec 84](../84-recovery-preview-seam/spec.md) の result surface 記述（`RecoveryPreviewUnavailable` 列挙と I5 の結果表）へ新 value を反映し、change history に記録する（同一 PR 内。矛盾する正本を残さないため）。

## Non-goals

- `RowManifestCodec` の canonical capture strictness、NULL-span desktop row の表現判断、typed `CaptureFailureCategory` への置換 — issue #269 の spec 判断に属する。
- `RecoveryProtocol.recover` 内の capture（mutation path、`RecoveryProtocol.kt:113`）と `ApplyProtocol` の capture 契約 — preview seam ではない。
- Settings status の persistence、retry UX、`WriterBusy` surface、`ManualOrganizationRun` の例外 rethrow 構造（capture 失敗以外の例外は従来どおり rethrow される）。
- `PlanPreviewProtocol` の変更 — 既に `NotPlannable(CAPTURE_FAILED)` へマップ済みであり leak は存在しない（plan で source-verified して記録する）。
- diagnostics event、log 追加、recovery store / Launcher DB / schema への一切の書込み。
- preview 成功後に confirm 時点で layout が capture 不能へ変わる window（`RecoveryProtocol.recover` 内の capture、`RecoveryProtocol.kt:113`、および `ManualOrganizationRun.confirmRecovery` の rethrow）— issue 270 の scope は preview seam であり、この TOCTOU 型 window は #269 の representability 修正で解消される前提の既知事項として本 issue では扱わない。

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

### Scenario: capture 失敗後も次の preview が成立する（lease 解放）

Given 前々シナリオの capture 失敗の直後である,

When 注入を解除して `inspectRecovery(pointId)` を再度呼ぶ,

Then 結果は `Restorable` であり、writer serialization lease と run mutex が capture 失敗 path でも解放されたことが証明される（`WriterBusy` / `Concurrent` が残らない）。

### Scenario: 正常 capture は従来どおり Restorable

Given capture が成功し、lock state が判明可能な `VERIFIED` point がある,

When `inspectRecovery(pointId)` が呼ばれる,

Then 従来どおり `Restorable` と confirmation capability が返る（非回帰）。

### Scenario: lock state 不明は従来どおり NotRestorable

Given capture は成功するが `OrganizerLockState.UNKNOWN` の item がある,

When `inspectRecovery(pointId)` が呼ばれる,

Then `NotRestorable(LOCK_STATE_UNAVAILABLE)` が返り、capture 失敗の Unavailable code にはならない（両 failure code の区別は維持される）。

## Data and state

- 読み取る data は spec 84 と同一: #89 inspection projection（capture 前に 1 回 — これは仕様上必須の許可された読み取りである）、`Clock`、pure retention policy、1 回の authoritative capture。
- capture 失敗 path で禁止されるのは、authoritative recovery DB への access（#89 projection 以外の maintenance / tombstone 読み、SQLite 接続）、書込み、lifecycle 遷移、retention/prune、layout 書込み、reload、diagnostics である。#89 inspection projection read の 1 回は capture 失敗 path でも発生し得る（正常 path と同様）。
- 永続化・migration・rollback への影響なし。禁止された全操作が 0 回であることを既存の `assertNoInspectionMutation` 型 counter で継続検証する。
- capture 失敗 path では confirmation registry への登録も発生しない（`Restorable` を返さないため）。

## Permissions, privacy, and security

None。新 surface 値は enum 定数であり、例外 message・row 内容・自由文字列を運ばない。spec 84 の closed result 制約（raw revision・manifest・row data を UI へ出さない）は維持される。

## Accessibility and localization

UI 変更なし。`Unavailable` variant は既存の `manual_organization_recovery_not_available` 文言で描画され、 TalkBack / focus 挙動は現行の `State.RecoveryPreview` path と同一である。

## Acceptance criteria

- [ ] TC-AC-01: capture が `RuntimeException` で失敗した `inspectRecovery` は、例外を漏らさず `Unavailable(pointId, <新 code>)` を返す。新 code は `RECOVERY_STORE_UNAVAILABLE` と区別可能である。
- [ ] TC-AC-02: capture 失敗 path で、lease と run mutex は解放される（失敗直後の再 `inspectRecovery` が `Restorable` を返すことで証明する）。また、authoritative recovery DB access・recovery store 書込み・lifecycle 遷移・layout 書込み・reload・retention 操作・diagnostics が 0 回である（#89 inspection projection read の 1 回は許容）。
- [ ] TC-AC-03: capture 失敗時は `Restorable` も confirmation capability も発行されない（fail-closed の維持）。
- [ ] TC-AC-04: 正常 capture（`Restorable`）と lock-state 不明（`NotRestorable(LOCK_STATE_UNAVAILABLE)`）の既存 path は不変である。
- [ ] TC-AC-05: caller seam で、capture 失敗を proxy する状態から `ManualOrganizationRun.beginRecoveryPreview()` が例外を throw せず `State.RecoveryPreview` へ遷移し、confirm 可能な `Restorable` でないことが観測できる。
- [ ] TC-AC-06: 注入は決定論的である（network・時間・scheduler に依存しない JVM test）。注入対象が production の JVM-testable でない row→codec path（`RowManifestCodec` は `android.database` 依存のため JVM source set で実行不可）であるため、protocol seam での注入（実 trigger と同一の `IllegalArgumentException`）をこの issue の決定論的 invalid-row 模擬とし、実 row 形での Path A 緑化は #269 の spec 判断と一体で検証する。spec 84 の result surface 記述（`RecoveryPreviewUnavailable` 列挙と I5 の結果表）へ新 value が反映されている。

## Test oracle

| AC | Evidence |
|---|---|
| TC-AC-01 | `RecoveryPreviewProtocolTest` に capture 失敗注入 case を追加: `Unavailable(pointId, CURRENT_LAYOUT_CAPTURE_UNAVAILABLE)` を assert |
| TC-AC-02 | 同 test で `assertNoInspectionMutation()` を assert し、失敗後に注入を解除して再 `inspectRecovery` が `Restorable` を返すことを assert（lease / mutex 解放の直接証拠） |
| TC-AC-03 | 同 test で result variant が `Unavailable` であること（`Restorable` でないこと）を直接 assert |
| TC-AC-04 | 既存 `verifiedUnexpiredPointReturnsSafeRestorablePreviewWithoutMutation` / `unknownLockStateReturnsTypedRejectionWithoutMutation` が変更なしで pass |
| TC-AC-05 | `ManualOrganizationRunTest` で、既存 fake application の `recoveryPreview` seam に `Unavailable(pointId, CURRENT_LAYOUT_CAPTURE_UNAVAILABLE)` を設定し、`beginRecoveryPreview()` が例外を throw せず `State.RecoveryPreview`（`Unavailable`）へ遷移することを assert。protocol test（capture 失敗 → `Unavailable`）との合成で caller 到達 path を covering（唯一の接続点 `LayoutApplicationModule.inspectRecovery` は `readinessGate.runWhenReady` の pass-through であることを plan で確認済み） |
| TC-AC-06 | `FakeLayoutWriter` の決定論的 capture-failure knob（`RuntimeException` フィールド）で注入; `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.protocol.RecoveryPreviewProtocolTest'` と `--tests 'app.lawnchair.organizer.ui.ManualOrganizationRunTest'` の成功、および `RecoveryPreviewContractTest` の期待集合更新後の成功を PR へ記録 |

## Change history

- 2026-09-10: Drafted for Issue #270（Phase1 spec/plan review の指摘により、caller seam AC・lease 解放証拠・#89 projection read の許容範囲明示を反映）。
- 2026-09-10: 第2 review cycle で contract test 期待集合更新を plan へ追加、TC-AC-05 evidence を stub+composition 構成に整合、evidence 形式代替を Open questions へ明示、confirm 時 capture TOCTOU window を Non-goals へ明記。同日 Phase1 review `Approve`（code-reviewer-2、head `f1f6033088`）により `accepted`。
- 2026-09-10: 実装が [PR #273](https://github.com/nunu1733/NunuLauncher/pull/273) として merge（merge commit `e7929c9a99`、head `25a185bf29`）。Phase2 review は code-reviewer-2 が `Approve`（`18e0dc6025`、test 強化 + docs-only delta を含む `25a185bf29` への承認引き継ぎを再確認）、独立 audit（[`docs/assessment/pr-273-recovery-preview-capture-failure.md`](../../docs/assessment/pr-273-recovery-preview-capture-failure.md)、対象 head `81939d414b`、[CI run 34457322743](https://github.com/nunu1733/NunuLauncher/actions/runs/34457322743)）は blocking findings なし。merge head 上の `CI / final-status` は [run 34463613621](https://github.com/nunu1733/NunuLauncher/actions/runs/34463613621) で clean pass。Issue #270 は PR merge により closed。全 TC-AC-01..06 が満たされたため `implemented` に遷移。

## Open questions

None（実装開始前に解消すべき未決定事項はない）。

- **variant 選択の根拠（確定事項）**: 型付き結果の variant は `Unavailable` とする。capture 失敗は point の非復旧性も復旧可能性も主張せず「現状の検証自体が成立しなかった」ことを意味する。`NotRestorable` の reason 集合は「復旧を進められない判断（point 状態の分類、および capture は成立した上での lock-state 判定不可）」を運ぶのに対し、capture 失敗は判定に必要な evidence が 1 つも得られなかった状態であり、検証不能を表す `Unavailable` が意味的に正しい。
- **evidence 形式の意図的な代替（owner 向け明示）**: issue 270 の scope が参照する「#265 harness の Path A」は repository に存在しない作業 tree の再現 harness であり、また実 row→codec path（`RowManifestCodec`）は `android.database` 依存のため JVM test で実行できない。そこで本 spec は、決定論的 invalid-row 模擬として protocol seam 注入（実 trigger と同一の `IllegalArgumentException`）を採用し、実 row 形での Path A 緑化検証を #269 の spec 判断と一体に帰属させる。この代替は Phase1 review で検証済みであり、実装 PR を issue 270 へ記録する際に明示する。
