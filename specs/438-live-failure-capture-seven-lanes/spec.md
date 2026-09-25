---
issue: "#438"
status: implemented
requirements: []
updated: 2026-09-25
---

# 全 instrumentation lane が failure時に live emulator evidence を保持する

## Problem

Issue #422 が全 instrumentation lane に失敗時 capture を装備した際、capture step は
`reactivecircus/android-emulator-runner@v2` がエミュレータを teardown した **後** に
走る step として配線された。`adb devices` が空になるなど、artifact に生きた
window/activity/logcat 証拠が残らない。PR #437 が観測された 3 lane
（manual-organization-ui / category-override / onboarding-proposal）を live capture へ
移行したが、残り 7 lane は同じ順序欠陥を保持している。

- 証拠: [PR #425 run 35947132661 artifact 10787591922](https://github.com/nunu1733/NunuLauncher/actions/runs/35947132661/artifacts/10787591922)
  は接続 device なし。[run 35960396387](https://github.com/nunu1733/NunuLauncher/actions/runs/35960396387)
  は emulator kill 後に外部 capture を開始した。
  [PR #437 run 35990634088 artifact 10804382388](https://github.com/nunu1733/NunuLauncher/actions/runs/35990634088/artifacts/10804382388)
  は runner 内 capture で生きた device/window/activity/logcat を記録した。
- これらは capture 順序欠陥の証拠であり、いかなる test 失敗の root cause 証拠でもない。

## Outcome

10 本すべての `organizer-instrumentation-*` lane が、test 失敗時にエミュレータが
生きている間に bounded failure-time evidence を取得し、元の test 失敗を job result と
して保持する。失敗原因の分類を rerun 前に全 lane で行えるようになる。

## Scope

- 残り 7 lane を既存 wrapper
  `tools/ci/run-emulator-command-with-failure-capture.sh`（#437 で導入・検証済み）経由の
  live capture に移行する。test selector、command 順序、exit status、artifact 名、
  lane↔surface routing は不変とする。
  - `organizer-instrumentation-shared-writer-tests`
  - `organizer-instrumentation-db-migration-tests`
  - `organizer-instrumentation-restore-capture-tests`
  - `organizer-instrumentation-production-input-tests`
  - `organizer-instrumentation-reservation-recovery-tests`
  - `organizer-instrumentation-exchange-import-ui-tests`
  - `organizer-instrumentation-method-choice-journey-tests`
- 単一 command の 5 lane は gradle command をそのまま wrapper で包む（#437 の
  category-override / onboarding-proposal 前例）。
- 複数 command/stage の 2 lane は、既存の command 列を 1 本の per-lane helper script
  （#437 の `run-manual-organization-ui-instrumentation.sh` 前例）へ移し、wrapper で
  1 回包む。
- lifecycle contract test を全 10 lane 分の wiring と artifact path をカバーするよう拡張し、
  portfolio validator を「全 lane が live capture を必須とし runner 外 capture step を
  拒否する」契約へ引き上げる。

### 複数 stage lane の failure semantics（現状と変更後）

`android-emulator-runner@v2` の script 入力は `parseScript` で行分解され、各行が
`sh -c <line>` として独立実行される。`@actions/exec` が非ゼロ exit で reject するため、
最初に失敗した行で後続行は実行されず、action が失敗し、その後 emulator が kill される
（[src/main.ts @v2](https://github.com/ReactiveCircus/android-emulator-runner/blob/v2/src/main.ts)、
2026-09-25 確認）。

- 現状: 最初の失敗行で sequence が止まり、emulator kill **後** に runner 外 capture が
  走る → 生きた証拠が取れない。
- 変更後: 同一 command 列を bash helper 内で実行する。最初の失敗 command で sequence が
  止まる点は現状と同じであり、wrapper が emulator teardown 前に capture し、元の失敗
  status をそのまま返す。restore-capture は pipeline を含まないため `set -euo pipefail`
  を使う。
- `production-input` lane の `adb shell am instrument … | tee …` は現状どおり tee の
  status で判定し、後続の `grep -q 'OK (1 test)'` / `INSTRUMENTATION_CODE: -1` が失敗
  検出器のまま残る。このため production-input helper は意図的に `set -eu`（pipefail
  なし）とし、停止点と報告 status（失敗時 status 1）を runner 行単位実行の現行挙動と
  完全に一致させる。pipeline への `pipefail` 追加は合否と status を変えるため行わない
  （PR #459 review round 1 指摘 1 の対応）。

## Non-goals

- production 振る舞い、instrumentation test oracle、test selector、class list、
  command 順序の変更。
- #418（Index4/focus/SnapshotStateObserver）や #304（per-boot occluder/ANR）の
  runtime 例外の診断・修正。
- 新しい instrumentation lane、surface、artifact 名の追加。
- manual-organization-ui / category-override / onboarding-proposal の 3 lane の
  再変更（#437 で完了済み）。

## Domain language

実装語のみ。`CONTEXT.md` への反映は不要。

## Behavior scenarios

### Scenario: 失敗時に live evidence を取得する（7 lane 共通）

Given API 36（production-input は API 35）emulator が runner によって起動され、
runner script（wrapper 経由）が実行されている
When lane の test command が非ゼロで終了する
Then wrapper は runner teardown より前に `capture-emulator-failure-evidence.sh` を
実行し、`adb devices` が生きた device を報告する evidence directory を作る
And wrapper は capture の成否にかかわらず元の command status を返し、
runner step は元の失敗 status で失敗する
And 既存の failure-time artifact 名（例: `shared-writer-failure-time-emulator-evidence`）
が同じ path（例: `build/shared-writer-failure-time-evidence/**`）で upload される

### Scenario: 成功時に capture しない

Given lane の test command が成功する
When runner script が完了する
Then failure-time evidence directory は作られず、failure-time upload は skip される
（`if: failure()`）

### Scenario: capture 自体が失敗しても元の失敗が job result である

Given test command が失敗した
When `capture-emulator-failure-evidence.sh` が timeout（300s, kill-after 30s）や
adb 不達で degraded 終了する
Then wrapper は degraded を job log に記録し、元の command status で終了する
And capture artifact は `if-no-files-found: warn` で警告のみに留まる

### Scenario: 複数 stage lane の最初の失敗で停止し capture する

Given restore-capture または production-input の helper が stage 列を実行している
When 途中の stage が非ゼロで終了する
Then helper は最初の失敗 command / 失敗 check の status で終了し、後続 stage は
実行されない（production-input は tee → grep の既存判定順序と status を保持する）
And wrapper は teardown 前に capture し、その status を返す

### Scenario: wiring の決定的契約

Given lifecycle contract test が `validate-repo-contract` job で実行される
When ci.yml の 10 lane の runner script または capture 配線が契約から逸脱する
Then contract test が失敗し、portfolio validator も live capture 必須契約の逸脱を検出する

## Data and state

CI orchestration のみを変更する。Launcher production code、favorites DB、
migration、backup/restore には触れない。artifact の保持期間（reports 7 日、
failure-time evidence 14 日）と名前は不変。

## Permissions, privacy, and security

None。新たな permission・外部通信・secret は追加しない。capture 対象は emulator の
dumpsys/logcat であり、既存の #315 bounded capture 方針（per-command timeout、
出力上限、総 budget、manifest 記録）をそのまま使う。

## Accessibility and localization

該当しない（CI 変更）。

## Acceptance criteria

- [ ] AC-1: reviewed spec/plan が各 lane の command 列、元の failure status の扱い、
  artifact path、rollback を記録している（本 spec + plan.md）。
- [ ] AC-2: 7 lane が runner teardown 前に capture し、成功時には capture しない。
  元の test 失敗が capture 失敗時にも job result として残る。
- [ ] AC-3: 決定的 lifecycle/portfolio contract test が 7 lane を含む全 10 lane の
  wiring と artifact path をカバーする。`ci_portfolio_map.yml` と
  `docs/engineering/ci-test-portfolio.md` が #422 の新規 test 規則に従い同じ PR で
  更新される。
- [ ] AC-4: 最終 head の hosted CI で、CI 変更により全必要 lane が起動し、
  permanent gate と `final-status` が pass する。
- [ ] AC-5: 最低 1 件の controlled または natural failure が、旧影響 lane の生きた
  evidence を示す。rerun 前に分類と保存が済んでいる。
- [ ] AC-6: Review recommendation、Owner final decision、merge-operator check が
  [github-workflow.md](../../docs/project/github-workflow.md) に従い別記録として残る。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 本 spec/plan の commit SHA。ChatGPT review が PR で spec 含む diff を確認 |
| AC-2 | `bash tools/ci/test_emulator_failure_capture_lifecycle.sh`（10 lane wiring + wrapper status 保持 / success 無 capture / device-gone）+ hosted run の job log（command 失敗時刻 < capture 時刻 < emulator 終了時刻、`adb-devices` に生きた device） |
| AC-3 | 同 lifecycle test の全 lane 分岐 + `python3 tools/repo-contract/validate_ci_portfolio.py` とその self-test（live capture 必須・runner 外 capture 拒否の負例を含む）+ map/doc 更新の同一 PR |
| AC-4 | 最終 head の hosted PR run: `full=true` 由来の全 lane 起動ログ、permanent gate 群と `final-status` の成功 |
| AC-5 | controlled failure run（旧影響 lane の helper 末尾に一時 probe、merge 前に revert）の failure-time artifact。分類コメントを rerun 前に Issue/PR へ記録し artifact を保存 |
| AC-6 | PR本文の Review / handoff packet。ChatGPT review の recommendation、Owner decision、merge 時の operator check を分離記録 |

## Open questions

なし。複数 stage lane の failure semantics は本 spec の Scope section で確定済み
（runner の行単位実行仕様は v2 ソース確認済み、2026-09-25）。

## Change history

- 2026-09-25: Draft created for #438。
- 2026-09-25: accepted。Owner の開始指示（「Issue438 対応開始」, 2026-09-25 session）と
  Issue #438 本文の scope/exit criteria を根拠とする。spec/plan を含む実装 diff は
  PR review（ChatGPT）と Owner gate で改めて確認する。
- 2026-09-25: PR #459 review round 1（ChatGPT,
  [comment 5827562500](https://github.com/nunu1733/NunuLauncher/pull/459#issuecomment-5827562500)）
  を受けて修正: production-input helper を `set -eu`（tee→grep oracle の既存 failure
  semantics を完全保持、pipefail は不採用）へ変更。validator の runner 外 capture 検出を
  step 名・timeout 記法に依存しない token 単位へ強化し、lifecycle test も script 参照の
  直接検出へ拡張。plan の command 件数と status を実態に合わせて更新。
- 2026-09-25: review round 2（ChatGPT,
  [comment 5827789706](https://github.com/nunu1733/NunuLauncher/pull/459#issuecomment-5827789706)）
  で round 1 指摘の解消と新規・残指摘なしを確認（head `00a4a7cf29` 再取得時点）。
  merge gate run の初回 attempt は対象外 manual lane の繰り返し失敗（
  [Issue #460](https://github.com/nunu1733/NunuLauncher/issues/460)、分類済み
  [comment 5827978157](https://github.com/nunu1733/NunuLauncher/pull/459#issuecomment-5827978157)）
  で失敗したが、分類後の Owner 実行 rerun（run 36101730123 attempt 2）で全 16 job
  success・`final-status` green。Owner が rerun green を根拠に merge を指示
  （2026-09-25 session「単独rerunでgreen確認しました。マージ進めてください」）。
  本 spec は最終 PR merge に合わせ `implemented` へ遷移。
