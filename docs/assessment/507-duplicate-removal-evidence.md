# Issue #507 Phase 2 evidence — duplicate confirmation in the edit surface

> Status: Recorded（tier M owner-emulator evidence。実機owner確認は #507 側の owner decision item として残置）
> Date: 2026-10-03
> Environment: emulator `nunu_smoke_api35`（Pixel-like、1080x2400、420dpi、API 35）、build `06329eec7d`（`assembleLawnWithQuickstepGithubDebug`）、debug install + manual `androidTest` install。fixture seeding: `EditingBurdenBenchmarkFixtureSeedingInstrumentationTest` を `-e persist true` で実行後、`run-as ... sqlite3` で `organizerLockState` をUNLOCKED（1）へ補正（seeding残差の4行がUNKNOWNのまま確定ゲートを止めるため。本機能の检証対象外）。
> All AC→evidence mapping: [spec 507](../../specs/507-duplicate-removal-proposal/spec.md) Test oracle。

## 1. 編集画面と重複サマリ行（AC-1、emulator補助証跡）

fixture §5 seeding後のホーム（`507-01-home.png`）には重複ペア（Fixture 02×2 = ページ0、重複1組目。Fixture 03×2 = ページ1、2組目）が存在する。workspace空きスペース長押し →「Edit layout grid」で編集画面（`507-03-surface.png`）を開くと、「**Duplicate icons: 2 groups**」のサマリ行が表示される（0組の間は行が出ない）。

## 2. 重複確認面（dialog）（AC-1）

サマリ行tapで確認面（`507-04-dialog.png`）が開き、各グループ（Fixture 02、Fixture 03）の各メンバー行が「名前 + Page n, row m, column k」で一覧される。fixture構成（ページ0/1の重複各2組）と一致。この時点でfavorites DBへの書込みは発生していない（capture読み取りのみ）。

## 3. 最後の1個のguard（AC-4）

確認面で1個目のメンバーをチェック後、もう1個もチェックしようとすると、`507-05-guard.png` のとおり **2個目のチェックは受け付けられず**「Keep at least one icon in each duplicate group.」のtyped理由が表示される（選択数は1のまま、零書込み。選択履歴は保持される）。

## 4. 面内Remove→図更新（AC-5/AC-6）

面内「Remove from Home」で選択メンバーが図から除かれ（`507-06-removed.png`）、サマリ行は「Duplicate icons: 1 groups」へ、確定ボタンは「Apply (1)」へ更新される（セッション計画の作業投影反映）。

## 5. 確定→ホーム反映→Undo（AC-5）

「Apply (1)」をtapすると既存#449適用経路（再capture照合・recovery point 1・1 transaction・相関reload・適用後検証）で1行が削除され、ホーム（`507-07-applied-undo.png`）で Fixture 02 が1個になる。確定直後に#450既存Undo snackbarが表示され、4秒窓内のUndo tapで favorites DB上の両ペアが2行へ復元されることを `run-as app.lawnchair.debug sqlite3 databases/launcher_5_4_4.db "SELECT title, count(*) FROM favorites WHERE title IN ('Fixture 02','Fixture 03') GROUP BY title;"`（結果: `Fixture 02|2` / `Fixture 03|2`）で確認した。Undo部品は#450不変経路であり、snackbar画像は任意のため、DB oracleを証跡とする。

## 6. 200% font scale（AC-8・構造確認）

`settings put system font_scale 2.0` のうえ、編集画面と確認面の崩れがないことを `507-09-font200.png` で確認（title折返し・ダイアログ内のスクロール・メンバー行の複数行化はCompose標準で追従。アクションボタンは省略表示で操作性維持）。

## 7. owner確認の取扱い

階層M workflow上、実機（物理端末）でのowner確認（表示・TalkBack）がAC-1/AC-8の完了条件であり、本evidenceはemulator補助証跡である。実機owner確認が取得できない段階での `implemented` 化・closing keywordへの遷移はspec/PR本文に行わない（Phase 2 review round 3（高）指摘対応。owner decision itemはIssue #507 へ記録する）。
