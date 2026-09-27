# Implementation Plan: 直接編集の書込み契約（ADR-0013）の起草・受入と安全規約の適用範囲の明確化

> Issue: #445
> Spec: [spec.md](./spec.md)
> Status: draft
> Risk tier: H — Issue #445（メモ§4.7）が本ADRを階層Hへ割り当てており、現行workflowの階層H条件「既存の書込み経路の契約を変える」に当たる（`AGENTS.md` 安全規約への明示的carve-out、書込み契約の確定）。手順は現行どおり（accepted spec + plan.md、Execution and approval contract）。本PR自体はrisk label・高リスクpath変更なしのdocs-onlyであるため `high-risk-evidence` gateの機械的発火対象ではなく、evidenceはrepository contract gateで足りる（workflow 適用条件の該当性による判断。tier Lへの下げではない）。保守者の指示により独立監査（別session）を追加実施する。
> Revision 2: 2026-09-27 — Phase1 review指摘1〜4とbranch freshnessに対応。AGENTS.md変更を純追記へ変更（指摘2）、ADR-0013に再検証契約・限定解釈根拠を追加（指摘1・3）、writer inventory allowlistをbackstopへ位置づけ直し（指摘4）、現行main（b40888ae17ce8924f23316119888ed6b80693d70）へrebase（branch freshness）。
> Revision 3: 2026-09-27 — 再review（round 2）指摘1〜2に対応。Risk tierをLからHへ訂正（tier Lの根拠を削除し、gate非発火は適用条件の該当性として分離して記載）、Outcome旧方針文の置換をspecへ反映。
> Revision 1: 2026-09-27 — 初版。Phase1 reviewへ提出。

## Current evidence

本planの記載に用いた実コードの根拠は、2026-09-27に本branch上で再検証した。ADR-0013本文に収録する `path:line` 根拠のうち主要なものをスポット検証し、すべて実コードと一致した:

- `src/com/android/launcher3/Workspace.java:2345` — `onDrop` のdrop確定で `mLauncher.getModelWriter().modifyItemInDatabase(...)` を呼ぶ（drag&dropの移動）。
- `src/com/android/launcher3/Workspace.java:3111-3116` — `addToExistingFolderIfNecessary` の分岐（フォルダ参加）。
- `src/com/android/launcher3/model/ModelWriter.java:190` `moveItemInDatabase`、`:252` `modifyItemInDatabase`、`:290` `addItemToDatabase`、`:319` `deleteItemFromDatabase`。
- `src/com/android/launcher3/model/ModelWriter.java:471-475` — `UpdateItemRunnable.runImpl`。単一行の1回 `update` で、明示的なtransactionなし（自ずと原子的）。
- `src/com/android/launcher3/model/ModelWriter.java:486-502` — `UpdateItemsRunnable`。複数行のみ `SQLiteTransaction`、失敗時 `:499-501` で例外握りつぶし（`e.printStackTrace()` のみ）。
- `src/com/android/launcher3/model/ModelWriter.java:570-579` — `ModelTask.executeOnModelThread`。すべてのタスクが `LayoutWriteCoordinator.runOrDefer(MODEL_WRITER, token=0)` でgateされる（fork追加の排他）。
- `src/com/android/launcher3/model/LayoutWriteCoordinator.java:53-59` `OwnerKind`、`:460-497` `runOrDefer`、`:525-527` `defersTokenlessWork`（ORGANIZERとrestore-familyのみtokenless workをdefer）。
- `src/com/android/launcher3/model/ModelDbController.java:298-303` — `newTransaction()`（coordinator leaseをtransaction closeまで保持）。`:915-919` — readiness集計の `organizerLockState` 参照。
- `src/com/android/launcher3/LauncherSettings.java:357` — `ORGANIZER_LOCK_STATE` 列名定義。編集経路の `ContentWriter` にlock列を置く呼出しは存在しない（`ORGANIZER_LOCK_STATE` の `src/` 内参照は列定義・migration（`DatabaseHelper.java:286-292`）・grid migration（`GridSizeMigrationUtil.java:198-214`）・readiness集計のみ）。
- `src/com/android/launcher3/folder/LauncherDelegate.java:99`、`src/com/android/launcher3/folder/Folder.java:1437,1517` — `addOrMoveItemInDatabase`。
- `src/com/android/launcher3/Launcher.java:1503,1593,2048` — `addItemToDatabase`。`:2132,2369` — `deleteItemFromDatabase`。
- `src/com/android/launcher3/DeleteDropTarget.java:120-137` — `onDrop` → `prepareToUndoDelete()` / `completeDrop` → `onDeleteComplete`。`src/com/android/launcher3/views/Snackbar.java:50` — `TIMEOUT_DURATION_MS = 4000`。
- `src/com/android/launcher3/accessibility/LauncherAccessibilityDelegate.java:495` — `MOVE_TO_WORKSPACE` の `moveItemInDatabase`。
- `src/com/android/launcher3/popup/SystemShortcut.java:337` — `UNINSTALL_APP`。
- `lawnchair/src/app/lawnchair/organizer/application/protocol/Ports.kt:51-56` `applyWriteSet`、`:92` `WriterKind`。`lawnchair/src/app/lawnchair/organizer/locks/EffectiveLocks.kt:18-46` — `LockProtectionScope`（`LockEffectNote` の説明機構の土台）。
- `docs/engineering/editing-burden-benchmark.md` — #441で正本が収録済み（削除のUndoは4秒snackbar、移動にはUndoがない。§3.3）。草案が参照していた未収録の `refocus-drafts/product/editing-burden-benchmark.md` はこちらへ置き換える。
- **branch freshness（Phase1 review指摘への対応）**: 現行main `b40888ae17ce8924f23316119888ed6b80693d70`（#444 Risk tiers導入。`AGENTS.md` と `docs/project/github-workflow.md` が変更済み）へrebase済み（2026-09-27）。`git diff <baseline>..HEAD -- src/ lawnchair/src/` が空であることを確認済みであり、本節の `path:line` 根拠は現行mainでも有効である。main側の文書変更（Risk tiers、階層Mの軽量spec手順、階層Hのplan要件の明示）は本planの前提と矛盾しない。

