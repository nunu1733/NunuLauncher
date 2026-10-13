# High-risk audit: PR #590 Phase 4 cutover — Lawnchair 16 baseline switch（Epic #516 Phase 4・最終head M3対象の独立audit第2回）

> Status: accepted
> Audit date: 2026-10-13

- Auditor: 独立audit session（ZCode / GLM。実装session・初回audit sessionとは別観点で再実行）。worktree `/Users/nunu/Documents/work2/NunuLauncher-phase4-cutover` は読み取り専用で使用（commit・push・git index変更なし。監査前後で `git status --porcelain` が空であることを確認）。
- PR: https://github.com/nunu1733/NunuLauncher/pull/590（head `106b4f7f60c28e3022b9b4b6a37ba23c2eb845e5` = 本audit対象head M3。base `main`、state OPEN、MERGEABLEを `gh pr view` で確認。labels `risk: layout-data` + `risk: migration` はvalidator出力で高リスク判定根拠として確認）
- Head SHA: 106b4f7f60c28e3022b9b4b6a37ba23c2eb845e5
- 構造: M3 = `-s ours` merge commit、M^1 = S3final 5bbee5ae042ef8f05ee29e0fac9842e8c52e2031、M^2 = R fef1c27d497f2927b8792ea2cbadd8d5f53c6583（= 現PR headRefOid）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/38422689296（conclusion **success**、event pull_request、head_sha = M3、全16 job success、failure job 0件。`run_attempt=2` — 初回attemptの `organizer-instrumentation-category-override-tests` 失敗はrunner SDK download破損のinfra flakeで、rerun（attempt 2）成功。spec非対象節の「rerun成功で解消扱いにしない」はPhase 3残項目に関する規定であり、本件はCI実行基盤の破損に対する同一commitのretryとして区別して記録する）
- Criteria: specs/516-16-rebase-phase4/spec.md AC-1 AC-6 AC-7（統合完全性・正本同期・独立audit）、docs/adr/0018-lawnchair-16-rebase.md ADR-0018（Decision 3 cutover機構・revision 10）。参照: specs/516-16-rebase-phase4/plan.md §4・§5、docs/assessment/issue-516-phase4-cutover.md、AGENTS.md「高リスクPRの独立エビデンス」。

**判定: 受入可（条件付き）** — 条件は、本audit文書のみを含むdocs-only commit EをM3の上に作成し、EでCI final-status successとhigh-risk-gate PASSを確認してからmergeすること（rev4契約どおり）。M3に対する本auditの全検証項目はPASS（発見3件はいずれも非blocking、下記Findings）。

## Scope

対象diff: `fef1c27d49..106b4f7f60`（R..M3）。実体はS3final `5bbee5ae04` のtree全体（15→16 baseline切替: upstream 16-dev `43a21b43d7` へのrebase + 保持stack統合 + main post-replay 28 unit + Phase 4 docs/証跡）と、Rを第2parentとするmerge構造。M3のtreeは `git diff 5bbee5ae04 106b4f7f60` が空であること（0行）を確認済み。

確認したruntime書き込み経路・migration対象: LawnchairLayoutFactory / PersistenceManifest / LauncherLayoutAdapter / ManualOrganizationRun / ExchangeFlowUi / LawnchairBackup の6 production file（main post-replayのproduction fix 5 PR = #565/#579/#581/#583/#587 相当、#583が2 file）がR treeとbyte一致することを検証（本audit項目「unit port完全性」）。DB書込みを含むorganizer適用経路はM3のpull_request CI（organizer-instrumentation-db-migration-tests / restore-capture-tests / reservation-recovery-tests 等9 surface + organizer-unit-tests 2000 tests）が同一tree上でsuccessであることを以て検証済みとみなす（rev4: CI対象をMへ移動）。

## Criteria check

1. **M3 merge構造（AC-1 / plan §4手順4）— PASS**
   - `git merge-base --is-ancestor fef1c27d49 106b4f7f60` → OK（R が M3 のancestor）。
   - `git cat-file -p 106b4f7f60` → parent 1 = `5bbee5ae042ef8f05ee29e0fac9842e8c52e2031`（S3final）、parent 2 = `fef1c27d497f2927b8792ea2cbadd8d5f53c6583`（R）。
   - `git diff 5bbee5ae04 106b4f7f60 --stat | wc -l` → **0**（M tree == S tree。`-s ours` 契約どおり）。
   - tag `pre-lawnchair16-20261013`^{commit} = `fef1c27d497f2927b8792ea2cbadd8d5f53c6583` == R == M^2 を確認。
