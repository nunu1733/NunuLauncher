# Issue #450 Edit Undo — 実行証跡（AC-1 / AC-9 / B5）

> Status: recorded（2026-09-30 round 13。agent実行記録。実機owner確認はowner decision item）
> Issue: [#450](https://github.com/nunu1733/NunuLauncher/issues/450)（FR-020、D-013）
> 正本: [editing-burden-benchmark](../engineering/editing-burden-benchmark.md) §4（snackbar方式の時間窓の記録規則）、§6（B5 baseline=削除1/移動8、目標=1操作）、§7（agent実行可能な検証手順）

## 実行結果（本HEAD、1回のtooling実行分）

エミュレータ `nunu_qpr2_api36_1`（API 36、en-US、`com.android.chrome` provision済み、本アプリをhome roleに設定）で、evidence tooling `HomeEditUndoEvidenceToolingTest`（CI lane外のon-demand tooling。#376 cold-process evidenceと同一扱い）を実行した。**7 tests / 全green**。

| flow | 結果 | 成果物（`docs/assessment/450-edit-undo-ac1-screenshots/`） |
|---|---|---|
| 移動（別ページへの移動・実シーケンス） | **pass** — 移動元ページ表示中に実編集 → 別ページへ移動 → snackbarが再bind後も存続（旧実装は約500msで自己消滅。round 12修正で解消）→ 実Undo tap → 元page-0セルへ復元 | `direct-move-before.png` / `direct-move-after.png` |
| 既存フォルダ追加 | **pass** — 実追加 → 実Undo tap → デスクトップ復帰 | `direct-add-to-folder-before.png` / `direct-add-to-folder-after.png` |
| 新規フォルダ作成（popup経路） | **pass** — round 12修正（1子フォルダのbind-time flatten抑止）によりフォルダがundo窓内で存続 → 実Undo tap → 子の元配置復帰 + フォルダ行削除 | `direct-create-folder-before.png` / `direct-create-folder-after.png` |
| ホームから外す | **pass** — 実削除 → 実Undo tap → 新idでの再挿入（元配置）を配置+起動targetで確認 | `direct-remove-before.png` / `direct-remove-after.png` |
| 編集画面確定（#449経路・移動） | **pass** — 実`confirm()` → 実snackbar tap → 復元（recovery経路） | `session-confirm-before.png` / `session-confirm-after.png` |
| AC-9（TalkBack） | **pass** — TalkBack有効（service bound）で実snackbarのlabel/actionがaccessibility treeに現れ、actionはfocusable/clickable + `ACTION_FOCUS`受諾 | `ac9-talkback-snackbar.png` / `ac9-accessibility-dump.xml` / `ac9-node-record.txt` |
| 抑止スコープ回帰 | **pass** — direct-edit由来でない1子フォルダは従来どおりlauncher自身によりflattenされる（cleanup無効化していないことの回帰oracle） | —（DB level oracle） |

各`before`画像は「実snackbarが表示されている状態（`Edit applied` + `Undo`）」、各`after`画像は「Undo tap 1操作後の状態」である。capture成功はtoolingがassertし、capture失敗はtest失敗になる（握りつぶしなし）。

## round 12対応の製品修正（本HEADに含む）

1. **別ページ移動のsnackbar自己消滅の修正**: `HomeEditExecutor.refreshAfterMove` のicon再bindを `forceAnimateIcons=false` へ変更（`bindInflatedItems` の遅延 `closeOpenViews` がsnackbarを約500msで閉じていた）。移動先ページへのsnapは従来どおり行う。accessibility経路の先例（bind無animation）と同一方式。
2. **1子フォルダのbind-time flatten抑止（スコープ限定）**: popupで作成した直後の1子フォルダは、`Folder` のbind-time single-child cleanupが「loading落ち」扱いでflattenしており、undo窓内で編集が自己消滅していた。`DirectEditCreateFolderTask` が作成直後の `FolderInfo` にtransientフラグを立て、`Folder` のbind-time cleanupのみこれを尊重する（#450 bridge。既存のcleanup自体は無効化しない — 抑止スコープ回帰oracleがそれを固定）。フラグは永続しないため、reload以降は従来のlifecycleに戻る。
3. **新規フォルダundoの照合緩和**: フォルダviewのbindは子のフォルダ内部cellを正規化してDBへ書き戻す（作成直後の `-1/-1` が `0,0` 等へ）。undo契約は「フォルダ内membership + rank」であり内部cellは照合対象でないため、`HomeEditUndoPlanner` のcreate-folder undo照合をcontainer + rankへ緩和した（stage-2 validatorは同一関数をadmission内で再実行するため整合）。

## 見つかった追加製品課題（owner triage item — 本PRでは製品を変更しない）

- **編集画面（#449経路）の1子フォルダ作成**: 編集画面のconfirm適用は1子フォルダを作れるが、適用直後のlauncher側flatten書込みが適用のpost-write検証と衝突し、適用は自動recover（VERIFICATION_FAILED → 復帰）する。ユーザーには「レイアウト変更なし」相当の表示になる。2子以上の選択では成立する。対応（1子作成の阻止 or flatten抑止のorganizer経路への適用）は#449表面での設計判断を要する。`EditSurfaceUndoInstrumentationTest.productionConfirmFlowWithNewFolderUndoIsStaledByFolderNormalization` がこの挙動をoracle化している（STALE_REVISIONの型付き拒否・零書込み・フォルダ内容保存まで固定）。
- **削除undoの新id再挿入**: `restoreRemovedItemForDirectEdit` はcaptureした行を**新id**で再挿入する（契約どおり）。行idでundo後を追う経路はid不変を仮定できない。

## B5の会計記録（§4の追加規則どおり）

| 項目 | 値 |
|---|---|
| 課題 | B5「誤って動かした・外したアイコンを元に戻す」 |
| baseline（確定値） | 削除1（4秒窓内のUndo tap）/ 移動8（逆drag） |
| fork実装のB5 | **削除1 / 移動1**（いずれもundo snackbarのUndo tap 1操作。上表の移動・外すの実操作系列で裏付け） |
| 目標 | 1操作（メモ§4.1）→ **達成** |
| snackbar方式の時間窓 | 有り。上流の `Snackbar` と同一機構（`AccessibilityManagerCompat.getRecommendedTimeoutMillis`、基準4000ms）。既存実装を変えないためaccessibility設定準拠の実時間 |
| 対象範囲 | 項目単位の4アクション（#448経路の逆操作）+ 編集画面確定（#449経路の復元）。新規アプリの配置は対象外（メモ§4.1） |

## 実機owner確認（owner decision item）

実機Pixel 9aでのB5操作（移動/フォルダ追加/新規フォルダ/外す + 編集画面確定のundo、TalkBack読み上げを含む）は、オーナーの実機確認事項として残す（#448/#449と同じ扱い）。上記の追加課題（編集画面1子フォルダ作成）は実機確認時の体感にも影響するため、確認項目に含めることを推奨。
