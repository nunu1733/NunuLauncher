---
issue: "#450"
status: implemented
requirements:
  - FR-020
  - NFR-013
  - NFR-014
  - D-013
updated: 2026-10-01
---

# 編集の取り消し（Undo）

> Risk tier: H — 項目単位の逆操作は `ModelWriter.java`（[高リスクpath一覧](../../docs/project/github-workflow.md#高リスクprへの独立エビデンス要求)収録済み）への直接編集系の新規最小操作であり、階層H条件「上流のmodel/loaderへのbridgeを作るまたは変える」「変更pathが高リスクpath一覧に当たる」に当たる。編集画面の確定の取り消しは既存のorganizer復元経路（`risk: layout-data` 対象経路）を使う。手順は現行どおり（accepted spec + plan.md、Execution and approval contract、`risk: layout-data` label、独立audit + `final-status`）。
> Epic: #439（再焦点化）。出典: 再焦点化方針メモ（2026-09-24に承認、Revision 5。以下「メモ§x」）。書込み契約は [ADR-0013](../../docs/adr/0013-direct-edit-write-contract.md)（#445で受入済み。AGENTS.md「ホームレイアウトを扱う安全規約」の直接編集carve-outが本Issueの逆操作を含む）を参照するのみであり、本specで重複定義しない。
> 前提の状態（2026-09-29に再照合。base `origin/main` `be7576c30a864574c28b0fd3450b6e767c435ea1`）: ADR-0013 accepted（2026-09-27）。#448 implemented（PR #472。`app.lawnchair.homeedit` と `ModelWriter` 直接編集3操作、Undo evidence記録が存在）。#449 implemented（PR #476、merge `29476b10a0`。`inspectCapture`/apply seamと編集画面の適用完了flowが実装済み）。ベンチマーク正本は [editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md)（#441確定。B5目標=1操作）。

## Problem

forkが提供する2種類の編集（(1) 項目単位のアクション（#448。即時書込み）、(2) 視覚的編集画面の確定（#449。organizerの安全な適用経路））のどちらにも、直後の取り消し経路が存在しない。#448の実装は各アクション成功時にUndo evidence（`HomeEditUndoLog`、`lawnchair/src/app/lawnchair/homeedit/HomeEditUndoLog.kt:39-50`）をprocess内へ記録するが、逆操作の実行・検証・UIは未実装である（同fileのdoc commentどおり本Issueが所有する）。上流の削除Undo（4秒snackbar + 遅延commit。`src/com/android/launcher3/model/ModelWriter.java:404-446`、`src/com/android/launcher3/DropTargetHandler.kt:73-98` の `prepareToUndoDelete`/undo tap）はdrag削除専用であり、#448の即時書込み（admission内再検証後の即時1回DELETE等）には適用できない。ベンチマークB5のbaselineは 削除1（4秒窓内のUndo tap）/ 移動8（逆drag）であり、目標はfork編集いずれも1操作である。

## Outcome

利用者がforkの編集（項目単位のアクション、編集画面の確定）を行うと、直後に「元に戻す」を1操作（snackbarのUndo tap）で実行できる。

- 項目単位のアクション（ページへ移動、フォルダへ入れる、ホームから外す）は、ADR-0013契約5の直接の逆操作で戻る。移動・フォルダ追加は元のcontainer/screen/cell/rankへの逆書込み、新規フォルダ作成は「子を元の位置へ戻し、作ったフォルダ行を消す」1 transaction、外すは削除前の行内容での再追加（逆INSERT）である。
- 視覚的編集画面の確定は、そのセッションの復元点（ADR-0014 Decision: 確定1回 = 適用1回 = 復元点1点）をorganizerの復元経路（`RecoveryRequest` のrevision照合付き）で戻す。
- 逆操作の前に現在状態を副作用のない検証で照合し、ずれていれば書かずにtypedな失敗を利用者へ示す（fail-closed。メモ§4.2条件5）。表示中のsnackbarが古い編集を指している競合（次の編集で記録が置き換わっている）でも、誤って新しい編集を取り消さない（零書込みの無操作）。

## Scope

- **対象と取り消し経路の割当（Issue本文どおり）**:
  1. **項目単位のアクション（#448）→ 直接の逆操作**。逆操作もADR-0013対象(c)の直接編集書込みであり、契約1〜5（変更対象の限定、二段階の副作用のない検証、1 DB transaction、MODEL_WRITER admission内完結、fail-closed）に従う。organizerのsnapshot revision照合・recovery point・相関reload・適用後の全体再検証は要求しない（AGENTS.md安全規約の直接編集carve-out、ADR-0013「不要とするもの」）。
  2. **視覚的編集画面の確定（#449）→ organizerの復元経路**。Undo = 確定時に作られた復元点の復元である。取り消し記録は `RecoveryPointId` と、確定直後の現在revision（`expectedCurrentRevision`。`RecoveryRequest.kt:14-17`）を保持し、undo要求はorganizer復元経路（`LayoutApplicationModule.recover`、`LayoutApplicationModule.kt:310`）へ流す。結果の型（`Restored` / `NotRestorable` / `RestoreFailed` / `WriterBusy` / `ConcurrentRun`。`Results.kt:129-149`）をそのまま扱い、失敗はtypedに表示する。本Issueは復元経路を新設・変更しない。
- **編集画面確定のundoにおける `expectedCurrentRevision` の正本（Phase 1 review round 1指摘2 + round 2指摘1の確定）**: `expectedCurrentRevision` は、**apply経路が適用後検証に実際に使ったmaterialized post-state（`MaterializedWriteSet.intendedState`）のrevision**とする。apply経路自身が `Applied` の組み立て点（lease保持中・1適用の内部）で `RevisionCalculator.revisionOf(writeSet.intendedState)` を計算し、internalなapply receiptで `pointId` と同時にfork側へ運ぶ。**適用後の再capture（post-hoc capture）も、適用計画の未解決 `intendedState` をそのまま使うことも正本にしない。** 根拠: (a) `ApplyResult.Applied` は適用後の独立DB再取得が `writeSet.intendedState`（materialized）と一致した後にのみ返される（`ApplyProtocol.kt:375-381`、`:392-399`）ため、`Applied` 受信時点で現在revisionはこのverified revisionと等しい（revisionはcanonical状態の決定的関数。`RevisionCalculator.kt:29-39`）。(b) 適用計画の `intendedState`（`ValidatedLayoutPlan.intendedState`）は新規フォルダを `ApplicationItemRef.PlannedFolder` のまま含む未解決状態であり（`EditSurfacePlanBuilder.kt:124`）、apply内部で永続ID採番（`LauncherLayoutAdapter.kt:212-219`）、planned ref→persistent ref解決（`:231-234`）、page正規化（`:236-239`）を経た **materialized後の状態** が実際のpost-stateである。plan由来のrevisionでは新規フォルダを含む確定の正常系undoが必ず `STALE_REVISION` になる（round 2指摘1）。(c) apply完了後の再captureは、captureまでの間に入った別の書込み後のrevisionを記録しかねず、「確定直後の状態からのずれを書かずに拒否する」目的に反する。(d) 復元は新規captureのrevisionと `expectedCurrentRevision` を比較し、不一致を `NotRestorable(STALE_REVISION)`・零書込みで拒否する（`RecoveryProtocol.kt:113-116`）ため、確定とundoの間に任意の書込み（手動drag、項目単位編集、新規アプリの配置を含む）があればundoはtyped失敗になる。なお `intendedRevisionOf`（`RevisionCalculator.kt:46-50`。INTENDED_POST_STATE digest）はcapture revisionとdigest種が異なるため使わない（captureと同種のPRE_STATE digestである `revisionOf` を使う）。
- **apply seamの拡張の範囲**: 上記のreceipt運搬はorganizer適用moduleの **internalな結果拡張**（`ApplyProtocol` / `LayoutApplicationModule` のinternal返値、`ManualOrganizationApplication` のadditiveなmethod、`HomeEditSurfaceAccess` のaccessor）で実装する。organizerのpublic契約（`ApplyResult` / `RecoveryRequest` / `RecoveryResult` / spec 13のapply・recovery振る舞い契約）は変更しない。
- **#449との接続点（確定）**: 確定完了（`ApplyResult.Applied` 受信時。`HomeEditSurfaceActivity.handleApplyResult`、`HomeEditSurfaceActivity.kt:267-302`）が、apply receiptで受け取った `pointId + verified post revision` を取り消し記録へ編集画面entryとして書き、Undo snackbarの表示をlauncherへ要求する。undo tapは本Issueの経路（organizer復元 `recover`）へ流れる。#449はimplemented（PR #476）であり、本接続は#449側の未実装部分を待たない。
- **「外す」のUndo方式の決定（Issue未解決事項への回答）**: **逆INSERTとする。上流の遅延commit削除（`prepareToUndoDelete` / `commitDelete` / `abortDelete`）は再利用しない。** 根拠: (a) #448の「外す」はadmission内再検証後に即時commit済みのDELETEであり（`removeItemForDirectEdit`、`ModelWriter.java:635-637,869-901`）、遅延commit機構はdrop時（commit前）にのみ働き、commit済みの削除を遡って採用できない、(b) 4種の逆操作すべてを「事前照合 + 逆row書込み1 transaction」の同一構造に揃えられる、(c) 削除の可視性が即時であること（NFR-013）は#448の時点で確定済みであり、遅延commitと両立しない。削除前行内容の運搬は、`DirectEditContract.ResultCallback` の **明示的な拡張**（削除成功時のみ非nullの `UndoRowPayload` 引数追加。`DirectEditContract.java` は#448が作ったfork所有のbridge契約fileである）でadmission内capture（DELETE直前）から実行経路の成功callbackへ返し、項目単位entryへ格納する（Phase 1 review round 2指摘2の確定。詳細はplan）。上流drag削除の4秒snackbar（baseline）は本Issueで変更せず、そのまま残る（Non-goals）。
- **取り消し記録（Undo記録）と世代紐付け（Phase 1 review指摘1の確定）**: process内の単一slotに持つ閉じたrecord（項目単位のentry / 編集画面確定のentry のいずれか1件）。**各recordはprocess内で一意の世代識別子（generation）を持つ。** snackbarのUndo actionは表示時のrecordの世代識別子を閉じ込めて渡し、記録の消費は「現在のslotが同一世代のrecordを保持しているときに限る」compare-and-consumeである。世代が不一致のとき（表示済みsnackbarが、すでに次の編集で置換されたrecordを指している競合。置換後のsnackbarがまだ表示されていない窓を含む）は **零書込みの無操作** であり、現行のrecordを消費せず、失敗表示もしない（現行編集は引き続きundo可能）。寿命は「process内で次の編集まで」（メモ§10、§11 B-3）。fork編集の成功が新たなrecordで置換し、undo試行の終了時（成功・失敗いずれか）に消費され、process死で消失する。永続化・時間窓付き保持（organizer復元点の24時間枠等）はNext。organizerのrecovery storeとは混同しない（本記録はrecovery storeへ書かない）。
- **UI**: 操作直後のsnackbar（メモ§11 B-3。上流のRemoveのUndoと同じ `Snackbar.show` 方式。`src/com/android/launcher3/views/Snackbar.java:89-92`）。表示時間は上流と同じくaccessibility設定に従う実時間（`AccessibilityManagerCompat.getRecommendedTimeoutMillis`、基準4000ms。`Snackbar.java:50,184-188`）。snackbarは同時に1つ（`Snackbar.show` が既存snackbarを閉じる。`Snackbar.java:91`）であり、連続編集では前の編集のsnackbarは新しい編集のsnackbarで置換される。取り消しの入口は第1版ではこのsnackbarのみとする（snackbar失効後の別入口はNext）。popup編集（#448）のsnackbarはlauncher上で直接表示する。編集画面確定（#449）のsnackbarは確定後にlauncher画面へ表示する（編集画面activityは `Applied` で閉じるため、launcher側への表示引き渡しを本Issueが実装する。launcher不在時はsnackbar不出であり、recordのみ残る。入口はsnackbarのみなので実効的なundoは発生しない）。
- **現在状態との照合とfail-closed**: 逆操作の前に、記録の前提を副作用のない純粋関数で現在状態に照合する。照合範囲はADR-0013契約5どおり「アクションが変えたrow」（対象アイテム、作ったフォルダ）と復帰先の空きに限り、全レイアウトのrevision照合はしない。**「外す」の逆INSERTでは、削除した起動先の現在availability（アプリcomponent / deep shortcut identity × profile）を照合入力に含める（Phase 1 review指摘3の確定）**: stage-1（submit時）とstage-2（admission内）で **同一のproduction verifier・同一の純粋照合** を通し、起動先が存在しない・検証自体が失敗した場合（fail-closed）は `UNDO_ITEM_UNAVAILABLE` で零書込みとする。編集画面確定のundoはorganizer復元のrevision照合（`RecoveryProtocol.kt:113-116`）に従う（経路の既存契約）。
- **organizer runとの排他**: 項目単位の逆操作は `LayoutWriteCoordinator` のMODEL_WRITER admission経由（deferあり）でorganizer runをまたがない。編集画面確定のundoはorganizer復元経路のlease契約（`WriterBusy` 等）に従う。
- **記録の所有と書き手**: 記録の形式・寿命・照合方法は本Issueが所有する。#448の実行経路が項目単位のentryを書き（既存の `HomeEditUndoLog` を本Issueのrecord型へ発展させる）、#449の確定完了flowが編集画面entryを書く。

## Non-goals

- 上流dragによる手動移動の取り消し（Next。#442後の判断。メモ§4.1、§4.5）。
- 上流のdrag削除snackbar（約4秒・遅延commit）の変更・置換。baselineとして記録対象でありそのまま残す（メモ§4.1）。
- 新規アプリの配置（#446/ADR-0015）の取り消し（メモ§4.1、§11 B-1。対象外）。
- 複数段のUndo、redo、履歴の遡り（FR-020は「直前の編集」1件のみ。undo試行の終了後にrecordを再利用しない）。
- organizer run適用の取り消しの新設（既存の復元経路を使うのみ）。
- 取り消し記録の永続化・時間窓（Next。メモ§4.5）。
- snackbar失効後の別のundo入口（Organizer hub・長押し等。Next）。
- 編集アクションの振る舞いの変更（#448が所有）。organizer復元経路の変更（spec 13が所有）。

## Domain language

- **編集の取り消し (Edit Undo)**: 利用者がforkの編集（項目単位のアクション、編集画面の確定）の直後に1操作で直前の編集を元に戻す操作（FR-020）。_Avoid_: 復元 (restore)（organizerのrecovery point復元と混同。編集画面確定のundoは結果として復元経路を使うが、操作の語は取り消し）、redo
- **取り消し記録 (Undo Record)**: 直前のfork編集1件を取り消すためにprocess内の単一slotへ保持する閉じたrecord。世代識別子を持ち、次のfork編集の成功で置換され、process死で消失する。_Avoid_: recovery point（organizerの適用前状態の永続復旧手段。別の物）、履歴（複数段を保持しない）
- **逆操作 (Inverse Operation)**: 項目単位のアクションを戻すための、ADR-0013契約に従う直接の編集書込み（逆UPDATE / 逆INSERT+逆DELETEの組 / 逆INSERT）。実行前に現在状態との照合を必ず通る。_Avoid_: abort（上流の遅延commit取消との混同）

（承認時に `CONTEXT.md` へ反映する）

## Behavior scenarios

### Scenario: 「ページへ移動…」直後に取り消す

Given ページ0のアイテムAを「ページへ移動…」でページ2へ移動し、snackbarが表示されている
When 利用者がsnackbarの「元に戻す」をtapする
Then Aのfavorites行が元のcontainer/screen/cell（rankを含む）へ1回のupdateで戻り、画面にAが元の位置へ表示される
And 取り消し記録は消費され、再度のundo（redo）はできない
And 移動と同じく、元の位置が表示される（元のページへsnapする）

### Scenario: 「フォルダへ入れる…」（既存フォルダ）直後に取り消す

Given Aを既存フォルダFへ入れた直後である
When 利用者が「元に戻す」をtapする
Then Aの行がFから元のcontainer/screen/cell/rankへ1回のupdateで戻り、Fの表示件数が減る
And Fが空になった場合、Fは残る（[spec 24](../24-empty-folder-policy/spec.md) のpreserve default。逆操作はFを消さない）

### Scenario: 「新しいフォルダ」直後に取り消す

Given Aの「フォルダへ入れる…」→「新しいフォルダ」でフォルダGが作られAがrank 0で入った直後である
When 利用者が「元に戻す」をtapする
Then 1つのtransaction内で、Aの行が元のcontainer/screen/cell/rankへ戻り、Gの行が削除される
And transaction内の失敗ではAもGも変化しない（変更前へ戻る）

### Scenario: 「ホームから外す」直後に取り消す

Given Aを「ホームから外す」で削除した直後である（Aの行はfavoritesから消えている）
When 利用者が「元に戻す」をtapする
Then 記録が保持する削除前の行内容（配置に加え、行の再構築に必要な列。itemType、profile、起動先、title等）でAの行が1回のinsertで元の位置へ戻る
And 削除した起動先が現在も端末に存在する限り、Aは元の位置へ表示される

### Scenario: 「ホームから外す」のundoで起動先が消えていれば書かずに失敗する

Given Aを「ホームから外す」で削除した後、undo前にAのアプリがアンインストールされた（deep shortcutの場合はショートカットが無効化・削除された）
When 利用者が「元に戻す」をtapする
Then 書かずにtypedな失敗（UNDO_ITEM_UNAVAILABLE）を表示する（古い行を再INSERTしない）
And availabilityの検証自体が失敗した場合（検証不能）も同様に零書込みのtypedな失敗とする（fail-closed）

### Scenario: 視覚的編集画面の確定直後に取り消す

Given #449の編集画面で複数アイテムへの編集を確定し、適用（復元点1点の作成を含む）が完了した直後である
When 確定完了時に表示されたsnackbarの「元に戻す」をtapする
Then 確定セッションの復元点がorganizerの復元経路（`RecoveryRequest`。pointId + 確定完了時にapply経路から受け取ったverified post revision）で復元され、ホームは確定前の状態へ戻る
And 新規フォルダの作成を含む確定でも、他の書込みがなければこのUndoは成功する（`Restored`）
And 復元後の再読込と不変条件の再検証は復元経路の既存契約（spec 13）に従う

### Scenario: 確定後に他の書込みがあれば編集画面のundoは零書込みで失敗する

Given 編集画面の確定後、undo前に手動drag・項目単位編集・新規アプリの配置のいずれかでレイアウトが変わった
When 利用者が「元に戻す」をtapする
Then 復元経路のrevision照合が不一致となり、`NotRestorable(STALE_REVISION)`・零書込みである
And その旨がtypedに表示され、取り消し記録は消費される

### Scenario: 状態が変わっていれば書かずに失敗する（fail-closed）

Given 「ページへ移動…」でAをページ2へ移した後、undo前にAを手動dragで別の場所へ動かした
When 利用者が「元に戻す」をtapする
Then 逆操作は書かずに失敗し、model/DBは変化しない
And typedな理由（対象が記録の結果位置にない旨）が表示され、取り消し記録は消費される

### Scenario: 元の位置が埋まっている場合は書かずに失敗する

Given 「ページへ移動…」の後、undo前に他のアイテムが元のセルへ置かれた
When 利用者が「元に戻す」をtapする
Then 書かずにtypedな失敗（復帰先が空いていない旨）を表示する（別セルへの代替配置はしない）

### Scenario: 作ったフォルダの状態が変わっていれば書かずに失敗する

Given 「新しいフォルダ」でGを作った後、undo前に利用者がGへ別のアイテムを追加した
When 利用者が「元に戻す」をtapする
Then 書かずにtypedな失敗（作ったフォルダの状態が変わった旨）を表示する（Gは消さない）

### Scenario: 連続編集では直前の1件のみ取り消せる

Given Aへの編集1の直後にBへの編集2を行った
Then 編集2の成功時点で取り消し記録は編集2のrecordへ置換され、編集1のsnackbarは編集2のsnackbarで置き換えられる
And 「元に戻す」で戻せるのは編集2のみである（FR-020）

### Scenario: 置換前のsnackbarのtapは次の編集を取り消さない（世代不一致の競合）

Given Aへの編集1のsnackbarがまだ表示されている間にBへの編集2が成功し、記録が編集2に置換された（編集2のsnackbarがまだ表示されていない窓を含む）
When 表示中の編集1のsnackbarで「元に戻す」をtapする
Then 零書込みの無操作であり、model/DBは変化しない
And 編集2の取り消し記録は消費されず、編集2は引き続き「元に戻す」で戻せる
And 失敗表示はしない（staleなsnackbarのtapは現行recordへのundo試行ではない）

### Scenario: process死後は取り消せない

Given 編集1のsnackbar表示中にprocessが死んだ
When ランチャーが再起動される
Then 取り消し記録は消失しており、undoの入口は存在しない（項目単位の編集に復旧手段は付けない。ADR-0013「直接編集はrecovery recordを持たない」）
And 編集画面確定の適用については、既存のorganizer復元入口（保持期間内の復元点）が引き続き利用可能である（本Issueはこれを変更しない）

### Scenario: 復元点がもう使えなければ失敗を示す

Given 編集画面の確定後、undo前に復元点が保持期間・件数でevictされた、または既に復元済み・失効した
When 利用者が「元に戻す」をtapする
Then 復元経路のtypedな拒否結果（`NotRestorable`（MISSING/EXPIRED/ALREADY_RESTORED等）、`WriterBusy` 等）を失敗として表示する。書込みは復元経路の契約に従う（失敗時は無変更）

### Scenario: organizer runの適用と同時に取り消さない

Given organizer runの適用（ORGANIZER lease）が実行中に、項目単位の逆操作がsubmitされた
Then 逆操作はMODEL_WRITER admissionでFIFOへdeferされ、lease解放後にadmission内の再検証（二段階目）を通って実行される
And 再検証で前提が崩れていれば書かずにtypedな失敗になる（spec 448と同一の契約）

### Scenario: 端末の格子が変わっていれば書かずに失敗する

Given 編集の後、undo前にgrid変更（列/行数の変化）が発生し、記録の元のcellが現在のdevice profileの範囲外になった
When 利用者が「元に戻す」をtapする
Then 書かずにtypedな失敗（現在の格子へ戻せない旨）を表示する

### Scenario: 上流のdrag削除のsnackbarは変わらない

Given 利用者がdragでアイテムを削除した（上流経路）
Then 上流の4秒snackbar（遅延commit取消）が従来どおり動作し、本Issueの取り消し記録は書かれない（fork編集のみが記録の対象）

## Failure and rejection vocabulary

逆操作・復元要求の失敗は閉じたtyped語彙で利用者へ示す（localized文字列はfork文字列慣行に従う）。生の例外や内部IDは表示しない。

- 対象が記録の結果と一致しない（移動済み・消滅等）: UNDO_STALE
- 復帰先（元の位置）が空いていない、または現在のdevice profile内に収まらない: UNDO_NO_SPACE
- 作成したフォルダの状態が変わっている（他の子を持つ・消滅・移動済み）: UNDO_FOLDER_CHANGED
- 復帰先アプリ等が端末に存在しない、またはavailability照合が不能（「外す」undoでのpackage消滅・deep shortcut消滅・検証失敗。fail-closed）: UNDO_ITEM_UNAVAILABLE
- 書込み自体の失敗: UNDO_WRITE_FAILED（ADR-0013契約3どおりtransactionで変更前へ戻る）
- 編集画面確定のundoは上記に加え、復元経路の拒否語彙（`NotRestorable` の理由、`WriterBusy` 等）を失敗表示へ対応づける。確定後に他の書込みがあった場合は `STALE_REVISION` の表示である

## Data and state

- **取り消し記録（process内、単一slot、永続化なし、世代識別子付き）**:
  - 項目単位entry: #448の `HomeEditUndoEvidence`（action種別、itemId、元のcontainer/screen/cell/span/rank、新しい配置、作成フォルダの参照と配置。`HomeEditUndoLog.kt:16-37`）を起点とし、**「外す」の逆INSERTに必要な削除前の行内容（行の再構築に必要な列。itemType、profile、起動先、title等）とavailability照合identity（component または package+shortcutId × profile）を含むよう拡張**した型。削除前行内容はadmission内（DELETE直前）にcaptureされ、`DirectEditContract.ResultCallback` の拡張引数で成功callbackへ運ばれ、記録へ格納される。
  - 編集画面entry: `RecoveryPointId` + 確定直後の現在revision（`expectedCurrentRevision` = apply経路が適用後検証に使ったmaterialized post-stateのrevision。apply receiptで確定完了時に受け取る）。
- 読むdata: 逆操作の照合・実行はspec 448と同じ経路（model thread上の現在状態投影 `DirectEditContract.Snapshot` / `HomeEditSnapshot`、availability照合入力、および `ModelWriter` のmodel/DB）。編集画面entryはrecovery store（既存の読み取り経路）。
- 書くdata: `favorites` 行（逆UPDATE、逆INSERT+フォルダ行DELETEの1 transaction、逆INSERT）。いずれも上流と同じ標準構造の行。`organizerLockState` 列は書かない（移動系の逆操作で不変。逆INSERTでは記録した元の行のlock値をそのまま再現する）。schema変更・migrationなし。
- 統計: undoのtapは上流の `LAUNCHER_UNDO` と同じ慣行で記録する（`DropTargetHandler.kt:87-90` のonUndoClickedの慣行。個人情報・itemIdを含まない）。

## Permissions, privacy, and security

- None。新規permission、外部通信、sensitive dataの追加なし。取り消し記録はprocess内の配置情報（および「外す」undoに必要な行内容）のみを保持し、log・diagnosticsへ itemId、package名、座標を出力しない（organizer-diagnosticsのredaction慣行に従う）。

## Accessibility and localization

- snackbarのlabelと「元に戻す」actionは既存の `Snackbar` の仕組み（文字列リソース、TalkBackで読めるTextView）を使う。上流の削除undoと同じ `R.string.undo` 系のaction文言を再利用できる。
- 表示時間は `AccessibilityManagerCompat.getRecommendedTimeoutMillis`（FLAG_CONTENT_TEXT | FLAG_CONTENT_CONTROLS）によるaccessibility設定準拠の実時間（既存実装を変えない。`Snackbar.java:184-188`）。
- 編集結果の通知は上流の慣行（`DragLayer.announceForAccessibility`。`DropTargetHandler` の削除通知の実例）に従い、undo失敗のTyped理由はspec 448と同じToast慣行（文字列リソース由来）で示す。
- 新規文字列は `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` へ追加する（fork文字列慣行）。

## Compatibility and migration

- schema変更・DB migrationなし。取り消し記録の保存先はprocess内のみ（preference・新規storeを作らない）。
- backup/restoreへの影響なし（書く行は上流と同じ標準構造）。
- `validate_writer_inventory.py` のwriter集約方針は不変（逆操作のSQLも `ModelWriter.java` に集約し、homeedit配下はDB書込みを持たない）。
- organizer側のpublic契約（`ApplyResult` / `RecoveryResult` / `RecoveryRequest`）は変更しない。undoからの `recover` 呼出しは既存のmutation entry（`LayoutApplicationModule.kt:310`）をfork側の既存application seam経由で流すのみである。apply経路のinternalな結果拡張（verified post revisionのreceipt運搬）はorganizer適用module内部の追加であり、public契約・apply/recoveryの振る舞い契約（spec 13）は不変である。
- `DirectEditContract.ResultCallback` の拡張（削除成功時のpayload引数追加）は、#448が作ったfork所有bridge契約の明示的拡張であり、`src/` 側の既存メソッドの振る舞いは変えない（impl場所はfork側 `HomeEditExecutor` とtestのみ）。
- 上流の `prepareToUndoDelete` 機構・drag削除snackbarは無変更。

## Dependencies

- **ADR-0013（#445、accepted）**: 逆操作の書込み契約の正本。要求テスト表の「Undoのfail-closed」行（状態ずれで書かずtyped失敗、正常系で元に戻る）は本Issueの実装PRが満たす（spec 448 AC-7が委任済み）。
- **#448（implemented）**: 項目単位entryの書き手（`HomeEditUndoLog`）。本Issueはrecord型の発展・逆操作・UIを実装する。evidence型の拡張（削除前行内容・availability identity）は本Issueの所有範囲（spec 448は「形式の詳細化は#450に委ねる」と明記）。
- **#449（implemented。PR #476、merge `29476b10a0`）**: 編集画面entryの書き手。接続点は本specで確定済み: 確定完了flow（`HomeEditSurfaceActivity.handleApplyResult` の `Applied` 分岐）がapply receiptで受け取ったentry（pointId + verified post revision）を書き、Undo snackbarをlauncherへ要求する。undo tapは本Issueの経路（organizer復元）へ流れる。復元・適用の安全契約の正本はspec 13（不変）。
- **#441（closed）**: B5の目標（1操作）と測定手順（§7）の正本。snackbar方式の4秒窓の有無を記録する（同書§4の追加規則）。
- **#440（closed）**: FR-020の反映先（`docs/product/requirements.md`）。

## Acceptance criteria

- [ ] AC-1: 2種の編集それぞれについて、直後のsnackbar Undo tap 1操作で元に戻ることが実機のスクリーンショットまたは録画で確認できる（項目単位は4アクションすべて。移動は元の位置・rank、新規フォルダはフォルダ消去込み、外すは行の再追加）。
- [ ] AC-2: 逆操作が実行前に現在状態との照合を通り、ずれ（対象の移動・消滅、復帰先の占有、作成フォルダの変化、grid範囲外、**「外す」のundoでのpackage消滅・deep shortcut消滅・availability照合不能**）で書かずにtypedな失敗を示すことを、失敗注入を含むテストで確認する（ADR-0013要求テスト表「Undoのfail-closed」行）。
- [ ] AC-3: 逆操作が1 DB transactionで成功するか変更前へ戻ることを、test DBでのテストで確認する（「新しいフォルダ」の逆操作=子の逆UPDATE+フォルダ行DELETEの組を含む。AGENTS.mdテスト規約）。
- [ ] AC-4: 逆操作が `LayoutWriteCoordinator` のMODEL_WRITER admissionを経由し、ORGANIZER lease中はdefer、lease解放後にadmission内再検証を通ることをテストで確認する（undoがorganizer runをまたがない）。
- [ ] AC-5: 編集画面確定のundoが、確定完了時にapply経路から受け取った `pointId + verified post revision`（materialized post-stateのrevision）でorganizer復元経路を呼び、`Restored` で確定前の状態へ戻ることを確認する。**新規フォルダの作成を含む確定でも、他の書込みがなければ正常系のundoが `Restored` になること**、記録されたrevisionがapply直後の新規captureのrevisionと一致すること（等価の固定。正本はapply内部値）、確定後に他の書込みがあった場合は `NotRestorable(STALE_REVISION)`・零書込みであること、復元点のevict・復元済み・writer busyをtypedに表示することを実装済みの#449適用完了flow（`HomeEditSurfaceActivity`）と接続した結合テストで確認する。
- [ ] AC-6: 取り消し記録の寿命がspecどおり動作する: 次のfork編集の成功で置換、undo試行終了で消費、process死で消失（再起動後にundo入口なし）。
- [ ] AC-7: 照合関数がinterface経由でテストされている（fixture、境界値、typed拒否理由、決定性。AGENTS.mdテスト規約。計画module層）。
- [ ] AC-8: ベンチマークB5: fork編集（項目単位、編集画面確定）の直後がundo 1操作であることをベンチマーク§7の手順で実測記録する。snackbar方式の時間窓（accessibility設定準拠の実時間）の有無を記録に含める。
- [ ] AC-9: アクセシビリティ: snackbar label/actionがTalkBackで読めること、表示時間がaccessibility設定に従うこと、undo失敗の理由が文字列リソース由来であることを確認する。
- [ ] AC-10: 文書: 本specが受入・実装を経て `implemented` となるとき、`CONTEXT.md`（domain language）、`DESIGN.md`（必要があればhomeedit moduleのundo構成の追記）、`docs/product/requirements.md`（FR-020のstatus更新）が更新される。
- [ ] AC-11: 世代紐付け: 取り消し記録が別の編集に置換済みのとき、置換前のrecordの世代識別子による消費要求は零書込みの無操作であり、現行recordを消費せず、現行編集が引き続きundo可能であることを、置換後のsnackbar表示前の窓を含む競合oracleのテストで確認する。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | エミュレータ操作記録（4アクション+編集画面確定のundo前後）+ 実機はowner確認 |
| AC-2 | homeedit JVM test（照合関数のtyped拒否。availability照合を含む）+ instrumentation failure注入（shared-writer lane。`DirectEditUndoModelWriterTest` への追加。package消失・shortcut消失の零書込みoracleを含む）。削除前行payloadがadmission内captureからcallback経由でrecordへ格納されることのoracleを含む |
| AC-3 | instrumentation: test DBでの逆操作transaction test（2行目失敗でrollback、model/DB一致） |
| AC-4 | instrumentation: shared-writer lane（ORGANIZER lease中のdefer→解放後のstage-2再検証。spec 448 AC-5と同じseam） |
| AC-5 | #449適用完了flowとの結合test（新規フォルダ作成込みの正常系 `Restored`、記録revisionとpost-apply capture revisionの一致、`STALE_REVISION`・evict・復元済み・WriterBusyの失敗表示を含む）+ エミュレータ操作記録 |
| AC-6 | JVM test（record holderの置換・消費）+ instrumentation process-death smokeの慣行 |
| AC-7 | `tests/unit/app/lawnchair/homeedit/` のJVM test（`organizer-unit-tests` gate。ci.yml:360） |
| AC-8 | ベンチマーク§7のagent手順による実行記録（PR本文） |
| AC-9 | JVM test（文言がリソース由来かつ空でない）+ エミュレータTalkBack記録 + owner実機確認 |
| AC-10 | `validate_repo_contract.py` 成功 + diff確認 |
| AC-11 | JVM test（record holderの世代不一致compare-and-consume。置換後のrecordが無消費・現行undo可能であること）+ executor層の競合oracle（stale tokenでのstartは零書込みの無操作） |

## Open questions

- **snackbarのlabel文言**: 編集種別をlabelへ出すか、共通文言にするかは実装PRで確定してよい（観測可能な振る舞いの要件は「undo actionが1tapで直前の編集を戻す」であり、label文言の別解は振る舞いを変えない）。ただし多言語（en/ja）でsnackbar幅に収まることを実装時に確認する。

（#449との接続はRevision 2で確定済み（expectedCurrentRevisionの正本・書込み时机・接続点）。open questionは残っていない）

## Change history

- 2026-09-28: Draft created for #450。出典: Issue #450本文、ADR-0013（#445受入）、ADR-0014（#447受入）、spec 448（implemented、PR #472）、ベンチマーク正本（#441確定）。Issue本文の未解決事項4件（外すundo方式、記録の保存先、snackbar単一表示、復元点evict時挙動）を本specで決定した。
- 2026-09-29: Revision 2 — Phase 1 re-entry + review round 1（[判定](https://github.com/nunu1733/NunuLauncher/issues/450#issuecomment-5873360451): Request changes）の3指摘対応と依存状態の再照合。指摘1（高）: 取り消し記録に世代識別子を導入し、snackbar actionとのcompare-and-consume契約・世代不一致時の零書込み無操作（現行recordを消費しない）をScope/Scenario/AC-11へ追加。指摘2（高）: 編集画面undoの `expectedCurrentRevision` の正本を「確定完了時に適用計画から `revisionOf(intendedState)` で決定」に固定し、post-hoc captureを禁止（根拠: `Applied` のexact-DB検証契約 `ApplyProtocol.kt:375-381` と復元のrevision照合契約 `RecoveryProtocol.kt:113-116`。「確定後に別書込み→`STALE_REVISION`零書込み」のScenario・AC-5を追加。`intendedRevisionOf` のdigest種違いを明記）。指摘3（中）: 「外す」undoのavailability照合入力（stage-1/stage-2同一判定・verifier注入・fail-closed）をScope/Failure vocabulary/AC-2へ追加し、package・shortcut消失の零書込みoracleをtest oracleへ追加。再照合: #449がimplemented（PR #476）となった現行main（`be7576c30a`）へ追従し、依存節・接続点（`handleApplyResult` の `Applied` 分岐）・`recover` 呼出し経路を確定。行番号根拠を現行mainで再検証。
- 2026-09-29: Revision 3 — Phase 1 re-review round 2（[判定](https://github.com/nunu1733/NunuLauncher/issues/450#issuecomment-5882646790): Request changes。round 1指摘1・3は解消認定）の残存2指摘に対応。指摘1（高）: `expectedCurrentRevision` の正本を **apply経路が適用後検証に使ったmaterialized post-state（`MaterializedWriteSet.intendedState`）のrevision** へ修正（Revision 2の「plan.intendedStateから決定」は、新規フォルダのplanned ref→persistent ref解決（`LauncherLayoutAdapter.kt:215,231`）とpage正規化（`:239`）により正常系でも一致しないため撤回）。apply内部（`Applied` 組み立て点・lease保持中）で計算し、internalなapply receiptでpointIdと同時に運ぶ（post-hoc captureは引き続き禁止）。public契約は不変。新規フォルダ作成込みの正常系undo `Restored` と記録revision≡post-apply capture revisionの結合oracleをAC-5へ追加。指摘2（中）: 削除前行payloadのplatform→fork運搬seamを `DirectEditContract.ResultCallback` の明示的拡張（削除成功時のみ非nullのpayload引数）として固定し、admission内capture→成功callback→record格納の経路とoracleをScope/AC-2/planへ記載。
- 2026-09-29: `accepted` 化。Phase 1 re-review round 3（[判定](https://github.com/nunu1733/NunuLauncher/issues/450#issuecomment-5882787921): **Clear**）により、Revision 3の2指摘がいずれも現行実装のseamに沿った実装可能な契約とtest oracleまで含めて解消と認定された。round 3の非blocking指摘（plan Change set表の `ApplyProtocol.kt` path誤記）は本受入commitで修正。
- 2026-10-01: statusを `implemented` へ遷移（遡及記録）。実装は [PR #481](https://github.com/nunu1733/NunuLauncher/pull/481)（2026-09-30 merge、Phase 2 re-review round 15 Clear）。独立audit（`docs/assessment/pr-481-edit-undo.md`）とIssue #450の終了条件確認、`docs/product/requirements.md` のFR-020 `implemented` 更新済みを根拠とする。本specのstatus遷移のみ漏れていたため本PRで揃えた。