## Design

### 変更対象と変更内容

| File | 変更 | 内容 |
|---|---|---|
| `docs/adr/0013-direct-edit-write-contract.md` | 新設 | #445 付録の承認済み草案（2026-09-24）を基に、下記「ADR本文の修正一覧」を適用して収録。frontmatter `status: accepted` |
| `AGENTS.md` | 安全規約節への純追記 | 既存の節（第1段落・7条件・「favorites…」段落）は1字も変更せず、末尾へ直接編集の1段落（ADR-0013へのcarve-out + fail-closed文）を追記するだけ（spec Scope Revision 2。Phase1 review指摘2） |
| `docs/adr/0004-organizer-lock-persistence.md` | Change historyへ1行追加 | 本文（Decision、Identity rules、Lifecycle等）は不変。関連リンクのみ |
| `specs/445-direct-edit-write-contract/spec.md` `plan.md` | 新設 | 本spec/plan |

コード・テスト・CI workflow・`docs/project/github-workflow.md` の変更はない（seam、interface、migrationの変更なし。rollbackはPR revert）。

### ADR本文の修正一覧（承認済み草案からの差分）

1. frontmatter: `status: proposed` → `status: accepted`。ヘッダのStatus注記を受入済み（#445、本PR）へ更新し、Dateを受入日へ更新する。
2. RF-ID → Issue番号の置換（メモ§3の対応表と #445 本文どおり）: RF-04→#445、RF-06→#446（ADR-0015）、RF-07→#448、RF-08→#449、RF-09→#450、RF-02→#441。ADR-0014草案への参照は「#447のADR-0014」とする（#447が起草・受入を追跡する）。
3. 未収録文書への参照の置換: `refocus-drafts/00-decision-memo.md` への参照は「再焦点化方針メモ（2026-09-24に承認、Revision 5）」（初出時に出典を明記し、以降「メモ§x」）とする。`refocus-drafts/project/agents-md-safety-scope.md` への参照は #445（付録の変更案と本plan）への参照に置き換える（Issue終了条件の指定）。`refocus-drafts/product/editing-burden-benchmark.md` への参照は `docs/engineering/editing-burden-benchmark.md` へ置き換える。
4. 未解決事項の解決の反映:
   - ADR-0004への追記の要否 → 解決済みとして記録（メモ§4.2の承認済み判断: 本文は変えず、Change historyへ関連リンクのみ追加）。Decisionロック節の「受入時にADR-0004への追記（または本ADRからの参照）を必要とする」を、この確定形に更新する。
   - 削除のUndoと上流機構の組合せ → #450への一本化は草案どおり（未解決ではなく所有の確定として残す）。
   - NFR-013のdefer解消後の反映 → #441/#448のspecでの確定は草案どおり残す。
   - `UpdateItemsRunnable` の失敗握りつぶし → 別調査候補として #445 に記録済みの旨へ文言を整える。
   - ワークスペースdragのロック済みアイテム → RF-nextでの扱いは草案どおり残す。