2. **unit port完全性（AC-1）— PASS**
   - `git log --first-parent --oneline fef1c27d49..5bbee5ae04 | wc -l` → **1498 commit**（S は R をancestorに持たないため、この範囲はS側の全履歴 — upstream 43a21b43d7起点のreplay/rebase系列・PR merge点・docs commit — を含む。28 main post-replay unitの対照は初回auditおよびcutover doc §2が正本）。
   - production fix 6 pathの `git diff 106b4f7f60:<path> fef1c27d49:<path>` が **全て空（IDENTICAL）**: `lawnchair/src/app/lawnchair/LawnchairLayoutFactory.kt`、`lawnchair/src/app/lawnchair/organizer/application/canonical/PersistenceManifest.kt`、`lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt`、`lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`、`lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`、`lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt`。（監査指示では「production fix 5件」と6 pathが列挙されていたが、初回audit対照どおり5 PR→6 fileの対応で全数検証済み）
3. **保持branch全headのancestry（AC-1）— PASS**: `e8aced7dbe` / `bc0171f253` / `54a7504230` / `57d4338bb5` / `33270ede1b` / `000c26903b` の6 headすべてが `git merge-base --is-ancestor` でM3のancestorと判定。
4. **G3'（plan §5）— PASS**: worktree HEADが `106b4f7f60c28e3022b9b4b6a37ba23c2eb845e5` であることを確認の上、`python3 tools/repo-contract/measure_upstream_patch_surface.py --verify` → `PASS: measurement completed with complete bridge ownership.`（EXIT=0）。upstream `43a21b43d7cc7850ab54e14b1a57dc9646685f35`、target = 記録済みmain_commit `a9e8c0764e80ea47d587f90408f1783c4a87f0e5`（Findings F2参照）。
5. **T9証跡（AC-3相当・plan §5 G4'）— PASS**: `docs/assessment/516-phase4-t9-evidence/reads/read{0..3}-favorites.sql` の6組すべての組合せdiffが空（4 fileのsha256が全て `481ae86df203db925be68761f1b8735f0591637cd9600a5aced183309c7a47ae` で一致）。README記載のAPK identity（`Lawnchair.15.Dev.(fef1c27).github.debug.apk` / `Lawnchair.16.Dev.(88218eb).github.debug.apk`）が `apk-shas.txt` の2 entry（`55c6206c…` / `53e8f7cf…`）と一致。READMEはsha256本体をapk-shas.txtへ委譲しており（inline値なし）、矛盾なし。T9 APKはrev4やり直しによりS₂ tree `88218ebbca` をsourceとする（README・cutover doc §3 G4'行の記録どおり）。
6. **契約rev4の整合（AC-1/AC-7/plan）— PASS**
   - `specs/516-16-rebase-phase4/spec.md` frontmatter `status: accepted`。
   - `specs/516-16-rebase-phase4/plan.md` frontmatter accepted（Status行にrev4補訂 — PR #590 review round 1対応 — を明記）。
   - plan §4 はrev4順（R固定→S gates→R再確認→M作成→**M push + G5' CI→G6' audit(M)→E = audit文書のみのdocs-only commit**→merge前再確認→O→最終証明）を規定。
   - spec AC-1 がrev4機構（M CI + AC-7 audit at M + E docs-only + O tree==E tree + main進行時のG1'〜G4'再実行）を、AC-7 が「Mに対する別session audit・`validate_high_risk_evidence.py` のschema（`Head SHA:` 欄・`CI run:` 欄・必須heading）・M..E差分1file条件」を規定。
   - `docs/assessment/issue-516-phase4-cutover.md` §5 にrev4やり直し記録（初回 S=`8fea123177` → E=`dec3ee4929` → M=`11b5453447` のCI run 38404418328/38404418234がrebase規模制限でfail → 順序改訂・旧E/M force-push撤去）。S₁→S₃のgateやり直しchainは§3 gates表（S₁=`ac2b985860`・S₂=`88218ebbca`・S₃=`e7798ecf1f`。S₃のSHAはFindings F3参照）。
