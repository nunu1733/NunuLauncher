---
issue: "#448"
status: draft
requirements:
  - FR-018
  - NFR-013
  - NFR-014
  - D-013
updated: 2026-09-27
---

# 項目単位の編集アクション（ページへ移動 / フォルダへ入れる / ホームから外す）

> Risk tier: H — #448（メモ§4.7）が「新しい書込み経路を持つため階層H」と定めている。現行workflowの階層H条件「Launcher DBへの新しい書込み経路を作る」（直接編集の書込み、ADR-0013契約4の最小の`ModelWriter`操作追加）と「上流のmodel/loaderへのbridgeを作るまたは変える」（`ModelWriter.java`への操作追加）に当たる。手順は現行どおり（accepted spec + plan.md、Execution and approval contract、`risk: layout-data` labelによる高リスク独立エビデンス契約）。
> Epic: #439（再焦点化）。出典: 再焦点化方針メモ（2026-09-24に承認、Revision 5。以下「メモ§x」）。書込み契約は [ADR-0013](../../docs/adr/0013-direct-edit-write-contract.md)（#445で受入済み）を参照するのみであり、本specで重複定義しない。
> ベンチマークの正本: [editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md)（#441で確定。baseline B2=48 / B3=21 / B4=25、目標はNow-2全体で B2≤24 / B3≤10 / B4≤12）

## Problem

長押しで選んだ1個のアイテムを、別ページやフォルダへ移す・ホームから外す操作がdragにしか存在しない。ページをまたぐ移動は「長押し → 指を隣ページの座標範囲へ運ぶ → drop」であり（`src/com/android/launcher3/Workspace.java` のdrop確定経路、ベンチマーク§3.1）、ページをまたぐdragを繰り返すB2/B3/B4が日常編集負担の主要因である。特に「ホームから外す」は長押しpopupに存在しない（`lawnchair/src/app/lawnchair/LawnchairLauncher.kt` の `getSupportedShortcuts` が登録するのは UNINSTALL / CUSTOMIZE /（recents有効時）PAUSE_APPS / placement lockのみ。UNINSTALLはアンインストールであって「外す」ではない）。非dragの単一アイテム書込みは上流に実例がある（accessibility経由の `MOVE_TO_WORKSPACE` → `ModelWriter.moveItemInDatabase`、フォルダへの外部drag参加 → `addOrMoveItemInDatabase`）が、操作面として利用者に届いていない。

## Outcome

利用者がホーム画面のアイテム（ワークスペースとホットシート上のアプリ・ショートカット）を長押しすると、popupに3つの編集アクションが現れる。

- **「ページへ移動…」**: 対象ページを選ぶと、そのアイテムがそのページの空きセルへ移動する。
- **「フォルダへ入れる…」**: 既存フォルダを選ぶか「新しいフォルダ」を選ぶと、そのアイテムがそのフォルダへ入る（新規フォルダの場合は置き先ページも選ぶ）。
- **「ホームから外す」**: アイコンがワークスペースから消える。アンインストールはしない。

各アクションは即座に反映され（NFR-013）、ADR-0013の書込み契約（1アクション = 1 DB transaction、二段階の副作用のない検証、MODEL_WRITER admission内での完結、失敗時は無変更でtypedな理由）に従う。アクションの振る舞い（どこへ動かすかの計算と検証）はfork側module `app.lawnchair.homeedit` に実装され、第2段の視覚的編集画面（#449）からも同じ計算が呼ばれる。各アクションの実行時にはUndoに必要な情報が記録され、Undo本体と寿命は#450が所有する。

## Scope

