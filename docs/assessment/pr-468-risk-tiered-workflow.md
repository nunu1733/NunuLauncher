# High-risk audit: PR #468 docs(444): リスク階層（Risk tiers H/M/L）の導入と実装語彙の正本移動

> Status: accepted
> Audit date: 2026-09-27

- Auditor: 独立session（ZCode general-purpose subagent。実装sessionとは別のsessionであり、solo保守のため独立sessionによる再実行・再確認として実施）。実装者報告（Issue #444コメント）に依存せず、commit済みdiff、file内容、PR metadata、CI runを自ら確認した。
- PR: https://github.com/nunu1733/NunuLauncher/pull/468
- Head SHA: d9b48f13349dc19aee48226995b06a1849109753
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/36291847063 （event pull_request、headSha一致、conclusion success、final-status success。docs-only diffのためsource jobs（organizer-unit-tests等）はskip。PR labelsは空、変更pathはすべてMarkdownでrisk label・高リスクpathのいずれにも該当せず、`classify_pr` は `(False, [])`。したがって本PRは「高リスクPRへの独立エビデンス」の機械要件の対象外であり、本記録はAC-5の独立実行証跡として作成した）
- Criteria: specs/444-risk-tiered-workflow/spec.md AC-1, AC-2, AC-3, AC-4, AC-5, AC-6

## Scope

Pre-check（read-only）: `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` → `nameWithOwner: nunu1733/NunuLauncher`、default branch `main`。`gh pr view 468 -R nunu1733/NunuLauncher` → headRefOid `d9b48f13349dc19aee48226995b06a1849109753`（= 監査対象head、branch `issue-444-risk-tiered-workflow`）、base `main`、state OPEN、labels空、files 16件（local `git diff --name-only ea8d57d068..d9b48f1334` と完全一致）。

対象diff: `git log --oneline ea8d57d068..d9b48f1334` = 8 commits（spec/plan草案 → phase-1 review対応2件 → spec accepted → Risk tiers/AGENTS追加 → CONTEXT語彙移動 → phase-2 review対応 → AGENTS正本分担表の階層H化）。`git diff --stat` = 16 files changed, 714 insertions(+), 43 deletions(-)。base `ea8d57d068` は #467 のmerge commit。head `d9b48f1334` は AGENTS.md 1行のみの変更。

- `docs/project/github-workflow.md`: `## Risk tiers（リスク階層）` 節の新設（階層の定義・判定・優先順位・格上げ/下げ・review往復上限・patch-surface oracle参照）、Issue intakeへのrisk tier行、Start gateの階層別spec要件、Lifecycle §4の階層別plan.md要件、`> Updated:` 更新。
- `AGENTS.md`: 正本分担表のplan.md行、手順4、手順8の階層条件付き改訂、手順節末尾の階層参照段落。
- `specs/_template/spec-lite.md`: 新設（階層M用軽量spec template）。
- `CONTEXT.md`: 12語の定義本文を各正本へ移動して参照化、前書き更新。境界語3種（export session、対象scope凍結、中断・破棄・キャンセル）は完全定義のまま残置。
- `DESIGN.md`: §4.2 に Durable status vocabulary 小節を新設（organizer durable status）。
- `specs/204,205,328,329,331,373,374,375,417`: 移動先のDomain language前書き更新（329は節新設、328/375は定義本文を統合、374は正本位置の記述行を更新）。
- `specs/444-risk-tiered-workflow/{spec.md,plan.md}`: 本Issueのspec/plan。

runtime surface: 変更はすべてMarkdown文書。Launcher DBへの書込み経路、schema migration、recovery store、backup/restore契約、permission・通信・secretの変更なし。runtime codeの変更を含まない。

注: 監査着手時点のlocal main worktreeの未追跡 `refocus-drafts/` はrepository未収録の保守者ローカル草案であり、監査対象（commit tree）から除外した。本記録は監査対象headを変更しない（記録の追加のみ）。

## Criteria check

