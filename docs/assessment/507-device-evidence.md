# Issue #507 device owner evidence — duplicate confirmation on the physical device

> Status: Recorded（tier M実機owner確認。AC-1/AC-8の完了条件）
> Date: 2026-10-03
> Device: Pixel 9a（`56231JEBF08674`、Android 16 / tegu、1080x2424・420dpi、ja-JP。#449/#450/#452の実機検証と同一端末）
> Build: `main` `66b6811083`（PR #513 merge後）の `assembleLawnWithQuickstepGithubDebug`（`Lawnchair.15.Dev.(66b6811).github.debug.apk`）を手動install。
> Setup: benchmark fixture（[editing-burden-benchmark](../engineering/editing-burden-benchmark.md) §5）を `EditingBurdenBenchmarkFixtureSeedingInstrumentationTest`（`-e persist true`）でseeding。emulator実施と同様、seeding残差の `organizerLockState=0`（UNKNOWN）4行をUNLOCKED（1）へ補正（本機能対象外。確定ゲート用。DBはforce-stop後のcheckpoint fileをhost側sqlite3で更新して戻す方式。`organizerLockState=1` 42行、重複2組 = `Fixture 02`×2 + `Fixture 03`×2 を検証済み）。
> 画像: 本directory `507-d*.png`。emulator補助証跡は [507-duplicate-removal-evidence.md](../assessment/507-duplicate-removal-evidence.md)。

## 1. 重複サマリ行と確認面（AC-1）

- `507-d01-home.png`: fixtureホーム（ja）。ページ0に重複ペア「Fixture 02」×2、ページ1に「Fixture 03」×2（identity表どおり）。
- `507-d02-popup.png`: workspace空きスペース長押しpopupに「編集画面」項目。
- `507-d03-surface.png`: 編集画面。上部に「**重複アイコン: 2組**」のサマリ行（0組では非表示）、「0件を選択中」。
- `507-d04-dialog.png`: サマリ行tapで確認面「重複アイコン」が開く。各グループ（Fixture 02 / Fixture 03）の各メンバー行が「名前 + ページn・行m、列k」つきで一覧される。この時点でfavorites DBは不変（capture読み取りのみ）。

## 2. 最後の1個のguard（AC-4）

- `507-d05-guard.png`: 確認面で1個目（Fixture 02・ページ1・行3、列1）をチェック後、2個目（列2）をtapすると **受け付けられず**、確認面内と背景の両方に「各重複グループで少なくとも1個は残してください」が表示される（選択数は1件のまま。零書込み）。

## 3. 面内Remove → 図更新 → 確定 → ホーム反映（AC-5/AC-6）

- `507-d06-removed.png`: 面内「ホームから外す」で選択メンバーが図から除かれ（ページ1・行3、列1が空きセル表示）、サマリは「重複アイコン: 1組」、確定ボタンは「確定 (1)」へ更新される（セッション計画の作業投影反映）。
- 「確定 (1)」tapで既存#449適用経路（再capture照合・recovery point 1・1 transaction・相関reload・適用後検証）により行が削除され、編集画面が閉じてlauncherへ戻る。

## 4. Undo（AC-5。既存#450経路）

- `507-d07-undo-snackbar.png`: 確定直後のlauncher画面に「**編集を適用しました** / **元に戻す**」のsnackbarが表示される。
- 「元に戻す」tap → recovery復元（相関reload含む）完了後、favorites DBを `run-as app.lawnchair.debug cat databases/launcher_5_4_4.db` で取得し host sqlite3 で照合: undo前 `Fixture 02|1 / Fixture 03|1` → **undo後 `Fixture 02|2 / Fixture 03|2`**（削除行の復元を確認。`507-d08-restored-home.png` は復元後の再seeding前の状態確認用）。
- 実施上の注記: undo tap後のrecovery復元＋reload完了まで端末で8秒程度を要した（emulatorでは2〜3秒）。tap直後（3秒以内）のDB読み取りでは復元前の値が観測されるため、undo完了のoracle取得には十分な待ちが必要。

## 5. TalkBack（AC-8。実機）

- `507-d09-talkback-summary.png`: TalkBack有効下で確認面を開いた状態。ダイアログタイトル「重複アイコン」にTalkBackフォーカス矩形（緑）が表示される — TalkBackから確認面へ到達・読み上げ対象になることを実機で確認。
- `507-d10-talkback-member.png`: TalkBack再有効化直後、開いている確認面のタイトルへTalkBackフォーカスが当たる。メンバー行の読み上げ内容の正本（`editSurfaceDuplicateRowDescription` の供給）をnode treeで構造確認:
  - `Fixture 02, ページ1・行3、列1, 選択中`（選択状態つき）
  - `Fixture 02, ページ1・行3、列2`（非選択）
  - 図上アイテムも同様に `名前, ページn (行, 列)[, 選択中][, 選択できません]` のcontent-descが供給される（node tree dump、20+ノード）。
- `507-d11-talkback-locked-row.png`: **選択不可の理由つきメンバー行へのTalkBackフォーカス到達**。Fixture 02片方を `organizerLockState=2`（LOCKED）へ補正したうえで確認面を開き、理由つき行「Fixture 02 / ページ1・行3、列2」へTalkBackフォーカス矩形（緑）が到達した状態。当該行の読み上げ対象（content-desc）はnode treeで `Fixture 02, ページ1・行3、列2, ロック中` が供給されていることを確認（名前・位置・選択不可の理由）。補正したlock値は確認後に再seedingで解消。
- **owner実読み上げ確認（2026-10-03）**: ownerが実機（Pixel 9a）でTalkBack読み上げ音声を直接確認した（サマリ行・確認面メンバー行の名前/位置/選択状態/選択不可の理由。d09〜d11のフォーカス状態でTalkBackが読み上げる内容とcontent-descの一致を耳で確認）。owner decision は [Issue #507](https://github.com/nunu1733/NunuLauncher/issues/507) へ記録する。
- TTS録音は行っていない（label等価性は TalkBackフォーカス矩形＋node treeのcontent-desc同一性＋ownerの実読み上げ確認で担保。#452 の運用と同水準）。
- **accessibility設定の復元**: 確認前に `enabled_accessibility_services` を保存し、確認後に同一文字列へ復元した（diff一致。TalkBack以外の既存service: MacroDroid / Sleep as Android / Bitwarden / Nova Launcher は全程無変更）。
- 実施上の注記: TalkBack有効下ではadb単一tapが「フォーカス」として効くため、手順の途中で図上アイテムが意図せず選択状態になることがある（`507-d10` 背景の選択枠。確認対象の動作には影響しない）。セッションは確認後にキャンセルで閉じ、DBは不変（確定操作なし）。

## 6. 終了条件との対応

| spec AC | 本evidence |
|---|---|
| AC-1（サマリ行・確認面の一覧） | §1（d01〜d04） |
| AC-4（最後の1個のguard・零書込み） | §2（d05） |
| AC-5（面内Remove=既存アクション・確定・Undo） | §3/§4（d06/d07・DB oracle） |
| AC-6（表示・選択の零書込み） | §1〜§4（確定までDB不変を構造的に満たす。#449適用経路の契約は既存test群が担保） |
| AC-8（TalkBack・200% font） | §5（d09/d10・node tree）+ emulator evidence §6（200% font scale） |
