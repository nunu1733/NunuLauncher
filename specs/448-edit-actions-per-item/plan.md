# Implementation Plan: 項目単位の編集アクション（#448 第1段）

> Issue: #448
> Spec: [spec.md](./spec.md)
> Status: draft
> Risk tier: H — Issue #448（メモ§4.7）が本機能を階層Hへ割り当て済み。現行workflowの階層H条件「Launcher DBへの新しい書込み経路を作る」「上流のmodel/loaderへのbridgeを作るまたは変える」に当たる（`ModelWriter.java` への最小操作追加 + fork側homeedit moduleの新設）。手順は現行どおり: accepted spec + plan.md、Execution and approval contract、`risk: layout-data` labelによる高リスク独立エビデンス（`final-status` + `docs/assessment/pr-<PR番号>-<slug>.md` の独立audit）。
> Phase 1（本書の初版）: spec + planの起草とreviewを追跡する。Phase 2（実装）は同じbranch/PRで行い、本planのRevisionで追跡する。

## Current evidence

本planの `path:line` 根拠は2026-09-27にmain `670527526490bfda5ec0862421ac3da790fafaa2` 上で検証した。

**popupとsystem shortcut（fork側拡張点）**

- `lawnchair/src/app/lawnchair/LawnchairLauncher.kt:286-296` — `getSupportedShortcuts()` が `Stream.concat` でfork側shortcut（UNINSTALL / CUSTOMIZE / PAUSE_APPS / `OrganizerLockShortcut.PLACEMENT_LOCK`）を追加する。3アクションはここへ追加分を加えるだけで、上流ファイルの変更は不要。
- `lawnchair/src/app/lawnchair/ui/popup/OrganizerLockShortcut.kt:39-50` — `SystemShortcut.Factory` としての対象絞り込み（`ITEM_TYPE_APPLICATION` / `ITEM_TYPE_DEEP_SHORTCUT`、`itemInfo.id == ItemInfo.NO_ID` でnull）と、`:128-145` の `AlertDialog` 確認dialog、`:70-75` のmodel thread（`Executors.THREAD_POOL_EXECUTOR`）→ mainHandler → Toastの非同期パターン。本Issueのdialog/非同期慣行の実例。
- `src/com/android/launcher3/folder/Folder.java:380` — フォルダ内アイテムのlong-clickは `mLauncherDelegate.beginDragShared(v, this, options)`（popupではなくdrag）。フォルダ内アイテムにpopupが出ない構造の根拠。
- `src/com/android/launcher3/popup/SystemShortcut.java:337` — `UNINSTALL_APP`（「外す」とは別物の根拠）。

**model writerとadmission（書込み経路の中心）**

- `src/com/android/launcher3/model/ModelWriter.java:190-220` — `moveItemInDatabase`。admission前に `updateItemInfoProps`（`:192`）で `ItemInfo` を変更し、desktop移入時はspanを1x1へ正規化（Issue #269、`:199-204`）、`enqueueDeleteRunnable(new UpdateItemRunnable(...))`（`:207`）。
- `ModelWriter.java:252-267` — `modifyItemInDatabase`。同様にadmission前にprops + spanを変更（`:254-256`）。
- `ModelWriter.java:290-314` — `addItemToDatabase`。admission前に `generateNewItemId()`（`:294`）とbindItems callback（`:295`）。この既存構造を直接編集は踏まない（ADR-0013契約4）。
- `ModelWriter.java:335-352` — `deleteItemsFromDatabase`。notifyDelete（UI除去）→ 1行delete + `removeItem` + verifier。
- `ModelWriter.java:400-442` — `prepareToUndoDelete` / `enqueueDeleteRunnable`（pending時は `mDeleteRunnables` へqueue） / `commitDelete` / `abortDelete`（無書込み + `forceReload`）。上流の削除Undoの機構。「ホームから外す」はこれを再利用する。
- `ModelWriter.java:459-476` — `UpdateItemRunnable.runImpl`。単一行の1回update（自ずと原子的）。`:478-503` — `UpdateItemsRunnable`。複数行のみ `SQLiteTransaction` だが失敗を握りつぶす（`:499-501`）。直接編集の複数行アクションはこの経路を使わず `newTransaction()` を自前で使う（ADR-0013契約3）。
- `ModelWriter.java:505-555` — `UpdateItemBaseRunnable.updateItemArrays`。`checkItemInfoLocked` + workspaceItems整理 + `ModelVerifier`。直接編集のtaskもこれを再利用する。
- `ModelWriter.java:557-582` — `ModelTask`。`run()` はloadId変化でskip、`executeOnModelThread()` が `LayoutWriteCoordinator.runOrDefer(MODEL_WRITER, token=0, exact=false, ...)` でgate。
- `src/com/android/launcher3/model/LayoutWriteCoordinator.java:460-497` — `runOrDefer`。ORGANIZER / restore-family lease中のtokenless workはFIFOへdefer（`:525-527`）。
- `src/com/android/launcher3/model/ModelDbController.java:298-303` — `newTransaction()`（close/commit/rollback、coordinator leaseをtransaction closeまで保持）。