- AC-1: **pass**。`docs/project/github-workflow.md` に `## Risk tiers（リスク階層）` 節（Risk tiers: 20行目、`### 階層の定義` 25行目、`### 階層の判定` 59行目）が追加され、階層H/M/Lの定義、判定方法、優先順位、格上げ・下げ、review往復上限、patch-surface oracle参照を含む。H条件（新しい書込み経路/既存書込み契約の変更、migration・recovery store・backup/restore契約、上流model/loader bridge、高リスクpath一覧該当）は「階層Hの条件は階層M/Lの判定より常に優先する」と明記され、高リスクpath上のbehavior-preserving refactorも階層Hとされる。Lは「高リスクpath一覧に触れず、新しい書込み経路等の階層H条件を作らない」文書・テスト・refactorに限定。判定方法は `#高リスクprへの独立エビデンス要求` anchor と `tools/repo-contract/validate_high_risk_evidence.py` を参照し「一覧を複製しない」と明記。階層Mのreview上限は「原則2 roundまで」、1 roundは「1回のreview recommendationと、それに対する対応の組」で、条件解除のためのevidence追加と再確認は同じround内と定義。階層Mの上流UI bridgeには `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` のPR本文reportが条件。Issue intakeに risk tier行（109行目）、Start gateのfeature bulletに階層別要件（157行目: plan revisionは階層Hのみ、階層Mは軽量specのacceptedで足りる）、Lifecycle §4（130行目）が階層Hではplan.md、階層Mでは不要と改訂、`> Updated:` は2026-09-27の#444記述へ更新。既存の「高リスクPRへの独立エビデンス要求」節と「Fork label vocabulary」節は ea8d57d068 と byte-identical（抽出節のdiffで確認、差分なし）。`plan.md` への言及は workflow+AGENTS で計8件（workflow 4, 35, 48, 130, 157, 221 / AGENTS 38, 51）で、すべて階層条件付き（階層Hの手順bullet、階層Mの「要求しない」記述、階層別改訂行、階層H限定のplan revision記述、AGENTS正本分担表・手順4）か、明示的に「Packet example (historical, exact revision; not a runtime claim)」とされた履歴例（221行目）であり、無条件のplan.md要件は残っていない。DocConsistencyTests制約も維持: `### 適用条件` 見出しの出現数は1（既存の高リスク節のみ）、新Risk tiers節内に `lawnchair/src/`・`src/` で始まるbacktick path tokenは存在しない（backtick tokenは `ModelWriter`、`LayoutWriteCoordinator`、`LauncherProvider`、`tools/repo-contract/...`、`docs/assessment/...` 等のみ）。AC-5のself-test（DocConsistencyTests含む51 tests）がheadで成功。
- AC-2: **pass**。`AGENTS.md`「Issue駆動・仕様駆動の手順」の手順4が「階層Hの変更では、spec承認後に `plan.md` を作り…階層Mはaccepted軽量spec…だけで実装開始でき、`plan.md` は要求しない」へ階層別に改訂。手順8のpacket要件も「plan revision（階層Hのみ。階層Mはaccepted軽量specのcommitのみ）」へ階層条件付き。正本分担表のplan.md行は「（階層H。階層Mはaccepted軽量specが正本であり `plan.md` を持たない）」（head commit `d9b48f1334` の変更）。手順節末尾に階層参照段落が追加され、workflow文書のRisk tiersへのlink、階層H/M/Lの手順要約、「階層M specには改善するベンチマーク課題と目標（編集負担ベンチマーク、NFR-014）を含める」旨を記載。「高リスクPRの独立エビデンス」節は ea8d57d068 と byte-identical（抽出節のdiffで確認、差分なし）。
- AC-3: **pass**。`specs/_template/spec-lite.md` が存在（74行、新規追加）。frontmatter `tier: M`、Problem、Benchmark（`../../docs/engineering/editing-burden-benchmark.md` とNFR-014を参照し、課題と目標値がなければ受け入れない旨）、Outcome、Scope、Non-goals、Behavior scenarios（2〜4個を目安、失敗時は「no persistent change is made」のzero-write例を含む）、Verification、Accessibility and localization を持つ。Verificationは「owner確認用のスクリーンショット/録画は実機で取得（emulatorは補助証跡としてのみ可）」と、上流UI bridge時の `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` によるPR本文report（baselineの `.md`/`.json` 更新は新baseline採用時のみ）を明記。既存 `specs/_template/spec.md` と `specs/_template/plan.md` は `git diff --name-only ea8d57d068..d9b48f1334` にも PR files にも含まれず、無変更。
- AC-4: **pass**。`CONTEXT.md` の12語（organizer durable status、export-scoped ID、取り込み成功状態、取り込み破棄、手段別失敗投影、原因別remedy、rebind、選択復元初期値、インポート正規化、交換セッション置換確認、scope binding gate、scope-bound依頼破棄）が同一見出しの参照（正本へのlink + 「#444で移動」注記）に置換されている（`grep -n "#444で移動" CONTEXT.md` = 12件）。移動先に定義本文が存在: DESIGN.md §4.2 の Durable status vocabulary（organizer durable status）、spec 204（export-scoped ID）、spec 328（取り込み成功状態・取り込み破棄）、spec 373（手段別失敗投影）、spec 375（原因別remedy・rebind・選択復元初期値）、spec 329（Domain language節を新設してインポート正規化）、spec 205（交換セッション置換確認）、spec 331（scope binding gate）、spec 417（scope-bound依頼破棄）。境界語3種は完全定義のまま残置（export session 137-139行、中断・破棄・キャンセル 216-218行、対象scope凍結 220-222行）。CONTEXT.md前書きは「#444で移動した語の定義は各所有spec/DESIGN.mdが正本、本書は参照、それ以外の語（境界語として残置したexport session、対象scope凍結、中断・破棄・キャンセル等）の正本は引き続き本書」へ更新。移動先のDomain language前書きは移動対象語を個別に正本宣言し、非対象語を含む節（204/205/328/331/417）は「他の用語の正本は引き続き `CONTEXT.md`」を明記。spec 374の「用語の正本は `CONTEXT.md`」記述行は移動後の所有関係を示す文へ更新。Markdown内部link検証（`validate_repo_contract.py`）がheadで成功しており、参照は切れていない。
- AC-5: **pass**。clean worktree（`git worktree add /tmp/audit-468 d9b48f1334`、detached HEADが40桁SHAと一致、未追跡物なし）で4コマンドを独立実行し、すべてexit 0（詳細はExecuted test surface）。CI run 36291847063 も `gh` で独立確認: event pull_request、headSha一致、conclusion success、final-status success、validate-repo-contract success、source jobsはdocs-onlyのためskip。
- AC-6: **pass**。`specs/444-risk-tiered-workflow/spec.md` のAC-6（222-227行）が階層Mの初回適用確認項目（軽量spec作成 → 実装PR本文に Risk tier: M を明示 → 実機のスクリーンショット/録画によるowner確認 → review往復≤2 round → 上流UI bridgeの場合は `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` の計測結果をPR本文へreport）を定義し、Domain language（82-93行）が `--target HEAD` 省略時の挙動（baseline JSON記録値の再計測）と `--enforce-baseline`（baseline超過でfail、増加はmandatory review signal）、baseline `.md`/`.json` 更新は新baseline採用時のみというoracleを本spec内に定義している。
- AC-7: **未達を確認（本PRの範囲外であることを確認）**。spec.md AC-7（228-232行）自身が「このACは本PRでは未達のまま残す。実証後にIssue #444へ記録し、その後で本specのstatusを `implemented` へ遷移する」と明記。specのfrontmatterは `status: accepted`（`implemented` ではない）、plan.mdもAC-7実証後に `implemented` へ遷移する運用を記載。監査対象diffはdocs-onlyであり、初回の階層M適用（軽量手順の実証）はmerge後の別変更でのみ成立するため、本PRで未達であることが正しい状態。

