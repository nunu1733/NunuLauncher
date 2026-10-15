# High-risk audit: PR #535 Phase 2 16-dev rebase実装（S0〜S4）

> Status: accepted（audit記録。Phase 2 review/validation closureの判定であり、Phase 2でのmerge承認ではない。merge条件はFindings節）
> Audit date: 2026-10-06

- Auditor: 独立監査session（実装を行ったsessionとは別のagent/sessionとして、再実行と再確認により実施。実装sessionの成果物の引用と、本session独自の再実行を「Executed test surface」で区別して記載する）
- PR: https://github.com/nunu1733/NunuLauncher/pull/535
- Head SHA: 6c4a88a9eb6131cb371bc0d255211e61698894c1
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/37347294575
- Criteria: specs/516-16-rebase-phase2/spec.md AC-1, AC-2, AC-3, AC-4, AC-5 / docs/adr/0018-lawnchair-16-rebase.md ADR-0018

## Scope

対象diffは `43a21b43d7cc7850ab54e14b1a57dc9646685f35`（ADR-0018 Decision 1のanchor）→ `6c4a88a9eb6131cb371bc0d255211e61698894c1` の384 commitである。内訳は (a) main first-parent 300単位のper-PR replay（`43a21b43..8b35f1ff7a`、300 commit）、(b) 停止head `88af5218ce` 以降のplan §4.1 S0〜S3（anchorモデル層統一とfork契約移植）、(c) S4の文書同期、(d) G4/G5で検出された欠陥の修復群（replay-log §6.3〜§6.11、§6.20）。

REBASE_HEAD=`7f46ab6466075ebf5a39f954cf3376d2968e6353` がreplay-log冒頭でG4開始前に確定記録されていることを確認した。REBASE_HEAD→audit headの差分は44 commitであり、production 12 file＋CI script 2 file（+346/−66。`ModelDbController.java`、`ModelWriter.java`、`LauncherModel.kt`、`ForkServiceModule.kt`、`LawnchairApp.kt`、`InvariantDeviceProfile.java`、`ModelProjectionCodec.kt`、`LayoutApplicationModule.kt`、`LawnchairLauncher.kt`、`LawnchairAlphabeticalAppsList.kt`、`WallpaperCarouselView.kt`、`NovaBackupConverter.kt`、`PackageUpdatedTask.java`、`res/values/attrs.xml`、`strings.xml`＋`tools/ci/` 2 script）を含む。PR本文の「REBASE_HEAD以降のsource差分はreview round 2の1 file gate修正のみ」という記述は実測と不一致である（Findings F2）。

確認したruntime書込み経路・migration対象: `src/com/android/launcher3/model/ModelWriter.java`（MODEL_WRITER admission、direct-edit/Undo、Issue #269 desktop-entry正規化）、`src/com/android/launcher3/model/ModelDbController.java`（grid migration両entry、schema33、active helper束縛）、`src/com/android/launcher3/LauncherModel.kt`（organizer token・snapshot gate）、`src/com/android/launcher3/InvariantDeviceProfile.java`（applyGridInfo exact束縛）、`lawnchair/src/app/lawnchair/organizer/application/**`（適用・recovery・store・protocol）、`lawnchair/src/app/lawnchair/backup/**`（Nova converter）、`lawnchair/src/app/lawnchair/LawnchairApp.kt`（startup hook・safe-mode回避）、`lawnchair/src/app/lawnchair/deck/**`（退役維持）。workflow「高リスクpath一覧」に該当し、risk labelは付いていないがpath判定で本契約が適用される。

## Criteria check

