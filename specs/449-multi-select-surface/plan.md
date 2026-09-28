# Implementation Plan: 複数選択の視覚的編集画面（#449 第2段）

> Issue: #449
> Spec: [spec.md](./spec.md)
> Status: draft
> Risk tier: H — specの冒頭に同じ根拠を記載する（layout適用 + recovery pointを伴う適用。新しい書込み経路・上流bridgeは作らない）。手順は現行どおり: accepted spec + plan.md、Execution and approval contract、`risk: layout-data` labelによる高リスク独立エビデンス（`final-status` + `docs/assessment/pr-<PR番号>-<slug>.md` の独立audit。auditは本実装sessionとは別の作業で行う）。
> Phase 1（本書の初版）: spec + planの起草とreviewを追跡する。Phase 2（実装）は同じbranchで行い、本planのRevisionで追跡する。#448の先例（spec + planをPhase 1で起草し、review clearでspecをacceptedに進める。Phase 2を同じbranch/PRで実施）に従う。
> Revision 2: 2026-09-28 — Phase 1 review round 1（[判定](https://github.com/nunu1733/NunuLauncher/issues/449#issuecomment-5862216096): Request changes）の指摘1〜4のうちplan側の対応。指摘1（受入前提）: spec冒頭へ受入条件（ADR-0014の受入前提。#442結論待ち）を明記し、本planもPhase 2の開始条件に同じ前提を置く。指摘2: data flowを `ApplyResult` variantごとの観測契約へ修正。指摘3: 共有plannerへの「指定セルへの新規フォルダ作成」intent variant追加をDesign/Change setへ反映。指摘4: 結合点5（lockState UNKNOWN）を未決の確認事項から撤去し、既存 `LOCK_STATE_UNAVAILABLE` 契約と一致する設計（選択不可+確定ゲート）へ確定。

## Current evidence

本planの `path:line` 根拠は2026-09-28にmain `f35ff4494f447c3cdb253eef6c3d10c77083ba03`（PR #472 merge後。#448のhomeedit module収録済み）上で検証した。

**適用経路（既存の安全な適用。本機能はこれを再利用し、実装を変更しない）**

- `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt:94` — `captureCurrent`（読み取り専用captureの実体）。`:180` — `prepareApplyWriteSet`（再captureとのrevision/状態一致検証を含み、intendedStateのitemsからwrite setをmaterializeする。PersistentItemはcaptureの行と対応し、欠落は削除として表現される。`FolderTitleResolver` は呼ばれず、folder行のtitleは `CanonicalItemState.title` 由来）。`:293` — `applyWriteSet`（1 transaction）。`:428` — `requestCorrelatedReload`。
- `lawnchair/src/app/lawnchair/organizer/application/protocol/ApplyProtocol.kt:113` / `:118` / `:199` — 確定時の再captureに対する `STALE_REVISION` / `EXACT_PRECONDITION_FAILED` 検証と、checkpointの `RECOVERY_POINT_ADMISSION_BLOCKED`。適用はcheckpoint → `markApplying` → 1 transaction → 分類（`classifyApplyOutcome`。相関reload + 検証を成功条件に含む）の順である。
- `lawnchair/src/app/lawnchair/organizer/application/public/Results.kt:45-68` / `:71-104` — `ApplyResult`（`NoChanges` / `Applied(pointId)` / `Rejected(PreWriteRejection)` / `RolledBack` / `Recovered` / `Unresolved` / `RecoveryFailed` / `ConcurrentRun`）と `PreWriteRejection`（`WRITER_BUSY` 等を含む）。
- `lawnchair/src/app/lawnchair/organizer/application/public/LayoutState.kt:27` — `LayoutState`。`:117` — `CanonicalItemState`（placement / `TargetKey` / title `OptionalText` / icon `OptionalBytes` / `OrganizerLockState` / profile availability）。`:260-279` — `ApplyAction`（`Preserve` / `Update` / `Insert`。削除はintendedStateからの不在で表現される）。
- `lawnchair/src/app/lawnchair/organizer/planning/PlanningResult.kt:121-143` — `FolderNaming`（`FromCategory` / `FromUserCategory` / `FromProposalLabel`。ユーザー作成フォルダのsemanticは存在しない）。`:162-168` — `NewFolder`（naming必須）。
- `lawnchair/src/app/lawnchair/organizer/application/actions/OrganizationPlanMaterializer.kt:165-166` — planner由来のplan構築でのみ `FolderTitleResolver`（非blank契約）が呼ばれる。homeeditが直接 `ValidatedLayoutPlan` を構築する場合はこの経路を通らない。
- `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt:136` / `:143` — `apply(plan)` と `applyWithRunId(plan, runId)`（composition root。recovery storeのbindingを持つ単一instance。第2instanceは作らない）。
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt:74` — `internal interface ManualOrganizationApplication`（`apply(plan, runId)` `:105`、`newRunId()`、`inspectPlan` 等のseam）。`:155` — `apply` の既存実装。`:176` — `ManualOrganizationRun.get(context)` の単一instance accessor。

**#448で実装済みの共有計画module（main収録済み）**

- `lawnchair/src/app/lawnchair/homeedit/HomeEditModel.kt:56-80` — `HomeEditIntent`（`sourcePlacement` precondition付きの `MoveToPage` / `AddToFolder` / `CreateFolderAndAdd` / `Remove`）。`:83-91` — `HomeEditRejection`（STALE / ITEM_GONE / NO_SPACE / REDUNDANT / FOLDER_GONE / PROFILE_MISMATCH / UNSUPPORTED）。`:94-123` — `HomeEditPlan`（`Move` / `CreateFolder` / `RemoveItem` / `Rejected`）。
- `lawnchair/src/app/lawnchair/homeedit/HomeEditPlanner.kt:19` — `plan(snapshot, intent): HomeEditPlan`（純粋。決定的な空きセル探索 `firstFreeCell` を含む）。`HomeEditSnapshotMapper`（`HomeEditAdapter.kt:12`）は `DirectEditContract.Snapshot` → `HomeEditSnapshot` の投影であり、本機能のorganizer capture → `HomeEditSnapshot` 投影とは別の入口になる（authorityが異なる: #448はmodel thread読み取り、#449はorganizer capture）。
- **`CreateFolderAndAdd` の置き先はrow-majorの最初の空きセルであり、指定セルを受けない**（`HomeEditPlanner.planCreateFolder`。対象をoccupiedから除いたうえでのfirst-fit）。specの置き先契約（先頭アイテムの元セル）を保証するには、指定セルを受け取るintent variantの追加が必要（review round 1 指摘3。本plan Designに反映）。
- **lock列の既存契約**: `ApplyProtocol.kt:557-558` — capture内に1行でも `OrganizerLockState.UNKNOWN` があるとapply全体を `LOCK_STATE_UNAVAILABLE` で拒否。`RowManifestCodec.kt:247` — 列値の範囲外/読み取り失敗はUNKNOWNへ正規化。`DatabaseHelper.java:288-291` — ADR-0004の移行は既存行をUNKNOWN（0）に設定する。よって「UNKNOWNを選択可能にする」設計は既存適用契約と矛盾する（review round 1 指摘4。選択不可+確定ゲートへ確定）。

**入口とCI**

- `lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt:16-23` — `DEFAULT_ORDER`（fork側のoption list。`lockHomeScreen` 有効時は `edit_mode` / `widgets` を除く既存規則あり。`:110`付近）。項目追加はfork側ファイルで完結し上流変更なし。
- Organizer hub: `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`（preferences配下のCompose面。`ManualOrganizationFace` / hub状態は `organizer/ui/ManualOrganizationRun.kt` の `State.Idle` 配下）。「編集画面」rowの追加位置はPhase 2冒頭でこの面の構成を確認して決める。
- `.github/workflows/ci.yml:335` / `:354` — `organizer-unit-tests` gate。filterは `app.lawnchair.homeedit.*` を含む（#448で追加済み。JVM testの新規classはrouting変更なしで収録）。
- `tools/repo-contract/ci_portfolio_map.yml:30-68` — instrumentation lane↔surface。`surface_layout_write` は shared-writer / production-input / reservation-recovery laneが所有。本機能の適用統合testは既存laneのどれかに追加し、新laneは作らない。
- `docs/engineering/editing-burden-benchmark.md` §4/§6/§7 — 重み（tap=1、長押し=2）、baseline確定値（B2=48 / B3=21 / B4=25）、目標（B2≤24 / B3≤10 / B4≤12）、fixture（§5）と検証手順（§7）。
- `docs/evidence/448/`、`docs/assessment/pr-472-edit-actions.md` — 先例のevidence構成。

## Design

### Modules and interfaces

```text
app.lawnchair.homeedit/                          （fork側。#448 moduleへの追加）
├── EditSurfaceState.kt                          # 新設: 編集セッションの純data
│                                                #   （選択集合、セッション計画、図投影、typed状態）
├── EditSurfaceProjection.kt                     # 新設: 純粋。LayoutState → 図表示投影
│                                                #   （ページ順、各アイテムのセル/span、ラベル参照、icon参照、
│                                                #    選択可否述語、dock・予約領域）
│                                                #   Nextでorganizer図previewと共有する分離候補（抽出自体はNext）
├── EditSurfaceSessionPlanner.kt                 # 新設: 純粋。HomeEditSnapshot投影 + 現在選択 + アクション
│                                                #   → 新セッション計画 または typed拒否（一括all-or-nothing）。
│                                                #   各アイテムに #448 の HomeEditPlanner.plan を呼ぶ（計画の共有）。
│                                                #   進行中の投影に適用しながら視覚順に決定的に計画する
├── EditSurfacePlanBuilder.kt                    # 新設: 純粋。capture（LayoutState + revision） + セッション計画
│                                                #   → ValidatedLayoutPlan。未選択アイテムはPreserve、
│                                                #   削除はintendedStateからの不在、新規フォルダは
│                                                #   PlannedFolder + NewFolder宣言。conservationを検証してから返す
├── HomeEditSurfaceAccess.kt                     # organizer application moduleへの薄い窓:
│                                                #   read-only capture 1本と apply 1本だけを公開する
│                                                #   （既存単一instanceの共有。第2instance・第2bindingを作らない）
└── ui/
    ├── HomeEditSurfaceActivity.kt               # 新設: 全画面Activity（fork側。launcher process内）
    ├── EditSurfaceScreen.kt                     # 新設: Compose図グリッド、選択、4アクションバー、
    │                                            #   確定/リセット/キャンセル、選択数・snapshot明示・
    │                                            #   理由表示、icon解決（TargetKey+profile → IconCache。
    │                                            #   custom icon bytes優先。解決不能はplaceholder）
    └── (既存) EditActionsShortcuts.kt           # #448のpopup経路。変更しない

共有moduleへの追加（#448の `app.lawnchair.homeedit`。additiveのみで既存経路は不変）
├── HomeEditModel.kt                             # HomeEditIntent へ指定セル付きの新規フォルダ作成variantを追加
│                                                #   （例: CreateFolderAt(sourcePlacement, screenId, cellX, cellY)。
│                                                #    popup経路が使う既存4 intentとその振る舞いは変更しない）
└── HomeEditPlanner.kt                           # 上記variantの計画追加: 指定セルが範囲内・1x1・
                                                 #   対象を除いたsnapshotで空いていることの検証と
                                                 #   既存 CreateFolder と同じ成功planの生成。違反はtyped拒否
                                                 #   （既存testへの影響なし。variantの新規testを追加）

organizer側（最小の追加。適用プロトコル・write set・recoveryの実装は変更しない）
├── LayoutApplicationModule                      # 新規: inspectCapture() — 読み取り専用capture
│                                                #   （plan preview seam族 [spec 84/194] と同契約:
│                                                #    書込み・lifecycle遷移・diagnostics発行なし）
├── ManualOrganizationRun / ManualOrganizationApplication
│                                                # 新規: 編集画面用の最小accessor（inspectCapture + apply。
│                                                #   run状態機械を経由しない。runIdは既存 newRunId 経路で発行）
└── planning/PlanningResult.kt（条件付き）        # FolderNaming へユーザー作成フォルダのvariant追加
                                                 #   （下記「実装時に確認する結合点」2。確定した場合のみ）

入口（fork側のみ。上流ファイル変更なし）
├── lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt
│                                                # 「編集画面」option追加（DEFAULT_ORDER + getLauncherOptions。
│                                                #   lockHomeScreen時は edit_mode/widgets と同じ規則で非表示）
└── Organizer hubのcompose面                     # 「編集画面」row追加
```

- **seam**: 呼び出し側とtestは (1) `EditSurfaceSessionPlanner`（セッション計画の純粋計算）、(2) `EditSurfacePlanBuilder`（適用計画の純粋構築）、(3) `HomeEditSurfaceAccess`（read-only capture / apply）の3つのseamを使う。`ApplyProtocol` / `prepareApplyWriteSet` / `applyWriteSet` / recovery storeの内部は検証しない（既存test群が所有）。#448の `HomeEditPlanner` はセッション計画から1アイテムずつ呼ばれ、単体で既存testが所有する。
- **型の境界**: 純粋3層（projection / session planner / plan builder）はAndroid型・DB行型をinterfaceへ漏らさない（organizer public型とhomeedit型のみ）。`HomeEditSurfaceAccess` だけがorganizer protocol型（`CapturedSnapshot` / `ValidatedLayoutPlan` / `ApplyResult`）に触れる。UI層はhomeedit型のみを受け、organizer型に触れない。
- **計画の共有（メモ§4.3「計算を共有し、書き方だけが異なる」）**: セッション計画の各アイテムの配置決定は #448 の `HomeEditPlanner.plan` そのものである。#449は複数アイテムの順序付け（視覚順）と all-or-nothing（1個でも `Rejected` なら全体を適用しない）と、適用計画への写像を追加するだけである。新規フォルダは、先頭アイテムの元セルを指定するintent variant（上記の共有module追加。review round 1 指摘3への対応。既存 `CreateFolderAndAdd` はrow-majorの最初の空きセルを選ぶため置き先契約を保証できない）で1回作り、残りはsession内部の仮フォルダidへの `AddToFolder` として計画し、plan builderで `PlannedFolder` ordinalへ写像する。
- **lock列の扱い（review round 1 指摘4への対応。既存契約と一致）**: captureの `OrganizerLockState.LOCKED` 行は選択不可（現行安全規約の「ロック配置不変」の入力側保証）。`UNKNOWN` 行は既存の `LOCK_STATE_UNAVAILABLE` 契約（capture内に1行でもUNKNOWNがあればapply全体を拒否。`ApplyProtocol.kt:557-558`）と一致させ、選択不可かつ、セッションcapture内にUNKNOWN行が存在する間は確定を無効化して理由を示す（零書込み）。`UNLOCKED` 行のみが選択・確定の対象になる。
- **一括適用の安全条件は適用経路の既存契約で満たる**: 適用計画のsourceState = セッション開始時のcaptureであり、確定時の再captureとの一致検証（`ApplyProtocol.kt:113/:118`）、checkpoint 1個（`:199`）、1 transaction、相関reload + 検証はすべて既存実装である。homeedit側は「選択アイテムのみを変える計画」を構築することでAGENTS.md安全規約の入力側条件（ロック不変: ロック中は選択不可、他行不変: Preserve、座標・参照有効: 純粋計画関数の検証）を満たす。
- **stale時の開き直し**: `Rejected(STALE_REVISION / EXACT_PRECONDITION_FAILED)` を受けたUIは、セッションを破棄して `inspectCapture()` で最新captureを取り直し、同じActivityで図を組み直す（選択・アクションは破棄済み）。理由表示は破棄の前に行う。

### Data flow（確定の例）

```text
確定tap（セッション計画が空でないこと）→ worker:
  EditSurfacePlanBuilder（純粋）: capture(セッション開始時) + セッション計画 → ValidatedLayoutPlan
     （未選択=Preserve、移動/追加=Update、新規フォルダ=Insert+NewFolder、外す=intendedStateから不在。
      conservation（全アイテムが保持・移動・削除に説明可能）をbuilder内でも検証）
→ HomeEditSurfaceAccess.apply(plan, runId)
     → module.applyWithRunId（既存。module mutex / ORGANIZER lease / 再capture検証 /
       checkpoint 1個 / 1 transaction / 相関reload + 検証）
     → Applied(pointId)          → 編集画面を閉じる（ホームは相関reloadで更新される）
     → Rejected(STALE_REVISION / EXACT_PRECONDITION_FAILED)
                                 → 零書込み → セッション破棄 → 理由表示 → 最新captureで開き直し
     → Rejected(RECOVERY_POINT_ADMISSION_BLOCKED / WRITER_BUSY / INVALID_PLAN /
       RECOVERY_STORE_UNAVAILABLE 等) / ConcurrentRun
                                 → 零書込み（pre-write拒否）→ 理由表示 + 再試行の促し（セッション保持）
     → RolledBack                → transaction rollback後のpre-state（無変更）→ 理由表示
     → Recovered                 → 自動復旧完了後のpre-state（無変更）→ 理由表示
     → Unresolved / RecoveryFailed
                                 → authoritativeState に従う。pre-stateを確認できない限り
                                   「無変更」と断定せず、状態不明/復旧未完了としてfail-closed表示
                                   （復旧への導線を含む。契約の正本は spec 13）
```

確定の前提: セッション計画が空でないことに加え、セッション開始時のcapture内に `OrganizerLockState.UNKNOWN` 行が存在しないこと（存在する場合は確定を無効化し理由を示す。`LOCK_STATE_UNAVAILABLE` 契約との一致。review round 1 指摘4）。

### Alternatives rejected

- **ワークスペース上の複数選択（案A）**: ADR-0014で不採用（上流変更5〜8ファイル・中核操作の安定性リスク最大）。本planはD-014に従う。
- **確定時適用を `ModelWriter` の直接編集操作（#448経路）で行う**: ADR-0013の対象は項目単位の即時編集であり、複数アイテムの一括確定は対象外（ADR-0013 Decision冒頭）。recovery point・相関reload・適用後検証を持つ安全規約の経路（メモ§4.3）を使う。
- **編集画面専用の第2の `LayoutApplicationModule` / recovery store binding の生成**: moduleはprocess単一のcomposition rootであり、recovery storeはmodule instanceにbindされている（`RecoveryStoreReconciliationIssuer` のbinding制約）。第2instanceは復元整合を壊すため作らない。既存instanceへの最小accessor追加で足りる。
- **organizerの `OrganizationPlanMaterializer`（planner出力→plan materialize）を経由する構成**: homeeditの計画はplanner出力（`PlanningResult`）ではなく、ユーザーが明示した選択と #448計画関数の結果である。materializerを経由すると `FolderTitleResolver`（非blank契約。`:165-166`）が呼ばれ、無題フォルダの要件と合わない。`ValidatedLayoutPlan` への直接構築（純粋plan builder）とし、titleは `OptionalText.Absent`（上流drag生成の無題フォルダと同じ）を書かせる。
- **「新しいフォルダ」の作成だけ #448経路（`ModelWriter`）で先行書込みする**: 1セッション = 1適用 = 1復元点の要件（メモ§4.3）を壊す。新規フォルダを含めて1つの適用計画に載せる。
- **図の構成を「現在ページのみ表示」にする**: B3の会計（選択4tap、ページ越えのswipeを含まない）と「別々のページにある4アプリ」の課題構造に合わない。全ページ1面の縮小表示とする（spec決定済み）。
- **アクション実行ごとの部分適用（空きが足りない分だけ移す）**: 選択全体への1操作というFR-019の振る舞いと、図の表示が常にセッション計画と一致するという予測可能性を優先し、all-or-nothing + typed理由とする（spec決定済み）。
- **新規フォルダの作成に既存 `CreateFolderAndAdd` をそのまま使う**: `planCreateFolder` は対象ページのrow-majorで最初の空きセルを選ぶため、specの置き先契約（先頭アイテムの元セル）を保証できない（review round 1 指摘3）。指定セルを受け取るintent variantを共有moduleへ追加する（additive。popup経路の既存4 intentと振る舞いは不変）。
- **`OrganizerLockState.UNKNOWN` を選択可能にする**: 既存適用契約はcapture内に1行でもUNKNOWNがあるとapply全体を `LOCK_STATE_UNAVAILABLE` で拒否する（`ApplyProtocol.kt:557-558`）。選択可能にすると「確定が必ず失敗するセッション」を許すことになり契約と矛盾する（review round 1 指摘4）。選択不可+確定ゲートとする。
- **確定前にUNKNOWN行を除去・正規化する**: lock列の正規化はlock authoring（Issue #38系）とADR-0004の所有であり、編集画面がlock列を読みも書かない設計（#448と同じ）と矛盾する。確定ゲートと理由表示で利用者へ次の手段（既存のlock確認導線）を示すに留める。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/homeedit/HomeEditModel.kt` / `HomeEditPlanner.kt` | 指定セル付き新規フォルダ作成のintent variantを追加（additive。popup経路の既存4 intent・振る舞い・既存testは不変。variantの新規testを追加） | specの置き先契約（先頭アイテムの元セル）を共有plannerで保証するため（review round 1 指摘3） |
| `lawnchair/src/app/lawnchair/homeedit/EditSurface{State,Projection,SessionPlanner,PlanBuilder}.kt` | 新設（純粋4層。概算+600〜900行） | セッション計画と適用計画の純粋計算。テストの最下層oracle。図描画の分離可能構成（Next共有の受け皿） |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditSurfaceAccess.kt` | 新設（薄い窓。~60行） | 既存organizer module instanceへのcapture/applyの唯一の出口。homeedit uiがorganizer protocol型に触れない境界 |
| `lawnchair/src/app/lawnchair/homeedit/ui/HomeEditSurfaceActivity.kt` / `EditSurfaceScreen.kt` | 新設（全画面Activity + Compose。概算+800〜1,200行） | ADR-0014案Bの操作面。fork側のみで完結 |
| `organizer/application/protocol/LayoutApplicationModule.kt` | `inspectCapture()` 追加（読み取り専用。~15行） | plan preview seam族と同契約の読み取り。セッション開始captureとstale時の再captureに使う |
| `organizer/ui/ManualOrganizationRun.kt`（`ManualOrganizationApplication`） | 編集画面用の最小accessor追加（capture + apply。~30行） | 既存単一instanceの共有。run状態機械を経由しない |
| `organizer/planning/PlanningResult.kt` + resolver mapping | `FolderNaming` へユーザー作成variant追加（結合点2が確定した場合のみ。~15行） | ユーザー作成フォルダの真実のprovenance。plannerのsemantic namingと混同しない |
| `lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt` | 「編集画面」option追加（~10行） | 既存fork拡張点。上流patch surfaceを増やさない |
| Organizer hubのcompose面（`ui/preferences/destinations/` 配下。Phase 2冒頭で位置確定） | 「編集画面」row追加（~20行） | メモ§4.3の2入口。hubは恒常作業領域（D-01/D-02） |
| `lawnchair/res/values/strings.xml` / `values-ja/strings.xml` | 入口2種、画面語（確定/リセット/キャンセル/選択数/snapshot明示）、理由表示、a11y用 | 既存fork文字列慣行 |
| `tests/unit/app/lawnchair/homeedit/` | projection / session planner / plan builder / 文言のJVM test | 純粋計算のoracle。`organizer-unit-tests` filterに既に収録（routing変更なし） |
| `tests/organizer-instrumentation/` | 編集セッション→適用の統合test（既存laneのclass listへ追加） | 実framework・実DBを要する適用・排他・stale・blocked・rollbackのoracle |
| `.github/workflows/ci.yml` / `tools/repo-contract/ci_portfolio_map.yml` / `docs/engineering/ci-test-portfolio.md` | instrumentation class追加時のlane割付とpath routingの記録更新（lane↔surface対応自体は不変の見込み） | quality-strategyのtest審査規約（同じPRで更新） |
| spec status / `CONTEXT.md` / `DESIGN.md` / `docs/product/requirements.md` | `implemented`、domain language 3語、homeedit記述への編集画面追加とread-only seamの追記、FR-019/NFR-013（+FR-018）status | 正本の分担（AC-14） |

**高リスクpath一覧の扱い**: 本設計はDB書込みを既存のorganizer適用経路（`LauncherLayoutAdapter` / `ApplyProtocol`。既に一覧収録・inventory済み）に集約し、fork側homeeditは直接DB書込みを持たない。よって高リスクpath一覧へのhomeedit追加は行わない（#448と同じ判断。`validate_writer_inventory.py` が機械的に裏付ける）。`LayoutApplicationModule` / `ManualOrganizationRun` への追加は適用プロトコルの実装変更を含まない（読み取りseamとaccessorの追加のみ）が、PR本文へ明記する。

## Migration and recovery

- schema/rule migration: なし。書く行はorganizer適用経路が書く標準の `favorites` 構造。
- failure中のrollback: 適用は既存プロトコル（checkpoint → 1 transaction → 分類）に従い、失敗時は変更前へ戻る。本機能はプロトコルの実装を変更しないため、既存test群の回帰確認で足りる。
- release rollback/downgrade: PR revertで閉じる。書き込まれた行は上流・organizer適用が書くのと同じ構造であり、旧版でも読める。
- process死: セッションはprocess内のみで永続化しないため、確定前のprocess死は無変更である。適用中のprocess死は既存の `markApplying` / restart reconciler契約に従う（既存testが所有）。

## 実装時に確認する結合点（fail-closed。確認結果をplan revisionへ記録する）

1. **無題フォルダのtitle**: `prepareApplyWriteSet` はfolder行のtitleを `CanonicalItemState.title` 由来で書く（`FolderTitleResolver` は経由しない）。`title = OptionalText.Absent` のplanned folderがTITLE null（無題。上流drag生成と同じ）として書けることをinstrumentation testで確認する。もし書込み経路が非blank titleを要求して拒否する場合は実装を止め、specの観測可能な振る舞い（無題フォルダ）を維持するための最小拡張をこのplanのrevisionで確定してから進める。
2. **`NewFolder.naming` の表現**: `ValidatedLayoutPlan` の不変条件はPlannedFolder参照にNewFolder宣言（naming必須）を要求する。ユーザー作成フォルダのsemanticとして `FolderNaming` へvariant（UserCreated相当）を追加し、resolver側のmappingは契約（非blank）を満たす安全な既定へ追加する。resolverはhomeedit経路では呼ばれない。variant追加の影響範囲（exhaustive when）を確認して最小に保つ。
3. **`ruleVersion` / `taxonomyVersion`**: 適用計画のprovenance列。homeeditの計画は整理ルール体系の外であるため、現行policy bundleのversionを記録値として使い、その旨をbuilderのdoc commentへ残す。取得経路を実装時に確認する。
4. **runIdとdiagnostics**: runIdは既存 `newRunId()` 経路で発行する。編集画面の適用イベントが既存diagnostics経路（app-private journal、個人情報なし）へ流れる挙動を確認し、organizer runのUI/journal読み出しと混在しないことを記録する。問題がある場合は原因と対処（typed記録の分離等）をPRで記録し、`docs/engineering/organizer-diagnostics.md` への記載要否を判断する。
5. ~~**lockState `UNKNOWN` の扱い**~~ — 解決（Revision 2。review round 1 指摘4）: 確認事項から設計へ確定した。`LOCKED` と `UNKNOWN` は選択不可、セッションcapture内にUNKNOWN行が存在する間は確定を無効化して理由を示す（既存 `LOCK_STATE_UNAVAILABLE` 契約と一致。`ApplyProtocol.kt:557-558`、`DatabaseHelper.java:288-291` の移行で既存行はUNKNOWNになる）。実装時の確認事項は「capture正規化でUNKNOWNになる実際の値域の再確認（`RowManifestCodec.kt:247`）と、確定ゲートのtest」に縮小する。
6. **readinessGate / module mutex**: 起動直後等でmoduleのreadinessが未完了の場合の `apply` / `inspectCapture` の挙動を確認し、UIの待ち方（待機 or typed失敗+再試行）を決める。既存のSettings面の扱いに合わせる。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1/2/3 構造 | エミュレータスクリーンショット（入口2経路、図、選択、対象外・ロック注記） | android-emulator plugin / 実機はowner確認 |
| AC-2/3 計算 | projection（図投影・選択可否述語・icon fallback参照）のJVM test | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.homeedit.*'`（CI: `organizer-unit-tests`） |
| AC-4 / AC-9 | session plannerのJVM test（一括移動・空き不足all-or-nothing・フォルダ・新規フォルダ・外す・決定性・冪等性・typed拒否。`HomeEditPlanner`共有の呼出しを含む） | 同上 |
| AC-5 構築 | plan builderのJVM test（Preserve/Update/Insert/不在、conservation、新規フォルダ宣言、sourceState一致） | 同上 |
| AC-5/6/7/8/10 統合 | instrumentation: 編集セッション→適用（選択行のみ変化、recovery point 1個、相関reload）、stale零書込み+開き直し、lease中/blocked零書込み+セッション保持、UNKNOWN行の確定ゲート、リセット・キャンセル零書込み、失敗注入rollback | 既存lane（`surface_layout_write` 系）へ追加。実装時に該当laneのclass listを確定 |
| AC-16 統合 | instrumentation: 失敗注入での `RolledBack`（pre-state表示）と `Unresolved` / `RecoveryFailed`（fail-closed表示。既存プロトコルの自動復旧testとの接続を確認） | 同上 |
| AC-11 | エミュレータでの操作計測（選択反映≤100ms、確定→適用完了≤3秒目標。PR本文）+ owner実機確認 | android-emulator plugin |
| AC-12 | ベンチマーク§5 fixture + §7手順に準拠したエミュレータ実行記録（B2=11 / B3=9 / B4=11、hub経由=4） | android-emulator plugin（PR本文へ記録） |
| AC-13 | patch surface計測 | `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` |
| AC-14 | repo contract検証 + diff確認 | `python3 tools/repo-contract/validate_repo_contract.py`、`python3 tools/repo-contract/test_validate_repo_contract.py` |
| AC-15 | 文言のJVM test + エミュレータTalkBack読み上げ記録 | JVM test + android-emulator plugin / 実機はowner確認 |
| 全体 | lint/format/build | `./gradlew spotlessCheck`、`./gradlew assembleLawnWithQuickstepGithubDebug` |

test-audit審査の要点（JVM testの追加とinstrumentation class追加のため）: (1) 既存test/laneで新module契約（セッション計画・適用計画構築・編集画面適用の統合）をカバーできない（新規surfaceのため）、(2) oracleは最下層（純粋計算はJVM、適用・排他・staleは実framework・実DB・実leaseを要するためinstrumentation）、(3) 新規laneは作らず既存gate/laneへ統合、(4) 既存coverageとの重複なし（適用プロトコル自体の契約testは既存test群が所有し、本機能は「編集セッションから構築した計画が適用経路を通る」統合のみを追加）、(5) 恒久gateへの昇格は既存surface ownership（surface_jvm / surface_layout_write）の延長である。

## Documentation updates

- [ ] spec status/history（accepted → implemented）
- [ ] `CONTEXT.md`（domain language 3語: 編集画面 / 編集セッション / セッション計画）
- [ ] `DESIGN.md`（§4のhomeedit記述へ編集画面と一括適用を追加、§4.2の読み取り専用seam族へ `inspectCapture` を追記）
- [ ] `docs/product/requirements.md`（FR-019 / NFR-013のstatus。FR-018は#448分を実装merge済みであることへの言及とともにstatus更新）
- [ ] `docs/engineering/ci-test-portfolio.md` / `tools/repo-contract/ci_portfolio_map.yml`（instrumentation classの割付記録。lane↔surface対応は不変の見込み）
- [ ] `docs/engineering/organizer-diagnostics.md`（結合点4の確認結果に応じて記載要否を判断）
- [ ] ADR（新設しない。ADR-0013/0014を参照するのみ。ADR-0014は #442 結論後の受入時に再照合）

## Execution checklist

- [ ] Current behavior confirmed（上記Current evidence。編集画面なしの現状、#448 popupの動作、入口のoption list構成）
- [ ] Tests fail for the missing behavior（projection / session planner / plan builderのJVM testとinstrumentation統合testを先に作り、未実装で失敗することを確認）
- [ ] Minimal implementation completed（純粋4層 → organizer accessor → UI → 入口 → 文字列）
- [ ] 結合点1〜6の確認と記録（失敗時は実装を止めてplan revisionで確定）
- [ ] Migration/recovery verified（rollback / stale / 排他 / process死 test）
- [ ] Full relevant verification completed（Verification表の全行）
- [ ] PR evidence and remaining risks recorded（実機確認はowner確認事項として明記）

## Review / handoff packet（Phase 1 Revision 2時点）

- Issue and all comments: https://github.com/nunu1733/NunuLauncher/issues/449; retrieved at 2026-09-28; state=OPEN; labels=type: feature
- Scope type: feature
- Accepted spec + commit: **本specの受入はADR-0014の受入が前提**（spec冒頭に明記。review round 1 指摘1）。ADR-0014の受入は #442 の最終結論が前提であるため、specはreview clear後も `draft` を維持し、ADR-0014がAccepted化した時点でrevisionを取り込んで再照合のうえ受入手続きへ進める。Phase 2（実装）の開始可否は、この前提の扱いを含めてreviewに判断を求める
- Bug oracle: N/A（feature。振る舞いoracleは本specのBehavior scenarios / AC）
- Plan + revision: specs/449-multi-select-surface/plan.md（本書、Revision 2）
- Base SHA: f35ff4494f447c3cdb253eef6c3d10c77083ba03（現行main。PR #472で#448収録後）
- Head SHA: round 1 review対象 `c57cbaf1f74fad3d542da55117322187c3ea9ab1`。Revision 2のheadは、本欄を含むcommit自体がheadを変えるためIssue #449へのhandoffコメントで記録する（正本）
- Diff: current main...headの実質差分は `specs/449-multi-select-surface/spec.md` / `plan.md` の2ファイル（compare URLはpush後に記録）
- Diff boundary: Phase 1はdocs-only（上記2ファイル）。full diffを確認対象とする
- Round 1 review: [判定](https://github.com/nunu1733/NunuLauncher/issues/449#issuecomment-5862216096) Request changes（指摘1〜4）。Revision 2での対応: 指摘1=受入前提の明記（外部前提）、指摘2=適用結果のvariant別観測契約、指摘3=共有plannerへの指定セルvariant追加、指摘4=UNKNOWN契約の確定（選択不可+確定ゲート）
- Executed evidence: `python3 tools/repo-contract/validate_repo_contract.py` の実行結果をpush後のhandoffコメントへ記録する
- 次の1手: Revision 2をpushし、ChatGPTへPhase 1再review（round 2）を依頼（結果はIssue #449コメントへ投稿）。あわせて、受入前提が未成立（#442結論待ち）の状態でのPhase 2開始の扱い（draft維持のまま実装を進める可否、または外部前提の完了まで停止）をreviewに判断を求める
