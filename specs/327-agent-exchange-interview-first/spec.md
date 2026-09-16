---
issue: "#327"
status: draft
requirements: [FR-017]
risk:
  - privacy
updated: 2026-09-16
---

# External Agent Exchangeをinterview-firstにし、AIでできることを明示する

> Status: **draft** (2026-09-16) — baseline `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b` (origin/main) 上で起草。前提となる #205 (implemented) と #331 (implemented) のexchange実装・#204 (accepted・実装済み) のintent契約は現mainに存在する。本specは #205 specのexchange framing / session / transport / import契約を変更せず、(1) exchange packageのinstruction部の内容 (interview-firstな2-phase構成への変更) と (2) アプリ内のuser向けcapability説明、を #327側の契約として定義する。

## Problem

#205で実装済みのExternal Agent Exchangeのinstruction部 (現 `ExchangePackageComposer` の `INSTRUCTION_HEADER` / `INSTRUCTION_FOOTER`) は、外部AIに「Ask the user clarifying questions if the request is ambiguous」(曖昧な場合に限る質問の**許可**) しか伝えず、Response formatは初回応答から `PersonalizedIntentV2` を返してよいかのように書かれている。

その結果、ChatGPT / Gemini等はexportされた情報だけで十分と判断して即座にIntentを返しやすく、外部agentic環境を使う本来の価値であるユーザーへのヒアリング (利用傾向・優先事項の確認) が発生しにくい。

またアプリ内の説明 (現string: 「ChatGPT等の外部AIへの依頼文を作成し、回答を検証付きで取り込めます。」) は、ユーザー視点で「AIで何ができるのか」「AIがホーム画面を直接配置するのか」「どんな希望を伝えられるのか」を伝えていない。priority / grouping / keep-position / page preference等のschema用語では、ユーザーは伝えられる希望の範囲を理解できない。

## Outcome

External Agent Exchangeのexchange package instructionとアプリ内説明が、**AIと相談して整理方針を作る** guided interview workflowになる。AIは最初の応答でIntentを生成せず、2〜4問のboundedなヒアリングを行い、理解した整理方針を要約し、ユーザーの確認後にのみ `PersonalizedIntentV2` を生成する。アプリは「AIでできること」をschema用語でなく具体例で説明し、AIがホーム画面を直接変更しない (再整理に使う希望・傾向を作る) ことを明示する。最終生成では、AIが推測で独自形式を作らないようcanonical JSON example/templateをpackageへ含める。

## Scope

