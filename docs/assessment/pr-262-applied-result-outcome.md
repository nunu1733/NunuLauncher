# Independent audit: PR #262 Report verified apply outcome in the completed tense (#231)

> Status: accepted
> Audit date: 2026-09-09

- Auditor: 独立監査セッション (general-purpose subagent、実装セッションとは別の作業主体。solo保守の独立session規定に基づく)
- PR: https://github.com/nunu1733/NunuLauncher/pull/262
- Head SHA: b2eb1851e8b3ef2ac4e140a9f61118a665c74f54
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/34314576878
- Criteria: specs/231-applied-result-outcome/spec.md — AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8

## Scope

対象diffは PR #262 の `2411c76082..e27ceda814` (base `main`)。変更 file は 11 件:

- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` (UI 表示分岐 + `appliedResultItems` 追加)
- `lawnchair/res/values/strings.xml` / `lawnchair/res/values-ja/strings.xml` (新規 4 plurals ×2 locale)
- `specs/52-manual-full-organization-vertical-slice/spec.md` (Result and recovery への契約追記)
- `tests/organizer-instrumentation/.../ManualOrganizationPreferencesInstrumentationTest.kt` (新規 3 test + ja plurals 解決拡張)
- `tests/organizer-instrumentation/.../ManualOrganizationProductionE2EInstrumentationTest.kt` (適用前後 capture 差分導出の件数主張)
- `specs/231-applied-result-outcome/{spec,plan}.md`、`docs/evidence/issue-231/*` (5 file)

本 PR は `risk: layout-data` / `risk: migration` label を持たず、高リスク path (`organizer/application/**`、Launcher DB、migration) に触れないため高リスク gate の適用対象外である。本記録はセッション指示による追加の独立検証である。zero-write (表示層のみ) を確認: DB 書込み、state shape 変更、permission/通信追加は diff 内に存在しない。

## Criteria check

- **AC-1 (PASS)** — `ManualOrganizationPreferences.kt:336-342` で `State.Applied` を `result is ApplyResult.Applied` で分岐。`appliedResultItems` (768-824) は現行 `summaryItems`/`changeCountItems` と同一の行順 (contextItems → moved → movedByReason → preserved → preservedByReason → new folders → new pages → rejected/unplaced 反復 → warnings → constraints) で、4 count 行のみ新規 plurals。test `verifiedSuccessSurfaceReportsAppliedOutcomeInPastTense` が完了形 4 行の表示と proposal 件数行の不在 (`assertCountEquals(0)`) を主張。
- **AC-2 (PASS)** — 成功見出し + `4 placements moved` / `11 placements were preserved` 等 (evidence en-02/en-03) により、画面だけで完了と変更/保持件数を説明できる。
- **AC-3 (PASS)** — `verifiedSuccessKeepsAppliedWordingAfterRecoveryPreviewCancel` (recovery preview cancel → `State.Applied` 再描画) に加え、Home 往復後の再訪 evidence `docs/evidence/issue-231/en-03-applied-result-after-home-roundtrip.png` が同一完了形内容を記録。
- **AC-4 (PASS)** — fixture test は `applied.summary` 件数から期待文字列を導出。production E2E (`ManualOrganizationProductionE2EInstrumentationTest.kt:221-228`) は適用前後の layout capture 差分から `movedFromDiff` を導出し `applied.summary.movedCount` / `newFolderCount` と一致を主張。
- **AC-5 (PASS)** — `nonSuccessApplyResultKeepsProposalSummaryWording` が `RolledBack` で現行描画 (proposal 件数行の表示、完了形行の不在) を主張。非成功経路は無変更の `summaryItems` を通過。
- **AC-6 (PASS)** — en plurals one/other (`strings.xml:1041-1056`)、ja other (`values-ja/strings.xml:123-134`)。ja locale fallback 検出 test と en 数量区別 test に 4 plurals を追加済み。
- **AC-7 (PASS)** — spec 52 §"Result and recovery" の契約追記が同一 PR diff に存在 (完了形 applied counts、行順不変、非成功は現行描画)。
- **AC-8 (PASS)** — `docs/evidence/issue-231/` に en 3 枚 + ja 1 枚 + README。ja 画像で新規 ja plurals (移動した配置: 4件 等) の解決と英語 fallback なしを視認。

## Executed test surface

監査セッション内で独立に実行:

- `./gradlew spotlessCheck` → BUILD SUCCESSFUL (exit 0)
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → BUILD SUCCESSFUL (exit 0)。result XML: 88 classes / **962 tests / 0 failures / 0 errors / 0 skipped**

CI 証拠 (同一 head SHA):

- run 34314576878 (CI, pull_request, head `e27ceda814`): 全 13 job success、**`final-status` success**。`organizer-instrumentation-issue52-tests` job は 45 tests / 0 failed ("Tests 45/45 completed. (0 skipped) (0 failed)") を log で確認。`gh run view 34314576878 -R nunu1733/NunuLauncher` で確認済み。

## Findings

なし (blocking)。参考記録: PR 本文に開示済みの初回 local instrumentation run における既存 E2E 2 test (`recoveryConfirmationExplainsTarget...` / `reservationlessLegacyTarget...`) の失敗は、分離実行と後続 full run で green であり、対象 diff の触れない planner/DB 経路の local AVD 状態干渉と判断される。監査対象 head の CI full run では 45/45 green。

## Overall verdict

**PASS** — AC-1..AC-8 を対象 head の diff に対して検証済み。CI `final-status` が同一 commit で成功、zero-write 主張は成立、独立再実行 (spotless + 962 unit tests) は green。

## Re-audit note (2026-09-09, review fix)

Review finding 対応の delta `b50f26307c..b2eb1851e8` (test-only: `ManualOrganizationPreferencesInstrumentationTest.kt` — proposal-wording の不在/存在確認を `applied.summary` 実件数と組にし、非成功 test を 4 count 対称化) を同一監査セッションが再確認した。verdict は **PASS のまま** (AC-1/AC-5 の test 証拠はむしろ強化、AC-4/AC-6 は無変更、コード/strings/spec/evidence に差分なし)。`./gradlew spotlessCheck` → PASS、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → 962 tests / 0 failures を新 head で独立実行。code に対する先の CI 証拠 (run 34314576878, final-status success) は delta が test-only のため引き続き有効。新 head の CI run 34322893724 (High-risk gate 34322893725 は success) は push 時点で進行中。Head SHA を `b2eb1851e8` へ更新した。