5. 草案ヘッダの「本節は出典の全文であり…」等の草案固有の注記は削除する（`docs/adr/` に収録された本文が正となるため）。

### `AGENTS.md` の変更の機械的確認
変更は末尾1段落の追記のみである。既存節（第1段落、7条件、「favorites…」段落）が1字も変わらないことは、`git diff` 上で既存行が一切現れず、追加行（`+`で始まる行）が追記段落のみであることで機械的に確認する。既存の広い適用対象（fail-closedなdefault）が維持されるため、ADR-0013の対象に分類されない単一item書込み（将来の未分類writerを含む）が規約の対象外になることはない。
### 高リスクpath一覧へのhomeedit追加方針（spec AC-7(a)。メモ§11 B-10。本Issueが正本）

- **判断**: 直接編集のmoduleは仮称 `app.lawnchair.homeedit`（`lawnchair/src/app/lawnchair/homeedit/`）に置かれる見込みであり（メモ§4.8）、このうち**Launcher DBへ書くコードを `lawnchair/src/app/lawnchair/homeedit/write/**` に集約する**ことを予定し、このpathを高リスクpath一覧（`docs/project/github-workflow.md` 適用条件2、`tools/repo-contract/validate_high_risk_evidence.py`）へ追加する。UI（popup等のbridge）と副作用のない計画関数（ADR-0013契約2の純粋検証）は含めない。
- **追加の実行タイミング**: 本PRではworkflow文書を変更しない（homeeditはまだ存在しないため）。**当該pathが初めてリポジトリに現れる実装PR（#448系の最初の書込み実装PR）が、同じPRで高リスクpath一覧へpathを追加する**。一覧は `validate_high_risk_evidence.py` と `DocConsistencyTests` で一致が強制されるため、同じPRで両方を更新する。
- **配置の逸脱時**: #448のspecが書込みコードを別の配置に置く場合は、そのspec/planで実際のpathを確定し、同じ規則（DB書込みpathのみ追加）で高リスク一覧へ追加する。適用対象の原則（「homeeditのうちLauncher DBへ書くコードのpathのみ。UIと副作用のない計算は含めない」）は #445 が正本であり続ける。

### writer inventory allowlist（spec AC-7(b)）
直接編集が新たなDB書込みfileを追加した場合、`tools/repo-contract/validate_writer_inventory.py` のsource-scan allowlistの更新がCIで要求される（既存の仕組みで自動的に検出される。`docs/assessment/issue-44-shared-writer-audit.md`）。

allowlistは「既知patternに一致したDB書込みfileがallowlistに載っていること」を検査する**backstop**であり（Phase1 review指摘4）、新規writerのinventory漏れをfailさせる役割を担う。allowlistはwriterが実際に `LayoutWriteCoordinator` のMODEL_WRITER admissionを通ることまでは検証しないため、ADR-0013 Decision 4の実保証は、(1) 直接編集の書込みが `ModelWriter`/coordinator admissionを通る構造（validation → admission（再検証）→ model/DB変更）、および(2) 要求テスト表のdefer/排他test（「defer後のstale検証」行を含む。将来の実装PRが追加する）によって担保される。この位置づけと、(1)(2)を最初の書込み実装PRのplanが引き継ぐことを本planに明記する。
### 順序制約（spec AC-7(c)）