## Executed test surface

すべてauditorが監査対象headで実行した（worktree内コマンドはclean worktree `/tmp/audit-468`、`gh`・section抽出等はmain checkoutからread-onlyで実行）:

- `git worktree add /tmp/audit-468 d9b48f1334` → detached HEAD `d9b48f13349dc19aee48226995b06a1849109753`。
- `python3 tools/repo-contract/validate_repo_contract.py` → `repository contract OK (/private/tmp/audit-468)`、exit 0。
- `python3 tools/repo-contract/test_validate_repo_contract.py` → `Ran 13 tests ... OK`、exit 0。
- `python3 tools/repo-contract/test_validate_high_risk_evidence.py` → `Ran 51 tests ... OK`、exit 0（DocConsistencyTests.test_doc_path_list_matches_validator = 高リスクpath一覧のset-equality検証を含む）。
- `./gradlew spotlessCheck` → 1回目 exit 1（`Configuring project ':searchuilib' without an existing directory is not allowed`。fresh worktreeでsubmodule未初期化の環境要因でありPR欠陥ではない）。`git submodule update --init platform_frameworks_libs_systemui` → exit 0（pinned `6a11ef767998885838a599331b5485f768b3d725`）。再実行 `./gradlew spotlessCheck` → `BUILD SUCCESSFUL`、exit 0。
- `git worktree remove --force /tmp/audit-468` → 削除完了（`git worktree list` で確認）。main worktreeはブランチ `issue-444-risk-tiered-workflow` @ `d9b48f1334`。
- `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` → `{"nameWithOwner":"nunu1733/NunuLauncher","defaultBranchRef":{"name":"main"}}`。
- `gh pr view 468 -R nunu1733/NunuLauncher --json number,state,baseRefName,headRefName,headRefOid,labels,files` → headRefOid = 監査対象SHA（一致）、base `main`、labels空、files 16件（local diffと一致）。
- `gh run view 36291847063 -R nunu1733/NunuLauncher --json event,headSha,status,conclusion,workflowName` → `{"event":"pull_request","headSha":"d9b48f13349dc19aee48226995b06a1849109753","conclusion":"success","status":"completed","workflowName":"CI"}`。`--json jobs` → `final-status` success、`validate-repo-contract` success、source jobsはすべて `skipped`。
- `python3 -c "from validate_high_risk_evidence import classify_pr; classify_pr([], <PR files>)"` → `(False, [])`（高リスクではない）。
- 静的整合の確認（headのcommit済み内容のみ）: 旧新の抽出節diff（workflowの「高リスクPRへの独立エビデンス要求」節と「Fork label vocabulary」節以降、AGENTS.mdの「高リスクPRの独立エビデンス」節）→ いずれも差分なし（byte-identical）。`grep -c '^### 適用条件' docs/project/github-workflow.md` → 1。Risk tiers節のbacktick token scan → `lawnchair/src/`・`src/` 始まりなし。`grep -n "plan.md" docs/project/github-workflow.md AGENTS.md` → 8件、すべて階層条件付きまたは履歴例。`grep -n "#444で移動" CONTEXT.md` → 12件。12語の移動先bold定義と境界語3種の完全定義をfile走査で確認。

