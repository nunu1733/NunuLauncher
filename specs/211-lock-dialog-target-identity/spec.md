---
issue: "#211"
status: implemented
requirements:
  - FR-003
updated: 2026-09-09
---

# Placement lock確認ダイアログが対象placementの名前・位置を表示する

## Problem

[Issue #211](https://github.com/nunu1733/NunuLauncher/issues/211)（[Exploratory UX
review](https://github.com/nunu1733/NunuLauncher/blob/main/.ux-review/ux-exploratory-review-2026-09-05.md)
2026-09-05, F-05 / major・high confidence）が、`Placement locks` 管理画面の lock
確認ダイアログが対象の配置アイテムを示していないことを報告した。実装上の根拠は次の
とおり（head `b25f20ca7c` 時点、2026-09-09 確認）:

- 管理画面 (`PlacementLockPreferences`) の各行は `LockRow` で、app 名
  (`entry.title`) と配置概要 (`placementDescription(entry, profileLabel)`) を表示し、
  trailing に plain text の状態ラベルを表示する (spec 38 §Launcher UI surfaces)。
- 行の tap で `openDialog(entry)` が `LockChangeDialog` を開くが、ダイアログ本文は
  `organizer_lock_dialog_current_state` (`Current state: %1$s`)、scope 説明、
  effect note のみで構成され（`PlacementLockPreferences.kt:305-378`）、
  **対象の app 名・配置概要は表示されない**。
- ダイアログは centered で tap した行を覆い隠すため、ダイアログ表示中に
  対象行を再確認することもできない。

同名 placement が複数ある状態（`Google — Home screen 1` と
`Google — Inside a folder, position 1` 等）では、ダイアログの文言だけでは
どちらを lock/unlock するか区別できず、誤った placement を lock して
意図した item が移動され続ける silent ミスが起きうる。ユーザーは
Cancel して行を再確認する手順を強制される。

review が指摘する対象は管理画面の確認ダイアログである。long-press ポップアップの
`OrganizerLockShortcut` 側ダイアログ (`organizer_lock_dialog_*` を共有する) は、
ダイアログを開く操作の対象アイコンが画面上に可視であるため、本 Issue の問題
（対象が画面から失われる）は発生しない。本 spec は管理画面のみを対象とする。

## Outcome

`Placement locks` 管理画面の lock 状態変更確認ダイアログ (`LockChangeDialog` の
`Available` 経路) で、ユーザーはダイアログ文言だけから次を説明できる:

1. 変更対象が「tap された行」と同じ app 名 (`entry.title` の表示文字列) であること。
2. 変更対象が「tap された行」と同じ配置概要 (行の description と同一の合成値:
   placement 種別 + profile label + effectively-protected 表示) であること。
3. **同名 placement が複数ある状態で、ダイアログ文言だけでどちらの placement を
   変更するか区別して説明できること**（受入条件の中核。行 description と同一の
   配置概要を表示するため、page・folder 内位置・profile の違いで区別できる）。

state label (`Current state: Unlocked` 等)、scope 説明、effect note、UNKNOWN の
review intro、confirm/cancel の動作は現行維持である。`Unavailable` 経路の
ダイアログ（lock 対象外 item の rejection 表示）は対象外である。

## Scope

- **`LockChangeDialog` の `Available` 経路への対象行追加**: ダイアログ本文の先頭に、
  tap された `LockStateEntry` から構成した対象行を追加する。
  - 構成値は行 (`LockRow`) と同一の source から取る: 表示タイトルは
    `entry.title.textOrFallback(entry)` と同一の合成規則、配置概要は
    `placementDescription(entry, profileLabel)` と**同一の合成関数**を使う
    （D1）。
  - **衝突区別行 (D4)**: 行の配置概要は placement 種別への縮約であり、同一
    page の別 cell、別 folder の同 position、別 app pair が同じ文言になる
    （PR #264 review P1）。よってダイアログは配置概要行に続き、
    `LockPlacementSummary` が保持する識別 detail と管理画面 listing から
    解決した parent title で構成した**区別行**を独立した `Text` node として
    常時表示する: Desktop は cell 座標 (`organizer_lock_dialog_target_position`)、
    folder child は所属 folder 名 (`organizer_lock_dialog_target_folder`)、
    app pair member はペア名 (`organizer_lock_dialog_target_app_pair`) +
    parent 配置 + member の split stage (`organizer_lock_dialog_split_top_left` /
    `organizer_lock_dialog_split_bottom_right`)。区別行は衝突の有無によらず
    常時表示する（表示条件の分岐を作らない）。
  - 対象行は 1 行 1 事実の単純テキストとし、`title` と `description` を
    別 node として読める形にする（a11y 契約は既存のダイアログ text node
    規約に従う）。
  - 新規 string (en/ja 同時追加、#123 / #161 契約):
    `organizer_lock_dialog_target_title` (`Target: %1$s`)、
    `organizer_lock_dialog_target_position` (`Position: row %1$d, column %2$d`)、
    `organizer_lock_dialog_target_folder` (`Folder: %1$s`)、
    `organizer_lock_dialog_target_app_pair` (`App pair: %1$s`)。
- **`PlacementLockPreferences` 内での値の単一供給**: `placementDescription` は
  既に同 file 内の private 合成関数であり、行描画とダイアログ描画の両方から
  呼ばれる。title の fallback 規約も既存 `textOrFallback` を共有する。
  locks module (`app.lawnchair.organizer.locks`)、`LockStateEntry` の shape、
  `LockAuthoringModule` の interface は無変更である。
- **test 追加**: `OrganizerLockScreenTest` に、同名 placement が複数ある
  fixture で、(a) 行 description とダイアログの対象行 description が同一である
  こと、(b) ダイアログ表示中に他方の placement がダイアログ文言に現れない
  こと、を検証する test を追加する（Issue 終了条件の 2 番目）。
- **spec 38 への追記** (docs-only): §Launcher UI surfaces の管理画面の記述へ、
  確認ダイアログが行と同一の識別情報を表示する旨を追記する。spec 38 の
  AC / oracle は変更しない（本 spec が固有の AC を持つ）。
- [plan.md](./plan.md) を作成する（変更 module、文言、検証）。

## Non-goals

- long-press ポップアップ (`OrganizerLockShortcut`) 側ダイアログへの対象行追加。
  対象アイコンが可視の文脈であり、`ItemInfo` 由来の title 取得は本 spec の
  問題設定（行が覆い隠される）と別の作業になる。必要になった時点で別 Issue とする。
- trailing の状態ラベル (`Unlocked`) が control に見えない課題 (review F-06)、
  `No placements need review.` の誤読 (F-12) は本 Issue では扱わない
  （Issue 補足に分離済み）。
- 行とダイアログの対象表示を超えた、lock 対象の一括選択・検索・絞り込み等の
  管理画面機能追加。
- lock/unlock 状態遷移、`UserReviewedIntent` の意味論、`LockAuthoringModule`
  の契約、Launcher DB (`organizerLockState` 列) への変更。
- batch review 確認ダイアログ (`Review N placements as …?`) の詳細化。
  対象は件数と一括方向であり、単一 placement の識別問題ではない。
- ダイアログの layout 構造変更（centered AlertDialog から別形式への変更）や
  リッチな card 描画。本文 1 行の追加に留める。

## Domain language

`CONTEXT.md` への追加は不要である。表示するのは既存の「配置 (Placement)」
と「配置アイテム (Layout Item)」の user-facing projection であり、
新しいドメイン概念を導入しない。`LockStateEntry` が既に保持する
`title` / `placement` / `profile` の再構成にすぎず、
planning・application・locks のいずれの契約も変更しない。

## Design decisions

### D1: 対象行の値は行描画と同一の合成経路から取る（表示値の同一性を構造で保証する）

「dialog が正しい対象を表示する」を検証可能にする鍵は、表示値が tap された
行の表示値と**同一の合成経路**で作られることである。`placementDescription` と
`textOrFallback` は同 file 内の private 関数であり、行 (`LockRow`) と
ダイアログ (`LockChangeDialog`) の両方から呼ぶだけで、行とダイアログで
description が分岐する経路は存在しなくなる。

代替案として、`LockStateEntry` へ description 文字列を事前合成して載せる
（module 側で合成する）案があったが、(a) `LockStateEntry` は UI 非依存の
domain 型であり、localized 文字列を載せるのは層違反である、(b) profile label
の解決には `UserCache` が必要で module 側に UI 依存を持ち込む、の理由で
却下した。description 合成は UI 層 (`PlacementLockPreferences`) に留める。

テストは「同一合成関数の呼び出し」という構造ではなく、fixture 上の
同一行 description 文字列が行とダイアログの両方に現れることを
Compose test で観測的に検証する（構造の直接検証をしないテスト規約に従う）。

### D2: 対象行は prefix string で導入し、単純テキスト node のままにする

ダイアログ本文は既存実装が `buildString` で 1 つの `Text` node を組んでいる。
対象行を「`Target:` prefix + title」+「配置概要」の 2 node にするか、
1 つの本文文字列へ連結するかの選択がある。

1 本文への連結は diff が最小だが、TalkBack で title と配置概要が 1 文に
連結され、同名行の区別 reading が長くなる。対して 2 node は title・description
を独立に読めるため spec 38 の a11y 契約（state/effect が screen reader で
読める、単一意味の node）と整合する。

よって対象行は、(1) `organizer_lock_dialog_target_title` (`Target: %1$s`)
で title を導入する行と、(2) `placementDescription` の結果（profile label /
effectively-protected を含む同一合成値）の行、の 2 つの `Text` node として
本文の先頭へ置く。既存の state/scope/effect 本文は、D4 の区別行（該当時）を
挟んで現行のまま維持する。これにより既存 test
(`folderLockDialogExplainsChildCoverageBeforeMutation` の substring assert 等)
は壊れない。

### D3: `Unavailable` 経路とポップアップ側は対象外とする

`LockExplanation.Unavailable` のダイアログは、lock 対象外 item の
rejection 理由を表示するものであり、対象 placement の識別は操作の続行判断に
影響しない。ポップアップ側 (`OrganizerLockShortcut`) は前述のとおり対象
アイコンが可視である。両者を対象に含めると、ポップアップ側は
`ItemInfo`→title 解決という別の実装課題を持ち込むため、本 spec では
管理画面の `Available` 経路に絞る。ポップアップ側の必要性は
UX review の観測範囲外であり、必要になった時点で別 Issue とする。

### D4: 衝突区別行 — `LockPlacementSummary` の detail と listing から解決した parent title を、衝突の有無によらず常時表示する

PR #264 review (P1) が、行の配置概要の縮約では AC-2 を一般には成立させないことを指摘した:
`placementDescription` は Desktop で page 番号のみ (`LockPlacementSummary.Desktop`
が cell/span 保有)、folder child で rank のみ (`InFolder` が parent 保有)、
app pair member で固定文のみ (`InAppPair` が parent 保有) であり、次が同じ
dialog text になる:

- 同一 app 名の icon が同一 Home page の別 cell に 2 件
- 同一 app 名が別 folder の同 position に 2 件
- 同一 app 名が異なる app pair に属する 2 件

情報源は `LockStateEntry.placement` (`LockPlacementSummary`) に既に保持される
detail と、管理画面 listing (既に解決済みの `entries` state) から引ける
parent 行の title。planning domain 型 (`GridCell`) や `ItemId` を直接表示せず、
UI 層で human readable 文へ合成する。parent title は
`listing.associateBy { it.item }` (dialog 表示中に UI が既に保持する値のみ;
新規 module 呼び出しを追加しない) から解決し、解決不能時 (title blank) は
raw item id を fallback とする — id は画面上の行 fallback (`textOrFallback`)
と同じ既得情報であり、新たな公開は発生しない。

「衝突があるときだけ表示する」条件分岐は、衝突判定自体が listing 全体と
entry 集合の一致を要求して表示の再現性を下げるため採らない。区別行は
Desktop / folder child / app pair member で**常時**表示し、Dock (rank 一意) と
Unsupported では省略する。表示 node 数が placement 種別で変化するが、
その変化は種別の関数であり listing の関数ではないため、dialog 描画は
決定的である。

残余衝突の解消 (PR #264 second review P1): folder 名・app pair 名は
user 編集可能で一意性を持たないため、区別行に parent title だけを載せると、
同名の parent が複数あり、そのそれぞれに同名 item が同 position で存在する
場合に dialog 文言が再び同一になる。よって folder / app pair の区別行は、
parent title に**parent 自身の配置**（Desktop なら page + cell
`Home screen N · row Y, column X`、Dock なら slot 番号）を連結する。

同一 pair 内の衝突の解消 (PR #264 third review P1): valid な app pair は
異なる split stage を持つ 2 member を許し、member の表示名の一意性は要求
しないため、同一 pair 内の同名 2 member は parent 情報だけでは区別できない。
よって app pair member の区別行は **member の split stage**
(`Member: top or left` / `Member: bottom or right`) も連結する。stage は
planner 契約により pair 内で必ず異なるため、member の区別はこの 1 語で
成立する。そのため `LockPlacementSummary.InAppPair` は stage field を
追加する（表示 model の拡張であり、capture / planner / application 契約と
DB は無変更。AC-5 をこの範囲で調整）。
これにより dialog の対象識別は「item title + rank + parent title +
parent の page + parent の cell」の組になり、2 つの placement が完全に
衝突するのは parent 同士が同一 page の同一 cell を共有する場合のみであるが、
これは no overlap 不変条件により到達不能である。parent の placement が
解決不能な場合のみ、title 導入のみに後退する（fail toward less detail）。

## Behavior scenarios

### Scenario: Dialog names the tapped row

Given 管理画面に `App A — Home screen 1` と `App A — Inside a folder, position 1`
の 2 行が表示されている（同名・異配置）,

When ユーザーが `App A — Home screen 1` の行を tap して確認ダイアログを開く,

Then ダイアログには、行と同一の表示タイトル (`App A`) を導入する対象行と、
行と同一の配置概要 (`Home screen 1` + profile label があればそれ) を示す行が
本文の先頭に表示される,

And `Current state: Unlocked` と scope/effect 説明は現行どおり表示される,

And confirm / Cancel の動作は現行どおりである（confirm は tap された行の
`entry.item` への `UserReviewedIntent` 付き変更要求である）。

### Scenario: Same-name rows are distinguishable from the dialog text alone

Given 同名 placement が複数ある状態で、片方の行の確認ダイアログが開いている,

When ダイアログ本文を観測する,

Then ダイアログの配置概要行は、開いた行の `placementDescription` 値と等しく、
開いていない同名行の `placementDescription` 値とは異なる（page 番号、
folder 内位置、Dock 位置、profile のいずれかで区別される）,

And したがってダイアログ文言だけでどちらの placement を変更するか説明できる
（Issue 受入条件）。

### Scenario: Colliding descriptions are resolved by the disambiguator line

Given 同一 Home page の別 cell に同名 icon が 2 件（row description が
`Home screen 1` と同一）、または別 folder の同 position に同名 item が 2 件
（row description が `Inside a folder, position 1` と同一）ある,

When それぞれの行の確認ダイアログを開く,

Then 各ダイアログには区別行が表示される: Desktop では
`Position: row %1$d, column %2$d`（cell 座標, 1-based）、folder child では
`Folder: <parent title>`（parent title が解決不能のときは raw item id）、
app pair member では `App pair: <parent title>`,

And 同一 row description を持つ 2 行のダイアログは区別行によって相異なる
文言になり、ダイアログ文言だけで対象を識別できる (D4, PR #264 review P1)。

### Scenario: Same-named parents are resolved by the parent's own placement

Given 同名の folder（または app pair）が 2 件あり、そのそれぞれに同名 item が
同 position で存在する（row description、target title、parent title の
すべてが同一になる）,

When それぞれの行の確認ダイアログを開く,

Then folder / app pair の区別行は parent title に parent 自身の配置
（Desktop なら `Home screen N · row Y, column X`、Dock なら `Dock N`）を
連結した文言になり、2 つの dialog text は parent の page・cell の違いで
相異なる (D4, PR #264 second review P1)。

And 2 つの placement が完全に衝突するのは parent 同士が同一 page の同一
cell を共有する場合のみであり、これは no overlap 不変条件で到達不能である。

### Scenario: Same-named members within one app pair are resolved by their split stage

Given 1 つの valid な app pair（2 member、title `Duo`）に、同名の 2 member
（同 display name、TOP_OR_LEFT / BOTTOM_OR_RIGHT の異なる stage）が存在する
— 両 member の target title・row description・parent title・parent 配置は
完全に一致する,

When それぞれの member 行の確認ダイアログを開く,

Then app pair member の区別行は `App pair: <pair title> · <pair の配置> ·
Member: <split stage>` となり、2 つの dialog text は split stage の違いで
相異なる (D4, PR #264 third review P1),

And planner 契約により pair 内の 2 member は必ず異なる stage を持つため、
同一 pair 内の member 衝突は stage 語で必ず解消される。

### Scenario: Dock and unsupported rows show no disambiguator

Given Dock 行（rank が単一 Dock 内で一意）または Unsupported 行の
ダイアログを開いた,

When 対象行が描画される,

Then 区別行は表示されず、配置概要行のみが対象の配置を示す（D4 の省略規則）。

### Scenario: Dialog opens without any write

Given 管理画面の任意の行で確認ダイアログを開いた,

When ダイアログが表示される,

Then lock state の書込みは発生しない（既存契約: 確認済み
`UserReviewedIntent` がない変更は行われない。既存 test
`unknownReviewResolvesOnlyThroughConfirmedDialog` の継続成功）。

### Scenario: Unknown review dialog also names the target

Given `UNKNOWN` 行の確認ダイアログを開いた,

When ダイアログが表示される,

Then 対象行（title 導入行 + 配置概要行）が review intro の前に表示される
（対象行は本文の先頭に置くのが規約であり、UNKNOWN のみ後回しにしない）,

And `Keep locked` / `Mark unlocked` / Cancel の動作は現行どおりである。

### Scenario: Dialog with no profile label and no parent protection

Given 単一 profile で effectively-protected でない行のダイアログを開いた,

When 対象行が描画される,

Then 配置概要行は placement 種別のみの値（`Home screen 1` 等）となり、
空の node は表示しない（`placementDescription` が 1 区分のみの値を返す
既存合成規約の再利用）。

## Data and state

- 読む data: 開いている `LockStateEntry`（既存。dialog 表示中に UI が既に保持している
  値のみ。新規 capture、新規 DB 読み取り、module 呼び出し追加はしない）。
- 永続化する data: なし。Launcher DB、lock state、recovery store への変更はない。
- 表示のみの変更であり、UI state (`dialogEntry` / `dialogExplanation`) の
  shape 変更は不要である。
- migration、backup/restore への影響: なし。

## Permissions, privacy, and security

None。追加 permission、外部送信、新規 sensitive data はない。表示する
title と placement 概要は管理画面の行が既に表示している同一値であり、
新たな情報公開はない。

## Accessibility and localization

- 対象行は title 導入行と配置概要行の独立した 2 `Text` node とし、TalkBack で
  単一意味ずつ読める（D2）。dialog title・button の focus 構成は既存の
  AlertDialog 規約に従う。
- strings は `values/` と `values-ja/` へ同時に追加する (#123 / #161 契約)。
  ja は glossary の「配置 (placement)」「ロック」語彙に従う
  （例: `対象: %1$s`）。
- 200% font scale で対象行が wrap しても dialog が崩れないことを
  screenshot で確認する（spec 52 a11y 契約に準拠した目視確認）。

## Acceptance criteria

| AC | Acceptance criterion | Required evidence |
|---|---|---|
| AC-1 | `LockChangeDialog` (`Available` 経路) の本文先頭に、開いた行と同一の表示タイトルを導入する対象行と、同一の配置概要（`placementDescription` と同一合成値）を示す行が表示される。state / scope / effect / review intro は現行維持。 | `OrganizerLockScreenTest` への Compose test 追加（同名 fixture で対象行の title と description を assert）+ screenshot |
| AC-2 | 同名 placement が複数ある fixture で、ダイアログの配置概要行が開いた行の description と等しく、他方と異なる。ダイアログ文言だけで対象を区別して説明できる。**row description が同一になる衝突（同 page 別 cell / 別 folder 同 position / 同名 parent × 同名 child 同 position / 同名 app pair × 同名 member / 同一 app pair 内の同名 2 member）でも、区別行 (D4, parent 自身の配置連結 + member split stage を含む) により区別できる。** | `OrganizerLockScreenTest` の同名 fixture test: (a) 行 description == dialog 内 description、(b) 他方の description は dialog に現れない、(c) collision fixture（同 page 別 cell、別 folder 同 position、同名 parent × 同名 child、同名 app pair × 同名 member、同一 valid app pair 内の同名 2 member）で区別行が tap 行ごとに正しく出ること |
| AC-3 | ダイアログを開くだけでは書込みが発生しない（既存契約の維持）。confirm / Cancel 動作は現行どおり。 | 既存 `unknownReviewResolvesOnlyThroughConfirmedDialog` / `busyFailureRendersLocalizedMessage` の継続成功 |
| AC-4 | 対象行（title 導入行・配置概要行・D4 区別行）はそれぞれ独立した `Text` node として TalkBack から読める。strings は en/ja で同時に追加する。 | Compose semantics test（各行の単独 exact-match 分離 assert）+ strings diff (values/ + values-ja/) |
| AC-5 | 表示契約の変更は `LockPlacementSummary` の表示 model 拡張（D4 のため `InAppPair` へ split stage field を追加）に留まり、`LockStateEntry` の shape、`LockAuthoringModule` 契約、planner / application 契約、Launcher DB への変更がない。 | diff review（`organizer/application/**` と DB への変更ゼロ、`organizer/locks/**` は `LockPlacementSummary.InAppPair` の stage field 追加と `placementSummaryOf` の受渡しのみ）+ 既存 JVM gate の継続成功 |

## Test oracle

| AC | Automated/manual evidence |
|---|---|
| AC-1 | `OrganizerLockScreenTest` 新規 test（dialog 表示後、title 導入 string + 行 description の assert）、en/ja screenshot |
| AC-2 | `OrganizerLockScreenTest` 新規 test（同名 2 行 fixture: tap 行の description が dialog に現れること、非 tap 行の description が現れないこと）+ collision fixture test（同 page 別 cell / 別 folder 同 position / 同名 parent / 同名 app pair / 同一 pair 内同名 2 member で区別行を assert） |
| AC-3 | 既存 test 群の継続成功（書込み確認ダイアログの契約 oracle） |
| AC-4 | Compose test での node 分離 assert + strings diff (values / values-ja 同期) |
| AC-5 | diff review + `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` 継続成功 |

## Open questions

None（spec 時点で確定）。D1–D3 が値の供給経路、node 構成、対象範囲を確定した。

## References

- [Issue #211: Placement lock確認ダイアログが対象placementの名前・位置を表示しない](https://github.com/nunu1733/NunuLauncher/issues/211)
- 原典の review report (`ux-exploratory-review-2026-09-05.md`, F-05) は
  ephemeral な作業文書であり、repository には存在しない。観測事実は
  Issue #211 本文が正本として記録している。
- [Spec 38: lock authoring and unknown-state review](../38-lock-authoring-unknown-review/spec.md)
- [Spec 230: restore 確認の復元対象説明](../230-restore-confirmation-target/spec.md)（同一の「確認ダイアログが対象を説明する」パターンの先例）
- [Spec 161: 日本語 UI コピー LQA](../161-japanese-ui-copy-lqa/spec.md)
- [PR #264 review (2026-09-09)](https://github.com/nunu1733/NunuLauncher/pull/264): placement 概要の縮約による AC-2 衝突指摘 (P1) — D4 の正本
- [Quality strategy](../../docs/engineering/quality-strategy.md)

## Change history

- 2026-09-09: Drafted for Issue #211 (UX exploratory review 2026-09-05, F-05)。
  行と同一合成経路からの対象行表示 (D1)、2 node 構成 (D2)、管理画面
  `Available` 経路への絞り込み (D3) を提案。
- 2026-09-09: Plan review 対応。string key を
  `organizer_lock_dialog_target_title` へ変更 (中身が title 導入であるため)、
  ephemeral review report 参照の注記を追加。
- 2026-09-09: Accepted。plan revision 3 (`7ccf3b18cb`) に対する
  in-session review (code-reviewer-2 agent、owner 委譲の Phase 1 gate) の
  Approve により実装へ移行する。
- 2026-09-09: PR #264 review 対応 (P1)。配置概要の縮約では同 page 別 cell /
  別 folder 同 position / 別 app pair が区別できないため、D4 (区別行の常時
  表示) を新設し、新規 3 string (`_target_position` / `_target_folder` /
  `_target_app_pair`) を Scope へ追加。AC-2 必要証憑へ collision fixture
  test を追加。evidence capture test は regression suite から分離する
  (instrumentation argument gate)。
- 2026-09-09: PR #264 re-review 対応。C-1: D4 追加後の dialog へ evidence
  screenshot を gate 経由で再撮影・差し替え (200% font scale の wrap 確認を
  区別行込みで実施)。C-2: Change history の文字化け 1 文字を修正。
  N-4 の残余衝突の認識を D4 へ追記。
- 2026-09-09: PR #264 second review 対応 (P1)。parent title のみの区別行は
  同名 parent で再衝突するため、D4 を改訂し区別行へ parent 自身の配置
  (Desktop: page + cell / Dock: slot) を連結する規約へ変更。AC-2 evidence と
  scenario に同名 parent・同名 app pair の衝突 class を追加。残余衝突は
  no overlap 不変条件により到達不能であることを明記。
- 2026-09-09: PR #264 third review 対応 (P1)。同一 valid app pair 内の
  同名 2 member は parent 情報だけでは区別できないため、`InAppPair` 表示
  model へ split stage を追加し、app pair member の区別行に member stage
  (`Member: top or left` / `Member: bottom or right`) を連結する規約へ変更。
  fixture を valid 2-member pair 構成へ変更し、同 pair 内同名 member の
  衝突 class を回帰固定。AC-5 を表示 model 拡張の範囲で調整。
- 2026-09-10: [PR #264](https://github.com/nunu1733/NunuLauncher/pull/264) を
  squash merge (merge commit `1f1ead86fdad1a7316cd24beddb190a02a10171f`)。
  review head `7fc68a97cc` の CI run
  [34420116789](https://github.com/nunu1733/NunuLauncher/actions/runs/34420116789)
  が全 job pass (`final-status` green)。`Closes #211` により Issue #211 は
  自動 close。受入条件 AC-1〜AC-5 (AC-5 は表示 model 拡張の範囲で調整済み) が
  満たされたため spec を `implemented` へ進める。
