# Plan: Lawnchair 16 rebase Phase 4 — cutover実行（Epic #516）

> Status: proposed（specと同じPRのreviewでaccept判定後にacceptedへ遷移）
> Risk tier: H（[spec.md](./spec.md)参照）
> 対象revision: main `85b86610b4731f5f06a116d1bc017385576bf967`（本PR merge後のmain headをplan実行開始時のR候補として扱う）
> 方針の正本: [ADR-0018 revision 10](../../docs/adr/0018-lawnchair-16-rebase.md)。本planは実行手順（統合順・port対照表・gates・T9手順・正本同期）を固定する。

## 1. 対象revisionとstack heads（2026-10-13時点の観測値）

| branch | head | 由来PR | 役割 |
|---|---|---|---|
| `main` | `85b86610b4` | — | 15 baseline（rollback点Rの母体） |
| `issue-532-phase2-restart` | `e8aced7dbe` | #535 | Phase 2 replay根幹（S0〜S4・G1〜G5） |
| `issue-526-target37-ui` | `bc0171f253` | #538 | #526 target 37 fork UI契約 |
| `issue-527-bal-granular` | `54a7504230` | #541 | #527 BAL granular移行 |
| `issue-528-lan-network` | `57d4338bb5` | #543 | #528 LAN permission/ECH/CT |
| `issue-524-api37-quickstep` | `e214b7b190` | #546 | #524 runtime検証とADR rev8（559 stackのancestor） |
| `issue-545-api37-provider-fix` | `33270ede1b` | #550 | #545 provider修復・#554 parcel（#557）・#562（#567）・ADR rev9/maxSdk 37 |
| `issue-559-api36-recents-compat` | `33b4c061c5` | #571 | #559修復＋#563 fix＋#555/#562 cherry-pick |
| `issue-574-contacts-data-projection` | `000c26903b` | #575 | #574 contacts検索修復（559 stack向け） |

merge-base（559 stack, main）= `b011d84ca9`（旧upstream分岐点。通常三方merge不可の根拠。ADR Decision 3 rev10）。

## 2. 統合branch構築（merge順と既知conflict）

branch: `issue-516-phase4-cutover`（worktree `/Users/nunu/Documents/work2/NunuLauncher-phase4-cutover`）。

1. **PR #575をGitHub上で `issue-559-api36-recents-compat` へmerge**（checks pass・MERGEABLE確認済み。main不変）。merge後の559 headを統合基点とする。
2. 統合branchを559新headから作成し、次の順でmerge（各merge前にdry-runでconflict集合を再確認し、結果を実行記録へ残す）:
   1. `issue-545-api37-provider-fix` — 既知conflict: `quickstep/src/com/android/quickstep/views/TaskContainer.kt`（#562が両側に存在するため。559側cherry-pickと545側PR #567 mergeの同一契約内容を `: Unit` 式body明示の形へ手動統合）。
   2. `issue-526-target37-ui` — dry-run conflict 0件。
   3. `issue-527-bal-granular` — dry-run conflict 0件。
   4. `issue-528-lan-network` — 既知conflict: `.github/workflows/ci.yml`（両側とも532由来の追加。528のlane/filter追加を統合側ci.ymlへunion。per-class invoker構造とclass filter集合の和を維持）。
3. conflict解消の規則: ADR-0018 Decision 4のdisposition、各bridge groupのowner spec/ADR受入条件、Phase 0 assessment §5に従う。解消で契約変更が必要になった場合は停止してADR/specへ戻す（plan §4.6相当）。

## 3. main post-replay unitのport対照表

mainのreplay基点は `38262fb74d`（Phase 2 plan対象revision）。これ以降のfirst-parent全unitを時系列順にportする。**本PR（Phase 4 spec/plan/ADR rev10）のmergeで対照表を確定**し、実行時に各unitの適用commitと方式を記録する。

