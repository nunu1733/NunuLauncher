# Implementation Plan: Recovery preview の capture 失敗を typed result へマップ

> Issue: #270
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

### 失敗連鎖 (source-verified @ main `a96da39505`)

1. `lawnchair/src/app/lawnchair/organizer/application/protocol/RecoveryPreviewProtocol.kt:80` — `writer.captureCurrent(CaptureId("recovery-preview:${pointId.value}"))` が try/catch なし。catch 節は lease 解放の `finally` のみ。
2. capture の内部では `RowManifestCodec.toCanonical`（`adapter/RowManifestCodec.kt:261` 付近の `requireNotNull(row.rawSpan)` 等）が unrepresentable row で `IllegalArgumentException` を throw する。#265 の受入済み再現で確定した trigger は「organizer 適用後 folder 子を workspace へ手動移動した結果の NULL-span desktop row」。
3. `LayoutApplicationModule.inspectRecovery`（`LayoutApplicationModule.kt:218`）は readinessGate で wrap するだけで例外を変換しない。
4. `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt:457-462` — `beginRecoveryPreview()` が `application.inspectRecovery(point)` を呼び、`catch (failure: Throwable) { cancelRecoveryPreview(); throw failure }` で rethrow。Settings coroutine（`execute {}` → `scope.launch`）が uncaught で死ぬ。

### 対比と先行実装 (source-verified)

- `PlanPreviewProtocol.kt:51-56` — 同じ `captureCurrent` を `catch (_: RuntimeException)` で包み、`PlanPreviewResult.NotPlannable(PlanPreviewRejection.CAPTURE_FAILED)` へマップ済み。**leak は存在しない**ため本 issue では変更しない（spec Scope の検証対象）。
- `RecoveryPreviewProtocol` の他の failure path（store 読み失敗、lock state 不明、lease 競合、mutex 競合）は全て型付き結果を返す。漏れは capture 失敗 1 箇所のみ。
- UI `ManualOrganizationPreferences.kt:1204-1216` — `recoveryPreviewMessage` は sealed variant 単位で網羅し、`Unavailable` は既存 `manual_organization_recovery_not_available` 文言へマップ済み。enum value 追加はこの `when` を壊さない。confirm ボタンは `Restorable` のときだけ表示（`:406`）。

## Design

### Modules and interfaces

1. **`RecoveryPreviewUnavailable`（public enum, 1 value 追加）**

   ```kotlin
   enum class RecoveryPreviewUnavailable {
       RECONCILIATION_PENDING,
       RECOVERY_STORE_UNAVAILABLE,
       CURRENT_LAYOUT_CAPTURE_UNAVAILABLE,
   }
   ```

   - 命名は既存の `CaptureFailureCategory.CAPTURE_UNAVAILABLE`（`integration/CompositionModels.kt:68`）と語彙を揃えつつ、対象が「現在 layout の capture」であることを明示する。`RECOVERY_STORE_UNAVAILABLE`（store 側障害）と区別可能（TC-AC-01）。
   - `Unavailable` variant を選ぶ理由: capture 失敗は「point が復旧不能」を主張せず「現状を検証できなかった」を意味する。`NotRestorable` の reason 集合は recovery point 状態の分類に使われている。

2. **`RecoveryPreviewProtocol.inspectWithRunMutex`（catch の追加）**

   ```kotlin
   val current = try {
       writer.captureCurrent(CaptureId("recovery-preview:${pointId.value}"))
   } catch (_: RuntimeException) {
       return RecoveryPreviewResult.Unavailable(
           pointId,
           RecoveryPreviewUnavailable.CURRENT_LAYOUT_CAPTURE_UNAVAILABLE,
       )
   }
   ```

   - `finally { lease.close() }` は既存のまま機能し、catch path でも lease は解放される（TC-AC-02）。
   - catch は `RuntimeException` のみ。`PlanPreviewProtocol` の先行実装と同一 scope で、`Error` 系は捕捉しない。
   - `Restorable` / confirmation 発行は capture 成功時のみ到達可能のまま（TC-AC-03、構造的に保証）。
   - lock-state 検査（`current.layoutState.items.any { ... UNKNOWN }`）は capture 成功後にのみ実行されるため、`LOCK_STATE_UNAVAILABLE` と新 code の区別は維持される（TC-AC-04）。

