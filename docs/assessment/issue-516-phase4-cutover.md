# Issue #516 Phase 4 — baseline切替cutover実行記録

> Status: in progress（rev4順で実行中。cutover merge後にO/tag/rollback最終証明をmain上の後続docs PRで追記して完了）
> 対応: Epic [#516](https://github.com/nunu1733/NunuLauncher/issues/516) Phase 4 / [ADR-0018 revision 10](../adr/0018-lawnchair-16-rebase.md) / [spec](../../specs/516-16-rebase-phase4/spec.md) / [plan](../../specs/516-16-rebase-phase4/plan.md)
> 実行日: 2026-10-13

## 1. 統合branch構築（plan §2）

基点: `issue-559-api36-recents-compat` @ `5a9e3ea5024062467866c2b928953586bd928f6b`（PR #575 merge済みの559 stack head）。branch: `issue-516-phase4-cutover`。

| 統合step | mergeしたbranch（head） | 結果 | conflict解消 |
|---|---|---|---|
| merge 1 | `issue-545-api37-provider-fix`（`33270ede1b`） | `9b52a62c7c` | `quickstep/.../TaskContainer.kt` 4 hunk: 559側（PR #571 cherry-pick）の `: Unit` 式body明示形式を保持（#562/#563契約。545側はPR #567 merge由来の同内容） |
| merge 2 | `issue-526-target37-ui`（`bc0171f253`） | `95f9f39fb8` | なし（dry-runどおり） |
| merge 3 | `issue-527-bal-granular`（`54a7504230`） | `153bf4f3b3` | なし |
| merge 4 | `issue-528-lan-network`（`57d4338bb5`） | `7a97a23c4e` | `.github/workflows/ci.yml`: organizer-unit-tests filterをunion（528の `app.lawnchair.search.*` ＋ 559側の `com.android.launcher3.PagedViewScreenCenterTest` を両方維持） |

ancestry検証: 統合headが `issue-532-phase2-restart` / `issue-524-api37-quickstep` / `issue-545-api37-provider-fix` / `issue-526-target37-ui` / `issue-527-bal-granular` / `issue-528-lan-network` / `issue-574-contacts-data-projection` の全headをancestorとして含むことを `git merge-base --is-ancestor` で確認済み。

## 2. main post-replay unit port対照表（plan §3の実行結果）

replay基点 `38262fb74d` 以降のmain first-parent unitを時系列順に適用。詳細は [516-phase4-t9-evidence/port-units.log](516-phase4-t9-evidence/port-units.log)。

| unit | main commit | 適用方式と結果 |
|---|---|---|
| P01 | `b759506e28` (#533) | skip: 16側にplan rev2が存在（identity確認: specs/516-16-rebase-phase2/{plan,spec}.md == main） |
| P02 | `15ae5bf917` (#534) | skip: 同上（model assessment も identity確認） |
| P03〜P13 | #536〜#553 | cherry-pick適用（specs/526/527/528/524/545） |
| P14/P15 | `9cdb98828c`/`a3aaf3925a` (#556/#560) | skip: specs/554は545 branch経由で最終内容が既に統合treeに存在（両fileともHEAD==mainを機械確認） |
| P16 | `6d3cd6c59f` (#564) | 適用（559側supersede版と共存。docs） |
| P17〜P19 | #566/#568/#569 | 適用（563 evidence docs） |
| **P20** | `3b67953d47` (#570) | **adapt適用**（#565 cold-start deadlock fix）。production `LawnchairLayoutFactory.kt` はmain版とbyte一致を機械確認。ci.yml filterへ `ColdStartFontLazyInversionInstrumentationTest` をunion、ci-test-portfolio.mdへ#565行をgraft |
| P21 | `8fe67e7650` (#572) | 適用（563 docs最終版） |
| P22 | `7985a45a6e` (#578) | 適用（#577 benchmark seeder test fix。tests配下） |
| **P23** | `840603371c` (#580) | **適用**（#579 folder capture fix）。production 1file（PersistenceManifest.kt）はmainとbyte一致 |
| **P24** | `0ef86713b9` (#582) | **適用**（#581 QSB-off page inventory fix）。production（LauncherLayoutAdapter.kt）はmainとbyte一致 |
| **P25** | `c3898c74f3` (#584) | **適用**（#583 settleImport fix。最終main内容＝interleave RED修正・Handler注入込み）。production 2fileはmainとbyte一致 |
| **P26** | `a9ec830a81` (#588) | **適用**（#587 cold settings restore fix。最終main内容）。production（LawnchairBackup.kt）はmainとbyte一致 |
| P27 | `85b86610b4` (#573) | 適用（Phase 3 closure matrix・curated evidence 全量） |
| P28 | `fef1c27d49` (#589) | 適用（Phase 4 spec/plan/ADR rev10。ADRはmain rev10を優先解消） |

**結果**: S = `20f7b9f33dd31501cb7a589b8d941b2da5be816c`（本assessment・T9証跡・正本同期docsのcommit後のheadが最終S。§5 gatesはそのSに対して成立）。

## 3. gates（plan §5）

| gate | 結果 | 証跡 |
|---|---|---|
| G1' build/format | **PASS**（S₃=e7798ecf1f で `assembleLawnWithQuickstepGithubDebug` + `spotlessCheck` + organizer unit gateを一括実行、BUILD SUCCESSFUL 5m9s。S₂=88218ebbca・S₁=ac2b985860 でも同gate PASS） | CIが正本 |
| G2' unit | **PASS 2000 tests / 0 failures / 0 skipped**（S₃。CI organizer-unit-tests filterと同一のfilter指定） | test-results XML集計 |
| G3' ownership | **PASS**。upstream ancestor `43a21b43d7` で再測定: counted **354 files / +32307 / −8923**（S₂でもcounted面不変・excluded面のみrev4 docs増分でexpected_measurement更新・`--verify` PASS）。正式採用を同一PRで実施 | [516-phase4-patch-surface-readoption.md](516-phase4-patch-surface-readoption.md)・[upstream-patch-surface-baseline.json](upstream-patch-surface-baseline.json) |
| G4' T9 | **PASS（emulator leg。rev4やり直し実行、S₂最終treeのAPK `16.Dev.(88218eb)` を使用）**。15→16→15→16同一data roundtrip、favorites全stage IDENTICAL・schema33不変・prefs key完全保持・FATAL 0 | [516-phase4-t9-evidence/](516-phase4-t9-evidence/) |
| G5' CI | Sのpull_request CI final-status（cutover PR）＝ 本PRのchecks | PR checks |
| G6' audit | 別session audit + `docs/assessment/pr-<PR>-<slug>.md`（Head SHA=S） | audit doc（docs-only commit E） |
| G7' rollback | 予備: `git revert -m 2 M` dry-run tree hash == R_candidate tree hash（merge前）。最終: `git revert -m 1 O` == 同（merge後追記） | §5へ追記 |

## 4. T9サマリ

[516-phase4-t9-evidence/README.md](516-phase4-t9-evidence/README.md) 参照。R_candidate = `fef1c27d497f2927b8792ea2cbadd8d5f53c6583`。実機leg（Pixel 9a / API 37）はowner decision待ち（ADR Decision 5 rev10の代替経路）。

## 5. cutover実行記録（merge時に追記）

- R_candidate: `fef1c27d497f2927b8792ea2cbadd8d5f53c6583`（tag `pre-lawnchair16-20261013` 付与・push済み）
- **cutover実行結果（2026-10-13 merge完了）**:
  - S3final: `5bbee5ae042ef8f05ee29e0fac9842e8c52e2031`（rebase後の最終統合tree。§2/§3のS系SHAでrebaseにより書き換わったものは本treeに同一内容が存在する）
  - M3（`-s ours` merge commit）: `106b4f7f60c28e3022b9b4b6a37ba23c2eb845e5`（M^1=S3final、M^2=R、tree==S3final）
  - E（audit文書のみ・最終PR head）: `b24632fdf37e619a3a37c1fa0b44afd639c457d9`（M3..E差分 = `docs/assessment/pr-590-16-baseline-cutover.md` 1file）
  - **O（cutover merge・rollback対象）**: `de4565323711facf17935cc56925b766e1d88037`（PR #590のmerge commit。**O^1 == R == tag == M3^2**、O^2 == E、O tree == E tree）
  - G5' CI: M3 run [38422689296](https://github.com/nunu1733/NunuLauncher/actions/runs/38422689296) final-status success（attempt 2。初回category-override失敗はrunner SDK download破損のinfra flake）・E run [38434153278](https://github.com/nunu1733/NunuLauncher/actions/runs/38434153278) final-status success
  - G6' audit: [pr-590-16-baseline-cutover.md](pr-590-16-baseline-cutover.md)（Head SHA=M3。ChatGPT review 2 rounds — round 2 **approve相当・blockingなし** [comment](https://github.com/nunu1733/NunuLauncher/pull/590#issuecomment-6151352071)）
  - G7' rollback証明: **予備証明** `git revert -m 2 M3` dry-run tree `c008f282caf5413c55b924f595d97922653d6f71` == R tree ✓。**最終証明** `git revert -m 1 O` dry-run tree `c008f282caf5413c55b924f595d97922653d6f71` == R tree ✓（両検証とも検証後abort、mainへpushせず）
- 保持PR/Issue処遇（plan §8実行結果）: PR #538/#541/#543/#546/#550 close（branchがmainのancestorであることを機械確認・superseded by #590）、#554/#558/#575 close、#532/#574/#559/#545 close（#545/#559は既存closeに到達記録コメント）。#576/#586はOPEN維持（tracked limitations・Epic完了に影響しないことをEpic commentで明記）
- **rev4やり直し記録（PR #590 review round 1対応）**: 初回 S=8fea123177 → E=dec3ee4929 → M=11b5453447 のCI run 38404418328/38404418234がCI側のrebase規模制限でfail。S₂から順序を「M CI→audit(M)→E(auditのみ)」へ改訂（plan rev4・ADR rev10補訂）し、CI両fixを投入してやり直す。旧E/Mは統合branchから撤去（force-push）。
- S / E / M / O / tag SHA: （追記）
- 予備証明 `git revert -m 2 M` dry-run: （追記）
- 最終証明 `git revert -m 1 O` dry-run: （追記）
- 保持PR処遇（plan §8）: （追記）

## 6. 正本同期（AC-6）

本cutover PR内で実施: `docs/engineering/upstream-strategy.md`（Current state→16-dev/43a21b43d7）、`docs/engineering/building.md`（toolchain実測値: JDK 21.0.12 / Gradle 9.8.0 / AGP 9.4.1 / Kotlin 2.4.20 / Platform 37 minor 2 / Build Tools 37.0.0 / targetSdk 37 / minSdk 26 / submodule 7d9e92bd）、`AGENTS.md`（baseline行・検証済みcommand toolchain）、patch-surface baseline再採用（JSON+md+readoption記録）、plan §10実行記録。
