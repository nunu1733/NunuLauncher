# Implementation Plan: 編集の取り消し（Undo）（#450）

> Issue: #450
> Spec: [spec.md](./spec.md)
> Status: draft（specと同じく本起草時点では未受入）。Revision 2（2026-09-29。Phase 1 re-entry + review round 1の3指摘対応）
> Risk tier: H — 項目単位の逆操作は `src/com/android/launcher3/model/ModelWriter.java`（高リスクpath一覧・writer inventory収録済み）への新規最小操作であり、階層H条件「上流のmodel/loaderへのbridgeを作るまたは変える」に当たる。実装PRは `risk: layout-data` label、独立audit（`docs/assessment/pr-<PR番号>-<slug>.md`）+ `final-status` 成功を要する。編集画面確定のundoは既存organizer復元経路の呼出しのみ（新規書込み経路なし）。

## Current evidence

本planの `path:line` 根拠は2026-09-29に `origin/main` `be7576c30a864574c28b0fd3450b6e767c435ea1` 上で再検証した（Revision 1の根拠commit `c5a7840b88` 以降の差分は #449の実装merge（PR #476）とdocs（#480）のみであることを `git diff --stat` で確認済み）。

**#448で実装済みの直接編集経路（逆操作が載る土台）**

- `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoLog.kt:16-37` — `HomeEditUndoEvidence`（action種別、itemId、old/new配置、span、rank、`createdFolderId` とその配置）。**:37の時点で「外す」の逆INSERTに必要な行内容（itemType、profile、起動先intent、title等）は含まない**（#450の拡張点）。
- `HomeEditUndoLog.kt:39-50` — `HomeEditUndoLog`（process内単一slot、`@Volatile`、`record`/`last` のみ。**世代識別子とcompare-and-consumeは持たない**）。`:57-111` — `buildUndoEvidence`（純粋builder。intent+plan+task報告値からevidenceを組む。`:77-80` に#449の `CreateFolderAt` 分岐追加済み）。
- `lawnchair/src/app/lawnchair/homeedit/HomeEditExecutor.kt:135-143`（confirm: MODEL_EXECUTOR上でstage-1 snapshot→planner）、`:145-206`（submit: stage-2 validator + ResultCallback、成功時 `recordUndoEvidence` :250-276）、`:216-248`（成功後のUI refresh: 旧view除去 + bind + ページsnap。accessibility precedent）、`:284-309`（typed拒否のToast表示）。
- `src/com/android/launcher3/model/DirectEditContract.java:44-51`（FAIL_* keys）、`:53-98`（Row/Snapshotの純JDK型投影）、`:101-131`（Decision/Validator）、`:140-146`（ResultCallback: old配置欄+`createdFolderId`/`createdFolder` がundo evidenceの運搬経路）。
- `src/com/android/launcher3/model/ModelWriter.java:610-638` — 直接編集3操作（`moveItemForDirectEdit` / `createFolderAndMoveForDirectEdit` / `removeItemForDirectEdit`）。`:644-662` — `buildDirectEditSnapshot`（admission内の現在状態投影。QSB予約行の合成込み）。`:669-710` — `DirectEditTask` 基底（admission内でstage-2 validator → 変更。失敗はtyped通知。`runImpl` :686-695）。`:712-784` — `DirectEditMoveTask`（単一UPDATE。desktop以外はcell=-1、span 1x1正規化）。`:786-866` — `DirectEditCreateFolderTask`（`newTransaction()` でINSERT+UPDATEの1 transaction、commit成功後にlive model同期）。`:869-901` — `DirectEditRemoveTask`（1回DELETE。**削除前の行内容はcallbackへ運ばない**。`runAdmitted` :876-900、DELETE :886）。
- `ModelWriter.java:404-446` — 上流の遅延commit削除（`prepareToUndoDelete`/`enqueueDeleteRunnable`/`commitDelete`/`abortDelete`）。fork編集の「外す」は即時commitのためこの機構は使えない（spec Decision）。
- `lawnchair/src/app/lawnchair/homeedit/HomeEditAdapter.kt:12-44`（Snapshot mapper + 拒否key対応）、`:53`（`HomeEditStage2Validator`: 同一planner再実行、stage-1 planと一致でのみproceed）。
- `lawnchair/src/app/lawnchair/homeedit/HomeEditModel.kt:66-105`（intent。`sourcePlacement` precondition :69込み。#449の `CreateFolderAt` :96-102追加済み）、`:108-116`（typed拒否）、`:119-152`（plan型。`Move` :133、`CreateFolder` :137、`RemoveItem` :145）。
- `lawnchair/src/app/lawnchair/homeedit/HomeEditPlanner.kt:19`（`plan`）、`:134`（`hasFreeCell`）、`:198`（`firstFreeCell`。undo plannerの占有/境界検査で再利用する探索helper群）。
- `lawnchair/src/app/lawnchair/homeedit/ui/EditActionsShortcuts.kt:38-43`（対象絞り込み: `ITEM_TYPE_APPLICATION` / `ITEM_TYPE_DEEP_SHORTCUT`、`id != NO_ID`）、`:84-99`（3 Factory）。

**#449で実装済みの編集画面適用完了flow（編集画面entryの書き手。Phase Bの接続先）**

