# Implementation Plan: Android 16/17適性確認とupstream同期判断（research実行計画）

> Issue: #442
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

本plan起草時に再確認した事実（確認日: 2026-09-26）:

- `build.gradle:29-36`: compileSdk `release(36)` + minorApiLevel 1、targetSdk 35、minSdk 26、buildTools 36.1.0。
- `build.gradle:170-171`: `quickstepMinSdk = "29"`、`quickstepMaxSdk = "35"`。
- `lawnchair/src/app/lawnchair/LawnchairApp.kt:67-69`: `compatible = Build.VERSION.SDK_INT in BuildConfig.QUICKSTEP_MIN_SDK..BuildConfig.QUICKSTEP_MAX_SDK`、`recentsEnabled = compatible && isRecentsComponent`。
- `systemUI/shared/src/app/lawnchair/compat/LawnchairQuickstepCompat.kt:43-51`: factory選択は `ATLEAST_V -> QuickstepCompatFactoryVV()` が最大で、API 36/37用の分岐なし。
- 保守者実機: Pixel 9a（tegu、Android 17 / API 37。`docs/assessment/ac14-physical-device-evidence.md:4`）。
- upstream状態（Issue本文、2026-09-24の `git ls-remote`）: `15-beta`/`15-dev` はbaseline commit `505dbc40` で停止、最新tagは `v15.0.0-beta3.0`、`16-dev` が進行中（head `6889441e64023213a26a726340c694c0b60f649c`。2026-09-24時点）。本plan実行時に再確認する。
- local環境: JDK 21.0.12、Android SDK system image `android-35`/`android-36`/`android-36.1`（API 37のimageは存在しない）。既存AVDに `nunu_qpr2_api36_1`（Pixel 6、Android 16。CIと同一構成。`docs/assessment/issue-132-operator-driven-mvp-dogfooding.md:51-53`）がある。
- 既知問題: #304（api36 UI laneのburst発生bootでのwindow focus保持occluder。標準ランチャー2例・SystemUI ANR 3例の自然発生capture。root cause未確定、OPEN）、#418（API 36 organizer instrumentationの断続的failure。SystemUI ANR、Compose timeout、`SlotWriter.moveSlotGapTo` 系process crash。いずれもCI emulator上の記録）。

推測と確認済み事実の区別: 上記は確認済み。16-devの内部変更内容（SDK範囲、compatLib、Launcher3 model/workspace）は本調査で初めて確認する。

## Design

### 調査の実施方法（seam）

本調査はproduction moduleのseamを変更しない。読み取りのseamは次の3系統である。

1. **upstream観察**: `git ls-remote upstream`（branches/tags。local object databaseを変更しない）とGitHub API（`repos/LawnchairLauncher/lawnchair/releases`、`/tags`、compare `v15.0.0-beta3.0...16-dev`）。forkへの書込みはない。
2. **16-dev上の対象file参照**: raw.githubusercontent.com による16-dev head上の特定path（`build.gradle`、`lawnchair/src/app/lawnchair/LawnchairApp.kt`、`systemUI/shared/src/app/lawnchair/compat/LawnchairQuickstepCompat.kt`）の読み取り。compare APIのfile一覧が300件上限に達する場合の補完に使う。
3. **emulator観察**: android-emulator tooling（AVD起動、APK install、UI自動化、screenshot、logcat）。対象buildは検証済みcommand `./gradlew assembleLawnWithQuickstepGithubDebug`（AGENTS.md「検証済みcommand」）。

### 成果物の配置

単一のresearch文書にまとめる（Issue本文の「research文書として記録されること」を1か所で満たす）。

```text
docs/assessment/issue-442-android16-17-fitness-research.md
├── 1. 問いと判断基準（Issue本文のA/B/C基準の引用と適用方法）
├── 2. upstream同期対象の確定（ls-remote出力、Releases/tags API応答、確認日）
├── 3. 16-dev差分の概観（commit数、主要領域、QUICKSTEP_MAX_SDK引き上げの評価材料）
├── 4. 既知問題の切り分け（#304/#418の分類表）
├── 5. 適性チェックリスト（5.1 agent実行分（API 36 emulator）、5.2 保守者実機分（Pixel 9a / API 37）の記録枠）
├── 6. 暫定結論（判断基準適用表と推奨。C方向の場合は専用Epic起票範囲とADR起草要件）
├── 7. 実機観測と最終結論の確定条件（日数・crash収集方法の保守者判断欄）
└── 8. Change history（対象commit SHA、確認日）
```

### 調査手順

