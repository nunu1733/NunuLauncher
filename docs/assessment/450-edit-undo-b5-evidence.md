# Issue #450 Edit Undo — 実行証跡（AC-1 / AC-9 / B5）

> Status: recorded（2026-09-30 round 13。agent実行記録。実機owner確認はowner decision item）
> Issue: [#450](https://github.com/nunu1733/NunuLauncher/issues/450)（FR-020、D-013）
> 正本: [editing-burden-benchmark](../engineering/editing-burden-benchmark.md) §4（snackbar方式の時間窓の記録規則）、§6（B5 baseline=削除1/移動8、目標=1操作）、§7（agent実行可能な検証手順）

## AC判定

エミュレータ `nunu_qpr2_api36_1`（API 36、en-US、`com.android.chrome` provision済み、本アプリをhome roleに設定）での実UI操作（実編集 → 実snackbarのUndo tap 1回）に基づくAC別の判定。toolingのpass数とは分離して記載する（review round 13 Finding 4）。成果物は `docs/assessment/450-edit-undo-ac1-screenshots/`。

| AC | 項目 | 判定 | 根拠と成果物 |
|---|---|---|---|
| AC-1（項目単位） | 移動（別ページへの移動・実cross-pageシーケンス） | **pass** | 移動元ページ表示中に実編集 → 別ページへ移動 → snackbarが再bind後も存続（round 12修正。旧実装は約500msで自己消滅）→ 実Undo tap → 元page-0セルへ復元。`direct-move-before.png` / `direct-move-after.png` |
| AC-1（項目単位） | 既存フォルダ追加 | **pass** | 実追加 → 実Undo tap → デスクトップ復帰。`direct-add-to-folder-before.png` / `direct-add-to-folder-after.png` |
| AC-1（項目単位） | 新規フォルダ作成（popup経路・#448） | **pass** | round 13修正（persisted OPTIONS bit）によりフォルダがundo窓内で存続 → 実Undo tap → 子の元配置復帰 + フォルダ行削除。`direct-create-folder-before.png` / `direct-create-folder-after.png` |
| AC-1（項目単位） | ホームから外す | **pass** | 実削除 → 実Undo tap → 新idでの再挿入（元配置）を配置+起動targetで確認。`direct-remove-before.png` / `direct-remove-after.png` |
| AC-1（編集画面確定・#449経路） | 移動 | **pass** | 実`confirm()` → 実snackbar tap → 復元（recovery経路）。`session-confirm-before.png` / `session-confirm-after.png` |
| AC-1（編集画面確定・#449経路） | 新規フォルダ作成（2項目選択） | **pass** | AC-5正常系: 実`confirm()` → receipt revision == 適用直後のfresh capture revision → undo（executor経由）→ `Restored` → 全pre-apply itemが元配置へ復帰 + 作成フォルダ行消失。round 13のadapter正規化修正により成立（下記「round 13対応の製品修正」）。oracle: `EditSurfaceUndoInstrumentationTest.productionConfirmFlowWithNewFolderUndoRestoresToThePreApplyState`（同class計18 tests green のうちの1つ） |
| AC-1（編集画面確定・#449経路） | 新規フォルダ作成（1項目選択） | **未対応** | 適用がself-recoverする（VERIFICATION_FAILED → レイアウト変更なし相当の表示）。organizer作成フォルダにはOPTIONS bitが付かないため、bind-time single-child cleanup書込みが適用のpost-write検証と衝突する。2項目がサポート対象。owner triage（#449表面）。下記「owner triage item」 |
| AC-9 | TalkBack | **pass** | TalkBack有効（service bound）で実snackbarのlabel/actionがaccessibility treeに現れ、actionはfocusable/clickable + `ACTION_FOCUS`受諾（round 12記録から変更なし）。`ac9-talkback-snackbar.png` / `ac9-accessibility-dump.xml` / `ac9-node-record.txt` |

各`before`画像は「実snackbarが表示されている状態（`Edit applied` + `Undo`）」、各`after`画像は「Undo tap 1操作後の状態」である。capture成功はtoolingがassertし、capture失敗はtest失敗になる（握りつぶしなし）。

## tooling実行状態

evidence tooling `HomeEditUndoEvidenceToolingTest`（CI lane外のon-demand tooling。#376 cold-process evidenceと同一扱い）を本HEADで1回実行し、**8 tests / 全green**。上表の実UI系列に加え、次の回帰oracleを含む:

| oracle | 内容 |
|---|---|
| reload生存 | `directEditCreatedFolderSurvivesReload` — snackbarを期限切れにした後のreloadでも、persisted OPTIONS bitによりpopup作成の1子フォルダがflattenされない |
| 抑止スコープ回帰 | `singleChildFolderCleanupStillFlattensNonDirectEditFolders` — direct-edit由来でない1子フォルダは従来どおりlauncher自身によりflattenされる（cleanup無効化していないことの回帰oracle） |

書込み面の回帰oracleは `DirectEditUndoModelWriterTest`（11 tests。`DirectEditModelWriterTest` 6 testsと合算で17）: `createFolderPersistsTheDirectEditCreatedOptionsBit` が作成行のOPTIONS bit永続化を、`moveAndRemoveDirectEditsDoNotSetTheCreatedFolderOptionsBit` が移動・外す経路でのbit不在をassertする。

## round 13対応の製品修正（本HEADに含む）

1. **フォルダ子の事前正規化（adapter。AC-5編集画面新規フォルダundoの修正）**: organizer adapterはそれまでフォルダ子のcellX/cellYをNULLでmaterializeしていた。launcherのフォルダbind（`Folder.bind` → `updateItemLocationsInDatabaseBatch(true)`）は各子をrank順indexの `FolderGridOrganizer` 位置と照合し、不一致を `moveItemsInDatabase` で書き戻す（wall-clockの `modified` 更新を伴う）。適用直後にこの正規化書込みが走るとcanonical revision operandがreceipt計算後へ変わり、recovery revision照合が不一致になり、正当なcreate-folder undoが `STALE_REVISION` 拒否されていた（DB dumpで確認済みの根本原因）。修正: `LauncherLayoutAdapter.rowFor` がlauncher同等の正規化grid cellを書くようになった（`FolderGridOrganizer.calculateGridSize`/`getPosForRank` のfork側port）。bind時の正規化はno-op（verifierが不一致なし → 書込みなし → `modified` 更新なし）となり、receipt revisionが適用後の実状態を記述し続ける。canonical `FolderChild` placementは (parent, rank) のみを運ぶため、この正規化はcanonical stateからは不可視。
2. **OPTIONS bit lifecycle（transientフラグの置換）**: popup作成直後の1子フォルダに対するbind-time cleanup抑止は、round 12までのtransient `FolderInfo.createdByDirectEdit` から、persistedな `FolderInfo.options` bit（`DirectEditContract.OPTIONS_DIRECT_EDIT_CREATED_FOLDER = 0x10`）へ置換された。bitはfavoritesのOPTIONS列に永続し、reloadでもloaderが `collection.options` を復元するため維持される（transientフラグはreloadで失効し、以降のbindで再びflatten対象になっていた）。bitはフォルダ行とともに消える（undoまたはユーザー削除）ためclear処理は不要。`Folder.java` のbind-guardはこのbitを参照する（round 13で登録される唯一の新規src/ path）。

## 見つかった追加製品課題（owner triage item — 本PRでは製品を変更しない）

- **編集画面（#449経路）の1子フォルダ作成（既知の限界・triage維持）**: 編集画面のconfirm適用は1子フォルダを作れるが、organizer作成フォルダにはOPTIONS bitが付かないため、適用直後のlauncher側bind-time single-child cleanup書込みが適用のpost-write検証と衝突し、適用は自動recover（VERIFICATION_FAILED → 復帰）する。ユーザーには「レイアウト変更なし」相当の表示になる。popup（direct-edit）経路の1子フォルダはOPTIONS bitにより生存する。2子以上の選択では編集画面でも成立する（上表どおりpass）。対応（1子作成の阻止 or 抑止のorganizer経路への適用）は#449表面での設計判断を要する。実機確認のcheck項目（下記）。
- **削除undoの新id再挿入**: `restoreRemovedItemForDirectEdit` はcaptureした行を**新id**で再挿入する（契約どおり）。行idでundo後を追う経路はid不変を仮定できない。

## B5の会計記録（§4の追加規則どおり）

| 項目 | 値 |
|---|---|
| 課題 | B5「誤って動かした・外したアイコンを元に戻す」 |
| baseline（確定値） | 削除1（4秒窓内のUndo tap）/ 移動8（逆drag） |
| fork実装のB5 | **削除1 / 移動1**（いずれもundo snackbarのUndo tap 1操作。AC判定表の移動・外すの実操作系列で裏付け） |
| 目標 | 1操作（メモ§4.1）→ **達成** |
| snackbar方式の時間窓 | 有り。上流の `Snackbar` と同一機構（`AccessibilityManagerCompat.getRecommendedTimeoutMillis`、基準4000ms）。既存実装を変えないためaccessibility設定準拠の実時間 |
| 対象範囲 | 項目単位の4アクション（#448経路の逆操作）+ 編集画面確定（#449経路の復元）。新規アプリの配置は対象外（メモ§4.1） |

## 実機owner確認（owner decision item）

実機Pixel 9aでのB5操作（移動/フォルダ追加/新規フォルダ/外す + 編集画面確定のundo、TalkBack読み上げを含む）は、オーナーの実機確認事項として残す（#448/#449と同じ扱い）。確認項目には上記の既知限界（編集画面での1項目選択の新規フォルダ作成がself-recoverし「レイアウト変更なし」相当表示になる挙動。2項目選択では成立する）をcheck項目として含めること。
