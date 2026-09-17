---
issue: "#328"
status: draft
requirements: [FR-017]
risk:
  - privacy
updated: 2026-09-18
---

# External Agent ExchangeのImport成功後に状態と次操作を明示する

> Status: **draft** (2026-09-18 re-entry revision)。**D-2 (Back/cancel時の破棄確認形式) とD-3 (CTA最終文言) は owner の product decision が必要** (Open questions 1/2)。summary数の意味論 (canonical representation基準) と成功状態の構造的guard (Back interception配置・run内strategy picker凍結・CTA single-flight) は本revisionで現行main (`8fd05a40`) の実装事実に基づき固定した。承認前にD-2/D-3を確定すること。

## Problem

現行 (baseline `8fd05a40d51a`、#205/#331に加え #329 Import Normalizer・#330 partial authoring v3・#332 clipboard/file-first import UI・#348 AI-facing contract sync 実装済み) のExternal Agent Exchangeでは、AI回答のimportがvalidationに通過すると、`ExchangeFlowStateHolder.import()` が即座に `run.attachIntent()` (run内entry) または `run.start(intent)` (idle entry) を実行し、exchange画面を閉じて1行のstatus (`exchange_import_accepted`: 「整理案を検証しました。プレビューを生成します…」) を表示するだけである (#332は入力取得UIのみを再構成し、validation通過後のこの挙動は既存のまま維持すると #332 specが境界固定している)。このため:

1. **取り込み結果が不明**: 何件の希望を認識したか、判断なしの項目があるかがどこにも表示されない。
2. **未適用状態が不明**: 「まだホーム画面へは適用されていない」ことが明示されない。また `exchange_import_accepted` の文言は実際の遷移先とずれる: idle entryでmissing-app検出が成功すると次画面は選択surfaceであり (選択確定までpreviewは生成されない)、検出がUnavailableの場合はrunが同期的にPlanning/Previewへ進むためexchange itemsが非hostingになり、status行自体が一度も表示されない。
3. **次操作への導線がない**: ユーザーは「何も起こらなかった」と判断しやすく、Organizerの再整理flow (#228選択 → strategy → #194 preview → #195 confirm → apply) への接続が暗黙である。
4. **successとwarning/rejectの区別がない**: 失敗は専用の取り込み結果画面 (typed 19種: contract 13種 + envelope 4種 + #329 normalization 2種) で表示されるが、判断なし項目を含むvalid import (warning相当) は純粋な成功と同一表示になり、取り込めなかった項目があることが説明されない。
5. **Back/cancelでimport結果が黙って失われる**: 成功後にrunが開始済み (idle entry) だとBackはrunをdismissし、bound intentは失われる (validated intentは非保持)。このlifecycleはUI上どこにも明示されていない。

Importは正規flowでは最終目的ではなく、personalization intentをPlannerへ渡す途中段階である (#205)。導線が切れる問題を、中間状態を設けて解消する。

## Outcome

Import成功後、**(a) 取り込み済みであること、(b) 認識した希望のprivacy-safeな件数summary、(c) 判断なし項目の有無、(d) まだホーム画面へ適用されていないこと、(e) 次のOrganizer操作への明示的CTA** を示す取り込み成功状態 (中間状態) がexchange導線内に表示される。ユーザーがCTAを押したときのみ、既存のrun接続seam (#331 `attachIntent` / #205 fresh run `start(intent)`) が **一度だけ** 実行され、既存のPlanner / preview / confirm / apply flowへ連続して進める。CTAを押さずに閉じる場合のintentの扱い (破棄) は明示的に定義・表示され、黙喪失しない。成功状態の表示中は、成功状態とpending intentを黙って消す競合操作 (idle entryの「整理を開始」row、run内entryのstrategy pickerによるrun差し替え、system Back経由の画面dismiss) が構造的に排除される。

## Scope

- Import (validation通過) 直後の **取り込み成功状態** のUI: 両entry (idle entry / #331 run内entry) で共通の中間状態。run接続 (attach/start) はCTAまで延期する。
- privacy-safe summary: `ValidatedPersonalizedIntent.completed` (#330 v3 canonical representation) から導出する **件数のみ** の表示 (内容は「Behavior scenarios: summaryの内容 (canonical基準)」で固定)。
- success / warning (canonical判断なし件数 > 0) / reject (既存typed失敗) の視覚・semantics上の区別。
- CTA押下時のgate拒否 (`Busy` / `NotAttachable`) のtyped案内 (成功状態を維持したまま) と、CTAのsingle-flight保証 (二重押下・遅延settleの排除)。
- 取り込み成功状態を閉じる (破棄する) 場合のsemanticsと、再取り込み可能性の案内。system Backのinterceptionは **常時compositionされる上位 (lazy item外) に配置** する (成功stateがlazy itemのviewport外でも保証される)。
- 取り込み成功状態表示中の競合affordanceの無効化: idle entryの「整理を開始」rowに加え、run内entry中のstrategy pickerによるrun差し替え (dismiss→restart) path。
- accessibility (TalkBack / Switch Access / large font) とja/en strings。
- Import → CTA → 次ステップ → preview までのend-to-end device evidence。

## Non-goals

- Import直後の自動layout apply (CTA以降も既存 #194 preview + #195 confirm + spec 13 apply必須)。
- preview/confirmationの省略、AI出力の再生成、Planner strategy自体の変更 (Issue本文)。
- validated intentの新規永続化・保持期間の追加。process-localな一時保持のみ (#205/#331の非永続契約を変えない)。
- Import入力の取得UI (clipboard/file-first化、bounded editor、認識framing/entry数のparse段階表示): **#332 が所有 (implemented)**。本specは `ValidatedPersonalizedIntent` が得られた時点以降を所有する (#332 spec「成功時の境界」: #328未実装の間は既存の即時接続挙動を維持)。
- #329 Import Normalizer (implemented)、#327 interview-first化 (OPEN)、#330 authoring contract (implemented — 本specはそのcanonical representationを **消費** するだけで再定義しない)、#348 AI-facing contract sync (implemented — export/instruction側の契約で成功状態UIに変更面なし)。
- run内entryの選択freeze・scope binding gateそのもの (#331所有。本specは `ImportSuccess` 表示中にそれらを追加拘束するのみ)。
- exchange framing / #204 validator / export session契約の変更。
- 既存失敗表示 (typed 19種の取り込み結果画面) の変更。

## Domain language

`CONTEXT.md` への追加用語案 (受入時に反映)。

**取り込み成功状態 (Import Success State)**:
importされたintentがvalidationを通過した後、run接続 (attach / fresh run開始) の前に表示される中間状態。取り込み済みであること、認識した希望の件数summary、未適用であること、次操作へのCTAを含む。CTA押下または明示的な破棄によって終了する。
_Avoid_: 適用完了 (未適用であることとの混同)、プレビュー (次ステップで生成される #194 preview との混同)

**取り込み破棄 (Import Discard)**:
取り込み成功状態をCTAなしに閉じる操作。pendingなvalidated intentを破棄する (zero-write)。exchange sessionは失効させないため、依頼が有効な間は同じ回答textを再取り込みできる。
_Avoid_: 取り消し (apply済み変更のrollbackとの混同。何も適用されていない)

**判断なし項目 (no-judgment items)**:
#330 v3 canonical representation (`CompletedPersonalIntent`) 上、`RefDecision.Authored` 以外のdecision (`UnresolvedAuthored` = 明示unresolved + bare entry正規化、`UnresolvedByOmission` = 未言及) を持つexport ref。3表現はsemantic identity・planner効果が同一 (spec 330 D-5/D-6) であり、UI上の区別はprovenance diagnosticであり **user-visibleな区別としない** (合算1件数で表現する)。

## Behavior scenarios

### Scenario: idle entry — 取り込み成功状態の表示

Given manual organization surfaceがrun非active (Idle/Cancelled) であり、idle entry (#205、#332実装後はclipboard読取・file読取・手動貼付のいずれかreceipt経由の共通import path) でAI回答がvalidationに通過した、
When 取り込みが成功する、
Then exchange導線内に **取り込み成功状態** が表示され、次を含む: (a) AIの提案を取り込んだこと、(b) privacy-safe summary (次scenario参照)、(c) 判断なし項目の有無、(d) 「まだホーム画面には適用されていない」こと、(e) 次のOrganizer操作への主要CTA、
And この時点ではrun stateは一切変化しない (single-active-operation gate未取得、detection未実行、zero-write)、
And 従来の即時 `run.start(intent)` と誤解を招く1行status (`exchange_import_accepted`) のみの表示は行わない。

### Scenario: summaryの内容 (canonical representation基準)

Given validation通過済みの `ValidatedPersonalizedIntent` (その `completed: CompletedPersonalIntent` が正本。#330 D-4: authored文書はdiagnostics用のみでplanner/previewに流れない)、
When 取り込み成功状態が表示される、
Then summaryは **`completed` から導出した件数のみ** で構成され、少なくとも次を含む:
- 認識した希望を持つ項目数 = `completed.authoredItemCount` (`RefDecision.Authored` のみを数える。bare entry = 全semantic fieldがnullのentryは #330 D-6でcanonical unresolvedへ正規化されるため **希望として数えない**。authored文書の `itemIntents.size` を直接数えない — bare entryを誤計上するため)、
- 判断なし項目数 = `completed.authoredUnresolvedCount + completed.omittedCount` の **合算値** (明示unresolved / bare entry / 未言及の3表現はsemanticに同一 (spec 330 D-5/D-6) であり、provenanceの区別はUIへ出さない。合算0件なら当該行は表示しない)、
- run内entryでは、依頼に含めた未配置アプリ候補数 (`session.scopeCandidates` の件数。既存の選択surface案内と同じ値)
And 内訳 (優先度指定件数 / グループ希望件数 / 配置先希望件数 / 保持希望件数) はoptionalな補助行としてよいが、**`RefDecision.Authored` のdecisionのみから** 導出し、bare entryを含めない (最終粒度はOpen questions 3)、
And app label・folder title・export-scoped `ref`・AI自由文 (`rationale`)・`confidence` は **表示しない**。理由: ref/labelはprivacy surfaceを広げ、`rationale` はuntrusted自由文の直接表示であり、`confidence` はAIの自己申告であり誤解を招くため (Issue AC「表示しない理由がspec化される」への回答)、
And summaryの導出は `validated.completed` のみを入力とする純粋関数として切り出し、出力modelにlabel/ref/text fieldが存在しないことをcontract testで固定する。

### Scenario: CTAで次ステップへ (idle entry、single-flight)

Given idle entryの取り込み成功状態が表示されている、
When ユーザーがCTAを押す、
Then 既存の #205 fresh run接続seam (`ManualOrganizationRun.start(trigger, intent)`) が **一度だけ** 実行され、取り込み成功状態は閉じ、通常flow (#228 detection → 選択、または検出Unavailableならcomposition直行) へ進む、
And CTA処理はsingle-flightである: 最初の押下で同期的にCTA処理中へ遷移し、処理中の追加押下はseamを呼ばず拒否される (二重押下で `start` が複数回呼ばれない)。接続結果のsettleは開始した成功状態instanceに紐付き、画面が先に閉じられた/置き換わった場合の遅延settleは適用されない (既存の `beginTransport` / `settleBelongsTo` と同じ構造的パターン)、
And 以降は既存契約どおり: 選択確定時のscope binding gate (#331、candidate集合∅との完全一致)、planning、#194 preview、#195 confirm、spec 13 apply。本specはこれらを変更しない、
And gateが `Busy` を返した場合 (CTA時に他の操作がactive)、CTA処理中状態は解除され取り込み成功状態が維持・再試行可能になり、typed案内 (`RUN_BUSY` 系) が表示される (zero-write)。遅延した拒否settleが、すでに閉じた成功状態へ `RUN_BUSY` 表示を残さないこと。

### Scenario: run内entry — 取り込み成功状態と選択surfaceへの復帰

Given #331 run内entry (runが `State.Selecting` を保持し、exchange stepのため選択がfreeze中) でAI回答がvalidationに通過した、
When 取り込みが成功する、
Then 同一の取り込み成功状態が表示され (候補数を含む)、選択は引き続きfreezeされたまま (= run状態は不変)、
When ユーザーがCTAを押す、
Then single-flightの下で既存の `ManualOrganizationRun.attachIntent` seamが **一度だけ** 実行され、validated intentが当該runへ接続され、取り込み成功状態が閉じ、選択編集が有効化される (既存の `intentScopeCount` 案内表示は継続)、
And attachが `NotAttachable` を返した場合 (selection surface消失・二重bind等)、CTA処理中状態は解除され取り込み成功状態は維持されtyped案内が表示される (zero-write、intent破棄はしない)、
And 成功状態の表示中、strategy pickerの選択変更によるrun差し替え (既存の `onStrategySelected` のrun active時 `dismiss()` → `start(trigger)` path) は無効化される (次scenario)。

### Scenario: 競合affordanceの無効化 (idle row + run内strategy picker)

Given 取り込み成功状態が表示されている、
When 同一surfaceにrunを開始・再開する競合affordanceが存在する、
Then **idle entry** では「整理を開始」rowが無効化され (#331の選択freezeと同じ拘束パターン)、**run内entry** ではstrategy pickerの選択変更が無効化される (run active時の `dismiss()` → `start(trigger)` によるrun差し替えが、pending intent・freeze済み選択・成功状態のCTA対象を黙って入れ替える経路の遮断)、
And いずれの競合操作によっても成功状態とpending intentが黙って消える経路は存在しない。無効化は視覚のみでなくaccessibility semanticsでも判別可能である、
And **idle entry** のstrategy pickerは引き続き操作できる (run非activeのため既存の `onStrategySelected` はrun再開pathを発動せず、成功状態とpending intentは不変)。

### Scenario: warning (判断なし項目あり) とsuccess/rejectの区別

Given validation通過済みintentのcanonical判断なし件数 (`authoredUnresolvedCount + omittedCount`) が0より大きい、
When 取り込み成功状態が表示される、
Then 表示は「取り込みは成功・判断なし項目あり」であり、**失敗・rejectと誤認させない** (reject表示は既存の取り込み結果画面が担い、本状態は失敗経路を置き換えない)、
And 判断なし項目の件数 (合算。provenance非表示) と、判断なし項目も従来どおり整理対象であること (preferenceなしで扱われること) が説明される、
And successとwarning-with-no-judgmentはsemantics上区別される (見た目のみでなく、heading/live region等でTalkBackが読み分けられる。「Accessibility」参照)。

### Scenario: 取り込み破棄 (CTAを押さずに閉じる)

Given 取り込み成功状態が表示されている、
When ユーザーがCTAを押さずに明示的な破棄操作 (「閉じる (破棄)」) またはsystem Backで状態を閉じる、
Then pendingなvalidated intentは **破棄** される (zero-write、run接続なし)。破棄であること、および依頼 (export session) が有効な間は同じ回答textを再取り込みできることが表示される (破棄操作のlabel・確認、または案内文言で)、
And exchange sessionのinvalidateは行わない (再取り込み可能性を保つ)、
And validated intentの保持・永続化は行わない (#205/#331契約の継承。process deathで失われても再取り込みが回復pathである)、
And 画面を閉じただけでimport結果が **黙って** 見失われる状態は存在しない (破棄は常に明示的)。

> system Backの **interception自体** は本specで固定する (次scenario)。Back時に確認dialogを挟むか、破棄を示すlabelの操作で十分とするかは **D-2 として未決** (Open questions 2)。いずれの場合も上記のsemantics (破棄の明示・再取り込み案内・session非invalidate) は不変。

### Scenario: system Backのinterceptionは成功stateが画面外でも保証される

Given 取り込み成功状態が表示されており、large font等により成功stateのlazy list itemがviewport外 (composition外) にある、
When ユーザーがsystem Backを押す、
Then Backは成功状態の破棄handling (D-2確定内容) として処理され、親の画面level Back処理 (`ManualOrganizationBackHandler` の `dismiss()` → run cancel / navigate away) に **落ちない**。pending intentが確認なしで消えない、
And この保証は成功stateのcomposableがcomposition中であることに依存しない (interceptionは常にcompositionされるhosting画面levelに登録され、成功state表示中のみ有効化される)。lazy item内への `BackHandler` 登録のみでは不十分 (viewport外でcallbackが存在しなくなる) であることを構造として回避する。

### Scenario: 既存失敗経路の無変更

Given importがframing失敗・normalization失敗 (#329)・validation reject・`INPUT_OVERSIZE`・clipboard/file読取失敗・入力未ready等で失敗する、
When 失敗が表示される、
Then 既存の取り込み結果画面 (typed 19種失敗表示 + 認識framing/version/entry数表示 + 折りたたみraw + 再取り込み) が従来どおり機能し、本specは失敗表示を変更しない (regression条件)。

### Scenario: process death

Given 取り込み成功状態の表示中にprocessが破棄される、
Then runは未開始・未接続であり、失われるのはpending intent (process-local) のみで、layout DB・export sessionへの影響はない、
And 再起動後の回復は既存どおり再取り込みである (session有効期限内であれば同じ回答textが再検証を通過する)。

## Data and state

- 読むdata: `ValidatedPersonalizedIntent` とその **`completed: CompletedPersonalIntent`** (`decisions` / `authoredItemCount` / `authoredUnresolvedCount` / `omittedCount`。正本は #330 D-4のcanonical representation。authored文書の `itemIntents` / `unresolvedRefs` をsummaryの計数根拠にしない)、`session.scopeCandidates`、entry種別 (idle / run内)。#204/#330/#331契約を本specは再定義しない。
- 永続化: **追加なし**。取り込み成功状態とpending intentはprocess-local (exchange flowのUI state) のみ。export session (#204、durable・TTL 24時間) は破棄操作でinvalidateしない。
- run state: 取り込み成功状態の表示中、`ManualOrganizationRun` の状態は不変 (idle entry: Idle/Cancelledのまま。run内entry: Selectingのまま・freeze継続)。CTAで既存seam (`start(trigger, intent)` / `attachIntent`) をsingle-flightで一度だけ実行する。CTA処理中は同期的な処理中標識により追加のseam呼出が構造的に拒否され、settleは開始instanceに紐付く。
- migration / backup / rollback: 影響なし (DB schema変更なし、新規storageなし)。

## Permissions, privacy, and security

- 追加permission・外部送信なし。
- summaryは件数のみ。app label・folder title・`ref`・AI自由文 (`rationale`)・`confidence` を取り込み成功状態に表示しない (理由はsummary scenario参照)。出力modelへの混入をcontract testで防止する。
- `rationale` 等のuntrusted自由文を将来表示する場合は別途spec・reviewを要する (本specでは非表示を固定)。
- 判断なし件数のprovenance内訳 (明示unresolved / bare / omissionの区別) はdiagnostic情報であり、privacy面の新規問題ではないがUIへ出さない (#330 D-5/D-6のsemantic同一性との整合)。

## Accessibility and localization

- 取り込み成功状態の到着はlive regionで通知 (success: Polite、warning要素: 表示階層で識別可能)、state headingへfocusが移動する (既存の `FocusTargetText` パターンに準拠)。
- success / warning / rejectは色だけでなくsemantics (heading文言・live region・contentDescription) で識別可能であること。TalkBackで「取り込み成功」「判断なし項目あり」が読み分けられること。
- 競合affordanceの無効化 (idle「整理を開始」row、run内strategy picker) はTalkBackに無効であることと理由が読み取れること。
- large font: summary・未適用表示・CTAが折返し表示され、CTAは画面内 (横scrollなし) で到達可能であること。成功state itemがviewport外に出てもsystem Backの保護が損なわれないこと (前出scenario)。Switch Accessで全操作 (CTA・破棄) が可能であること。
- stringsは `values/` + `values-ja/` の両方へ追加する (spec 123契約)。日本語をUI copyの正本とする (#205 Decision 4と同一)。

## Acceptance criteria

- [ ] AC-1: import成功 (validation通過) 時、両entry (idle / run内) で取り込み成功状態が表示され、run接続 (attach/start) はCTA押下まで実行されないことがtestされる (表示中のrun state不変・zero-writeを含む)。
- [ ] AC-2: 取り込み成功状態が「まだホーム画面には適用されていない」ことを明示することがtestされる (string存在 + 表示)。
- [ ] AC-3: 次のOrganizer操作へ進む主要CTAが存在し、CTAで既存seam経由の接続が **一度だけ** 行われることがtestされる (idle: `start(trigger, intent)`、run内: `attachIntent`)。CTAのsingle-flightがtestされる: 二重押下でseamが複数回呼ばれないこと、および成功状態が閉じた後の遅延settleが表示へ適用されないこと。CTA時のgate拒否 (`Busy` / `NotAttachable`) は処理中状態を解除し取り込み成功状態を維持したままtyped案内される。
- [ ] AC-4: privacy-safe summary (件数: `authoredItemCount`・判断なし合算件数 (`authoredUnresolvedCount + omittedCount`)・run内では候補数) が **`completed` canonical representationから導出** されることがtestされる。bare entryが希望件数に計上されないこと、未言及refが判断なし件数に計上されること (bare/omission合算の計数test oracle)、provenanceがuser-visibleでないこと、非表示項目 (`rationale` / `confidence` / label / ref) とその理由が本specに固定されていること (summary導出の純粋関数 + 出力modelの非混入contract test) を含む。
- [ ] AC-5: CTAを押さずに閉じる場合のlifecycleがD-2の確定内容どおり実装される: 破棄が明示的であること、exchange sessionがinvalidateされないこと (破棄後に同じ回答textの再取り込みが成立すること)、競合affordance (idle「整理を開始」row、run内strategy pickerのrun差し替え) が成功状態中表示中に無効化されることがtestされる。
- [ ] AC-6: success / warning (判断なし合算 > 0) / rejectが、TalkBackを含めて識別可能であること (semanticsベース。手動/instrumentation evidence)。
- [ ] AC-7: system Backが成功stateのlazy item viewport外 (large font) でも成功状態のhandlingに捕捉され、親のdismiss/navigateに落ちないことがinstrumentation testされる。
- [ ] AC-8: large font (font scale最大) で次操作 (CTA) と未適用表示が画面内で把握できること (手動/instrumentation evidence)。
- [ ] AC-9: Import → CTA → 次ステップ (選択またはcomposition) → preview までのend-to-end device evidenceがある (physical device)。
- [ ] AC-10: 既存失敗表示 (typed 19種取り込み結果画面、入力source失敗statusを含む) と既存export flow (生成・送信前確認・transport) がregressionなく機能すること。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `ExchangeFlowStateHolder` unit test (validated → 取り込み成功状態、run state不変、attach/start未呼出) + hosting instrumentation test |
| AC-2 | string解決test + UI表示 (unit/instrumentation) |
| AC-3 | holder unit test: CTA → seam呼出 (`Started`/`Attached`)、`Busy`/`NotAttachable` で状態維持・処理中解除。**二重押下でseam 1回のみ** (continuing flag guard)、**遅延settleが閉じた/置き換わった成功状態へ適用されない** (identity anchor)。`ManualOrganizationRunTest` 連携 |
| AC-4 | summary純粋関数のunit test: `authoredItemCount` / `authoredUnresolvedCount + omittedCount` の導出、**bare entry非計上** (bare 1件 → 希望0件・判断なし1件)、**omission計上** (未言及2件 → 判断なし2件)、出力modelへlabel/ref/text fieldが存在しないことの型/contract test |
| AC-5 | holder unit test (discard → session invalidate未呼出、再取り込み成立の往復test、破棄後pending intent消失) + idle競合row無効化UI test + run内strategy picker無効化UI test |
| AC-6 | a11y assertion (semantics/live region) + TalkBack手動evidence |
| AC-7 | instrumentation: 成功state表示中に成功itemをviewport外へscrollさせ (またはfont scale最大で同等状態にし) system Back → 親dismiss/navigateが発火しないことのassertion |
| AC-8 | font scaleを上げたinstrumentation/手動evidence |
| AC-9 | physical device evidence (docs/assessment/ またはIssue。#205 AC-10 evidenceと兼ね可) |
| AC-10 | 既存 `ExchangeFlowStateHolderTest` / `ExchangeFlowControllerTest` / 失敗表示testのregression実行 |

## Open questions (acceptance前に解消必要)

1. **CTA最終文言 (D-3)**: Issue提示候補は「次へ: 整理方法を選ぶ」「この提案で整理案を作る」。次ステップの実体は #228 選択surface (検出成功時) またはcomposition直行→preview (検出Unavailable時) であり、strategy選択はpreferences screen上のpickerで並存する。案: 実際の次surfaceを断定しすぎない文言 (idle「この提案で整理を進める」等) / run内「選択に戻って整理を確定する」。owner確定待ち。
2. **Backの破棄確認形式 (D-2)**: system Backで確認dialogを挟むか、破棄を示すlabel付き操作のみで十分とするか。案: 明示ボタンはlabelで足り、system Backは確認dialogを挟む (誤Back保護)。interceptionの配置 (hosting画面level・常時composition) と成功state画面外での保護はOpen question化せず本specで固定済み。owner確定待ち。
3. **summary内訳の粒度**: 種別別件数 (優先度/グループ/配置先/保持。`RefDecision.Authored` のみから導出) をV1に含めるか。案: 含める (件数のみで追加privacy面なし)。実装PRで微調整可。

## Change history

- 2026-09-16: Draft created for #328 (baseline `aab0d293d1a9` = origin/main)。現行実装 (#205 PR #325 / #331 PR #333 後) の成功時挙動 (即時attach/start + 1行status、失敗専用の取り込み結果画面、Back時の黙喪失) をコード確認の上、取り込み成功状態の中間状態・summary・CTA・破棄semantics・a11yを起草。D-2/D-3はowner decision待ちでdraft。
- 2026-09-18: Re-entry revision (review Required 1–4対応)。baselineを `8fd05a40d51a` (origin/main、#329/#330/#332/#336/#348実装merge後) へ更新。**(1)** summaryを #330 v3 canonical representation基準へ変更: 認識希望数 = `completed.authoredItemCount`、判断なし = `authoredUnresolvedCount + omittedCount` の合算 (provenance非表示、D-5/D-6のsemantic同一性に整合)、warning条件を判断なし合算 > 0へ明示、内訳はAuthoredのみから導出。旧coverage不変条件 (export items = `itemIntents` ∪ `unresolvedRefs`) はv3で不成立のため除去。**(2)** system Backのinterceptionを常時compositionされるhosting画面levelへ配置するよう固定 (lazy item内BackHandlerはviewport外で無効になるため不採用)、AC-7を追加。**(3)** run内entryの成功状態中表示中にstrategy pickerのrun差し替え (dismiss→restart) を無効化するよう競合affordance scenarioへ追加。**(4)** CTAのsingle-flight (同期処理中遷移・identity anchored settle・二重押下/遅延settle拒否) をspec/AC-3へ追加。失敗種別をtyped 19種 (#329 normalization追加後) へ更新、#332/#329/#330/#348をimplementedとして参照更新。

## References

- [Issue #328](https://github.com/nunu1733/NunuLauncher/issues/328)
- [Spec 205: external agent exchange](../205-external-agent-exchange/spec.md) (**implemented**。fresh run再構築、zero-write、失敗typed表示)
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md) (**implemented**。run内entry・選択freeze・`attachIntent`・scope binding gate)
- [Spec 330: partial intent authoring](../330-partial-intent-authoring/spec.md) (**implemented**。v3 canonical representation (`CompletedPersonalIntent` / `RefDecision`)、D-4 completer、D-5/D-6 bare entry・omissionのsemantic同一性 — 本specのsummary基準)
- [Spec 329: import normalizer](../329-import-normalizer/spec.md) (**implemented**。失敗typed表示にnormalization 2種を追加)
- [Spec 332: exchange import input UI](../332-exchange-import-input-ui/spec.md) (**implemented**。clipboard/file-first入力・認識metadata表示。成功後は #328へ渡す境界固定)
- [Spec 348: exchange AI-facing contract sync](../348-exchange-ai-facing-contract/spec.md) (**implemented**。export/instruction側の契約)
- [Spec 204: AI personalization context/intent exchange contract](../204-ai-personalization-context-intent-contract/spec.md) (`ValidatedPersonalizedIntent`、export session)
- [Spec 194: plan preview seam](../194-plan-preview-seam/spec.md) / [Spec 195: confirmation change list](../195-organizer-confirmation-change-list/spec.md) (preview/confirm path)
- [Spec 228: organizer missing-app selection](../228-organizer-missing-app-selection/spec.md) (次ステップの選択surface)
- [Spec 123: organizer UI convergence](../123-organizer-ui-convergence/spec.md) (ja/en strings契約)
- Issue #327 (interview-first化、OPEN — 本状態と変更面なし)
- [requirements.md](../../docs/product/requirements.md) (FR-017), [AGENTS.md](../../AGENTS.md), [DESIGN.md](../../DESIGN.md), [CONTEXT.md](../../CONTEXT.md)