- **編集アクションmodule（`app.lawnchair.homeedit`。本Issueが最初の実装を所有）**:
  - **純粋計画関数（UI・DB・model状態に触れない）**: 入力は「現在のレイアウトの投影（snapshot。device profileの格子、ページ順とscreenId、各アイテムのcontainer/screen/cell/span/type/profile、フォルダの存在と所属と件数）」「対象アイテムの識別（itemId、現在のcontainer/screen/cell）」「意図（移動先ページ、移動先フォルダ、新規フォルダ、または外す）」。出力はclosed result（`MoveToPage` / `AddToFolder` / `CreateFolderAndAdd` / `Remove` の各成功plan、または `Reject` とtypedな理由）。保存（conservation）・重なりなし・device profile内・container参照・profile分離を検証する（メモ§4.2条件2）。
  - **即時書込み経路（本Issue。ADR-0013に従う）**: 計画の確定から `ModelWriter` 同経路に追加する最小操作（ADR-0013契約4どおり「validation → MODEL_WRITER admission → admission後の再検証 → model/DB変更」の順序がadmissionの内側で完結する構造）での書込み、結果のtypedな通知まで。既存 `ModelWriter` メソッドがadmission前に `ItemInfo` を変更する構造（`ModelWriter.java` の `updateItemInfoProps` が `executeOnModelThread` より先）を踏まない。
  - **一括確定経路の受け皿（#449が使う。本Issueではinterfaceの分離のみ）**: 純粋計画関数とsnapshot投影は即時書込み経路から独立しており、#449は同一の計算を視覚的編集画面から呼び、適用はorganizerの安全な適用経路で行える（メモ§4.3「計算を共有し、書き方だけが異なる」）。本Issueは#449用のUI・適用・一括書込みを実装しない。
  - **Undo記録（情報の記録のみ。実装・寿命・UIは#450が所有）**: 各アクションの成功時に、逆操作に必要な情報（対象itemId、元のcontainer/screen/cell/rank/span、移動先、新規作成したフォルダの参照とその置き先、「外す」対象の削除前配置）をprocess内に記録する。形式の詳細化・複数段・永続化は#450に委ねる。
- **popupの3つのsystem shortcut**（既存の `SystemShortcut.Factory` 仕組み。手本は `OrganizerLockShortcut.PLACEMENT_LOCK`。追加はfork側拡張点 `LawnchairLauncher.getSupportedShortcuts()` への追加分のみで、上流ファイルの変更を想定しない。発生した場合はNFR-010の記録対象とする）:
  - **対象アイテムの絞り込み**: placement lockと同じ方針（`ITEM_TYPE_APPLICATION` / `ITEM_TYPE_DEEP_SHORTCUT` かつ `itemInfo.id != NO_ID`）。widget、フォルダ自身、app pairは対象外。フォルダ内アイテムは本codebaseではpopupが出ない構造である（`Folder` がlong-clickでdragを開始する。`src/com/android/launcher3/folder/Folder.java` の `beginDragShared`）ため、popup経由のアクションは自然にワークスペースとホットシート（非taskbar）上のアイテムに限られる。
  - **「ページへ移動…」**: 既存ページの一覧dialog（fork側。既存のAlertDialog慣行に従う。実例 `OrganizerLockShortcut` のconfirmDialog）。選ぶと、純粋計画関数がそのページ内の空きセルを決定的に探索して移動先を決め、即座に書き込む。移動成功後は移動先ページを表示する。候補は既存ページに限る（「新しいページ」はNon-goals）。
  - **「フォルダへ入れる…」**: 既存フォルダ一覧（同じprofileのフォルダのみ）+「新しいフォルダ」のdialog。既存フォルダへの追加はrank末尾（`FolderInfo.add` のrank管理に対応）。新規フォルダは対象アイテムの現在のcontainer・現在のセルに1x1で作り、アイテムをrank 0で入れる（dragの `createUserFolderIfNecessary` が「相手アイテムのセル」を使うのに対し、popup経由では相手がいないため、空く対象セルをそのまま使う）。フォルダ行のINSERT + 子の移動のUPDATEを1つの明示的transactionに包む（ADR-0013契約3）。
  - **「ホームから外す」**: 上流の削除と同じ遅延commit構造（`prepareToUndoDelete` / `commitDelete` / `abortDelete`）を再利用し、上流のRemoveと同じ4秒snackbarのUndoを表示する（#450のUndo統合前の暫定保護であり、誤操作の保護水準を上流drag-removeと揃える。窓の変更・統合Undoは#450のscope）。行のDELETEはcommit時にadmission内で再検証を通過した後の1回の原子的書込みである。アンインストール・フォルダ中身の暗黙削除をしない（ADR-0013契約6）。空フォルダの残置は [spec 24](../24-empty-folder-policy/spec.md) の方針（preserve default）に従う。