**フォルダ・移動先・フォルダ作成の上流実例**

- `src/com/android/launcher3/model/data/FolderInfo.java:125-143` — `add(item, rank, animate)`。rank管理と `Folder.willAccept` 検証（model内list操作。DB書込みをしない）。
- `src/com/android/launcher3/Workspace.java:2117-2175` — `createUserFolderIfNecessary`。drag経路のフォルダ作成は `Launcher.addFolder`（`src/com/android/launcher3/Launcher.java:2043-2061`）で `addItemToDatabase(folderInfo, ...)` → FolderIcon生成。popup経由では相手セルがないため、本Issueは対象セルに1x1フォルダを作る別の最小操作を追加する。
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
- ADR-0014（#447）は未受入。ADR-0014草案（`refocus-drafts/adr/0014-edit-surface.md`）は第1段popupを `SystemShortcut.Factory` に載せることで一致しており、上流変更の追加bridgeを要求しない。specのScope「ADR-0014との照合」のとおり、#447受入時に再照合する。

## Design

### Modules and interfaces

```text
app.lawnchair.homeedit/                  （fork側。新設）
├── HomeEditSnapshot.kt                  # 編集snapshotの投影型（純data）
├── HomeEditPlanner.kt                   # 純粋計画関数（intent → closed result）
│                                        #   成功: MoveToPage / AddToFolder / CreateFolderAndAdd / Remove
│                                        #   失敗: Reject(typed理由)
├── HomeEditAdapter.kt                   # model/DeviceProfile → HomeEditSnapshot の投影
│                                        #   + DirectEditContract のvalidator実装（Plannerへ委譲）
├── HomeEditExecutor.kt                  # 確定UI flow: snapshot取得(model thread) → dialog
│                                        #   → 計画 → ModelWriter直接編集操作のsubmit → 結果通知
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
    └── removeItemForDirectEdit(...)     #   prepareToUndoDelete窓 + admission内: 再検証 → 1回delete
```

- **seam**: 呼び出し側（popup）とtestは `HomeEditPlanner`（純粋計画）と `ModelWriter` の直接編集操作（書込み）の2つのseamを使う。`ModelWriter` の既存メソッド・既存task classの内部は検証しない。
- **型の境界**: 純粋計画（`HomeEditPlanner`）はAndroid型・DB行型をinterfaceへ漏らさない（`HomeEditSnapshot` は投影）。platform↔forkの境界（`DirectEditContract`）は純JDK型のみで、`src` が `lawnchair` を参照しない構造を保つ。書込みのSQLは `ModelWriter.java`（既に高リスクpath一覧・writer inventory収録済み）に集約し、homeedit配下は `getModelDbController()` 等のDB書込みpatternを持たない。
- **二段階検証の同一関数性**: stage 1（書込み依頼時）は `HomeEditAdapter` が作ったsnapshotに対して `HomeEditPlanner` を実行。stage 2（admission内）は `ModelWriter` がmodel threadで `BgDataModel`/screen順から `DirectEditContract` 経由でsnapshotを組ませ、**同一の** `HomeEditPlanner` 関数へ再実行させる（validatorを関数として渡す構造）。成功planの移動先がstage 1と異なる場合はstaleとして拒否する（無音の再計画はしない。#446のfallback（UpstreamDefault）は「上流が追加を決めたアイコン」の固有緩和であり、明示選択の編集アクションには当てはまらない）。
- **「ホームから外す」の構造**: `prepareToUndoDelete()` を呼んでから直接編集delete操作をenqueueし、上流snackbar（4秒）を表示。timeout → `commitDelete()`（queueされた操作がadmission → 再検証 → 1回delete）、Undo tap → `abortDelete()`（無書込み + forceReload）。commit時の再検証失敗はabort相当（無書込み + reload再同期）+ typed記録。`mPreparingToUndo` 窓の排他は上流の単一責務のまま（popup操作はUI threadで直列）。
- **NFR-013**: 確定（dialog positive）→ `closeAllOpenViews` → model threadでsnapshot取得・計画・submit。排他なし通常時は1回のupdate/INSERT+UPDATE/DELETEで即反映（bindは既存のmodel仕組み）。defer時はADR-0013契約4のとおりlease解放後の反映であり、spec AC-10のとおり追加の進捗表示は作らない。成功時は「ページへ移動」のみ移動先ページへsnapする（他は現在ページに表示変化が現れる）。

