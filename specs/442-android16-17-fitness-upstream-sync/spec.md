---
issue: "#442"
status: draft
requirements: []
risk: []
updated: 2026-09-26
---

# Android 16/17での日常利用適性とupstream同期判断の材料が証跡つきで確定し、ADR-0014（#447）が結論を前提に起草を開始できる

## Problem

forkのproduct baselineはLawnchair 15 beta 3（commit `505dbc40e6154c05158b5d0271c45f6a885a411b`）に固定され、targetSdk 35である（`build.gradle:29-36`。本spec起草時に再確認済み）。quickstep（最近使ったアプリ連携）の有効条件は `recentsEnabled = compatible && isRecentsComponent` で、`compatible` は `SDK_INT in QUICKSTEP_MIN_SDK..QUICKSTEP_MAX_SDK`（`lawnchair/src/app/lawnchair/LawnchairApp.kt:67-69`）であり、その範囲は min 29 / max 35 に固定されている（`build.gradle:170-171`）。つまり **API 36/37の端末では最近使ったアプリ・PAUSE_APPS・GnC経由の設定が無効化される**。compatLibのfactory選択も `ATLEAST_V -> QuickstepCompatFactoryVV()` までで、API 36/37用の分岐は存在しない（`systemUI/shared/src/app/lawnchair/compat/LawnchairQuickstepCompat.kt:43-51`）。

保守者の実機はPixel 9a（tegu、Android 17 / API 37）であり（`docs/assessment/ac14-physical-device-evidence.md:4`）、日常利用がAPI 37端末上で行われる実態がある。一方で、Android 16/17での日常利用（ホーム操作、drag&drop、フォルダ、アプリ起動、最近使ったアプリ、通知ドット、ウィジェット等）の適性を扱った文書は存在しない。

再焦点化方針メモ（2026-09-24に承認、Revision 5。Epic #439）§5により、**本Issue（RF-03）の結論はADR-0014（#447）の受入の前提**である。#447は「上流workspaceへの変更量と、Lawnchair 16へのrebaseのコスト」を比較基準に含むため、rebaseの要不要（選択肢A/B/C）が先に確定しないとADR-0014を受け入れられない（メモ §4.3基準2、§5）。

また、起草時に未解決だった事実がある: GitHub Releasesの確認が行えなかった（WebFetch失敗）、メモ §2「上流との同期なし」が「同期すべき対象が存在しない」ことを意味するかの正式記録、`QUICKSTEP_MAX_SDK=35` 引き上げに必要な16-dev側の対応の有無、である。

## Outcome

`docs/assessment/issue-442-android16-17-fitness-research.md` が正本として存在し、(1) upstream同期対象の有無の確定、(2) 16-dev差分の概観（rebaseコスト評価の入力）、(3) 既知問題（#304/#418）の影響範囲の切り分け、(4) 適性チェックリストのagent実行分（API 36 emulator）の結果、を証跡（対象commit、出典link、確認日）つきで記録する。その上で、選択肢A/B/Cの判断基準（[Issue #442](https://github.com/nunu1733/NunuLauncher/issues/442)）の適用表と暫定結論を記録する。結論がC（Lawnchair 16へのrebaseを専用Epic+ADRで計画）方向の場合、専用Epicの起票範囲とADR起草要件（[upstream-strategy.md](../../docs/engineering/upstream-strategy.md) §Upgrade policyの比較軸を満たす）が明記される。保守者が行う実機7日観測（Pixel 9a / API 37）の記録枠と最終結論の確定条件も同じ文書に設ける。本Issueの完了により、#447（ADR-0014）が結論を前提に起草を開始できる。

## Scope

本Issueはresearch（[research.yml](../../.github/ISSUE_TEMPLATE/research.yml)）であり、成果は証拠・判断・記録である。コード変更はしない。