- exchange package **instruction部の内容契約** (英語固定・static text。#205 Decision 2/4の継承):
  - **Phase 1 (interview)**: 最初の応答ではIntent marker blockを含んではならない。export内容だけで判断可能に見えても、原則として短いヒアリングを行う。質問は2〜4問、スマホで回答しやすい短さにboundedする。質問主题の例 (よく使う/すぐ開きたいアプリ、仕事/私用等のまとまり、動かしたくないもの、前ページ/片手操作等の好み) を指示に含める。質問内容の完全固定はしない (Issue non-goal)。
  - **整理方針の要約**: ヒアリング後、AIが理解した整理方針を短く要約してユーザーに提示する。
  - **ユーザー確認**: ユーザーの了承後にのみ最終Intentを生成する。
  - **Phase 2 (final answer)**: 既存のResponse format契約 (INTENT marker行、`personalized-intent-v2`、coverage、exportId echo、FIXED/CANDIDATEの扱い) を維持したまま、**canonical JSON example/template** をinstruction部へ含める。
  - **provider中立性**: 特定provider (ChatGPT/Gemini) 固有の機能 (function calling、system role、provider memory、UI automation等) に依存しない、一般的なmulti-turn chatで成立する指示であること。
- **canonical example/templateの構造契約**: accepted schema/version (`personalized-intent-v2`) に対応する具体例。example内のID・値は実データとして誤認しない構造 (placeholder) とする。
- **アプリ内capability説明 (UI copy)**: exchange導線 (idle entry・#331 run-in entry) に、schema用語でなく具体例での「AIでできること」、および「AIはホーム画面を直接変更しない」旨を表示する。送信後にAIが質問してくること (期待される会話flow) の説明を含む。
- 上記のaccessibility (TalkBack / Switch Access / large font) とja/en strings (spec 123契約)。
- representativeなChatGPT/Gemini等でのdevice evidence (prompt受領 → 質問 → 確認 → Intent生成)。

## Non-goals

- Launcher内でのLLM実行、AIによるlayoutの直接apply (既存non-goalの継承)。
- chain-of-thought取得・保存。
- #204 validator・import strictnessの緩和 (#329 Import Normalizerが別所有)。interview-firstはinstruction (prompt) 契約であり、launcher側の新しい検証layerではない。
- AI側の質問内容・数の完全固定 (boundedな指針の提示のみ)。
- import入力UI (clipboard/file-first化) の変更 (#332が別所有)。
- import成功後の状態・次操作の表示設計 (#328が別所有)。
- `PersonalizedIntent` schema自体・authoring契約 (coverage、FIXED item扱い等) の変更 (#330が別所有)。本specのexampleは現行accepted schema (`personalized-intent-v2`) に対して定義する。
- exchange framing (marker形式)、envelope上限、session、transport、privacy tier、送信前確認の順序契約の変更 (#205/#331所有の契約は不変)。
- アプリ内での会話 (ヒアリング) 画面の提供。ヒアリングは外部AIアプリ内で行われる。

## Domain language

`CONTEXT.md` への追加用語案 (受入時に反映)。

**整理方針確認 (policy confirmation)**:
interview-firstなExternal Agent Exchangeにおいて、外部AIがヒアリング結果から理解した整理方針を短く要約してユーザーに提示し、了承を得るstep。了承 (またはユーザーによる明示的なskip) 後にのみ最終 `PersonalizedIntentV2` の生成が行われる。launcher側はこの会話順序を検証しない (安全性はframing抽出・#204 validator・preview/confirmが所有する)。
_Avoid_: 承認画面 (launcher内UIとの混同。確認は外部AIアプリ内で行われる)、system prompt

## Behavior scenarios

### Scenario: package生成とinterview-first instruction

Given manual organization surface (idle) または #331 の選択surface (run-in) からexchange生成が実行される、
When exchange packageが生成される、
Then instruction部は **2-phase構成** (Phase 1: interview、Phase 2: final answer) を明示し、(a) 最初の応答ではIntent marker blockを含んではならない旨、(b) export内容だけで判断可能に見えても原則として短いヒアリングを行う旨、(c) 質問は2〜4問でスマホで回答しやすい短さにboundedする旨、(d) ヒアリング後に理解した整理方針を短く要約しユーザーの了承後にのみ最終Intentを返す旨、を含む、
And 既存の遵守事項 (CONTEXT dataの `ref` のみ使用、`FIXED` itemの扱い、`CANDIDATE` subjectの扱い、widget span・座標・DB変更の禁止、coverage、`exportId` echo) は #205/#331実装時から意味を変えずに保持される、
And data部 (CONTEXT marker行で囲まれたcanonical JSON単行) とinstruction部の機械分離 (AC-1相当、#205) は不変であり、`parsePackageStructure` が引き続き成立する。

### Scenario: representativeな外部AIとの往復 (ChatGPT / Gemini)

Given ユーザーがpackageを外部AIへ送信する、
When AIが最初の応答を返す、
Then AIはIntentを返さず、2〜4問程度の短い質問 (よく使うアプリ、まとまり、動かしたくないもの、前ページ/片手操作等の好み) をユーザーに投げる、
And ユーザーの回答後、AIは理解した整理方針を短く要約して確認を求め、
And ユーザーが了承した場合のみ、INTENT marker行で囲んだ `personalized-intent-v2` JSONを返す、
And その返答をimportすると既存framing抽出・#204検証・preview/confirm pathを通る (本flowの観測はdevice evidenceが担う)。

### Scenario: ユーザーが「質問不要 / このまま生成」と明示した場合

Given ユーザーがAIとの会話で「質問は不要、このまま生成してほしい」と明示した、
When AIが応答する、
Then AIは追加の質問をせず、**自らの想定する整理方針を短く要約した文章と、最終Intent (INTENT marker行で囲んだJSON) を同一返答内**に返す、
And launcher側は会話上のskipがあったかを検出・検証しない (importは従来どおりframing抽出と#204検証のみで成立する)、
And ユーザーがskipせず通常手順を辿った場合と、import以降のlauncher側の挙動に差はない。

### Scenario: AIがinstructionに従わず初回応答でIntentを返した場合

Given AIがinterview-first指示に従わず、質問なしで最終Intentを返した、
When ユーザーがその返答をimportする、
Then launcherは会話flowの不備を理由にrejectしない (検出不可能であり、安全性の正本はframing/schema/allow-list検証にある)。framing・schemaが正当なら従来どおりvalidated intentとして扱われ、違反なら従来のtyped失敗となる。

### Scenario: canonical example/templateの同梱

Given exchange packageが生成される、
When instruction部のResponse format (Phase 2) 節を確認する、
Then accepted schema (`personalized-intent-v2`) に対応する具体的なJSON example/templateが含まれ、
And example内の `exportId`・`ref` 等のID値は**実データとして誤認しないplaceholder**であり、生成されたpackageの実際の `exportId`・`ref` と一致しない、
And exampleはINTENT marker行で囲まれていない (AIがexampleをそのままmarker付きでechoして `FRAMING_AMBIGUOUS` を起こす表面を広げない)、
And exampleをplaceholderから有効値へ置換した場合、#204 `IntentCodec.decode` の閉schema (allow-list key) に合格する。

### Scenario: AIがexampleをverbatimでechoした場合

Given AIがexampleを独自の判断で置き換えず、example値のまま最終回答を返した、
When ユーザーがそれをimportする、
Then 実際のexportとexampleのID不一致により既存typed失敗 (`EXPORT_MISMATCH` / `UNKNOWN_REF` 等) としてzero-write rejectされ、失敗説明が再依頼を案内する (既存UX。新規失敗classは追加しない)、
And exampleはplaceholder構造と「値を自分の判断に置き換えること」の指示により、この経路が発生しにくい設計である。

### Scenario: アプリ内capability説明 (idle entry / run-in entry)

Given ユーザーがexchange導線を表示する (manual run非active時、または #331 選択surface内)、
When 導線の説明文を読む、
Then 「AIでできること」がschema用語 (priority / grouping / page affinity等) を使わず具体例 (よく使うアプリを優先する、関連するアプリをまとめる、動かしたくないアプリを維持する、重要なものを前のページに寄せる、今の配置をできるだけ崩さない等) で示され、
And 「AIがホーム画面を直接変更するのではなく、再整理に使う希望・傾向を作る」旨が明示され、
And 送信後にAIが数問の質問 → 整理方針の確認 → 最終案生成という会話flowが想定される旨が説明され、
And 説明・CTAはTalkBack読み上げ・large fontで利用できる。

### Scenario: provider中立性の維持

Given exchange packageが任意のmulti-turn chat環境 (ChatGPT / Gemini / その他) へ渡される、
When instruction部を読む、
Then 特定provider固有の機能 (function calling、tool schema、system role、provider memory、UI automation) を要求する文言は存在せず、平文の複数往復会話のみで指示が完結する、
And アプリ内説明は具体provider名を例示でき (既存copyと同様)、特定providerを必須とする意味の文言を含まない。

## Decisions (このdraftで提案する決定 — owner acceptance待ち)

1. **skip宣言の取り扱い (Issueがspec決定を求めた項目)**: ユーザーの「質問不要 / このまま生成」明示は**確認stepの代替**として扱う。AIは追加質問なしで、自らの想定する整理方針の短い要約と最終Intentを同一返答内に返す。理由: (a) 会話の強制はlauncher側から検証できずprompt契約でしか表現できない、(b) ユーザーの明示的意思に反して追加の往復を要求するのはUX上逆行する、(c) launcher側の安全性 (framing・validator・preview/confirm) は会話順序に依存しないため、skipを許容しても安全表面は変わらない。ヒアリングの完全省略 (要約なし即Intent) は指示上認めない — 要約はskip時も1段落として添付させる。
2. **canonical exampleの構造**: exampleはinstruction部のPhase 2 (Response format) 節にstatic textとして含める。ID値は実データと誤認できないplaceholder (例: `exportId` に「CONTEXT dataのexportIdに置き換える」ことを示す明示的な非実在値) とし、(a) example自体はINTENT marker行で囲まない (`FRAMING_AMBIGUOUS` 誘発の抑制)、(b) exampleは `IntentCodec` の閉schemaに対してcontract testで検証され、schema version変更 (#330等) とexampleの更新は同一変更で行われる (乖離防止の不変条件)、(c) 「exampleをそのままcopyせず全ての値を自分の判断で置き換える」旨を指示に含める。
3. **instruction部のstatic維持**: interview-first化に伴いinstructionを動的生成 (会話状態やscope内容への依存) にはしない。#205 Decision 2 (固定長instruction + payload上限でpackage sizeを構造的上限内に収める) を継承する。#331 run-in entryでも同一instructionを用いる (CANDIDATE subjectの扱いは既存指示が担保)。
4. **capability説明の配置**: 導線 (idle entry・run-in entry) の説明領域に具体例リスト + 「直接変更しない」明示 + 期待される会話flowの3要素を置く。送信完了status copyは期待されるflow (AIからの質問) に言及した文言へ更新する。import入力欄のUI変更は #332 へ譲る。最終的な文面 (ja正本・en) の微調整はa11y/device evidenceで行う (構造・必須要素は本specで固定)。

## Data and state

- 読むdata: なし (追加の入力sourceなし。exchange package生成は既存 #204/#331 compositionをそのまま使う)。
- exchange packageは引き続き生成時に完了するimmutableな値であり、instruction部の拡張 (interview指針・example) はstatic textとしてcomposerが埋め込む。package本文・会話内容の永続化は行わない (#205契約の継承)。
- exampleの正本はcomposer実装内の定数とし、schema version (`personalized-intent-v2`) と `ContextExportContract.INTENT_SCHEMA_VERSION` の一致をunit testが強制する。
- 新規DB書込・migrationなし。privacy tier・session・transport・import pipelineの状態は不変。
- stringsは `values/` + `values-ja/` の両方へ追加する (spec 123契約)。jaを正本とする (#205 Decision 4の継承)。

## Permissions, privacy, and security

- 追加permission・network・telemetryなし。外部送信は既存Pre-send Disclosure経由のみ。
- instruction部・exampleはstatic textであり、新たに外部へ出る情報は発生しない。exampleのplaceholder値は実ユーザーの `exportId`・`ref` と一致しない (誤送信・誤認防止)。
- ヒアリング (Phase 1) は外部AIアプリ内の会話であり、その回答はNunuLauncherを経由しない。capability説明に「相談は外部AIアプリ内で行われ、会話内容はNunuLauncherに送信されない」旨を含め、ユーザーの認識を既存privacy契約 (送信はpackageのみ) と揃える。
- 脅威モデルは #205/#204 の継承: interview-first指示に従わない (あるいは悪意ある) 返答であっても、framing抽出・#204 validator・planner制約・preview/confirmが不変であることでunsafe mutationに到達しない。interview指示はsafety mechanismではなくUX品質契約である。

## Accessibility and localization

- capability説明・flow説明・CTAはTalkBack読み上げ (意味単位の読み上げ、具体例リストの箇条書き構造)、Switch Access操作、large font (font scalingによる折返し・省略なし) に対応する。
- 説明文は既存導線と同じくlive region不要の静的textとし、状態変化のannounceは既存status行の規則に従う。
- stringsは `values/` (en) + `values-ja/` (ja正本) の両方へ配置する。

## Acceptance criteria

前提: #204 (accepted・実装済み) / #205 (implemented) / #331 (implemented) の契約・実装は現mainに存在し、本specの実装blockerではない。

- [ ] AC-1: exchange packageのinstruction部が、(a) 初回応答でIntent marker blockを含んではならない旨、(b) exportだけで判断可能に見えても原則として短いヒアリングを行う旨、(c) 質問が2〜4問でスマホで回答しやすいboundedな数である旨、(d) ヒアリング後に整理方針を短く要約しユーザーの了承後にのみ最終Intentを生成する旨、を含むことがunit testで検証される。既存の遵守事項 (ref制限、FIXED、CANDIDATE、禁止事項、coverage、exportId echo) とpackage構造 (CONTEXT marker分離・data単行) が回帰testで不変であることも検証される。
- [ ] AC-2: instruction部のPhase 2節に、accepted schema (`personalized-intent-v2`) に対応するcanonical JSON example/templateが含まれ、(a) ID値が実データとして誤認しないplaceholderであること、(b) exampleがINTENT marker行を含まないこと、(c) placeholderを有効値へ置換したexampleが `IntentCodec.decode` の閉schemaに合格すること、(d) exampleのschema versionが `ContextExportContract.INTENT_SCHEMA_VERSION` と一致することがunit testで検証される。
- [ ] AC-3: ユーザーの「質問不要/このまま生成」明示時の取り扱い (Decision 1) がinstructionに記述されていること (要約+Intentの同一返答、追加質問なし) がunit testで検証される。
- [ ] AC-4: exchange導線 (idle entry・run-in entry両方) に「AIでできること」がschema用語を使わない具体例で表示され、「AIがホーム画面を直接変更しない (再整理に使う希望・傾向を作る)」旨が明示されることがUI test (string存在・構造) で検証される。
- [ ] AC-5: 送信後の期待される会話flow (AIからの質問 → 整理方針確認 → 最終案) がユーザーに説明される (導線説明または送信完了status) ことが検証される。
- [ ] AC-6: instruction・UI copyが特定provider固有機能に依存しない (依存review: function calling / system role / UI automation等の要求文言の不在、plain multi-turn chatで成立) ことが確認される。
- [ ] AC-7: representativeなChatGPT/Gemini等を用いたdevice evidence (package送信 → AIの質問受領 → 回答 → 整理方針要約の確認 → 了承 → Intent生成 → import) がある。
- [ ] AC-8: capability説明・CTAがTalkBack / large fontで読める・操作できるa11y evidenceがある。ja/en stringsが両方存在する。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `ExchangePackageComposerTest` 拡張 (2-phase指針・bounded質問・要約と了承の各文言の存在、既存4section・marker・遵守事項の回帰、`parsePackageStructure` 往復) |
| AC-2 | composer unit test (exampleの存在、placeholder構造、INTENT marker不在、schemaVersion一致) + codec contract test (placeholder置換exampleのdecode成功・閉schema適合) |
| AC-3 | composer unit test (skip宣言の扱いの文言存在) |
| AC-4 | UI test (entry rowのtestTag配下の説明text・具体例・「直接変更しない」文言、idle/scoped両entry) + strings存在test (en/ja) |
| AC-5 | UI test (導線説明またはtransport success copyの文言) |
| AC-6 | 依存review (instruction全文・UI copyの走査) — 実装PRのreview記録 |
| AC-7 | physical-device evidence記録 (docs/assessment/ またはIssue。AC-7用の会話log要約を含む) |
| AC-8 | 手動a11y evidence (TalkBack・large font) — #205 AC-9系の後続evidence PRと合同で可 |

含める観察: unit/contract (instruction内容・example schema適合・package構造回帰)、UI (説明表示・strings両locale)、手動 (a11y、representative provider会話)。

## Open questions (non-blocking — owner review / evidence中に確定)

1. **skip時の要約添付の要否**: Decision 1は「skip時も要約1段落を添付」を含める。要約なし即Intentを許すほうがユーザーの意思に忠実という反論があり得る。owner reviewで確定する。
2. **capability説明の情報量**: 具体例リスト5項目 + 直接変更しない明示 + flow説明を導線に置くと縦に長くなる。展開形式 (常時表示 vs 折りたたみ) は実装PRのUX判断とする (必須要素は本specで固定)。
3. **instruction最終prose文言**: 構造・必須要素は本specで固定。微調整はAC-7 evidenceでagent遵守率を見て行う (#205 Open question 1の継承)。

## Change history

- 2026-09-16: Draft created for #327。baseline `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b` (origin/main)。#205 (implemented) / #331 (implemented) のexchange実装 (`ExchangePackageComposer` の現行instruction、`ExchangeFlowUi` のentry/description copy、strings) と #204 accepted契約 (`personalized-intent-v2` 閉schema、`IntentCodec` allow-list) を確認し、interview-first 2-phase instruction・canonical example構造・capability説明・skip取り扱い (Decision 1) を起草。#328/#329/#330/#332との責務境界をNon-goalsへ明記。

## References

- [Issue #327](https://github.com/nunu1733/NunuLauncher/issues/327)
- [Spec 205: External Agent Exchange](../205-external-agent-exchange/spec.md) (**implemented**。framing・envelope上限・session置換・送信前確認・instruction 4section構成 (Decision 2) の所有)
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md) (**implemented**。run-in entry・CANDIDATE subject・`SCOPE_MISMATCH`)
- [Spec 204: AI personalization context/intent exchange contract](../204-ai-personalization-context-intent-contract/spec.md) (**accepted・実装済み**。`personalized-intent-v2` schema・validator・codec)
- [Spec 123: organizer UI convergence](../123-organizer-ui-convergence/spec.md) (ja/en strings契約)
- Issue #328 (import成功後の状態明示、OPEN), Issue #329 (Import Normalizer、OPEN), Issue #330 (authoring contract簡素化、OPEN), Issue #332 (clipboard/file-first import UI、OPEN) — 責務境界
- [CONTEXT.md](../../CONTEXT.md) (外部エージェント交換・交換パッケージ等の既存用語), [DESIGN.md](../../DESIGN.md) (gate 12/13), [AGENTS.md](../../AGENTS.md)
