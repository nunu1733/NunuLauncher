# High-risk audit: PR #464 Editing burden benchmark definition, fixture seeding, and evidence

> Status: accepted
> Audit date: 2026-09-26

- Auditor: 独立session（general-purpose subagent、実装セッション外。solo保守の独立session監査として実施）
- PR: https://github.com/nunu1733/NunuLauncher/pull/464
- Head SHA: `a4b6fa2156a78e95343783745ae51afa280acf1c`
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/36188208059（head `a4b6fa2156`。初回実行で `organizer-unit-tests`（ExchangeFlowStateHolderTest ×2、ClassCastException）と `manual-organization-ui` lane（OrganizerDiagnosticsRouteInstrumentationTest > issue372）が失敗し、PRコメントで分類記録の上、失敗2 jobのみ `--failed` rerunを実行。監査時点（2026-09-26）でrerun中: 両jobとも `in_progress`、他13 jobは全てsuccess。rerun結果の確定は本監査の後続確認事項（Findings参照））
- Criteria: `specs/441-editing-burden-benchmark/spec.md` のAC-1〜AC-5（本PR対象分。AC-6/AC-7/AC-8は別注記）とNFR-014（定義正本: `docs/engineering/editing-burden-benchmark.md`）

## Scope

対象diff（`origin/main`（`a57403b5a5af19f236154ffcc42a7c7276b1956a`）..`a4b6fa2156`、12 commits、54 files、+2358/−3）:

- 定義文書 `docs/engineering/editing-burden-benchmark.md`（新設、Status: Accepted。B1〜B7、重み表、操作数、fixtureホーム、計測手順、baseline概算、目標表）
- fixture seeding instrumentation `tests/organizer-instrumentation/app/lawnchair/organizer/ui/EditingBurdenBenchmarkFixtureSeedingInstrumentationTest.kt`（2 test、881行）
- fixture起動先: `tests/organizer-instrumentation/AndroidManifest.xml`（activity-alias 35本 `F01`〜`F35`。実数をmanifestから直接計数して確認）+ drawable 35件 + `BenchmarkFixtureActivity`
- 計測用固定対象アプリ `tests/benchmark-install-targets/`（新module、10 flavor）+ `settings.gradle` 登録
- `build.gradle`（androidTest res.srcDirs追加）、`tools/ci/run-manual-organization-ui-instrumentation.sh`（class list 1件追加）、`docs/engineering/ci-test-portfolio.md`（lane行更新、map edge不変）
- 文書: `CONTEXT.md`（4用語）、`docs/README.md`（map行）、`docs/assessment/441-fixture-seeding-evidence.md`（evidence + screenshot 3枚）

runtime書き込み経路・migration対象: production code変更なし。seedingの書込みはtest process内の `modelDbController` 経由で `favorites` 表のみ（schema変更なし）。書込み境界は「非preservedな `CONTAINER_DESKTOP` root + その再帰descendant（子孫→root順で削除）」に限定され、hotseat行・その子孫（`preservedIds`の閉包伝播で保持）・予約領域重複行は書換対象外であることを実コードで確認した。製品manifestは不変（fixture aliasはtest APKのmanifest `tests/organizer-instrumentation/AndroidManifest.xml` のみ）。

確認経路: PR本文・diff（`gh pr view/diff`）、Issue #441の全コメント（Phase 1 review 3回→Approved、Phase 2 review→Revision 6→re-review→Revision 2→re-review→Revision 3 docs-only→Phase 2クリア、各判定とhead SHAが記録済み）、ローカルgit検証、CI run/PRコメントの分類記録。

## Criteria check

