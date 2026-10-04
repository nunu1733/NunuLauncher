# Spec: 16-dev rebase実行（Epic #516 Phase 2）

> Status: accepted（2026-10-04。PR #533 review round 5でblocking解消・Approved（[review](https://github.com/nunu1733/NunuLauncher/pull/533#issuecomment-5976918255)。head `be377805fe85b46175aef3f33c0f6ba9005aa5ca` を確認）。受入は本PR #533のmergeで完了する）
> Issue: [#532](https://github.com/nunu1733/NunuLauncher/issues/532)（Epic #516）
> Risk tier: **H** — 上流model/loader bridge、schema、migration、backup/restore経路の全てが対象。判定理由: 変更pathがgithub-workflowの高リスクpath一覧に広く当たるため。
> 上位契約: [ADR-0018](../../docs/adr/0018-lawnchair-16-rebase.md)（accepted）、[Phase 0 assessment §5 disposition](../../docs/assessment/issue-516-16-rebase-phase0-research.md)、[#522 assessment](../../docs/assessment/issue-522-rebase-data-compatibility.md)（accepted）。本specはこれらを反復せず、実行の受入条件のみを定義する。
> External reference scan: 省略（理由: 既存ADR/specの実行手順の具体化であり、新規seam・新規契約・platform APIの新規扱いを含まない）

## 1. 目的と範囲

baseline `505dbc40` 以降のfork変更全量を、16-dev候補 `43a21b43d7cc7850ab54e14b1a57dc9646685f35` 上へreplayし、fork ancestryを維持したproduction rebase branchを完成させる。

対象: main `38262fb74d144f4655dfbf01c0084e44c86560be` の first-parent 300単位（baseline後。親数による内訳はmerge commit 234 / single-parent commit 66。PR由来か直接commitかはsource→replay対応表で確認する）。
非対象: baseline側のみに存在する33 upstream commits（ADR-0018 Decision 2。Nova restore 2挙動は#522 §4の採用portとして含む、icon shadow submodule pinはplan.mdの判断点）、API 37 quickstep有効化・targetSdk behavior change対応の実装（rebase後統合）、Phase 3再検証・Phase 4切替。

## 2. 受入条件（AC）

- **AC-1（replay完了とancestry）**: 300単位すべてがrebase branchへ適用され、`git merge-base --is-ancestor 43a21b43 <rebase head>` が真である。各単位の適用結果（適用/conflict解消/skip理由）が `replay-log.md` に記録されている。
- **AC-2（disposition遵守）**: conflict解消はPhase 0 §5のdisposition（keep 57 / adapt 48 / drop 0）に従う。rebase中に挙動変更が必要になった単位は、rebase差分で解決せずaccepted ADR/specの改訂を経る。ログに「挙動変更なし」を確認した記録がある。
- **AC-3（無関係差分の混入禁止）**: rebase差分にformat/package移動・無関係cleanupが含まれない。`git diff 43a21b43..<rebase head>` の各pathが、いずれかのreplay単位由来であることで説明できる。
- **AC-4（検証gate）**: `./gradlew assembleLawnWithQuickstepGithubDebug` と `spotlessCheck` 成功、organizer unit test gate成功、**patch-surface ownership gate**（旧105 inventoryのowner写像によるcandidate ownership inventoryを `--baseline-file` に渡し、全non-excluded production差分がowner分類済みで増分・移動がreview済み。accepted baselineの再採択はPhase 4）、[#522 assessment](../../docs/assessment/issue-522-rebase-data-compatibility.md) T1〜T9の実行（T4/T5/T7/T8は追加oracleの実装を含む。実行表は[plan §7](./plan.md)）、CI merge gate（final-status）成功。
- **AC-5（正本同期）**: replay完了時点で、upstream-patch-surface-baselineの再採択（Phase 4）以外の正本が矛盾なく、conflict解消で生じた判断がADR/specに反映されている。

## 3. 失敗時の振る舞い

- 16-dev headがreplay中に前進した場合: replayを続行しない。anchor刷新はADR-0018改訂によってのみ行う（Decision 1）。
- conflict解消で契約上の判断が必要になった場合: 当該単位で停止し、replay-logへ判断要求を記録して、ADR/spec改訂後に再開する。merge都合で契約を変えない。
- 検証gateで失敗した場合: 当該単位範囲の再解消を行い、gateの再実行を要求する。分類なきrerun-greenは証拠にならない（quality-strategy）。

## 4. Test oracle

plan.mdの検証gate表（AC-4）を正本とする。T1〜T9の各oracleの内容は [#522 assessment](../../docs/assessment/issue-522-rebase-data-compatibility.md) が所有する。