1. **upstream同期対象の確定**（spec Scenario 1）:
   - `git ls-remote upstream refs/heads/15-beta refs/heads/15-dev refs/heads/16-dev` と `git ls-remote upstream 'refs/tags/v15*' 'refs/tags/v16*'` を実行し、出力をresearch文書へ引用する。
   - `gh api repos/LawnchairLauncher/lawnchair/releases`（draftを含む全state）と `/tags` を確認し、release候補の公開状況を記録する。fork側 `nunu1733/NunuLauncher` のreleasesも対比として確認する（メモ §2「GitHub Releaseなし」の再確認）。
2. **16-dev差分の概観**（spec Scenario 2）:
   - `gh api repos/LawnchairLauncher/lawnchair/compare/v15.0.0-beta3.0...16-dev --jq` で `total_commits`、`files` の概観（300件上限に達した場合は `_links` と上限の明記）、主要directory（`build.gradle`、`lawnchair/src/`、`src/com/android/launcher3/`、`systemUI/`）のfile数を記録する。
   - 16-dev head上の `build.gradle`（quickstep SDK範囲、compileSdk/targetSdk）、`LawnchairQuickstepCompat.kt`（factory分岐）、`LawnchairApp.kt`（recents有効化条件）をraw参照で確認し、`QUICKSTEP_MAX_SDK=35` 引き上げの評価材料として記録する。
   - 本格計測（`measure_upstream_patch_surface.py`）は行わない。候補upstream commitがlocal object databaseに存在しないためである（upstream-strategy.md §Sync workflow 6の前提）。rebase Epic側の `type: upstream` Issueで行うことをresearch文書に明記する。
3. **既知問題の切り分け**（spec Scenario 3）:
   - #304/#418のIssue本文・capture記録・run linkを照合し、(a) 発生環境（CI emulator / 実機）、(b) 影響面（instrumentation計測 / 日常利用UI）、(c) 実機への波及可能性の根拠、を分類表にする。分類できない部分は「未確定」と明記する。
   - #304のapi36 emulator発生系（`nunu_qpr2_api36_1` と同一構成）が、本調査のemulatorチェックリスト実行時に観測された場合の対応（記録方法）をresearch文書に先に定める（観測したらsignatureごとに記録し、チェックリスト結果から切り分ける）。
4. **適性チェックリストのagent実行分**（spec Scenario 4）:
   - debug APKをbuildする（`./gradlew assembleLawnWithQuickstepGithubDebug`。対象commit SHAを記録）。
   - AVD `nunu_qpr2_api36_1`（Pixel 6、API 36.1）を起動し、debug APKをinstallしてdefault launcherに設定する。emulator状態はsynthetic（個人dataなし）に保つ。
   - チェックリスト項目をUI自動化で実施する。項目と方法:
     - ホーム基本操作: ページswipe、アイコン長押し（popup表示）、drag&drop（同ページ/隣ページ移動）、Remove drop targetとundo snackbar（4秒窓。`src/com/android/launcher3/views/Snackbar.java:50`）、フォルダ作成・開閉。
     - アプリ起動・終了: 設定app等の起動、ホーム復帰。起動時間の概観（主観記録ではなく、logcatのactivity起動完了時刻等の取得可能な範囲で記録）。
     - ウィジェット: ウィジェット追加（clock等のsystem widget）、リサイズ、動作。
     - 通知ドット: test notificationの投稿とドット表示（NotificationListener許可が必要な場合は許可手順と結果を記録。許可できない場合は未実施+理由）。
     - 検索: drawer検索（入力と結果表示）、QSB表示。
     - 回転: emulator回転でlayoutが崩れないか。
     - recents無効の確認: swipe up & holdでlauncher提供のrecents overviewが表示されないことを確認し、`QUICKSTEP_MAX_SDK=35` の設定と整合することを記録する。GestureNavでの日常利用が成立するかの評価を記録する。
     - 安定性: 本チェックリスト実行中のcrash/ANRのlogcat確認（7日観測は保守者作業であり、ここでは「実行中にcrashなし」の範囲のみ記録する）。
   - 各項目の結果（実施/未実施+理由）、screenshot、確認日をresearch文書へ記録する。未実施項目（work profile、通知ドットの許可不可の場合等）は保守者実機分の記録枠へ引き継ぐ。
5. **research文書の起草と暫定結論**（spec Scenario 5）:
   - 判断基準の適用表（A/B/Cそれぞれの基準に対する証拠の有無と評価）を作る。保守者の方針指示（方針C想定、2026-09-26）は「判断基準C-(3) 保守者が製品要件とする」の該当事実として、指示の記録とともにresearch文書へ記載する。
   - 暫定結論は「推奨と根拠」であり、最終結論は保守者の実機観測と判断で確定する（spec Scenario 5、AC-6）。暫定結論がC方向の場合、専用Epicの起票範囲（tracking対象: patch-surface本格計測、SDK引き上げ対応、AGENTS.mdの専用Epic/ADR要件）と、rebase用ADRの起草要件（upstream-strategy.md §Upgrade policyの5比較軸を満たすこと）を明記する。