3. **`FakeLayoutWriter`（test knob 追加、production に隣接しない）**

   ```kotlin
   /** Deterministic injection: when set, captureCurrent throws it. */
   var captureFailure: RuntimeException? = null
   ```

   `captureCurrent` 冒頭で `captureFailure?.let { throw it }`。既存の `capturedSnapshots` counter は throw 前に increment する（lease が取得され capture が試みられたことの観測に使用）。

4. **`RecoveryPreviewProtocolTest`（test 追加）**

   - `captureFailureReturnsTypedUnavailableWithoutMutation`: `writer.captureFailure = IllegalArgumentException("injected unrepresentable row")` を seed 後に設定し、`Unavailable(pointId, CURRENT_LAYOUT_CAPTURE_UNAVAILABLE)`、`capturedSnapshots == 1`、`assertNoInspectionMutation()` を assert。注入例外は #265 の `IllegalArgumentException`（unrepresentable row）を proxy する（TC-AC-05 の決定論的注入）。
   - 既存 test は変更しない（TC-AC-04 の非回帰 evidence）。

5. **`specs/84-recovery-preview-seam/spec.md`（正本の同期）**

   - Result surface の `RecoveryPreviewUnavailable` 列挙へ `CURRENT_LAYOUT_CAPTURE_UNAVAILABLE` を追加し、change history に 1 行追記（issue #270、typed capture-failure mapping）。status は `implemented` のまま、本 PR で実装済みとなる内容の追記のみ。

### 変更しない seam

- `LayoutWriterPort`（interface 変更なし）、`LayoutApplicationModule`、`ManualOrganizationRun`、`RecoveryProtocol`、`ApplyProtocol`、UI、`RowManifestCodec`。
- 呼び出し側と test は同じ protocol seam を使い続ける。新規 interface / adapter は作らない。

## Migration / Rollback

- DB schema、recovery store、snapshot 形式への影響なし。migration 不要。
- rollback は commit revert のみで成立する純粋な code 変更。動作変更は「例外 1 種類が型付き結果へ変わる」ことのみで、既存の型付き結果・成功 path は不変。

## Risk assessment

- `risk: layout-data` / `risk: migration` label 対象の変更は含まない（書込み path に触れない）。ただし PR は高リスク path `lawnchair/src/app/lawnchair/organizer/application/**` を変更するため、[github-workflow](../../docs/project/github-workflow.md) の高リスク独立エビデンス契約が path 条件により適用される: `CI / final-status` の成功と、`docs/assessment/pr-<PR番号>-<slug>.md` の独立 audit 記録（実装 session とは別の作業主体）が merge 前提になる。
- public surface 拡張（enum value 追加）は spec 84 の閉じた result 契約への追加であり、同 spec の本文を同一 PR で同期する。

## Test and verification

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.protocol.RecoveryPreviewProtocolTest'
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
./gradlew spotlessCheck
./gradlew assembleLawnWithQuickstepGithubDebug
python3 tools/repo-contract/validate_repo_contract.py
```

- 最初の 1 件が TC-AC-01..05 の直接的 evidence。organizer 全体 filter は非回帰確認。CI の `organizer-unit-tests` job（`final-status` 接続）が同一 surface を実行する。
- 修正は失敗を再現するテストを伴う: 実装前に `captureFailureReturnsTypedUnavailableWithoutMutation` を追加し、現行 main で「例外が漏れて test 失敗」することを確認してから catch を入れる。

## Steps

1. `FakeLayoutWriter` に `captureFailure` knob、`RecoveryPreviewProtocolTest` に failure-path test を追加 → 現行実装で失敗することを確認（oracle）。
2. `RecoveryPreviewUnavailable` へ value 追加、`RecoveryPreviewProtocol` に catch を実装 → test が緑化。
3. spec 84 の surface 記述・change history を同期。
4. 全 verification command を実行し結果を PR へ記録。
5. PR 作成（`Closes #270`）、`docs/assessment/` の独立 audit を別作業主体で実施。
