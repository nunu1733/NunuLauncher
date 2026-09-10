---
issue: "#233"
status: draft
requirements: []
updated: 2026-09-10
---

# Home backup復元previewで複数ページの保存内容を確認できる

## Problem

[Issue #233](https://github.com/nunu1733/NunuLauncher/issues/233) が報告するように、複数 Home page を持つ layout の Lawnchair backup を復元する際、restore 確認画面の graphical preview には先頭ページの screenshot しか表示されず、他ページの保存内容を restore 実行前に確認できない。実装上の根拠は次のとおり (baseline `6b6bf8dd9fa0c42399185dbb13c30192f1e15962` 時点、2026-09-10 確認):

- backup 作成側 `CreateBackupViewModel.captureScreenshot()` (`lawnchair/src/app/lawnchair/backup/ui/CreateBackupViewModel.kt`) は `LauncherPreviewView` で **1枚だけ** bitmap を生成する。
- その描画は `LauncherPreviewRenderer` (`src/com/android/launcher3/graphics/LauncherPreviewRenderer.java`) が行う。renderer は `launcher_preview_layout` を inflate し、workspace としては `FIRST_SCREEN_ID` 1枚 (two-panel device のみ `SECOND_SCREEN_ID` 追加) の `CellLayout` しか用意しない。`populate()` は `filterCurrentWorkspaceItems` で先頭画面以外の item を `otherWorkspaceItems` へ分離し、それらを**描画しない**。
- 生成された1枚の bitmap は `screenshot.png` として zip に保存され (`LawnchairBackup.create`)、`BackupInfo` proto には page に関する情報がない (`previewWidth` / `previewHeight` / `previewDarkText` のみ)。
- 復元側 `RestoreBackupScreen.kt` の portrait preview は `backup.screenshot` を単一の `Image` として表示する。pager 等の page 切替機構は存在しない。

一方で backup zip の `launcher.db` は**全 page の favorites 行**を含み、restore は全 page を書き戻す。よって「preview で見える範囲 ≠ restore が書き換える範囲」であり、preview を「この backup を適用してよいか」の確認材料として使うと、2ページ目以降の変更は事前検証から漏れる。Issue の観測 (2ページ目の Settings 配置を restore 前に確認できなかった) はこの構造から決定的に導かれる。regression ではなく upstream Lawnchair 由来の設計上の制約である (調査事項の1つはこの点で解決済み)。

## Outcome

複数 Home page を含む backup の restore 確認画面で、ユーザーは (1) graphical preview が先頭ページのみであることを明示され、(2) 保存対象の全 Home page を識別する補助情報 (page 数と各 page の内容要約) を restore 実行前に確認できる。これにより「表示されている preview = backup 全体」という誤認が排除され、high-impact な restore 操作の事前確認としての妥当性が回復する。

## Scope

- **preview 制約の明示**: `RestoreBackupScreen` の graphical preview に対し、layout を含む backup かつ保存された page 数が2以上の場合、preview が先頭ページのみを表すことを示す caption を表示する。page 数が1 (または page 情報を取得できない) 場合は現行の無 caption 表示を維持する。
- **page 識別情報の表示**: backup zip 内の `launcher.db` を読み取り専用で解析し、workspace page (`container = -100`) の一覧と各 page の要約 (item 数、folder 数、widget 数。app title の代表例は open question 参照) を確認画面に表示する。解析は backup 作成時ではなく restore 画面表示時に行うため、**backup format は変更しない**。過去の backup にも同じ経路で作用する。
- **失敗時の typed 表示**: `launcher.db` の読み取りや解析に失敗した場合、確認画面は screenshot preview と restore 操作を維持し、page 識別情報の欄に「page 情報を表示できない」旨の typed 文言を表示する。restore 自体を失敗扱いにしない。
- strings は `values/` と `values-ja/` の両方へ追加する (#123 の日本語 fallback 禁止契約)。
- a11y: 追加する caption・要約は screen reader で到達可能であり、focus順・liveRegion 規約に従う。

## Non-goals

- backup / restore data format (`BackupInfo` proto、zip entry 構成) の変更。page 毎の screenshot 追加等も含め、必要性が確定するまで行わない (「調査事項」の残項目は Open questions へ分離)。
- graphical な複数 page preview (pager / swipe / page indicator) の実装。page 識別情報の text 表示で事前確認の最低線を満たす。graphical 拡張は Open questions の決定待ち。
- `LauncherPreviewRenderer` 等 `com.android.launcher3` / AOSP 由来 file への変更。本 spec の最小経路は `app.lawnchair.backup` 配下と string resource で完結する。
- Nova backup restore (`RestoreNovaBackupScreen`) の preview。Nova 流し込みは screenshot を持たず、別 workflow である。
- restore transaction / critical section (`LawnchairBackup.restore`、#58 / ADR-0011 系列) の変更。本 spec は読み取り専用の確認 UI 追加であり、restore 実行経路へ触れない。
- Organizer recovery snapshot (#230) の UI、および restore 実行後の結果表示。
- backup 作成画面 (`CreateBackupScreen`) の変更。

## Domain language

- **保存された page (saved page)**: backup zip の `launcher.db` において `favorites.container = -100` (workspace) を持ち、同じ `screen` 値に属する item の集合。本 spec の page 数・要約はこの定義に基づく。

`CONTEXT.md` への反映は承認時に判断する (実装語のみであれば追加しない)。

## Behavior scenarios

### Scenario: multi-page backup の確認

Given layout を含む backup が 3 workspace page 分の favorites を保存しており
When ユーザーが restore 画面を開く
Then graphical preview には従来どおり先頭ページの screenshot が表示される
And preview に「この画像は先頭ページのみ」である旨の caption が表示される
And 保存された page 数 (3) と各 page の要約 (page 毎の item / folder / widget 数) が確認画面に表示される

### Scenario: single-page backup の確認

Given backup が 1 workspace page のみ保存しており
When ユーザーが restore 画面を開く
Then caption も page 要約も表示されず、現行の表示のまま追加の冗長な情報を示さない

### Scenario: layout を含まない backup

Given backup の contents が wallpaper のみであり
When ユーザーが restore 画面を開く
Then page caption・page 要約は表示されない (preview の screenshot も現行どおり非表示)

### Scenario: launcher.db 解析失敗

Given zip 内の `launcher.db` が破損・読み取り不能であり
When ユーザーが restore 画面を開く
Then screenshot preview と contents 選択・restore button は現行どおり動作する
And page 識別情報の欄には「page 情報を表示できない」旨の typed 文言のみが表示される
And restore 実行は block されず、解析失敗は diagnostic log に記録される

### Scenario: 巨大・異常な favorites

Given 解析の結果 page 数が異常に大きい値 (例: screenId の飛び番を含む) であり
When restore 画面が page 要約を表示する
Then 表示は画面を壊さない範囲に制限・省略され (scroll 可能、件数は全件表示)、restore 可否は変化しない

### Scenario: cancel

Given いずれの状態でも
When ユーザーが restore を実行せずに画面を離れる
Then 何も書き込まれない (本機能は restore 画面の表示期間中のみ zip を読み取り専用で開く)

## Data and state

- 読む data: 選択された backup zip の `info` (contents flag) と `launcher.db` entry (favorites 表の `container` / `screen` / `itemType` 読み取り)。正本は zip 自体であり、追加の永続化は行わない。
- 一時的作業領域: zip 内 `launcher.db` は SQLite として開くため cache directory への展開が必要な場合、解析完了後に削除する。残存しないこと。
- 永続化する data: なし。backup format・`BackupInfo` proto・launcher DB は一切変更しない。
- migration / backup compat: 新旧 backup で format が同一のため compat 層不要。古い backup でも解析は同経路で動く。
- rollback: 本機能は UI 追加のみであり、revert で機能表面が消えるだけで data 上の影響はない。
- layout 安全規約への影響: なし。`favorites` への書き込みを伴わない。

## Permissions, privacy, and security

- 追加 permission なし。既存の SAF document access (選択された zip の uri) のみを使う。
- favorites の item 種別数は app 配置の指標になり得るが、画面表示のみで外部送信しない。diagnostic log に page 数・item 数の集計値のみ記録し、app 名等の個別 item 情報は log に出さない。

## Accessibility and localization

- caption と page 要約は text として screen reader で読み上げ可能にする。preview `Image` は現行どおり装飾 (`contentDescription = null`) とし、情報は text 側が担う。
- page 要約が複数行になる場合、scroll 可能な領域に置き、focus 順を「caption → 要約 → contents 選択 → restore button」の自然な順に保つ。
- font scaling に追従する text layout を使う (固定高の行を仮定しない)。
- strings は `values/` と `values-ja/` の両方に追加する。

## Acceptance criteria

- [ ] AC-1: multi-page (2 page 以上) の layout を含む backup を restore 画面で開いたとき、preview が先頭ページのみである旨の caption と、保存された page 数・各 page の要約が表示される。
- [ ] AC-2: single-page の backup では caption・要約が表示されない。layout を含まない backup でも表示されない。
- [ ] AC-3: `launcher.db` 解析失敗時、screenshot preview と restore 操作は維持され、page 情報不可の typed 文言が表示される。restore は block されない。
- [ ] AC-4: 解析は zip を読み取り専用で扱い、終了後に作業領域を残さない。favorites への書き込みを一切行わない。
- [ ] AC-5: multi-page fixture (既知の screenId 構成の launcher.db を含む zip) に対する unit test で、page 数・page 別集計が決定的に検証される。解析失敗の failure injection も同様に検証される。
- [ ] AC-6: representative device/emulator で 2 page layout の backup → restore 画面確認の手動 evidence が記録される (caption・要約の表示、a11y での読み上げ)。
- [ ] AC-7: 追加 string が `values/` と `values-ja/` の両方に存在する。
- [ ] AC-8: upstream 由来の制約であること、本修正が `app.lawnchair.backup` 配下の fork 固有変更 (AOSP patch なし) である方針が plan に記録される。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | Robolectric unit test (`tests/unit/app/lawnchair/backup/`) — fixture zip から ViewModel state の caption 要件・page summary 値を検証。UI 表示の最終確認は AC-6 の手動 evidence |
| AC-2 | 同上 unit test (single-page fixture / wallpaper-only fixture) |
| AC-3 | 同上 unit test (破損 db entry を含む fixture による failure injection) |
| AC-4 | 同上 unit test (解析後の作業 file 削除、favorites 書き込みなしをreadonly open で検証) |
| AC-5 | 同上 unit test。screenId の飛び番・folder/widget 混在の境界 fixture を含む |
| AC-6 | emulator (2 page layout) での手動確認 screenshot / 録画、a11y は TalkBack または semantics tree dump |
| AC-7 | `git grep` / string resource 存在検証 (unit test または PR evidence) |
| AC-8 | plan.md の記載と PR 本文 |

## Open questions

- **app title 等の page 内容の具体的識別情報をどこまで出すか**: page 別の件数集計のみで「どの app がどこへ戻るか」の識別として十分か、app title の代表例 (例: 各 page 先頭の app 名) を載せるか。app title は favorites 行の `title` / `intent` から得られるが、privacy 表示の粒度と実装 cost が変わる。実装 PR の方向付けで決定する (blocking ではない: 件数集計のみでも AC-1 の「識別情報」の最低線は満たす)。
- **graphical 複数 page preview の要否**: 本 spec の text 要約で事前確認の不足が解消されるか、それとも page 毎の画像 prompt が必要か。必要になった場合は (a) backup format への page 別 screenshot 追加、(b) restore 時に `launcher.db` から `LauncherPreviewRenderer` 系で live render、のいずれかを検討し、別 Issue + format 変更なら spec/ADR を分離する。
- **upstream 報告**: 先頭 page のみの preview は upstream Lawnchair の設計である。upstream への改善報告を行うかは本 Issue の PR で判断し、実施する場合は upstream Issue chooser から行う (fork の Issue に混ぜない)。

## Change history

- 2026-09-10: Draft created for #233. 先頭 page 限定が upstream の構造的な設計であることを code evidence とともに確定し、format 不変・読み取り専用の page 識別情報表示を中心とする契約として起草した。