- `lawnchair/src/app/lawnchair/homeedit/ui/HomeEditSurfaceActivity.kt:234-265` — `confirm`（surfaceExecutor上で `EditSurfacePlanBuilder.build` → `access.apply`。`:62-70` のdoc commentどおり、相関reloadの完了待ちがあるためMODEL_EXECUTOR上では実行しない専用スレッドを使う）。`:267-302` — `handleApplyResult`（**`:271-275` の `Applied` 分岐が現在は `finish()` のみ。ここが編集画面entryの書込み+snackbar表示要求の接続点**）。
- `lawnchair/src/app/lawnchair/homeedit/EditSurfacePlanBuilder.kt:59,67` — `build`（`ValidatedLayoutPlan` 構築。`intendedState` を含む）。
- `lawnchair/src/app/lawnchair/homeedit/HomeEditSurfaceAccess.kt:31`（`inspectCapture`）、`:40`（`apply`）。**`recover` accessorは未提供**（Phase Bでadditive追加）。
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt:75`（`ManualOrganizationApplication` interface。`apply` :106、`inspectCapture` :134）、`:242-247`（`ManualOrganizationModule.applicationForEditSurface`。単一インスタンスの共有。#449で追加済み）。
- `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt:136`（`apply`）、`:266-278`（`inspectCapture`。readiness gate+非block mutex、fail-closed null）、**:310（`recover(request: RecoveryRequest): RecoveryResult`。既存のpublic mutation entry。本Issueは新設せずこれを流す）**。
- `lawnchair/src/app/lawnchair/organizer/application/public/Results.kt:47`（`Applied(runId, pointId)`。**post-apply revisionは含まない**）、`:129-149`（`RecoveryResult` と `RecoveryRejection`）。
- `lawnchair/src/app/lawnchair/organizer/application/public/RecoveryRequest.kt:14-17`（pointId + `expectedCurrentRevision`）。
- `lawnchair/src/app/lawnchair/organizer/application/public/ValidatedLayoutPlan.kt:16-25`（`intendedState` はpublic。適用計画からpost-stateを参照できる）。

**revision契約（`expectedCurrentRevision` の正本の根拠。review指摘2）**

- `lawnchair/src/app/lawnchair/organizer/application/revision/RevisionCalculator.kt:34-39` — `revisionOf(state)`（PRE_STATE digest。capture revisionと同種。canonical状態の決定的関数）、`:46-50` — `intendedRevisionOf`（INTENDED_POST_STATE digest。**digest種が異なるためundoの `expectedCurrentRevision` には使わない**）。
- `lawnchair/src/app/lawnchair/organizer/application/protocol/ApplyProtocol.kt:375-381` — `Applied` 返却前のexact-DB検証（`db.layoutState == writeSet.intendedState && db.manifest == writeSet.intendedManifest`。不一致は自動復旧へ）。`:392-399` — VERIFIED登録後のみ `Applied(runId, pointId)` を返す。**したがって `Applied` 受信時点で現在revision == `revisionOf(intendedState)` が経路の契約として成立する。**
- `lawnchair/src/app/lawnchair/organizer/application/protocol/RecoveryProtocol.kt:113-116` — 復元は新規captureのrevisionと `expectedCurrentRevision` を比較し、不一致は `NotRestorable(STALE_REVISION)`・零書込み。
- `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt:124` — capture revisionは `RevisionCalculator.revisionOf(captured.state)`（`revisionOf` と同種の比較が成立する根拠）。
- 結合oracle（AC-5）: `Applied` 直後に新規captureのrevisionが `revisionOf(intendedState)` と等価であること、およびその後の別書込みが `STALE_REVISION`・零書込みのundoになることをtestで固定する。

**availability照合の既存seam族（「外す」undoの照合入力。review指摘3）**

- `lawnchair/src/app/lawnchair/organizer/application/protocol/CandidateResolution.kt:47-59` — `CandidateAvailabilityPort.verifyLaunchable(List<CandidateTarget.AppKey>)`（#228。pre-write boundaryでのavailability再検証の実例。`ApplyProtocol.kt:129-147`）。**AppKey（アプリcomponent）のみでdeep shortcutは対象外**のため、undo側はshortcut availabilityを含む独自の検証入力を持つ（下記Design）。
- `lawnchair/src/app/lawnchair/homeedit/ui/HomeEditSurfaceActivity.kt:341-345`（アプリcomponent availability解決の実例: `launcherApps.getActivityList`）、`:348-360`（deep shortcut availability解決の実例: `getShortcuts` PINNED|MANIFEST）。undoのproduction verifierはこれらと同一の判定を使う。

**snackbar・統計（上流、再利用のみ）**

- `src/com/android/launcher3/views/Snackbar.java:50`（`TIMEOUT_DURATION_MS = 4000`）、`:91`（`show` が既存snackbarを閉じる=単一表示）、`:134-149`（action buttonのclick処理）、`:184-188`（`AccessibilityManagerCompat.getRecommendedTimeoutMillis` によるaccessibility設定準拠の実時間）。
- `src/com/android/launcher3/DropTargetHandler.kt:73-74`（`prepareToUndoDelete`）、`:87-98`（undo tapで `LAUNCHER_UNDO` log。label文言・accessibility announceの慣行）。
- `lawnchair/src/app/lawnchair/LawnchairLauncher.kt:581` — `LawnchairLauncher.instance`（launcher不在時はnull。編集画面activityからlauncher上のsnackbar表示へ引き渡すときに使う。src/の変更をしないためのfork側accessor）。

**排他**

- `src/com/android/launcher3/model/LayoutWriteCoordinator.java:53-58`（`OwnerKind`: ORGANIZER/MODEL_WRITER/GRID_MIGRATION/RESTORE/BACKUP_RESTORE）。`ModelWriter` 経路は `runOrDefer(MODEL_WRITER)` でgate（`ModelWriter.java:570-579`）。undoの逆操作も同じadmissionを使うため追加の排他装置は不要。編集画面undoの `recover` はorganizer復元経路の既存lease契約（`RecoveryProtocol.kt:71-78`）に従う。

**テスト基盤（既存、新規laneは作らない）**

- `tests/unit/app/lawnchair/homeedit/` — `HomeEditPlannerTest` / `HomeEditStage2ValidatorTest` / `HomeEditUndoLogTest` / `HomeEditAcceptanceOraclesTest`（strings検証を含む）/ `SourcePlacementSnapshotTest`。`.github/workflows/ci.yml:360`（`organizer-unit-tests` gateのrun行。`--tests 'app.lawnchair.homeedit.*'` 収録済み）。
- `tests/organizer-instrumentation/com/android/launcher3/DirectEditModelWriterTest.java`（production seam直下の書込みtest）+ `com/android/launcher3/organizer/DirectEditWriteShapeTest.java` + `app.lawnchair.homeedit.EditSurfaceApplyInstrumentationTest`。`.github/workflows/ci.yml:423`（shared-writer lane class list。新規classはここへ追加する）。
- `docs/engineering/editing-burden-benchmark.md` §7（B5のagent実行手順）、§4（snackbar方式は4秒窓の有無を記録する追加規則）。

**前提の確認**

- ADR-0013 accepted（`docs/adr/0013-direct-edit-write-contract.md`。契約5がUndoの正本、要求テスト表の「Undoのfail-closed」行が本Issueの実装PRで満たすべき行）。
- AGENTS.md「ホームレイアウトを扱う安全規約」の直接編集carve-outが「前者のUndo」を明示的に含む。逆操作はsnapshot revision照合・recovery point・相関reload・適用後全体再検証を要求しない。
- ADR-0014 accepted（確定1回=適用1回=復元点1点が#449の実装前提）。
- #448 implemented（spec/plan・PR #472・audit `docs/assessment/pr-472-edit-actions.md`）。evidence型の拡張は「形式の詳細化は#450に委ねる」（spec 448 Scope/AC-9）により本Issueの所有範囲。
- #449 implemented（spec `implemented`・PR #476・audit `docs/assessment/pr-476-edit-surface.md`、merge `29476b10a0`）。Non-goalsで「Undoの実装（#450が所有する）」を明示済み。本planのPhase Bは#449の未実装部分を待たない。

## Design

### Modules and interfaces

```text
app.lawnchair.homeedit/                          （fork側。#450の主戦場）
├── HomeEditUndoRecord.kt                        # 取り消し記録の型と単一slot holder
│                                                #   HomeEditUndoEntry = DirectEditEntry | EditSessionEntry
│                                                #   DirectEditEntry: HomeEditUndoEvidence + 削除前行内容
│                                                #     （UndoRowPayload: 復元列 + availability identity）
│                                                #   EditSessionEntry: RecoveryPointId + expectedCurrentRevision
│                                                #     （= RevisionCalculator.revisionOf(intendedState)。確定完了時に決定）
│                                                #   Slot = (generation: Long, entry)。generationはprocess内単調増加
│                                                #   record(entry) -> HomeEditUndoToken（generation値）
│                                                #   compareAndConsume(token) -> HomeEditUndoEntry?
│                                                #     （同一generationのときだけ取り出し、slotを空にする。
│                                                #      不一致はnullを返し slot無変更=現行recordを消費しない）
│                                                #   置換(fork編集成功)・消費(undo試行終了)・process死で消失
├── HomeEditUndoAvailability.kt                  # availability照合入力（純data）
│                                                #   identity: App(component, userSerial) | Shortcut(package, shortcutId, userSerial)
│                                                #   state: AVAILABLE | UNAVAILABLE | UNKNOWN（検証失敗はUNKNOWN=fail-closed）
├── HomeEditUndoPlanner.kt                       # 純粋照合関数: (HomeEditSnapshot, HomeEditUndoEntry,
│                                                #   availability=UndoAvailability?) -> UndoPlan.Success(逆操作plan) | Reject(typed理由)
│                                                #   照合: 対象がevidenceのnew配置にいる / 復帰先が空き・範囲内 /
│                                                #   作成フォルダが作ったまま(子が対象のみ・配置不変) /
│                                                #   remove-undoはavailabilityがAVAILABLEであること
│                                                #   （UNAVAILABLE/UNKNOWNは UNDO_ITEM_UNAVAILABLE で拒否）
│                                                #   占有・境界は HomeEditPlanner のhelperを再利用
├── HomeEditUndoVerifier.kt                      # availability port（fork側interface）+ production実装
│                                                #   App: launcherApps.getActivityList(component, user) 非空
│                                                #   Shortcut: getShortcuts(PINNED|MANIFEST) 非空
│                                                #   （#228のavailability検証と編集画面icon解決と同一判定。
│                                                #    binder失敗・security例外はUNKNOWN。stage-1/stage-2で同一instance）
├── HomeEditUndoExecutor.kt                      # snackbar tap → 世代照合（compareAndConsume。不一致は無操作で終了）
│                                                #   → DirectEditEntry: MODEL_EXECUTOR上でsnapshot取得・availability取得・
│                                                #     stage-1照合 → ModelWriter逆操作のsubmit(stage-2 validator+verifier込み)
│                                                #   → EditSessionEntry: 専用スレッドで organizer recover(RecoveryRequest)
│                                                #     （HomeEditSurfaceActivity の surfaceExecutor と同理由。
│                                                #      相関reload待ちをMODEL_EXECUTOR上で行わない）
│                                                #   成功: UI refresh + LAUNCHER_UNDO log / 失敗: typed Toast（いずれもrecord消費済み）
└── ui/HomeEditUndoSnackbar.kt                   # Snackbar.show(launcher, label, R.string.undo,
                                                 #   onActionClicked = { executor.start(token) })
                                                 #   popup経路: launcher上で直接show
                                                 #   編集画面経路: LawnchairLauncher.instance 経由でlauncherへ引き渡し
                                                 #   （instanceがnullなら表示しない。recordのみ残る）

