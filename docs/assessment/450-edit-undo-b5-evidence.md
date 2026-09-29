# Issue #450 Edit Undo — 実行証跡（AC-1 / AC-9 / B5）

> Status: recorded（2026-09-30。agent実行記録。実機owner確認はowner decision item）
> Issue: [#450](https://github.com/nunu1733/NunuLauncher/issues/450)（FR-020、D-013）
> 正本: [editing-burden-benchmark](../engineering/editing-burden-benchmark.md) §4（snackbar方式の時間窓の記録規則）、§6（B5 baseline=削除1/移動8、目標=1操作）、§7（agent実行可能な検証手順）

## 実行結果（本HEAD、1回のtooling実行分）

エミュレータ `nunu_qpr2_api36_1`（API 36、en-US、`com.android.chrome` Provisión済み、本アプリをhome roleに設定）で、evidence tooling `HomeEditUndoEvidenceToolingTest`（CI lane外のon-demand tooling。#376 cold-process evidenceと同一扱い）を実行した。

| flow | 結果 | 成果物（`docs/assessment/450-edit-undo-ac1-screenshots/`） |
|---|---|---|
| 移動（ページへ移動） | **pass** — 実snackbarのUndo actionを実tapし、DB levelで元のpage-0セルへ復元を確認 | `direct-move-before.png` / `direct-move-after.png` |
| 既存フォルダ追加 | **pass** — 安定した2子フォルダfixtureへの実追加 → 実Undo tap → デスクトップ復帰を確認 | `direct-add-to-folder-before.png` / `direct-add-to-folder-after.png` |
| ホームから外す | **pass** — 実削除 → 実Undo tap → 新idでの再挿入（元配置）を確認 | `direct-remove-before.png` / `direct-remove-after.png` |
| 編集画面確定（#449経路） | **pass** — 実`confirm()` → 実snackbar tap → 復元（recovery経路）を確認 | `session-confirm-before.png` / `session-confirm-after.png` |
| 新規フォルダ作成（popup経路） | **findings記録**（下記「見つかった製品課題」）— 1子フォルダがundo窓内でlauncher自身によりiconへflattenされるため、undoの可視操作系列は成立しない | `direct-create-folder-before.png` / `direct-create-folder-undo-t1.png`（「Can’t undo」Toast記録） |
| AC-9（TalkBack） | **pass** — TalkBack有効（service bound）で実snackbarのlabel/actionがaccessibility treeに現れ、actionはfocusable/clickable | `ac9-talkback-snackbar.png` / `ac9-accessibility-dump.xml` / `ac9-node-record.txt` |

各`before`画像は「実snackbarが表示されている状態（`Edit applied` + `Undo`）」、各`after`画像は「Undo tap 1操作後の状態」である。capture成功はtoolingがassertし、capture失敗はtest失敗になる（握りつぶしなし）。

新規フォルダの逆操作（フォルダ行削除込みの1 transaction復元）の契約自体は、model levelで `DirectEditUndoModelWriterTest`（folder undo 1-transaction oracle、shared-writer lane常設）が担保する。

## B5の会計記録（§4の追加規則どおり）

| 項目 | 値 |
|---|---|
| 課題 | B5「誤って動かした・外したアイコンを元に戻す」 |
| baseline（確定値） | 削除1（4秒窓内のUndo tap）/ 移動8（逆drag） |
| fork実装のB5 | **削除1 / 移動1**（いずれもundo snackbarのUndo tap 1操作。上表の移動・外すの実操作系列で裏付け） |
| 目標 | 1操作（メモ§4.1）→ **達成** |
| snackbar方式の時間窓 | 有り。上流の `Snackbar` と同一機構（`AccessibilityManagerCompat.getRecommendedTimeoutMillis`、基準4000ms）。既存実装を変えないためaccessibility設定準拠の実時間 |
| 対象範囲 | 項目単位の4アクション（#448経路の逆操作）+ 編集画面確定（#449経路の復元）。新規アプリの配置は対象外（メモ§4.1） |

## 見つかった製品課題（owner triage item — 本PRでは製品を変更しない）

1. **別ページへの移動のsnackbar自己消滅**: `HomeEditExecutor.refreshAfterMove` が `bindItems(..., forceAnimateIcons=true)` で移動後iconを再bindし、`bindInflatedItems` の遅延 `closeOpenViews`（`NEW_APPS_PAGE_MOVE_DELAY`=500ms）が表示直後のundo snackbarを閉じる。移動先が表示中ページの場合は発生しない（本証跡の移動系列はこの経路）。ユーザー影響: 別ページ移動の直後約0.5秒のみsnackbarが見える。修正方針（`forceAnimateIcons=false` への変更等）は#448/#450仕様の再確認を要するため、owner triageへ分離する。
2. **1子フォルダの自動flatten**: popupの「新しいフォルダ」は1アイテムの1子フォルダを作るが、launcherはundo窓内（約1〜2秒）で1子フォルダをiconへflattenする（DB書込み: 子がフォルダcellへ、フォルダ行削除）。Undo tapは「Can’t undo: the icon has moved since.」でSTALE拒否される（`direct-create-folder-undo-t1.png`）。layoutは既に自己復帰しているため状態は破壊されないが、undoは失敗表示になる。model levelの逆操作契約（フォルダ行削除込み1 transaction）は `DirectEditUndoModelWriterTest` が担保済み。
3. **削除undoの新id再挿入**: `restoreRemovedItemForDirectEdit` はcaptureした行を**新id**で再挿入する（契約どおり）。行idでundo後を追うcallback/統計はid不変を仮定できない（本証跡のtoolingも配置+起動targetで突合した）。

## AC-9のaccessibility確認（エミュレータ）

- TalkBack（`com.google.android.marvin.talkback`）をshell設定で有効化し、`dumpsys accessibility` でservice boundを確認した上で、実undo snackbarを表示した。
- `ac9-node-record.txt`: label node（`app.lawnchair.debug:id/label`、text="Edit applied"、visibleToUser=true）とaction node（`app.lawnchair.debug:id/action`、text="Undo"、visibleToUser=true、clickable=true、focusable=true、ACTION_FOCUS受諾）を記録。
- `ac9-accessibility-dump.xml`: 表示中のwindow hierarchy全体。
- 表示時間のaccessibility設定準拠とresource由来文言は自動oracle（`HomeEditAcceptanceOraclesTest`、en+ja）が担保。
- **実機での読み上げ確認はowner decision item**（下記）。

## 実機owner確認（owner decision item）

実機Pixel 9aでのB5操作（移動/フォルダ追加/新規フォルダ/外す + 編集画面確定のundo、TalkBack読み上げを含む）は、オーナーの実機確認事項として残す（#448/#449と同じ扱い）。上記の製品課題2件は実機確認時の体感にも影響するため、確認項目に含めることを推奨。