- **AC-1（定義文書）: 確認**。`docs/engineering/editing-burden-benchmark.md` がStatus: Acceptedで存在し、B1〜B7（§2）、重み表確定値（§4）、操作数の定義（§4、#452引用）、fixtureホーム（§5: 4列×5行+QSB有効、35 identity、重複2組、収容契約、決定的配置規則）、計測手順（§7: QSB固定、固定対象アプリ、`pm install-create --install-reason 4` のinstall経路、試行間reset、計時点）、baseline概算（§6、非実測の明記）、目標表（暫定+確定手順）を含む。plan Revision 6（A-8/A-10/A-11/A-13、Revision 5の2項目フォルダ化を含む）と節レベルで一致することを目視照合した。`validate_repo_contract.py` がクリーンworktreeでexit 0（markdown link検証を含む）。
- **AC-2（同一入力→同一fixture）: 確認**。`fixtureSeedsIdenticallyFromSameInputAndPreservesDockAndReservations` が同一入力表から削除→insertを2回実行し、正規化projection（`normalizedProjection`、`_ID`/`MODIFIED`除外、folder参照id解決）の一致をassert。収容契約（page 0がQSB予約を除き満杯、page 1に空きcell≥1、B1配置先前提）も `assertFixtureContract` 内のassertionとして実装されている。実行記録はevidence §1/§5（clean checkout `7102b0d4bf`、2 test PASS）。本監査はエミュレータを起動していないため、test実行の再現ではなく、testコードが契約をコードとして持つこととevidence記録の整合で確認した。
- **AC-3（hotseat/予約領域保持+非交差+復元）: 確認**。`seedFixture` の削除集合は非preserved desktop root + `collectDescendants` による再帰descendantのみ（子孫→root順）。`preservedIds` はhotseat全行+予約重複desktop行+その子孫閉包。`assertReservationDisjoint` はfixture-owned行のみに `ReservationOverlapAcceptance.overlaps` falseを要求し、`assertUntouchedRowsUnchanged` がpreserved/fixture外行の無変化を検証。非自明保持test `seedingPreservesPreExistingHotseatFolderDescendants` はhotseatフォルダ+child 2行を注入し、root+child 3行のbefore/after完全一致・`container == folderId`・fixture-owned非交差をassert（child比較あり、vacuous対策のslot事前除外+precondition assert付き）。persist時は `assumeFalse(persistMode())` で第2testをskip（dock汚染防止）。restore modeで `assertEquals(originalRows, snapshotFavorites())`。
- **AC-4（identity一意性・重複2組）: 確認**。`assertFixtureContract` がdesktop app 35行、identity group数33、重複group数2、各group 2行、重複キーが指定alias（F02/F03）+serialであることをassert。定義文書§5のidentity→role表とtest入力表 `FIXTURE_LAYOUT` の一致をtestが検証する設計。manifest実数35 alias（`F01`〜`F35`、一意計数済み）でRevision 5/6の契約と一致。
- **AC-5（CI統合+test-audit）: 確認**。新lane/workflowなし。`run-manual-organization-ui-instrumentation.sh` のclass listに1件追加（実コードで確認）、`ci-test-portfolio.md` のmanual-organization-ui lane行に#441のco-occupant追記（map edge不変、`ci_portfolio_map.yml`はdiff外）。test-audit審査6項目（不足理由、oracle配置、impact surface、重複、昇格根拠、map/portfolio）がIssue #441のhandoff packetに記録済み。support file（test APK manifest/res、`tests/benchmark-install-targets/`）がsurface path filter外で単独変更時はfail-closedで全source laneへ倒れる旨の訂正記載もある（coverage欠落ではなく保守的倒れ）。
- **NFR-014（編集負担）: 確認（定義正本の提供まで）**。`CONTEXT.md` にNFR-014の正本を `editing-burden-benchmark.md` とする4用語が追加され、specのrequirements `[NFR-014]` と一致。NFR-014自体のstatus確定はAC-8（後続）であり本PRでは確定しない（PR本文もそのとおり記載）。
- **AC-6（spotlessCheck）: 別注記で確認**。本監査では未実行。evidence §5にclean checkout `7102b0d4bf` でのBUILD SUCCESSFUL記録、CI `check-style` jobが本runでsuccess。
- **AC-7/AC-8（baseline実測と目標確定）: 本PR対象外**。specが明示的に後続ステップ（保守者・実機Pixel 9a、後続PRが `Closes #441`）と定義しており、PR本文・Issue記録と一致。

## Executed test surface

監査session自身が実行したcommandと結果:

- `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` → `nunu1733/NunuLauncher` / `main` を確認（AGENTS.mdの操作先契約どおり）
- `gh pr view 464 -R nunu1733/NunuLauncher --json title,state,headRefName,baseRefName,headRefOid,body,commits` → OPEN、base `main`、head `a4b6fa2156...`、12 commits
- `gh pr diff 464 -R nunu1733/NunuLauncher` → 全diffを目視確認
- `gh issue view 441 -R nunu1733/NunuLauncher --comments` → Phase 1/Phase 2の全review記録を読み、各revisionのhead SHAと現headの対応を確認
- `git fetch origin issue-441-editing-burden-benchmark` → FETCH_HEAD = `a4b6fa2156a78e95343783745ae51afa280acf1c`（PR headと一致）
- `git log a57403b5a5..a4b6fa2156 --oneline` → 12 commits（Phase 1 spec/plan 5件 → 実装 → review対応3件 → evidence記録2件 → docs-only修正1件）
- `git diff --stat a57403b5a5..a4b6fa2156` → 54 files、+2358/−3（上記Scopeの内訳）
- `git worktree add /tmp/audit464-wt a4b6fa2156...` でheadのクリーンworktreeを作成し:
  - `python3 tools/repo-contract/validate_repo_contract.py --root /tmp/audit464-wt` → **repository contract OK（exit 0）**。なおメインworktreeでは `refocus-drafts/`（untracked、ローカルのみ）のbroken link 5件でFAILするため、クリーンworktreeでの検証を正とした（refocus-draftsはリポジトリ未収録がplan.mdの記載と一致）
  - `python3 tools/repo-contract/test_validate_repo_contract.py` → OK
  - `python3 tools/repo-contract/validate_ci_portfolio.py --root /tmp/audit464-wt`（venvでPyYAMLを導入）→ **CI portfolio validation OK（exit 0）**
  - `python3 tools/repo-contract/test_validate_ci_portfolio.py`（venv）→ OK
  - 検証後worktree/venvは削除済み