- **ロックの扱い**: 直接編集はロックを妨げない（メモ§4.2、ADR-0013 Decision「ロック（既定案の確定）」）。ロック済みアイテムへの操作時は、dialog内にその旨を示す1行を表示する（「外す」の場合はロックも削除される旨）。表示にはADR-0004の `LockEffectNote` の説明機構（`EffectiveLocks.kt`）の既存のtyped note/localized mappingの慣行に従う。ロック列 `organizerLockState` は移動で不変、削除で行とともに消える。書込みがロック列を書くことはない。
- **ADR-0014との照合**: 第1段のpopup操作面は #447（ADR-0014）の決定に依存しない。本specのmodule分担（純粋計画の共有 + 2書込み経路の分離、popup経路のbridge最小化）は、ADR-0014草案（refocus-drafts、#447で起草中）の要件（第1段popup / 第2段視覚的編集画面、第2段の適用はorganizerの安全な適用経路）と矛盾しない。#447のADR受入時に矛盾が判明した場合はspecを更新する。

## Non-goals

- 複数選択・一括適用（#449の視覚的編集画面）。
- 編集アクションの振る舞いの再実装（#449は本moduleの計算を共有する）。
- Undoの実装・窓・寿命・UIの定義（#450が所有する。本Issueは上流の削除snackbar再利用とUndo記録の情報要件のみ）。
- 上流のdragによる手動移動の取り消し（Next。メモ§4.5）。
- 新規アプリの配置先（#446 / ADR-0015）。
- 移動先のきめ細かい指定（セル座標の直接指定、Dockへの移動、widgetの移動）と「新しいページ」の作成。空きセルへの自動配置に限る。根拠: (1) 上流accessibility経路の空ページ生成（`addExtraEmptyScreens` / `commitExtraEmptyScreens`）はUI先行の構造であり、ADR-0013契約4のadmission内完結構造にそのまま載らない、(2) B2の計測課題（ページ0 → 既存ページ3）は既存ページで達成可能、(3) 既存ページの空きがない場合のtyped拒否と理由表示で利用者は次の手を知れる。「新しいページ」の追加は後続Issue（#449またはメモNext）へ分離する。
- organizerのplanner、提案生成、AI交換（凍結。#443）への変更。
- 上流のaccessibilityアクション（`LauncherAccessibilityDelegate` のMOVE/REMOVE/MOVE_TO_WORKSPACE）とkeyboard shortcutの変更（上流経路のまま）。

## Domain language

- **編集アクション (Edit Action)**: 利用者が長押しpopupで明示的に選んだ、1個の配置アイテムへの単一の即時変更（ページへ移動、フォルダへ入れる、ホームから外す）。ADR-0013対象(a)。_Avoid_: 適用 (apply)（organizerのplan適用と混同）、整理 (organization)
- **編集意図 (Edit Intent)**: 編集アクションの対象と移動先の組（対象itemId + 移動先ページ / フォルダ / 外す）。純粋計画関数への入力。_Avoid_: 意図 (UserReviewedIntent)（lock authoringの語）、plan（organizerのplan artifactと混同）
- **編集snapshot (Edit Snapshot)**: 1回の編集意図の計画と検証のために、現在のホームレイアウトを投影した読み取り専用の入力。organizerのレイアウトsnapshotより小さく、単一操作の検証に必要なfieldのみを持つ。_Avoid_: Layout Snapshot（organizer runの入力。別の粒度）

（承認時に `CONTEXT.md` へ反映する）

## Behavior scenarios

### Scenario: 「ページへ移動…」でアイテムを既存ページへ動かす

Given ページ0にアプリAが置かれ、ページ2に空きセルが存在する
When 利用者がAを長押しし「ページへ移動…」→ ページ2を選ぶ
Then Aのfavorites行がページ2の空きセルへ1回のupdateで移り、Aのアイコンはページ2へ表示される
And 移動先は事前に利用者へ選択dialogで示したページであり、行のcontainer/screen/cell以外の値（intent、title、`organizerLockState`を含む列）は変化しない
And 成功後、移動先ページが表示される

### Scenario: 「ページへ移動…」で空きのないページを選ぶ

Given 選んだページにAのspanと同一に空けられるセルが存在しない
When 利用者が「ページへ移動…」→ そのページを選ぶ
Then favorites行は変化せず、Aは元の位置に残る
And 利用者へtypedな拒否理由（空きがない旨）が表示される

