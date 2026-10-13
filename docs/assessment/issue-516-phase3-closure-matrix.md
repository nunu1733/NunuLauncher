# Issue #516 Phase 3 クロージャー証跡マトリクス（close-readiness）

> Status: draft（Worker記録。Review未了。本fileはPhase 3の照合結果であり、Epic close・cutover merge・実機免除の主張は含まない）
> Date: 2026-10-11（検証実行日。全remote観測はこの日付で再確認済み）
> 対応: Epic [#516](https://github.com/nunu1733/NunuLauncher/issues/516) Phase 3「fork機能の再検証」
> 階層: L（docs-only。コード・テスト・CI laneの追加なし。test-audit適用対象の外——新規testなし）
> 正本との関係: 目的・範囲・進捗の正本はIssue #516。本fileはIssue本文Phase 3節と各accepted spec/ADRの受入条件を、実行証跡（SHA・CI run・artifact・docs path）へ対応付ける照合表であり、契約そのものは複製しない。

## 0. 検証方法（本日実行したcommandと一次出力）

| # | command | 結果の要点 |
|---|---|---|
| C1 | `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` | `nunu1733/NunuLauncher` / `main` 確認 |
| C2 | `git ls-remote origin refs/heads/…`（§1表の全branch） | head SHAを§1へ記録。ローカル stale ADR（rev6相当）には依存しない |
| C3 | `git fetch origin <SHA>` + `git merge-base --is-ancestor` | 祖先関係は§2。`33270ede1b`（#550 head）→`33b4c061c5`（559 stack tip）は **非子孫**（sibling）と確定 |
| C4 | `gh pr checks {535,538,541,543,550,571}` + `commits/{sha}/check-runs` API | final-status の head 別成否を§5 R-7表へ |
| C5 | `gh run view --log` / failure-capture artifact DL（id 11432472958, 11432158417, 11665005321 等） | 失敗laneのsignature分類を§6へ |
| C6 | `git show "<head>:lawnchair/src/app/lawnchair/LawnchairLayoutFactory.kt" \| grep -c "by lazy { FontManager"` | 全Phase 3 stack headで該当1行（#565欠陥 **残存**）、main `8fe67e7650` で0（修復済み）。§7 |
| C7 | `adb devices -l` + `getprop` | `emulator-5554`（API 37, `google/sdk_gphone64_arm64/emu64a:17/CE2A.260420.019`）と `emulator-5556`（API 36, #559 exact-fingerprint `…BE2A.250530.026.F3/13894323`）のみ。**物理端末（Pixel 9a）は接続なし** → §8 owner-only の前提事実はこの観測 |

## 1. 生きているanchor（2026-10-11 GitHub観測、観測者: 本session）

| branch | head | PR / state | 備考 |
|---|---|---|---|
| `main` | `8fe67e765037df356864068dc289db1bae243e8a` | — | PR #572 merge。15 baseline運用継続中（Phase 4 cutover未完） |
| `issue-532-phase2-restart` | `e8aced7dbee4480c178a9cb994b8d55686ef41ce` | [#535](https://github.com/nunu1733/NunuLauncher/pull/535) OPEN・merge保留 | Phase 2 review対象head `6c4a88a9eb` + audit docs commitのみ |
| `issue-526-target37-ui` | `bc0171f25374fadf2d6296a0df4c29022416f12f` | [#538](https://github.com/nunu1733/NunuLauncher/pull/538) OPEN・MERGEABLE | base=phase2-restart。Phase 4までmerge保留（ADR-0018 D3） |
| `issue-527-bal-granular` | `54a75042305c74ddc0cb118ce6313e2c1746ccca` | [#541](https://github.com/nunu1733/NunuLauncher/pull/541) OPEN | source機能head `de913083da` + docs-only 2 commit |
| `issue-528-lan-network` | `57d4338bb5be5114481b98bef5a90f757b1d1f23` | [#543](https://github.com/nunu1733/NunuLauncher/pull/543) OPEN・MERGEABLE | |
| `issue-524-api37-quickstep` | `e214b7b19019e0efd2f28f53bf8b56f0b3510b76` | — | #550のbase |
| `issue-545-api37-provider-fix` | `33270ede1b56ca08fbd2f8c2f2092ab7e0295946` | [#550](https://github.com/nunu1733/NunuLauncher/pull/550) OPEN・MERGEABLE | v6統合。#557・#567 merge含む。ADR-0018 rev9 `e70486ddd7` の子孫（C3） |
| `issue-554-api37-recents-parcel` | `f131e8e09441dc2d10801d4619b671b0da15a6ee` | #557 MERGED（545 branchへ） | [#554](https://github.com/nunu1733/NunuLauncher/issues/554) は cutoverまでOPEN保持の明示契約 |
| `issue-559-api36-recents-compat` | `33b4c061c55db6edf646a48e6a18570ce771d15f` | #571 MERGED（559 stack tip） | 実装・review head `7a30761390`。`f131e8e094` 系sibling。545 branch後発docs commitは非包含（C3） |

Provider/recents系のPhase 3候補統合は **この559 stack tip `33b4c061c5` が先端**（#563修正+#555/#562 cherry-pick込み）。UI/BAL/LANの3スライスは phase2-restart からの独立verticalで、相互未統合。**全Phase 3変更の単一統合headのビルド・CI・再auditはPhase 4 cutover契約**（ADR-0018 Decision 3「切替はrebase branchの全検証完了後、単一のmergeで行う」＋#532完了報告の cutover 条件）に組み込まれている。**本sessionの解釈ではPhase 3 の完了条件はslice単位の再検証+head CIで、単一統合の検証はPhase 4が所有する**が、これは契約文言からのスコープ解釈であり、Phase 3で単一統合headを作らないことの要求免除を主張するものではない（owner判断でPhase 3統合candidateによる先行検証を追加することは可能で、その場合はその証跡がこのmatrixの行になる）。

## 2. stackトポロジー検証結果（C3の実測、stale記録の訂正込み）

- `e8aced7dbe`(phase2) → `33b4c061c5`(559 stack): 子孫 **YES**
- `e8aced7dbe`(phase2) → `33270ede1b`(545/#550): 子孫 **YES**
- `f131e8e094`(554) → `33270ede1b`(545): 子孫 **YES**（#557統合済み）
- `33270ede1b` → `33b4c061c5`: **NO**（559 stackは545 branchの `f131e8e094` 以降のdocs/追加分を含まないsibling先端。ただし #555/#562 相当は #571 cherry-pickで内容到達済み）
- `b0ffef6a07`（#550過去head・CI green run 37923319103）→ `33270ede1b`: 子孫 **YES**
- `3b67953d47`（#565 main fix merge）→ `8fe67e7650`(main): **YES**；→ `33b4c061c5`: **NO**（§7）
- `57d4338bb5`（#543 head）→ `33b4c061c5`: **NO**（LAN sliceは559 stackに未統合——cutoverまで正常保持）

ローカルmainのADR-0018はrevision 6以前であり（Phase 4でmain同期）、rev9は `e70486ddd7`（`docs/adr/0018-lawnchair-16-rebase.md` as of #550 lineage）を正本とする。phase2完了報告の「head `6c4a88a9eb`」は正しく、branch tip `e8aced7dbe` との差は audit docs 1 commit のみ（docs-only、CI non-regression根拠は§5 R-7注）。

## 3. Phase 2（replay / G1〜G5）— 完了事実として独立記録（Phase 3証跡の肩代わりを禁止）

Phase 2 の gate は rebase branch 自体の受入証跡であり、Phase 3 matrix の行とは区別して保持する（#516コメント 2026-10-06、[#532](https://github.com/nunu1733/NunuLauncher/issues/532)、[replay-log §5〜§7](https://github.com/nunu1733/NunuLauncher/blob/e8aced7dbee4480c178a9cb994b8d55686ef41ce/specs/516-16-rebase-phase2/replay-log.md)）。

| gate | 内容 | 証跡 |
|---|---|---|
| G1 | build/format green | PR #535・audit session再実行。[pr-535 audit](https://github.com/nunu1733/NunuLauncher/blob/e8aced7dbee4480c178a9cb994b8d55686ef41ce/docs/assessment/pr-535-phase2-16dev-rebase.md)（branch上、main同期はPhase 4） |
| G2 | organizer unit **1935 tests / 0 failures** | head `6c4a88a9eb`、同上audit |
| G3 | ownership inventory 570 path全分類 PASS | 正式再採択はPhase 4（上流基準切替時） |
| G4 | T1〜T8 emulator全green（ローカルAPI 36 `emulator-5556`、`build/g4-evidence*`） | replay-log §6/§7。**T9（cutover直前 R ラウンド）はPhase 4所有** |
| G5 | CI 16/16 final-status pass | [run 37347294575](https://github.com/nunu1733/NunuLauncher/actions/runs/37347294575) @ `6c4a88a9eb` |

Phase 2 = CLOSED ではない（#532はcutoverまでOPEN保持）。上表は「rebaseブランチの再検証前提が成立している」記録であり、下 §4〜§5 のPhase 3行の合格そのものではない。

## 4. Phase 3 matrix — fork契約の再検証（#445〜#450系＋Epic本文Phase 3列挙項目）

判定値: `PASS`（証跡確定）/ `PASS(rem)`（本日remediation実行で確定）/ `PENDING(trace)`（該当要件はあるが、このmatrixで具体的なscenario名・run ID・artifact pathが紐付いていない。紐付けが取れるまで合格と数えない）/ `BLOCKED(owner)`。

| 行 | 要求（Issue本文準拠） | 証跡（新baseline上） | 判定 |
|---|---|---|---|
| R-1 | direct-edit write contract / writer admission / transaction / rollback（ADR-0013・#118・#299/376） | shared-writer lane（`DirectEditModelWriterTest` `DirectEditUndoModelWriterTest` `LayoutWriteCoordinatorTest` `ModelWriterTransactionReentryTest` `EditSurfaceApply/Undo…` 等、ci.yml当該job class list）green @ `37502010177`(#538 head)、`37564923987`(#543 head)、`38076177185`(#563/#571 head)、G5 `37347294575`。replayによる契約移植は replay-log §3/§5 | **PASS** |
| R-2 | new-app destination policy（#446/#497、ADR-0015） | 同lane `InstallDestinationModelWriterTest` `InstallDestinationQueueTest` `AppDestinationNoticeTest` green（R-1と同一run群）。destination picker/settingsの**emulator** UI確認は #526 Verification（PreferenceActivity配下）として [526 evidence @bc0171f253](https://github.com/nunu1733/NunuLauncher/tree/bc0171f25374fadf2d6296a0df4c29022416f12f/docs/assessment/526-target37-ui-evidence) に包含（※ #526はemulator matrixが主証跡、実機legは§8 O1） | **PASS** |
| R-3 | item edit / multi-select / stale handling / Undo（#448/#449/#450） | rev3でtrace確定（明細 §13-A）: multi-select 4 action計画=`EditSurfaceSessionPlannerTest`（JVM、organizer-unit-tests permanent gate）、セッション1 transaction適用・stale零書込み・rollback=`EditSurfaceApplyInstrumentationTest`（shared-writer lane、ci.yml明示class list）、stale gate fail-closedとselection×recreate=#526 `EditSurfaceTarget37ContractInstrumentationTest`（@`bc0171f253`、run `37502010177`）、Undo逆操作=`EditSurfaceUndoInstrumentationTest`/`DirectEditUndoModelWriterTest`。green runは§5の `37347294575`/`37502010177`/`37493739107`/`37564923987`/`38035713365`/`38076177185`。untraced残存: **選択数ラベル表示（spec 449「複数選択と選択数の表示」）をassertするoracle**（専用UI tap journey scenarioも不在。旧baseline `450-edit-undo-ac1-screenshots` は#448単一itemで充当不可） | **PENDING(trace)**（縮小済: 残=選択数ラベルのみ。multi-select操作・stale反映・Undoの主体は§13-Aでtrace確定） |
| R-4 | Organizer planner/application/recovery、preview、lock、category override | G2（§3）+ restore-capture / reservation-recovery / onboarding-proposal / method-choice lanes green @ `37502010177`、`37564923987`、`38076177185`。reservation-recovery は `37505731629`(#541 head)・`38035713365`(#550 head) で環境失敗→§6再実行 **attempt2 両run final-status pass（2026-10-11）** | **PASS(rem)** |
| R-5 | backup/restore/downgrade、schema migration（#522契約・schema33維持） | G4 T1〜T8 green（replay-log §6/§7。成功sanitize/profile/widget remap・real ZIP restore・prefs/cache・Nova変換）。T9 15fork→16→R→16 は **Phase 4 cutover直前の必須実行として所有分離**（#522完了コメント「API36/37実証とPhase4 cutover decisionは未完・後続Phase所有」）。契約正本は [issue-522 assessment](./issue-522-rebase-data-compatibility.md) と [specs/516-16-rebase-phase2/plan.md](../../specs/516-16-rebase-phase2/plan.md) | **PASS**（Phase 3範囲）＋ Phase 4 引継 T9 |
| R-6a | drag/drop、folder、widget、hotseat、app launch、notification dots、search | traced部分: app launch/widget tap→configure→result/search入口（QSB）/通知action・tapは #527 [bal-evidence](https://github.com/nunu1733/NunuLauncher/tree/54a75042305c74ddc0cb118ce6313e2c1746ccca/docs/assessment/527-bal-granular-evidence)、custom suggestions検索は #528 [lan-evidence](https://github.com/nunu1733/NunuLauncher/tree/57d4338bb5be5114481b98bef5a90f757b1d1f23/docs/assessment/528-lan-network-evidence)、編集surface表示は #526 [ui-evidence](https://github.com/nunu1733/NunuLauncher/tree/bc0171f25374fadf2d6296a0df4c29022416f12f/docs/assessment/526-target37-ui-evidence)（API 36/37 emulator実測）。rev3追加紐付け（§13-B）: folder作成・追加・Undo writer契約（`DirectEditModelWriterTest`/`DirectEditUndoModelWriterTest`）、hotseat restore admission（`HotseatRestoreAdmissionTest`）・hotseat undo slot（`HomeEditUndoPlannerTest`）・dock/hotseat保持（`EditingBurdenBenchmarkFixtureSeedingInstrumentationTest`）は§5 run群でgreen、widget picker drag手順（527 `api37-07-widget-drop-attempt.png`）とworkspace drag（526 tablet scenario3のsetup操作）は限定的記録。untraced残存（UNCOV 4項目、§13-B）: **workspace drag/drop専用scenario・folder開閉UI・hotseat操作UI・notification dots表示** — これらはemulator上でのscenario/test追加でcoverage追加が可能な残課題であり（本PRでtest追加はしない）、§8 O4/R-8の実機gateとは独立要件で相互代替しない。upstream test tree（`tests/tapl`/`multivalentTests`）とG4/G5（organizer/DB契約側）をUI matrixの代替として紐付けない | **PENDING(trace)**（縮小済: UNCOV 4項目のみ） |
| R-6b | recents/gesture | API 36 provider環境: [#559恒久assessment](./issue-559-api36-recents-repair.md)（direct APP_SWITCH+gesture 両verified、baseline/fixed対照、parcel/FATAL 0）+ [#563 assessment](./issue-563-api36-home-empty-overview.md)（bug oracle GREEN 3/3 FG9-11、FG12、control FC2、[evidence proof.json](./563-home-empty-overview-evidence/proof.json)）。API 37 provider matrix: v6 `4ddd6ae306` qa3/qb3 全oracle PASS（[545-evidence @33270ede1b](https://github.com/nunu1733/NunuLauncher/tree/33270ede1b56ca08fbd2f8c2f2092ab7e0295946/docs/assessment/545-api37-provider-fix-evidence)）。実機 leg → R-8 | **PASS**（emulator leg） |
| R-7 | API 36 CI（新baseline上のhead別merge gate） | §5 表 — 列挙headすべてでfinal-status pass（2件の初回赤は§6 attempt 2 で解消。triage限定は§6末尾のとおり） | **PASS(rem)** |
| R-8 | 保守者実機API 37での代表操作・crash/ANR・recents/gesture結果をrepo evidenceへ（ADR-0018 Decision 7、Epic exit criteria） | 実機matrix対象項目は§8に列挙。**Pixel 9a 未接続（C7のadb実測）**。emulatorで代替はしない（waiver主張禁止） | **BLOCKED(owner-only)** |

## 5. R-7明細 — API 36 CI（final-status）head対応表

対象は「review/検証されたhead」上の CI。branch merge先 tip の再CIは PR event で再確認される契約（cutover head再audit。#532完了報告）に委ねる。

| branch / PR | head | CI run | 結果 |
|---|---|---|---|
| phase2 `#535` | `6c4a88a9eb` | [37347294575](https://github.com/nunu1733/NunuLauncher/actions/runs/37347294575) | final-status **pass**（16/16） |
| 〃（branch tip） | `e8aced7dbe` | — | branch tipは docs-only audit commit（`6c4a88a9eb..e8aced7dbe` = `docs/assessment/pr-535-phase2-16dev-rebase.md` 1 file、C3 diff実測）。branch pushへCI不发火は現行CI発火設計どおり |
| #538 (`526`) | `bc0171f253` | [37502010177](https://github.com/nunu1733/NunuLauncher/actions/runs/37502010177) | final-status **pass**（gh pr checks実測 entries 17/17 pass。中間head `3019ca6782` の [37447773329](https://github.com/nunu1733/NunuLauncher/actions/runs/37447773329) もpass） |
| #541 (`527`) | `de913083da`（機能source head） | [37493739107](https://github.com/nunu1733/NunuLauncher/actions/runs/37493739107) | **pass**（attempt 2、全16 job。失敗2laneの分類recordは[#541 comment](https://github.com/nunu1733/NunuLauncher/pull/541#issuecomment-6019329604)） |
| #541 (`527`) | `54a75042305c`（現head、docs-only差分） | [37505731629](https://github.com/nunu1733/NunuLauncher/actions/runs/37505731629) | 初回 **fail**（§6分類）→ attempt 2（2026-10-11、本session再実行）**final-status pass**・4 lane全green（manual-org 3-method fail・SDK破損3件とも非再現） |
| #543 (`528`) | `57d4338bb5` | [37564923987](https://github.com/nunu1733/NunuLauncher/actions/runs/37564923987) | final-status **pass**（全17 job、flake 4件のcategory 5分類＋rerun解消済みは#516コメント記録） |
| #550 (`545`) | `b0ffef6a07`（#557/#567統合前の過去head） | [37923319103](https://github.com/nunu1733/NunuLauncher/actions/runs/37923319103) | final-status **pass**（#545 close時の記録） |
| #550 (`545`) | `33270ede1b`（現head） | [38035713365](https://github.com/nunu1733/NunuLauncher/actions/runs/38035713365) | 初回 **fail**（reservation-recovery 1 lane、§6分類）→ attempt 2（2026-10-11、本session再実行）**final-status pass**・reservation-recovery green（20分stall非再現） |
| #571 (`563`) | `7a30761390` | [38076177185](https://github.com/nunu1733/NunuLauncher/actions/runs/38076177185) | final-status **pass**（emulator 2 laneの環境flakeは再実行で解消済みと#571記録。分類failure class 5、ANR主体 `com.google.android.as`・launcher関与0） |
| 559 stack tip | `33b4c061c5` | （なし） | merge commit pushへCI不发火（branch design）。review/検証対象はPR head `7a30761390`。cutover時に統合headでpull_request CI再実行がPhase 4契約 |

## 6. 本日実行したCI赤2件の triage・分類・remediation

分類基準: [quality-strategy.md](../engineering/quality-strategy.md) failure class **5（emulator / runner / platform environment defect）**。rerunは「分類の記録を伴う」要件に従い、以下にsignatureを固定する。

**PR #541 head `54a75042305c` / run [37505731629](https://github.com/nunu1733/NunuLauncher/actions/runs/37505731629)（2026-10-06）** — 現head CI赤の内訳4 job:

1. `shared-writer`（1m38s）・`exchange-import-ui`（1m52s）・`reservation-recovery`（1m22s）: job log に `Warning: An error occurred while preparing SDK package …: Error on ZipFile unknown archive` → `error: could not connect to TCP port 5554: Connection refused`。**emulator未起動（SDK image降版下载破損）**。テスト実行ゼロ。log signature上は環境失敗（class 5）の一次証拠。
2. `manual-organization-ui`（18m57s）: 56 test中3 fail（`ManualOrganizationPreferencesInstrumentationTest` 3 method）。report artifact [11432472958](https://github.com/nunu1733/NunuLauncher/actions/artifacts/11432472958) のXML: 全failのmessageは `window-focus-gate … focusedWindow=Window{… Application Not Responding: com.android.systemui} … target window never gained focus within 15000ms`。**SystemUI ANR dialogのfocus強奪**（launcher関与無。InjectedInputEnvironmentのfail-closed健康ゲートが設計どおり検出）。環境failure evidence（#571記録の `com.google.android.as` ANRと同型）。
3. 機能差分はdocs-only（`527-bal-granular-evidence/README.md`・`pr-541-bal-granular-audit.md` の2 file、C3 diff実測）で、pass済み機能head `de913083da` とproduction差分同一。よって初回failはproduction差分が新規投入したregressionではない（ただし次記の限定つき）。

**PR #550 head `33270ede1b` / run [38035713365](https://github.com/nunu1733/NunuLauncher/actions/runs/38035713365)（2026-10-10）** — `reservation-recovery`（37m25s）: gradle `Running tests on devices: test(AVD) - 16` 出力後 **約20分間 test dispatchなし**で wrapper exit 124、failure-capture artifact [11665005321](https://github.com/nunu1733/NunuLauncher/actions/artifacts/11665005321): `device-pressure` capture `status=124 timed_out=true`（runner cpu pressure 15s timeouts = #528/#563記録済みのrunner飢餓signature）。dropbox `data_app_anr` 0件・launcher ANR/tombstone signatureなし・`topResumedActivity=LawnchairLauncher` 生存。runner-side stallが第一帰納。**競合仮説の #565 cold-start deadlock（§7、このstack headに残存実在）は排除できない**——deadlockはinstrumentation attach前のmain thread停止と同型停滞を作る。再現した場合のescalation先はowner（§7にcherry-pick候補 `4b732b43ad` のスコープ記録あり。最終統合はPhase 4 cutover契約のみで、本matrixは条件付き無視を進言しない）。

remediation実行（2026-10-11、本session）: `gh run rerun 37505731629 --failed` / `gh run rerun 38035713365 --failed`。**結果（attempt 2）: 両run final-status pass**。#541 のSDK破損3 lane・manual-org 3-method（SystemUI ANR focus）は非再現、#550 reservation-recovery の20分stallも非再現（lane実行時間 1314-1702s のnormal帯）。このgreenはenvironment起因triage（class 5）を支持するが、1回の非再現はraceを論理的に排除しない。よって両分類は「triage分類（環境evidence-based）」として記録し、#565残存欠陥はtrackedのまま§7で保持する（非再現=解消宣言でも免除でもない）。

## 7. #565 cold-start deadlock とstack残存（cutover注意の固定）

- 修復commit: `4b732b43ad`（PR #570、main merge `3b67953d47` ∈ main `8fe67e7650`）。oracle・trace: [anr/cold-start-deadlock-anr-trace.txt](./563-home-empty-overview-evidence/anr/cold-start-deadlock-anr-trace.txt)（main実在をC6で確認）。
- 欠陥コード（`private val fontManager by lazy` + inflator内 `FontManager.INSTANCE.get` のlazy monitor跨ぎ）は **全Phase 3 stack headに残存**（C6 blob grep実測: `bc0171f253`/`54a75042305c`/`57d4338bb5`/`33270ede1b`/`33b4c061c5`/`e8aced7dbe` 各1行、main 0行）。
- Epic 2026-10-11コメントの記録どおり「559 stack未適用—cutover時に解消」が現行owner方針で、**この統合契約はPhase 4のみ**。§6の再実行非再現はこの残存欠陥を解消も無視もしない（trackedのまま）。
- **参考情報（ownerがhang再現等の追加判断を行う場合の選択肢。本docs・本matrixはいずれも実行も採用を勧めない）**: `4b732b43ad` を `issue-545-api37-provider-fix`（および必要なら `issue-526-target37-ui`/`issue-527-bal-granular`/`issue-528-lan-network`）へcherry-pick → 新headでpull_request CI。cherry-pick候補の構成（`3b67953d47` のdiff実測20 file）: production 1 file（`LawnchairLayoutFactory.kt` 9行）+ **専用bug oracle** `ColdStartFontLazyInversionInstrumentationTest.kt`（221行、RED→GREEN実績#570）+ spec-lite 565 + evidence + `ci.yml`/`ci-test-portfolio.md` のrouting。production fileは全stack headでblob完全一致（`67bbe9b4508305b7aeebee33f90d2d6acde8001b` @ `e8aced7dbe`/`de913083da`/`33270ede1b`/`33b4c061c5`/`bc0171f253`/`57d4338bb5`、C6追加実測）で当該hunkの競合は見込めない。`ci.yml`/portfolio側はstack・main分岐によるcontext適応が必要になる可能性（未実行のため断定しない）。

## 8. owner-only実機matrix残項目（Pixel 9a / API 37 — 免除根拠なし）

前提操作（全項目共通）: **Pixel 9a（API 37）をUSB接続し `adb devices -l` に出ていることを確認**。本日時点のローカル観測（C7）はemulator 2台のみで、この接続が存在しない。以下は spec 526/527/528 Verification と #516コメント（2026-10-07・10-09「実機matrixへの引き継ぎ」）・ADR-0018 Decision 7 の委譲項目の列挙。

| # | 項目 | 正確な操作 | 記録先 |
|---|---|---|---|
| O1 | #526 実機表示/操作（**Pixel 9aで実施可能な項目のみ**） | Pixel 9a API 37で home edit surface の Scenario 1〜5（insets消費・適用中back gate・再作成時の破棄案内（TalkBack ONで liveRegion=Polite 一回通知）・fontScale 1.3/2.0・ja/en・portrait↔landscape・IME/cutout）＋ PreferenceActivity配下（organizer hub/preview/exchange/diagnostics、backup/restore、destination picker）の回転/fontScale実測。スクリーンショット/録画をPRへ添付。**注記: spec 526のsw600dp以上 tablet設定の回転/resizeはPixel 9a（phone幅）では物理的に代表実測不可** — tablet形状の証跡はemulator matrix（526 evidenceのsw800dp等、§4 R-6a traced分）で確保済みの方針を維持し、実機でのtablet代替要求は行わない。実tablet端末が用意できる場合のみ owner追加項目 | #526系 docs/assessment 追記（Phase 4同期）＋PR本文 |
| O2 | #527 実機残測 | Pixel 9a API 37で **smartspace tap**・**share chooser**（emulator未実測としてREADMEに明記された2項目）をBAL観測（logcat `ActivityTaskManager`＋debug StrictMode）付きで実行成功確認。自己再起動PIはfailure signature取得済み（creator側最小opt-in判断は別Issue入力、実機で追加block有無のみ記録） | #527系evidence README実機節 |
| O3 | #528 実機残測 | Pixel 9a API 37で LAN permission 3状態（許可/拒否/取消）のsettings案内遷移、TalkBack読み上げ、**release署名buildで katbin bugreport upload 成功/失敗**実測（emulator実測は済、実機再確認） | #528系evidence追記 |
| O4 | Epic exit criteria 代表日常操作 | Pixel 9a API 37（通常構成=非root。provider構成は非root実機不可のため主証跡は済root emulator matrix）で launch/drag/folder/widget/hotseat/search/recents gesture/通知dots の代表操作を通し、**crash/ANR・recents/gesture結果をrepository evidenceへ**（#524/#545の「通常構成の無影響確認」担当範囲） | docs/assessment 実機記録（新規 or #545 dir追記） |
| O5 | 実機provider構成 | ADR-0018 D7「実機でのprovider構成確認はPhase 3実機matrixのowner手順」— 非root Pixel 9aでは構成不可のため、root化可能実機が用意できる場合のみ実施。できない場合は #524 close記録（emulator主証跡＋実機通常構成）で確定済みのowner境界を再確認・記録 | ADR-0018 change history（変更不要の確認記録） |

**証跡identity固定（review指摘のdoc条件）**: O1〜O5の実機証跡を追記する際は、各O項目ごとに **実行source SHA・APK SHA-256・OS build・device fingerprint** を記録し、証跡とbuildの対応を固定する。O1〜O3は各slice branchのbuild identityを読めるが、**O4は対象sliceが相互未統合（§1）のため、どのbuild（統合candidate head）で代表操作を実施するかは未決定であり、owner decisionとして実施時に固定する**（単一統合headはPhase 4 cutover契約が所有）。

**owner action（必須・未了）**: O1〜O5のすべてがPixel 9a（API 37）のUSB接続を前提操作とする。本trace時点で接続なし（C7）。ownerによる接続完了までO項目は実行できない。§13-BのUNCOV 4項目へのemulator coverage追加は別作業であり、この実機gateを代替しない（実機gateもemulator coverageの欠落を埋めない）。

**禁止事項の再確認**: 「closeまで実施」という指示はO1〜O5の実測を創出しない。実機接続なしでのPASS/WAIVER主張は禁止（AGENTS.md 高リスク安全規約・quality-strategy証拠原則）。

## 9. provider/recents系と保持契約の状態整理（close済み=契約保持、と混同しないための表）

| 対象 | GitHub state | Phase 3的取扱 |
|---|---|---|
| #524 | CLOSED（完了） | 判断fix済み（maxSdk 37は #545で採択成立、revision 9） |
| #545 / PR#550 | Issue CLOSED / PR OPEN保持（cutoverまでmerge禁止、ADR D3/8） | R-6b emulator PASS。§5 #550 head CI赤は §6 attempt 2 で **final-status pass 解消済み（2026-10-11。triage限定は§6どおり）** |
| #554 / PR#557 | Issue **OPEN**（AC-1..3/AC-5 runtime確認済みだが **cutoverまでcloseしない明示契約**）。PR #557は545 branchへmerged | 残存未確認範囲: Nothing OS実機・不正payload実転送（[554 evidence README](https://github.com/nunu1733/NunuLauncher/tree/33270ede1b56ca08fbd2f8c2f2092ab7e0295946/docs/assessment/554-api37-recents-evidence)）→ owner実機 or cutover後記録 |
| #555/#562 | CLOSED（main＋559 stack cherry-pick到達 `965c2eb32f`/`c116160796`/`0a6b3061fd`） | R-6b/#563 PASSの前提 |
| #559 | CLOSED | R-6b PASS証跡（恒久assessment） |
| #563 / PR#571 | CLOSED・MERGED（559 stack `33b4c061c5`） | R-6b PASS。accepted residual: emulator視覚oracle基準のowner採用（tier M、#562同一形式、owner上書き可） |
| #565 / PR#570 | CLOSED（main fix） | §7 stack残存注記 |
| #526/#527/#528 / PR#538/#541/#543 | Issue CLOSED / PR **OPEN保持** | §4 R-6aスライス。O1〜O3実機残 |
| #532 / #524証跡PR#546 | Issue #532 OPEN保持（cutover record）。PR #546（`issue-524-api37-quickstep` @ `e214b7b190`）OPEN・保持・final-status pass（[run 37588127420](https://github.com/nunu1733/NunuLauncher/actions/runs/37588127420)、本日実測） | 終了条件はPhase 4。Epic child closeとは独立 |
| #509 | OPEN（Epic完了後再開条件） | exit criteria最終項目の延期記録どおり。Phase 3判定対象外 |
| #537 | CLOSED（既存問題分離） | 対象外 |

## 10. #521 assessment V1〜V8 の処置対応（Phase 3入力）

#521 assessment §5（[issue-521-target-sdk-37-behavior-changes.md](./issue-521-target-sdk-37-behavior-changes.md)）のV1〜V8と§3 T01〜T27の27項目について、rev3で **V↔T↔child AC証跡の写像表を §13-C に作成した**。処置判定は残項目を明示したうえで **PENDING（未完）を維持**し、「全項目対応済み」の主張はしない。traced確定分（T01/T02/T05/T13/T14/T17/T18/T24=実測確定、T03/T09/T10/T12=限定付き、(c)静的11項目=追加変更不要の分類維持）と **残PENDING（未完）**（V3 Arabic/Thai等の行高実測・外部物理keyboard/CJKV IME変換、V6 contacts許可時検索のruntime確認（T20）、V8 packaged/dependency native artifact確認（T19）・static final変更禁止/native DCL consumerのmerged dependency再scan（T11）、T08 merged manifest画像機能確認）は §13-C に列挙。

判断分離として記録済みで未決扱いしない項目（根拠: #516コメント 2026-10-07・#527/#528 close記録）: ECH opt-in（spec 528 Non-goal。新規DNS経路追加に相当するため要時起票）、自己再起動PI creator側最小opt-in（#527 close記録。failure signature保存済み、後続判断入力）。

## 11. Phase 4へ渡すcutover注意（本matrixの範囲外宣言）

1. 単一main merge + 統合headのpull_request CI（final-status）+ cutover head再audit（#532報告契約。CI発火経路調査含む）。
2. ADR-0018 D3/8 のcutover対象SHA更新: 559 stack `63d15f00dc`→`33b4c061c5`（伸長済み・本session実測）、#550 `33270ede1b`、526/527/528 heads §1表。
3. #565 `4b732b43ad` は全Phase 3 stack headに未適用（§7。blob完全一致で残存実在）。**最終統合はPhase 4 cutover契約のみ**。§6の非再現をもって無視・解消扱いとはしない。
4. unset render pivot欠陥は上流16-devにも潜在（#571記録）→ cutover後fork patch再適用判断（`computeScreenCenter` NaN guard・`applyLoadPlan` re-pivot）。`PagedViewScreenCenterTest` はそのまま生存。
5. T9（15fork→16→R→16・APK入替cold-process独立読取）cutover直前実行。baseline切替正本（upstream-strategy/building guide/patch-surface再採択/toolchain）更新。
6. 実機O1〜O5の結果をEpic exit criteria「API 37実機の代表日常操作…repository evidence」行へ反映（close前に本matrix §8表をstate更新）。
7. **Phase 4はcutover（baseline切替・統合・正本更新）を所有するが、Phase 3残項目を引き継ぐのではない**。R-3選択数ラベル・R-6a UNCOV 4項目・§10 V残はPhase 3側で（emulator coverage追加 or owner実機）閉じる。#554のOPEN保持も、rebase stack上のruntime契約検証とcutover/mainline統合までcloseを保留する明示契約であり、実装不足のPhase 4送りではない（§9表）。

## 12. 本日のnet変更と次手（Worker記録 — github-workflow Execution contract準拠）

- 変更file: **本docs 1 fileのみ**（`docs/assessment/issue-516-phase3-closure-matrix.md`、branch `issue-516-phase3-closure-matrix` @ base `8fe67e7650`、production/test/CI指定一切なし → 階層L）。
- GitHub実行副作用: **CI run rerun 2件**（§6。code/PR/Issue/comment変更なし。push・commit・close・mergeなし——publicationはmain orchestration）。
- Review packet: base `8fe67e7650`。head SHAは本docs自身に持たずPR #573のhead記録に従う。diff 1 file（§13 trace明細追加・R-3/R-6a縮小・§10 V写像・§8 identity/owner action・§11境界注記）・未確認範囲=（a）§8実機全項目（owner接続待ち。各O項目に実行source SHA/APK SHA-256/OS build・device fingerprintを固定記載する条件付き）（b）PENDING残: R-3選択数ラベル、R-6a UNCOV 4項目（§13-B）、§10 V残（§13-C: Arabic/Thai・外部keyboard/CJKV・contacts runtime・native artifact/DCL再scan・T08）（c）rerun race排除の限定（§6）。
- 次手（順守順序）: **rev3 commit/push → PR #573 head更新 → ChatGPT再review依頼（main orchestration、新head）** → 残阻塞の処理（UNCOV/V残=emulator coverage追加 or owner判断、O1〜O5=owner接続後の実機実施）。
- owner action（必須・未了）: **Pixel 9a（API 37）のUSB接続**（§8）。未接続のままO項目は実行不可。
- Epic close主張: **しない**（Phase 4未完）。Phase 3 completionの現在の真実の状態は、**（i）R-8実機matrix（owner-only、Pixel 9a未接続＝接続待ち）と、（ii）PENDING残（§13-A/B/Cへ縮小済み）の両方で阻塞**。「ハードウェア接続さえ残る」というready宣言は行わない。PASS確定行は §4 R-1・R-2・R-4・R-5・R-6b・R-7（§6のtriage限定と§5 head対応表の範囲内）。R-3主体とR-6aのfolder/hotseat契約は§13でtrace確定済みだが、判定ラベルは残存明記のとおり。

## 13. PENDING(trace)紐付け明細（rev3 trace収集。実行・test追加なし、既存証跡の照合のみ）

判定語: **T**（scenario/test名とrun/SHA/artifactまで確定）/ **T(p)**（充当可能だが限定付き・incidental・旧baseline）/ **UNCOV**（既存のscenario/test/artifactなし）。run URLは `https://github.com/nunu1733/NunuLauncher/actions/runs/<run ID>` 形式。§5 run群 = `37347294575`（`6c4a88a9eb`）/ `37502010177`（`bc0171f253`）/ `37493739107`（`de913083da`）/ `37564923987`（`57d4338bb5`）/ `38035713365`（`33270ede1b`）/ `38076177185`（`7a30761390`）。全runの `conclusion=success` とhead SHA一致はrev3でAPI再確認済み。

### 13-A. R-3 multi-select / stale / Undo

| 要件 | scenario/test（assertion） | 所在・run | 判定 |
|---|---|---|---|
| multi-select 4 action（spec 449: ページ移動/フォルダ入れ/新規フォルダ/外す。視覚順・all-or-nothing・選択可否） | `tests/unit/app/lawnchair/homeedit/EditSurfaceSessionPlannerTest.kt` — `move to page plans every item deterministically in visual order`、`move to page is all or nothing on missing space`、`add to folder appends in visual order after existing members`、`new folder sits at the first selected item cell with ranks in visual order`、`remove plans a removal change for every selected item`、`touched items cannot be acted on again`、`locked items cannot join an action`、`profile mixing rejects the folder action as a whole`、`confirm gate blocks an empty session`、`a folder row itself is not a selection target` | `organizer-unit-tests` permanent gate（ci.yml `--tests 'app.lawnchair.homeedit.*'`）→ §5 run群 | T |
| セッション→1 transaction適用（無題フォルダINSERT+子UPDATE+物理DELETE） | `tests/organizer-instrumentation/app/lawnchair/homeedit/EditSurfaceApplyInstrumentationTest.kt` — `sessionAppliesAsOneTransactionWithPhysicalDeleteAndUntitledFolder` | shared-writer lane（ci.yml 明示class list）→ §5 run群 | T |
| stale反映 fail-closed（零書込み） | `EditSurfaceApplyInstrumentationTest.staleRevisionIsRejectedWithZeroWrites` / `DirectEditModelWriterTest.deferredMoveRejectsStaleTargetWithoutWrite` / `DirectEditUndoModelWriterTest.restorePlacementRejectsStaleWithoutWrite` / `EditSurfaceUndoInstrumentationTest.writeAfterConfirmMakesTheUndoStaleWithZeroWrite`・`undoWithAMismatchedRevisionIsRejectedAsStale` | shared-writer lane → §5 run群 | T |
| stale gate×世界移動（spec 526 oracle 3b）＋selection×recreate | `EditSurfaceTarget37ContractInstrumentationTest`（526 branch @`bc0171f253`）— `zeroWriteTerminalReleaseThenWorldMoveFailsClosedThroughTheStaleGate`、`recreateAfterSelectionOnlyShowsTheDiscardNoticeWithPoliteLiveRegion` 他recreate 3 case | run `37502010177`（526固有）。API 37 emulator 10/10はPR #538本文記録（local head `5d7775720f`） | T |
| rollback / Undo逆操作 | `EditSurfaceApplyInstrumentationTest.injectedWriteFailureRollsBackToTheExactPreState` / `DirectEditUndoModelWriterTest.undoCreateFolderRestoresChildAndDeletesFolderInOneTransaction`・`undoCreateFolderRollsBackBothWritesWhenTheDeleteFails`・`folderUndoAbandonedMidTransactionLeavesThePreStateAfterReopen` | shared-writer lane → §5 run群 | T |
| 選択数ラベル表示（spec 449 Scenario: 複数選択と選択数の表示） | count表示をassertするoracleなし（`EditSurfaceA11yDescriptionTest` はselected-state semanticsのみ）。専用UI tap journey scenarioなし。旧baseline `450-edit-undo-ac1-screenshots` は#448単一itemで充当不可 | — | UNCOV |

### 13-B. R-6a drag/drop・folder・hotseat・notification dots

| 要件 | scenario/test（assertion） | 所在・run | 判定 |
|---|---|---|---|
| widget picker drag手順 | 527 `api37-07-widget-drop-attempt.png`（README S2）→ bind同意08→配置09 | 527 evidence @`54a75042305c`、API 37 emulator | T(p) |
| workspace icon drag | 526 Scenario 3 tablet行「dragでworkspaceにアイテムを追加して実施」→ `api37-tablet-scenario3-notice-landscape.png` | 526 evidence @`bc0171f253`、API 37 tablet emulator | T(p) incidental |
| workspace drag/drop専用scenario | なし。`OrganizerHubDragGuardInstrumentationTest` はorganizer hubのdrag-guardで別surface。upstream `tests/tapl`/`multivalentTests` はCI unroutedでrun証跡にならない | — | UNCOV |
| folder 作成・追加・Undo（writer契約） | `DirectEditModelWriterTest.existingFolderAddUpdatesDbAndModelState`・`createFolderSuccessUpdatesDbCollectionsAndContents`・`createFolderFailureLeavesModelAndDbAtOldPlacement` / `DirectEditUndoModelWriterTest.undoCreateFolder*`・`folderUndoAbandonedMidTransactionLeavesThePreStateAfterReopen` | shared-writer lane → §5 run群 | T |
| folder UI（作成/入れる）截图 | `docs/assessment/450-edit-undo-ac1-screenshots/450-edit-undo-direct-create-folder-*`・`…-add-to-folder-*` | **旧baseline**。Phase 3（新baseline）証跡に充当不可 | T(p) |
| folder開閉UI | なし（upstream folder test unrouted） | — | UNCOV |
| hotseat restore admission | `com.android.launcher3.hybridhotseat.HotseatRestoreAdmissionTest` — `createBackupUsesAtomicAdmissionAndCreatesBackupTable`、`restoreExistingBackupExecutesAndDropsBackupTable`、`deferredRestoreLetsExactCorrelatedReloadCompleteBeforeOrganizerRelease`、reservation blocking系 | shared-writer lane → §5 run群 | T |
| hotseat undo slot（spec 450） | `HomeEditUndoPlannerTest` — `hotseat undo restores when the recorded slot is within the current capacity and free`、`hotseat undo rejects with zero write when the current hotseat shrank below the recorded slot` | organizer-unit-tests → §5 run群 | T |
| dock/hotseat保持（#441 fixture seeding） | `EditingBurdenBenchmarkFixtureSeedingInstrumentationTest.fixtureSeedsIdenticallyFromSameInputAndPreservesDockAndReservations`、`seedingPreservesPreExistingHotseatFolderDescendants` | manual-organization-ui lane → §5 run群 | T |
| hotseat操作UI（起動・drag投入） | なし（`EditSurfaceProjectionTest` のdock投影はJVMのみ） | — | UNCOV |
| notification dots（badging） | なし。527の通知evidence（`api37-15-notification-tap-view.png`、通知action→`UploaderService`）は通知tap/actionでありdotsではない。#521 T16の(c)はSMS consumer不在の静的判定でdots動作の証拠ではない | — | UNCOV |

UNCOV 4項目（workspace drag/drop専用・folder開閉UI・hotseat操作UI・notification dots表示）は、**emulator上でのscenario/test追加によりcoverageを追加できる残課題**であり（本PRでtest追加はしない）、§8 O4/R-8の実機gateとは独立した要件で相互代替しない。UNCOV項目を527通知evidence・`OrganizerHubDragGuardInstrumentationTest`・T16 (c)で充当しない。

### 13-C. §10 #521 V1〜V8 ↔ T01〜T27 写像（child AC証跡へ。完全性主張なし）

| V | 紐付け（child AC evidence） | 残（PENDING・未完） |
|---|---|---|
| V1 / #526 | 526 emulator matrix（PR #538本文）＋ `EditSurfaceTarget37ContractInstrumentationTest` 10 test @run `37502010177` | 外部keyboard操作・notification dots（13-B UNCOV） |
| V2 / #526 | tablet sw800dp回転/resize（`api37-tablet-scenario1-insets-landscape.png`、`api37-phone-backup-route-resize-720.png`）＋recreate破棄3 oracle | （O1のPixel 9a phone幅限界は§8注記どおり） |
| V3 / #526 | fontScale 1.3/2.0・ja/en・TalkBack・IME可視 | **Arabic/Thai等の行高実測**（accepted spec 526 Verificationがja/en範囲。#521 T03要件との差）・**外部物理keyboard/CJKV IME変換** |
| V4 / #527 | 527 S1〜S5 ＋ `api37-restart-pi-block-signature.txt` ＋ `api37-strictmode-policy-inherit-verify.txt` | smartspace tap・share chooser未実測（README宣言→O2） |
| V5 / #528 | LAN 3状態・typed failure・katbin・redaction・API 36比較（evidence 02〜32） | katbin実機再確認（→O3）。CT固有failureのemulator再現不可はREADME記録どおり |
| V6 / rebase統合 | widget/Smartspace: 545-evidence qa3/qb3 @`33270ede1b` ＋ 527 widget chain ＋ restore-capture（replay-log §6.5） | **contacts許可時検索のruntime確認（T20）**・large RemoteViews上限超過case |
| V7 / #516・#522 | replay-log G4 T1〜T8 §6.5全green @`e8aced7dbe` ＋ G2 unit ＋ db-migration/restore-capture/reservation-recovery lanes（§5 run群） | T9はPhase 4所有（変更なし） |
| V8 / Phase2 build | G1/G5 `37347294575` ＋ Espresso 3.7.0（C7）＋ `LooperIdleLock` public IdleHandler（C8）＋ API 37 local 10/10（PR #538本文） | **packaged/dependency native artifact確認（T19）**・**static final変更禁止/native DCL consumerのmerged dependency再scan（T11）**・merged manifest画像機能（T08） |

T行bucket: **T（実測確定）** T01, T02, T05, T13, T14, T17, T18, T24 / **T(p)** T03, T09, T10, T12 / **(c)静的のみ（追加変更不要の分類維持。Phase 3新証跡なし）** T04, T06, T07, T15, T16, T21, T22, T23, T25, T26, T27 / **PENDING（未完）** T08（merged manifest画像機能）, T11（static final/DCL再scan）, T19（native artifact）, T20（contacts runtime）。

## Change history

- 2026-10-11: Draft（#516 Phase 3 close-readiness照合。C1〜C7実測、rerun 2件実行・attempt 2両green）。
- 2026-10-11（rev2・公開前tighten）: 過剰な完全性主張を訂正——R-6a/R-3をPENDING(trace)へ格下げ、§10「残欠無し」撤回、§1統合スコープ解釈のqualify、§6「class 5確定」をtriage分類へ軟化（race排除不可を明記、#565はPhase 4契約統合のみ）、§9 #550状態を解消済みへ更新、O1のtablet項目をPixel 9a非代表として分離、§12のhead自己参照削除と次手順序（commit/push→PR→review）修正、旧CONDITIONALラベル削除、日本語表記統一。
- 2026-10-11（rev3・trace紐付け）: §13にR-3/R-6a/§10のtrace明細（test名・SHA・run ID）を追加。R-3はPENDING範囲を選択数ラベルへ、R-6aはUNCOV 4項目へ縮小（emulator coverage追加可能性と実機gateの独立性を明記、他surface証跡の充当禁止を固定）。§10にV1〜V8↔T01〜T27写像と残PENDING（Arabic/Thai・外部keyboard/CJKV・contacts runtime・native artifact・static final/DCL再scan・T08）を明示。§8に証跡identity固定（実行source SHA/APK SHA-256/OS build・device fingerprint）とO4 build未決定・owner action接続を追記。§11にPhase 4境界（残項目の非引継ぎ・#554契約）を固定。test/CI/production変更なし。
- 2026-10-11（resume・current-snapshot追加）: §14を追加。§0〜§13はdraft d82時点の歴史的観測として保持し、Pixel 9a absent等の当時記述は改変しない。current truthは§14のみがsupersedeする。

## 14. Current snapshot 2026-10-12（§0〜§13の歴史的観測をsupersedeする現時点。closure判断なし）

§0〜§13は過去時点の記録として保持する。以下は2026-10-12時点のcurrentであり、証跡文書は作業中の未公開artifactであってpublic/mainの状態を意味しない。

| 項目 | 現時点の状態 | 証跡 |
|---|---|---|
| O1 UI | Instrumentation 10/10、screenshot count 2、fonts/insets/recreate/back証跡あり。TalkBackのexact audible-onceはclaimしない | `516-phase3-device-evidence/o1-ui/o1-result.md`（private working receipt）、source `bc0171f253`、APK `343169...` |
| O2 share chooser | 実測BAL block 0 | `516-phase3-device-evidence/o2-bal/o2-share-chooser-result.md`（private working receipt）、source `54a750...` |
| O2 Calendar date-card tap | **PASS**。reviewed source `54a75042305c74ddc0cb118ce6313e2c1746ccca`、APK SHA-256 `5096d9413b55f71d18fb58496beec2194e9a43f36decc0f17ab27fceed7c1f8a`。`BAL_ALLOW_VISIBLE_WINDOW` code 0を確認。other `PendingIntent` variantsはassert対象外 | `516-phase3-device-evidence/o2-bal/o2-calendar-card-result.md`（private working receipt） |
| O3 LAN | allow/deny/cancel実shot 3/6/7、TalkBack focus/TTS request観測あり。permission baselineのfinal worker claimは独立検証なし、receipt参照 | `516-phase3-device-evidence/o3-lan/o3-lan-permission-states.md`（private working receipt）、`516-phase3-device-evidence/o3-lan/o3-katbin-result.md`（private working receipt）、source `57d4338`、APK `c908...` |
| O3 katbin release | buildType appID debug QA artifact `33fc...`のsuccess/failure既存resultあり。debug cert signingでありproduction release keystoreではないこと、suffix config未commitをlimitationとして記録 | `516-phase3-device-evidence/o3-lan/o3-katbin-result.md`（private working receipt） |
| R6 folder + hotseat | 実測PASS | `516-phase3-device-evidence/o4-ops/o4-ops-result.md`（private working receipt）、`516-phase3-device-evidence/o4-ops/hotseat/o4-hotseat-result.md`（private working receipt）、source `33b4c061`＋574 fix |
| R6 notification dots | main opened `02` PNGで複数dotのbasic renderingを確認。diagnostic controlled owned-cycleは `BLOCKED`（新Epicの必須gateとはしない）。privacy curation required | `516-phase3-device-evidence/o4-ops/dots/o4-dots-result.md`（private working receipt） |
| R6 drag | manual owner restoration **PASS**。screenshot `manual-obs-20261012-000826.png` / XMLでcolumn 3、column 2 emptyを確認。自動化失敗の原因はunknownで、source unchanged | `516-phase3-device-evidence/o4-ops/drag/manual-observation.md`（private working receipt）、`516-phase3-device-evidence/o4-ops/drag/o4-drag-result.md`（private working receipt） |
| V3 typography | Arabic/Thai＋ja IME PASS。Bluetooth physical keyboard接続はmain観測済み、代表入力/Esc取消/回転はowner-observed PASS（2026-10-12追補）。追補の実行APKは復元済み`260bccda...ff55da`で旧V3 sliceとは異なり、source provenance照合/reviewはPENDING。CJKV scopeも未完で、optionalまたはwaivedとは扱わない | `516-phase3-device-evidence/v3-typography/v3-result.md`（private working receipt） |
| V6 contacts | source PR #575 fixed head `000c26903b9f0880ce551721dd89c3bb09da1ad6` verified; physical 37＋API 36 search/tap/cleanup PASS。main merge保留、Epic #516 overall closeはPhase 4で別途 | `516-phase3-device-evidence/v6-contacts/v6-result.md`（private working receipt）、`516-phase3-device-evidence/v6-contacts/bug-contacts-search-account-column-api37.md`（private working receipt）、[GitHub #575](https://github.com/nunu1733/NunuLauncher/pull/575) |
| V6 CI | run `38141049003` attempt 2 success、exact fixed head `000c26903b9f0880ce551721dd89c3bb09da1ad6`。初回Compose failureはclass 6でtracked #576、retryで修正済みとはしない | `516-phase3-device-evidence/v6-contacts/fix-verification-packet.md`（private working receipt）、[run 38141049003](https://github.com/nunu1733/NunuLauncher/actions/runs/38141049003) |
| V8 source rescan | direct setters 21、zero 22、three 3を確認。declaring modifiersとgeneric helpersはpending | `516-phase3-device-evidence/v8-artifact/packaged-consumers.md`（private working receipt） |

End state: **Phase 3 NOT CLOSED**。未完了はphysical keyboard追補のsource provenance照合/review、CJKV scope、V8 unclassified consumers、teardown unknown baselines、privacy curation/publication/review。検証用crash通知の不在は2026-10-12のowner回答で確認済み（main独立観測ではない。履歴とlimitsは`516-phase3-device-evidence/receipts/teardown-receipt.md`（private working receipt））。実機O2 Calendar date-card tap、R6 drag manual restoration、V6 exact-head CIは完了した証跡として扱う。R3のcountは2 observedのまま。artifact文書は未公開で、privacy curation/reviewが必要。Epic #516 overall closeはPhase 4に属し、ここではclosureを主張しない。


## 15. Codex continuation — current closure work (2026-10-13)

Status: **Phase3 completion record, effective when PR573 merges after exact-head review and required checks.** Execution and source-fix validation are complete within the trace boundaries below; final publication review/checks are the remaining gate. This section supersedes the current-state labels of §§0–14, which remain historical snapshots. The [curated evidence index](./516-phase3-closure-evidence/README.md) contains the limited publication; original untracked device evidence and private backups are protected. Epic516 remains OPEN and Phase4 cutover/T9 have not been performed.

- Restored keyboard/CJKV APK source provenance is resolved as full33b4c061 base plus the ten-line contacts fix, with an explicit precommit-build identity. [Provenance](./516-phase3-closure-evidence/identity-and-provenance.md).
- zh/ko/vi conversions and owner-confirmed audible TalkBack input feedback are recorded; temporary languages/IME/accessibility changes were restored. Owner accepts current font1.0 / OS auto-rotationON / home-rotationOFF / Simeji / ja-JP / Nova. Unknown historical originals remain limits. [CJKV/teardown](./516-phase3-closure-evidence/cjkv-and-teardown.md).
- V8's packaged reflective/native consumer inspection is complete within its enumerated artifact boundaries. [Consumer inspection](./516-phase3-closure-evidence/v8-consumer-inspection.md).
- Disposable API37 large-home production capture/preview/apply (500→501,498 unchanged,49 locks retained) and fresh-process durable recovery to the exact500-row readonly SQLite projection pass; real ZIP32/33 restore passes2/2. This is not a replacement for SAF or a physical500-item result. [Data/recovery packet](./516-phase3-closure-evidence/api37-data-and-recovery.md).
- [#577](https://github.com/nunu1733/NunuLauncher/issues/577)/[#578](https://github.com/nunu1733/NunuLauncher/pull/578) repaired the benchmark fixture's UNKNOWN encoding; exact-head CI/independent reviews and old-encoding RED/fixed-encoding GREEN are complete. Merge `7985a45a6e0d14977bd0f0c155882de65756254c`; source applicability to16 is recorded separately.
- Normal-folder capture defect [#579](https://github.com/nunu1733/NunuLauncher/issues/579) is fixed by [#580](https://github.com/nunu1733/NunuLauncher/pull/580), merged to current main/15 as `840603371c498fa6162192facf9a2aa73dadc242`. Independent Standards/Spec reviews and separate audit are complete; exact source CI `38227448412`, final head CI `38248477685`, and published high-risk gate pass. The separate 16 validation port enabled physical capture without changing DB rows. [Physical discard notice](./516-phase3-closure-evidence/physical-discard-notice.md) records owner-observed audible-once PASS, restoration of the previous APK/settings, and exact43-row comparison. The earlier failed capture attempt remains excluded. This repairs a preexisting15/16 representation defect; it is not a Phase4 cutover.
- R3 actual UI tap/deselect/refusal and all four staged figures, including fully visible Page3 moved cards, are now complete. Cancellation preserves all42 fixture rows/columns. Actual Apply/Undo ownership remains with the already traced production test lanes. [R3 receipt](./516-phase3-closure-evidence/r3-actual-ui.md).
- [Private inventory limits](./516-phase3-closure-evidence/private-inventory-limits.md) distinguishes the historical129-file hash snapshot from the current selected follow-up inventory; no private originals are included in publication.

- Native Smartspace home placement and actual minute update, plus UI ON/OFF state, are recorded in [Smartspace/page capture](./516-phase3-closure-evidence/smartspace-and-page-capture.md). Existing API37 converter/restore-capture results remain distinct from those UI observations. Actual OFF capture exposed [#581](https://github.com/nunu1733/NunuLauncher/issues/581), fixed by [#582](https://github.com/nunu1733/NunuLauncher/pull/582): merge `0ef86713b9414f0aa85c028e2e77d3ec14c0f4db` (2026-10-12T15:31:59Z), test-source `94d0c088df979b4d0aa32158701aa8ab62c530b4`, and audit publication `eead64fabe9581f997a54924d43f660875257669`. All-affected/final CI [38267058803](https://github.com/nunu1733/NunuLauncher/actions/runs/38267058803) and high-risk gate [38267058744](https://github.com/nunu1733/NunuLauncher/actions/runs/38267058744) passed; independent full-current Standards review (Luna) and Spec review approved. The original failed CI remains recorded as historical evidence. This closes the QSB-off page-capture correction and audit; it does not close Phase 3.
- Actual SAF Exchange Reply→Import exposed #583 main-thread run query and a queued-UI ownership race. [PR584](https://github.com/nunu1733/NunuLauncher/pull/584) merged as `c3898c74f32144cd1bc229e1a153ed08a3aeb253` (2026-10-13T05:00:59Z). Source/test `5cefa6dfff0d9af3a76bebe9d5ebc1950c5e7da0`, original/independent CI [38324607913](https://github.com/nunu1733/NunuLauncher/actions/runs/38324607913), docs publication `0bbc9f6b045ebbf0462846cac808044c9951022f`, final CI [38347440730](https://github.com/nunu1733/NunuLauncher/actions/runs/38347440730) and gate [38347440751](https://github.com/nunu1733/NunuLauncher/actions/runs/38347440751) pass. Independent audit and full-current Standards/Spec approve. Historical77 CI38321690999 FAILED manual UI2/155: #585 repaired existing Direct-publication fixtures; #586 remains the distinct unexplained JIT return timeout. Separate16 source75492ec6407b4c36645186c519bdd93583008f5d/APKa02cf76f0557c76cf751d852effb4d724384db33c0225c9ad11cba10fe7bd74e actual OpenDocument Import/cold review/confirmed discard are complete, pending bytes and500-row DB/prefs preserved until explicit discard. [SAF packet](./516-phase3-closure-evidence/saf-backup-and-import.md).
- Native original43 restore, folder open/Back, bounded drag and exact restoration projection, permissionless probe install/launch/uninstall refresh are complete and source-bound. [Native operations](./516-phase3-closure-evidence/native-operations.md) excludes inconsistent pixel/XML pairs and bounds package launch evidence to actual focus/XML.
- Supported cold exported settings restore exposed [#587](https://github.com/nunu1733/NunuLauncher/issues/587), reproduced on actual15/16 before cleanup/wipe. [PR588](https://github.com/nunu1733/NunuLauncher/pull/588) initializes the existing AppState/module on main before the restore lease. The source fix was introduced by `f34c3b90702618aabba36d58cf47cc2d2cbaf7a1`; audited production-tree/CI head `059f498ec22a298ecdade53d585e4c9d529c74e9` (its next commit changes only the packet, not production bytes) has6 local safety regressions PASS and exact source CI [38350644104](https://github.com/nunu1733/NunuLauncher/actions/runs/38350644104) PASS. Separate16 source62cd636/APK10580 cold exportedSettings→realSAF→singleRestore→43 semantic/prefs→nativeCOLDrestart is now GREEN. The earlier pre-Restore bootANR and missing-companion5-row invocation are retained separately; no success is inferred from those runs. [Cold settings proof](./516-phase3-closure-evidence/cold-settings-restore.md). Independent audit is accepted; publication head ed7a4d2cd83ad1ff5fb946f9938031571e0e65bc passed high-risk gate [38353976636](https://github.com/nunu1733/NunuLauncher/actions/runs/38353976636) and all-applicable/final CI [38353976637](https://github.com/nunu1733/NunuLauncher/actions/runs/38353976637). Full-current independent Standards/Spec approved in the [review record](https://github.com/nunu1733/NunuLauncher/pull/588#issuecomment-6139309560). PR588 merged as a9ec830a81d2924d8ff96108f576936c2b2af207 at2026-10-13T06:10:09Z; Issue587 is CLOSED. This is normal main/15 maintenance and a separately validated16 source port, not Phase4 cutover.
- Both task-owned validation emulators are stopped with data/evidence retained privately; current candidate installed APK bytes were independently verified. Protected emulators and physical Pixel were not changed. [Owned fixture teardown](./516-phase3-closure-evidence/owned-fixture-teardown.md) preserves known-baseline checks and unavailable final-listener reread limits.

Completed distinct evidence also includes native normal/large/rejected widgets, the owned-emulator synthetic notification cycle, actual work-profile pause/resume and named profile tests, canonical500 SAF positive/cancel/cold restore and112-candidate manual preview/dismissal. Remaining gate: PR573 limited publication, final privacy/hash/link checks, exact-head review and required CI. Successful completion/merge makes this Phase3 completion record effective. Known CI-cause follow-ups #576/#586 and unavailable historical original settings remain explicitly tracked limitations, not silently reported fixed by rerun success. Previously completed Calendar tap, physical drag restoration, #575 exact-head CI, and #582 QSB-off fix/CI/audit remain retained evidence. Epic close and Phase4 cutover/CI/audit/T9 are separate and not performed.