src/com/android/launcher3/model/                （platform側。bridgeの最小）
├── DirectEditContract.java                      # 拡張（純JDK型のまま。既存型は変更しない）:
│                                                #   UndoRowPayload — 「外す」の削除前行の復元列投影
│                                                #     （itemType/container/screen/cell/span/rank/userSerial/intent/
│                                                #      title/options等、再INSERTに必要な列。availability identityを含む）
│                                                #   AvailabilityVerifier — UndoRowPayload -> int(AVAILABLE/UNAVAILABLE/UNKNOWN)
│                                                #     （fork側から注入。ModelWriter自身はLauncherAppsを呼ばない。
│                                                #      Validator注入と同じパターン）
│                                                #   FAIL_UNDO_* keys（UNDO_STALE/UNDO_NO_SPACE/UNDO_FOLDER_CHANGED/
│                                                #     UNDO_ITEM_UNAVAILABLE/UNDO_WRITE_FAILED 相当）
└── ModelWriter.java                             # 逆操作3種を追加（DirectEditTaskと同じ構造。既存メソッド・classは
                                                 #   変更しない。`DirectEditRemoveTask` のみ削除前行のpayload captureを追加）
    ├── restorePlacementForDirectEdit(...)       #   admission内: 再照合 → 明示配置への1回UPDATE
    │                                            #   （containerは DESKTOP/HOTSEAT/folderId いずれも可）
    ├── restoreRemovedItemForDirectEdit(...)     #   admission内: availability再照合（注入verifier）→ 再照合
    │                                            #   → 1回INSERT（行内容はpayload。_idはitemIdを再利用しない新規採番でも
    │                                            #   元の_idでもない第三の値は作らない。実装時にmodel/DB整合で確定）
    └── undoCreateFolderForDirectEdit(...)       #   admission内: 再照合 → newTransaction()
                                                 #   （子の逆UPDATE + フォルダ行DELETE）→ model同期