7. **CI（G5'・AC-1/AC-7）— PASS**: run [38422689296](https://github.com/nunu1733/NunuLauncher/actions/runs/38422689296) は conclusion=success・head_sha=106b4f7f60…・failure jobなし。job内訳: organizer-instrumentation×9（category-override / restore-capture / db-migration / manual-organization-ui / method-choice-journey / exchange-import-ui / reservation-recovery / production-input / shared-writer）+ organizer-unit-tests + check-style + build-debug-apk + validate-repo-contract + changes + **final-status**、すべてsuccess。infra flake経緯は冒頭CI run欄に記録。
8. **正本同期（AC-6）— PASS（F1/F2の観察付き）**
   - `AGENTS.md`（プロジェクト状態）・`docs/engineering/upstream-strategy.md`（baseline commit/tag pin、L17-18）・`docs/engineering/building.md`（L4）のbaselineがいずれも `43a21b43d7cc7850ab54e14b1a57dc9646685f35`。
   - patch-surface baseline JSON（`docs/assessment/upstream-patch-surface-baseline.json`）の `main_commit` は **`a9e8c0764e80ea47d587f90408f1783c4a87f0e5`**（F2参照。`5bbee5ae04` ではない）。
   - `git grep -n 505dbc40 106b4f7f60 -- docs/engineering AGENTS.md` → **6件**（期待0件に対し。F1参照。全てR時点から存在する既存参照で、cutoverによる新規追加は0件）。ADR-0018・assessment配下の505dbc40言及は15系由来の歴史記述として適切。
9. **validator（AGENTS.md高リスク要件の機械検証）— 期待どおりfail-closedを確認**: `validate_high_risk_evidence.py --help` に `--base-sha`（「PR base commit; enables the local three-dot diff instead of the 300-file-capped gh pr diff」= 300file上限fix）が存在。実行（`--repo nunu1733/NunuLauncher --pr-number 590 --head-sha 106b4f7f60… --base-sha fef1c27d49…`、worktree内）→ 「high-risk PR (label 'risk: layout-data'; label 'risk: migration'; high-risk path change(s): … 14 total)」と分類した上で `FAIL: no docs/assessment/pr-590-<slug>.md audit record for this PR`（EXIT=1）。audit文書がEで追加される前の時点ではこのfailが正しい挙動（fail-closed）。案内メッセージもrev4契約（audit後はdocs-only commitのみ許容）と整合。

## Executed test surface

gradleは実行していない（audit指示どおり）。実行した検証コマンドと結果:

- `git -C <worktree> rev-parse HEAD` / `git status --porcelain` → `106b4f7f60c28e3022b9b4b6a37ba23c2eb845e5` / 空（clean）。
- `git merge-base --is-ancestor fef1c27d49 106b4f7f60` → 終了コード0。
- `git cat-file -p 106b4f7f60 | grep parent` → parent 1 = 5bbee5ae042e…、parent 2 = fef1c27d497f…。
- `git diff 5bbee5ae04 106b4f7f60 --stat | wc -l` → 0。
- `git log --first-parent --oneline fef1c27d49..5bbee5ae04 | wc -l` → 1498。
- `git diff 106b4f7f60:<path> fef1c27d49:<path>`（6 path） → 全て空。
- `git merge-base --is-ancestor <head> 106b4f7f60`（6 branch head） → 全てancestor。
- `python3 tools/repo-contract/measure_upstream_patch_surface.py --verify`（worktree HEAD=106b4f7f60で実行） → PASS、EXIT=0。
- `diff -q` による `reads/read{0..3}-favorites.sql` 全6組 → 全て同一。`shasum -a 256` 4 file → 同一hash。README APK identity ↔ `apk-shas.txt` 照合 → 一致。
- `gh run view 38422689296 --repo nunu1733/NunuLauncher --json conclusion,jobs,headSha,status` → success / failure job 0件 / headSha=106b4f7f60…。`gh api repos/nunu1733/NunuLauncher/actions/runs/38422689296` → run_attempt=2。
- `git rev-parse pre-lawnchair16-20261013^{commit}` → fef1c27d497f…（R一致）。`gh pr view 590 --repo nunu1733/NunuLauncher` → headRefOid=106b4f7f60…、MERGEABLE。
- `git grep -n 505dbc40 106b4f7f60 -- docs/engineering AGENTS.md` → 6件（F1）。`git grep -c 505dbc40 fef1c27d49 -- <同3 file>` → 1/4/1件（同一の既存参照）。
- `python3 tools/repo-contract/validate_high_risk_evidence.py --help` → `--base-sha` 存在。同validator本実行 → high-risk分類後「no audit record」でFAIL、EXIT=1（E前の正しいfail-closed）。
- 補助: `git log -1 --format=…` / `git diff --stat` による e7798ecf1f ↔ 9ead3203fa の対比（tree同一 `ce9d8986b6…`、parent違い。F3）。

