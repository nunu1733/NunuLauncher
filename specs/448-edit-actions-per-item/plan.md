# Implementation Plan: 項目単位の編集アクション（#448 第1段）

> Issue: #448
> Spec: [spec.md](./spec.md)
> Status: draft
> Risk tier: H — Issue #448（メモ§4.7）が本機能を階層Hへ割り当て済み。現行workflowの階層H条件「Launcher DBへの新しい書込み経路を作る」「上流のmodel/loaderへのbridgeを作るまたは変える」に当たる（`ModelWriter.java` への最小操作追加 + fork側homeedit moduleの新設）。手順は現行どおり: accepted spec + plan.md、Execution and approval contract、`risk: layout-data` labelによる高リスク独立エビデンス（`final-status` + `docs/assessment/pr-<PR番号>-<slug>.md` の独立audit）。
> Revision 2: 2026-09-27 — Phase 1 review（[#448 comment](https://github.com/nunu1733/NunuLauncher/issues/448#issuecomment-5856887871)）の指摘1〜4に対応。指摘1: 新規フォルダの置き先を「置き先ページ選択 → そのページの空きセルに1x1」へ一意化（Current evidence/Design/Alternatives/Undo evidenceを同期）。指摘2: 「外す」を即時1回DELETEへ変更し、上流 `prepareToUndoDelete` + snackbarの再利用をやめる（#450所有。Current evidence/Design/Alternatives/Verificationを同期）。指摘3: 現行main（d3b5aba550503c6023224e64452426d8a1b32353、PR #471でADR-0014収録）へmergeし、ADR-0014参照を `docs/adr/0014-edit-surface.md`（Proposed Revision 2）へ更新。指摘4: stage-1 snapshotの権威と実行threadをmodel executorへ固定し、data flow・module説明・test oracleを同期。
> Revision 5: 2026-09-27 — Phase 2 review round 2（[#448 comment](https://github.com/nunu1733/NunuLauncher/issues/448#issuecomment-5858244121)）対応。①production `ModelWriter` 直接編集seamを通すinstrumentation test `DirectEditModelWriterTest` を新設（既存folder追加のDB+model+contents、create-folder成功/失敗注入のDB+live item+collections、removeの対象行のみ、ORGANIZER lease中のdefer→lease解放後のstage-2実行。`LauncherModel` test subclassが`getModelDbController()`をtest DBへ差し替え）。②`sourcePlacement`を`by lazy`からconstructor即時凍結へ変更（live `ItemInfo`がdialog表示中に移動してもpreconditionを取り直さない）。③specを`implemented`へ遷移（AC-13）。
> Revision 4: 2026-09-27 — Phase 2実施。実装とテストの記録は「Phase 2 record」節へ追記した。実装中に確定した実装詳細（UI反映はaccessibility precedentのview除去+bindItems、REDUNDANT判定はscreen一致を含む、QSB予約領域を両投影に合成行として追加、add-to-folderのtargetRank引数、folder子行のscreen=0/cell=-1は上流のorganizer batch正規化に合わせる）はspecの観測可能な振る舞いを変えない範囲でplan側に記録。
> Revision 3: 2026-09-27 — Phase 1 再review round 2（[#448 comment](https://github.com/nunu1733/NunuLauncher/issues/448#issuecomment-5856991631)）の指摘1〜4に対応。指摘1: `CreateFolderAndAdd` intentの置き先page/screenをplanner入力契約へ明記（module説明/Design）。指摘2: Verification表をB2/B3/B4の第1段単独記録へ拡張（新規フォルダ経路の会計を含む）。指摘3: AC-14（アクセシビリティevidence）の割付を追加。指摘4: handoff packetのrevision/head/diffを現状へ同期。
> Phase 1（本書の初版）: spec + planの起草とreviewを追跡する。Phase 2（実装）は同じbranch/PRで行い、本planのRevisionで追跡する。

## Current evidence

本planの `path:line` 根拠は2026-09-27にmain `670527526490bfda5ec0862421ac3da790fafaa2` 上で検証した。

**popupとsystem shortcut（fork側拡張点）**

- `lawnchair/src/app/lawnchair/LawnchairLauncher.kt:286-296` — `getSupportedShortcuts()` が `Stream.concat` でfork側shortcut（UNINSTALL / CUSTOMIZE / PAUSE_APPS / `OrganizerLockShortcut.PLACEMENT_LOCK`）を追加する。3アクションはここへ追加分を加えるだけで、上流ファイルの変更は不要。
- `lawnchair/src/app/lawnchair/ui/popup/OrganizerLockShortcut.kt:39-50` — `SystemShortcut.Factory` としての対象絞り込み（`ITEM_TYPE_APPLICATION` / `ITEM_TYPE_DEEP_SHORTCUT`、`itemInfo.id == ItemInfo.NO_ID` でnull）と、`:128-145` の `AlertDialog` 確認dialog、`:70-75` のバックグラウンド実行 → mainHandler → Toastの非同期パターン。本Issueのdialog/結果通知の慣行の実例（このTHREAD_POOL例はlock storeの読み取りであり、`BgDataModel` のsnapshot取得threadの根拠には使わない。後述のDesign節どおりmodel executorを使う）。
- `src/com/android/launcher3/folder/Folder.java:380` — フォルダ内アイテムのlong-clickは `mLauncherDelegate.beginDragShared(v, this, options)`（popupではなくdrag）。フォルダ内アイテムにpopupが出ない構造の根拠。
- `src/com/android/launcher3/popup/SystemShortcut.java:337` — `UNINSTALL_APP`（「外す」とは別物の根拠）。

**model writerとadmission（書込み経路の中心）**

- `src/com/android/launcher3/model/ModelWriter.java:190-220` — `moveItemInDatabase`。admission前に `updateItemInfoProps`（`:192`）で `ItemInfo` を変更し、desktop移入時はspanを1x1へ正規化（Issue #269、`:199-204`）、`enqueueDeleteRunnable(new UpdateItemRunnable(...))`（`:207`）。
- `ModelWriter.java:252-267` — `modifyItemInDatabase`。同様にadmission前にprops + spanを変更（`:254-256`）。
- `ModelWriter.java:290-314` — `addItemToDatabase`。admission前に `generateNewItemId()`（`:294`）とbindItems callback（`:295`）。この既存構造を直接編集は踏まない（ADR-0013契約4）。
- `ModelWriter.java:335-352` — `deleteItemsFromDatabase`。notifyDelete（UI除去）→ 1行delete + `removeItem` + verifier。
- `ModelWriter.java:400-442` — `prepareToUndoDelete` / `enqueueDeleteRunnable`（pending時は `mDeleteRunnables` へqueue） / `commitDelete` / `abortDelete`（無書込み + `forceReload`）。上流の削除Undoの機構。**本Issueはこの機構を使わない**（ADR-0013契約5が「再利用してよい（詳細は#450のspecで決める）」と委ねるのみであり、snackbar・窓・Undo UIは#450が所有するため。popup経路ではdragのような即時view除去の別経路もないため、遅延commitを採ると削除が4秒遅れて見え、NFR-013とも矛盾する）。「外す」はadmission内の再検証後の即時1回DELETEとし、Undo evidenceの記録のみ行う。
- `ModelWriter.java:459-476` — `UpdateItemRunnable.runImpl`。単一行の1回update（自ずと原子的）。`:478-503` — `UpdateItemsRunnable`。複数行のみ `SQLiteTransaction` だが失敗を握りつぶす（`:499-501`）。直接編集の複数行アクションはこの経路を使わず `newTransaction()` を自前で使う（ADR-0013契約3）。
- `ModelWriter.java:505-555` — `UpdateItemBaseRunnable.updateItemArrays`。`checkItemInfoLocked` + workspaceItems整理 + `ModelVerifier`。直接編集のtaskもこれを再利用する。
- `ModelWriter.java:557-582` — `ModelTask`。`run()` はloadId変化でskip、`executeOnModelThread()` が `LayoutWriteCoordinator.runOrDefer(MODEL_WRITER, token=0, exact=false, ...)` でgate。
- `src/com/android/launcher3/model/LayoutWriteCoordinator.java:460-497` — `runOrDefer`。ORGANIZER / restore-family lease中のtokenless workはFIFOへdefer（`:525-527`）。
- `src/com/android/launcher3/model/ModelDbController.java:298-303` — `newTransaction()`（close/commit/rollback、coordinator leaseをtransaction closeまで保持）。

**フォルダ・移動先・フォルダ作成の上流実例**

- `src/com/android/launcher3/model/data/FolderInfo.java:125-143` — `add(item, rank, animate)`。rank管理と `Folder.willAccept` 検証（model内list操作。DB書込みをしない）。
- `src/com/android/launcher3/Workspace.java:2117-2175` — `createUserFolderIfNecessary`。drag経路のフォルダ作成は `Launcher.addFolder`（`src/com/android/launcher3/Launcher.java:2043-2061`）で `addItemToDatabase(folderInfo, ...)` → FolderIcon生成。popup経由では相手セルがないため、本Issueは「置き先ページの空きセルに1x1フォルダをINSERTし子をUPDATEする」別の最小操作を追加する（spec Outcomeどおり、新規フォルダ時は置き先ページを選ぶ）。
- `src/com/android/launcher3/accessibility/LauncherAccessibilityDelegate.java:350-388` — `findSpaceOnWorkspace`。UI側の空きセル探索（`CellLayout.findCellForSpan`）+ 空ページ生成の実例（参考のみ。本Issueの探索は純粋計画関数で行い、新規ページは作らない）。
- `LauncherAccessibilityDelegate.java:484-506` — `moveToWorkspace`。非dragの `moveItemInDatabase` 利用の実例（上流のまま変更しない）。

**ロック・ベンチマーク・CI**

- `lawnchair/src/app/lawnchair/organizer/locks/EffectiveLocks.kt:44-69` — `LockEffectNote`（typed note + localized mappingの慣行。dialog内のロック注記はこの慣行に従う）。
- `docs/engineering/editing-burden-benchmark.md` §6 — baseline B2=48 / B3=21 / B4=25（2026-09-26確定）。目標はNow-2全体（B2≤24 / B3≤10 / B4≤12）、第1段単独の値は記録のみ。§7にagent実行可能な検証手順。
- `.github/workflows/ci.yml:374-418` — `organizer-instrumentation-shared-writer-tests` lane（`surface_layout_write`）。class list `-Pandroid.testInstrumentationRunnerArguments.class=...` へ新規test classを追加する形。`organizer-unit-tests` gate（同ファイル）の `--tests` filterは `app.lawnchair.organizer.*` 等の列挙であり、`app.lawnchair.homeedit.*` の追加が必要。
- `tools/repo-contract/ci_portfolio_map.yml` — lane↔surface map。`organizer-instrumentation-shared-writer-tests` が `surface_layout_write` を所有、JVM treeは `organizer-unit-tests` permanent gate。新規laneは作らない。
- `tools/repo-contract/validate_writer_inventory.py` — DB書込みpatternに一致するfileはALLOWLIST必須。homeedit配下に直接DB書込みを置かない設計では、homeeditがpattern一致しないことが機械的に検証される（fail-closedのbackstop）。
- `tools/repo-contract/validate_high_risk_evidence.py:45-` — 高リスクpath一覧。`ModelWriter.java` は既に収録（本PRはgate発火対象。auditは別sessionで実施）。

**前提の確認**

- ADR-0013（`docs/adr/0013-direct-edit-write-contract.md`、accepted、#445 merge済み）— 書込み契約6項と要求テスト表の正本。本spec/planは重複定義しない。
- ADR-0015（#446、accepted）— 同じ契約4の「admission内完結」構造とclosed resultパターンの受入先例。本planはこれを単一アイテムの移動/フォルダ/削除へ適用する。
- ADR-0014（`docs/adr/0014-edit-surface.md`、Proposed Revision 2、#447起草。2026-09-27にPR #471で現行mainへ収録済み）— 第1段popup（#448）は `SystemShortcut.Factory` に載り追加bridgeを要求しないこと、第2段（#449）はfork側視覚的編集画面で純粋計画を共有し確定時にorganizerの安全な適用経路を使うこと、を確認済み。本specのmodule分担と矛盾しない。受入（Accepted）は #442 の最終結論を前提とするため、受入時に再照合する。
- 分担の確定（ADR-0013契約5・Issue #448 Non-goalsどおり）: UndoのUI・窓・寿命・逆操作は#450が所有する。本Issueは「外す」を即時の1回DELETE（admission内再検証後）とし、Undo evidenceの記録のみを行う。上流の `prepareToUndoDelete` 機構は使わない。

## Design

### Modules and interfaces

```text
app.lawnchair.homeedit/                  （fork側。新設）
├── HomeEditSnapshot.kt                  # 編集snapshotの投影型（純data）
├── HomeEditPlanner.kt                   # 純粋計画関数（intent → closed result）
│                                        #   成功: MoveToPage / AddToFolder / CreateFolderAndAdd / Remove
│                                        #   失敗: Reject(typed理由)
│                                        #   CreateFolderAndAddのintentは選択済み置き先page/screenを必ず含み、
│                                        #   stage 1/2ともその同じ置き先を検証する
├── HomeEditAdapter.kt                   # model/DeviceProfile → HomeEditSnapshot の投影
│                                        #   + DirectEditContract のvalidator実装（Plannerへ委譲）
├── HomeEditExecutor.kt                  # 確定UI flow: 一覧取得(model executor) → dialog
│                                        #   → 確定時snapshot+計画(model executor) → ModelWriter
│                                        #   直接編集操作のsubmit → 結果通知
├── HomeEditUndoEvidence.kt              # Undo記録の情報型 + process内holder（#450が将来所有）
└── ui/EditActionsShortcuts.kt           # 3つのSystemShortcut.Factory + page/folder選択dialog
                                         #   （dialogはAlertDialog慣行。OrganizerLockShortcut参照）

src/com/android/launcher3/model/         （platform側。bridgeの最小）
├── DirectEditContract.java              # 新設: snapshot/decision/resultの最小型 + 関数型interface
│                                        #   （純JDK型。Android型・lawnchair型へ依存しない）
└── ModelWriter.java                     # 3つの最小操作を追加（既存メソッドは変更しない）
    ├── moveItemForDirectEdit(...)       #   admission内: 再検証 → ItemInfo変更 → 1回update
    ├── createFolderAndMoveForDirectEdit(...) # admission内: 再検証 → newTransaction()
    │                                    #   （INSERT folder + UPDATE item）→ model更新
    └── removeItemForDirectEdit(...)     #   admission内: 再検証 → 1回delete（即時。Undoは#450）
```

- **seam**: 呼び出し側（popup）とtestは `HomeEditPlanner`（純粋計画）と `ModelWriter` の直接編集操作（書込み）の2つのseamを使う。`ModelWriter` の既存メソッド・既存task classの内部は検証しない。
- **型の境界**: 純粋計画（`HomeEditPlanner`）はAndroid型・DB行型をinterfaceへ漏らさない（`HomeEditSnapshot` は投影）。platform↔forkの境界（`DirectEditContract`）は純JDK型のみで、`src` が `lawnchair` を参照しない構造を保つ。書込みのSQLは `ModelWriter.java`（既に高リスクpath一覧・writer inventory収録済み）に集約し、homeedit配下は `getModelDbController()` 等のDB書込みpatternを持たない。
- **二段階検証の同一関数性**: stage 1（書込み依頼時）は `HomeEditAdapter` が作ったsnapshotに対して `HomeEditPlanner` を実行。stage 2（admission内）は `ModelWriter` がmodel threadで `BgDataModel`/screen順から `DirectEditContract` 経由でsnapshotを組ませ、**同一の** `HomeEditPlanner` 関数へ再実行させる（validatorを関数として渡す構造）。成功planの移動先がstage 1と異なる場合はstaleとして拒否する（無音の再計画はしない。#446のfallback（UpstreamDefault）は「上流が追加を決めたアイコン」の固有緩和であり、明示選択の編集アクションには当てはまらない）。
- **stage-1 snapshotの権威と実行thread（1つに固定）**: 確定時点の編集snapshotは**model executor（`MODEL_EXECUTOR` 経由のmodel task）上で** `HomeEditAdapter` が組む。UI threadや `THREAD_POOL_EXECUTOR` から `BgDataModel` を一括読み取りしない（`BgDataModel` の読み取りはmodel threadの契約に従う。`OrganizerLockShortcut` のTHREAD_POOL例はlock storeの読み取りであり `BgDataModel` の根拠にはならない）。dialogへ出す一覧は表示時点の近似でよい（確定時に再検証するため楽観表示）。stage-1 snapshotの取得からadmissionの間に状態が変わった場合は、stage 2の再検証でstale拒否になる（test oracleで固定する）。
- **「ホームから外す」の構造**: popup確定 → model executorでstage 1 → `ModelWriter.removeItemForDirectEdit` のsubmit → admission内で再検証 → 1回DELETE（即時）+ `removeItem`/verifier → callback。失敗・stale時は無変更でtyped理由を通知する。UndoのUI・窓・逆操作は#450が所有するため本Issueでは作らず、成功時にUndo evidence（削除前の配置）を記録するのみである。`prepareToUndoDelete` / `commitDelete` / `abortDelete` は本Issueでは使わない。
- **NFR-013**: 確定（dialog positive）→ `closeAllOpenViews` → model threadでsnapshot取得・計画・submit。排他なし通常時は1回のupdate/INSERT+UPDATE/DELETEで即反映（bindは既存のmodel仕組み）。defer時はADR-0013契約4のとおりlease解放後の反映であり、spec AC-10のとおり追加の進捗表示は作らない。成功時は「ページへ移動」のみ移動先ページへsnapする（他は現在ページに表示変化が現れる）。

### Data flow（「ページへ移動…」の例）

```text
popup tap → HomeEditExecutor: model executor（MODEL_EXECUTOR経由のmodel task）で
             dialog用のpage/folder一覧を取得（BgDataModel/DB読み取り。表示時点の近似）
        → 一覧dialog表示（楽観表示。確定時に再検証する）
→ 選択確定 → model executorで確定時点のHomeEditSnapshotを取得 → HomeEditPlanner(stage 1, intent)
        → Reject: Toastに理由、終了（無書込み）
        → MoveToPage(plan): ModelWriter.moveItemForDirectEdit(plan, validator, callback)
             → executeOnModelThread（admission。deferされうる）
             → runImpl（admission後）: snapshot再取得 → Planner(stage 2)
                  → PASS: ItemInfo変更 → 1回update → updateItemArrays/verifier → callback(成功, undoEvidence)
                  → FAIL: 無変更 → callback(Reject理由) → Toast
        → callback(UI): 成功ならページsnap + Undo記録、失敗なら理由表示
```

### Alternatives rejected

- **既存 `moveItemInDatabase` / `addItemToDatabase` の再利用**: admission前に `ItemInfo` 変更・ID採番・bind callbackが発生し（上記根拠）、ADR-0013契約4違反。最小操作を同経路へ追加する（契約どおり）。
- **fork側homeedit/writeからcoordinatorを直接呼びDBを書く構造**: `checkItemInfoLocked` / `ModelVerifier` / loadId guardを再実装することになり、ADR-0013 Alternatives「直接編集専用の新しいwriter」（Rejected）と同じ問題。書込みは `ModelWriter` に集約する。
- **「新しいページ」候補**: accessibility経路の空ページ生成はUI先行（`addExtraEmptyScreens` → `commitExtraEmptyScreens` → 書込み）であり、admission内完結構造にそのまま載らない。B2は既存ページで達成可能なため第1段では非対象（spec Non-goals）。
- **「外す」に確認dialogを足す**: Rejected（第1段では）。上流の削除Undo（4秒snackbar）を持つdrag-removeと違い、Undoが#450に先送りされているため保護水準の議論は残るが、ベンチマークB4=1個3操作の前提（メモ§4.1の確定会計）と明示的な2段ジェスチャ（長押し+tap）であること、アプリ自体はドロワーに残るため手動復元が可能であることから、確認dialogは足さない。この暫定リスクはspec Scopeに明記し、#450の統合Undoで解消する。
- **「外す」に上流の遅延commit削除（`prepareToUndoDelete` + 4秒snackbar）を再利用する**: Rejected。snackbar・窓・逆操作UI・寿命は#450が所有する（ADR-0013契約5、Issue #448 Non-goals）ため、#448が窓の値とUIを確定するのは責務超過である。さらにpopup経路にはdrag側のような即時view除去の別経路がなく、遅延commitを採ると削除が窓の間見た目に反映されずNFR-013（即時性）とも矛盾する。よって「外す」はadmission内再検証後の即時1回DELETEとし、Undo evidenceの記録のみを行う。暫定期間の誤操作復元は手動であることをspecに明記した。
- **新規フォルダの作成を `Launcher.addFolder` で行う**: UI（FolderIcon）生成と結合し、UI thread前提。model threadのtransaction内で完結する最小操作として新設する（フォルダiconのbindは既存のmodel reload/bindに従う）。置き先はspec Outcomeどおり「新しいフォルダ選択後の置き先ページ選択 → そのページの空きセル」であり、対象アイテムの現在セルでもhotseatでもない。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `src/com/android/launcher3/model/DirectEditContract.java` | 新設（~120行。純JDK型のsnapshot/decision/result + 関数型interface） | platform↔fork境界の最小契約。`src` が `lawnchair` に依存できないためsrc側に置く |
| `src/com/android/launcher3/model/ModelWriter.java` | 3つの直接編集操作を追加（既存メソッド・既存classの変更なし。既存の`ModelTask`/`updateItemArrays`/`newTransaction`機構を再利用） | ADR-0013契約4「同経路に追加する最小の操作」。SQL書込みの一元化（高リスクpath・inventory済み） |
| `lawnchair/src/app/lawnchair/homeedit/**` | 新設: snapshot/planner/adapter/executor/undo evidence（上記構成） | メモ§4.8のmodule配置。純粋計算と書込み起点の分離。#449がplannerを共有 |
| `lawnchair/src/app/lawnchair/homeedit/ui/EditActionsShortcuts.kt` | 3つの `SystemShortcut.Factory` + dialog（fork側） | 既存拡張点。上流patch surfaceを増やさない |
| `lawnchair/src/app/lawnchair/LawnchairLauncher.kt` | `getSupportedShortcuts()` へ3 Factoryを追加（2〜4行） | 既存のfork拡張点への追加分のみ |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | menu title 3種、dialog title/message、拒否理由、ロック注記 | 既存fork文字列慣行（`organizer_lock_*`）に従う |
| `tests/unit/app/lawnchair/homeedit/**` | planner JVM test（fixture/境界/拒否理由/決定性）、undo evidence test、interface構造test | 純粋計画の最下層oracle（test-audit: 既存laneでは新moduleをカバーできない。同一gateへのfilter拡張で足り、新lane不要） |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/...` | 直接編集の書込みtest（rollback/failure注入/defer-stale/排他/process死。ADR-0013要求テスト表の割付） | shared-writer laneの既存seam（`ModelWriterTransactionReentryTest` 等と同格）。新laneを作らない |
| `.github/workflows/ci.yml` | `organizer-unit-tests` filterへ `app.lawnchair.homeedit.*` 追加、shared-writer laneのclass listへ新規class追加 | 既存gate/laneへの統合（test-audit審査: 低層配置・重複なし・surface_jvm / surface_layout_writeの既存ownershipを維持） |
| `docs/engineering/ci-test-portfolio.md` / `tools/repo-contract/ci_portfolio_map.yml` | 対象classのlane割付の記録更新（lane↔surfaceの対応自体は不変） | quality-strategyのtest審査規約（同じPRで更新） |
| `DESIGN.md` / `CONTEXT.md` / 本spec status | homeedit moduleの位置づけ、直接編集gate行（ADR-0013）の追加、domain language 3語、`implemented` 遷移 | 正本の分担（AC-13） |

**高リスクpath一覧の扱い（#445 plan「homeedit追加方針」の実行）**: 本設計はDB書込みのSQLを `ModelWriter.java`（一覧収録済み・inventory ALLOWLIST済み）に集約し、fork側homeeditは直接DB書込みを持たない。よって高リスクpath一覧へのhomeedit追加は**行わない**。この判断は `validate_writer_inventory.py` が機械的に裏付ける（homeedit配下が将来書込みpatternに一致すればCI failで露見し、その時点で一覧追加とALLOWLIST更新が必須になる）。#445 planの逸脱時規定（「spec/planで実際のpathを確定し、同じ規則（DB書込みpathのみ追加）で扱う」）に基づく決定である。

## Migration and recovery

- schema/rule migration: なし。書く行は上流と同じ標準の `favorites` 構造（フォルダ行含む）。
- failure中のrollback: 複数行アクションは `newTransaction()` のtry-with-resourcesでcommitなしに自動rollback。単一行アクションは1回のupdate/delete/deleteで自ずと原子的。
- release rollback/downgrade: PR revertで閉じる。書き込まれた行は上流drag等が書くのと同じ構造であり、旧版でも読める。作られた空フォルダはspec 24（preserve default）どおり残る。
- backup/restore: 影響なし（標準行のみ）。process死: 1 transactionの前か後のどちらかであり、再起動時のmodel/DBは整合する（testで確認）。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-5 (a)(b)(c)(d) | instrumentation: admission前無変更 / defer後stale（stage-1 snapshot取得後の状態変化もstage 2で検出することを含む）/ coordinator排他 / 1 transaction | `connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...`（shared-writer lane。CI: `organizer-instrumentation-shared-writer-tests`） |
| AC-2/3/4 振る舞い | instrumentation: 移動・フォルダ・削除の書込み結果と周辺行不変 | 同上 |
| AC-6 | JVM planner test（`CreateFolderAndAdd` intentの置き先page/screenを含む入力契約の検証を含む） | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.homeedit.*'`（CI: `organizer-unit-tests`） |
| AC-7 process死 | instrumentation process-death smokeの慣行に従うtest | shared-writer laneに同梱 |
| AC-1/10 | エミュレータでのpopup表示・操作・応答計測 | android-emulator plugin / 実機はowner確認 |
| AC-11 | ベンチマーク§7のagent手順によるエミュレータ実行記録（B2/B3/B4の第1段単独コスト。B3は既存フォルダ経路と新規フォルダ経路（置き先ページ選択含む）を区別して記録） | android-emulator plugin（PR本文へ記録） |
| AC-14 | dialog/shortcut構築のJVM test（文言がリソース由来かつ空でない）+ エミュレータTalkBack読み上げ記録 | JVM test + android-emulator plugin / 実機はowner確認 |
| 全体 | lint/format/build/repo-contract | `./gradlew spotlessCheck`、`./gradlew assembleLawnWithQuickstepGithubDebugDebug`、`python3 tools/repo-contract/validate_repo_contract.py`、`python3 tools/repo-contract/test_validate_repo_contract.py`、`python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` |

test-audit審査の要点（新規test・CI filter変更のため）: (1) 既存test/laneで新module契約をカバーできない（homeeditは未存在のため）、(2) oracleは最下層（純粋計画はJVM、DB/排他/deferは実framework・実DBを要するためinstrumentation）、(3) 新規laneは作らず既存 `organizer-unit-tests` gateとshared-writer laneへ統合、(4) 既存coverageとの重複なし（直接編集の書込みは既存testが対象外）、(5) 恒久gateへの昇格はsurface ownership（surface_jvm / surface_layout_write）の延長でありscheduled sweepでは不足。

## Documentation updates

- [ ] spec status/history（accepted → implemented）
- [ ] CONTEXT.md（domain language 3語）
- [ ] DESIGN.md（§4にhomeedit moduleの位置づけ、§11 gate 2系統へ直接編集（ADR-0013）行を追加。#446 spec rev 6が「将来の#448系PR」に委ねた分）
- [ ] ADR（本PRでは新設しない。ADR-0013/0014/0015を参照するのみ）
- [ ] AGENTS.md（変更なし。verified commandの追加も不要 — 既存commandのみ使用する）
- [ ] `docs/engineering/ci-test-portfolio.md`（test割付の記録）

## Phase 2 record（2026-09-27）

**実装**

| File | 内容 |
|---|---|
| `src/com/android/launcher3/model/DirectEditContract.java` | 新設。snapshot/Row/Decision/Validator/ResultCallback（純JDK型。FAIL_*キー） |
| `src/com/android/launcher3/model/ModelWriter.java` | `moveItemForDirectEdit`（targetContainer=DESKTOPまたはfolderId）/ `createFolderAndMoveForDirectEdit` / `removeItemForDirectEdit` + `DirectEditTask`基底（admission内でstage-2 validator → 変更）。既存メソッド・classの変更はなし（内部fieldの可視性のみDirectEditTask側で自己保持） |
| `lawnchair/src/app/lawnchair/homeedit/{HomeEditModel,HomeEditPlanner,HomeEditAdapter,HomeEditUndoLog,HomeEditExecutor}.kt` | 純粋計画・stage-2 validator・Undo記録・確定flow（stage-1 snapshotはMODEL_EXECUTOR上でModelDbController.db読み取りから構成。QSB予約を合成行として追加） |
| `lawnchair/src/app/lawnchair/homeedit/ui/EditActionsShortcuts.kt` | 3つの`SystemShortcut.Factory` + page/folder選択dialog + ロック注記（`OrganizerLocks.explain`） |
| `LawnchairLauncher.kt` | `getSupportedShortcuts()`へ3 Factory追加 |
| strings (en/ja) | menu 3種、dialog title 3種、拒否理由8種、ロック注記2種 |

**実装で確定した詳細（specの観測可能な振る舞いの範囲内）**

- UI反映: `bindItemsModified`はUI上no-opのため（`BgDataModel.Callbacks`のdefaultは空。上流はdrag層がviewを更新）、成功後にexecutorがaccessibility precedent（`LauncherAccessibilityDelegate.moveToWorkspace`）と同じ「旧view除去 + desktop宛先は`bindItems`で再作成 + 対象ページへsnap」を行う。removeも同様にexecutor側でview除去（`notifyOtherCallbacks`はownerを除外するため）。
- REDUNDANT判定はcontainer=DESKTOPかつscreen一致かつ同一セルのときのみ（別ページの同座標は実移動）。
- 両投影（stage-1 executor / stage-2 ModelWriter）にQSB予約領域（第1画面のrow 0 × `numSearchContainerColumns`）を合成行として追加し、空きセル探索が検索バー領域に落ちないことを同一規則で保証。
- folder子行の値は上流のexternal drag追加（`Folder.onAdd` → organizer batch正規化）に合わせ `screen=0, cellX/Y=-1, rank=N` とし、フォルダopen時の`updateItemLocationsInDatabaseBatch`が正規化する。
- 新規フォルダ行は`Launcher.addFolder`と同じ`new FolderInfo()` + 標準列（title null=UNLABELED）。ID採番はadmission内。

**検証（2026-09-27実施。結果はPR本文へ記録）**

- `./gradlew spotlessCheck` -> PASS
- `./gradlew assembleLawnWithQuickstepGithubDebug` / `assembleLawnWithQuickstepGithubDebugAndroidTest` -> PASS
- unit gate（ci.ymlと同一command）: **1722 tests, 0 failures**（homeedit 25件を含む）
- instrumentation（AVD nunu_qpr2_api36_1 + 実機 Pixel 9a の2端末）: `DirectEditWriteShapeTest` 3件×2端末 PASS、shared-writer lane回帰セット（ModelWriterTransactionReentryTest等9 class + DirectEditWriteShapeTest）**65 tests × 2端末 = 130、0 failures**
- `validate_repo_contract.py`: PASS（refocus-drafts/未追跡ディレクトリの既存2件のみ非該当）、`test_validate_repo_contract.py` PASS、`validate_writer_inventory.py` PASS（homeedit配下にDB書込みpatternなし = 高リスクpath追加不要判断の機械裏付け）、`test_validate_high_risk_evidence.py` OK
- `measure_upstream_patch_surface.py --enforce-baseline`: main上でも同一内容でFAIL（既存baseline未更新分。本PR起因ではない）。本PRのsrc/ deltaは `ModelWriter.java` +283行（counted済み）と `DirectEditContract.java` 新設（counted +1 file）→ PR本文へNFR-010として記録
- AC-11の第1段単独会計（ベンチマーク§7は会計指標であり、実装済みUI flowの操作数から決定的に算出）: B2 = 5個 ×（長押し2 + tap 1 + 対象ページ選択 1）= **20**（baseline 48、58%削減）、B3 = 既存フォルダ経路 4個 × 4 = **16**（baseline 21、新規フォルダ経路は+1ページ選択の5/個）、B4 = 6個 ×（長押し2 + tap 1）= **18**（baseline 25、28%削減）。各flowの操作数はエミュレータ実行で検証済み（下記）。合否はNow-2全体で判定（記録のみ）。
- AC-14のTalkBack記録: TalkBack有効化状態でpopupを開き、3アクションのラベル（「ページへ移動…」「フォルダへ入れる…」「ホームから外す」）がa11y tree上で読み上げ対象であることを確認。証跡 `docs/evidence/448/08-talkback-popup.png` + 自動test（`HomeEditAcceptanceOraclesTest` のstrings検証）。実機での読み上げ確認はowner確認事項。
- エミュレータ実機操作（AC-1/2/3/4/5(a)のユーザー可視検証）: popup表示、Page移動（hotseat→desktop、desktop→desktop、QSB回避cell(0,1)）、フォルダ追加（Google folder rank末尾）、Remove（行削除+view除去、アンインストールなし）。DB照会で各書込みが1 transaction内容どおりであること（単一UPDATE / DELETE）を確認。証跡: `docs/evidence/448/*.png`。実機（Pixel 9a）でのinstrumentation testはPASSしたが、実機でのpopup操作確認はowner確認事項として残す。
- 修正履歴: 実装中に2件の欠陥を検出・修正（null tagのmatcher NPE、notifyOtherCallbacksのowner除外によるremove時view残存）。いずれもエミュレータ実機操作で検出し、回帰をDB+UI両面で再確認済み。
- Phase 2 review round 1（#448コメント5857714252）対応: ①既存フォルダ追加時に`FolderInfo.contents`へサイレント追加（model threadからのlistener通知はFolderIconのview touchでクラッシュするため）し、folder iconのpreview更新はexecutorがUI threadで`FolderIcon.onAdd`により実施（エミュレータで再発確認: Gmail→Google folder追加、view除去・無 crash）。②新規フォルダtaskをDB-first構造へ変更（commit成功までlive ItemInfoを変更しない。失敗時はmodel/DB双方が旧状態）。③intentへaction開始時の`sourcePlacement`preconditionを追加（dialog表示後のtarget移動をstage-1でSTALE拒否）。④Undo evidenceを純粋builder関数化し、作成フォルダ自身の配置も記録。⑤AC-1/7/8/14の受入oracle追加（対象絞り込みpredicate test、process death oracle、純粋module構造test、strings test）。`buildUndoEvidence`・DirectEditWriteShapeTestにproduction経路のtestを追加。

## Execution checklist

- [ ] Current behavior confirmed（上記 Current evidence。実機/エミュレータでpopupが現状3+2項であることの確認を含む）
- [ ] Tests fail for the missing behavior（planner test・書込みtestを先に作り、未実装で失敗することを確認）
- [ ] Minimal implementation completed（DirectEditContract → ModelWriter 3操作 → homeedit module → popup → 文字列）
- [ ] Migration/recovery verified（rollback/process死/排他 test）
- [ ] Full relevant verification completed（Verification表の全行）
- [ ] PR evidence and remaining risks recorded（実機確認はowner確認事項として明記）

## Review / handoff packet（Phase 1時点）

- Issue and all comments: https://github.com/nunu1733/NunuLauncher/issues/448; retrieved at 2026-09-27; state=OPEN; labels=type: feature
- Scope type: feature
- Accepted spec + commit: Phase 1 reviewでacceptedへ進める（本PRのmergeが受入）
- Bug oracle: N/A（feature。振る舞いoracleは本specのBehavior scenarios / AC）
- Plan + revision: specs/448-edit-actions-per-item/plan.md（本書、Revision 3）
- Base SHA: d3b5aba550503c6023224e64452426d8a1b32353（現行main。PR #471でADR-0014収録後。指摘3対応でbranchへmerge済み）
- Head SHA: round 2 review対象 `ed5055dc3f05c8b1c9dc72001ae73f70e6a2549d`。Revision 3のheadは、本欄を含むcommit自体がheadを変えるためIssue #448へのhandoffコメントで記録する（正本）
- Diff: current main...headの実質差分は `specs/448-edit-actions-per-item/spec.md` / `plan.md` の2ファイル（compare URLはpush後に記録）
- Diff boundary: Phase 1はdocs-only（上記2ファイル）。full diffを確認対象とする
- Executed evidence: `python3 tools/repo-contract/validate_repo_contract.py` -> PASS（refocus-drafts/配下の未追跡ローカルdraftに起因する既存3件のみ非該当）; `git diff --stat` 目視
- 次の1手: Revision 3をpushし、ChatGPTへPhase 1再review（round 3）を依頼（結果はIssue #448コメントへ投稿）→ clear後、Phase 2（実装）→ Phase 2 review → PR作成・独立監査・merge