### Alternatives rejected

- **本格的なpatch-surface計測を本Issueで行う**: 候補upstream commit（16-dev）がlocal object databaseに存在しない。fetchには `type: upstream` Issueの分離がIssue本文の方針であり、概観（compare API）で十分に判断材料になるため却下。
- **API 37 emulatorを新規に取得する**: localにAPI 37 system imageは存在せず、SDK imageの追加installは本調査の範囲を超える環境変更である（AGENTS.md「dependency追加…はspecとリスク評価なしに行わない」。system image追加はproduction変更ではないが、保守者実機（API 37）が既に存在するため証拠価値も限定的）。却下し、API 37は保守者実機に委ねる。
- **実機のUI自動化**: 保守者の個人端末をagentが操作しない（privacy・safety）。実機分は保守者の手動観測とする。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `specs/442-android16-17-fitness-upstream-sync/spec.md` | 新設（research spec） | 成果物・判断基準・終了条件の固定 |
| `specs/442-android16-17-fitness-upstream-sync/plan.md` | 新設（本書） | 調査手順と証跡方法の固定 |
| `docs/assessment/issue-442-android16-17-fitness-research.md` | 新設 | Issue本文のexit artifact（適性確認の記録+research文書）。docs/assessment/が調査記録の既定配置 |

コード・CI・workflowの変更はない。

## Migration and recovery

なし。production data・schema・backup/restoreに触れない。repositoryへの追加はdocsのみであり、revertで完全に戻る。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | research文書§2のls-remote出力引用とAPI応答、確認日 | `git ls-remote upstream ...`、`gh api repos/LawnchairLauncher/lawnchair/releases` |
| AC-2 | research文書§3のcompare概観と16-dev上file参照、確認日 | `gh api repos/LawnchairLauncher/lawnchair/compare/...`、raw file参照 |
| AC-3 | research文書§4の分類表（#304/#418のrun link・capture照合） | GitHub上の記録照合（読み取りのみ） |
| AC-4 | research文書§5.1の項目別結果、screenshot、端末/build/SHA/確認日 | `./gradlew assembleLawnWithQuickstepGithubDebug`、AVD `nunu_qpr2_api36_1`（API 36.1）でのUI自動化 |
| AC-5 | research文書§6の判断基準適用表と暫定結論 | 手動（証拠の統合判断） |
| AC-6 | research文書§7の記録枠と確定条件 | 文書確認 |
| AC-7 | 成功したcommand出力をPR本文へ記録 | `./gradlew spotlessCheck`、`python3 tools/repo-contract/validate_repo_contract.py` |

含めるべき観点: 本調査はdocs-onlyであるため、testはrepository contract gateが中心となる。emulator観察はproduction codeを変更しない検証であり、結果は文書の証跡として扱う。CI（`final-status`）はdocs-only path filterによりrepository contract検証のみ実行する。

## Documentation updates

- [x] spec status/history（本spec/plan。review後にstatus更新）
- [ ] CONTEXT.md — 用語追加なし
- [ ] DESIGN.md — system structure変更なし
- [ ] ADR — 本Issueでは作らない。暫定結論がC方向の場合、rebase用ADRは専用Epic側で採番・起草する（メモ §4.9）
- [ ] AGENTS.md — verified command変更なし（build commandは既存の検証済みcommandを使用）
- [ ] [upstream-strategy.md](../../docs/engineering/upstream-strategy.md) — 更新しない（「upstream HEAD: 16-dev」の記録日は本調査の観察日で更新するかをresearch文書で提案し、変更は別PR扱いにせず本PRで判断して記録する）

## Execution checklist

- [ ] Issue #442と全コメント、spec/planの再確認（開始時）。
- [ ] upstream同期対象の確定（ls-remote、Releases/tags API）と記録。
- [ ] 16-dev差分概観（compare API、対象file参照）と記録。
- [ ] #304/#418の切り分け表の作成と記録。
- [ ] debug APKのbuild（対象SHA記録）。
- [ ] emulatorチェックリストの実施と記録（screenshot、項目別結果）。
- [ ] research文書の起草（判断基準適用表、暫定結論、実機観測記録枠）。
- [ ] `./gradlew spotlessCheck` と `python3 tools/repo-contract/validate_repo_contract.py` の実行とPR本文への記録。
- [ ] PR本文に研究証跡（対象commit、確認日、未確認範囲）を記録する。