## Findings

blocking となる発見はなし。次の3件を記録する（いずれも非blocking・後続docs対応）。

- **F1（AC-6・低）**: `git grep 505dbc40 106b4f7f60 -- docs/engineering AGENTS.md` が6件（期待0件）。内訳: `docs/engineering/package-provenance.md` 4件（旧baselineでのprovenance突合の記録 — 歴史記述として適切）、`docs/engineering/organizer-diagnostics.md:5` と `docs/engineering/performance-budgets.md:5` の `> Baseline: Lawnchair v15.0.0-beta3.0 / commit 505dbc40…` ヘッダ各1件。この2ヘッダは現行baselineの主張として陳腐化している可能性があるが、spec AC-6の必須更新対象（upstream-strategy.md / building.md / patch-surface baseline / ADR-0018 / AGENTS.md / assessment）には含まれず、**R（fef1c27d49）時点から同一内容・同一件数が存在する既存参照**（`git grep -c` で1/4/1件と確認）でcutoverが新規に追加したものではない。E以後のbranch凍結規約により本PRでは修正せず、merge後のdocs PRでのヘッダ更新または「歴史記述」である旨の明示を推奨する。
- **F2（AC-6・低・設計として自己無矛盾）**: patch-surface baseline JSONの `main_commit` は `a9e8c0764e80…` であり、監査指示の期待値 `5bbee5ae04` と一致しない。経緯: S3finalのtip `5bbee5ae04` はJSON re-point専用のdocs-only commitであり、commitは自身のSHAを値に持てないため、JSONは1commit下の `a9e8c0764e`（docs commit直前のS3 head）をanchorする。`docs/` は計測対象から除外されるため `a9e8c0764e` と `5bbee5ae04` のcounted surfaceは同一で、`--verify` は記録値でPASSする（本audit項目4）。S3final tipとanchorの1commit差は/docs-onlyであることを殖えて記録するもので、契約違反ではない。
- **F3（証跡トレーサビリティ・低）**: cutover doc §3 gates表がG1'/G2'の実施対象として引用する `S₃=e7798ecf1f` は **M3のancestorに存在しない**（初回S3試行系列 — parent `e04790fde8` = 撤去された初回S3 tip — に属するcommit）。同一subject・**同一tree**（`ce9d8986b6…`）の `9ead3203fa` がM3のfirst-parent上に存在する（やり直し時のrewrite）。実害はない: gate対象treeの内容はM3 ancestry内に同値で存在し、かつrev4ではM3自身のpull_request CI（run 38422689296、tree == S3final tree）が正本のsource tree証明になる。ただし記録されたSHAでgateを再追跡できないため、merge後のdocs PRでcutover doc §3のS₃表記を `9ead3203fa`（または「初回試行SHA・現branchでは同内容commit」との注記）へ訂正することを推奨する。

- **補記（監査指示の前提差異・影響なし）**: 監査指示は「production fix 5件」としつつ6 pathを列挙したが、初回audit対照（#583が2 file）どおり6 path全数で検証し全てIDENTICALを確認した。unit数1498はS側全履歴（replay系列・merge点・docs含む）のfirst-parent countであり、main post-replay 28 unit対照は初回auditおよびcutover doc §2が正本として維持される。

- **残課題（merge条件）**: 本audit文書を唯一の内容とするdocs-only commit EをM3上に作成（`git diff --name-only M3 E` が `docs/assessment/pr-590-16-baseline-cutover.md` 等のaudit doc 1fileのみ）し、Eでpull_request CI final-status successとhigh-risk-gate PASSを確認してからmergeする。merge直前に plan §4手順8の再確認（`origin/main == R_candidate == tag == M^2`）を実施すること。