- **AC-1（replay完了とancestry）: 満たす（再実行）**。`git rev-list --first-parent --reverse 505dbc40e6154c05158b5d0271c45f6a885a411b..38262fb74d144f4655dfbf01c0084e44c86560be` は300件、replay branch `43a21b43..8b35f1ff7a` は300件、`git merge-base --is-ancestor 43a21b43… HEAD` と `8b35f1ff7a` のHEADに対するancestorはいずれも真。source-replay-map.mdから抜き打ち5単位（019/100/197/255/300）を `git rev-list` の実出力と `git log --format=%s` で突合し、元SHA↔replay SHA↔件名が全件一致。replay-log §2の300行は単位番号・元commit・結果（applied 300、conflict 20単位）を記録済み。
- **AC-2（disposition遵守）: 満たす（記録確認＋gate結果による裏付け）**。replay-log §3はconflict解消が全てplan §4（keep相当のfork契約復元）に従い、挙動変更を意図した解消は発生しなかったと記録。submodule pin判断点（plan §2）は§0の確認により到達せず。層単位の統一（G1停止）はADR-0018 Decision 9（accepted revision 7）とplan revision 2のaccepted改訂を経て解決済み。G4で確定した契約上の判断（activity base class、#59 commit seam）はADR-0018 revision 7とspec 59 revision noteとしてaccepted正本に反映済みを確認。spec 452のrevision note（DEFAULT_ORDER 10項目、#452 comment linked）も確認。
- **AC-3（無関係差分の混入禁止）: 満たす（計測の再実行）**。G3のcandidate ownership inventory（570 production path、§8 attribution表で全pathに由来単位を帰属）を正本commandで再実行し `PASS: measurement completed with complete bridge ownership.`（exit 0）を確認。`LauncherModel.java`→`.kt`、grid migration分割、onPostInit代替、anchor-integration-repairs群はbridge groupとして分類済み。旧inventory `counted_files == 105` 一致はplan §7どおり合格条件外（candidate status維持、正式再採択はPhase 4）。
- **AC-4（検証gate）: 満たす（G1/G2は再実行、G3は再実行、G4は証跡突合、G5はGitHub API照会）**。詳細は「Executed test surface」。`assembleLawnWithQuickstepGithubDebug` と `spotlessCheck` は本sessionでgreen。organizer unit test gateはci.ymlと同一commandの再実行でtests=1935 / failures=0。G4はREBASE_HEAD対の実行表（§6.1〜§6.8）と `build/g4-evidence3/` のXML 12件（計59 case、failures=0）・boot smoke・cross-process記録を突合し一致。G5は16 job全成功（final-status含む）を2 runで確認。T9はplan §7どおりPhase 4 owner closureでSKIP記録どおり。
- **AC-5（正本同期）: 満たす（文書確認）**。ADR-0018（YAML frontmatter `status: accepted`、revision 7にDecision 9実装境界2点）、spec 59 revision note（readback後継、replay-log §6.3/§6.7 linked）、spec 452 revision note、`tools/repo-contract/ci_portfolio_map.yml` と `docs/engineering/ci-test-portfolio.md` の同期、replay-log/source-replay-map/wip-adoption-table/ownership-inventory-candidate（.md/.json）の同梱を確認。`python3 tools/repo-contract/validate_repo_contract.py` と `python3 tools/repo-contract/test_validate_repo_contract.py` はともにexit 0。
- **plan §4.1 S0〜S4終了条件: 満たす（gate水準での確認）**。S0（300件対応表・WIP 253 path採否表・candidate inventory初版・replay-log訂正）、S1〜S3（`LayoutWriteCoordinator.runOrDefer(MODEL_WRITER)` のModelWriter.java:669での実在、G2のDirectEdit/NestedTransaction/organizer reload群green、G4のT1〜T8全greenで契約保持を裏付け）、S4（G1〜G5・inventory・log・CI map/portfolio同期・本audit）。
- **ADR-0018 Decision 9（revision 7）: 遵守を確認**。anchorモデル構造統一（旧Java duplicate・旧model providerの削除）、恒久二重モデル不採用、fork契約は既存seamへの移植（test結果で検証）、activity base classと#59 seamのrevision 7境界どおりの実装。

## Executed test surface

本sessionが独立に再実行した項目（head `6c4a88a9eb6131cb371bc0d255211e61698894c1`、clean working tree）:

- `git rev-parse HEAD` → `6c4a88a9eb6131cb371bc0d255211e61698894c1`。`git status --porcelain` → 空。`git merge-base --is-ancestor 43a21b43d7cc7850ab54e14b1a57dc9646685f35 HEAD` → 真。
- **G1**: `./gradlew spotlessCheck` → BUILD SUCCESSFUL。`./gradlew assembleLawnWithQuickstepGithubDebug` → BUILD SUCCESSFUL。
- **G2**: ci.yml `organizer-unit-tests` stepと同一の `./gradlew testLawnWithQuickstepGithubDebugUnitTest -Pnunu.excludeAiExchangeUnitTests=true --tests 'app.lawnchair.organizer.*' --tests 'app.lawnchair.homeedit.*' --tests 'app.lawnchair.ui.popup.*' --tests 'app.lawnchair.ui.preferences.navigation.*' --tests 'app.lawnchair.bugreport.*' --tests 'app.lawnchair.backup.*' --tests 'app.lawnchair.migration.*' --tests 'app.lawnchair.DeviceProfileOverridesPresetResolutionTest' --tests 'app.lawnchair.preferences2.SharedPreferencesLegacyKeyMigrationTest'` → BUILD SUCCESSFUL。結果XML 187 classを集計し **tests=1935 / failures=0 / errors=0 / skipped=0**（replay-log §7・PR本文の1935/0と一致）。
- **G3**: `python3 tools/repo-contract/measure_upstream_patch_surface.py --baseline-file specs/516-16-rebase-phase2/ownership-inventory-candidate.json --upstream 43a21b43d7cc7850ab54e14b1a57dc9646685f35 --target 7f46ab6466075ebf5a39f954cf3376d2968e6353` → `PASS: measurement completed with complete bridge ownership.`（exit 0）。`git diff --name-only 43a21b43… 7f46ab64… | wc -l` = 1,832（markdown §3の全changed path数と一致）。`git diff --name-only 43a21b43… HEAD | wc -l` = 1,837（差分5 pathの内訳はFindings F3）。
- **G4証跡突合**: `build/g4-evidence3/` のXML 12件を集計 → GridMigrationFailureTest 30/30、RestoreLeaseSerializationTest 11/11、RealZipRestoreE2E 2/2、RestoreDbTaskSuccessPathTest 1/1、NovaRestoreGridApplicationTest 4/4、NovaConverterBoundary(SmartspaceOff含む) 3/3、capture 4 class 5/5、GridMigrationSuccessTest 3/3、合計59 case / failures=0 / errors=0。`boot-smoke.txt` は `mCurrentFocus=…LawnchairLauncher` かつcrash buffer空。`cross-process-stages.txt` はhead `eacca743c6` でStageA OK / force-stop / StageB OK。replay-log §6.8.2の成績表と一致。emulator再実行は監査taskの定義どおり省略し、REBASE_HEAD以降の差分評価で代替した（Findings F2/F3）。
- **G5**: `gh run view 37347294575 -R nunu1733/NunuLauncher` → head `6c4a88a9…`、16 job全てsuccess（changes / check-style / organizer-unit-tests / validate-repo-contract / build-debug-apk / emulator 9 lane / final-status）。`gh run view 37341705150 -R nunu1733/NunuLauncher` → head `f7e835987c…`、16 job全てsuccess。
- **review指摘の解消**: `src/com/android/launcher3/LauncherModel.kt:324` に `val needsSnapshot = organizerToken != null` のgateが実在し、`captureModelSnapshot` はgate内のみで実行されることを直接読んで確認（review comment 5999309905 P2の解消、5999973159のresolved判定と一致）。PR本文はPhase 2 non-merge・`Refs #532`/`Refs #516`・handoff head = 本headへ更新済みを確認（同P1の解消）。
- **contract検証**: `python3 tools/repo-contract/validate_repo_contract.py` → repository contract OK（exit 0）。`python3 tools/repo-contract/test_validate_repo_contract.py` → repository contract OK（exit 0）。

引用のみの項目（本sessionは再実行せず、記録とローカル証跡の突合のみ）:

- G4のemulator実行自体（T1〜T8の実行、§6.1/§6.5/§6.8の表）: REBASE_HEAD対としてreplay-logに記録された実装sessionの実行であり、本sessionは残存証跡（`build/g4-evidence2/`、`build/g4-evidence3/`、boot smoke、cross-process記録）との突合で検証した。初回非green実行の証跡dir `build/g4-evidence/` は現存しない（Findings F4）。
- replay-log §2の300単位のうち抜き打ち5件以外の個別突合、WIP採否表253 pathのpath単位再検証: source-replay-map.md・wip-adoption-table.mdの記録を正として引用（300件全体の一対一対応はAC-1の件数・ancestry・抜き打ち突合で検証）。

