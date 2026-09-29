# Issue #450 Edit Undo — B5 evidence（ベンチマーク記録）

> Status: recorded（2026-09-30。agent実行記録。実機owner確認はowner decision item）
> Issue: [#450](https://github.com/nunu1733/NunuLauncher/issues/450)（FR-020、D-013）
> 正本: [editing-burden-benchmark](../engineering/editing-burden-benchmark.md) §4（snackbar方式の時間窓の記録規則）、§6（B5 baseline=削除1/移動8、目標=1操作）、§7（agent実行可能な検証手順）

## B5の会計記録（§4の追加規則どおり）

| 項目 | 値 |
|---|---|
| 課題 | B5「誤って動かした・外したアイコンを元に戻す」 |
| baseline（確定値） | 削除1（4秒窓内のUndo tap）/ 移動8（逆drag） |
| fork実装のB5 | **削除1 / 移動1**（いずれもundo snackbarのUndo tap 1操作） |
| 目標 | 1操作（メモ§4.1）→ **達成** |
| snackbar方式の時間窓 | 有り。上流の `Snackbar` と同一機構（`AccessibilityManagerCompat.getRecommendedTimeoutMillis`、基準4000ms。`Snackbar.java:184-188`）。既存実装を変えないためaccessibility設定準拠の実時間 |
| 対象範囲 | 項目単位の4アクション（#448経路の逆操作）+ 編集画面確定（#449経路の復元）。新規アプリの配置は対象外（メモ§4.1） |

## 裏付け（automated evidence、本PR実行分）

B5の「1操作」は、操作の会計として次のautomated evidenceで裏付けられる（エミュレータ `nunu_qpr2_api36_1` API 36）:

- **項目単位のundo（削除→逆INSERT、移動→逆UPDATE、新規フォルダ→1 transaction）**: `DirectEditUndoModelWriterTest` 9 tests green — 各逆操作が1操作（1回のsnackbar tap → 1回のadmission内書込み）で完了すること、失敗時は零書込みであることを実DBで確認。
- **編集画面確定のundo（復元経路）**: `EditSurfaceUndoInstrumentationTest` 18 tests green — 実 `confirm()` flowで確定した復元点を、undo snackbar tap 1操作（`HomeEditUndoExecutor.start`）で `Restored`（確定前layoutへ戻る）ことを確認。

**共有lane実行結果**: 18 pass + 1 skip。skipは `productionConfirmFlowWithNewFolderUndoRestoresToThePreApplyState`（新規フォルダの実confirm()経由oracle。recover内部の recapture が test側captureと分岐する環境固有の問題で、plan自体は同一。production module直呼びでは同一planが Applied確認済み。owner-triage itemとして #449表面へ切り分け済み。skipはgreen test数に含めない）。

## AC-1のundo前後の可視記録（エミュレータ操作記録）

AC-1の「undo前後が判別できる記録」は、録画の代わりに「instrumentation oracleの状態遷移assert + エミュレータスクリーンショット」の組で構成した:

- **undo前（編集適用後）の状態**: `productionConfirmFlowUndoExecutorRestoresThenReportsNotRestorableOnRepeat` の revision-equality oracle が「confirm適用後のlayout（item移動先・folder行あり）」を `postApplyCapture` で固定し、`expectedRevision == post-apply capture revision` をassertする。
- **undo後（復元後）の状態**: 同oracleの zero-write probe が「undo後のlayout（item元位置・folder行なし）」を `adapter.captureCurrent` で固定する。
- **エミュレータスクリーンショット**（`docs/assessment/450-edit-undo-ac1-screenshots/`）:
  - `01-home.png` — undo操作が行われるホーム画面の状態。
  - `undo-before.png` — 編集画面確定で移動したアイテムが移動先（ページ1）に表示されている状態（undo前=編集適用後）。
  - `undo-after.png` — undo snackbar tap 1操作でアイテムが元のページ0へ戻った状態（undo後=復元後）。
- 4アクションのundo前後は `DirectEditUndoModelWriterTest`（9 tests）が実DBで「undo前=編集適用後の配置 / undo後=元の配置」を検証する。

## AC-9のaccessibility確認（エミュレータ）

- **TalkBack有効化**: エミュレータ `nunu_qpr2_api36_1` でTalkBack（`com.google.android.marvin.talkback`）を有効化し、`dumpsys accessibility` でbound service（FEEDBACK_SPOKEN）を確認した。
- **accessibility node tree**: UI Automatorの `android_ui_describe` で launcher workspaceの全アイテムが contentDescription/text付きのaccessibility nodeとして現れることを確認した（Gmail/YouTube/Phone/Messages/Chrome等が読み上げ対象として列挙）。undo snackbarは上流の `Snackbar` の仕組み（文字列リソース由来のlabel/action、TalkBackで読めるTextView）をそのまま再利用するため、同一の読み上げ経路に乗る。
- **構造的保証**: 新規文字列は `homeedit_undo_*`（en+ja、`HomeEditAcceptanceOraclesTest.all undo strings are defined and non-empty in en and ja` で自動検証済み）。undo失敗の理由はspec 448と同じToast慣行（文字列リソース由来）。`Snackbar` の表示時間は `AccessibilityManagerCompat.getRecommendedTimeoutMillis`（FLAG_CONTENT_TEXT | FLAG_CONTENT_CONTROLS）によるaccessibility設定準拠の実時間（既存実装を変えない）。
- **実機での読み上げ確認はowner decision item**（下記）。

## 実機owner確認（owner decision item）

実機Pixel 9aでのB5操作（4アクション+編集画面確定のundo、TalkBack読み上げを含む）は、オーナーの実機確認事項として残す（#448/#449と同じ扱い）。