### Data flow（「ページへ移動…」の例）

```text
popup tap → HomeEditExecutor: THREAD_POOL_EXECUTORでsnapshot取得（BgDataModel/DB読み取り）
        → page一覧dialog表示（空き有無は表示時点の近似。確定時に再検証するため楽観表示でよい）
→ ページ選択 → mainHandler: HomeEditPlanner(stage 1, intent=MoveToPage(n))
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
- **「外す」に確認dialogを足す**: 上流drag-removeと同じ保護水準（4秒snackbar Undo）を再利用する方が操作負担が小さく（ベンチマークB4=1個3操作の前提と一致）、確認dialogは再利用機構との二重確認になる。
- **新規フォルダの作成を `Launcher.addFolder` で行う**: UI（FolderIcon）生成と結合し、UI thread前提。model threadのtransaction内で完結する最小操作として新設する（フォルダiconのbindは既存のmodel reload/bindに従う）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `src/com/android/launcher3/model/DirectEditContract.java` | 新設（~120行。純JDK型のsnapshot/decision/result + 関数型interface） | platform↔fork境界の最小契約。`src` が `lawnchair` に依存できないためsrc側に置く |
| `src/com/android/launcher3/model/ModelWriter.java` | 3つの直接編集操作を追加（既存メソッド・既存classの変更なし。既存の`ModelTask`/`updateItemArrays`/`newTransaction`/`prepareToUndoDelete`機構を再利用） | ADR-0013契約4「同経路に追加する最小の操作」。SQL書込みの一元化（高リスクpath・inventory済み） |
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
| AC-5 (a)(b)(c)(d) | instrumentation: admission前無変更 / defer後stale / coordinator排他 / 1 transaction | `connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=...`（shared-writer lane。CI: `organizer-instrumentation-shared-writer-tests`） |
| AC-2/3/4 振る舞い | instrumentation: 移動・フォルダ・削除の書込み結果と周辺行不変 | 同上 |
| AC-6 | JVM planner test | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.homeedit.*'`（CI: `organizer-unit-tests`） |
| AC-7 process死 | instrumentation process-death smokeの慣行に従うtest | shared-writer laneに同梱 |
| AC-1/10/11 | エミュレータでのpopup表示・操作・計測（ベンチマーク§7手順） | android-emulator plugin / 実機はowner確認 |
| 全体 | lint/format/build/repo-contract | `./gradlew spotlessCheck`、`./gradlew assembleLawnWithQuickstepGithubDebugDebug`、`python3 tools/repo-contract/validate_repo_contract.py`、`python3 tools/repo-contract/test_validate_repo_contract.py`、`python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` |

test-audit審査の要点（新規test・CI filter変更のため）: (1) 既存test/laneで新module契約をカバーできない（homeeditは未存在のため）、(2) oracleは最下層（純粋計画はJVM、DB/排他/deferは実framework・実DBを要するためinstrumentation）、(3) 新規laneは作らず既存 `organizer-unit-tests` gateとshared-writer laneへ統合、(4) 既存coverageとの重複なし（直接編集の書込みは既存testが対象外）、(5) 恒久gateへの昇格はsurface ownership（surface_jvm / surface_layout_write）の延長でありscheduled sweepでは不足。

## Documentation updates

- [ ] spec status/history（accepted → implemented）
- [ ] CONTEXT.md（domain language 3語）
- [ ] DESIGN.md（§4にhomeedit moduleの位置づけ、§11 gate 2系統へ直接編集（ADR-0013）行を追加。#446 spec rev 6が「将来の#448系PR」に委ねた分）
- [ ] ADR（本PRでは新設しない。ADR-0013/0014/0015を参照するのみ）
- [ ] AGENTS.md（変更なし。verified commandの追加も不要 — 既存commandのみ使用する）
- [ ] `docs/engineering/ci-test-portfolio.md`（test割付の記録）

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
- Plan + revision: specs/448-edit-actions-per-item/plan.md（本書、初版）
- Base SHA: 670527526490bfda5ec0862421ac3da790fafaa2（現行main。#470 merge後）
- Head SHA: Phase 1 push後にPR/Issueへ記録
- Diff: Phase 1 push後にcompare URLを記録
- Diff boundary: Phase 1はdocs-only（spec.md + plan.md の2ファイル）。full diffを確認対象とする
- Executed evidence: `python3 tools/repo-contract/validate_repo_contract.py` -> PASS予定; `git diff --stat` 目視
- 次の1手: branch `issue-448-edit-actions-per-item` をpushし、ChatGPTへPhase 1 reviewを依頼（結果はIssue #448コメントへ投稿）→ clear後、Phase 2（実装）→ Phase 2 review → PR作成・独立監査・merge
