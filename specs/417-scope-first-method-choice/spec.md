---
issue: "#417"
status: draft
requirements: [FR-006, FR-017, NFR-009]
risk: []
updated: 2026-09-24
---

# Organizerの対象scope確定を方法選択より先に行い、AI/非AIを同じ凍結scope上の兄弟分岐へ一本化する

> 契約の根拠: [Issue #417][1]（問題・Outcome・Required design・受入条件の正本）。
> 改訂対象の既存decision: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-04, D-05, D-17, §5.1 T-07/T-08, §5.2 選択面からのrun-in AI相談, §5.3 canonical順序）、
> accepted disposition [docs/product/organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)
> （§5 supersession mapへの#417行追加）。
> 本specが改訂を要求する既存spec: [spec 369](../369-run-display-integration/spec.md)（canonical順序の文言）,
> [spec 372](../372-ai-consultation-request-flow/spec.md)（T-07二択・idle相談flowの入口）,
> [spec 331](../331-exchange-target-scope-coupling/spec.md) §5（idle entry / run-in entryの二入口構造。
> scope binding gateの契約は不変）,
> [spec 367](../367-organizer-materials-relocation/spec.md)（維持されるsecondary entryの記述）。
> 維持する契約（1行も緩めない）: [spec 228](../228-organizer-missing-app-selection/spec.md) D-1,
> [spec 331](../331-exchange-target-scope-coupling/spec.md) D-2/D-4/D-5,
> [spec 348](../348-exchange-ai-facing-contract/spec.md), [spec 327](../327-agent-exchange-interview-first/spec.md),
> [spec 374](../374-durable-imported-intent/spec.md), [spec 375](../375-scope-remedy-rebind/spec.md)。
> 本specは[Issue #417][1]の成果物である。

## Problem

現行Organizerでは、AI/非AIの方法選択（T-07前置き面）が候補検出・対象選択より先に現れる。このため:

- 未配置アプリをAIの依頼へ含めるには、ユーザーは先に「そのまま整理」でrunへ入り、選択面を経てからrun-in AI相談を開く必要がある。方法を選ぶ前にscopeの形成を強制される。
- 同じ「AIに相談」に、scope確定前のidle相談（配置済みのみexport）とscope確定後のrun-in相談（選択済み候補をexport）の2つの入口が併存し、AIが見る対象集合が入口timingで変わる。
- 選択面の編集可否が、別state machineの隠れ条件（Exchange画面が`Closed`でないこと）だけで凍結され、「なぜ編集できないか」が画面上の作業状態から説明できない。フィールド観測では、候補が見えているのに個別チェック/すべて選択が無効な中間状態が発生した。
- run-in AI入口は選択0件をgateしておらず、未確定の選択のままAI相談へ入れ、その場で選択全体が凍結される。

原因は文言ではなく、scope形成・方法選択・RUN lease・Exchange state・selection freezeが別々のstate machineとして同一surfaceで交差する構造にある。

## Outcome

1回の整理について、**対象scopeを先に確定し、その同一scopeに対してAIを使うか使わないかを選ぶ**単一フローになる。ユーザーは候補検出→対象選択→scope確定を終えてから「このまま整理 / AIに相談」を選び、どちらの肢も同じ凍結scopeを消費する。AIだけが別のscope形成timingを持つことはなく、選択の凍結は「AI依頼が確定scopeを参照している間」というユーザー可視の理由で説明される。

## Scope

- 正規journeyの順序変更: 入口（方法選択を含まない）→ run admission → 候補検出 → 対象選択（候補あり時）→ **scope確定（凍結）** → **方法選択面「整理案の作り方」** → 同一scopeでcapture/plan。
- 方法選択面の新設: 「このまま整理」（deterministic planner）と「AIに相談」（scope凍結後の依頼作成〜取り込み）を兄弟分岐として同一面に提示する。既存run-in scoped exchange flow（T-15〜T-18の契約）を、この面から開く形へ再配置する。
- idle AI相談（run外・pre-run request）の新規作成入口を廃止する（Retire）。既存のdurable依頼・取り込み済み提案のimport・再開契約は #374/#375 のまま維持する。
- 候補0件時: 対象選択面を表示せず方法選択面へ進む（既存D-06 pass-throughの到達点をplanning直接から方法選択面へ変更）。0件でもAIに相談できる（export subjectsは配置済みのみ）。
- 選択面上で0件のまま続行することを明示操作とし、「未配置アプリを追加せず整理する」旨を提示する。
- 選択編集の凍結条件を「AI依頼が確定scopeを参照している間」に限定し、凍結理由をUI上に表示する。依頼が存在する状態で対象選択へ戻る場合は、D-13の「破棄」確認を経る。
- Home変化に対するfreshness: 既存のcomposition時fail-closed検証（`CANDIDATE_SELECTION_STALE` / preview stale）とtyped再試行案内を正規の挙動として固定し、検出cutより古いcaptureからscopeを確定させない。
- 上記に伴う `docs/product/organizer-to-be-ux.md`・`docs/product/organizer-disposition-migration.md`・spec 369/372/331/367の改訂（production変更より先に同一PR内で適用する）。

## Non-goals

- AI output schema / validator / framing / instruction契約の変更（spec 204/205/327/329/348が所有）。
- 配置済みitemの部分選択・除外（対象scopeの配置済み部分は常に全体。選択できるのは未配置候補のみ、spec 228どおり）。
- 候補検出アルゴリズム・detection cutの取得方法の変更（admission時のfresh captureを維持）。
- scope binding gate（完全一致・candidate projection digest・typed `SCOPE_MISMATCH`・zero-write）の緩和（spec 331 D-2/D-4/D-5、#375 amendment不変）。
- durable pending intent store / rebind契約の変更（spec 374/375不変）。
- External Agentのinterview質問内容の固定。
- preview / explicit confirmation / transactional apply safetyの省略。
- 旧idle入口で作成済みの依頼・提案の無効化（読み取り互換を維持する）。
- 今回観測された1回限りのImport失敗の原因断定（Issue本文のNotesどおりfailure classは未確定）。

## Disposition of existing decisions

Issue #417受入条件「idle AI / run-in AIの二経路についてContinue / Amend-Supersede / Retireのdispositionが明記される」に対する決定:

| 対象 | Disposition | 内容 |
|---|---|---|
| idle AI相談（pre-run request flow、D-04/D-17前段） | **Retire（新規作成入口）** | 同じ「AIに相談」に2つのscope形成timingが併存することが問題の根拠であり、Outcome「AIだけ別のscope形成タイミングを持たせない」に直接反するため。代替案B（idle AIを「追加アプリを含めない別機能」として残す）は却下した。 |
| run-in scoped AI相談（T-08選択面のscope凍結entry） | **Amend（正規AI pathへ一般化）** | 「凍結scopeをexportする」契約（spec 331 §5）を、選択面から方法選択面へ移した上でAI pathの唯一の入口とする。 |
| 検出→選択→凍結→AI相談の順序（代替案Cの実質） | **採用（run内で完結）** | 検出・選択をrun外に二重化する代わりに、既存run state machine内（admission→検出→選択→凍結）で実現する。 |
| durable依頼・取り込み済み提案・rebind（spec 374/375） | **Continue** | `Hub → ImportReview` 1経路・依頼時scope完全一致・選択復元初期値＋明示確認を含め不変。`entryKind` は型ごと維持し、新規生成は `RUN_IN` のみ（`IDLE` は既存recordの読み取り互換のため残す）。 |
| T-07前置き面の方法選択（spec 369/372） | **Amend-Supersede** | 前置き面は方法選択を含まない入口面となり、方法選択はscope確定後の方法選択面へ移る。spec 372 EX-AC-01の「T-07に方法選択が現れる」部分を本specが置換する。 |
| 選択面からのrun-in AI相談entry（TO-BE §5.2 secondary entry 3、spec 367） | **Supersede** | 選択面（編集中）にAI相談entryは現れなくなる。AI相談は凍結後の方法選択面から開く。 |

## Domain language

承認時に `CONTEXT.md` へ反映する用語:

- **対象scope凍結 (Frozen Organization Scope)**: 1回の整理runについて、方法選択より前にユーザーが明示確定した対象集合。配置済み対象（常に全体）と、選択済み未配置候補（0件以上）からなる。確定後はAI export・deterministic planner・import検証のすべてがこの同一scopeを参照する。
- **方法選択面 (method choice)**: scope凍結後に現れる「このまま整理 / AIに相談」の選択面。旧T-07前置き面の方法選択（spec 369/372）はここへ移る。

## Behavior scenarios

### Scenario: 候補あり → 選択 → scope確定 → このまま整理

Given 候補が3件検出された状態でrunが開始され、対象選択面が表示されている
When ユーザーが1件を選択して「続行」し、方法選択面で「このまま整理」を選ぶ
Then capture/planは選択済み1件をadditionsに含むscopeで実行され、previewにその1件が現れる
And 選択面で選ばなかった2件はplanに現れない

### Scenario: 候補あり → 選択 → scope確定 → AIに相談

Given 同じく1件を選択してscopeを確定し、方法選択面が表示されている
When ユーザーが「AIに相談」を選び、依頼を作成して外部AIの回答を取り込み、提案で続行する
Then export文書のCANDIDATE subjectsは選択済み1件と一致し、import後のplanningも同じ1件をadditionsとして消費する
And 依頼作成から取り込み完了まで、対象選択面へ戻る導線は「破棄」確認（D-13）を経由するものであり、選択を黙って変更しない

### Scenario: 候補0件 → 選択面を挟まず方法選択

Given 候補が0件の状態でrunが開始された
When 検出が完了する
Then 対象選択面は表示されず、方法選択面へ進む
And 「AIに相談」を選ぶとexport subjectsは配置済み対象のみを含む（候補subjectは0件）

### Scenario: 選択面上で0件のまま続行

Given 候補が3件表示され、1件も選択していない
When ユーザーが「続行」を選ぶ
Then 「未配置アプリを追加せず整理する」旨が提示された上でscopeが確定し、planningのadditionsは空になる
And これは暗黙の未確定状態ではなく、明示的な0件選択として扱われる

### Scenario: AI依頼が参照するscopeの凍結と理由表示

Given 方法選択面からAI依頼が作成済みである
When ユーザーが対象選択面へ戻ろうとする（system Back）
Then 「依頼を破棄するか」の確認（D-13）が表示される
And 破棄を承認した場合のみ選択面が編集可能な状態で再表示され、依頼は #204 のsession置換規則どおり無効化される

### Scenario: 依頼なしの方法選択面からのBack

Given scope確定後の方法選択面にAI依頼が存在しない
When ユーザーがsystem Backで戻る
Then 対象選択面が編集可能な状態で再表示される（zero-write、選択内容は保持される）

### Scenario: 取り込み後のplanning段階でのscope不一致

Given 方法選択面から作成した依頼の回答が検証を通過した
When 提案で続行してplanning段階のscope binding gateが評価される
Then projection差（availability/分類のdrift）はtyped `SCOPE_MISMATCH` としてzero-write失敗し、依頼の作り直しへ案内される
And 選択集合の差による `SET_MISMATCH` は、依頼が凍結scopeから作られるこの経路では発生しない

### Scenario: Home変化後のstale候補

Given 対象選択面で1件を選択して確定した後、Homeが変更され当該候補のavailabilityが変化した
When capture/compositionが実行される
Then 既存のfail-closed検証（`CANDIDATE_SELECTION_STALE` / preview stale）が作動し、zero-writeのtyped失敗と再試行（整理のやり直し）案内が返る
And 古いcaptureから確定したscopeでplanは作られない

### Scenario: 既存（legacy）のidle由来依頼の取り込み

Given 旧版で作成されたidle依頼（配置済みのみのexport scope）がdurableに残っている
When hub status cardから依頼を開き回答を取り込み、提案で続行する
Then #375 の再開契約どおり fresh run admission → 検出 → 選択面（unchecked初期値・件数案内）→ 確認時の完全一致検証を経てplanningへ進む
And 依頼時scopeに候補が含まれないため、候補を選択した状態の確認は `SET_MISMATCH` としてzero-write失敗し、選択修正による継続が案内される

### Scenario: process死後の再開

Given 方法選択面から依頼を作成した後、processが死んだ
When ユーザーが `Hub → ImportReview` から提案を再開する
Then #375 のrebind契約（RUN_IN由来の選択復元初期値＋明示確認1回）でfresh runがadmissionされ、依頼時scopeとの完全一致検証を経てplanningへ進む
And 方法選択面は再表示されない（方法は既に確定済みであり、intentがboundされたrunは確認へ直行する）

## Data and state

- 読むdataと正本: 候補検出cut（admission時のfresh capture、`MissingAppCandidateSource`）、export session（durable、24h）、durable pending intent（durable、依頼と同一TTL）。いずれも既存契約から変更しない。
- 永続化するdata: **新設しない**。選択状態・確定scopeはprocess-localなrun stateのままである（spec 228継続）。`entryKind`（IDLE/RUN_IN）は既存durable recordの読み取り互換のため型ごと維持するが、新規に作成される依頼はすべて `RUN_IN` である。
- migration、backup/restore、rollbackへの影響: なし（persistent format・DB書込み経路の変更はない。本specの全経路はzero-writeである）。
- layoutを扱う場合の扱い: 対象scopeは「配置済み全対象＋選択済み未配置候補」。配置済みの部分選択は存在しない（spec 228 D-1: 全候補未選択デフォルト・明示選択のみ追加対象）。

## Permissions, privacy, and security

- None。新規permission、新規外部送信経路、sensitive dataの追加はない。External Agent Exchangeのprivacy契約（送信前確認・privacy tier・export-scoped ref）はspec 205/331のまま不変である。

## Accessibility and localization

- 方法選択面・凍結理由・破棄確認はTalkBackで理解できること（label、focus順、状態変化のlive region告知）。既存のscope mismatch行のassertive live regionの方針を凍結理由表示へも適用する。
- キーボード/switch accessで入口→選択→確定→方法選択→各肢へ到達できること。
- 200% font scaleで方法選択面・選択面・凍結理由が崩れないこと。
- 新設・変更する文言はEN/ja両方を同時に提供する。

## Acceptance criteria

- [ ] AC-1: 候補あり時、1回の整理が「開始→検出→対象選択→scope確定→方法選択→planning」の正規順序で進み、方法選択面はscope確定後にのみ現れる。方法選択の両肢は同じ確定scope（選択済み候補を含む）を消費する。
- [ ] AC-2: 選択済みmissing appsは、AI exportのCANDIDATE subjectsとdeterministic plannerのadditionsに同一集合として現れる（同一の確定scopeから組成される）。
- [ ] AC-3: 候補0件時、対象選択面を表示せず方法選択面へ進み、AIに相談できる（export subjectsは配置済みのみ）。
- [ ] AC-4: 選択面上の0件続行は「未配置アプリを追加せず整理する」旨の明示を伴い、暗黙の未確定状態と区別される。
- [ ] AC-5: 選択コントロールが編集不可になるのは「AI依頼が確定scopeを参照している間」のみであり、その理由（依頼の存在と状態）がUI上に表示される。Exchange内部stateのみを理由とする不可解な無効化は発生しない。依頼が存在しない選択面では、編集は常に可能である。
- [ ] AC-6: run外のidle依頼の新規作成入口は撤去され、新規に作成される依頼はすべてscope確定後のrun内（`entryKind=RUN_IN`）で作られる。既存のdurable依頼・取り込み済み提案は #374/#375 契約（`Hub → ImportReview` 1経路、IDLE由来のunchecked復元を含む）どおりimport・再開できる。
- [ ] AC-7: 検出cutより後のHome変化により候補がstaleになった場合、composition時のfail-closed検証が作動し、zero-writeのtyped失敗と再試行案内が返る。古いcaptureから確定したscopeでplanは作られない。
- [ ] AC-8: instrumentationで次のjourneyが固定される: (a) empty Home → 全候補選択 → AI依頼 → import → preview、(b) empty Home → 全候補選択 → このまま整理 → preview、(c) AI依頼存在下のBack/選択面への遷移で、編集可否と理由が意図どおりに変化する、(d) Back/中断/process recreation後にscope ownership（どのscopeがどの依頼・runに紐づくか）が曖昧にならない。
- [ ] AC-9: TalkBack / keyboard / 200% font scaleで、scope確定・方法選択・凍結理由・次操作が理解できる。
- [ ] AC-10: `docs/product/organizer-to-be-ux.md` へのrevision追記（D-04/D-05/D-17/§5.1/§5.2/§5.3の改訂）、`docs/product/organizer-disposition-migration.md` のsupersession mapへの#417行追記、spec 369/372/331/367の該当規定へのAmend/Supersede標記が、production変更のcommitより先に同一PR内で適用される。
- [ ] AC-11: #331 scope binding gate（完全一致・candidate projection digest・typed `SCOPE_MISMATCH`・zero-write）、spec 348/327のinstruction・interview契約、spec 374/375のdurable・rebind契約、spec 228 D-1の既存test回帰がすべて維持される。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | unit: run state遷移（確認→方法選択面→各肢）。instrumentation: 正規journeyの順序assert |
| AC-2 | unit: export組成とplanner入力が同一scopeから組まれることのcontract test。instrumentation: (a)(b) journey |
| AC-3 | instrumentation: 0候補で方法選択面へ進むことの既存pass-through testの改訂版 |
| AC-4 | unit + instrumentation: 0件続行の表示と空additions組成 |
| AC-5 | unit: 凍結条件（依頼存在との対応）。instrumentation: (c) journey |
| AC-6 | unit: `entryKind` 生成と入口撤去（idle row不在）。instrumentation: hub経由のlegacy import |
| AC-7 | unit: composition時stale検出の既存契約 + instrumentation: Home変化後のtyped再試行 |
| AC-8 | instrumentation: (a)〜(d) |
| AC-9 | instrumentation: TalkBack label/フォーカス、200% font scaleのscreenshot assert |
| AC-10 | PR diffのcommit順序（docs commitがproduction commitより先）とreview確認 |
| AC-11 | 既存unit/instrumentation回帰（scope binding gate、durable import、rebind、selection既定値）が全绿色であること |

organizer JVM gate（`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`）とfocused connected test laneを必須evidenceとする（quality-strategyどおり、instrumentationはJVM gateの代替ではない）。

## Open questions

（なし。`accepted` 時点で空。文言の最終copyは実装PRで確定するが、挙動は本specのscenarioに固定済みである。）

## Change history

- 2026-09-24: Draft created for #417.
