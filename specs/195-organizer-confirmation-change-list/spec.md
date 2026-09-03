---
issue: "#195"
status: accepted
requirements: []
risk: []
updated: 2026-09-03
---

# Organizer confirmation UI renders the concrete change list

## Problem

Manual organization の確認画面 (`ManualOrganizationPreferences` の `State.Preview`) は「移動 N 件 / 新規フォルダ N 件 / 新規ページ N 件」の件数サマリーしか表示しない。ユーザーは破壊的な一括変更を承認する前に、**どの項目が、どこからどこへ、どう変わるか**を確認できない (#192 の問題定義、[調査記録 §6](../../docs/assessment/issue-192-organizer-concrete-preview-investigation.md))。

[Spec 194](../194-plan-preview-seam/spec.md) が read-only plan preview seam と純粋な `PreviewChange` projection を提供済みであり、`State.Preview(summary, details)` の optional `details` として UI から観測可能である。本 Issue はその projection を消費する confirmation UI への更新であり、#194 の契約にのみ依存し、planner / application 契約には触れない。

## Outcome

確認画面が `PreviewChange` 一覧を主表示になる。ユーザーは、件数ヘッダ (materialize 済み actions 由来の `PreviewCounts` truth) と、変更種別ごとに grouping された具体的変更行 (「名前 — 移動元 → 移動先」) を確認してから confirm する。大量変更は先頭 N 件に切り詰められ、group ごとの「すべて表示」で展開できる。preview が得られなかった場合 (`details == null`) は、その旨が明示的に告知され、既存の count-only サマリーで確認を継続できる。

## Scope

- `State.Preview` の描画を `PreviewChange` 一覧中心へ更新する (`ManualOrganizationPreferences` と、そこから呼ぶ organizer UI 配下の純粋な行構築 module):
  - 件数ヘッダは **`PreviewCounts` (materialize 済み actions 由来)** を truth とする。既存の `Summary` 由来の変更件数行 (moved / preserved / new folder / new page / warnings と movedByReason / preservedByReason breakdown) は `details != null` の画面では表示しない (行数とヘッダの一致を保つ truth 分担契約)。
  - 変更種別 grouping: 移動 → 新規フォルダ → 新規ページ → 保持 → 警告 (項目警告行) の順で、空の group は表示しない。
  - 大量変更時は group ごとに先頭 5 件を表示し、group ごとの「すべて表示 (N件)」展開 /「先頭N件のみ表示」折りたたみを提供する。展開 state は process-local な UI state である。
  - 行表現: 「名前 — 移動元 → 移動先」。位置は page 序数 + 行帯 × 列帯の領域語 + 行序数で表現し、生 cell 座標・生 ordinal は見せない。同一ページ内移動は assessment §6.1 の領域表現を主表現とし、行帯が不変で行序数が変化する move は行序数の併記 (「2段目から1段目」) を行う。帯内微調整 (`sameBandAdjustment`) は「«領域» 内で位置を調整」の専用行形式で明示する。
  - 移動行には joined `Disposition.Moved.rationale` (`PlacementCode`) の、保持行には `PreserveReason` の、行レベルの理由語を添える (件数 breakdown の行内への置き換え)。
  - 新規フォルダ行は配置 + メンバー label 一覧で識別する。新規ページ行は 1-based 表示位置で示す。
- `details == null` (compatibility fallback) の画面では、degraded である旨の告知行を追加し、既存の count-only サマリー (`Summary` 由来の現行表示) をそのまま維持する。
- strings は `values/` と `values-ja/` の両方へ追加する (#123 の日本語 fallback 禁止契約)。`PreviewLabel.KindFallback(kind)` の総称文言もここで解決する。
- a11y: 変更行は単一の意味ある読み上げ node とし、`liveRegion` は既存の status 行のみに留める。展開 action は状態 (`stateDescription`) を semantics に持つ。Switch Access / keyboard traversal で全 action に到達でき、200% font scale で wrap する (spec 52 a11y 契約)。
- stale / readiness / recreate / NoChanges の既存 typed 結果表示を維持する。preview 自動再計画はしない。
- [spec 52](../52-manual-full-organization-vertical-slice/spec.md) の §"Preview and details" へ描画契約の所在を追記する。

## Non-goals

- #194 preview seam / projection / coordinator 契約の変更 (`State` の shape、`inspectPlan`、confirm 時の previewed plan 適用は不変)。
- visual before/after preview。
- #182 strategy 選択 UI とその rationale 拡張 (projection は無変更で消費する)。
- planner / application / diagnostics 契約の変更。新 state、新 `PhaseCode`、新 diagnostics は追加しない。
- `State.PlanningRejected(IMPOSSIBLE)` の fail-closed 終了 (plan / projection 整合性違反の presentation alias) に専用文言を導入すること (後述の決定 D4)。
- profile role (個人/仕事用) の表示名投影。

## Design decisions (spec 時点で確定 required by #194 / #192 の引き継ぎ)

### D1: `details == null` 時の confirm 可否 — **許可する (既存どおり) + 明示的な degraded 告知**

`details == null` は環境的失敗 (`WriterBusy` / `Concurrent` / readiness `Unavailable` / `NotPlannable(CAPTURE_FAILED)`) のみで発生し、plan 自身の妥当性の問題ではない。plan が適用対象として安全であることは apply の A2 exact precondition gate (revision + 完全状態比較) が preview→confirm 間の TOCTOU を含めて構造的に保証するため、confirm の可否を preview 取得の成否に束ねる安全上の根拠がない。一方で、一時的な writer busy を confirm の hard blocker にすると、件数サマリーが正確に機能している状況で機能全体が使えなくなる。

よって confirm は許可する。ただし「null を黙って count-only と同等に扱う」こと (#194 が恒久化を禁じた姿) は避け、画面先頭に degraded 告知行 (「具体的な変更一覧を用意できませんでした」) を表示して、ユーザーが件数のみでの確認であることを観測可能にする。confirm を block する方向へ変更する場合は、coordinator 契約の変更を伴う別 spec を要求する。

### D2: ヘッダ件数の truth — **変更件数は `PreviewCounts`、入力文脈行は `Summary`、warning group のみ行数 truth**

- `details != null` の画面で表示する変更件数行 (moved / preserved / new folder / new page / warnings) は、すべて `PreviewCounts` を truth とする。group 見出しの件数も同じ `PreviewCounts` を使い、truncation 中でも group 見出しが全体件数を示す。
- **warning group の例外 (review で確定)**: `PreviewCounts.warningCounts` には global / multi-item warning (具体行を持たない) も含まれるため、warning group の見出しと展開件数の truth は **`ItemWarningChange` の行数** とする。global / multi-item warning は上部の warning count 行 (`PreviewCounts` truth) のみに現れ、group 件数には数えない。「すべて表示 N件」が実際の行数より多く約束する虚偽表示を構造的に排除する。
- `Summary` 由来の movedByReason / preservedByReason の件数 breakdown は、`details != null` の画面では表示しない (理由は行レベルの rationale / reason 語へ置き換わる)。`Summary` の変更件数行と `PreviewCounts` を同一画面で混在させない。
- scope 行 (対象数 / profile 数 / page 数)、device 行、constraint 行 (locked / unavailable / widget 等) は**変更の件数ではなく入力の文脈情報**であり、spec 52 MFO-AC-07 の要求を満たすため `Summary` を truth として両 mode で表示する。これらは行数一致契約 (§Header counts の truth 分担) の対象外である。
- `details == null` (fallback) の画面は、告知行を除き現行の `summaryItems` 全文を維持する (regression-free)。

### D3: `PreviewLabel.KindFallback(kind)` の文言 — **本 Issue の strings で解決**

`CanonicalItemKind` の各値に対応する総称語を `values/` + `values-ja/` へ追加する (アプリ / ショートカット / 旧形式のショートカット / フォルダ / ウィジェット / カスタムウィジェット / アプリペア / 不明種別は汎用語)。`Unknown(code)` の `KindCode` 生値は表示へ露出しない (privacy 契約の踏襲)。

### D4: fail-closed 終了時の文言 — **既存の `manual_organization_impossible` を再利用し、新文言を作らない**

plan / projection 整合性違反で `State.PlanningRejected(IMPOSSIBLE)` に落ちる経路は、通常フローでは到達しない defensive path であり (#194 の §Coordinator integration)、`State` は planner の `Impossible` と presentation alias として同型である (#194 が新 state を意図的に導入しなかった契約)。両者を UI で区別する文言を用意するには coordinator / state 契約の変更が必要であり、UI-only の本 Issue の scope 外である。文言の精度より契約の安定を優先し、既存文言と retry action の再利用にとどめる。将来区別が必要になった時点で、coordinator 契約側の spec で `State` への区別情報を追加してから文言を分ける。

### D5: 同一ページ内移動の帯内微調整 residual risk — **text-only で出荷可能と判定 (visual preview の起票必須とはしない)**

assessment §6.1 の residual risk に対し、本 spec は次の表現契約を採用する:

1. **(a) 帯内移動行の明示的表現**: `sameBandAdjustment` の move は、source / destination が同じ語に潰れる代わりに「«領域» 内で位置を調整」の専用行形式で全件を明示する (#194 契約により行は action と 1:1 であるため、潰れた move が行ごと消えることは構造的にない)。
2. **行序数の併記**: 同一ページで行帯が不変・行序数が変化する move は「«領域» (2段目から1段目)」の形式で行方向の変化を復元する。帯内かつ同行序数の純粋な列方向調整のみが「位置を調整」行として同一文言になり得る。
3. **(b) 集計 / 警告**: group 見出しが全体件数を示し、行とヘッダの一致 (`PreviewCounts` truth) で「見えない変更」の発生を構造的に排除するため、追加の警告行は導入しない。

残余リスクとして「同帯・同行序数の列方向調整は before/after が同一文言になる」ことをここに明記する。これは破壊性の最も低い変更 class であり、行の存在自体が調整の発生を告知するため、text-only の説明力は不足と判定しない。visual preview は Optional な将来拡張のまま残し、本判定を覆す運用上の証拠 (LQA / dogfood) が出た時点で別 Issue として起票する。

### D6: truncation と grouping の規約

- group の順序と件数: 移動 (`movedCount`) → 新規フォルダ (`newFolderCount`) → 新規ページ (`newPageCount`) → 保持 (`preservedCount`) → 警告 (§D2 例外により `ItemWarningChange` 行数)。空の group は見出しごと省略する。group 内の行順は projection の決定的順序 (`plan.actions` 順等) をそのまま使う (re-sort しない)。
- truncation は group ごとに先頭 5 件。展開 / 折りたたみは group ごとの独立 UI state (初期値: 折りたたみ)。「すべて表示 (N件)」の N は group の全体件数 (`PreviewCounts`)。
- truncation は表示上の省略であり、confirm 対象 (preview 済み plan) には一切影響しない。

## Behavior scenarios

### Scenario: Concrete change list replaces the count-only preview

Given `inspectPlan` が `Previewed` を返し、`State.Preview(summary, details)` が公開されている,

When 確認画面が描画される,

Then 変更件数ヘッダは `details.counts` 由来であり、移動 → 新規フォルダ → 新規ページ → 保持 → 警告の group に、`details.changes` の全行が決定的順序で表示される (truncation 分を除く),

And 各移動行は「名前 — 移動元 → 移動先 (理由)」の形式であり、位置は page 序数と領域語で表現され、生 cell / `ItemId` / package 等は表示へ現れない。

### Scenario: Same-band adjustment is announced, not collapsed

Given 同一ページの同一行帯 × 同一列帯内の move が `details.changes` に含まれる,

When 確認画面が描画される,

Then その move は「«領域» 内で位置を調整」の専用行として他の move と同数だけ存在し、行帯が不変で行序数が変わる move には「N段目からM段目」の併記がある。

### Scenario: Large change lists truncate per group and expand

Given 移動 9 件を含む `details` が公開されている,

When 確認画面が描画される,

Then 移動 group は先頭 5 件と「すべて表示 (9件)」action を表示し、action で全 9 件が表示され、再実行で 5 件へ戻る,

And group 見出しの件数は常に 9 (`PreviewCounts`) であり、truncation は confirm 対象に影響しない。

### Scenario: Degraded count-only preview announces the missing details

Given 環境的失敗により `State.Preview(summary, details = null)` となっている,

When 確認画面が描画される,

Then「具体的な変更一覧を用意できなかった」告知行が表示され、既存の count-only サマリーと confirm / cancel が現行どおり機能する,

And 変更一覧 group は表示されない。

### Scenario: Empty diff and stale behavior do not regress

Given planner が空差分の `Planned` を返した、または preview 時 capture が stale となった,

When run が進行する,

Then それぞれ現行どおり **No changes** (confirm を出さない) / **Stale** (recapture 要求) が表示される,

And readiness 失敗・recreate・recovery の既存表示は不変である。

### Scenario: Accessibility of the change list

Given 変更一覧が表示されている,

When TalkBack / Switch Access / 200% font scale で操作する,

Then 各変更行は単一の意味ある node として読め、`liveRegion` は status 行のみで、展開 action は展開/折りたたみの state を報告し、keyboard / Switch traversal で status → 展開 action → confirm → cancel に実際に到達でき、必須 content が 200% で wrap して到達可能である,

And 展開による行挿入の reflow 後も展開 action 自身が focus を保持する (spec 52 の focus 復帰契約)。

## Data and state

- 読む data は `State.Preview(summary, details)` の observable 値のみ。UI は planner / application の型や DB に直接触れない。
- 展開 state は `remember` による process-local な UI stateであり、serialize / 保存しない。`details` が変われば初期化してよい。
- 永続化、migration、backup/restore、Launcher DB への影響は **なし** (zero-write の表示層)。

## Permissions, privacy, and security

None。新たな permission、network、telemetry、export は追加しない。表示名は #194 の projection が既に solution 化した canonical capture title に限られ、raw package / component / `ItemId` / `PageId` / 生 cell / digest / profile serial は本 UI のいかなる行にも表示しない (spec 194 の Labels and privacy 契約の消費側遵守)。

## Accessibility and localization

- 変更行は 1行 = 1 node の意味ある label (名前 + 変化) を持ち、行の `liveRegion` は設定しない (読み上げ洪水防止)。status 行の `liveRegion = Polite` と focus 復帰 (表示後・展開後・stale 後) は spec 52 契約を継承する。
- 展開 action は `stateDescription` (展開 / 折りたたみ) を持ち、Switch Access / keyboard traversal で到達可能である。
- 200% font scale で全必須 content が wrap し、切抜き・横 scroll 依存がない。
- 追加 strings はすべて `values/` + `values-ja/` に置き、placeholder を保持する (#123: 日本語実行時の英語 fallback 禁止)。多様な locale で layout が崩れない文言長とする。

## Acceptance criteria

| AC | Acceptance criterion |
|---|---|
| AC-1 | `details != null` の確認画面に具体的変更一覧が表示され、行は #194 契約 (`PreviewChange`) のみを消費する。変更件数ヘッダと group 件数は `PreviewCounts` 由来であり (warning group のみ §D2 例外で `ItemWarningChange` 行数)、`Summary` の変更件数行と混在しない。 |
| AC-2 | 変更一覧の行は `details.changes` の全件を (truncation 分を含め) 表現し、group 順序と行順は projection の決定的順序に従う。帯内微調整行と行序数併記が §D5 の契約どおり描画される。 |
| AC-3 | 大量変更 (group あたり閾値超過) で truncation + 展開 / 折りたたみが動作し、展開 state が semantics に現れる。 |
| AC-4 | `details == null` で告知行と現行 count-only サマリー + confirm が表示され、既存 flow が退化しない。変更 0 件 / stale / recreate / readiness の既存表示も退化しない。 |
| AC-5 | TalkBack (行 node、liveRegion 配置、state 語) / Switch Access / keyboard traversal / 200% font scale の UI test と evidence がある。 |
| AC-6 | 追加 strings が ja / en 両 locale で解決する (#123 契約)。`KindFallback` の全 kind と全 `PreserveReason` / `PlacementCode` / 項目警告 `WarningCode` に対応する文言が存在する。 |
| AC-7 | fixture (移動のみ / フォルダ作成を含む / 新規ページ追加 / 変更 0 件 / stale) の UI test が instrumented evidence (API 36 / Platform 36.1) として記録される。 |
| AC-8 | spec 52 の描画契約所在の明文化が同じ PR で完了する。 |

## Test oracle

| AC | Evidence |
|---|---|
| AC-1, AC-2 | `tests/unit/.../ui/OrganizationPreviewContentTest.kt` (純粋行構築: grouping、位置語、same-band 行、行序数、理由語、`PreviewCounts` truth) + instrumentation 描画 test |
| AC-3 | instrumentation test: 9 件移動 fixture で truncation → 展開 → 折りたたみと `stateDescription` 主張 |
| AC-4 | instrumentation test: `WriterBusy` fallback (告知行 + count-only + confirm)、NoChanges fixture (confirm 無し)、既存 stale / recreate test の無変更通過 |
| AC-5 | instrumentation test: 行 node の単一性と非 liveRegion、展開 action の state 語、実際の keyboard focus traversal (status → expand → confirm → cancel) と展開後の展開 action focus 保持、200% font scale |
| AC-6 | instrumentation test: ja configuration context で全追加 string を解決し en 値と一致しないこと (fallback 検出)、format string の解決 |
| AC-7 | `.github/workflows/ci.yml` `organizer-instrumentation-issue52-tests` job (API 36 / Platform 36.1) の成功 run URL を PR へ記録。可能な場合ローカル emulator 実行結果も記録 |
| AC-8 | PR diff review (spec 52) |

## Open questions

None。spec 時点で確定した判断は §Design decisions (D1–D6) のとおり。実装が `details` 契約で表現できない表示需要 (例: widget 理由語の不足) が判明した場合は、projection 契約を拡張せず owner review で停止する。

## Change history

- 2026-09-03: Drafted for Issue #195。#194 spec が委ねた 4 項目の契約判断 (D1–D4) と assessment §6.1 の residual risk 判定 (D5)、truncation 規約 (D6) を確定して作成。Issue owner の実施指示に基づき受理し、owner review は実装 PR で継続する。
- 2026-09-03: Review revision (owner review @ PR #198): warning group の見出し / 展開件数の truth を `ItemWarningChange` 行数へ分離し、global / multi-item warning は header count のみへ明確化 (Medium, §D2/§D6)。a11y 契約へ展開後の展開 action focus 保持を明記し、AC-5 の traversal evidence を実際の keyboard focus 移動で検証するよう更新 (Blocking)。

## References

- [Issue #195: Organizer confirmation UI を件数中心から具体的変更一覧中心へ更新](https://github.com/nunu1733/NunuLauncher/issues/195)
- [Spec 194: read-only plan preview and PreviewChange projection contract](../194-plan-preview-seam/spec.md)
- [Issue #192 assessment: Organizer concrete preview investigation (§5, §6, §6.1)](../../docs/assessment/issue-192-organizer-concrete-preview-investigation.md)
- [Spec 52: manual full-organization vertical slice (Preview and details / a11y 契約)](../52-manual-full-organization-vertical-slice/spec.md)
- [Spec 123: organizer UI convergence (日本語 fallback 禁止契約)](../123-organizer-ui-convergence/spec.md)
- [AGENTS.md: source-of-truth, safety, and quality rules](../../AGENTS.md)
- [Quality strategy](../../docs/engineering/quality-strategy.md)
