# High-risk audit: PR #273 recovery preview capture failure → typed unavailable

> Status: accepted
> Audit date: 2026-09-10

- Auditor: General-purpose subagentとして実行された独立audit session（solo保守における独立session）。このsessionはPR #273の実装・review-fixを行っていない。
- PR: https://github.com/nunu1733/NunuLauncher/pull/273
- Head SHA: 81939d414b70663f774b8d81c670bd91bd759827
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34457322743
- Criteria: specs/270-recovery-preview-typed-capture-failure/spec.md TC-AC-01; specs/270-recovery-preview-typed-capture-failure/spec.md TC-AC-02; specs/270-recovery-preview-typed-capture-failure/spec.md TC-AC-03; specs/270-recovery-preview-typed-capture-failure/spec.md TC-AC-04; specs/270-recovery-preview-typed-capture-failure/spec.md TC-AC-05; specs/270-recovery-preview-typed-capture-failure/spec.md TC-AC-06

## Scope

対象diffは `a96da39505..81939d414b`（`git diff --stat` で確認、9 files、+357/−2）。宣言scope外の変更はなかった。

- `lawnchair/src/app/lawnchair/organizer/application/protocol/RecoveryPreviewProtocol.kt`: `inspectWithRunMutex` 内の単一 `writer.captureCurrent` 呼び出しを `RuntimeException` に対して catch し、`Unavailable(pointId, CURRENT_LAYOUT_CAPTURE_UNAVAILABLE)` を返すよう変更。catch 範囲は capture 呼び出し 1 行のみで、lock-state 判定・`Restorable` 構築には及ばない。
- `lawnchair/src/app/lawnchair/organizer/application/public/RecoveryPreview.kt`: `RecoveryPreviewUnavailable` へ `CURRENT_LAYOUT_CAPTURE_UNAVAILABLE` を 1 value 追加（KDoc 付き）。既存 2 値（`RECONCILIATION_PENDING`、`RECOVERY_STORE_UNAVAILABLE`）の意味は不変。
- `tests/unit/.../adapter/FakeLayoutWriter.kt`: 決定論的注入 knob `captureFailure: RuntimeException?` を追加。`captureCurrent` が counter increment 後に throw する。
- `tests/unit/.../protocol/RecoveryPreviewProtocolTest.kt`: failure-path 2 test 追加（typed Unavailable + no-mutation、lease/mutex 解放後の再 `inspect` が `Restorable`）。既存 test の削除・変更なし。
- `tests/unit/.../ui/ManualOrganizationRunTest.kt`: caller-seam test 追加（`beginRecoveryPreview()` が例外を throw せず `State.RecoveryPreview(Unavailable)` へ遷移、cancel で `Applied` へ復帰）。
- `tests/unit/.../contract/RecoveryPreviewContractTest.kt`: `RecoveryPreviewUnavailable` 期待集合へ新 value を追加。
- `specs/270-recovery-preview-typed-capture-failure/spec.md`（新規、status `accepted`）と同 directory の `plan.md`（新規）。
- `specs/84-recovery-preview-seam/spec.md`: result surface（`RecoveryPreviewUnavailable` 列挙と I5 の結果表）への新 value 反映と change history 1 行の sync。受入条件（FR/NFR）自体は不変。

runtime書き込み経路・migration対象の変更はゼロである。production変更は上記 protocol と public enum の2 fileのみで、`LayoutApplicationModule`、`RecoveryProtocol.recover`、`ApplyProtocol`、`RowManifestCodec`、recovery store / Launcher DB / schema、Launcher3由来codeには一切触れていない。なお本PRは高リスクpath（`lawnchair/src/app/lawnchair/organizer/application/**`）を含むため独立audit要件の対象であり、本記録がその要件を満たす。

## Criteria check