```

- **seam**: 呼び出し側（snackbar action）とtestは `HomeEditUndoPlanner`（純粋照合）と `ModelWriter` の逆操作（書込み）の2つのseamを使う。`ModelWriter` 内部の既存task classは検証しない（spec 448と同じ規約）。availabilityは `HomeEditUndoAvailability`（純data）+ `HomeEditUndoVerifier`（port）+ `DirectEditContract.AvailabilityVerifier`（stage-2注入）で運び、plannerは純粋関数のまま Android型・DB行型を受けない。
- **型の境界**: `HomeEditUndoPlanner` はAndroid型・DB行型をinterfaceへ漏らさない（`HomeEditSnapshot` 投影）。`DirectEditContract` は純JDK型のまま。SQLは `ModelWriter.java` に集約し、homeedit配下はDB書込みpatternを持たない（`validate_writer_inventory.py` のbackstopが自動検証）。
- **二段階照合の同一関数性**: stage-1（submit時、MODEL_EXECUTOR上のsnapshot+availability）とstage-2（admission内、`buildDirectEditSnapshot`+注入verifierのavailability）で**同一の** `HomeEditUndoPlanner` 関数を通す。構造は#448の `HomeEditStage2Validator` と同じ（validator関数を渡す）。stage-1と異なる結果・拒否は書かない。availabilityは同一のproduction `HomeEditUndoVerifier` instanceを使い、stage-1/stage-2で同一判定を保証する（review指摘3の「二段階で同一判定」の実装）。
- **記録の単一slotと世代**: `HomeEditUndoLog` を `HomeEditUndoRecord` へ発展させる（既存 `HomeEditUndoLogTest` を移植・拡張）。slotは `AtomicReference<Slot>`（`Slot(generation, entry)`）で置換・消費をatomicに行い、`compareAndConsume(token)` は同一generationのときのみ取り出す。record/consumeはmodel thread(callback)、UI thread(snackbar tap)、編集画面activity threadから挟まるため、可視性と原子的な置換・消費はこの1箇所に集約する（review指摘1の実装）。
- **編集画面entryのrevision決定（review指摘2の実装）**: `handleApplyResult` の `Applied` 分岐で `HomeEditUndoRecord.record(EditSessionEntry(result.pointId, RevisionCalculator.revisionOf((built as EditSurfaceApplyPlan.Ready).plan.intendedState)))` を行う。post-hoc captureは行わない。根拠の契約はCurrent evidenceのrevision契約節（`Applied` ⇔ exact-DB検証）。**_threading_: EditSession undoの `recover` は相関reloadの完了待ちを含むため、`HomeEditSurfaceActivity.surfaceExecutor`（:70）と同じ専用スレッドで実行する（MODEL_EXECUTOR上で待つとLoaderTaskが永久に実行されないデッドロックの再発を避ける）。
- **Snackbar表示の引き渡し**: popup経路（`HomeEditExecutor` 成功callback。launcher上）はそのまま `Snackbar.show`。編集画面経路はactivity上ではなくlauncher上に表示する必要があるため、`LawnchairLauncher.instance`（:581）へtoken+label付きで表示を依頼する（instance nullなら表示しない）。launcher側のhookはsrc/に触れない（`HomeEditUndoSnackbar` がlauncherのDragLayerへattachする既存 `Snackbar.show` 慣行のまま）。

### Data flow（「ページへ移動…」のundoの例）

```text
編集成功callback（model thread）: recordUndoEvidence → token = HomeEditUndoRecord.record(entry)
  → mainHandler: HomeEditUndoSnackbar.show(launcher, label, token)
