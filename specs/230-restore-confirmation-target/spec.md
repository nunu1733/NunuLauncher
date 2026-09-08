---
issue: "#230"
status: accepted
requirements: []
risk: []
updated: 2026-09-08
---

# restore 確認で「どこへ戻るか」を画面文言から判断できる

## Problem

[Issue #230](https://github.com/nunu1733/NunuLauncher/issues/230) (Astra blind exploratory UX review 2026-09-06, Finding 2) が、Organizer 適用後の `Restore the previous layout` 確認画面に、復元対象を判断する情報が表示されないことを報告した。実装上の根拠は次のとおり (head `3e49ac6871` 時点、2026-09-08 確認):

- `ManualOrganizationRun.State.RecoveryPreview` の描画 (`ManualOrganizationPreferences.kt`) は、`recoveryPreviewMessage()` が返す 1 文のみである:
  - `Restorable` → `manual_organization_recovery_preview`: `Restoring the saved layout requires confirmation.`
  - 非 restoration 結果 → `manual_organization_recovery_not_available` または `manual_organization_apply_concurrent`
- `Restorable` 時に表示される情報は「確認が必要であること」だけであり、次のいずれも表示されない:
  - どの状態へ戻るか (いつの・どの apply の前の状態か)
  - 戻る placement の要約 (何が元へ戻るか)
- 併せて、用語が 3 通りに分かれている: entry action は `Restore the previous layout` (`manual_organization_recovery`)、確認 action は `Restore saved layout` (`manual_organization_recovery_confirm`)、進行/結果は `the saved layout` (`manual_organization_recovering` / `restored`)。同じ対象を指す呼称が画面ごとに揺れている。

review の観測どおり、Restore は Organizer の safety net であるにもかかわらず、確認時点で復元内容を検証できず、ユーザーは直前の Home 状態を記憶していることを要求される。

## Outcome

restore 確認画面 (`State.RecoveryPreview` の `Restorable`) で、ユーザーは画面文言だけから次を説明できる:

1. 戻り先が「この画面で確認した整理の適用前の home layout」であること (いつの状態か)。
2. 直前の整理の適用内容の要約 (例: 「直前の整理での変更: 移動 8 件 / 新規フォルダ 2 個」) を、**apply の履歴表示として**確認できること。restore が実際に何件の配置を書き換えるかの予測値としては表示しない (D4)。
3. 表示する要約が、その確認画面の recovery point を生んだ apply と相関づけられていること。別の apply の要約を表示しない (D2)。
4. `previous layout` / `saved layout` の呼称が、entry action・確認 action・進行・結果の全画面で同じ対象を指していること (D5)。

非 `Restorable` 結果 (NotRestorable / Unavailable / WriterBusy / Concurrent) の表示は現行の typed 文言を維持し、詳細化しない。Cancel は zero-write のまま維持される。

## Scope

- **`State.RecoveryPreview` の表示へ restore 対象の説明と apply 文脈の要約を追加**: `ManualOrganizationRun` が既に process-local に保持する apply 文脈 (`lastVerifiedApply` の `Summary` — `State.Applied(result, summary)` 由来) を確認画面へ渡し、次を表示する。application 契約 (spec 84 の `RecoveryPreviewResult` / `RecoveryPreviewSummary`) は変更しない。
  - 共通の戻り先文: 「保存したレイアウト — この画面で確認した整理を適用する前の状態に戻ります。実行するには確認が必要です。」
  - **apply 履歴行** (truth は apply 時の `Summary`): 「直前の整理での変更: 移動 %1$d 件」を基本形とし、`newFolderCount > 0` なら「新規フォルダ %1$d 個」、`newPageCount > 0` なら「新規ページ %1$d ページ」を同じ行に連結する。件数は apply の履歴値であり、restore が書き換える配置数の予測ではない (D4)。全件 0 の場合は履歴行を省略し、共通の戻り先文のみとする。この場合でも復元対象は一意に説明される。
  - **相関ゲート (D2)**: 履歴行は、表示中の recovery point が `lastVerifiedApply` に記録された `ApplyResult.Applied.pointId` と等価なときだけ表示する。等価でない、または `lastVerifiedApply` が保持されていないときは、履歴行を省略して共通の戻り先文のみで確認を継続する (confirm 可否を変更しない)。
- **用語の統一** — spec 84 が summary effect を `RESTORE_SAVED_LAYOUT` に固定し、spec 13 が復元対象を "the saved layout represented by the recovery point" と定める契約を踏まえ、ユーザー向け呼称を「保存したレイアウト」に統一する:
  - entry action `manual_organization_recovery` は `Restore the previous layout` から `Restore saved layout` 系 (ja: 「保存したレイアウトに戻す」) へ変更する。
  - 確認 action `manual_organization_recovery_confirm`、進行 `manual_organization_recovering`、結果 `manual_organization_recovery_restored` / `not_available` / `failed` は既に「保存したレイアウト」系であり、対象語を変更しない。
  - `manual_organization_recovery_inspecting` の「以前のレイアウト」も同じ語へ統一する。
  - 英語の `previous layout` 残存箇所 (`manual_organization_apply_recovered` 等 apply 結果文) は、自動 recovery が復元する対象も同じ recovery point であるため、同じ語へ統一する。対象は spec 承認時に全 `previous layout` 系 string を列挙して確定する。
- **`manual_organization_recovery_preview` の置換**: `Restoring the saved layout requires confirmation.` を、用語統一後の共通の戻り先文 + 確認要求とする (例: 「保存したレイアウト — この画面で確認した整理を適用する前の状態に戻ります。実行するには確認が必要です。」)。単独の告知文としての役割は共通の戻り先文が担う。
- strings は `values/` と `values-ja/` の両方へ追加・変更する (#123 の日本語 fallback 禁止契約)。
- a11y: 追加行は既存の `FocusTargetText` / `SummaryText` 規約に従う。status 見出しの focus + `liveRegion` 構成は維持し、追加の履歴行は liveRegion 対象外とする (#195 と同一規約)。
- [spec 52](../52-manual-full-organization-vertical-slice/spec.md) の §"Result and recovery" へ、確認画面が apply 文脈の要約を表示する旨を追記する。

## Non-goals

- spec 84 の `RecoveryPreviewResult` / `RecoveryPreviewSummary` / opaque confirmation 契約の変更。詳細な preview 情報 (item identity、配置、件数) を application seam から公開することはしない。本 spec の要約は application から返る値ではなく、同一 run が既に観測した apply 文脈 (`Summary`) から UI 側で構成する。
- restore 先の placement を item 単位で一覧する change list (#195 の restore 版) の実装。`Summary` には item label がなく、item 単位の逆写像を構成するには application 契約の拡張が必要であるため、必要になった時点で別 Issue とする (「望ましい状態」の「必要なら preview / affected items の展開」の判断は先送り)。
- 保存時刻 / generation の人間向け表現 (`when` の absolute time 表示)。recovery point の creation time は `RecoveryPreviewSummary` に含まれず、application 契約の拡張が必要であるため先送りする。戻り先は「この画面で確認した整理の適用前」で一意に説明される。
- restore transaction、recovery protocol、retention、reconciliation の変更。spec 13 の契約は無変更である。
- `RecoveryResult` 側 (restore 実行後) の文言詳細化。成功/失敗の typed 文言は現行維持とする (review が指摘したのは確認時点の判断材料であり、実行後の表示は結果語で十分に説明される)。
- diagnostics record 形式の変更。
- 適用前の Home をユーザーが直接確認するための別経路 (Home を長押しして一時的に前状態を見る等) の新設。

## Domain language

`CONTEXT.md` への追加は不要である。`recovery point` の既存定義 (「整理runの適用前へアプリ内操作で戻すために保存された、検証済みの復旧状態」) と spec 84 の summary 契約を変更しない。UI で統一するユーザー向け呼称「保存したレイアウト (saved layout)」は recovery point の user-facing projection であり、新しいドメイン概念ではない。

## Design decisions

### D1: 情報源 — **application 契約を拡張せず、run が既に保持する apply 文脈の `Summary` を使う**

spec 84 は `RecoveryPreviewSummary` を意図的に「`RESTORE_SAVED_LAYOUT` + confirmation required + revision conditional の 3 定数のみ」と固定し、item count、件数、時刻、revision を公開しない。これは privacy 契約 (RP-AC-05) と一体の決定であり、preview seam を太らせることは spec 84 の re-accept を要求する。

一方、restore 確認が到達可能な経路は 1 つしかない: `ManualOrganizationRun.beginRecoveryPreview()` は `lastVerifiedApply != null` かつ `appliedPoint != null` のときだけ inspection を実行し、`lastVerifiedApply` は `State.Applied(result, summary)` が検証済み適用で確定したときだけ記録される。つまり確認画面が表示されるとき、同一 run が同一 recovery point を生んだ apply の `Summary` を既に process-local に保持している。`appliedPoint` は `ApplyResult.Applied(pointId)` からのみ設定され、restore 対象 point と 1:1 で対応するため、この `Summary` は「restore 先 (apply 前) 状態の要約」の truth として妥当である。

この構成により、application 契約は無変更のまま、確認画面は「どの整理の前に戻るか」と「何が戻るか」を説明できる。run state machine への保持追加は、既に保持している `lastVerifiedApply` を `State.RecoveryPreview` へ見せるだけである。

### D2: point–summary 相関保証 — **既存 invariant の明示化と UI 側 pointId 等価ゲート**

spec review (2026-09-08) が、確認画面に表示する summary が現在の recovery point に対応していることを何で検証するかを要求した。本 spec は既存実装が構造的に保証している invariant を正とし、その上に防御ゲートを規約化する。

- **安定識別子**: `RecoveryPointId` を相関の識別子とする。`lastVerifiedApply` が保持する `State.Applied.result` は `ApplyResult.Applied(runId, pointId)` であり、preview の `RecoveryPreviewResult.Restorable.pointId` と同じ型で直接比較できる。追加の識別子・generation・永続化は導入しない。
- **更新順序と失敗時 invalidation (既存 invariant)**: `ManualOrganizationRun.apply()` は、`appliedPoint` と `lastVerifiedApply` を同一の `ApplyResult.Applied` を源として同一の `synchronized(lock)` 区間内でのみ更新する (`ManualOrganizationRun.kt` の apply 結果遷移、`State.Applied` かつ `ApplyResult.Applied` のとき)。適用が `Applied` 以外 (`Rejected` / `RolledBack` / `Recovered` / 失敗系) に分類された場合、両者とも更新しない — どちらか一方だけが先に進む経路は存在しない。両者は process-local かつ非永続であり、process death で同時に消える。restart 後は run state が `Idle` から始まり、restore action は `State.Applied` でしか描画されないため、restart 後に restore 確認へ到達することはない。
- **UI 側 pointId 等価ゲート (防御)**: 確認画面は、`lastVerifiedApply.result` を `ApplyResult.Applied` へ narrow し、その `pointId` が preview の `pointId` と等しいときだけ履歴行を描画する。等しくない、`lastVerifiedApply` が null、result が `Applied` でない、のいずれかの場合は、**いかなる apply の `Summary` も表示せず**、共通の戻り先文のみで確認を継続する。confirm / Cancel は通常どおり機能する (履歴行の不在は restore の可否に影響しない)。

上記 1 番目の invariant により「recovery point は A のまま、`lastVerifiedApply` だけが B / stale になる」状態は到達不能である。しかし state machine の将来変更で相関が壊れた場合に、誤った組の表示ではなく要約の省略へ fail させるため、pointId 等価ゲートを規約として要求する。summary と point の対応は確認画面の表示条件であり、`inspectRecovery` の結果 (spec 84) や restore protocol (spec 13) には影響しない。

### D3: 要約の粒度 — **件数要約とし、item 単位の change list は先送りする**

#195 の concrete change list は `PreviewChange` projection (item label、移動元 → 移動先) を spec 194 が提供するため実装できた。restore 側には対応する逆方向 projection が存在せず、`Summary` は item label を持たない。item 単位の「元の位置へ戻る」一覧を提供するには、recovery point 内の pre-state manifest から item identity を読む application 契約の拡張 (spec 84 の re-accept) が必要である。

本 spec は件数要約までを出荷対象とし、item 単位一覧は「必要になった時点で application 契約の spec を起票」する後続候補として Issue に記録する。件数要約でも「直前の整理で何をしたか」の判断材料 (移動件数、フォルダ生成、ページ追加) は提供され、Issue #230 の受入基準「復元元を一意に理解できる」を満たす。

### D4: 件数は apply 履歴として表現し、restore 予測差分と誤認させない

spec review (2026-09-08) Should。apply 時の `Summary` 件数は apply の履歴であり、apply 後にユーザーが Home を直接編集した場合、restore が実際に書き換える配置数とは一致しない。restore の実差分は recovery protocol が適用時に exact precondition として検証するものであり、確認時点で予測表示する契約は存在しない (spec 84 が件数を公開しない理由と同源)。

よって履歴行は「直前の整理での変更: …」の語彙で apply 履歴であることを明示し、「N 件を元に戻します」「N 件の配置が戻ります」等の restore 予測差分の語彙を禁止する。共通の戻り先文は状態の方向 (適用前へ戻る) のみを述べ、件数を含まない。

### D5: 用語の統一方向 — **「保存したレイアウト (saved layout)」に寄せる**

3 系統の呼称のうち、「保存したレイアウト」系は確認 action・進行・結果ですでに使われており、変更が最小である。また spec 84 が `RESTORE_SAVED_LAYOUT` を effect として固定しているため、UI 語彙をこれに揃えると契約との対応が直線的になる。`previous layout` 系 (entry action、inspecting、apply 結果文) を「保存したレイアウト」系へ置換する。

apply 結果文 (`manual_organization_apply_recovered`: `Your previous layout was restored.`) を含める理由は、自動 recovery が復元する対象も同じ recovery point であるため、同じ対象への 2 つ目の呼称を残すと「previous」と「saved」が別物に見えるリスク (Issue #230 の指摘) が残るからである。変更対象の string 一覧は plan で列挙し、en/ja 両方を同時に出す。

### D6: 非 `Restorable` 結果の表示は現行維持

`NotRestorable` / `Unavailable` は typed な理由 (`MISSING` / `EXPIRED` / `CORRUPT` / …) を持つが、既存の `manual_organization_recovery_not_available` は意図的に理由を一般化した文言である (spec 84 の「closed result categories を localized な非色表示へ map する」契約)。理由の詳細化は spec 230 の問題 (復元対象の説明) とは独立であり、`Unavailable(RECONCILIATION_PENDING)` 等の区別表示は必要になった時点で別 Issue とする。

## Behavior scenarios

### Scenario: Restorable preview explains the restore target

Given 検証済み適用 (`ApplyResult.Applied`) が完了し、`State.Applied` から `Restore saved layout` を選択した,

When `inspectRecovery` が `Restorable` を返し、確認画面が描画される,

Then 画面には共通の戻り先文が表示され、apply 時の `Summary` に応じて「直前の整理での変更: …」の apply 履歴行が表示される (0 件区分は連結されない),

And 履歴行の値は、preview の `pointId` と `lastVerifiedApply` が保持する `ApplyResult.Applied.pointId` が等価な apply の `Summary` から構成される (D2 相関ゲート),

And 確認 action は `Restore saved layout` (保存したレイアウトに戻す) であり、Cancel と並べて decision pair を維持する,

And application 契約への追加呼び出しは発生せず、`RecoveryPreviewResult` の値は無変更である。

### Scenario: Correlation is absent or mismatched — history line omitted, restore still confirmable

Given `Restorable` preview が表示されているが、`lastVerifiedApply` が保持されていない、`ApplyResult.Applied` でない、またはその `pointId` が preview の `pointId` と等価でない (防御 path、D2),

When 確認画面が描画される,

Then apply 履歴行は表示されず、共通の戻り先文のみが表示される。**いかなる apply の `Summary` 値も表示しない**,

And confirm と Cancel は既存どおり機能し、restore 操作が block されない。

### Scenario: A later apply replaces the correlated context

Given 検証済み適用 A の後に、さらに検証済み適用 B が完了している (`appliedPoint` = B、`lastVerifiedApply` = B),

When B の recovery point に対する restore 確認画面が描画される,

Then 履歴行は B の `Summary` から構成され、A の `Summary` の値は表示されない。

### Scenario: Process restart removes the restore entry

Given 検証済み適用と recovery point が存在する状態で process が再起動した,

When Organizer を開く,

Then run state は `Idle` から始まり、restore action は表示されない (restore 確認は `State.Applied` 以外から到達できない既存規約、D2)。

### Scenario: Zero-change apply still identifies the restore target uniquely

Given 適用された plan の `Summary` が moved == 0、newFolderCount == 0、newPageCount == 0 である,

When restore 確認画面が描画される,

Then apply 履歴行は省略され、共通の戻り先文のみが表示される,

And 戻り先の説明は「この画面で確認した整理の適用前」であり、一意である。

### Scenario: Restore execution returns to the explained target

Given `Restorable` 確認画面に表示された履歴行が、apply 時の `Summary` から構成されている,

When ユーザーが `Restore saved layout` を確定し、recovery が `Restored` を返す,

Then 適用後の layout は、説明された「この画面で確認した整理の適用前の状態」と一致する (recovery protocol の exact pre-state verification が既に保証する)。

And この一致は既存の production E2E (`manualRunUsesProductionCaptureApplyVerificationAndRecovery` の recovery 区間) へ、履歴行の truth 値が apply 前状態と整合することの assertion を追加して検証する。

### Scenario: External layout changes after apply do not turn the history line into a restore prediction

Given 検証済み適用の後、ユーザーが Home を直接編集し、外部状態が変化している,

When restore 確認画面が描画される,

Then 履歴行は apply 時の `Summary` 値のまま apply 履歴として表示され、「N 件を元に戻します」等の restore 予測差分の語彙は使われない (D4),

And restore の実差分は recovery protocol の exact precondition / write-set が適用時に検証するものであり、確認画面は件数を予測表示しない。

### Scenario: Cancel remains zero-write

Given `Restorable` 確認画面が表示されている,

When ユーザーが `Cancel` を選択する,

Then `State.Applied` へ戻り、recovery store・layout DB・model への書込みは発生しない (既存契約の維持)。

And `cancelRecoveryPreview()` の既存 unit test (`cancellingRecoveryPreviewRestoresVerifiedApplySummaryAndActionSurface`) が継続して成功する。

### Scenario: Non-restorable result keeps the current typed message

Given `beginRecoveryPreview()` の結果が `NotRestorable` / `Unavailable` / `WriterBusy` / `Concurrent` である,

When 確認画面が描画される,

Then 既存の typed 文言のみが表示され、apply 履歴行は表示されない (要約の truth となる apply 文脈は保持しているが、実行可能な restore の説明ではないため)。

## Data and state

- 読む data: `ManualOrganizationRun.lastVerifiedApply` が保持する `State.Applied` の `Summary` と `ApplyResult.Applied.pointId` (process-local、既存)。新規に読む永続 data はない。
- 永続化する data: なし。recovery store、Launcher DB、model への変更はない。相関ゲート (D2) は既存の process-local 値 (`appliedPoint` / `lastVerifiedApply`) の比較であり、新識別子・runId 追跡・永続化を追加しない。
- 表示のみの変更であり、`State` の shape 変更は `State.RecoveryPreview` への summary 参照追加 (既存 object の再利用) のみを許容する。新 state、新 diagnostics、新 persistency は追加しない。
- migration、backup/restore への影響: なし。

## Permissions, privacy, and security

None。追加 permission、外部送信、新規 sensitive data はない。表示する件数は既に `State.Applied` で表示済みの同一値 (`Summary`) であり、recovery manifest・item identity・revision・時刻を公開しない (spec 84 の privacy 契約を維持)。

## Accessibility and localization

- 追加行は既存の `SummaryText` / body 規約を使い、status 見出し (`FocusTargetText`) の focus + `liveRegion = Polite` 構成を維持する。履歴行は liveRegion 対象外 (#195 同様、読み上げ長の爆発を防ぐ)。
- 単一意味の読み上げ: 履歴行は 1 行 1 事実とし、TalkBack で独立 node として読める。200% font scale で wrap する (spec 52 a11y 契約)。
- strings は `values/` と `values-ja/` へ同時に追加・変更する (#123 契約)。用語統一による既存 string の置換も en/ja を同期させる。

## Acceptance criteria

| AC | Acceptance criterion | Required evidence |
|---|---|---|
| AC-1 | `Restorable` 確認画面に共通の戻り先文が表示され、apply 時 `Summary` に応じた apply 履歴行 (移動 / フォルダ / ページ、0 件区分省略) が表示される。履歴行は apply 履歴の語彙であり、restore 予測差分の語彙 (「N 件を元に戻します」等) を使わない。ゼロ変更適用では共通文のみで一意に説明される。 | `ManualOrganizationRunTest` への unit test 追加 (`State.RecoveryPreview` が履歴 summary を運ぶこと、non-restorable では履歴行がないこと) + strings の語彙確認 |
| AC-2 | **point–summary 相関ゲート (D2)**: 履歴行は、preview の `pointId` と `lastVerifiedApply` が保持する `ApplyResult.Applied.pointId` が等価なときだけ表示される。不一致・不在ではいかなる apply の `Summary` も表示せず、共通文のみで confirm / Cancel が機能する (restore を block しない)。 | unit test: (a) pointId 一致 → 履歴行表示、(b) summary 不在 → 履歴行なし・restore 可能、(c) summary は存在するが pointId 不一致 → 表示せず fallback、(d) Apply A → Apply B → B の pointId + B の summary のみ、(e) restart 後は restore 確認画面へ到達しない |
| AC-3 | entry action・確認 action・進行・結果・apply 自動 recovery 結果のユーザー向け呼称が「保存したレイアウト」で統一される。en/ja 両方で同期する。 | strings diff (values/ + values-ja/)、#161 LQA 規約に沿った用語確認 |
| AC-4 | Cancel は zero-write である (既存契約の維持)。 | 既存 unit test の継続成功 |
| AC-5 | Restore 実行後の layout は、確認画面で説明した「この画面で確認した整理の適用前の状態」と一致する。履歴行の件数は restore 予定件数として解釈されない。 | production E2E の recovery 区間への assertion 追加 + apply 後に外部状態を変化させる case で履歴語彙を assert (既存の exact pre-state verification oracle を利用) |
| AC-6 | application 契約 (spec 84 の result surface、spec 13 の protocol) は無変更である。相関ゲートに新識別子・永続化・runId 追跡を追加しない。 | public-shape 変更なしの確認 (diff review、契約 test の継続成功) |
| AC-7 | representative device/emulator evidence で Apply → Home 確認 → Organizer 再訪 → Restore confirmation → Cancel / Restore の両経路を確認する。 | instrumentation / emulator evidence を PR へ記録 |

## Test oracle

| AC | Automated/manual evidence |
|---|---|
| AC-1 | `ManualOrganizationRunTest`: apply → `beginRecoveryPreview` → `State.RecoveryPreview` が履歴 summary を運ぶことの assert、non-restorable path で運ばないことの assert、strings 断片の語彙 assert (「元に戻します」形式の件数文を使わない) |
| AC-2 | `ManualOrganizationRunTest`: 推奨テスト (a) 一致 → 表示、(b) summary 不在 → 履歴行なし + confirm 可能、(c) pointId 不一致 → 表示せず fallback、(d) Apply A → Apply B → B のみ表示、(e) restart 後 unreachable、を state と仮 application の fixture で検証 |
| AC-3 | strings.xml (values / values-ja) の diff、`previous layout` 系 string の残存 grep が plan の列挙と一致 |
| AC-4 | 既存 `cancellingRecoveryPreviewRestoresVerifiedApplySummaryAndActionSurface` の継続成功 |
| AC-5 | `ManualOrganizationProductionE2EInstrumentationTest.manualRunUsesProductionCaptureApplyVerificationAndRecovery` の recovery 区間拡張 (apply 後に外部状態変化を挟む case を含む) |
| AC-6 | spec 84 RP-AC-01 系 public-shape contract test の継続成功 + 新識別子/永続化が diff に含まれないことの review 確認 |
| AC-7 | emulator 実機操作 evidence (screenshot + 操作記録) を PR へ添付 |

## Open questions

None (spec 時点で確定)。D1–D6 が情報源、相関保証、粒度、件数語彙、用語方向、非対象を確定した。item 単位の restore preview、保存時刻表示、理由別 not-available 文言は、application 契約の拡張を要求するため本 spec では扱わない。

## References

- [Issue #230: Organizerのrestore確認で復元対象・戻り先を判断できない](https://github.com/nunu1733/NunuLauncher/issues/230)
- [Spec 13: safe layout application and recovery](../13-safe-layout-application/spec.md)
- [Spec 84: read-only revision-bound recovery preview](../84-recovery-preview-seam/spec.md)
- [Spec 52: manual full organization vertical slice](../52-manual-full-organization-vertical-slice/spec.md)
- [Spec 195: organizer confirmation UI renders the concrete change list](../195-organizer-confirmation-change-list/spec.md)
- [Spec 209: decision action affordance](../209-organizer-decision-action-affordance/spec.md)
- [Spec 210: stale apply outcome](../210-stale-apply-outcome/spec.md)
- [ADR-0003: separate private recovery-point database](../../docs/adr/0003-organizer-recovery-point-storage.md)
- [Quality strategy](../../docs/engineering/quality-strategy.md)

## Change history

- 2026-09-08: Drafted for Issue #230 (Astra blind exploratory UX review 2026-09-06, Finding 2)。apply 文脈の `Summary` 再利用 (D1)、件数要約までの出荷 (D3)、「保存したレイアウト」への用語統一 (D5) を提案。
- 2026-09-08: Spec review (PR #244) 対応。point–summary 相関保証を D2 として新設 (既存 invariant の明示化 + UI 側 pointId 等価ゲート)、apply 履歴語彙の規約を D4 として新設、D 番号を D1–D6 へ再採番。推奨テスト 6 件を scenarios / AC / test oracle へ反映。
- 2026-09-08: Accepted by the Issue #230 owner (PR #244 review「この相関ルールが明文化されれば、spec としては Approve 可能」+ Approve)。plan.md の作成を開始する。
