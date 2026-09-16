---
issue: "#328"
status: draft
requirements: [FR-017]
risk:
  - privacy
updated: 2026-09-16
---

# External Agent ExchangeのImport成功後に状態と次操作を明示する

> Status: **draft** (2026-09-16)。本体は確定可能な範囲で整理したが、**D-2 (Back/cancel時のlifecycle詳細) とD-3 (CTA最終文言) は owner の product decision が必要** (Open questions 1/2)。承認前にこれらを確定すること。

## Problem

現行 (baseline `aab0d293d1a9`、#205 PR #325 + #331 PR #333 実装後) のExternal Agent Exchangeでは、AI回答のimportがvalidationに通過すると、`ExchangeFlowStateHolder.import()` が即座に `run.attachIntent()` (run内entry) または `run.start(intent)` (idle entry) を実行し、exchange画面を閉じて1行のstatus (`exchange_import_accepted`: 「整理案を検証しました。プレビューを生成します…」) を表示するだけである。このため:

1. **取り込み結果が不明**: 何件の希望を認識したか、未判断 (`unresolvedRefs`) の項目があるかがどこにも表示されない。
2. **未適用状態が不明**: 「まだホーム画面へは適用されていない」ことが明示されない。また `exchange_import_accepted` の文言は実際の遷移先とずれる: idle entryでmissing-app検出が成功すると次画面は選択surfaceであり (選択確定までpreviewは生成されない)、検出がUnavailableの場合はrunが同期的にPlanning/Previewへ進むためexchange itemsが非hostingになり、status行自体が一度も表示されない。
3. **次操作への導線がない**: ユーザーは「何も起こらなかった」と判断しやすく、Organizerの再整理flow (#228選択 → strategy → #194 preview → #195 confirm → apply) への接続が暗黙である。
4. **successとwarning/rejectの区別がない**: 失敗は専用の取り込み結果画面 (typed 17種) で表示されるが、`unresolvedRefs` を含むvalid import (warning相当) は純粋な成功と同一表示になり、取り込めなかった項目があることが説明されない。
5. **Back/cancelでimport結果が黙って失われる**: 成功後にrunが開始済み (idle entry) だとBackはrunをdismissし、bound intentは失われる (validated intentは非保持)。このlifecycleはUI上どこにも明示されていない。

Importは正規flowでは最終目的ではなく、personalization intentをPlannerへ渡す途中段階である (#205)。導線が切れる問題を、中間状態を設けて解消する。

## Outcome

Import成功後、**(a) 取り込み済みであること、(b) 認識した希望のprivacy-safeな件数summary、(c) 未判断項目の有無、(d) まだホーム画面へ適用されていないこと、(e) 次のOrganizer操作への明示的CTA** を示す取り込み成功状態 (中間状態) がexchange導線内に表示される。ユーザーがCTAを押したときのみ、既存のrun接続seam (#331 `attachIntent` / #205 fresh run `start(intent)`) が実行され、既存のPlanner / preview / confirm / apply flowへ連続して進める。CTAを押さずに閉じる場合のintentの扱い (破棄) は明示的に定義・表示され、黙喪失しない。

## Scope

- Import (validation通過) 直後の **取り込み成功状態** のUI: 両entry (idle entry / #331 run内entry) で共通の中間状態。run接続 (attach/start) はCTAまで延期する。
- privacy-safe summary: `ValidatedPersonalizedIntent` から導出する件数のみの表示 (内容は「Behavior scenarios: summaryの内容」で固定)。
- success / warning (`unresolvedRefs` > 0) / reject (既存typed失敗) の視覚・semantics上の区別。
- CTA押下時のgate拒否 (`Busy` / `NotAttachable`) のtyped案内 (成功状態を維持したまま)。
- 取り込み成功状態を閉じる (破棄する) 場合のsemanticsと、再取り込み可能性の案内。
- 取り込み成功状態表示中の競合affordance (同一surfaceのplainな「整理を開始」row) の無効化。
- accessibility (TalkBack / Switch Access / large font) とja/en strings。
- Import → CTA → 次ステップ → preview までのend-to-end device evidence。

## Non-goals

- Import直後の自動layout apply (CTA以降も既存 #194 preview + #195 confirm + spec 13 apply必須)。
- preview/confirmationの省略、AI出力の再生成、Planner strategy自体の変更 (Issue本文)。
- validated intentの新規永続化・保持期間の追加。process-localな一時保持のみ (#205/#331の非永続契約を変えない)。
- Import入力の取得UI (clipboard/file-first化、bounded editor): **#332 が所有**。本specは `ValidatedPersonalizedIntent` が得られた時点以降を所有し、#332は入力surfaceから本状態への接続を前提に再構成してよい。
- #329 Import Normalizer、#327 interview-first化、#330 authoring contract簡素化 (いずれも独立Issue。本状態はそれらの有無に関わらず成立する)。
- exchange framing / #204 validator / export session契約の変更。
- 既存失敗表示 (typed 17種の取り込み結果画面) の変更。

## Domain language

`CONTEXT.md` への追加用語案 (受入時に反映)。

**取り込み成功状態 (Import Success State)**:
importされたintentがvalidationを通過した後、run接続 (attach / fresh run開始) の前に表示される中間状態。取り込み済みであること、認識した希望の件数summary、未適用であること、次操作へのCTAを含む。CTA押下または明示的な破棄によって終了する。
_Avoid_: 適用完了 (未適用であることとの混同)、プレビュー (次ステップで生成される #194 preview との混同)

**取り込み破棄 (Import Discard)**:
取り込み成功状態をCTAなしに閉じる操作。pendingなvalidated intentを破棄する (zero-write)。exchange sessionは失効させないため、依頼が有効な間は同じ回答textを再取り込みできる。
_Avoid_: 取り消し (apply済み変更のrollbackとの混同。何も適用されていない)

## Behavior scenarios

### Scenario: idle entry — 取り込み成功状態の表示

Given manual organization surfaceがrun非active (Idle/Cancelled) であり、idle entry (#205) のimport操作でAI回答がvalidationに通過した、
When 取り込みが成功する、
Then exchange導線内に **取り込み成功状態** が表示され、次を含む: (a) AIの提案を取り込んだこと、(b) privacy-safe summary (次scenario参照)、(c) 未判断項目の有無、(d) 「まだホーム画面には適用されていない」こと、(e) 次のOrganizer操作への主要CTA、
And この時点ではrun stateは一切変化しない (single-active-operation gate未取得、detection未実行、zero-write)、
And 従来の即時 `run.start(intent)` と誤解を招く1行status (`exchange_import_accepted`) のみの表示は行わない。

### Scenario: summaryの内容 (privacy-safe)

Given validation通過済みの `ValidatedPersonalizedIntent`、
When 取り込み成功状態が表示される、
Then summaryは **件数のみ** で構成され、少なくとも次を含む:
- 認識した希望を持つ項目数 (`itemIntents` の件数)
- 未判断項目数 (`unresolvedRefs` の件数。0件なら表示しないことでよい)
- run内entryでは、依頼に含めた未配置アプリ候補数 (`session.scopeCandidates` の件数。既存の選択surface案内と同じ値)
And 内訳 (優先度指定件数 / グループ希望件数 / 配置先希望件数 / 保持希望件数) はoptionalな補助行としてよい (最終粒度はOpen questions 3)、
And app label・folder title・export-scoped `ref`・AI自由文 (`rationale`)・`confidence` は **表示しない**。理由: ref/labelはprivacy surfaceを広げ、`rationale` はuntrusted自由文の直接表示であり、`confidence` はAIの自己申告であり誤解を招くため (Issue AC「表示しない理由がspec化される」への回答)、
And summaryの導出は純粋関数として切り出し、出力modelにlabel/ref/text fieldが存在しないことをcontract testで固定する。

### Scenario: CTAで次ステップへ (idle entry)

Given idle entryの取り込み成功状態が表示されている、
When ユーザーがCTAを押す、
Then 既存の #205 fresh run接続seam (`ManualOrganizationRun.start(trigger, intent)`) が実行され、取り込み成功状態は閉じ、通常flow (#228 detection → 選択、または検出Unavailableならcomposition直行) へ進む、
And 以降は既存契約どおり: 選択確定時のscope binding gate (#331、candidate集合∅との完全一致)、planning、#194 preview、#195 confirm、spec 13 apply。本specはこれらを変更しない、
And gateが `Busy` を返した場合 (CTA時に他の操作がactive)、取り込み成功状態は維持され、typed案内 (既存 `exchange_run_busy` 系) が表示され、状態の再試行ができる (zero-write)。

### Scenario: run内entry — 取り込み成功状態と選択surfaceへの復帰

Given #331 run内entry (runが `State.Selecting` を保持し、exchange stepのため選択がfreeze中) でAI回答がvalidationに通過した、
When 取り込みが成功する、
Then 同一の取り込み成功状態が表示され (候補数を含む)、選択は引き続きfreezeされたまま (= run状態は不変)、
When ユーザーがCTAを押す、
Then 既存の `ManualOrganizationRun.attachIntent` seamでvalidated intentが当該runへ接続され、取り込み成功状態が閉じ、選択編集が有効化される (既存の `intentScopeCount` 案内表示は継続)、
And attachが `NotAttachable` を返した場合 (selection surface消失・二重bind等)、取り込み成功状態は維持されtyped案内が表示される (zero-write、intent破棄はしない)。

### Scenario: warning (未判断項目あり) とsuccess/rejectの区別

Given validation通過済みintentの `unresolvedRefs` が非emptyである、
When 取り込み成功状態が表示される、
Then 表示は「取り込みは成功・未判断項目あり」であり、**失敗・rejectと誤認させない** (reject表示は既存の取り込み結果画面が担い、本状態は失敗経路を置き換えない)、
And 未判断項目の件数と、未判断項目も従来どおり整理対象であること (preferenceなしで扱われること) が説明される、
And successとwarning-with-unresolvedはsemantics上区別される (見た目のみでなく、heading/live region等でTalkBackが読み分けられる。「Accessibility」参照)。

### Scenario: 取り込み破棄 (CTAを押さずに閉じる)

Given 取り込み成功状態が表示されている、
When ユーザーがCTAを押さずに明示的な破棄操作 (「閉じる (破棄)」) またはsystem Backで状態を閉じる、
Then pendingなvalidated intentは **破棄** される (zero-write、run接続なし)。破棄であること、および依頼 (export session) が有効な間は同じ回答textを再取り込みできることが表示される (破棄操作のlabel・確認、または案内文言で)、
And exchange sessionのinvalidateは行わない (再取り込み可能性を保つ)、
And validated intentの保持・永続化は行わない (#205/#331契約の継承。process deathで失われても再取り込みが回復pathである)、
And 画面を閉じただけでimport結果が **黙って** 見失われる状態は存在しない (破棄は常に明示的)。

> Backの具体的な扱い (確認dialogを挟むか、破棄を示すlabelの操作で十分とするか) は **D-2 として未決** (Open questions 2)。いずれの場合も上記のsemantics (破棄の明示・再取り込み案内・session非invalidate) は不変。

### Scenario: 競合affordanceの無効化

Given idle entryの取り込み成功状態が表示されている、
When 同一surfaceに「整理を開始」rowなど、runを開始する競合affordanceが存在する、
Then 取り込み成功状態が開いている間、当該rowは無効化され (#331の選択freezeと同じ拘束パターン)、競合操作によって成功状態とpending intentが黙って消える経路は存在しない、
And strategy picker (#182/#283) の選択変更は妨げない (run非activeのため既存のdismiss/restart経路は発動せず、成功状態は維持される)。

### Scenario: 既存失敗経路の無変更

Given importがframing失敗・validation reject・`INPUT_OVERSIZE`・入力未ready等で失敗する、
When 失敗が表示される、
Then 既存の取り込み結果画面 (typed 17種失敗表示 + 再取り込み) が従来どおり機能し、本specは失敗表示を変更しない (regression条件)。

### Scenario: process death

Given 取り込み成功状態の表示中にprocessが破棄される、
Then runは未開始・未接続であり、失われるのはpending intent (process-local) のみで、layout DB・export sessionへの影響はない、
And 再起動後の回復は既存どおり再取り込みである (session有効期限内であれば同じ回答textが再検証を通過する)。

## Data and state

- 読むdata: `ValidatedPersonalizedIntent` (`intent.itemIntents` / `intent.unresolvedRefs` / `session.scopeCandidates`、およびoptional内訳導出用のper-item field) とentry種別 (idle / run内)。正本は #204/#331契約のまま、本specは再定義しない。
- 永続化: **追加なし**。取り込み成功状態とpending intentはprocess-local (exchange flowのUI state) のみ。export session (#204、durable・TTL 24時間) は破棄操作でinvalidateしない。
- run state: 取り込み成功状態の表示中、`ManualOrganizationRun` の状態は不変 (idle entry: Idle/Cancelledのまま。run内entry: Selectingのまま・freeze継続)。CTAで既存seam (`start(trigger, intent)` / `attachIntent`) を一度だけ実行する。
- migration / backup / rollback: 影響なし (DB schema変更なし、新規storageなし)。

## Permissions, privacy, and security

- 追加permission・外部送信なし。
- summaryは件数のみ。app label・folder title・`ref`・AI自由文 (`rationale`)・`confidence` を取り込み成功状態に表示しない (理由はsummary scenario参照)。出力modelへの混入をcontract testで防止する。
- `rationale` 等のuntrusted自由文を将来表示する場合は別途spec・reviewを要する (本specでは非表示を固定)。

## Accessibility and localization

- 取り込み成功状態の到着はlive regionで通知 (success: Polite、warning要素: 表示階層で識別可能)、state headingへfocusが移動する (既存の `FocusTargetText` パターンに準拠)。
- success / warning / rejectは色だけでなくsemantics (heading文言・live region・contentDescription) で識別可能であること。TalkBackで「取り込み成功」「未判断項目あり」が読み分けられること。
- large font: summary・未適用表示・CTAが折返し表示され、CTAは画面内 (横scrollなし) で到達可能であること。Switch Accessで全操作 (CTA・破棄) が可能であること。
- stringsは `values/` + `values-ja/` の両方へ追加する (spec 123契約)。日本語をUI copyの正本とする (#205 Decision 4と同一)。

## Acceptance criteria

- [ ] AC-1: import成功 (validation通過) 時、両entry (idle / run内) で取り込み成功状態が表示され、run接続 (attach/start) はCTA押下まで実行されないことがtestされる (表示中のrun state不変・zero-writeを含む)。
- [ ] AC-2: 取り込み成功状態が「まだホーム画面には適用されていない」ことを明示することがtestされる (string存在 + 表示)。
- [ ] AC-3: 次のOrganizer操作へ進む主要CTAが存在し、CTAで既存seam経由の接続が行われることがtestされる (idle: `start(trigger, intent)`、run内: `attachIntent`)。CTA時のgate拒否 (`Busy` / `NotAttachable`) は取り込み成功状態を維持したままtyped案内される。
- [ ] AC-4: privacy-safe summary (件数: 認識した希望数・未判断数・run内では候補数) が表示されること、および非表示項目 (`rationale` / `confidence` / label / ref) とその理由が本specに固定されていることがtestされる (summary導出の純粋関数 + 出力modelの非混入contract test)。
- [ ] AC-5: CTAを押さずに閉じる場合のlifecycleがD-2の確定内容どおり実装される: 破棄が明示的であること、exchange sessionがinvalidateされないこと (破棄後に同じ回答textの再取り込みが成立すること)、競合affordanceが成功状態中表示中無効化されること。
- [ ] AC-6: success / warning (`unresolvedRefs` > 0) / rejectが、TalkBackを含めて識別可能であること (semanticsベース。手動/instrumentation evidence)。
- [ ] AC-7: large font (font scale最大) で次操作 (CTA) と未適用表示が画面内で把握できること (手動/instrumentation evidence)。
- [ ] AC-8: Import → CTA → 次ステップ (選択またはcomposition) → preview までのend-to-end device evidenceがある (physical device)。
- [ ] AC-9: 既存失敗表示 (typed 17種取り込み結果画面) と既存export flow (生成・送信前確認・transport) がregressionなく機能すること。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `ExchangeFlowStateHolder` unit test (validated → 取り込み成功状態、run state不変、attach/start未呼出) + hosting instrumentation test |
| AC-2 | string解決test + UI表示 (unit/instrumentation) |
| AC-3 | holder unit test (CTA → `start`/`attachIntent` 呼出、`Busy`/`NotAttachable` で状態維持) + `ManualOrganizationRunTest` 連携 |
| AC-4 | summary純粋関数のunit test (件数導出・境界、出力modelへlabel/ref/text fieldが存在しないことの型/contract test) |
| AC-5 | holder unit test (discard → session invalidate未呼出、再取り込み成立の往復test、破棄後pending intent消失) + 競合row無効化のUI test |
| AC-6 | a11y assertion (semantics/live region) + TalkBack手動evidence |
| AC-7 | font scaleを上げたinstrumentation/手動evidence |
| AC-8 | physical device evidence (docs/assessment/ またはIssue。#205 AC-10 evidenceと兼ね可) |
| AC-9 | 既存 `ExchangeFlowStateHolderTest` / `ExchangeFlowControllerTest` / 17種失敗表示testのregression実行 |

## Open questions (acceptance前に解消必要)

1. **CTA最終文言 (D-3)**: Issue提示候補は「次へ: 整理方法を選ぶ」「この提案で整理案を作る」。次ステップの実体は #228 選択surface (検出成功時) またはcomposition直行→preview (検出Unavailable時) であり、strategy選択はpreferences screen上のpickerで並存する。案: idle「次へ: 整理を始める」/ run内「選択に戻って整理を確定する」。owner確定待ち。
2. **Backの破棄確認形式 (D-2)**: system Backで確認dialogを挟むか、破棄を示すlabel付き操作のみで十分とするか。案: 明示ボタンはlabelで足り、system Backは確認dialogを挟む (誤Back保護)。owner確定待ち。
3. **summary内訳の粒度**: 種別別件数 (優先度/グループ/配置先/保持) をV1に含めるか。案: 含める (件数のみで追加privacy面なし)。実装PRで微調整可。

## Change history

- 2026-09-16: Draft created for #328 (baseline `aab0d293d1a9` = origin/main)。現行実装 (#205 PR #325 / #331 PR #333 後) の成功時挙動 (即時attach/start + 1行status、失敗専用の取り込み結果画面、Back時の黙喪失) をコード確認の上、取り込み成功状態の中間状態・summary・CTA・破棄semantics・a11yを起草。D-2/D-3はowner decision待ちでdraft。

## References

- [Issue #328](https://github.com/nunu1733/NunuLauncher/issues/328)
- [Spec 205: external agent exchange](../205-external-agent-exchange/spec.md) (**implemented**。fresh run再構築、zero-write、失敗typed表示)
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md) (**implemented**。run内entry・選択freeze・`attachIntent`・scope binding gate)
- [Spec 204: AI personalization context/intent exchange contract](../204-ai-personalization-context-intent-contract/spec.md) (`ValidatedPersonalizedIntent`、export session)
- [Spec 194: plan preview seam](../194-plan-preview-seam/spec.md) / [Spec 195: confirmation change list](../195-organizer-confirmation-change-list/spec.md) (preview/confirm path)
- [Spec 228: organizer missing-app selection](../228-organizer-missing-app-selection/spec.md) (次ステップの選択surface)
- [Spec 123: organizer UI convergence](../123-organizer-ui-convergence/spec.md) (ja/en strings契約)
- Issue #332 (import入力UI、OPEN — 入力取得は#332、成功後導線は本spec)、Issue #329 / #327 / #330 (関連OPEN)
- [requirements.md](../../docs/product/requirements.md) (FR-017), [AGENTS.md](../../AGENTS.md), [DESIGN.md](../../DESIGN.md), [CONTEXT.md](../../CONTEXT.md)