### Scenario: 「フォルダへ入れる…」で既存フォルダに入れる

Given ページ0にアプリA、ページ1にフォルダF（同profile）がある
When 利用者がAを長押しし「フォルダへ入れる…」→ Fを選ぶ
Then Aの行のcontainerがFのidに変わりrankが末尾になり、1回のupdateで書かれる
And Fの表示件数が増え、AはFの中に表示される
And Aの元のセル（ページ0）は空く

### Scenario: 「フォルダへ入れる…」で新しいフォルダを作る

Given ページ0のセル(x,y)にアプリAが置かれている
When 利用者がAを長押しし「フォルダへ入れる…」→「新しいフォルダ」→ 置き先ページを選ぶ
Then 1つのtransaction内で、フォルダ行が1行INSERTされ、Aの行がそのフォルダのcontainerへUPDATEされる
And フォルダは置き先ページの空きセルに1x1で置かれ、Aはrank 0でその中に入る
And transaction内の失敗ではフォルダ行もAの行も変化しない

### Scenario: 「ホームから外す」

Given ページ0にアプリAが置かれ（アンインストール対象ではない）
When 利用者がAを長押しし「ホームから外す」を選ぶ
Then Aのアイコンはワークスペースから消え、上流の削除と同じ4秒snackbarが現れる
And snackbarがtimeoutするとAのfavorites行が1回のdeleteで消える（アンインストールはしない）
And snackbar内のUndoを選ぶとAの行は消えない（上流の `abortDelete` と同じ無書込みでの取り消し）
And Aがアプリ・ショートカットである限り、ランチャー内の他の配置（他フォルダの中身等）は一切変化しない

### Scenario: 書込み前の検証失敗で書かない

Given 直前のorganizer適用等により、dialogが表示した時点と現在の状態がずれている（対象アイテムが移動済み・フォルダが削除済み・配置先が埋まった等）
When 利用者がアクションを確定する
Then 書込み依頼時の検証（一段階目）で拒否され、model/DBは変化しない
And 利用者へtypedな拒否理由が表示される

### Scenario: admission後の再検証で書かない（defer後のstale）

Given organizer runの適用（ORGANIZER lease）が実行中である
When 利用者がアクションを確定する
Then 書込みは `LayoutWriteCoordinator` のMODEL_WRITER admissionでFIFOへdeferされる
And lease解放後、admission内で現状態に対する再検証（二段階目）が行われる
And 対象rowまたは配置先がorganizer適用で変化していた場合、model/DBは一切変化せず（admission成立までmodel/DBへの変更は発生しない）、typedな失敗が通知される
And 現状態でも成立する場合は、admission内で検証→変更が完結して書かれる

### Scenario: 複数行アクションの途中失敗で全rollback

Given 新規フォルダ作成（フォルダ行INSERT + 子UPDATE）の書込みが2行に及ぶ
When 2番目のwriteが失敗する（test注入）
Then transaction全体がrollbackし、フォルダ行もAの行も変化せず、model（`mBgDataModel`）もDBと一致したままになる
And 失敗はtypedに通知され、握りつぶされない

### Scenario: 対象外のアイテムにアクションを出さない

Given 長押しされたのがwidget、フォルダ自身、app pair、または未保存アイテム（`id == NO_ID`）である
When popupが構築される
Then 3つの編集アクションはいずれも現れない

### Scenario: ロック済みアイテムへの操作

Given Aの `organizerLockState` がLOCKEDである
When 利用者がAへ「ページへ移動…」を確定する
Then 移動は拒否されず、dialogの表示にロック中であることが示され、移動後も行のロック列は不変である
And 「ホームから外す」の場合、dialogの表示にロックも削除される旨が示される

## Data and state

