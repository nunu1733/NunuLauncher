# Plan: 16-dev rebase実行（Epic #516 Phase 2 / Issue #532）

> Status: draft（review受理後にacceptedへ）
> Risk tier: H（[spec.md](./spec.md)参照）
> 対象revision: main `38262fb74d144f4655dfbf01c0084e44c86560be`、anchor upstream `43a21b43d7cc7850ab54e14b1a57dc9646685f35`

## 1. 現在codeの根拠

- fork変更の全量: baseline `505dbc40` 以降のmain first-parent 300単位（PR merge 296 + 直接commit 4）。
- 上流変更の全量: [Phase 0 assessment](../../docs/assessment/issue-516-16-rebase-phase0-research.md) §3（5,166 files）。
- 再適用対象: bridge inventory 105 path（fork追加49 / upstream無変更8 / upstream変更45 / upstream削除3）。
- 競合面の判定材料: 本plan §3のインベントリ（各単位が48 adapt pathに触れるかでA/B/C分類。生成日 2026-10-04、生成scriptは `git diff-tree` first-parent diff × adapt path集合）。

## 2. 変更module / seam / 方式

- **rebase branch**: `issue-516-rebase-16-dev`（`43a21b43` から作成済み）。`main` はrebase完了・検証完了まで変更しない。
- **replay単位**: per-PR squash replay。merge commit `M` には `git cherry-pick -m 1 <M>`（first parent基準の差分）を適用し、messageを `PR #<番号>: <title>` へrewordする。直接commitは単純cherry-pickする。元commit SHAはreplay-logに記録する。
- **順序**: first-parentの現在順（時系列）を厳守する。並べ替えによるsemantic conflictを避ける。
- **submodule**: `platform_frameworks_libs_systemui` のpinは、C群のreplay中に ADR-0018 Decision 2のとおり判断する（16-dev pin `7d9e92bd` + icon shadow修正の再適用、または修正済み `6a11ef76` 維持）。判断まで当該単位のsubmodule差分を適用しない。

## 3. インベントリと実行順（2026-10-04時点。実行中の増分はreplay-logへ記録）

| 群 | 単位数 | 定義 | 取扱い |
|---|---:|---|---|
| A | 132 | docs/specs/tools/.github等の非production単位 | 競合なし想定。適用して次へ |
| B | 86 | production差分だがadapt path非接触（fork-owned新規file中心） | 競合なし想定。依存先API変化のcompile追従は後続単位またはgate時に解消 |
| C | 82 | 48 adapt pathのいずれかに接触 | conflict解消の主体。dispositionに従い再表現する |

C群の代表（replay-logへ全量を記録）: PR #79（deck退役本体、95 files × 15 adapt paths）、#341/#498（user categories / destination policy）、#481（edit undo、`Folder.java`/`ModelWriter.java`）、#476/#486（edit surface / home entry）、#75/#77（schema・grid migration）、#160/#314/#319（model reload/LoaderTask）、#464（benchmark、build.gradle/settings.gradle）。`LauncherModel.java`→`.kt` 化・`GridSizeMigrationUtil` 分割・`MainThreadInitializedObject` onPostInit削除の3点（Phase 0 §5「要対応の3点」）がC群のsemantic解消の中心である。

## 4. conflict解消手順

1. conflictが発生したら、当該pathのdisposition（[Phase 0 §5](../../docs/assessment/issue-516-16-rebase-phase0-research.md)）を確認する。
2. **keep**: fork側内容をそのまま復元する（patch競合なしの前提が崩れた場合はadaptiveへ再判定し、replay-logに記録）。
3. **adapt**: 16-dev側の新構造を正とし、forkの契約（各bridge groupのowner ADR/specの受入条件）を満たす形で再表現する。重点3点: `LauncherModel.kt` への `OrganizerModelReloadAdapter` 接続、`GridSizeMigrationDBController/Logic` への `GridMigration*` 接続（transaction ownership [spec 118](../../specs/118-sqlite-migration-transaction-audit/spec.md)維持）、`MainThreadInitializedObject` のonPostInit代替hook（Issue #14要件）。
4. **drop**: 該当なし（Phase 0確定）。16-dev代替を発見した場合は停止してPhase 0 assessment/ADR-0018を改訂する。
5. Nova restore 2挙動（#522 §4採用port）: `NovaBackupConverter.kt` のC単位で、警告/toggleと座標丸め・rows補償を一組で16-dev構造へ実装する。fixture境界値oracle（T4）を同時に追加する。
6. 解消で挙動変更が必要になった場合: その単位で停止し、replay-logへ「ADR/spec改訂要求」を記録する。改訂accepted後に再開する。

## 5. 実行の記録（replay-log）

`specs/516-16-rebase-phase2/replay-log.md` を正本として、単位ごとに次を追記する: 単位番号/PR番号、元commit SHA、分類（A/B/C）、結果（適用/conflict解消内容/skip理由）、挙動変更の有無。sessionをまたぐ作業はこのlogでhandoffする（[workflow Agent handoff](../../docs/project/github-workflow.md)）。

## 6. migration / rollback

- Launcher DB schemaはfork SCHEMA_VERSION 33を維持する（[#522 assessment](../../docs/assessment/issue-522-rebase-data-compatibility.md)。upstream 32→32はupstream比較に限る）。schema変更を要求する解消はADR-0004と同様の受入を要求する。
- rollback: rebase branchは作業branchであり、破棄はbranch削除でよい（main不変）。baseline切替のrollback点はADR-0018 Decision 5（Phase 4）。

## 7. 検証（AC-4 gate。全replay完了後）

| gate | command / 方法 |
|---|---|
| G1 build/format | `./gradlew assembleLawnWithQuickstepGithubDebug`、`./gradlew spotlessCheck` |
| G2 unit | organizer unit test gate（[quality-strategy](../../docs/engineering/quality-strategy.md)のcommand） |
| G3 surface | `python3 tools/repo-contract/measure_upstream_patch_surface.py --upstream 43a21b43… --target <rebase head>`（受入inventory 105 pathの再現を確認。expected再採択はPhase 4） |
| G4 data互換 | [#522 assessment](../../docs/assessment/issue-522-rebase-data-compatibility.md) T1〜T9（upgrade/downgrade/backup-restore/recovery/Nova oracle） |
| G5 CI | PRのCI merge gate（final-status）。本spec/plan自体はdocs-onlyだが、replay結果のPRは高リスクgateの対象 |

## 8. 実行順のまとめ

1. 本plan/specのreview・accepted（本PR）
2. A群（132単位）→ B群（86単位）を時系列でcherry-pick（replay-logへ逐次記録）
3. C群（82単位）を時系列でcherry-pickし、§4の手順で解消
4. submodule pin判断（§2）
5. G1〜G5 gate → PR（`Refs #516`、`Refs #532`。Phase 3検証はEpic側で継続のため、本PRは#532の終了条件が満たされた時点で `Closes #532` となる）
