# High-risk audit: PR #158 QSB予約領域を考慮した全体整理

> Status: accepted (technical audit; PR high-risk gate pending audit-file commit)
> Audit date: 2026-08-27

- Auditor: Codex independent audit session (実装セッションとは別の監査セッション)
- PR: https://github.com/nunu1733/NunuLauncher/pull/158
- Head SHA: 26b40829e5829d4034c859c080da153f8edf63cc
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/33053922113
- Criteria: specs/155-qsb-reservation-reload/spec.md FR-002, FR-004, FR-005, FR-006, NFR-002, NFR-005, NFR-007, NFR-011; docs/adr/0008-qsb-reservation-context-and-recovery-compatibility.md ADR-0008

## Scope

PR #158 の現行 exact-head source diff、前回監査からのCI workflow差分、Issue #155 の全コメント、accepted な仕様・ADR、Launcher adapter、planner/materializer、recovery store、Loader reload bridge、関連 unit/contract/instrumentation test を確認した。

主な確認経路は、`LauncherLayoutAdapter` の canonical capture/page authority、reservation-aware planner occupancy、application の revision/precondition/A7 verification、v2 recovery compatibility gate、既存 Loader sanitizer/lease behavior である。監査では production source を変更していない。

## Criteria check

- QSB-AC-01〜04: **static review + local JVM/contract evidence は適合**。`WorkspaceLayoutManager.FIRST_SCREEN_ID` と同一 capture の QSB/IDP snapshot、rowless first page、reservation の preserve-only 表現、context の revision/resource 伝播を確認した。reservation は item/action/`favorites` row に materialize されない。
- QSB-AC-05: **適合**。v1 lifecycle matrix、non-empty/retained-tombstone fail-closed、expired-only migration、v2 downgrade のテストがあり、現行の専用 API 36 / Platform 36.1 CI lane で実行成功している。
- QSB-AC-06: **実装・テスト内容は適合**。red-first fixture が旧 `(0,0)` target と correlated reload の削除/A7 mismatch を確認し、修正側の production E2E が予約外 target を確認する。
- QSB-AC-07: **適合**。API 36 / Platform 36.1 の production manual E2E は QSB on、rowless/disabled control、A7/A8/recovery と teardown ordering を確認し、現行 exact-head CI で成功している。
- QSB-AC-08: **適合**。normal sanitizer と organizer reload の分離を確認する `SanitizerInstrumentationTest` が専用 lane の exact-head CI で成功している。
- QSB-AC-09: **適合（audit-fileのPR反映待ち）**。focused connected lane、`CI / final-status`、style、build、organizer JVM、repository-contract は現行 exact head で成功している。high-risk gate はこの監査記録をPR checkoutへ反映した後に再実行する必要がある。

## Executed test surface

監査セッションで実行したもの:

```bash
git status --short
git diff --check main...HEAD
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
python3 tools/repo-contract/test_validate_high_risk_evidence.py
python3 tools/repo-contract/validate_high_risk_evidence.py --repo nunu1733/NunuLauncher --pr-number 158 --head-sha 26b40829e5829d4034c859c080da153f8edf63cc --root /Users/nunu/Documents/work/NunuLauncher
./gradlew --no-daemon --max-workers=2 spotlessCheck
./gradlew --no-daemon --max-workers=2 testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
./gradlew --no-daemon --max-workers=2 assembleLawnWithQuickstepGithubDebug
```

上記はすべて成功した（contract test は 11 tests、high-risk evidence validator test は 47 tests）。また GitHub API で CI run `33053922113` が PR #158 の `26b40829e5829d4034c859c080da153f8edf63cc` に紐づく `pull_request` run であり、`final-status`、style、build、organizer unit、Issue 155専用 instrumentation lane を含む全 source jobs が success であることを確認した。監査記録を現在のcheckoutに置いた状態で high-risk evidence validator も PASS する。

CI workflow の exact source command も確認した。追加された `organizer-instrumentation-issue155-tests` は clean API 36 emulator 上で、次の4クラスを明示指定している: `ProductionPublicSeamInstrumentationTest`、`RealAdapterRowMatrixInstrumentationTest`、`SanitizerInstrumentationTest`、`RecoveryStoreLifecycleTest`。このjobと `final-status` は run `33053922113` で success である。

## Findings

監査判定: **accepted**。前回の blocking finding は解消され、Issue #155 の focused connected instrumentation が exact-head CI に登録され成功している。残る作業は、この監査記録をPR headへコミットして high-risk gateを再実行することである。

### Previous finding — resolved — Issue #155 の focused connected instrumentation が CI に登録されていない

前回監査時点では、`.github/workflows/ci.yml` の `connectedLawnWithQuickstepGithubDebugAndroidTest` 呼び出しに次の Issue #155 acceptance evidence classes が含まれていなかった。

- `app.lawnchair.organizer.application.ProductionPublicSeamInstrumentationTest`
- `app.lawnchair.organizer.application.RealAdapterRowMatrixInstrumentationTest`
- `app.lawnchair.organizer.application.SanitizerInstrumentationTest`
- `com.android.launcher3.organizer.RecoveryStoreLifecycleTest`

現行PRでは専用 clean API 36 laneが追加され、4クラスすべてを明示指定している。run `33053922113` の `organizer-instrumentation-issue155-tests` は success であり、この evidence gap は解消された。

対応済みの内容は、これらのクラスを独立した clean API 36.1 connected-test laneへ追加し、現行PR head `26b40829e5829d4034c859c080da153f8edf63cc` の PR `CI / final-status` を成功させることである。

### Static review conclusion

前回 findings 以外に、現行 exact-head source review で新たな production logic defect は確認できなかった。rowless first-screen の page-id collision、QSB reservation overlap predicate、v1 recovery fail-closed、recovery context の page-list差分許容（reservation constraint は厳密比較）、および test teardown の QSB semantics ordering は、Issue #155 の re-review 指摘に沿って解消されている。
