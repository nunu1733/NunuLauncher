---
issue: "#233"
status: accepted
requirements: []
updated: 2026-09-11
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

複数 Home page を含む backup の restore 確認画面で、ユーザーは (1) graphical preview に保存された page の全部が表示されない可能性があることを明示され、(2) 保存対象の Home page を識別する補助情報 (非空 page 数と各 page の item 種別件数) を restore 実行前に確認できる。これにより「表示されている preview = backup 全体」という誤認が排除され、high-impact な restore 操作の事前確認としての妥当性が回復する。ただし本機能は page 別の**件数集計**までを提供し、「特定の app がどの page のどこへ配置されるか」を特定できるようにはしない (app 同定は非対象、Open questions 参照)。

## Scope

- **preview 制約の明示**: `RestoreBackupScreen` において、layout を含む backup かつ「preview で表示されない可能性のある非空 page」が存在する場合、preview 画像が保存された page の全部は表示されない可能性がある旨の caption を表示する。判定は **preview coverage の推定**に基づく: `launcher.db` の `workspaceScreens` 表 (`screenRank` 昇順) の先頭 screen を特定する。表が存在しない場合は renderer の実際の動作 (`LauncherPreviewRenderer` が固定の `FIRST_SCREEN_ID = 0` を描画する) に合わせて **screen 0** を coverage とみなす (非空 screen の最小値は使わない。screen 0 が空で item が screen 1 以降にのみ存在する backup でも caption が出る)。先頭 screen 以外に非空 page が1つでも存在するとき表示する。caption 文言は two-panel device では先頭2 panel 分が screenshot に含まれるため「表示されない**可能性がある**」とする (coverage は device で変わるため断定を避ける)。非空 page が先頭 screen のみの場合は現行の無 caption 表示を維持する。
- **page 識別情報の表示**: backup zip 内の `launcher.db` を読み取り専用で解析し、workspace page (`container = -100`) の一覧と各 page の要約 (page 別の item 総数 / folder 数 / widget 数) を確認画面に表示する。解析は backup 作成時ではなく restore 画面表示時に行うため、**backup format は変更しない**。過去の backup (`BACKUP_VERSION = 1` のみが存在) にも同じ経路で作用する。
- **集計定義 (確定)**:
    - 対象行は `favorites` のうち `container = -100` (workspace) の行のみ。hotseat (`container = -101`) は対象外。
    - **非空 page** = workspace item を1つ以上持つ `screen` 値。item が存在しない空 screen は page 数に数えない。page 数・page 判定は favorites 行の集計から導く (workspaceScreens 表には依存しない)。
    - page 順は `screen` 値の昇順とし、表示上の page 番号はこの順の連番 (1 始まり) とする。`screen` の飛び番は詰めて連番化する。
    - page 別の件数は、**item 総数** (当該 page の workspace 行数。folder・widget を含む)、**folder 数** (`itemType = 2: ITEM_TYPE_FOLDER`)、**widget 数** (`itemType = 4: ITEM_TYPE_APPWIDGET` と `itemType = 5: ITEM_TYPE_CUSTOM_APPWIDGET` の2種) の3種。folder 内部の child は親 folder の1行として数え、二重計上しない。app (0) / shortcut (1) / deep shortcut (6) / app pair (10) 等その他の itemType は個別件数を表示しない (item 総数に含まれる)。
