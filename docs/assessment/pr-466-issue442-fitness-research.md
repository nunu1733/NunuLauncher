# Independent audit: PR #466 Android 16/17 fitness research (agent-executed part)

> Status: accepted
> Audit date: 2026-09-26

- Auditor: 実装session（ZCode GLM-5.3-flash）とは別の独立監査session（general-purpose subagent）。solo保守のため、実装を行ったsessionと異なる独立sessionによる監査である。
- PR: https://github.com/nunu1733/NunuLauncher/pull/466
- Head SHA: ec63cb5d82d9479c9d4faaeb5497fe3f44138261
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/36231735805（head `ec63cb5d82` 上のrun。docs-only path filterによりsource jobは全skipped、`final-status` success。本監査はこのhead上のrunを参照する）
- Criteria: [specs/442-android16-17-fitness-upstream-sync/spec.md](../../specs/442-android16-17-fitness-upstream-sync/spec.md)（status: accepted、Revision 2）— AC-1、AC-2、AC-3、AC-4、AC-5、AC-6、AC-7（PV-AC-01〜07）。AC-8（PV-AC-08）は実機観測後の最終PRの対象であり、本PRでは未達であることがspec上の前提であるため本監査の対象外とする。

## Scope

対象diffは `main...issue-442-android16-17-fitness`（head `ec63cb5d82d9479c9d4faaeb5497fe3f44138261`）の全14 file:

- `docs/assessment/issue-442-android16-17-fitness-research.md`（新規、research正本）
- `docs/assessment/evidence-442/*.png`（新規11枚、emulator証跡screenshot）
- `specs/442-android16-17-fitness-upstream-sync/spec.md`、`plan.md`（新規）

**docs-only確認**: 全変更fileは `docs/`、`specs/` 配下のmarkdownとpngのみ。production code（`*.kt`/`*.java`）、build設定、`.github/workflows/`、`tools/` への変更は0件（diffのpath一覧で機械確認済み）。runtime書き込み経路・migration対象は存在しない。

本監査で確認した外部証跡: PR #466本文とhead SHA（GitHub API）、Issue #442とPR #466のreview comment（Phase 1: issuecomment-5843973838 → 5844176592 Approved、Phase 2: PR comment 5844712515 → 5844829728 Approved）、head commit上のcheck-runs、`closingIssuesReferences`（空）。監査は読み取りのみで、localの未追跡 `refocus-drafts/` には触れていない。

## Criteria check

- **AC-1（PV-AC-01）**: research文書 §2に `git ls-remote` のbranches/tags出力引用（`15-beta`/`15-dev` = `505dbc40…`、16-dev = `d73e44f9…`、v16 tag不在）、Releases/tags API応答の引用（nightly、v15.0.0-beta3.0等。fork releases空配列）、確認日 2026-09-26T07:22:59Z、time-varying注記つきsource linkが存在する。起草時の未解決事項「GitHub Releasesの確認」の解消（§2.3）と選択肢B不成立の結論（§2.4）を確認。**満たす**。
- **AC-2（PV-AC-02）**: §3にcompare API概観（`status: diverged`、ahead_by 7,374、files 300件上限、主要変更領域）と、16-dev head `d73e44f9` にcommit固定したraw link（build.gradle、LawnchairQuickstepCompat.kt、LawnchairApp.kt）と行引用つきの対象file表（§3.2）が存在する。`QUICKSTEP_MAX_SDK=35` 引き上げに必要な16-dev側対応（SDK範囲35..36、`QuickstepCompatFactoryVBaklava`、recents条件の同一構造）の有無が記録されている。**満たす**。
- **AC-3（PV-AC-03）**: §4のsignature別分類表が (a) 発生環境、(b) user-facing直接証拠の有無、(c) production exposureの静的根拠、(d) 分類を備え、run/job/artifact linkと最小signature行をsource列に持つ（5 family: window focus occluder、SystemUI ANR/focus-gate、Compose timeout、SlotWriter/Activity-destroy、SnapshotStateObserver）。SlotWriter/Activity-destroy familyが「未確定」と明記され、Issue単位要約がsignature別結果を潰していない。**満たす**。
- **AC-4（PV-AC-04）**: §5.1に項目別結果（OK/部分OK/未自動化確認/未確認と理由）、端末（AVD `nunu_qpr2_api36_1`、API 36.1）、build（`assembleLawnWithQuickstepGithubDebug`、APK名）、対象commit SHA `a441eba228bfcda94bd044905df8381fdfa6c790`、確認日 2026-09-26が記録されている。recents無効の確認が `QUICKSTEP_MAX_SDK=35`（build.gradle:170-171）との整合つきで含まれる。API 37確認と7日観測が保守者作業でありAC-8の終了条件であることが明記されている。**満たす**。
- **AC-5（PV-AC-05）**: §6.1の判断基準適用表（A: 条件付き成立/成立、B: 不成立、C-(1): 不成立、C-(2): 不成立、C-(3): 保守者の最終判断待ち）と§6.2の暫定結論「A/C未確定」が根拠つきで存在する。C-(2)が不成立とされており、quickstep/compat事実は§6.1.1に判断基準外の材料として分離されている。結論がC方向の場合のEpic起票範囲とADR起草要件（§6.3、upstream-strategy.md §Upgrade policyの比較軸）が明記されている。PR本文は `Refs #442` でclosing keywordを含まず、`closingIssuesReferences` は空（API確認）。**満たす**。
- **AC-6（PV-AC-06）**: §5.2に保守者実機観測の記録枠（端末/build/期間の記入欄、日数とcrash収集方法の保守者確定欄、記録範囲の注意、チェックリスト項目、最終結論の確定条件）が存在する。**満たす**。
- **AC-7（PV-AC-07）**: PR本文の「検証」節に `./gradlew spotlessCheck` → BUILD SUCCESSFUL（exit 0）、`python3 tools/repo-contract/validate_repo_contract.py` → repository contract OK（`refocus-drafts/` を一時退避してtracked filesのみ検証。CIのcheckoutには存在しないためCIでは影響しない旨の説明つき）、`./gradlew assembleLawnWithQuickstepGithubDebug` → BUILD SUCCESSFULが記載されている。CIの `validate-repo-contract` jobもhead `ec63cb5d82` 上でsuccess（run 36231735805）。**満たす**。
- **AC-8（PV-AC-08）**: 本PRでは未達であることを確認（research文書 §5.2/§7が記録枠と確定条件のみで、実機観測結果と最終結論の記録は空。spec上の設計どおり）。**対象外として確認済み**。