- **TC-AC-01 — capture 失敗が型付き `Unavailable` を返し例外を漏らさない:** accepted。`RecoveryPreviewProtocolTest.captureFailureReturnsTypedUnavailableWithoutMutation` が `IllegalArgumentException` 注入下で `Unavailable(pointId, CURRENT_LAYOUT_CAPTURE_UNAVAILABLE)` を直接 assert する。新 code は `RECOVERY_STORE_UNAVAILABLE` とは別の enum 定数であり、`RecoveryPreviewContractTest` の期待集合更新により閉じた列挙として区別が検証される。
- **TC-AC-02 — failure path での lease / run mutex 解放と mutation 0 回:** accepted。`captureFailureReleasesLeaseAndMutexForSubsequentPreview` が、注入解除後の再 `inspect` が `Restorable` を返すこと（`WriterBusy` / `Concurrent` が残留しない直接証拠）を assert する。3 test とも `assertNoInspectionMutation()` を実行し、authoritative recovery DB access・store 書込み・lifecycle 遷移・layout 書込み・reload・retention・diagnostics が 0 回であることを既存 counter で継続検証する（#89 inspection projection read の 1 回は許容と spec に明記済み）。code上も、catch 後の `return` は外側 `try` を抜け `finally { lease.close() }` を通るため、lease 解放構造は維持される。
- **TC-AC-03 — `Restorable` / confirmation capability を発行しない:** accepted。failure path は capture 呼び出し直後の `return` であり、`confirmationIssuer` と `RecoveryPreviewSummary` の構築は `Restorable` branch にのみ存在するため、capture 失敗時に registry 登録も capability 発行も発生し得ない。fail-closed は維持される。
- **TC-AC-04 — 既存 path（`Restorable` / `NotRestorable(LOCK_STATE_UNAVAILABLE)`）の不変:** accepted。`verifiedUnexpiredPointReturnsSafeRestorablePreviewWithoutMutation` と `unknownLockStateReturnsTypedRejectionWithoutMutation` は diff で一切変更されておらず、local 再実行で pass した。lock-state 不明（capture 成功後の判定）と capture 失敗の code 区別は構造的に維持されている。
- **TC-AC-05 — caller seam で例外を throw せず `State.RecoveryPreview` へ遷移:** accepted。`ManualOrganizationRunTest.recoveryPreviewWithCaptureFailureSurfacesTypedUnavailableWithoutThrowing` が、`Unavailable(pointId, CURRENT_LAYOUT_CAPTURE_UNAVAILABLE)` を `beginRecoveryPreview()` 経由で観測し、confirm 可能な `Restorable` でないこと、cancel 後に `Applied` へ復帰することを assert する。spec の Test oracle どおりの stub+composition 構成であり、唯一の接続点 `LayoutApplicationModule.inspectRecovery` は `readinessGate.runWhenReady` の pass-through である（plan で source-verified 済み）。
- **TC-AC-06 — 決定論的注入と spec 84 sync:** accepted。注入は `FakeLayoutWriter.captureFailure`（`RuntimeException` フィールド）による JVM test であり、network・時間・scheduler に依存しない。spec 84 の `RecoveryPreviewUnavailable` 列挙と I5 結果表へ新 value が反映され、change history に 2026-09-10 の記録が追加されている。実 row 形での Path A 緑化検証は spec の Open questions に明示されたとおり #269 の spec 判断へ帰属し、本PRの scope 外である。

## Executed test surface

audit対象head `81939d414b70663f774b8d81c670bd91bd759827` で clean な working tree のまま local 再実行した:

```bash
git rev-parse HEAD            # 81939d414b70663f774b8d81c670bd91bd759827
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
./gradlew spotlessCheck
```

- `testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`: `BUILD SUCCESSFUL in 32s`。XML result で本PRの新 test が実行・成功（0 failure / 0 error / 0 skipped）を確認: `RecoveryPreviewProtocolTest`（16 tests、新規 `captureFailureReturnsTypedUnavailableWithoutMutation` / `captureFailureReleasesLeaseAndMutexForSubsequentPreview` を含む）、`ManualOrganizationRunTest`（37 tests、新規 `recoveryPreviewWithCaptureFailureSurfacesTypedUnavailableWithoutThrowing` を含む）、`RecoveryPreviewContractTest`（4 tests）。
- `spotlessCheck`: `BUILD SUCCESSFUL in 1s`。

PR-associated CI merge gate（run `34457322743`、`pull_request` event、head branch `issue-270-recovery-preview-capture-failure`、head SHA 一致）を `gh pr checks` / `gh run view` / GitHub API で直接照合した: `organizer-unit-tests`、`check-style`、`build-debug-apk`、全 instrumentation job、`validate-repo-contract`、`final-status` が成功。`organizer-instrumentation-issue99-tests` は attempt 1（job `102806742541`）で `androidx.compose.ui.test.ComposeTimeoutException`（`CategoryOverridePreferencesInstrumentationTest`、本PRが触らない UI surface）による emulator flake で失敗し、rerun の attempt 2（job `102811321738`）で 8/8 成功したことを log で確認した。

## Findings

**Blocking findings: none.** 対象head `81939d414b70663f774b8d81c670bd91bd759827` を Issue #270 の実装として accept する。

安全規約の独立確認:

- failure path は persistent write を一切行わない。catch 後の `return` のみで、store / lifecycle / layout / reload / diagnostics への到達が構造上存在しない（既存 `assertNoInspectionMutation` 型 counter の test でも継続検証）。
- catch は `RuntimeException` のみ。`Error` やその他 throwable は従来どおり leak する（spec の意図どおり、capture 失敗以外の例外は rethrow される）。
- `Restorable` も confirmation も failure path では発行されない（`confirmationIssuer` は `Restorable` branch のみ）。
- lease / mutex 解放構造は intact: capture を包む内側 `try` の `return` は外側 `try` の `finally { lease.close() }`、さらに `inspect` の `finally { mutex.release(runId) }` を通る。`finally` の再配置は行われていない。
- `git diff --stat a96da39505..81939d414b` の全 file が宣言scope内。Launcher3/AOSP由来code・DB schema・manifest・permission・依存追加の変更はゼロ。

非blocking note:

- `high-risk-evidence` gate は本audit記録の追加前に fail 状態である。本 file がPRへ push され、`high-risk-gate` が pass するまで merge してはならない（既存の運用規則どおり）。
- capture 成功後に confirm 時点で layout が capture 不能へ変わる TOCTOU window（`RecoveryProtocol.recover` 内 capture）は spec 270 の Non-goals であり #269 の representability 修正で解消される前提の既知事項。本PRでは未解決のまま分離されている。