| # | unit | 種別 | 16側適用方式 |
|---|---|---|---|
| P01 | `b759506e28` (#533) phase2 plan rev1 | docs | 16側にplan rev2が存在 → identity確認の上skip記録 |
| P02 | `15ae5bf917` (#534) ADR rev6/7・model assessment・plan rev2 | docs | 16側に同期済み（S0要件）→ identity確認の上skip記録 |
| P03 | `1ec1522aad` (#536) specs/526 | docs | port（16側に526 branchの実装はあるがspec dirは不在） |
| P04 | `6ce7c86222` (#539) specs/526 rev2 | docs | port |
| P05 | `05f23ec748` (#540) specs/527 | docs | port |
| P06 | `ba403917a1` (#542) specs/528 | docs | port |
| P07 | `8b56ba5029` (#544) specs/524 | docs | port |
| P08 | `db7ff0c03a` (#547) specs/524 addendum | docs | port |
| P09 | `b8ef650249` (#548) specs/545 | docs | port |
| P10 | `0f0e2f7644` (#549) specs/545 addendum | docs | port |
| P11 | `3b940cfe0d` (#551) specs/545 addendum2 | docs | port |
| P12 | `9374adbe71` (#552) specs/545 addendum3 | docs | port |
| P13 | `ac02195c3d` (#553) specs/545 addendum4 | docs | port |
| P14 | `9cdb98828c` (#556) specs/554 | docs | port |
| P15 | `a3aaf3925a` (#560) specs/554 rev | docs | port |
| P16 | `6d3cd6c59f` (#564) issue-559 assessment | docs | port（559側supersede版と衝突するfileは新規main内容を取り込みつつ16側結論を優先。差分を記録） |
| P17 | `bed45139da` (#566) 563 docs | docs | port（559側PR #571内docsと重複 → 新しい方へ統合） |
| P18 | `2c60e1b101` (#568) 563 docs | docs | 同上 |
| P19 | `0c737bd45c` (#569) 563 docs | docs | 同上 |
| P20 | `3b67953d47` (#570) **#565 cold-start deadlock fix** | production+test+spec+docs | **adapt必須**。main本番commit `28b5800a88`（fontManager per-call解決）を16構造へ適用。16適応の既存実績: validation branch `issue-516-phase3-ui-validation` の `f2592577f9`（同一修正）。test `0fe3972d35`・spec `13ba657669`・docs `4b732b43ad` も追従 |
| P21 | `8fe67e7650` (#572) 563 docs | docs | port |
| P22 | `7985a45a6e` (#578) #577 benchmark seeder | test | adapt（tests/organizer側。16側test構造へ適用） |
| P23 | `840603371c` (#580) **#579 folder capture** | production | **adapt必須**。16適応の既存実績: `41d8753d7a`（最終main内容と差分確認の上適用） |
| P24 | `0ef86713b9` (#582) **#581 QSB-off page inventory** | production | **adapt必須**。16適応の既存実績: `ae69715379` |
| P25 | `c3898c74f3` (#584) **#583 settleImport crash** | production+test+docs | **adapt必須**。**最終main内容（interleave RED修正・Handler注入含む）を正とする**。`75492ec640` は旧版のため参考のみ |
| P26 | `a9ec830a81` (#588) **#587 cold settings restore** | production+test+docs | **adapt必須**。**最終main内容（source `059f498ec2` 相当）を正とする**。`62cd636607` は旧版のため参考のみ |
| P27 | `85b86610b4` (#573) Phase 3 closure matrix・curated evidence | docs | port全量（16側に競合なし） |
| P28 | 本PR（Phase 4 spec/plan/ADR rev10） | docs | port（merge後にcherry-pick） |

規則:

- docs unitは原則 `git cherry-pick -m 1`（merge unit）で適用。16側に同じ正本の新しいrevisionが既に存在する場合（P01/P02、P16〜P19の重複file）は内容比較を記録した上でskip/統合し、黙って落とさない。
- production unitは**最終main内容を契約**として16構造へ適応する。適応は既存16適応実績（validation branch）と差分比較し、古い適応で新しい契約を上書きしない。
- port適用後、対照表に各unitの「適用commit SHA / 方式（clean・adapt・skip+理由）」を記録する（実行記録§10）。これがAC-1機械検証(i)の入力。

## 4. cutover merge機構（ADR Decision 3 rev10の実行手順）

1. 統合headでgates（§5）全green後、`git merge -s ours main`（main=当時のmain head。R候補）を統合branch上で実行。merge commit message: `Cutover: Lawnchair 16 baseline switch (Epic #516 Phase 4)` + 対照表要旨。
2. 機械検証: (i) `git diff <統合head> <merge commit>` が空、(ii) `git diff --name-status main..<merge commit>` のD entryがすべて説明済み、(iii) 保持stack各branchがmerge commitのancestorであること（`git merge-base --is-ancestor`）。
3. tag: main到達直前に、cutover直前main headへ `pre-lawnchair16-<YYYYMMDD>` を付与（ADR Decision 5。merge commit自身をtag化しない）。
4. PR: 統合branch → main（`Closes #516` はEpic終了条件がすべて満たされる最終PRのみ。本cutover PRはexit criteria残照合のため `Refs #516` とし、Epic closeは正本同期・処遇記録完了後の判断）。
5. merge方式: GitHub merge commit（PRのmergeでmainへ単一mergeとして到達。fast-forward不可の場合もmerge commitで統一）。

## 5. gates

| gate | 内容 |
|---|---|
| G1' build/format | `./gradlew assembleLawnWithQuickstepGithubDebug` ＋ `./gradlew spotlessCheck`（統合head。submodule `platform_frameworks_libs_systemui` @ `7d9e92bd` をinit） |
| G2' unit | organizer unit test gate（quality-strategyのcommand。`--tests 'app.lawnchair.organizer.*'`） |
| G3' ownership inventory | `measure_upstream_patch_surface.py` をupstream `43a21b43d7`・target 統合headで実行し、candidate baseline（bridge groupsの新path写像・pin再記録）で全counted pathが分類済みであることを確認。**正式採択（JSON/md更新）はcutover PRに含める**（AC-6） |
| G4' T9 | §6のprotocol（AC-3）。emulator leg必須・実機legはAC-4 |
| G5' CI | cutover PR headの `pull_request` CI final-status success |
| G6' 独立audit | 別sessionによるaudit ＋ `docs/assessment/pr-<PR>-<slug>.md` ＋ high-risk-gate workflow PASS（label: `risk: layout-data`, `risk: migration`） |
| G7' rollback証明 | AC-5（revert dry-run tree hash一致 ＋ T9 rollback leg） |

## 6. T9 protocol（emulator leg）

環境: 新規disposable API 36 AVD（google_apis・arm64。既存稼働emulator 5554/5556と保護AVDは使用しない）。APKは両方とも同一local debug keystoreでbuild（署名一致を保証。CI build不使用）。

APK:

- **R APK**: cutover直前main head（P28 merge後のmain）から `assembleLawnWithQuickstepGithubDebug`。
- **16 APK**: 統合head（cutover head）から同variant。

手順（各install前に `dumpsys package` でversionName・APK sha256を記録）:

1. R APKをinstallし、初回setup完了後、代表data作成: folder作成（2 app格納）・hotseatへのapp移動・workspace画面操作・設定変更1件以上（例: icon sizeやhome rotation）。`am force-stop` → read0取得。
2. 16 APKを `adb install -r`（data保持）。HOME intentでcold起動 → logcat FATAL/ANR scan・screenshot・起動完了確認 → `am force-stop` → read1取得。比較read0→read1。
3. R APKを `adb install -r`（**rollback leg**）。cold起動・同検証 → read2取得。比較read1→read2。
4. 16 APKを `adb install -r`。cold起動・同検証 → read3取得。比較read0→read3。

cold読取（read0〜3共通）: `am force-stop app.lawnchair.debug` → `run-as app.lawnchair.debug` で `databases/`（launcher.db・`-journal`/`-wal`/`-shm`）・`shared_prefs/`・`datastore/`・`files/`（recovery artifacts）を取得。比較項目: file単位sha256・`sqlite3` row dump（favorites他全tableの行数・行内容hash）・prefs key/value対照。差分はすべて分類記録（期待される書込み: 起動時のprefs default merge・journal正規化。**favorites行の喪失・schema変化・recovery artifact欠落は非説明差分としてfail**）。

実機leg（AC-4）: owner用議定書をassessmentへ用意する（Pixel 9a・API 37・debug build・owner日常dataでの15→16→15確認手順と注意）。実施またはowner decisionによる代替を待ってPhase 4完了判定。

## 7. 正本同期の変更file（AC-6）

| file | 変更 |
|---|---|
| `docs/engineering/upstream-strategy.md` | Current state block（baseline commit/branch・toolchain・更新日）・必要ならSync workflow参照 |
| `docs/engineering/building.md` | Baseline header・toolchain表・検証command・成果物名・submodule pin |
| `docs/assessment/upstream-patch-surface-baseline.json` / `.md` | upstream `43a21b43d7`・target cutover headで再採択（pins再記録・expected_measurement・bridge_groups写像・Acquisition history） |
| `docs/adr/0018-lawnchair-16-rebase.md` | cutover実行記録（統合head・merge・tag・T9・rollback証明の各SHAと結果） |
| `AGENTS.md` | プロジェクト状態のbaseline行・検証済みcommand内のtoolchain記述 |
| `docs/assessment/issue-516-phase4-cutover.md`（新規） | 統合対照表・gates・T9証跡index・rollback証明・実機leg記録 |
| `docs/assessment/pr-<PR>-<slug>.md`（新規） | 高リスクPR独立audit記録 |
| `specs/516-16-rebase-phase4/plan.md` §10 | 実行記録（unit対照表の適用結果） |

## 8. 保持PR・子Issue処遇

| 対象 | 処遇 |
|---|---|
| PR #575 | cutover前に559 stackへmerge（§2.1）。PRはMERGEDで完結 |
| PR #535/#538/#541/#543/#546/#550 | cutover後、branchがmainのancestorであることをancestry証拠としてclose（superseded by cutover PR。review・検証記録の正本としてPRは残る） |
| PR #558 | #554のclose判断と同時に処遇決定（判定契約非採用の記録があるため、close with commentを基本とする） |
| #532/#545/#554/#574 | cutover到達をもってclose（#554はclose条件が実質達成済みのため、cutover comment付きでclose） |
| #576/#586 | closeしない（tracked limitations。Epic完了に影響しないことをEpic commentへ明記） |

## 9. rollback手順（AC-5の実行詳細）

1. **事前証明**: cutover merge後（main到達後）、scratch branchで `git revert -m 1 --no-commit <merge>` → `git write-tree` のhashが `pre-lawnchair16-<date>`^{tree} と一致を確認 → abort。結果をassessmentへ記録。
2. **実運用手順**: mainで `git revert -m 1 <cutover merge>` をPRとして実行（protected mainへのforce-resetは行わない）。または tag `pre-lawnchair16-<date>` からrelease再発行（ADR Decision 5）。
3. **data面**: T9 rollback legが16書込みdataの15での可読性を証明。16運用中の新規書込みが加わった場合はT9範囲外となるため、rollback時のdataはT9証跡の条件下にあることを手順書へ明記。

## 10. 実行記録

実行時に追記する（merge順dry-run結果・unit適用対照表・gates結果・T9証跡・cutover SHA・tag SHA）。

## Change history

- 2026-10-13: revision 1 proposed。ADR-0018 revision 10と同時に起草。