- **表示条件の独立性**: caption・page 要約は layout を含む backup (`info.contents` が `INCLUDE_LAYOUT_AND_SETTINGS` を含む) について、screenshot の有無・orientation (portrait / landscape) に依存せず同一の内容を表示する。配置場所は portrait の `DummyLauncherBox` の外 (options 領域) とし、landscape でも表示が維持される。利用者が layout の restore を checkbox で外した場合も情報提供は継続する (backup の性質を示す情報であるため)。
- **失敗時の typed 表示**: `launcher.db` の読み取りや解析に失敗した場合 (entry 不在・破損・重複 entry・schema 不一致・size 上限超過を含む)、確認画面は screenshot preview と restore 操作を維持し、page 識別情報の欄に「page 情報を表示できない」旨の typed 文言を表示する。restore 自体を失敗扱いにしない。この unavailable 状態は backup 全体の parse 失敗 (`RestoreBackupUiState.Error`) とは別 state である。
- **解析の非同期分離**: page 要約の解析は `readInfoAndPreview()` 完了後に関係なく並行して進められ、確認画面の表示 (Success state) を解析完了まで待たせない。解析中は page 情報欄に何も表示せず (追加の pending 表示はしない)、完了時に caption・要約が現れる。
- strings は `values/` と `values-ja/` の両方へ追加する (#123 の日本語 fallback 禁止契約)。
- a11y: 追加する caption・要約は text として screen reader で到達可能であり、focus順は「caption → 要約 → contents 選択 → restore button」の自然な順を保つ。font scaling に追従し、要約が長い場合は scroll 可能な領域に置く。runtime の TalkBack 挙動は手動 evidence (AC-8) で確認する。

## Non-goals

- backup / restore data format (`BackupInfo` proto、zip entry 構成) の変更。page 毎の screenshot 追加等も含め、必要性が確定するまで行わない (「調査事項」の残項目は Open questions へ分離)。
- graphical な複数 page preview (pager / swipe / page indicator) の実装。page 識別情報の text 表示で事前確認の最低線を満たす。graphical 拡張は Open questions の決定待ち。
- app title・app icon 等による page 内容の同定。本改訂では件数集計のみに確定し、title 表示は別 Issue として追跡する (Open questions 参照)。
- `LauncherPreviewRenderer` 等 `com.android.launcher3` / AOSP 由来 file への変更。本 spec の最小経路は `app.lawnchair.backup` 配下と string resource で完結する。
- Nova backup restore (`RestoreNovaBackupScreen`) の preview。Nova 流し込みは screenshot を持たず、別 workflow である。
- restore transaction / critical section (`LawnchairBackup.restore`、#58 / ADR-0011 系列) の変更。本 spec は読み取り専用の確認 UI 追加であり、restore 実行経路へ触れない。
- Organizer recovery snapshot (#230) の UI、および restore 実行後の結果表示。
- backup 作成画面 (`CreateBackupScreen`) の変更。

## Domain language

- **保存された page (saved page)**: backup zip の `launcher.db` において `favorites.container = -100` (workspace) かつ当該 `screen` に所属する item が1つ以上存在する page。本 spec の page 数・要約はこの定義に基づく。空 screen・hotseat は含まない。

`CONTEXT.md` への反映は承認時に判断する (実装語のみであれば追加しない)。

## Behavior scenarios

### Scenario: multi-page backup の確認

Given layout を含む backup が 3 つの非空 workspace page 分の favorites を保存しており
When ユーザーが restore 画面を開く
Then graphical preview には従来どおり先頭 page の screenshot が表示される
And preview 近傍に preview 画像に保存された page の全部が表示されない可能性がある旨の caption が表示される
And 保存された非空 page 数 (3) と各 page の要約 (page 別の item / folder / widget 件数) が表示される
And page は screen 値の昇順で Page 1..3 の連番表示となる

### Scenario: single-page backup の確認

Given backup の非空 workspace page が先頭 screen の1つのみであり
When ユーザーが restore 画面を開く
Then caption も page 要約も表示されず、現行の表示のまま追加の冗長な情報を示さない

### Scenario: 先頭 screen が空で item が以降の page のみ

Given 先頭 screen (workspaceScreens の screenRank 最小、表がなければ screen 0) に workspace item がなく、item が2番目以降の screen にのみ存在する場合
When ユーザーが restore 画面を開く
Then 非空 page が先頭 screen 以外に存在するため caption と page 要約が表示される (非空 page 数が1でも表示する)

### Scenario: 空の workspace / hotseat のみ

Given backup の favorites に workspace item が存在しない (hotseat のみ、または完全に空) 場合
When ユーザーが restore 画面を開く
Then page 数 0 として扱い、caption・page 要約は表示しない (single-page と同じ挙動)

### Scenario: layout を含まない backup

Given backup の contents が wallpaper のみであり
When ユーザーが restore 画面を開く
Then page caption・page 要約は表示されない (preview の screenshot も現行どおり非表示)

### Scenario: orientation と contents 選択

Given multi-page の backup を開き
When 端末を回転させる、または layout の contents checkbox を外す
Then caption・page 要約の情報は表示され続け、restore button の有効条件は現行のまま変化しない

### Scenario: launcher.db 解析失敗

Given zip 内の `launcher.db` が破損・読み取り不能・entry 不在・schema 不一致・size 上限超過のいずれかであり
When ユーザーが restore 画面を開く
Then screenshot preview と contents 選択・restore button は現行どおり動作する
And page 識別情報の欄には「page 情報を表示できない」旨の typed 文言のみが表示される
And restore 実行は block されず、解析失敗は diagnostic log に記録される

### Scenario: 巨大・異常な favorites

Given 解析の結果 page 数が異常に大きい値 (例: screenId の飛び番を含む) であり
When restore 画面が page 要約を表示する
Then 表示は画面を壊さない範囲に制限・省略され (scroll 可能、page 数は全件表示)、restore 可否は変化しない
And `launcher.db` entry が size 上限 (実装で定義、既定 64 MB) を超える場合、解析を打ち切り unavailable 表示とする

### Scenario: cancel

Given いずれの状態でも
When ユーザーが restore を実行せずに画面を離れる
Then launcher DB への書き込みは一切行われない。解析のための cache directory 内 一時 file は完了時の解析処理によって削除される (process 終了時に残り得るのは cache のみで、永続領域への残存はない)

## Data and state

- 読む data: 選択された backup zip の `info` (contents flag) と `launcher.db` entry (favorites 表の `container` / `screen` / `itemType` 読み取り)。正本は zip 自体であり、追加の永続化は行わない。
- zip 読み取りの安全契約: entry は**完全一致の `launcher.db`** のみを受け付け、zip 内の entry 名から出力 path を構成しない (出力先は内部で生成した一時 file)。同名 entry が複数ある場合・entry が存在しない場合は unavailable とする。entry の uncompressed size が上限 (既定 64 MB) を超える場合は unavailable とする。
- 一時的作業領域: `context.cacheDir` 配下に**解析1回ごとに一意な名前**で作成し、DB を close した後 `finally` で削除する。削除は best-effort とし、失敗時は diagnostic log のみ (cache であるため system により回収され得る)。
- 永続化する data: なし。backup format・`BackupInfo` proto・launcher DB は一切変更しない。
- migration / backup compat: zip format・proto は不変のため backup 間の compat 層は不要。`LawnchairBackup` が書き出してきた `BACKUP_VERSION` は導入 (commit `2038c6722c`) 以来一貫して 1 のみであるため、db 解析は schema の必要 column (`favorites.container` / `screen` / `itemType`) が存在するかを検証し、欠損時は unavailable とする (backup 全体の Error に昇格しない)。
- rollback: 本機能は UI 追加のみであり、revert で機能表面が消えるだけで data 上の影響はない。
- layout 安全規約への影響: なし。`favorites` への書き込みを伴わない。

## Permissions, privacy, and security

- 追加 permission なし。既存の SAF document access (選択された zip の uri) のみを使う。
- zip 読み取りは entry 名完全一致・内部生成 path のみで行い、zip entry 名による path traversal を構造的に生じさせない。
- favorites の item 種別数は app 配置の指標になり得るが、画面表示のみで外部送信しない。diagnostic log に page 数・item 数の集計値のみ記録し、app 名等の個別 item 情報は log に出さない。

## Accessibility and localization

- caption と page 要約は text として screen reader で読み上げ可能にする。preview `Image` は現行どおり装飾 (`contentDescription = null`) とし、情報は text 側が担う。
- page 要約が複数行になる場合、scroll 可能な領域に置き、focus 順を「caption → 要約 → contents 選択 → restore button」の自然な順に保つ。
- font scaling に追従する text layout を使う (固定高の行を仮定しない)。
- strings は `values/` と `values-ja/` の両方に追加する。
- 実機での TalkBack による読み上げ確認は手動 evidence で行う (semantics dump に加えて TalkBack での到達性を確認する)。

## Acceptance criteria

- [ ] AC-1: layout を含む backup のうち、先頭 screen (workspaceScreens の screenRank 最小、表がなければ renderer と同じ screen 0) 以外に非空 page が存在するものを restore 画面で開いたとき、preview に保存 page の全部が表示されない可能性がある旨の caption と、非空 page 数・各 page の要約 (item / folder / widget 件数) が表示される。page は screen 昇順の連番表示。
- [ ] AC-2: 非空 page が先頭 screen のみの backup では caption・要約が表示されない。layout を含まない backup でも表示されない。
- [ ] AC-3: `launcher.db` 解析失敗時 (entry 不在・破損・重複・schema 不一致・size 超過)、screenshot preview と restore 操作は維持され、page 情報不可の typed 文言が表示される。restore は block されず、backup 全体の Error にもならない。zip level の失敗は unit test で、SQLite level の失敗 (open 不能・schema 欠損) は emulator で検証する。
- [ ] AC-4: 解析は zip を読み取り専用で扱い、entry 名完全一致と内部生成の一時 file を使う。正常完了時に一時 file は削除される。favorites への書き込みを一切行わない。
- [ ] AC-5: 既知の favorites 構成の fixture を用いた JVM unit test で、page 数・page 別集計 (folder の二重計上なし・hotseat 除外・空 screen 除外・screen 飛び番の詰め) が決定的に検証される。zip 抽出の失敗注入 (traversal 名・重複 entry・ truncation・size 上限超過) も同様に検証される。
- [ ] AC-6: caption・page 要約は portrait / landscape 両方で表示され、layout checkbox を外しても表示が維持される。解析は Success state 表示を block しない。
- [ ] AC-7: 追加 string が `values/` と `values-ja/` の両方に存在する。
- [ ] AC-8: representative device/emulator で 2 page layout の backup → restore 画面確認 → restore 実行までの手動 evidence が記録される (caption・要約の表示、TalkBack による読み上げ確認、実 launcher.db を用いた解析経路、および restore 実行後に復元された workspace の page 構成が backup の保存内容と一致することの確認を含む)。
- [ ] AC-9: upstream 由来の制約であること、本修正が `app.lawnchair.backup` 配下の fork 固有変更 (AOSP patch なし) である方針が plan に記録される。実装 PR は `lawnchair/src/app/lawnchair/backup/**` 変更として high-risk gate (独立 audit 記録 + `final-status` CI 成功) を満たす。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | JVM unit test (`tests/unit/app/lawnchair/backup/`) — 集計 logic と zip 抽出の pure JVM 実装に対する fixture test。実機での UI 表示は AC-8 |
| AC-2 | 同上 unit test (single-page / hotseat-only / 空 favorites fixture) |
| AC-3 | unit test: zip level の失敗注入 (entry 不在・entry 破損・重複 entry・size 超過)。SQLite level の失敗 (open 不能・schema 欠損) は AC-8 の emulator evidence |
| AC-4 | 同上 unit test (解析後の作業 file 削除、内部生成 path、favorites 書き込みなし) |
| AC-5 | 同上 unit test。screenId 飛び番・folder/widget 混在・folder 二重計上なし・先頭 screen 空の境界 fixture を含む |
| AC-6 | unit test (ViewModel state の pending 分離) + AC-8 の手動確認 |
| AC-7 | `git grep` / string resource 存在検証 (unit test または PR evidence) |
| AC-8 | emulator (2 page layout) での手動確認 screenshot / 録画 + TalkBack 読み上げ確認。実 launcher.db を用いた解析経路、および restore 実行後の workspace page 構成が backup 保存内容と一致することの確認を含む |
| AC-9 | plan.md の記載、PR 本文、および `docs/assessment/pr-<PR番号>-<slug>.md` |

## Open questions

- **app title 等の page 内容の具体的識別情報**: 本改訂では件数集計のみに確定した。title 表示の要望が生じた場合は別 Issue で追跡し、privacy 粒度・実装 cost を改めて評価する。
- **graphical 複数 page preview の要否**: 本 spec の text 要約で事前確認の不足が解消されるか、それとも page 毎の画像 prompt が必要か。必要になった場合は (a) backup format への page 別 screenshot 追加、(b) restore 時に `launcher.db` から `LauncherPreviewRenderer` 系で live render、のいずれかを検討し、別 Issue + format 変更なら spec/ADR を分離する。
- **upstream 報告**: 先頭 page のみの preview は upstream Lawnchair の設計である。upstream への改善報告を行うかは本 Issue の PR で判断し、実施する場合は upstream Issue chooser から行う (fork の Issue に混ぜない)。

## Change history

- 2026-09-10: Draft created for #233. 先頭 page 限定が upstream の構造的な設計であることを code evidence とともに確定し、format 不変・読み取り専用の page 識別情報表示を中心とする契約として起草した。
- 2026-09-11: codex review (gpt-6-astra) の指摘へ対応。caption 文言を two-panel でも嘘のならない「一部のみ」表現へ修正、非空 page の定義と集計規則 (folder 二重計上禁止・hotseat 除外・screen 連番化) を確定、landscape / checkbox / screenshot 欠落時の表示条件を明確化、zip 読み取りの安全契約 (entry 完全一致・内部生成 path・重複/size 上限)、一時 file の一意性と削除契約、解析の非同期分離、schema 検証失敗の unavailable 扱い、test 環境の実態 (JVM unit test) への整合、high-risk gate 要件を追記した。app title 表示は non-goal へ確定分離。
- 2026-09-11 (2nd): 再レビューの指摘へ対応。caption 判定を「非空 page 数 >= 2」から「先頭 screen 以外に非空 page が存在」へ変更し、two-panel での断定を避ける「可能性」表記へ修正。widget 数の itemType (4/5) を明示。SQLite level の失敗検証を emulator evidence へ分離し、AC-8 に restore 実行後の page 構成一致確認 (round-trip) を追加した。
- 2026-09-11 (3rd): 第3ラウンドレビューの指摘へ対応。`workspaceScreens` 表がない場合の先頭 screen fallback を「非空 screen の最小値」から renderer の実際の描画対象である `screen 0` へ変更し、screen 0 が空の backup で caption が消える隙間を解消した。