undo tap（UI thread）
  → HomeEditUndoExecutor.start(token)
      → current = compareAndConsume(token)
          → null（世代不一致 or 空）: 終了（零書込み・無表示・現行recordは無変更）
          → entry: 続行
  → MODEL_EXECUTOR: buildSnapshot()（spec 448と同じ投影）+ availability取得（production verifier）
  → HomeEditUndoPlanner.verify(snapshot, entry, availability)
      → Reject: mainHandler: typed Toast、終了（無書込み）
      → UndoPlan成功: ModelWriter.restorePlacementForDirectEdit(itemId, 旧container/screen/cell/rank,
                    stage2validator(+verifier), callback)
           → executeOnModelThread（admission。ORGANIZER lease中はdefer）
           → runImpl: buildDirectEditSnapshot + availability再取得（同一verifier）→ stage-2再照合
                → PASS: 1回UPDATE → model同期 → callback(成功)
                → FAIL: 無変更 → callback(typed失敗)
  → callback: 成功なら UI refresh（旧view除去+再bind+元ページsnap）+ LAUNCHER_UNDO log
              失敗なら typed Toast（いずれもrecordはcompareAndConsume済み）
```

編集画面entry（Phase B）: 確定完了（`handleApplyResult` の `Applied` 分岐）: `token = record(EditSessionEntry(pointId, revisionOf(intendedState)))` → `LawnchairLauncher.instance` へsnackbar表示依頼 → `finish()`。undo tap → `compareAndConsume(token)`（不一致は無操作）→ 専用スレッドで `HomeEditSurfaceAccess.recover(RecoveryRequest(pointId, expectedRevision))` → `Restored`: 復元経路の相関reloadに任せる / `NotRestorable`・`WriterBusy`・`RestoreFailed`: typed Toast（`RecoveryResult` mapper。純粋関数、JVM test対象）+ recordは消費済み。

### Alternatives rejected

- **「外す」のundoに上流の遅延commit（`prepareToUndoDelete`+`abortDelete`）を再利用**: Rejected。#448の「外す」は即時commit済みDELETEであり、遅延commitはdrop時（commit前）にしか働かない。snackbar・窓・逆操作UIは#450所有（ADR-0013契約5）で、NFR-013の即時性とも矛盾する（spec 448 planの同判断を踏襲）。
- **逆操作をorganizerの復元経路で実装する**: Rejected（ADR-0013 Alternativesと同じ）。1操作の戻しにpre-state manifest作成・保存は過大。単一slotの行内容記録で足りる。
- **「外す」undoの行再構築をIconCache/package解決で行う**: Rejected。deep shortcutのintent列・title等はDB行にしかなく、削除後に復元不能。削除時（admission内、DELETE直前）に完全な行投影をcaptureしてevidenceへ載せる。
- **undo tap時に対象を再探索して「近い位置」へ戻す**: Rejected。fail-closed（メモ§4.2条件5）に反する。復帰先が埋まればtyped失敗とし、代替配置はしない。
- **取り消し記録の永続化（preference/新規store）**: Rejected（第1版）。寿命はメモ§10/§11 B-3で確定済み（process内で次の編集まで）。DB migration・backup契約を避ける。
- **`moveItemForDirectEdit` の流用**: undoはHOTSEAT戻し・folder→元配置など既存moveの前提（desktop/folder宛て・span正規化）と異なるため、明示配置へ戻す専用の最小操作を追加する（ADR-0013契約4「同経路に追加する最小の操作」）。
- **apply完了後に再captureして `expectedCurrentRevision` を記録する（post-hoc capture）**: Rejected（review指摘2）。apply完了とcaptureの間の別書込み後のrevisionを記録しかねず、「確定直後の状態からのずれを拒否する」目的に反する。`Applied` はexact-DB検証（`ApplyProtocol.kt:375-381`）を経るため、適用計画からの `revisionOf(intendedState)` 決定と等価であり、競合を持たない。
- **世代識別子なしの単一slot消費（Revision 1草案）**: Rejected（review指摘1）。snackbarの置換がmain threadへpostされる構造では「旧snackbar表示中に新recordへ置換済み・新snackbar未表示」の窓が必ず存在し、旧snackbarのtapが新編集を消費しうる。token閉じ込め+compareAndConsumeで構造的に排除する。
- **availability照合をUI層（stage-1）だけに置く**: Rejected（review指摘3）。defer中のアンインストール・shortcut無効化をadmission内で再照合しないと、消えた起動先の古いrowを再INSERTしうる（ADR-0013契約2の二段階検証にavailabilityを含める）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoRecord.kt` | 新設（`HomeEditUndoLog` からの発展・置換。evidence拡張+EditSessionEntry+世代付きcompare-and-consume） | 記録の形式・寿命は#450所有（spec 448 AC-9の委任） |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoAvailability.kt` | 新設（純dataのavailability入力）+ `HomeEditUndoVerifier.kt`（port+production実装。LauncherApps） | stage-1/stage-2同一判定のavailability照合（review指摘3） |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoPlanner.kt` | 新設（純粋照合関数。availability入力込み。`HomeEditPlanner` の占有/境界helper再利用） | AGENTS.mdテスト規約（計画moduleはinterface経由・副作用なし） |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoExecutor.kt` | 新設（undo flow。世代照合・snackbar出力含む。EditSessionは専用スレッドでrecover） | spec 448の `HomeEditExecutor` と対称な構成 |
| `lawnchair/src/app/lawnchair/homeedit/ui/HomeEditUndoSnackbar.kt` | 新設（snackbar表示。token閉じ込め。launcher引き渡し） | 入口はsnackbarのみ（spec Scope）。src/に触れない |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditExecutor.kt` | 成功callback末尾にsnackbar表示とrecord書込みの差し替え（`HomeEditUndoLog`→`HomeEditUndoRecord`） | 既存flowの最小変更 |
| `lawnchair/src/app/lawnchair/homeedit/ui/HomeEditSurfaceActivity.kt` | `Applied` 分岐へentry書込み+snackbar表示依頼を追加（既存分岐は変更しない） | #449接続点（spec Scope確定済み） |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditSurfaceAccess.kt` + `organizer/ui/ManualOrganizationRun.kt` | `recover` accessorのadditive追加（interface+production impl。`LayoutApplicationModule.recover` への委譲のみ） | undo tapの復元経路。organizer public契約の変更なし |
| `src/com/android/launcher3/model/DirectEditContract.java` | `UndoRowPayload`・`AvailabilityVerifier`・FAIL_UNDO_* keysの追加（既存型・既存メソッドは変更しない） | 「外す」逆INSERTの行内容運搬とstage-2 availability注入 |
| `src/com/android/launcher3/model/ModelWriter.java` | 逆操作3種を追加（`DirectEditTask` 構造に準拠。既存メソッド・classは変更しない。`DirectEditRemoveTask` は削除前行をpayloadへcaptureするよう拡張） | ADR-0013契約4。SQLの一元化（高リスクpath・inventory収録済みfile） |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | snackbar label、undo失敗理由（`homeedit_undo_error_*`）、a11y announce | fork文字列慣行 |
| `tests/unit/app/lawnchair/homeedit/**` | UndoPlanner test（種別×照合×境界×availability×決定性）、UndoRecord test（置換・消費・世代不一致compare-and-consume）、evidence拡張のbuilder test、RecoveryResult mapper test、strings test拡張 | 純粋層の最下層oracle。gate収録済みfilterで自動実行 |
| `tests/organizer-instrumentation/com/android/launcher3/DirectEditUndoModelWriterTest.java`（新規）等 | 逆操作の書込みtest（round-trip、行内容忠実度、transaction失敗注入、defer/stale、occupancy拒否、**availability失敗注入（package消失・shortcut消失・verifier失敗→零書込み）**、process死） | shared-writer laneの既存seam。class listへの追加のみ |
| `tests/organizer-instrumentation` 配下の#449結合test（`EditSurfaceApplyInstrumentationTest` 隣接）または同lane新規class | 編集画面undoの結合oracle（`Applied`→`revisionOf(intendedState)`一致→`Restored`、apply後別書込み→`STALE_REVISION`零書込み） | AC-5の相手側が実装済みのため結合で検証 |
| `.github/workflows/ci.yml` | shared-writer lane class listへ新規test class追加（ci.yml:423。必要に応じpath routing） | 既存laneへの統合のみ（新規lane不作成） |
| `CONTEXT.md` / `DESIGN.md` / `docs/product/requirements.md` | domain language 3語、homeedit undo構成の追記（必要最小限）、FR-020 status | 正本の分担（spec AC-10） |