## Findings

判定: **受入可 — Issue #532 Phase 2のreview/validation closureとして**。ただし本判定はPhase 2でのmerge承認ではない。ADR-0018 Decision 3/5どおり、本PRのmergeはPhase 4の単一cutover mergeと同一actionであり、Phase 2ではmergeしない。

残置リスクと後続要件:

- **F1（必須・Phase 4 cutover条件）: `pull_request` eventのCI runが存在しない**。PR #535 / branch `issue-532-phase2-restart` には `pull_request` triggerのci.yml runが1件もなく（statusCheckRollup空、`gh run list --event pull_request` で当該branch 0件、high-risk-gate workflowも未実行）、本head上で成功している2 run（37341705150、37347294575）はいずれも `workflow_dispatch` である。workflow正本と `validate_high_risk_evidence.py`（event != `pull_request` を明示拒否）により、`High-risk gate / high-risk-evidence` はworkflow_dispatch runを証拠と認めない。したがって本PRは現状、機械gateをpassできない。Phase 4 cutover時に (a) cutover PRのhead上で `pull_request` eventのCI merge gate（final-status、source job含む）を成功させ、(b) そのheadに対して本auditと同様の独立auditをやり直すことを必須条件とする。なお他PRでは同一triggerでpull_request runが発火しているため、本branchで発火しない原因の調査を推奨する。
- **F2（PR本文の修正推奨・docs-only）**: PR本文G5行の「REBASE_HEAD以降のsource差分はreview round 2の1 file gate修正のみ」は実測（44 commit、production 12 file +346/−66）と不一致である。差分はreplay-log §6.5〜§6.11・§6.20に記録されたG4/G5修復群であり、各commitは局所再実行と最終headのCI greenで検証済みのためG4/G5の結論は揺らがないが、本文の記述は正確化すべきである。
- **F3（Phase 4再計測の入力）**: G3 candidate inventoryの計測対象はREBASE_HEADであり、REBASE_HEAD以降にproduction差分へ新規入ったpathが1件ある（`src/com/android/launcher3/InvariantDeviceProfile.java`。E2修復による）。candidate inventoryはplan §7どおり未採択で、Phase 4が最終headで再計測する際にこの増分を含めること。
- **F4（証跡・文書の軽微な不整合）**: (a) §6.1の初回G4証跡dir `build/g4-evidence/` は現存せず、最終green証跡は `build/g4-evidence3/` のみ（§6.8の「証跡XML 13件」に対し実ファイルは12件）。(b) replay-log §6.9.3-2/§6.10.4-2の「manual-org laneはcomma filterのまま」の残置記述は陳腐化している（commit `7db78f4521` で `tools/ci/run-manual-organization-ui-instrumentation.sh` は `run-instrumentation-per-class.sh` 経由のper-class dispatchへ変更済み、両CI runで当該lane green）。replay-logの訂正を後続docs commitで行うこと。
- **F5（Criteria機械検証の注記）**: `specs/516-16-rebase-phase2/spec.md` はYAML frontmatterを持たない（blockquoteの `> Status: accepted` のみ。spec 123件中frontmatter有り121件の現行形式に対する例外）。gateのCriteria substance検証はfrontmatterのstatusを機械読みするため、本記録のCriteria引用のうちspec側はgate上でstatus未検証となる。frontmatter追加はspecs/配下（docs/外）の変更でありHead SHA lineage要件上、本audit commitには含めずownerの後続対応（適用時は再audit）とする。
- **G4 evidenceのarm64 vs x86_64差**: ローカルG4はarm64 AVD、CI正本はx86_64 pixel_7_pro。最終headでのx86_64裏付けはG5の9 emulator lane green（run 37347294575）が担う。lane設計上fail-fastの未走査classが生じ得る点（§6.10.4-1、§6.11.4-1）はreplay-log記録のとおり残置し、Phase 3/4の検証で継続監視する。
- **#452/#59のspec revision注記**: 本auditで両noteの存在と内容（DEFAULT_ORDER 10項目、#59 readback後継）を確認済み。各spec ownerによる追認はEpic #516 Phase 3の継続事項である。