- **upstream同期対象の有無の確定**（調査method 2）: `git ls-remote`（読み取りのみ）で `15-beta`/`15-dev` のheadがbaseline commitと一致することを再確認し、15系の追加commit・tagの有無を記録する。GitHub Releases / 16-devのrelease候補の公開状況をAPIで確認し、起草時の未解決事項を解消する。
- **16-devとの差分の概観**（調査method 3。rebaseコストの見積りの入力）: GitHub compare API（`v15.0.0-beta3.0...16-dev`）によるcommit数・変更file概観、および16-dev head上の対象file（build設定、quickstep compat、`LawnchairApp.kt`相当）の読み取り（read-onlyなHTTP参照。local object databaseへのfetchはしない）により、`QUICKSTEP_MAX_SDK=35` の引き上げに必要な16-dev側の対応の有無を含めて概観する。本格的なpatch-surface計測（`tools/repo-contract/measure_upstream_patch_surface.py`）はlocal object databaseへの16-dev fetchを要するため、本Issueでは行わず、結論がC方向の場合は専用Epic側の最初の `type: upstream` Issueの対象として明記する。
- **api36/37既知問題の棚卸し**（調査method 4）: Issue #304（OPEN、root cause未確定）と #418（API 36 organizer instrumentationの断続的failure）の発生signature・環境・影響面を照合し、「CI計測環境の問題」「実機日常利用への波及」「未確定」に切り分けて記録する。
- **適性チェックリストのagent実行分**（調査method 1のうちagent実行可能な補助証拠）: API 36 emulator（system image `android-36`、既存AVD `nunu_qpr2_api36_1` 等）にdebug APK（`assembleLawnWithQuickstepGithubDebug`）を導入し、[Issue #442](https://github.com/nunu1733/NunuLauncher/issues/442)のチェックリストのうちUI自動化で確認できる項目（ホーム基本操作、アプリ起動、フォルダ作成・開閉、Remove drop targetとundo snackbar、ウィジェット追加、検索、回転、recents無効の確認）を実施して、項目ごとの結果（実施/未実施と理由）、端末、build、commit SHA、確認日を記録する。API 37用system imageはlocalに存在しないため、**API 37（Android 17）の確認は保守者の実機（Pixel 9a）に依存する**ことを明記する。
- **research文書の新設**: `docs/assessment/issue-442-android16-17-fitness-research.md` に上記全結果、選択肢A/B/Cの判断基準適用表、暫定結論（推奨）、保守者の実機観測の記録枠（日数・crash収集方法の保守者判断欄と、最終結論の確定条件）をまとめる。

## Non-goals

- Lawnchair 16へのrebaseの着手（メモ §6、AGENTS.md「Lawnchair 16への変更は通常updateとして扱わず、専用EpicとADRを要求する」）。本Issueは計画に必要な材料の確定までで止める。
- 専用Epicの起票とrebase用ADRの起草そのもの（結論がC方向の場合、起票範囲と起草要件の明記まで。ADRはEpic側で採番する。メモ §4.9「本件のD-IDは割当がない」）。
- `QUICKSTEP_MAX_SDK` 等のコード変更、targetSdk変更。
- local object databaseへの16-dev fetchと本格的なpatch-surface計測（rebase Epic側の `type: upstream` Issueの対象）。
- 保守者による実機7日観測の実行（保守者の手作業。本Issueでは記録枠と暫定結論まで。Issue本文「適性の暫定結論と最終結論を分けて記録する」）。
- Issue #304 / #418 のroot cause調査そのもの（影響範囲の切り分けまで）。
- 音声アシスタント・壁紙・feed等の周辺機能の網羅確認（起草時の未解決事項を本specで確定: 対象はホーム編集と日常利用の中核に絞る）。
- work profile（P2相当）のemulator自動化確認（emulatorにwork profileをprovisionしない。実機観測の記録枠に含める）。

## Domain language

なし（新規ドメイン用語なし。`CONTEXT.md` への反映は不要）。

## Behavior scenarios

### Scenario: upstream同期対象の不在/存在が証跡つきで確定される

Given upstream repository `https://github.com/LawnchairLauncher/lawnchair.git`（読み取りのみの参照）
When `git ls-remote`（branchesとtags）とGitHub Releases APIを実行する
Then `15-beta`/`15-dev` のhead SHA、15系tagの有無、16系tag/release候補の公開状況が、出力の引用と確認日つきでresearch文書に記録される
And baseline `505dbc40` 以降の15系commitの有無についての結論（選択肢Bの成立可否）が記録される

### Scenario: 16-devの差分概観がrebase評価材料として記録される

Given GitHub compare APIの `v15.0.0-beta3.0...16-dev` 結果と16-dev head上の対象file参照
When commit数、主要な変更領域、build設定（compileSdk/targetSdk）、quickstep compatの分岐を確認する
Then 概観結果が出典linkと確認日つきでresearch文書に記録される
And `QUICKSTEP_MAX_SDK=35` の引き上げを評価するために必要な16-dev側の対応（SDK範囲、compatLib factory、recents有効化条件）の有無が記録される

### Scenario: 既知問題がCI計測環境か実機日常利用かで切り分けられる

Given #304（api36 window focus、root cause未確定）と #418（API 36 instrumentation断続的failure）の記録（Issue本文、capture、run link）
When 発生signature・環境（CI emulator / 実機）・影響面（instrumentation test / 日常利用UI）を照合する
Then 各Issueを「CI計測環境の問題 / 実機日常利用への波及 / 未確定」に分類し、根拠（run link、captureの証拠行）つきでresearch文書に記録される
And 分類が未確定の部分は未確定として明記される（推測で補わない）

### Scenario: 適性チェックリストのagent実行分が証跡つきで記録される

Given API 36 emulatorとdebug APK（`assembleLawnWithQuickstepGithubDebug`。対象commit SHAを記録）
When チェックリストのagent実行可能項目をUI自動化で実施する
Then 項目ごとの結果（実施/未実施と理由）、端末（AVD名、system image、API level）、build、commit SHA、確認日がresearch文書に記録される
And recents（最近使ったアプリ）がAPI 36で無効であることが、build設定（`QUICKSTEP_MAX_SDK=35`）との整合つきで確認される
And API 37（Android 17）の確認と7日観測が保守者の実機作業として明記される

### Scenario: research文書に暫定結論と実機観測の記録枠が残る

Given 調査1〜4の結果が記録済みである
When [Issue #442](https://github.com/nunu1733/NunuLauncher/issues/442)の選択肢A/B/Cの判断基準を結果へ適用する
Then 基準ごとの適用結果と暫定結論（推奨）が根拠つきでresearch文書に記録される
And 結論がC方向の場合、専用Epicの起票範囲とADR起草要件（[upstream-strategy.md](../../docs/engineering/upstream-strategy.md) §Upgrade policyの比較軸）が明記される
And 保守者の実機観測（日数・crash収集方法は保守者判断。提案: 7日、logcat/bugreportの手動収集）の記録枠と、最終結論の確定条件が明記される

## Data and state

- 本Issueはproduction data・schema・migrationに触れない。
- 調査成果の正本は `docs/assessment/issue-442-android16-17-fitness-research.md` と本spec/planである。upstream観察（ls-remote、API応答）には対象commit・確認日を添える。
- emulator上で作る状態（fixture的なアプリ配置等）は一時的なものであり、端末外へ永続化しない。repositoryへはテキスト記録と必要最小限のscreenshot（個人情報を含まないsynthetic状態のみ）を残す。
- 保守者実機の観測記録は、保守者が後から同一文書の記録枠へ追記する（本PRでは記録枠のみ）。

## Permissions, privacy, and security

None。新規permission・外部送信はない。upstream観察は読み取りのみ（`git ls-remote`、GitHub API、raw file参照）。emulator上の証跡はsynthetic状態であり、実端末の個人dataを含まない。保守者実機の観測記録には個別アプリ名等が入りうるため、記録枠には「public repositoryへcommitしてよい範囲を保守者が判断する」旨を明記する。

## Accessibility and localization

None。productionの振る舞いは変更しない。

## Acceptance criteria

- [ ] AC-1: upstream同期対象の有無の結論が、`git ls-remote` 出力・GitHub Releases/tags API応答の引用と確認日つきでresearch文書に記録されている。起草時の未解決事項「GitHub Releasesの確認」が解消されている（PV-AC-01）。
- [ ] AC-2: 16-dev差分の概観（commit数、主要変更領域）と、`QUICKSTEP_MAX_SDK=35` 引き上げの評価材料（16-dev側のSDK範囲・compatLib factory・recents有効化条件の対応の有無）が出典linkと確認日つきで記録されている（PV-AC-02）。
- [ ] AC-3: #304/#418の分類（CI計測環境の問題 / 実機日常利用への波及 / 未確定）と根拠が記録されている（PV-AC-03）。
- [ ] AC-4: チェックリストのagent実行分がAPI 36 emulatorで実施され、項目ごとの結果（実施/未実施と理由）、端末、build、commit SHA、確認日がresearch文書に記録されている。recents無効の確認がbuild設定との整合つきで含まれる。API 37の確認と7日観測が保守者作業として明記されている（PV-AC-04）。
- [ ] AC-5: 選択肢A/B/Cの判断基準適用表と暫定結論（推奨）が根拠つきで記録されている。結論がC方向の場合、専用Epicの起票範囲とADR起草要件が明記されている（PV-AC-05）。
- [ ] AC-6: 保守者の実機観測の記録枠（日数・crash収集方法の保守者判断欄、記録範囲の注意、最終結論の確定条件）がresearch文書に存在する（PV-AC-06）。
- [ ] AC-7: `./gradlew spotlessCheck` と `python3 tools/repo-contract/validate_repo_contract.py` が成功する（PV-AC-07）。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | research文書のupstream節（ls-remote出力とAPI応答の引用、確認日） |
| AC-2 | research文書の16-dev概観節（compare API結果、16-dev上の対象file参照、確認日） |
| AC-3 | research文書の既知問題節（#304/#418のrun link・capture証拠との照合） |
| AC-4 | research文書のチェックリスト節（項目別結果、screenshot、端末/build/SHA/確認日） |
| AC-5 | research文書の結論節（判断基準適用表、暫定結論、Epic/ADR起草要件） |
| AC-6 | research文書の実機観測節（記録枠と確定条件） |
| AC-7 | PR本文のcommand実行結果 |

## Open questions

blocking なものはない。保守者判断を要する事項は次のとおりspec段階で非blockingとして扱う:

- 実機観測の日数（提案: 7日）とcrash収集方法（提案: logcat/bugreportの手動収集）は、research文書の記録枠に保守者確定欄を設けて、観測開始前に確定する。
- 周辺機能（音声アシスタント・壁紙・feed等）は本調査の対象外と確定した（Non-goals）。

## Change history

- 2026-09-26: Draft created for #442（Issue本文＝RF-03草案（再焦点化方針メモ 2026-09-24承認、Revision 5）を入力に起草。コード上の事実は本起草時に再確認済み）。