- 読むdata: Launcher DB（`favorites`。model threadの `BgDataModel` と、screen順の権威はmodel）、device profileの格子寸法、フォルダのtitle/件数、ロック状態（既存の読み取り経路）。新規の権威を作らない。
- 書くdata: `favorites` 行のupdate（移動・フォルダ追加）、INSERT（新規フォルダ行）+ UPDATEの1 transaction（新規フォルダ）、DELETE（外す）。schema変更・migration・backup/restore契約への影響なし（書く行は上流と同じ標準構造）。`organizerLockState` 列は書かない。
- 一時的なstate: 編集snapshotは計画・検証の間だけのprocess内値であり永続化しない。Undo記録はprocess内であり、寿命・UI・複数段は#450が所有する（本Issue内では直近の記録保持のみ）。
- 対象集合とitem typeの扱い: 対象は `ITEM_TYPE_APPLICATION` / `ITEM_TYPE_DEEP_SHORTCUT`（idあり、workspace/hotseat配置）。widget・フォルダ・app pair・folder内アイテム・他profileは対象外。profile分離は移動先フォルダのprofile一致で検証する。

## Permissions, privacy, and security

- None。新規permission、外部通信、sensitive dataの追加はない。書込み先は端末内のLauncher DBのみ。

## Accessibility and localization

- 3つのshortcutには既存の `SystemShortcut` と同じラベル/iconの仕組みを使い、TalkBackで読めるラベルを持つ。
- 対象選択dialogは既存のAlertDialog慣行に従い、選択肢（「ページN」「フォルダ名」「新しいフォルダ」）、拒否理由、ロック注記が支援技術で読める。font scalingで崩れない。
- 新規文字列は `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` へ追加し、既存のfork文字列慣行（`organizer_lock_*` 等）に従う。
- 上流のaccessibilityアクション・keyboard shortcutは変更しない。popup経路とaccessibility経路の書込みが重なっても、どちらも単一アイテムのmodel書込みであり不変条件は保たれる。

## Acceptance criteria

