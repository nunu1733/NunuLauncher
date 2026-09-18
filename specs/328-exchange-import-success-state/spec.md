---
issue: "#328"
status: draft
requirements: [FR-017]
risk:
  - privacy
updated: 2026-09-18
---

# External Agent ExchangeのImport成功後に状態と次操作を明示する

> Status: **draft** (2026-09-18 re-entry revision 9)。**D-2/D-3 (およびOpen question 3) はowner decisionとして確定済み** ([Issue コメント](https://github.com/nunu1733/NunuLauncher/issues/328#issuecomment-5723309396)、本spec「Decisions」に具体化)。本revisionで **strategy write同士のsingle-flight (arbiterがIdleのときのみ `Idle → Writing` を取得可・non-idle中の追加選択は開始しない)**・**normative guard名を `strategyArbiterBusy := arbiterState != Idle` へ統一 (旧 `strategyWriteInFlight` を本文から撤去)**・**書込開始gateのentry別policy明記 (idle `ImportSuccess && !continuing` 中の書込は許可) + `Writing → Idle` (Committed-no-restart) 遷移と解除oracle追加** を行った。attempt anchor・CTA single-flight・CTA処理中の破棄/Back不受理・strategy変更との相互排他 (commit時gate/書込開始gate/attach前owning runId再照合)・CEを含むseam例外契約・summary内訳4種 + global preference・process death分岐は前revisionで固定済み。未決のproduct decisionは残っていない。

## Problem

現行 (baseline `a9ec3c2cf9`、#205/#331に加え #329 Import Normalizer・#330 partial authoring v3・#332 clipboard/file-first import UI・#348 AI-facing contract sync・#327 interview-first化 実装済み) のExternal Agent Exchangeでは、AI回答のimportがvalidationに通過すると、`ExchangeFlowStateHolder.import()` が即座に `run.attachIntent()` (run内entry) または `run.start(intent)` (idle entry) を実行し、exchange画面を閉じて1行のstatus (`exchange_import_accepted`: 「整理案を検証しました。プレビューを生成します…」) を表示するだけである (#332は入力取得UIのみを再構成し、validation通過後のこの挙動は既存のまま維持すると #332 specが境界固定している。#327はentry直下にcapability説明を追加したがvalidation通過後の挙動は変更していない)。このため:

1. **取り込み結果が不明**: 何件の希望を認識したか、判断なしの項目があるかがどこにも表示されない。
2. **未適用状態が不明**: 「まだホーム画面へは適用されていない」ことが明示されない。また `exchange_import_accepted` の文言は実際の遷移先とずれる: idle entryでmissing-app検出が成功すると次画面は選択surfaceであり (選択確定までpreviewは生成されない)、検出がUnavailableの場合はrunが同期的にPlanning/Previewへ進むためexchange itemsが非hostingになり、status行自体が一度も表示されない。
3. **次操作への導線がない**: ユーザーは「何も起こらなかった」と判断しやすく、Organizerの再整理flow (#228選択 → strategy → #194 preview → #195 confirm → apply) への接続が暗黙である。
4. **successとwarning/rejectの区別がない**: 失敗は専用の取り込み結果画面 (typed 19種: contract 13種 + envelope 4種 + #329 normalization 2種) で表示されるが、判断なし項目を含むvalid import (warning相当) は純粋な成功と同一表示になり、取り込めなかった項目があることが説明されない。
5. **Back/cancelでimport結果が黙って失われる**: 成功後にrunが開始済み (idle entry) だとBackはrunをdismissし、bound intentは失われる (validated intentは非保持)。このlifecycleはUI上どこにも明示されていない。

Importは正規flowでは最終目的ではなく、personalization intentをPlannerへ渡す途中段階である (#205)。導線が切れる問題を、中間状態を設けて解消する。

## Outcome

Import成功後、**(a) 取り込み済みであること、(b) 認識した希望のprivacy-safeな件数summary、(c) 判断なし項目の有無、(d) まだホーム画面へ適用されていないこと、(e) 次のOrganizer操作への明示的CTA** を示す取り込み成功状態 (中間状態) がexchange導線内に表示される。ユーザーがCTAを押したときのみ、既存のrun接続seam (#331 `attachIntent` / #205 fresh run `start(intent)`) が **一度だけ** 実行され、既存のPlanner / preview / confirm / apply flowへ連続して進める。CTAを押さずに閉じる場合のintentの扱い (破棄) は明示的に定義・表示され、黙喪失しない。成功状態の表示中は、成功状態とpending intentを黙って消す競合操作 (idle entryの「整理を開始」row、run内entryのstrategy pickerによるrun差し替え、system Back経由の画面dismiss) が構造的に排除される。さらに **CTA処理中 (seam呼出〜settle) は破棄・system Backとも不受理** とする: `run.start` / `attachIntent` はsettle前にrun stateを先に変更するseamであるため、処理中の破棄受理は「破棄済みのUI表示と開始済みrunの乖離」を生む。破棄・Backはsettle (成功で画面遷移 / 拒否で処理中解除) 後に再度受け付けられる。成功状態へ入る前段の **import validation自体もattempt anchorの下で動作** する: import開始時に採番したattempt tokenが検証settleに紐付き、キャンセル後・再import後・run差し替え後の遅延validation結果は成功状態を生成も置き換えもしない。

## Scope

- Import (validation通過) 直後の **取り込み成功状態** のUI: 両entry (idle entry / #331 run内entry) で共通の中間状態。run接続 (attach/start) はCTAまで延期する。
- privacy-safe summary: item-level件数は `ValidatedPersonalizedIntent.completed` (#330 v3 canonical representation) から導出し、加えてplanner-effectiveな `globalPreference.minimizeMovement` をprivacy-safeな全体方針行として表示する (内容は「Behavior scenarios: summaryの内容 (canonical基準)」で固定)。
- success / warning (canonical判断なし件数 > 0) / reject (既存typed失敗) の視覚・semantics上の区別。
- CTA押下時のgate拒否 (`Busy` / `NotAttachable`) のtyped案内 (成功状態を維持したまま) と、CTAのsingle-flight保証 (二重押下・遅延settleの排除)。settleのanchorは **import開始時に採番されるprocess-localなattempt token** (成功状態へ継承。`validated.identity` はcontent digestでありanchorに使わない)。
- **CTA処理中 (`continuing`) の破棄・system Back不受理**: seam開始からsettleまで、破棄操作・Back処理・`discardImport()` を受け付けない (破棄/Backはsettle後に再度可能)。
- **import validationのattempt anchor**: import開始時にattempt tokenを採番し (entry種別と、run内entryはowning runIdもcapture)、validation settle (`Validated` / `Failure` / `InputNotReady`) とCTA settleの双方をそれに紐付ける。キャンセル・入力変更・再import・run差し替え後の遅延settleは成功状態を生成・変更しない。
- **import attempt生存中の競合freeze**: 成功状態表示後だけでなく、import attempt開始 (validation中) から終了 (成功状態終了・破棄・キャンセル) までの全期間、idle entryの「整理を開始」rowとrun内entryのstrategy pickerによるrun差し替えpathを無効化する。
- **CTAのseam例外時契約**: `start` / `attachIntent` が例外で抜けた場合もsettle契約が閉じる。catch後に `ensureActive()` 相当で呼出coroutine自体のcancelを判定し、cancel済みなら例外を再伝播させ、active contextなら **`CancellationException` を含む全例外** をattempt-boundなfailure settleへ変換し処理中状態を解除する (Scope要約。詳細はCTA scenario)。
- 取り込み成功状態を閉じる (破棄する) 場合のsemanticsと、再取り込み可能性の案内。system Backのinterceptionは **常時compositionされる上位 (lazy item外) に配置** する (成功stateがlazy itemのviewport外でも保証される)。
- 取り込み成功状態表示中の競合affordanceの無効化: idle entryの「整理を開始」rowに加え、run内entry中のstrategy pickerによるrun差し替え (dismiss→restart) path。
- accessibility (TalkBack / Switch Access / large font) とja/en strings。
- Import → CTA → 次ステップ → preview までのend-to-end device evidence。

## Non-goals

- Import直後の自動layout apply (CTA以降も既存 #194 preview + #195 confirm + spec 13 apply必須)。
- preview/confirmationの省略、AI出力の再生成、Planner strategy自体の変更 (Issue本文)。
- validated intentの新規永続化・保持期間の追加。process-localな一時保持のみ (#205/#331の非永続契約を変えない)。
- Import入力の取得UI (clipboard/file-first化、bounded editor、認識framing/entry数のparse段階表示): **#332 が所有 (implemented)**。本specは `ValidatedPersonalizedIntent` が得られた時点以降を所有する (#332 spec「成功時の境界」: #328未実装の間は既存の即時接続挙動を維持)。
- #329 Import Normalizer (implemented)、#327 interview-first化 (implemented — capability説明の追加のみでvalidation通過後のflowは不変)、#330 authoring contract (implemented — 本specはそのcanonical representationを **消費** するだけで再定義しない)、#348 AI-facing contract sync (implemented — export/instruction側の契約で成功状態UIに変更面なし)。
- run内entryの選択freeze・scope binding gateそのもの (#331所有。本specは `ImportSuccess` 表示中にそれらを追加拘束するのみ)。
- exchange framing / #204 validator / export session契約の変更。
- 既存失敗表示 (typed 19種の取り込み結果画面) の変更。

## Domain language

`CONTEXT.md` への追加用語案 (受入時に反映)。

**取り込み成功状態 (Import Success State)**:
importされたintentがvalidationを通過した後、run接続 (attach / fresh run開始) の前に表示される中間状態。取り込み済みであること、認識した希望の件数summary、未適用であること、次操作へのCTAを含む。CTA押下または明示的な破棄によって終了する。
_Avoid_: 適用完了 (未適用であることとの混同)、プレビュー (次ステップで生成される #194 preview との混同)

**取り込み破棄 (Import Discard)**:
取り込み成功状態をCTAなしに閉じる操作。pendingなvalidated intentを破棄する (zero-write)。exchange sessionは失効させないため、依頼が有効な間は同じ回答textを再取り込みできる。入口は2つ: (1) labelで破棄を明示するボタン「破棄して閉じる」(追加確認なし — label自体が明示)、(2) system Back (確認dialogを挟む — 誤Back保護、D-2)。CTA処理中 (`continuing`) はいずれの入口も不受理。
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

### Scenario: import attemptのanchor — 遅延validation settleは成功状態を生成・置換しない

Given import開始時にholderが **attempt token** (process-local単調増加) を採番し、entry種別と、run内entryでは **owning runId** (開始時に `State.Selecting` を保持していたrunのid) をcaptureしたうえでvalidationを開始した、
When validation結果 (`Validated` / `Failure` / `InputNotReady`) が遅延して到着する、
Then settleは **capture時と同じattempt tokenがcurrent** な場合のみ適用される (current = そのattemptの `Importing` 画面が表示中か、そのattemptから生成された `ImportSuccess` / 失敗画面が表示中)。それ以外の遅延settleはdropされ、成功状態・失敗画面・status行を生成も置き換えもしない。特に:
- import A → 明示的なキャンセル (`close()` 等) → Aの `Validated` が後から到着: **キャンセル済みのため成功状態は出現しない** (破棄済みの画面を再出現させない),
- import A → 内容を変えてimport B → Bが先に `Validated` → Bの成功状態表示中にAの `Validated` が到着: **Aのsettleはdropされ、Bの成功状態は不変** (古いattemptで最新を上書きしない。逆順到着も同様に最新attemptのみがcurrent),
- run内entryでsettle時のrun stateがcaptureしたowning runIdの `State.Selecting` でない (run差し替え・dismiss後等): **成功状態を表示せずdropする** (凍結により通常発生しない。defense-in-depth)。

And attempt tokenは成功状態へ継承され (Data and state参照)、CTA settleと同一のanchorとなる。
And 遅延dropは「黙喪失」に当たらない: dropが起こるのは該当attemptがすでにキャンセル/置換済み (pending intentがすでに破棄済み) の場合のみであり、依頼が有効な間の再取り込みが回復pathである。

### Scenario: summaryの内容 (canonical representation基準)

Given validation通過済みの `ValidatedPersonalizedIntent` (その `completed: CompletedPersonalIntent` が正本。#330 D-4: authored文書はdiagnostics用のみでplanner/previewに流れない)、
When 取り込み成功状態が表示される、
Then summaryは **item-levelでは `completed` から導出した件数のみ** で構成され、加えてplanner-effectiveな全体方針行 (後述の `minimizeMovement`) を持ち、少なくとも次を含む:
- 認識した希望を持つ項目数 = `completed.authoredItemCount` (`RefDecision.Authored` のみを数える。bare entry = 全semantic fieldがnullのentryは #330 D-6でcanonical unresolvedへ正規化されるため **希望として数えない**。authored文書の `itemIntents.size` を直接数えない — bare entryを誤計上するため)、
- 判断なし項目数 = `completed.authoredUnresolvedCount + completed.omittedCount` の **合算値** (明示unresolved / bare entry / 未言及の3表現はsemanticに同一 (spec 330 D-5/D-6) であり、provenanceの区別はUIへ出さない。合算0件なら当該行は表示しない)、
- run内entryでは、依頼に含めた未配置アプリ候補数 (`session.scopeCandidates` の件数。既存の選択surface案内と同じ値)
And 内訳 (優先度指定件数 / グループ希望件数 / 配置先希望件数 / 保持希望件数) は **V1必須** (Decisions 3でowner確定) であり、`RefDecision.Authored` のdecisionのみを母集団として次の **item単位の算式** で数える:
  - 優先度指定件数 = `Authored.intent.importance != null` の件数、
  - グループ希望件数 = `Authored.intent.desiredGroupRefs` が非空 **または** `groupSemantic != null` の件数、
  - 配置先希望件数 = `pageAffinity != null` **または** `regionAffinity != null` の件数、
  - 保持希望件数 = `preserve != null` の件数 (`false` も「保持しない」という指定として1件に数える)、
  - 1項目が複数dimensionを同時に指定した場合は複数の行で数える (dimension毎の独立計数)。bare entryは全dimensionで非計上 (`Authored` ではないため)。各内訳行は0件なら表示しない、
- **全体方針 (global preference) 行**: `validated.intent.globalPreference?.minimizeMovement` はplannerが実際に消費する正本値 (`globalMinimizeMovement`、`null`/`false` はplanner効果なしと同一) であり、planner-effectiveな意味で表示する: `true` のとき「全体方針: 現状の配置をなるべく維持する」(en "Overall goal: keep the current layout as much as possible") の行を表示する。`false` / `null` のときは行を表示しない (authoring provenanceではなくplanner効果に寄せる)。global-only intent (item希望が1件もないが `minimizeMovement = true` のみを持つ) でも「認識した希望 0件」だけで終わらず、取り込み結果が空に見えないこと、
And app label・folder title・export-scoped `ref`・AI自由文 (`rationale`)・`confidence` は **表示しない**。理由: ref/labelはprivacy surfaceを広げ、`rationale` はuntrusted自由文の直接表示であり、`confidence` はAIの自己申告であり誤解を招くため (Issue AC「表示しない理由がspec化される」への回答)、
And summaryの導出は `validated.completed` (item計数) と `validated.intent.globalPreference?.minimizeMovement` (planner-effective boolean 1値。item計数の根拠ではないため #330 D-4のauthored文書制約に抵触しない) のみを入力とする純粋関数として切り出し、出力modelにlabel/ref/text fieldが存在しないことをcontract testで固定する。表示した全体方針行はCTA後のplanner projection (`globalMinimizeMovement`) と一致することをcontract testで固定する。

### Scenario: CTAで次ステップへ (idle entry、single-flight)

Given idle entryの取り込み成功状態が表示されている、
When ユーザーがCTAを押す、
Then 既存の #205 fresh run接続seam (`ManualOrganizationRun.start(trigger, intent)`) が **一度だけ** 実行され、取り込み成功状態は閉じ、通常flow (#228 detection → 選択、または検出Unavailableならcomposition直行) へ進む、
And CTA処理はsingle-flightである: 最初の押下で同期的にCTA処理中 (`continuing`) へ遷移し、処理中の追加押下はseamを呼ばず拒否される (二重押下で `start` が複数回呼ばれない)。接続結果のsettleは **当該import attemptに採番されたprocess-localなattempt token** (成功状態へ継承) に紐付き、tokenが一致しない画面 (閉じた/置き換わった/再importで作り直された成功状態) への遅延settleは適用されない (既存の `beginTransport` / `settleBelongsTo` と同じ構造的パターン。tokenの性質はData and state参照)、
And CTA処理中は破棄・system Backが **不受理** である (次scenario)。
And 以降は既存契約どおり: 選択確定時のscope binding gate (#331、candidate集合∅との完全一致)、planning、#194 preview、#195 confirm、spec 13 apply。本specはこれらを変更しない、
And gateが `Busy` を返した場合 (CTA時に他の操作がactive)、CTA処理中状態は解除され取り込み成功状態が維持・再試行可能になり、typed案内 (`RUN_BUSY` 系) が表示される (zero-write)。遅延した拒否settleが、すでに閉じた成功状態へ `RUN_BUSY` 表示を残さないこと。
And seamが例外で抜けた場合もsettle契約が閉じる: `run.start` はdetection/planning中の `Throwable` を `abort()` 後に再throwするため、CTA側は例外をcatchしたうえで **呼出coroutine自体がcancel済みかを先に判定し** (`currentCoroutineContext().ensureActive()` 相当。cancel済みなら例外をそのまま再伝播させ、生存scopeのUI stateを触らない)、contextが生存している場合は **`CancellationException` を含む全ての例外をattempt-boundなfailure settleへ変換する** (処理中状態を解除し `continuing = false` へ復帰、typed案内を表示、成功状態を再試行・破棄可能に保つ。run側のabortはseam内で完了済みのためzero-write契約と整合)。seam内部発の `CancellationException` で `continuing = true` のまま取り残される経路、処理中のまま破棄・Backが永久に不能になる経路、および旧attemptへの誤settleを残さないこと。holder scope自体の破棄 (画面消失) によるcancelはUI settleを要求しない。
And **strategy変更とimport continuationは相互排他の競合操作** である: idle CTA押下で `run.start` が実行されると、settle前にrunはactive (`State.Capturing` → `CandidateDetection`/`Selecting`) となるため、処理中のstrategy選択変更は既存の `dismiss()` → `start(trigger)` pathで **validated intentを持たない別run** へ差し替えられ得る。よって (1) **idle entryも `continuing` の間はstrategy pickerを無効化** し (「idle pickerは常に操作可」ではない)、(2) UIのenabledに依存しない **commit時gate** を置く: strategy書込が **import continuation中 (両entry) にcommitした場合、およびrun内entryではimport attempt生存中の全期間 (validation中・成功状態表示中を含む) にcommitした場合**、commit時のrun判定による `dismiss()` → `start(trigger)` を **発火させない** (strategy自体のcommitは無効化しない — 次回以降のrunに反映。run内の「import開始前に始まった書込が成功状態表示中にcommitし、owning runを差し替える」競合を含む)、(3) **strategy書込がin-flightの間はCTAと新規importの開始を拒否する** (typed案内で再試行)、(4) **`onStrategySelected` 自体にもstate gateを置き**、run内entryのimport attempt生存中およびidle entryのimport continuation中は **新しいstrategy書込を開始しない** (UIのrecomposition前のqueued click等をUI disabledだけに依存せず遮断する)。いずれもUI表示だけでなくstate上のgateで保証する。
And **commit時gateは単一のarbiter状態機械 (`Idle → Writing → RestartReserved/Restarting → Idle`) で動く**: restart可否の判定とreservation取得はMain-confined直列化点上で原子的に実行し、`dismiss()` → `start(trigger)` の実行はIO上で行う (startは同期heavy — Mainで実行しない)。`Writing` / `RestartReserved` / `Restarting` の全期間、import開始・CTA開始ともtypedに拒否される。arbiterはrestart成功・非commit・`start()` throw・coroutine cancelの全終端で解除される。import開始とrestart判定は必ず全順序を持ち (import attempt先行なら書込開始gateによりW自体が開始しない / W先行ならimportは拒否され重ならない)、「W側がgateを読んで可と判断した直後、restart実行前にimportが開始し、attemptが内部競合で黙ってdropされる」競合窓が存在しない、
And **CTA時にもowning runId (run内entry) を再照合する** (defense-in-depth): run内entryの成功状態はowning runIdを保持しており、`attachIntent` 呼出直前に現在の `State.Selecting.runId` が一致することを確認する。不一致 (成功状態表示中のrun差し替え等) の場合は `attachIntent` を呼ばず、typed failure settleとして処理する (処理中解除・typed案内・破棄/再取り込み可能。既知のfreezeだけにrun identity保証を依存しない)。
And 成功settleは **defense-in-depthとして `Started.runId` の一致も確認する**: settle時に現在のrun stateが露出するrunId (例: `State.Selecting.runId`) が取得でき、かつseamが返した `Started.runId` と一致しない場合 (競合でrunが差し替えられた)、成功settleを適用せずfailure settleとして扱う (処理中解除 + typed案内、成功状態は再試行・破棄可能に維持)。

### Scenario: CTA処理中 (`continuing`) の破棄・system Back不受理

Given いずれかのentryで取り込み成功状態が表示され、CTA押下により `continuing` へ遷移し、run接続seam (`start` / `attachIntent`) のsettleが未到達である、
When ユーザーが破棄ボタン・system Back・その他の破棄入口を操作する、
Then いずれも **受理されない**: 破棄ボタンはdisabled、system Backのinterceptionは確認dialogを出さずBackを取り込む (何もしない)、`discardImport()` は何も変更せずreturnする。つまりCTA処理中に成功状態が閉じる・pending intentが破棄される経路は存在しない、
And この不受理はseamのmutex性に依存しない構造guardである (UI stateの `continuing` flagを単一の入口で検査)。seamがrun stateをsettle前に変更する性質 (`beginOperation` / intent bind) のため、処理中の破棄受理は「破棄済み表示」と「開始済みrun」の乖離を避けられない — それを避けるのが本guardの目的である、
And settle後は通常どおりである: 成功 (`Started` / `Attached`) なら成功状態は閉じて既存flowへ進み、拒否 (`Busy` / `NotAttachable`) なら `continuing` が解除され、破棄・Back・再試行が再度可能になる。

### Scenario: 同一identityの再importに対する遅延settle分離 (ABA)

Given 取り込み成功状態AでCTAが押され (`continuing`、attempt token `tA`)、settleが未到達のままAの画面が閉じられた/置き換えられたとする (例: 拒否settle経由の継続表示ではなく、成功settleによる遷移後の再import等)、
When 同一semantic内容の回答が再importされ、同じ `validated.identity` (content digest、#330 D-5) を持つ新しい成功状態Bが表示され、BでCTAが押される (`attempt token tB ≠ tA`)、
Then その後到着するAの遅延settleは **Bへ適用されない** (token不一致でdrop)。Bの表示・`continuing` 状態・CTA対象は変化しない。`identity` の一致だけではattemptを区別できないため、anchorはattempt tokenであることの証明testである。

### Scenario: run内entry — 取り込み成功状態と選択surfaceへの復帰

Given #331 run内entry (runが `State.Selecting` を保持し、exchange stepのため選択がfreeze中) でAI回答がvalidationに通過した、
When 取り込みが成功する、
Then 同一の取り込み成功状態が表示され (候補数を含む)、選択は引き続きfreezeされたまま (= run状態は不変)、
When ユーザーがCTAを押す、
Then single-flightの下で既存の `ManualOrganizationRun.attachIntent` seamが **一度だけ** 実行され、validated intentが当該runへ接続され、取り込み成功状態が閉じ、選択編集が有効化される (既存の `intentScopeCount` 案内表示は継続)、
And attachが `NotAttachable` を返した場合 (selection surface消失・二重bind等)、CTA処理中状態は解除され取り込み成功状態は維持されtyped案内が表示される (zero-write、intent破棄はしない)、
And 成功状態の表示中、strategy pickerの選択変更によるrun差し替え (既存の `onStrategySelected` のrun active時 `dismiss()` → `start(trigger)` path) は無効化される (次scenario)。

### Scenario: 競合affordanceの無効化 (idle row + strategy picker、両entry)

Given import attemptが生存している (validation中の `Importing` から取り込み成功状態の表示、および成功状態の終了・破棄・キャンセルまでの全期間。idle entryではさらにCTA処理中 `continuing` を含む)、
When 同一surfaceにrunを開始・再開する競合affordanceが存在する、
Then **idle entry** では「整理を開始」rowが無効化され (#331の選択freezeと同じ拘束パターン。validation中も含む — validation中に開始された新runの上に後から成功状態が載ることを防ぐ)、**run内entry** ではstrategy pickerの選択変更が無効化される (run active時の `dismiss()` → `start(trigger)` によるrun差し替えが、pending intent・freeze済み選択・成功状態のCTA対象を黙って入れ替える経路の遮断。成功状態表示後だけでなくvalidation中も含む)、
And **idle entryのstrategy pickerは、CTA処理中 (`continuing`) は無効化される** (idle CTAで `start` が実行されるとsettle前にrunがactiveになるため、この窓のstrategy変更はvalidated intentを持たない別runへの差し替えを起こし得る。idle pickerが常に操作可能という扱いではない)。`continuing` でない間 (run非active) は操作でき、変更は既存どおりpreference書込のみでrun再開pathを発動しない、
And **UIのdisabledに依存しないcommit時gateが存在する**: strategy書込が (a) import continuation中 (両entry)、または (b) run内entryではimport attempt生存中の全期間 (validation中・成功状態表示中を含む。`continuing` の有無によらない) にcommitした場合でも、commit時のrun active判定による `dismiss()` → `start(trigger)` は発火しない (strategyのcommit自体は有効で次回以降のrunに反映される。imported run・owning runを黙って差し替えない)。**restart可否の判定とreservation取得はMain-confined直列化点上で原子的に行われ、`dismiss()`/`start(trigger)` の実行はIO上で行われる (Mainで同期heavy seamを実行しない)** — arbiter (`Writing`/`RestartReserved`/`Restarting`) の全期間、import開始・CTA開始ともtypedに拒否され、gate読取とreservation取得の間にimport開始が割り込む競合窓は存在しない (全順序保証)、
And **arbiterがnon-idle (`strategyArbiterBusy := arbiterState != Idle`) の間は、CTAに加えて新規importの開始自体もtypedに拒否される** (再試行可。pre-existing書込とimport attemptの重なりを起点で排除)、
And **strategy書込がin-flightの間はCTAを開始できない** (typed案内で再試行。書込commitとimport continuationの直列化)、
And **`onStrategySelected` にもstate gateがあり**、run内entryのimport attempt生存中およびidle entryのimport continuation中は **新しいstrategy書込自体を開始しない** (UI disabledのrecompose遅延・queued clickを構造で遮断)、
And いずれの競合操作によっても成功状態とpending intentが黙って消える経路は存在しない。無効化は視覚のみでなくaccessibility semanticsでも判別可能である。

### Scenario: warning (判断なし項目あり) とsuccess/rejectの区別

Given validation通過済みintentのcanonical判断なし件数 (`authoredUnresolvedCount + omittedCount`) が0より大きい、
When 取り込み成功状態が表示される、
Then 表示は「取り込みは成功・判断なし項目あり」であり、**失敗・rejectと誤認させない** (reject表示は既存の取り込み結果画面が担い、本状態は失敗経路を置き換えない)、
And 判断なし項目の件数 (合算。provenance非表示) と、判断なし項目も従来どおり整理対象であること (preferenceなしで扱われること) が説明される、
And successとwarning-with-no-judgmentはsemantics上区別される (見た目のみでなく、heading/live region等でTalkBackが読み分けられる。「Accessibility」参照)。

### Scenario: 取り込み破棄 (CTAを押さずに閉じる)

Given 取り込み成功状態が表示されており、CTA処理中 (`continuing`) ではない、
When ユーザーがCTAを押さずに破棄操作を行う (D-2確定: 明示ボタン「破棄して閉じる」の押下、またはsystem Back → 確認dialog「取り込みを破棄しますか?」での確定)、
Then pendingなvalidated intentは **破棄** される (zero-write、run接続なし)。破棄であること、および依頼 (export session) が有効な間は同じ回答textを再取り込みできることが表示される (system Backの確認dialogは当該案内を本文に含む。明示ボタン経由の場合は破棄後の案内文言で)、
And exchange sessionのinvalidateは行わない (再取り込み可能性を保つ)、
And validated intentの保持・永続化は行わない (#205/#331契約の継承。process deathで失われても再取り込みが回復pathである)、
And 画面を閉じただけでimport結果が **黙って** 見失われる状態は存在しない (破棄は常に明示的: ボタンはlabelで破棄を明示、system Backは確認dialogを挟む)。

> D-2はowner decisionとして確定済み ([Issue コメント](https://github.com/nunu1733/NunuLauncher/issues/328#issuecomment-5723309396)): 明示ボタンはlabelで足り (追加確認なし)、system Backは確認dialogを挟む。interceptionの配置 (hosting画面level・常時composition) と成功state画面外での保護は本specで固定済み。CTA処理中は両入口とも不受理 (前出scenario)。

### Scenario: system Backのinterceptionは成功stateが画面外でも保証される

Given 取り込み成功状態が表示されており、large font等により成功stateのlazy list itemがviewport外 (composition外) にある、
When ユーザーがsystem Backを押す、
Then Backは成功状態の破棄handling (D-2確定: 確認dialog。CTA処理中なら確認dialogを出さず取り込む) として処理され、親の画面level Back処理 (`ManualOrganizationBackHandler` の `dismiss()` → run cancel / navigate away) に **落ちない**。pending intentが確認なしで消えない、
And この保証は成功stateのcomposableがcomposition中であることに依存しない (interceptionは常にcompositionされるhosting画面levelに登録され、成功state表示中のみ有効化される)。lazy item内への `BackHandler` 登録のみでは不十分 (viewport外でcallbackが存在しなくなる) であることを構造として回避する。

### Scenario: 既存失敗経路の無変更

Given importがframing失敗・normalization失敗 (#329)・validation reject・`INPUT_OVERSIZE`・clipboard/file読取失敗・入力未ready等で失敗する、
When 失敗が表示される、
Then 既存の取り込み結果画面 (typed 19種失敗表示 + 認識framing/version/entry数表示 + 折りたたみraw + 再取り込み) が従来どおり機能し、本specは失敗表示を変更しない (regression条件)。

### Scenario: process death

Given 取り込み成功状態の表示中にprocessが破棄される (CTA処理中 `continuing` ではない)、
Then runは未開始・未接続であり、失われるのはpending intent (process-local) のみで、layout DB・export sessionへの影響はない、
And 再起動後の回復は既存どおり再取り込みである (session有効期限内であれば同じ回答textが再検証を通過する)。

### Scenario: process death (CTA処理中 `continuing`)

Given CTA押下後のseam実行中 (`continuing`) にprocessが破棄される、
Then seamがすでにrun stateを進めている可能性はある (`start` のdetection/planning進行、または `attachIntent` のbind済み) が、apply前のprocess-local stateのみであり、layout DBへのdurable writeは発生していない。再起動後のrun/success状態は復元されず (attempt token非永続)、回復は再取り込みである (session有効期限内)、
And process deathにより「破棄済み表示と開始済みrunの乖離」が継続することはない (成功状態ごとUI stateが消失するため)。

## Data and state

- 読むdata: `ValidatedPersonalizedIntent` とその **`completed: CompletedPersonalIntent`** (`decisions` / `authoredItemCount` / `authoredUnresolvedCount` / `omittedCount`。正本は #330 D-4のcanonical representation。authored文書の `itemIntents` / `unresolvedRefs` をsummaryの計数根拠にしない)、`session.scopeCandidates`、`validated.intent.globalPreference?.minimizeMovement` (planner-effective boolean 1値 — summaryの全体方針行の根拠。planner `IntentPlannerAdapter` が消費する同一値)、entry種別 (idle / run内)、run内entryではimport開始時のowning runId (`State.Selecting.runId`)。#204/#330/#331契約を本specは再定義しない。
- 永続化: **追加なし**。取り込み成功状態とpending intentはprocess-local (exchange flowのUI state) のみ。export session (#204、durable・TTL 24時間) は破棄操作でinvalidateしない。
- run state: **validation開始から `ImportSuccess` 表示中の `!continuing` の間**、`ManualOrganizationRun` の状態は不変 (idle entry: Idle/Cancelledのまま。run内entry: owning runIdのSelectingのまま・freeze継続)。**CTA開始 (`continuing`) 後は既存seam (`start(trigger, intent)` / `attachIntent`) がrun stateを先行変更し得るが、strategy restart等の **別操作によるrun差し替えは禁止** (相互排他gate)。CTA処理中は同期的な処理中標識 (`continuing` flag) により追加のseam呼出が構造的に拒否され、破棄・Backも不受理である。
- **attempt token (settle anchor)**: import開始時に採番されるprocess-localな単調増加token。validation settle (`Validated` / `Failure` / `InputNotReady`) とCTA settleの双方がこれに紐付き、settleは「current画面が同じattempt tokenに属する」場合のみ適用される。tokenはUI state内のみで消費され、`validated.identity` (content digest、#330 D-5 — 同一semantic内容の再importで同一値になる) はanchorに使わない。キャンセル・入力変更・再importは旧attemptをinvalidateする。
- **strategy書込との相互排他 (単一のarbiter状態機械)**: strategy選択変更の書込 (既存 `onStrategySelected` → `store.select` → commit → run判定 → `dismiss()`/`start(trigger)`) とimportの開始・継続は直列化する。arbiter状態機械は **単一** で、`Idle → Writing → RestartReserved/Restarting → Idle` のみ: `Writing` = `store.select` 呼出〜commit/no-op判定、`RestartReserved` = Main-confined直列化点上でrestart可否判定を通過しrestart権を取得した状態 (判定と取得は原子的)、`Restarting` = `dismiss()`/`start(trigger)` をIO上で実行中 (`start` は同期heavyのためMainでは実行しない)。`Writing` / `RestartReserved` / `Restarting` の **全期間で新規import開始とCTA開始をtypedに拒否する** (再試行可) — したがって「importが書込と重なって成功する」経路は原理的に存在せず、import attemptが先に成立した場合は **書込開始gate** (`onStrategySelected` はrun内entryの `importAttemptActive` 中およびidle entryの `importContinuationActive` 中は新しい書込を開始しない) によりW自体が開始しない。import開始とrestart判定は同一直列化点で全順序を持ち、「gate通過後にrunを差し替えられる」競合窓および「内部競合による黙ってdrop」を排除しつつ、Main上で同期heavy seamを実行しない。**arbiterの解除は全ての終端で行う** (restart成功・非commit・`start()` throw・coroutine cancel — `finally` 相当で解除し、import/CTA拒否を残留させない)。いずれもUI disabledとは独立したstate gateである。
- migration / backup / rollback: 影響なし (DB schema変更なし、新規storageなし。attempt tokenも永続化しない)。

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

- [ ] AC-1: import成功 (validation通過) 時、両entry (idle / run内) で取り込み成功状態が表示され、run接続 (attach/start) はCTA押下まで実行されないことがtestされる (**CTA押下前** のrun state不変・zero-writeを含む。CTA押下後のrun state変化は既存seamの正常な挙動)。validation settleのattempt anchorがtestされる: import → キャンセル → 遅延 `Validated` で成功状態が再出現しないこと、import A → import B の逆順settleでB (最新attempt) のみが成功状態になりAの遅延settleで置換されないこと、run内でsettle時にowning runIdが `State.Selecting` を外れていたら成功状態を表示しないこと。
- [ ] AC-2: 取り込み成功状態が「まだホーム画面には適用されていない」ことを明示することがtestされる (string存在 + 表示)。
- [ ] AC-3: 次のOrganizer操作へ進む主要CTAが存在し、CTAで既存seam経由の接続が **一度だけ** 行われることがtestされる (idle: `start(trigger, intent)`、run内: `attachIntent`)。CTAのsingle-flightがtestされる: 二重押下でseamが複数回呼ばれないこと、および成功状態が閉じた後の遅延settleが表示へ適用されないこと。settleのanchorがattempt tokenであることが **同一 `validated.identity` の再import (ABA) test** で固定される: 旧attemptの遅延settleが、同一identityで作り直された新しい成功状態attemptへ適用されないこと。CTA処理中 (`continuing`) の破棄・Backが不受理であることがtestされる (seam開始 → settle前に破棄/Back試行 → 不受理で成功状態維持、seamは影響を受けずsettleへ進行)。**seam例外時** もtestされる: (a) fake seamが非`CancellationException` をthrow → 処理中解除・再試行/破棄可能に復帰・run abort済み・旧attempt誤settleなし、(b) fake seamが **`CancellationException` をthrowするがholder scopeは生存** → 同様にfailure settleへ落ち `continuing = false` で復帰する (取り残しなし)、(c) holder scope自体をcancelした場合はUI settleを要求しない。**strategy変更との相互排他** もtestされる: (d) idle CTAでfake `run.start` をdetection/planning中で停止させ、strategy変更試行 → `dismiss()`/`start(trigger)` が非発火でvalidated runが維持されること、(d2) **排他の2方向 (確定policy)**: (i) W先行 — 書込開始からのarbiter状態 (`Writing`/`RestartReserved`/`Restarting`) 全期間でimport開始がtypedに拒否され、importが書込と重なって成功する経路が存在しないこと、(ii) import先行 (run内) — import attempt成立後の `onStrategySelected` が書込開始gateでW自体を開始しないこと (owning runはdismiss/restartされない)。idle entryでは `ImportSuccess && !continuing` 中のstrategy書込は **許可** であり (正規経路)、その `Committed + run inactive` 終端で `dismiss()`/`start(trigger)` が発火せず arbiterが `Idle` へ戻ること (Writing → Idle 遷移)、(d3) arbiter状態中 (`Writing`/`RestartReserved`/`Restarting`) の新規import開始とCTA開始がtypedに拒否されること、(d4) **arbiter解除 (table-driven)**: 終端各種 — restart成功 / **Committed-no-restart (`Committed` だがrun非active・restart不要/抑止)** / 非commit (`Committed` 以外) / `start()` throw / coroutine cancel — のすべてでarbiterが `Idle` へ戻り (finally相当) import・CTAが再開可能になること、(e) **run内のpre-existing書込とimportは重ならない (確定policy)**: R1 `Selecting` → strategy書込W開始 → import試行 → **typed拒否・validation未開始** → Wのrestart判定/必要ならIO上でrestart完了 → arbiter解除 → import再試行 → 現在のowning runで `ImportSuccess` 表示、(f) arbiter non-idle中 (`strategyArbiterBusy`) のCTA開始が拒否されること (`RestartReserved` / `Restarting` の各状態を含む)、(g) continuation flip後の直接 `onStrategySelected` 呼出で書込もrestartも開始されないこと (書込開始gate)、(i) **strategy write同士のsingle-flight**: fake storeでW1を `Writing`/`Restarting` 中に停止 → 別strategyをtap → **2本目の `store.select` / restartが開始されないこと** (arbiterが `Idle` のときのみ `Idle → Writing` を取得可) → W1の終端後にのみarbiterが `Idle` へ戻ること (run active時で検証し、W1のreleaseが別writeを誤って解放しないこと含む)、(h) **attach前のowning runId再照合**: 成功状態表示中にowning runIdのrunが差し替えられた状態 → CTAで `attachIntent` を呼ばずtyped failure settleになること。CTA時のgate拒否 (`Busy` / `NotAttachable`) は処理中状態を解除し取り込み成功状態を維持したままtyped案内され、解除後に破棄・Backが再度可能であることがtestされる。
- [ ] AC-4: privacy-safe summary (件数: `authoredItemCount`・判断なし合算件数 (`authoredUnresolvedCount + omittedCount`)・run内では候補数、**V1必須の内訳4種** (優先度/グループ/配置先/保持。Decisions 3)、**全体方針行** (`minimizeMovement == true` で表示)) が導出されることがtestされる。item計数が **`completed` canonical representationから** 導出されること、bare entryが希望件数と全内訳に計上されないこと、未言及refが判断なし件数に計上されること (bare/omission合算の計数test oracle)、内訳の **item単位算式** がtestされる (優先度 = `importance != null`、グループ = `desiredGroupRefs` 非空 **または** `groupSemantic != null`、配置先 = `pageAffinity != null` **または** `regionAffinity != null`、保持 = `preserve != null` (`false` も1件)。複数dimension指定項目が複数行で数えること、0件行を表示しないこと含む)、**global-only intent** (`minimizeMovement = true` のみ・item希望0件) で全体方針行が表示され取り込み結果が空に見えないこと、**表示した全体方針行がplanner projection (`globalMinimizeMovement`) と一致すること** (contract test)、`false`/`null` で行を表示しないこと、provenanceがuser-visibleでないこと、非表示項目 (`rationale` / `confidence` / label / ref) とその理由が本specに固定されていること (summary導出の純粋関数 + 出力modelの非混入contract test) を含む。
- [ ] AC-5: CTAを押さずに閉じる場合のlifecycleがD-2確定内容 (明示ボタン「破棄して閉じる」は追加確認なし、system Backは確認dialog) どおり実装される: 破棄が明示的であること、exchange sessionがinvalidateされないこと (破棄後に同じ回答textの再取り込みが成立すること)、競合affordance (idle「整理を開始」row、run内strategy pickerのrun差し替え) が **import attempt生存中の全期間** (validation中の `Importing` を含む) 無効化されること、**idle entryのstrategy pickerが `continuing` 中に無効化されること**、**commit時gateがrun内entryのimport attempt全期間 + 両entryのimport continuation中に効くこと** (strategy書込の遅延commit → `dismiss()`/`start(trigger)` 非発火)、**arbiter状態中 (`Writing`/`RestartReserved`/`Restarting`) のCTA開始と新規import開始が拒否されること**、**restart判定のMain-confined直列化とIO実行分離** (`dismiss()`/`start(trigger)` はIO上で実行されること)、**arbiterの全終端解除 (table-driven)** (restart成功・**Committed-no-restart**・非commit・`start()` throw・cancelで解除され拒否が残留しないこと)、**idle `ImportSuccess && !continuing` 中のstrategy選択 → `Committed` → dismiss/start非発火 → arbiter `Idle` 復帰 → CTA再試行成功** の1連の経路、**書込開始gate** (run内attempt中/idle continuation中の `onStrategySelected` が新しい書込を開始しないこと) がtestされる。CTA処理中の破棄拒否 (`discardImport()` 不受理) を含む。
- [ ] AC-6: success / warning (判断なし合算 > 0) / rejectが、TalkBackを含めて識別可能であること (semanticsベース。手動/instrumentation evidence)。
- [ ] AC-7: system Backが成功stateのlazy item viewport外 (large font) でも成功状態のhandlingに捕捉され、親のdismiss/navigateに落ちないことがinstrumentation testされる。
- [ ] AC-8: large font (font scale最大) で次操作 (CTA) と未適用表示が画面内で把握できること (手動/instrumentation evidence)。
- [ ] AC-9: Import → CTA → 次ステップ (選択またはcomposition) → preview までのend-to-end device evidenceがある (physical device)。
- [ ] AC-10: 既存失敗表示 (typed 19種取り込み結果画面、入力source失敗statusを含む) と既存export flow (生成・送信前確認・transport) がregressionなく機能すること。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `ExchangeFlowStateHolder` unit test (validated → 取り込み成功状態、run state不変、attach/start未呼出; **blocking fake import による (a) import → cancel → 遅延Validated drop、(b) A/B逆順settleで最新attemptのみが成功状態、(c) run-inでsettle時owning runId不一致drop**) + hosting instrumentation test |
| AC-2 | string解決test + UI表示 (unit/instrumentation) |
| AC-3 | holder unit test: CTA → seam呼出 (`Started`/`Attached`)、`Busy`/`NotAttachable` で状態維持・処理中解除。**二重押下でseam 1回のみ** (continuing flag guard)、**遅延settleが閉じた/置き換わった成功状態へ適用されない** (attempt token anchor)、**ABA: 同一identityで再importした新attemptに旧attemptの遅延settleが適用されない**、**CTA処理中の破棄/Back不受理**、**seam例外** (非CE throw → failure settle / **CE throwだがscope生存 → failure settle** (取り残しなし) / scope cancel → UI settle要求なし)、**strategy相互排他** (blocking fakeで (d) idle CTAの `start` をdetection/planning中に停止 → strategy変更 → dismiss/start非発火、(d2) 排他の2方向 (W先行 → arbiter全期間でimport拒否・重なり成功なし / import先行・run内 → 書込開始gateでW開始せずrestart抑止)、(d5) **write同士single-flight** (W1をWriting/Restarting中に停止 → 別strategy tap → 2本目のselect/restart不開始 → W1終端後にIdle復帰)、(d3) arbiter状態中 (`Writing`/`RestartReserved`/`Restarting`) のimport開始・CTA開始typed拒否、(d4) arbiter解除 table-driven (restart成功 / Committed-no-restart / 非commit / `start()` throw / cancel)、(e) **pre-existing書込はimportと重ならない**: W開始 → import試行 → typed拒否・validation未開始 → W restart判定/完了 → 解除 → import再試行 → owning runでImportSuccess、(f) arbiter non-idle中のCTA拒否、(g) continuation後の直接 `onStrategySelected` 呼出 → 書込/restartとも開始されない、(h) attach前owning runId再照合 → 不一致でattachせずfailure settle)、**runId不一致settle** (`Started.runId` ≠ 現在runのrunId → failure settle扱い)。`ManualOrganizationRunTest` 連携 |
| AC-4 | summary純粋関数のunit test: `authoredItemCount` / `authoredUnresolvedCount + omittedCount` の導出、**bare entry非計上** (bare 1件 → 希望0件・判断なし1件・全内訳0件)、**omission計上** (未言及2件 → 判断なし2件)、**内訳4種の算式** (importance/desiredGroupRefs+groupSemantic/pageAffinity+regionAffinity/preserve。複数dimension指定項目が複数行で数える、`preserve=false` を1件と数える、0件行非表示)、**global-only intent oracle** (`minimizeMovement=true` のみ → item計数0でも全体方針行あり、`false`/`null` → 行なし、planner `globalMinimizeMovement` との一致contract test)、出力modelへlabel/ref/text fieldが存在しないことの型/contract test |
| AC-5 | holder unit test (discard → session invalidate未呼出、再取り込み成立の往復test、破棄後pending intent消失、**continuing中のdiscard不受理**) + idle競合row無効化UI test (**validation中含む**) + run内strategy picker無効化UI test (**validation中含む**) + **idle pickerのcontinuing中無効化UI test** + **commit時gate test** (run内attempt全期間 + 両entrycontinuation中の遅延commit → dismiss/start非発火) + **arbiter状態中のCTA拒否・import開始拒否test** + **restart判定のMain-confined直列化とIO実行分離test** + **arbiter全終端解除 test (table-driven: restart成功 / Committed-no-restart / 非commit / `start()` throw / cancel)** + **idle ImportSuccess中strategy選択 → Committed → 非発火 → Idle復帰 → CTA成功 test** + **書込開始gate test** (run内attempt中/idle continuation中の `onStrategySelected` が書込を開始しない) + system Back確認dialog経路 (確定で破棄・キャンセルで成功状態維持) test |
| AC-6 | a11y assertion (semantics/live region) + TalkBack手動evidence |
| AC-7 | instrumentation: 成功state表示中に成功itemをviewport外へscrollさせ (またはfont scale最大で同等状態にし) system Back → 親dismiss/navigateが発火しないことのassertion |
| AC-8 | font scaleを上げたinstrumentation/手動evidence |
| AC-9 | physical device evidence (docs/assessment/ またはIssue。#205 AC-10 evidenceと兼ね可) |
| AC-10 | 既存 `ExchangeFlowStateHolderTest` / `ExchangeFlowControllerTest` / 失敗表示testのregression実行 |

## Decisions (owner確認済み)

owner decision記録: [Issue コメント](https://github.com/nunu1733/NunuLauncher/issues/328#issuecomment-5723309396) (2026-09-18)。未決のopen questionは残っていない。

1. **CTA最終文言 (D-3) — 確定**: 次surfaceを断定しすぎない中立文言。idle entry「この提案で整理を進める」(en "Continue with this proposal")、run内entry「選択に戻って整理を確定する」(en "Return to selection to finish organizing" — attach成功時の復帰先が選択surfaceであることはrun内entryで確定)。Issue候補「次へ: 整理方法を選ぶ」はstrategy選択がpickerで並存し次画面が選択surfaceとは限らないため不採用。ja正本 (spec 123 / #205 Decision 4)。
2. **Backの破棄確認形式 (D-2) — 確定**: 明示ボタン「破棄して閉じる」はlabelで足り追加確認なし。system Backは確認dialog「取り込みを破棄しますか?」を挟む (確定で破棄・キャンセルで成功状態維持)。dialog本文に再取り込み案内を含む。interceptionの配置 (hosting画面level・常時composition) と画面外保護は本spec固定済み。CTA処理中は両入口とも不受理。
3. **summary内訳の粒度 — 確定**: 優先度/グループ/配置先/保持の種別別件数をV1に含める (`RefDecision.Authored` のみから導出、件数のみ)。実装PRでの微調整を許容。

## Change history

- 2026-09-16: Draft created for #328 (baseline `aab0d293d1a9` = origin/main)。現行実装 (#205 PR #325 / #331 PR #333 後) の成功時挙動 (即時attach/start + 1行status、失敗専用の取り込み結果画面、Back時の黙喪失) をコード確認の上、取り込み成功状態の中間状態・summary・CTA・破棄semantics・a11yを起草。D-2/D-3はowner decision待ちでdraft。
- 2026-09-18: Re-entry revision (review Required 1–4対応)。baselineを `8fd05a40d51a` (origin/main、#329/#330/#332/#336/#348実装merge後) へ更新。**(1)** summaryを #330 v3 canonical representation基準へ変更: 認識希望数 = `completed.authoredItemCount`、判断なし = `authoredUnresolvedCount + omittedCount` の合算 (provenance非表示、D-5/D-6のsemantic同一性に整合)、warning条件を判断なし合算 > 0へ明示、内訳はAuthoredのみから導出。旧coverage不変条件 (export items = `itemIntents` ∪ `unresolvedRefs`) はv3で不成立のため除去。**(2)** system Backのinterceptionを常時compositionされるhosting画面levelへ配置するよう固定 (lazy item内BackHandlerはviewport外で無効になるため不採用)、AC-7を追加。**(3)** run内entryの成功状態中表示中にstrategy pickerのrun差し替え (dismiss→restart) を無効化するよう競合affordance scenarioへ追加。**(4)** CTAのsingle-flight (同期処理中遷移・identity anchored settle・二重押下/遅延settle拒否) をspec/AC-3へ追加。失敗種別をtyped 19種 (#329 normalization追加後) へ更新、#332/#329/#330/#348をimplementedとして参照更新。
- 2026-09-18: Re-entry revision 2 (2nd review Required 1–3 + acceptance blocker対応)。baselineを `a9ec3c2cf9` (origin/main、#327実装merge後) へ更新 (#327をimplementedへ参照更新、capability説明はvalidation通過後flowに変更面なしと確認)。**(1)** CTA処理中 (`continuing`) の破棄・system Back **不受理** を構造guardとして固定 (seamはsettle前にrun stateを変更するため、処理中の破棄受理は「破棄済み表示」と「開始済みrun」の乖離を避けられない)。破棄scenario・Back scenario・新scenario・AC-3/AC-5へ反映。**(2)** settle anchorを `validated.identity` (content digest、同一内容の再importで同一値 → ABA競合) から **process-localな単調増加generation token** へ変更し、同一identity再import時の遅延settle分離 (ABA) scenarioとtest oracleを追加。**(3)** **D-2/D-3/Open question 3をowner decisionとして確定** ([Issue コメント](https://github.com/nunu1733/NunuLauncher/issues/328#issuecomment-5723309396)) — CTA文言 (idle「この提案で整理を進める」/ run内「選択に戻って整理を確定する」)、明示破棄ボタンlabel「破棄して閉じる」(追加確認なし) + system Back確認dialog、summary内訳V1採用。「Open questions」を「Decisions (owner確認済み)」へ置換。
- 2026-09-18: Re-entry revision 3 (3rd review 高×1・中×2・低×1対応)。**(1) import validation自体のattempt anchor** (高): import開始時にattempt tokenを採番し、entry種別とrun内entryはowning runIdをcapture。validation settle (`Validated`/`Failure`/`InputNotReady`) もtokenに紐付き、キャンセル後・再import後 (A→B逆順) ・owning runId不一致の遅延settleは成功状態を生成・置換しない (新scenario「import attemptのanchor」、AC-1へ追加)。成功状態のsettle anchorは当該attempt tokenと統一。競合freezeの対象期間を **import attempt生存中の全期間** (validation中の `Importing` を含む) へ拡張 (競合affordance scenario・AC-5)。**(2) CTAのseam例外時契約** (中): `CancellationException` は再throw、それ以外のseam例外はattempt-boundなfailure settleへ変換し処理中解除・再試行/破棄可能 (`start` は `abort()` 後に再throwする実装事実に基づく)。CTA scenario・AC-3へ追加。**(3) summary内訳4種のV1必須化** (中): owner確定済み (Decisions 3) を反映し「optional」表記を廃止、item単位の算式 (importance / desiredGroupRefs+groupSemantic / pageAffinity+regionAffinity / preserve (`false` も1件))・複数dimension同時計数・0件行非表示をscenario・AC-4・oracleへ固定。**(4) process death scenarioのcontinuing分岐** (低): `!continuing` (run未開始・未接続) と `continuing` (run state進行の可能性はあるがdurable writeなし、UI stateごと消失、回復は再取り込み) を分離。
- 2026-09-18: Re-entry revision 4 (4th review 高×1・中×2対応)。**(1) strategy変更とimport continuationの相互排他** (高): idle CTAで `run.start` がsettle前にrunをactive化するため、continuing中のstrategy選択変更が既存 `dismiss()` → `start(trigger)` pathでvalidated intentを持たない別runへ差し替え得る事実を踏まえ、(a) idle entryも `continuing` 中はstrategy picker無効化 (「idle pickerは常に操作可」scenarioを修正)、(b) UI非依存の **commit時gate** — CTA前に開始されたstrategy書込がcontinuation中に遅延commitしてもrun差し替えを発火させない (commit自体は維持)、(c) **`strategyWriteInFlight` 中はCTA開始を拒否** — を競合affordance scenario・CTA scenario・Data and state・AC-3/AC-5へ追加。defense-in-depthとして成功settle時の `Started.runId` 一致確認を追加。**(2) seam例外契約の精緻化** (中): 無条件のCE再throwはseam内部発CEで `continuing = true` のまま取り残され得るため、「呼出coroutine自体のcancel (`ensureActive()` 相当) なら再伝播、生存contextなら **CEを含む全例外** をattempt-bound failure settleへ変換」へ修正。scope自体のcancelはUI settleを要求しない。AC-3へCE (scope生存) testを追加。**(3) global preferenceのplanner-effective表示** (中): plannerが消費する `globalPreference?.minimizeMovement` (`globalMinimizeMovement`) をsummaryへ追加 — `true` で「全体方針: 現状の配置をなるべく維持する」行を表示、`false`/`null` は行なし。global-only intentが「希望0件」に見える問題を解消し、表示とplanner projectionの一致をcontract test化 (AC-4)。summary純粋関数の入力を `completed` + 当該boolean 1値へ明示。
- 2026-09-18: Re-entry revision 5 (5th review 高×1・中×1・低×1対応)。**(1) strategy書込との直列化をrun内entryのattempt全期間へ拡張** (高): commit時gateの抑止条件を「import continuation中 (両entry)」に加えて「run内entryのimport attempt生存中の全期間」へ拡張 — import開始前に始まったstrategy書込が成功状態表示中 (`continuing=false` を含む) にcommitしてowning runを差し替え、CTAが別runへintentをattachする競合を閉じる。加えて (i) run内attempt contextがowning runIdを保持し `attachIntent` 呼出直前に再照合 (不一致は `attachIntent` 非呼出のtyped failure settle)、(ii) `onStrategySelected` への書込開始gate (run内attempt中/idle continuation中は新しい書込を開始しない — recompose前queued click対策)。AC-3のtest oracleを (e)〜(h) へ再編 (旧(e)は `strategyWriteInFlight` CTA拒否と矛盾していたため分離)。**(2) Scopeのseam例外文言を確定契約へ統一** (中): 「CE再throw/それ以外failure settle」の旧要約をrevision 4の確定契約 (`ensureActive()` 相当でcancel判定、cancel済みなら再伝播、active contextならCEを含む全例外をfailure settle) へ統一。**(3) Change set (plan) のsummary導入函数入力表記をDesign/AC-4と統一** (低)。
- 2026-09-18: Re-entry revision 6 (6th review 高×1・中×1・低×1対応)。**(1) strategy書込のrestart判定とimport開始の原子的直列化** (高): 現行 `execute` (`scope.launch` + `withContext(IO)`) ではcommit後のrestart判定・実行もIO上で動き、gate読取とrestart実行の間にMain-confinedなimport開始が割り込むと、attemptが内部競合で黙ってdropされる競合窓が残る (「gate通過後にrunを差し替えられる」)。`strategyWriteInFlight` のlifetimeを **restart判定完了まで** へ延長し、restart判定・実行を **Main-confined直列化点** へ移すことで gate読取→dismiss/start を原子的に閉じ、import開始側も同一点を通り `strategyWriteInFlight` 中は **新規import開始自体をtypedに拒否** する (pre-existing書込とimportの重なりを起点で排除)。CTA scenario・競合affordance scenario・Data and state・AC-3 (d2)(d3)・AC-5へ反映。**(2) run state不変の適用範囲を限定** (中): 「import attempt生存中ずっとrun state不変」はidle CTAの `continuing` 契約 (seamがsettle前にrun stateを進める) と矛盾するため、「validation開始〜 `ImportSuccess` 表示中の `!continuing` まで」に限定し、CTA開始後は「別操作による差し替えは禁止」へ書き分け。AC-1へ「CTA押下前」を明記。**(3) Scopeのsummary要約をglobal preference表示と統一** (低)。
- 2026-09-18: Re-entry revision 7 (7th review 高×2・低×1対応)。**(1) restart実行のIO分離 (reservation方式)** (高): revision 6の「Main-confined直列化点上で dismiss/start まで原子的に」は同期heavyな `run.start` をMainで実行することになるため、「Main上ではrestart可否の判定とreservation取得までを原子的に実行し、`dismiss()` → `start(trigger)` の実行はIO上で行う」へ変更。reservation中・IO実行中はimport/CTAともtyped拒否、reservation/in-flightはrestart成功・非commit・`start()` throw・coroutine cancelの全終端で解除 (`finally` 相当)。AC-3 (d4)・AC-5へ解除test追加。**(2) AC-3(e)等のoracle再編** (高): revision 6の確定契約 (in-flight中はimport開始を拒否) と「W in-flight中にimport成功 → ImportSuccess(R1)」の旧oracle (e) は両立しないため、(e) を確定policyへ置換 (W開始 → import試行 → typed拒否・validation未開始 → W restart判定/完了 → 解除 → import再試行 → owning runでImportSuccess)。逆方向 (import attempt成立後の書込開始gate) は既存のとおり。(d2) も「どちらか」ではなく確定policyの期待結果へ統一。**(3) 「summaryは件数のみ」の残存表記を統一** (低): summary scenario冒頭を「item-levelでは件数のみ + planner-effectiveな全体方針行」へ更新 (plan側の「data class: 件数のみ」も同様に統一)。
- 2026-09-18: Re-entry revision 8 (8th review 高×1・中×2対応)。**(1) 旧「W in-flight中にimport成功」oracleの残存除去** (高): AC-5本文・Test oracle AC-3 (併記されていた旧(e))・Test oracle AC-5から旧sequenceを全て削除し、確定policyのsequence (W開始 → import試行 → typed拒否・validation未開始 → W restart判定/IO restart完了 → 解除 → import再試行 → owning runでImportSuccess) へ一本化。**(2) arbiter状態機械の一本化** (中): `strategyWriteInFlight` のlifetime二重定義を解消し、`Idle → Writing → RestartReserved/Restarting → Idle` の単一状態機械として定義 (`Writing` = select呼出〜commit/no-op判定、`RestartReserved` = Main-confined直列化点上でrestart権取得 (原子的)、`Restarting` = dismiss/startをIO実行)。全状態でimport/CTA開始をtyped拒否。AC-3 (d2) を「排他の2方向」(W先行 → import拒否 / import先行 → 書込開始gate) へ書き換え、判定 vs import開始の両方向競合oracleは削除。**(3) non-commit終端の解除をoracleへ追加** (中): AC-3 (d4)/AC-5の解除testをtable-driven (restart成功 / 非commit / `start()` throw / cancel) へ拡張。
- 2026-09-18: Re-entry revision 9 (9th review 高×1・中×2対応)。**(1) strategy write同士のsingle-flight** (高): `onStrategySelected` にrepeat-tapで `store.select` を連続起動できる既存性 (`selectedStrategy` 更新はcommit後) を踏まえ、Main-confined直列化点上で **arbiterが `Idle` のときのみ `Idle → Writing` を取得可** と固定。non-idle中の追加strategy選択はstore呼出自体を開始しない (V1ではqueueせず拒否/no-op)。UIもarbiter non-idle中はpickerをdisabled化。AC-3 (i) へW1停止中の2本目開始不発火・W1終端後にIdle復帰・release誤解放なしのtestを追加。**(2) normative guard名の統一** (中): 旧 `strategyWriteInFlight` (lifetimeがcanonicalより短い) を本文から撤去し、**`strategyArbiterBusy := arbiterState != Idle`** へ統一 (import開始・CTA・UI disabled・oracleの全てがこのpredicateを参照)。AC-3 (f) を「arbiter non-idle中のCTA拒否」へ置換し `RestartReserved`/`Restarting` 各状態でのCTA不開始を明示。**(3) 書込開始gateのentry別policy明記 + Committed-no-restart終端** (中): run内 = `importAttemptActive` 中はW開始拒否 / idle = `ImportSuccess && !continuing` 中の書込は許可 (正規経路) / idle continuation = `importContinuationActive` 中は拒否、を明文化。state遷移へ **`Writing → Idle` (Committedだがrestart不要/抑止)** を追加し、「idle ImportSuccess → strategy選択 → Committed → 非発火 → Idle復帰 → CTA再試行成功」を1本のtestで固定 (AC-3 (d2)(ii)/AC-5)。

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
- Issue #327 (interview-first化、**implemented** — capability説明を追加するがvalidation通過後のflowは不変)
- [Owner decision D-2/D-3/Q3](https://github.com/nunu1733/NunuLauncher/issues/328#issuecomment-5723309396) (2026-09-18)
- [requirements.md](../../docs/product/requirements.md) (FR-017), [AGENTS.md](../../AGENTS.md), [DESIGN.md](../../DESIGN.md), [CONTEXT.md](../../CONTEXT.md)