**高リスクpath一覧の扱い**: 逆操作のSQLは既に一覧収録済みの `ModelWriter.java` へ集約するため、一覧への新規追加は不要（spec 448と同じ判断）。`validate_writer_inventory.py` がhomeedit配下のDB書込みpattern不在を機械検証する。実装PRは `risk: layout-data` + 独立audit + `final-status`（階層H）。

## Migration and recovery

- schema/DB migration: なし。取り消し記録はprocess内のみ。
- failure中のrollback: 複数行の逆操作（undoCreateFolder）は `newTransaction()` のtry-with-resourcesでcommitなし自動rollback。単一行は1回のUPDATE/INSERTで自ずと原子的。`UpdateItemsRunnable` の失敗握りつぶし経路は使わない（ADR-0013契約3）。
- release rollback/downgrade: PR revertで閉じる。書かれた行は上流と同じ標準構造であり旧版でも読める。process内記録はrevert後自然に無効（snackbarが出ないだけ）。
- process死: 取り消し記録・snackbarとも消失。1 transactionの前後でのみDBが変わるため再起動時のmodel/DBは整合（ADR-0013要求テスト表process死行と同じ検証）。
- 編集画面確定の復元点: 24時間・最新3点の既存保持契約のまま（変更しない）。undoが `NotRestorable` を受けたときはspecどおりtyped表示で終了する。