- spec→文書→testの目視照合: spec AC-1〜5 ↔ `editing-burden-benchmark.md` §2/§4/§5/§7 ↔ `EditingBurdenBenchmarkFixtureSeedingInstrumentationTest.kt` のassertion ↔ manifest alias 35本 ↔ `run-manual-organization-ui-instrumentation.sh` class list ↔ `ci-test-portfolio.md` lane行（上記Criteria checkの根拠）
- `gh pr checks 464 -R nunu1733/NunuLauncher` → 13 job success、`organizer-unit-tests` と `manual-organization-ui` はrerun中（pending表示）
- `gh run view 36188208059 -R nunu1733/NunuLauncher`（2回。初回+JSON）→ head `a4b6fa2156...`、13 job success、2 job `in_progress`（rerun）
- `gh run view 36155374249 -R nunu1733/NunuLauncher`（main `a57403b5a5` のCI）→ conclusion `failure`、失敗jobは `manual-organization-ui` + `final-status`、`--log-failed` で失敗testが `OrganizerDiagnosticsRouteInstrumentationTest > issue372ConsultationSessionSurvivesARealMaterialsWriteViaTheProductionRoute` であることを確認 → PRコメントの「mainで同一testが同一理由で失敗」の分類記録と一致
- PRコメント2件（CI失敗分類）を読み、分類根拠（diff非交差、同一commitローカルPASS、main同一failure）を上記で検証

実行しなかった確認（理由を明記）: `spotlessCheck`（CI check-styleがsuccess、evidence §5にclean checkout記録あり。監査での再実行は二重）、emulatorでのfixture test再実行（実機/emulator計測は保守者のAC-7後続ステップであり、evidence §5のclean checkout記録+CI lane実行でcoverageされる）。

## Findings

問題なしと判断する根拠に加え、残課題を記録する:

1. **AC-6/AC-7/AC-8の残置（既知・計画どおり）**: AC-7/AC-8はspec自身が本PR対象外と定義する後続ステップ（保守者・実機計測→ `editing-burden-baseline.md` → 目標確定+NFR-014確定）。PR本文は `Refs #441` でありclosing keywordを使っていない。AGENTS.mdの「終了条件を満たす最終PRだけが `Closes #<issue>` を含める」規約と整合。merge後に保守者の後続PRで完了を確認すること。
2. **CI rerunの確定が未了**: run 36188208059の `organizer-unit-tests` と `manual-organization-ui` は監査時点でrerun中（`in_progress`）。初回失敗の分類（ExchangeFlowStateHolderTest ×2 = frozen領域の既知不安定面でdiff非交差、issue372 = main `a57403b5` のrun 36155374249で同一test・同一理由の既存failure。本PRの新規test 2件は147 test中PASS）は、監査がmain runの失敗test名とPR差分の非交差を独立確認したもので正当である。ただし **merge判定の前にrerun結果の確定（green化）を確認する必要がある**。rerunが再び失敗する場合はPRコメント記載のとおりtracking Issue分離と再分類が必要。本監査はrerun結果を前提としない分類の妥当性確認までを行う。
3. **evidenceのprovenance構造（改善余地・非blocker）**: evidence §5の実行対象treeは `7102b0d4bf` であり、現head `a4b6fa2156` との差分はevidence文書自体の2 commits（code変更なし）である。この構造はPhase 2 re-review（Revision 3）で明示確認・承認済みであり、本監査も `git diff 7102b0d4bf..a4b6fa2156 --stat` 相当（docs配下のみ）で裏付けた。ただし最終head自体のCI connected run（manual-organization-ui lane）はrerun完了時に初めてhead上での実行evidenceとなるため、rerun greenの確認がmerge gateの実質要件となる。
4. **本監査のvalidator実行環境の注記**: メインworktreeは `refocus-drafts/`（untracked）によりrepo-contract validatorがFAILするため、監査はhead `a4b6fa2156` のクリーンworktreeで検証した。このuntracked draftの扱い（リポジトリ未収録のまま維持するか、削除/移動するか）は作業環境の保守課題であり、本PRの範囲外。
5. **重大な問題（AC不成立、証拠と主張の不一致）: なし**。

## Findings事後確認（2026-09-26）

Findings 2（CI rerunの確定）はその後解消した: run 36188208059のrerunで `organizer-unit-tests` と `manual-organization-ui` はいずれもsuccessで完了し、run全体のconclusionは `success`、PR #464の `final-status` はpass。merge前要件（rerun greenの確定）は充足。本追記以降のhead差分はdocs配下のみ。Phase 2 reviewで指摘されたwrite境界・persist dock汚染・vacuous oracle・provenanceの各問題はRevision 6〜3の対応で解消されており、監査が実コードでその解消を確認した（Criteria check参照）。