### Scenario整合（spec Scenario 1〜6）

- Scenario 1（upstream同期対象）↔ research §2、Scenario 2（16-dev概観）↔ §3、Scenario 3（signature別切り分け）↔ §4、Scenario 4（チェックリストagent実行分）↔ §5.1、Scenario 5（暫定結論と記録枠）↔ §6/§5.2、Scenario 6（実機観測と最終結論）↔ §5.2/§7（未達、最終PR対象）。各scenarioのThen/And項目はresearch文書の対応節に記録されており、不整合は見つからなかった。

### closing keyword検査

- PR本文: `Refs #442` のみで `Closes #442` 等のclosing keywordなし（本文全文を確認。本文はclosing keyword不使用を明示的に宣言している）。
- commit message 6件（`06f8b51013`〜`ec63cb5d82`）: `Closes`/`Fixes`/`Resolves` を含まない（case-insensitive grepで0件）。
- GitHub `closingIssuesReferences`: 空配列（API確認）。

## Executed test surface

本PRはdocs-onlyのため、監査sessionで実行した検証は次のとおりである（production testは対象外。PR本文記載の検証結果はAC-7として照合した）:

- `git diff main...issue-442-android16-17-fitness --name-only` — 全変更pathがdocs/specs配下であることを確認（production code・CI・workflow変更なし）。
- `git log main..issue-442-android16-17-fitness --format=...` + grep — commit messageのclosing keyword検査（0件）。
- `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` — `nunu1733/NunuLauncher` / `main` を確認（監査前のread-only確認）。
- `gh pr view 466` / `gh pr checks 466 -R nunu1733/NunuLauncher` — PR本文、head SHA、CI status確認。
- `gh api repos/nunu1733/NunuLauncher/commits/ec63cb5d82.../check-runs` — head上のrun確認: source job 13件すべてskipped、`changes` success、`high-risk-evidence` success、`validate-repo-contract` success、`final-status` success。
- `gh api graphql ... closingIssuesReferences` — 空を確認。
- research文書・spec全文の読み合わせ（AC-1〜AC-8、Scenario 1〜6）。

## Findings

- **結論: approve（条件なし）**。AC-1〜AC-7はすべて満たす。AC-8はspec上、実機観測後の最終PRの対象であり本PRでは未達であることが確認された（これはspec設計どおりであり、不備ではない）。
- docs-only確認: production code・CI・workflow・tools への変更なし。本PRは高リスクgate（`risk: layout-data`/`risk: migration`）の対象外であるが、保守者指示により本独立監査記録を作成した。
- CI: head `ec63cb5d82` 上のrun 36231735805で `final-status` がsuccess（docs-onlyのためsource jobはskip）。`high-risk-evidence` もsuccess。
- 留意事項（merge可否に影響しない）:
  1. PR本文のReview/handoff packetの「CI evidence」欄はhead a30e259a20の旧run（36231247763）を参照したままである（「最新headのCI runはpush後に確認して本節を更新する」の記載あり）。本監査記録がhead `ec63cb5d82` 上のrun 36231735805を記録するため、証跡としては本書が補完する。merge前にWorkerがpacket欄を更新するか、本書への参照でmerge判断を行うこと。
  2. research文書の対象commit SHA（`a441eba228`、emulator検証実施時点）とPR head（`ec63cb5d82`）は異なるが、その後の2 commitはreview対応の文書修正のみであり、emulator証跡の有効性に影響しない（§8 change historyで対応内容が記録されている）。
  3. §5.2の実機観測記録枠と最終結論（AC-8）は未記入であり、Issue #442は最終PRまで開いたまま維持されること（PR本文も同様に宣言している）。
- 後続Issue分離: なし（16-dev本格計測等は既にspec Non-goalsと§6.3でrebase Epic側へ分離済み）。