## Failure handling（実装観点）

- stage-1照合拒否・stage-2再照合拒否・availability拒否（UNAVAILABLE/UNKNOWN）・書込み例外: いずれも無変更でtyped失敗（spec 448の `reportFailureKey` と同じToast経路）。`DirectEditContract` のFAIL_UNDO_* keysで運ぶ。
- **世代不一致（stale token）**: `compareAndConsume` がnullを返した時点で終了。零書込み・無消費・無表示（spec Scenario「置換前のsnackbarのtapは次の編集を取り消さない」）。二重tapも同一tokenの2度目のconsumeなので同様に無操作。
- undo tapの二重実行防止: snackbar actionは1回で閉じる（`Snackbar` の既存動作）に加え、recordの消費を世代照合でatomicに行うため、2度目のstartは無操作になる。
- ORGANIZER lease中のundo: defer後のstage-2再照合（配置+availability）で前提が崩れていればtyped失敗（spec 448 AC-5(c)と同一構造のtestで検証）。
- 編集画面entryの失敗: `RecoveryResult` の各variantをtyped表示へ対応づけるmapper（純粋関数、JVM test対象）。`RestoreFailed` 等の不確実な結果は `authoritativeState` に従い「無変更」と断定しないfail-closed表示（spec 449 AC-16と同じ語彙）。
- 編集画面entryで `LawnchairLauncher.instance` がnull（launcher不在）: snackbarを出さず終了。recordは次の編集まで残るが入口がないため実効的なundoは発生しない（spec Scopeのとおり）。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-2（fail-closed。availability込み） | JVM: UndoPlanner typed拒否群（availability含む）+ instrumentation: 状態ずれ・占有・フォルダ変化・package消失・shortcut消失・verifier失敗の注入 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.homeedit.*'` / shared-writer lane |
| AC-3（1 transaction） | instrumentation: undoCreateFolderの2行目失敗注入→全rollback、model/DB一致 | `connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...DirectEditUndoModelWriterTest` |
| AC-4（排他/defer） | instrumentation: ORGANIZER lease中のundo defer→解放後stage-2（`DirectEditModelWriterTest` のdefer-stale testと同seam） | 同上 |
| AC-5（編集画面undo） | 結合test: `Applied`→`revisionOf(intendedState)`一致→`Restored`、apply後別書込み→`STALE_REVISION`零書込み、evict/復元済み/WriterBusy表示 | shared-writer lane（#449結合test隣接）+ エミュレータ操作記録 |
| AC-11（世代紐付け） | JVM: record holderの世代不一致compare-and-consume（置換・無消費・現行undo可能）+ executor層のstale token無操作oracle | organizer-unit-tests gate |
| AC-1/8/9（UI・実測・a11y） | エミュレータ操作記録（4アクション+確定undo、TalkBack読み上げ、accessibility timeout）、B5 §7手順の記録（4秒窓の有無を含む） | android-emulator plugin。実機はowner確認 |
| AC-6（寿命） | JVM: UndoRecord置換・消費 + instrumentation process-death smokeの慣行 | 上記lane |
| AC-7（純粋関数） | JVM: UndoPlanner fixture/境界/決定性 | organizer-unit-tests gate（ci.yml:360） |
| AC-10（文書） | `validate_repo_contract.py` + diff確認 | `python3 tools/repo-contract/validate_repo_contract.py` |
| 全体 | lint/format/build | `./gradlew spotlessCheck`、`./gradlew assembleLawnWithQuickstepGithubDebug`、`measure_upstream_patch_surface.py --target HEAD --enforce-baseline`（src/ deltaはModelWriter.java拡張とDirectEditContract.java拡張のみの見込み。NFR-010としてPR本文へ記録） |

## Incremental implementation order