docs-only diffのためruntime test laneは存在せず、AC-5の必要表面は上記4コマンドとCI run。高リスク要件は `classify_pr = (False, [])` のとおり本PRに適用されない（本記録は独立実行証跡としての任意の追加）。

## Findings

- blockingな問題なし。AC-1〜AC-6は監査対象head `d9b48f1334` でpass。AC-7はaccepted specの規定どおり本PRでは未達であり、その状態が正しい。
- 残課題（PR外・merge後の運用）: Issue #444は最初の階層M変更でAC-7を実証し結果を記録するまでopenのまま維持し、本PRおよび中間PRは `Refs #444`（`Closes #444` にしない）。spec 444のstatusは `accepted` のままとし、実証後に `implemented` へ遷移する。
- 検証環境の開示（PR欠陥ではない）: fresh worktreeでの `./gradlew spotlessCheck` はsubmodule未初期化だとGradle構成段階で失敗する。AGENTS.mdの検証済みcommand列（`git submodule update --init --recursive` を先に実行）どおりsubmoduleを初期化した後はexit 0。監査記録として初回失敗と再実行成功の両方を開示する。
- 観察（non-blocking・表記一貫性）: Domain language前書きの「非対象語は引き続き `CONTEXT.md` が正本」という第2文は、非対象語を含む節（204/205/328/331/417）にはあるが、移動対象語のみの節（329、373、375）にはない。329/373/375の節には移動対象語（またはその定義内の下位概念）以外の用語がなく、CONTEXT.md側の前書きが一般規則を保持するため所有関係の曖昧さは生じない。Phase 2 Finding 2の文言を全specへ一律に揃えたい場合の任意の追記候補であり、AC-4の失敗ではない。
- audit範囲の注記: 本PRはlabels空・Markdown pathのみで `classify_pr = (False, [])` のため、`high-risk-gate` の独立エビデンス機械要件は本PRに適用されない。本記録はAC-5の独立再実行証跡とAC別の確認記録として作成したもので、実装sessionの主張には依存していない。