`Criteria: ADR-0013` 参照は `accepted`（または `implemented`）のADRに対してのみ有効である（`docs/project/github-workflow.md` 高リスクaudit要件の機械検証）。本PRでADR-0013を `accepted` として収録するため、#446（ADR-0015）、#448、#450の実装PRは本PR merge後に開始する。本PR自身はdocs-onlyであり、この順序制約の影響を受けない。

### PR構成とclosing keyword

- 単一PR。base `main`。branch `issue-445-direct-edit-write-contract`。
- ADR-0013の新設、`AGENTS.md` の変更、ADR-0004のChange history追記、spec/planをすべて同じPRに入れる（spec AC-6「ADR-0013の受入と `AGENTS.md` 変更が同じPR群」を満たす）。
- #445 の全終了条件を本PRで満たす最終PRであるため `Closes #445` を使う（AGENTS.md規則7。spec Open questions 6の判断）。merge時にIssueが自動closeされる。
- 本PRはdocs-onlyのため高リスクgateの対象外であるが、保守者の指示により、別session（general-purposeサブエージェント）による独立監査を実施し、結果をPRへ記録する。

## Verification

実行する検証と、PR本文への記録:

1. `python3 tools/repo-contract/validate_repo_contract.py` — markdown内部link、required filesの検証（ADR-0013内の相対参照、spec内リンクを含む）。
2. `python3 tools/repo-contract/test_validate_repo_contract.py` — validator self-test。
3. `git diff origin/main -- AGENTS.md docs/adr/0004-organizer-lock-persistence.md` の目視 + 機械確認 — `AGENTS.md` は既存行がdiffに一切現れず追加行が末尾段落のみであること（既存節の文言不変。指摘2対応後の純追記）、ADR-0004はChange historyの1行追加のみであること。
4. `path:line` 根拠のspot check — 上記「Current evidence」のとおり2026-09-27に実施済み。結果をPRに記録する。
5. CI: docs-onlyのためsource jobはpath filterでskipされ、`validate-repo-contract` のみ実行される。これがdocs-only PRの必要十分なevidenceである（`docs/project/github-workflow.md` Evidence選択原則）。

実施しない検証と理由: build/`spotlessCheck`（markdownのみの変更であり、対象のlint対象に入らない。docs-only PRの既定のevidence範囲）。instrumentation（コード変更なし）。

## リスク

- 規約の適用範囲を広く読んでいるagentが本変更を「安全規約の緩和」と誤解するリスク。PR本文に「適用対象の明示であり、既存7条件の強度は変えない」ことを明記する（#445 リスク節どおり）。
- ADR本文の `path:line` が将来のrebaseでずれるリスク。ADRは「2026-09-27検証」の日付付きで根拠を記録し、ずれは文書の参照更新で対処する（ADR-0013 Contextの性質上、blockerにならない）。
- AGENTS.md変更により、変更前の規約文を暗記したagentが直接編集の実装を規約違反と誤判定するリスク。変更はADR受入と同じPRで即時に行い、混在期間を作らない。

## Review / handoff packet（Phase1時点）

- Issue and all comments: https://github.com/nunu1733/NunuLauncher/issues/445; retrieved at 2026-09-27; state=OPEN; labels=type: research
- Scope type: research/decision
- Accepted spec + commit: 本PRでacceptedへ進める（Phase1 review後にstatus: acceptedへ更新）
- Bug oracle: N/A（research/decision。成果物は文書）
- Plan + revision: specs/445-direct-edit-write-contract/plan.md（本書、Revision 2）
- Base SHA: b40888ae17ce8924f23316119888ed6b80693d70（#444 merge後の現行main。Phase1 reviewのbranch freshness指摘に対応しrebase済み）
- Head SHA: Phase1 push後にPR/Issueへ記録
- Executed evidence: 上記Verification参照
- 次の1手: 改訂版（Revision 2）をpushし、ChatGPTへPhase1再reviewを依頼 → clear後にPhase2（ADR-0013/AGENTS.md/ADR-0004の実装）→ Phase2 review → PR作成・独立監査・merge