1. **Phase A-1 記録と契約の拡張**: `HomeEditUndoRecord`（世代付きslot+EditSessionEntry+消費）+ `HomeEditUndoAvailability`/`HomeEditUndoVerifier` + `DirectEditContract.UndoRowPayload`/`AvailabilityVerifier`/FAIL_UNDO_* + `DirectEditRemoveTask` の行capture。JVM test（builder・record・世代oracle）。
2. **Phase A-2 純粋照合**: `HomeEditUndoPlanner` + JVM test群（AC-7/AC-11の純粋層）。
3. **Phase A-3 逆操作の書込み**: `ModelWriter` 逆操作3種 + `DirectEditUndoModelWriterTest`（AC-2/3/4の書込み面。shared-writer lane class listへ追加）。
4. **Phase A-4 UI**: snackbar表示（`HomeEditUndoSnackbar`）・`HomeEditUndoExecutor`・strings（en/ja）・a11y announce・LAUNCHER_UNDO log。エミュレータ操作記録（AC-1/8/9）。
5. **Phase B 編集画面接続**: `HomeEditSurfaceAccess`/`ManualOrganizationApplication` への `recover` accessor追加、`handleApplyResult` `Applied` 分岐のentry書込み+表示依頼、`RecoveryResult` mapper、結合test（AC-5）。**#449はimplementedのためPhase A後すぐ着手できる（ブロッカなし）。**
6. **文書**: CONTEXT/DESIGN/requirements FR-020 status（AC-10）。実装PRに独立audit（`docs/assessment/pr-<n>-edit-undo.md`）。

## Dependencies and blockers

- **#449（implemented。PR #476）**: Phase Bの相手側は実装済み。本planの接続契約（`handleApplyResult` `Applied` 分岐でのentry書込み、`recover` accessorのadditive追加、`revisionOf(intendedState)` の正本）は現行実装のseam上で確定済み（Current evidence参照）。
- **ADR-0013要求テスト表「Undoのfail-closed」行**: 本実装PRで満たす（spec 448 AC-7が委任済み）。
- **#441（closed）**: B5測定の手順・記録規則は確定済み（§4/§7）。

## Risks

- `ModelWriter.java` への追加（高リスクpath）。既存メソッド・classを変更せず追加のみとし、`DirectEditTask` 構造を踏襲する（`DirectEditRemoveTask` のpayload captureは既存classへの最小拡張であり、削除の振る舞いは変えない）。独立audit + `final-status`。
- 「外す」undoの行再構築の忠実度（intent/title/profile/lock値）。削除時captureをadmission内（DELETE直前）に行うことで、stage-1〜stage-2間の変化を構造的に排除する。行内容の等価性はinstrumentation testで検証。
- availability照合のbinder呼出し（`LauncherApps`）をadmission内（model thread）で行うこと。上流のmodel threadでの `LauncherAppsCompat` 利用惯例と同じであり、副作用のないreadである（ADR-0013契約2の「副作用のない検証」を満たす）。verifier失敗はUNKNOWN→fail-closedで零書込み。
- `revisionOf(intendedState)` と復元時の新規capture revisionの等価性は「canonical encodingが状態の決定的関数」に依存する。`Applied` のexact-DB検証（構造一致）が前提であるため、等価性を結合test（AC-5）で固定する。将来のcanonical encoding変更時は本契約の再検証が必要（spec 13のrevision契約に従う）。
- HOTSEAT戻しの列慣行（cellX/rank/screenId解釈）は上流の書込み実例と突き合わせて実装時に確定する（未検証領域参照）。
- Snackbar単一表示による連続編集時の前undo消失は仕様（FR-020「直前のみ」）であり、実装の複雑化（queue）で回避しない。世代不一致の競合はtoken閉じ込めで構造的に排除する。

## Explicitly unverified areas

- HOTSEAT宛て逆UPDATEの正確な列値（screenId/cellX/rankの使われ方）は実装時に `BgDataModel`/上流hotseat書込みの実例から確認する必要がある（本plan起草時点では未確認）。
- `restoreRemovedItemForDirectEdit` の再INSERTの `_id` 扱い（元の `_id` の再利用可否は `BgDataModel`/`ModelDbController` の挙動次第）は実装時に確認し、model/DB整合のtestで固定する。
- `UndoRowPayload` の列集合（再INSERTに必要なfavorites列の確定リスト）は、実装時に `ContentWriter` の書込み列と突き合わせて確定する（忠実度testで検証）。
- 編集画面確定後のlauncher再開とsnackbar表示のlifecycle timing（`LawnchairLauncher.instance` 取得とDragLayer attachの順序）は実装時にエミュレータで確認する。
- snackbar labelを編集種別ごとに分けた場合のja文言幅（`Snackbar` の2行fallback有無）は実装時に確認する。
- B5の実測値（目標1操作の合否記録）と実機owner確認は実装PR時点の証拠である（本planでは算出しない）。

## Execution checklist

- [ ] Current behavior confirmed（上記Current evidence。snackbar未実装・undo未実装の現状確認を含む。#449実装後の行番号再検証済み）
- [ ] Tests fail for the missing behavior（UndoPlanner test・record世代oracle・逆操作testを先に作り、未実装で失敗することを確認）
- [ ] Minimal implementation completed（Phase A-1→A-4→Bの順）
- [ ] Migration/recovery verified（rollback/排他/process死 test）
- [ ] Full relevant verification completed（Verification表の全行）
- [ ] PR evidence and remaining risks recorded（risk: layout-data、独立audit、final-status、B5記録、実機owner確認の明記）