- [ ] AC-1: 長押しpopupに3つのアクションが、対象絞り込み条件（`ITEM_TYPE_APPLICATION` / `ITEM_TYPE_DEEP_SHORTCUT`、`id != NO_ID`）どおりに表示される。対象外（widget、フォルダ、app pair、未保存アイテム）には表示されない。エミュレータのスクリーンショットで構造を確認し、実機での表示・操作をownerが確認する。
- [ ] AC-2: 「ページへ移動…」が既存ページへの空きセル自動配置で動作し、空きがない場合・対象が消えた場合等は無変更でtypedな拒否理由を表示する。移動は1回のupdateである。
- [ ] AC-3: 「フォルダへ入れる…」が既存フォルダ（rank末尾、同profile検証）と新規フォルダ（対象セルに1x1、INSERT+UPDATEを1 transaction）で動作する。transaction途中失敗で全rollbackし、model/DBが一致する。
- [ ] AC-4: 「ホームから外す」が上流と同じ遅延commit削除（4秒snackbar、Undo選択で無書込み取り消し、timeoutで1回のDELETE）で動作し、アンインストール・他アイテム（フォルダ中身を含む）の暗黙削除をしない。
- [ ] AC-5: すべての書込みがADR-0013契約に従う。(a) admission成立より前にmodel/DB・`ItemInfo`の変更・ID採番・bind callbackが発生しない、(b) 純粋計画関数による書込み前検証とadmission後の再検証の二段階があり、どちらも満たさなければ書かない、(c) `LayoutWriteCoordinator` のMODEL_WRITER admissionを経由し、ORGANIZER lease中はdefer、lease解放後に再検証→書込みが完結する、(d) 1アクション = 1 DB transaction（複数行は `newTransaction()`、失敗時rollback、握りつぶしなし）。
- [ ] AC-6: 純粋計画関数がinterface経由でテストされている（fixture、境界値、typed拒否理由、決定性、冪等性。AGENTS.mdテスト規約）。テストは既存の `organizer-unit-tests` gateで実行される。
- [ ] AC-7: ADR-0013要求テスト表のうち本Issueの行（途中失敗の注入、transaction rollback、admission後の再検証、organizer runとの排他、process死、破壊・復旧の組合せ）が成功する。Undo fail-closed行は#450の実装PRで満たす。
- [ ] AC-8: `app.lawnchair.homeedit` が純粋計画（#449共有）と即時書込み経路（popup）を分離したinterfaceを持ち、popup経路からのみADR-0013の書込みが行われる構造である。一括確定適用（#449）の実装は含まない。
- [ ] AC-9: 各アクション成功時にUndo記録の情報（対象itemId、元のcontainer/screen/cell/rank/span、移動先、新規フォルダの参照と置き先、削除前配置）がprocess内へ記録される。本体・寿命・UIは#450に委ねる旨が文書に記録されている。
- [ ] AC-10: NFR-013の応答性: 排他なしの通常時、アクション確定から視覚反映までが即時である（目標1秒以内。エミュレータ計測値をPRに記録し、実機計測をowner確認に含める）。ORGANIZER lease中のdefer時は、反映がlease解放後になることを明記する（遅延中の追加の進捗表示は作らない）。
- [ ] AC-11: ベンチマーク: B2〜B4の合否はNow-2全体（#448+#449のうち適した方）で判定するため本Issueでは確定しない。B2の第1段単独の重み付きコスト（目安: 1個あたり 長押し2 + tap 1 + 対象ページ選択 1 = 4、5個で20）をエミュレータでの実行記録（ベンチマーク§7の手順に従う）として残し、baseline 48からの削減幅を記録する。
- [ ] AC-12: patch surface: PR上で `measure_upstream_patch_surface.py --target HEAD --enforce-baseline` を実行し、結果をPR本文に記録する。src/側の新規・変更ファイル（bridge）がbaseline比で増える場合、NFR-010としてPRで記録する。
- [ ] AC-13: 文書: specが `implemented` になり、`DESIGN.md`（homeedit moduleの位置づけと、直接編集のgate 2系統（ADR-0013）行）、`CONTEXT.md`（domain language 3語）が更新される。高リスクpath一覧へのhomeedit追加要否の判断（本specは「fork側homeeditはDB書込みを直接行わないため追加なし。書込みは既に一覧内の `ModelWriter.java` に集約」と決定。`validate_writer_inventory.py` のscanがhomeedit配下のDB書込みpatternを検出した場合はCI failで機械的に露見する）をplanへ記録する。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | エミュレータスクリーンショット（popup表示・対象外非表示）+ owner実機確認。popup構築のJVM test（絞り込み述語） |
| AC-2 | homeedit純粋計画のJVM test（空きセル探索・境界・拒否理由）+ instrumentation書込みtest（1回updateの検証）+ エミュレータ操作記録 |
| AC-3 | 同上 + instrumentation failure注入test（2行目失敗でrollback、model/DB一致） |
| AC-4 | instrumentation test（遅延commit、abort無書込み、commit DELETE、周辺行不変）+ エミュレータ操作記録 |
| AC-5 | instrumentation（shared-writer lane）: defer後stale検証（ADR-0013要求テスト表のとおり）、admission前無変更、coordinator排他。homeedit JVM test（二段階検証の同一関数性） |
| AC-6 | `tests/unit/app/lawnchair/homeedit/` のJVM test群。`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.homeedit.*'` |
| AC-7 | instrumentation: `ModelWriterTransactionReentryTest` 等と同じshared-writer laneへ追加したtest class群。process死は既存のprocess-death smokeの慣行に従う |
| AC-8 | homeedit package構造のJVM test（計画moduleがmodel/DB/UI型をimportしないことの構造検証またはPublicSeamShapeTest相当） |
| AC-9 | Undo記録のJVM test（各アクション結果にevidenceが載ること。寿命は試験しない） |
| AC-10 | エミュレータでの操作計測（確定→反映の記録）+ owner実機確認 |
| AC-11 | ベンチマーク§7のagent手順によるエミュレータ実行記録（PR本文） |
| AC-12 | `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` の出力（PR本文） |
| AC-13 | `validate_repo_contract.py` 成功 + diff確認（DESIGN.md/CONTEXT.md/spec status） |

## Open questions

- なし（承認時点で解決済み）。移動先セルの決め方（空きセル自動配置・決定的探索、同位置になる計画は拒否）、フォルダ内アイテムの対象外（popup構造上出ない）、新規フォルダの置き先（対象アイテムの現在container・現在セル）、「新しいページ」の非対象、widget/フォルダ自身/app pairの非対象、外すの確認なし+上流snackbar再利用、は本specで決定した。

## Change history

- 2026-09-27: Draft created for #448（Phase 1）。出典: Issue #448本文 + 承認済み再焦点化方針メモ（Revision 5）§4.2/§4.3/§4.8/§4.9、ADR-0013（#445受入）、ベンチマーク正本（#441確定）、ADR-0015（#446受入）のbridge書込み構造先例。
