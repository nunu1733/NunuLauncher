---
issue: "#327"
status: implemented
requirements: [FR-017]
risk:
  - privacy
updated: 2026-09-19
---

# External Agent Exchangeをinterview-firstにし、AIでできることを明示する

> Status: **implemented** (2026-09-18。Phase1 accepted後、Phase2実装がChatGPT実装review 2回のround (1st **Request changes** [Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/327#issuecomment-5721144695)、head `e535d3c5f5272e1b3ff9fe35e0ccfcda8107dc76` 基準 → re-review **Approve** [Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/327#issuecomment-5721349944)、head `f0c176f57152cf0b3784fa5f87df7654fcab246b` 基準) を経て完了。AC-1〜AC-6はコード・テスト・CI (issue332 instrumentation lane含む) で完了。**AC-7 (representative provider device evidence) / AC-8 (TalkBack・large font a11y evidence) のみ未完了** — 実装セッションでは実デバイス・ownerログインが要るため後続evidence passの対象)。Phase1 accepted ( ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/327#issuecomment-5720837963)、head `c7f3b7000e920cfdfd26ab661d0ebf74234691f9` 基準、blocking findingなし) を受けたため、non-blocking nit 2点 (planのOpen question参照番号・example説明の `schemaVersion` literal表現) を修正のうえstatusをdraft → acceptedへ更新。nit自体はreviewが「契約意味・実装可能性に影響しない」と明示済み)。baseline `8fd05a40d51abd24b40a7b93579bb9b76d046f75` (origin/main、#348 merge後) 上での再起草。1st revision (`5d51346648750ba0d6384824586774831789e4d8`) へのreview ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/327#issuecomment-5699803337)) でRequired指摘された #330 実装済みv3契約へのre-anchorと、ownerのscope clarification ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/327#issuecomment-5717034078)) による1往復UX前提の反映を行い、さらに #348 (accepted・実装済み) がlandしたinstruction構成 (descriptor派生output contract・canonical authoring form・finalize前self-check) の上に再anchorした。本specは #205 (implemented) / #331 (implemented) のexchange framing・session・transport・import契約と、#348 (implemented) のAI-facing contract同期・canonical authoring form・one-round-trip invariantを **1つも変更せず**、その上に (1) interview-firstな会話構成のinstruction差分と (2) アプリ内user向けcapability説明を定義する。

## Problem

#348実装後のexchange package instruction (`ExchangePackageComposer`) は、Goal / You may / Output contract (descriptor派生) / You must / [CONTEXT data] / Before sending your final answer / Response format で構成され、返答形式は単一fenced `json` blockのcanonical authoring formである。質問に関する文言は「You may: ... Ask the user clarifying questions while you work, before you finalize」と「You must: ... If information you need is missing, ask the user before you finalize」のみで、前者は曖昧時を含む作業中の質問の**許可**、後者は情報不足時の質問要求であり、**原則として最初の応答でヒアリングを行う**構成にはなっていない。そのため ChatGPT / Gemini等は、exportされた情報だけで十分と判断して初回応答でIntentを返しやすく、外部agentic環境を使う本来の価値であるユーザーへのヒアリング (利用傾向・優先事項の確認) が発生しにくい。

またアプリ内の説明 (現string: `exchange_entry_subtitle`「ChatGPT等の外部AIへの依頼文を作成し、回答を検証付きで取り込めます。」) は、ユーザー視点で「AIで何ができるのか」「AIがホーム画面を直接配置するのか」「どんな希望を伝えられるのか」を伝えていない。priority / grouping / keep-position / page affinity等のschema用語では、ユーザーは伝えられる希望の範囲を理解できない。

## Outcome

External Agent Exchangeのexchange package instructionとアプリ内説明が、**AIと相談して整理方針を作る** guided interview workflowになる。Launcher ↔ 外部AI間のartifact交換は #348のone-round-trip invariant (request 1回・final artifact 1回) を維持したまま、AI側の会話内で2〜4問のboundedなヒアリングを行い、理解した整理方針を要約し、ユーザーの確認後にのみ単一fenced `json` blockの `PersonalizedIntent` (accepted schema: `ContextExportContract.INTENT_SCHEMA_VERSION`、現行 `personalized-intent-v4`) を返す。アプリは「AIでできること」をschema用語でなく具体例で説明し、AIがホーム画面を直接変更しない (再整理に使う希望・傾向を作る) ことを明示する。最終生成では、AIが推測で独自形式を作らないようcanonical JSON example/templateをpackageへ含める (exampleの同期保証は #348のdrift防止策に従う)。

## Scope

- exchange package **instruction部の会話構成契約** (英語固定・静的合成。#205 Decision 2の継承 + #348 Decision 3のsection構成の上に差分を重ねる):
  - **Phase 1 (interview)**: 最初の応答では最終JSON artifact (fenced `json` block内のintent) を含んではならない。export内容だけで判断可能に見えても、原則として短いヒアリングを行う。質問は2〜4問、スマホで回答しやすい短さにboundedする。質問主题の例 (よく使う/すぐ開きたいアプリ、仕事/私用等のまとまり、動かしたくないもの、前ページ/片手操作等の好み) を指示に含める。質問内容の完全固定はしない (Issue non-goal)。作業中のweb検索等の調査 (You may) は変わって許容される。
  - **整理方針の要約 (policy confirmation)**: ヒアリング後、AIが理解した整理方針を短く要約してユーザーに提示し、了承を求める。
  - **ユーザー確認**: ユーザーの了承後にのみ最終Intentを生成する。最終応答の形式は #348のcanonical authoring form (単一fenced `json` block + candidate 1個) とfinalize前self-checkをそのまま使う。
  - **skip宣言の扱い**: ユーザーが「質問不要 / このまま生成」と明示した場合、AIは追加質問なしで、自らの想定する整理方針の短い要約と最終Intentを同一返答内に返す (Decision 1)。
  - **provider中立性**: 特定provider (ChatGPT/Gemini) 固有の機能 (function calling、system role、provider memory、UI automation等) に依存しない、一般的なmulti-turn chatで成立する指示であること。
- **canonical example/templateの構造契約**: accepted schema (現行 `personalized-intent-v4`。#330 partial authoring semantics準拠。#337によるv4拡張の正本はspec 337) に対応する具体例。example内のID値は実データとして誤認しないplaceholderとし、example自体はfenced code blockで囲まずINTENT marker行も含まない (Decision 2)。exampleの内容は #348のdescriptor派生output contractに対してcontract testで同期する。
- **アプリ内capability説明 (UI copy)**: exchange導線 (idle entry・#331 run-in entry) に、schema用語でなく具体例での「AIでできること」、「AIはホーム画面を直接変更しない」旨、期待される会話flow (AIからの質問 → 整理方針の確認 → 最終案。Launcher↔AIの受け渡しは1往復) を表示する。相談は外部AIアプリ内で行われ、会話内容はNunuLauncherに送信されない旨も併記する (privacy)。
- 上記のaccessibility (TalkBack / Switch Access / large font) とja/en strings (spec 123契約)。
- representativeなChatGPT/Gemini等でのdevice evidence (prompt受領 → 質問 → 回答 → 整理方針確認 → 了承 → Intent生成 → import)。

### #348との責務境界 (本specが触れないもの)

- AI-facing contractのproduction同期 (descriptor `IntentWireContract`、output contract section、finalize前self-check、canonical authoring form、one-round-trip invariant、repair導線の禁止) は #348が所有する。本specのinstruction差分はこれらのsection・文を **削除・意味変更せず** 追加のみを行い、`Issue348AiFacingContractSyncTest` が無変更で通り続けることを回帰条件とする。
- 本specのcanonical exampleは #348のdrift防止策 (descriptor派生契約とのcontract test) の対象に従う。
- import失敗時のtyped表示・許容回復文は #348 (spec 205/329のnormative更新を含む) が所有する。

## Non-goals

- Launcher内でのLLM実行、AIによるlayoutの直接apply (既存non-goalの継承)。
- chain-of-thought取得・保存。
- #204 validator・import strictnessの緩和 (#329 Import Normalizerが別所有)。interview-firstはinstruction (prompt) 契約であり、launcher側の新しい検証layerではない。
- AI側の質問内容・数の完全固定 (boundedな指針の提示のみ)。
- import入力UIの変更 (#332 implemented。本specは触れない)。
- import成功後の状態・次操作の表示設計 (#328が別所有)。
- `PersonalizedIntent` schema自体・authoring契約の変更 (#330 implemented。partial authoring semanticsの正本。#337のv4拡張 (spec accepted・実装merge済み、PR #355) も同様に本specの非対象)。本specのexampleは現行accepted schema (`ContextExportContract.INTENT_SCHEMA_VERSION`。起草時 `personalized-intent-v3`、#337により現行は `personalized-intent-v4`) の **表示** を追加するだけで、schema・validatorに触れない。
- AI-facing contractの同期機構・finalization self-check・canonical authoring form・one-round-trip invariantの定義 (#348 implemented)。本specはそれらを前提に使う。
- exchange framing (marker形式の受理規則)、envelope上限、session、transport、privacy tier、送信前確認の順序契約の変更 (#205/#331所有の契約は不変)。
- Launcher ↔ 外部AI間のartifact往復数の変更 (1往復に固定。#348 one-round-trip invariantの継承)。
- アプリ内での会話 (ヒアリング) 画面の提供。ヒアリングは外部AIアプリ内で行われる。

## Domain language

`CONTEXT.md` への追加用語案 (受入時に反映)。

**整理方針確認 (policy confirmation)**:
interview-firstなExternal Agent Exchangeにおいて、外部AIがヒアリング結果から理解した整理方針を短く要約してユーザーに提示し、了承を得るstep。了承 (またはユーザーによる明示的なskip宣言) 後にのみ最終 `PersonalizedIntent` の生成が行われる。確認のやり取りは外部AIアプリ内の会話であり、Launcher ↔ AI間のartifact交換 (request package 1回・final artifact 1回) には数えられない。launcher側はこの会話順序を検証しない (安全性はframing抽出・#204 validator・preview/confirmが所有する)。
_Avoid_: 承認画面 (launcher内UIとの混同。確認は外部AIアプリ内で行われる)、system prompt

## Behavior scenarios

### Scenario: package生成とinterview-first instruction

Given manual organization surface (idle) または #331 の選択surface (run-in) からexchange生成が実行される、
When exchange packageが生成される、
Then instruction部は **2-phase構成** (Phase 1: interview、Phase 2: final answer) を明示し、(a) 最初の応答では最終JSON artifact (fenced `json` block内のintent) を含んではならない旨、(b) export内容だけで判断可能に見えても原則として短いヒアリングを行う旨、(c) 質問は2〜4問でスマホで回答しやすい短さにboundedする旨、(d) ヒアリング後に理解した整理方針を短く要約しユーザーの了承後にのみ最終Intentを返す旨、を含む、
And #348がpinningした既存要素 (descriptor派生のOutput contract section、You mustのproduction-enforced規則・partial authoring規則・ask-before-final文、finalize前self-check、Response formatのcanonical authoring form要求、repair導線の不在) は文を削除・意味変更されずに保持される (`Issue348AiFacingContractSyncTest` が無変更で成功する)、
And data部 (CONTEXT marker行で囲まれたcanonical JSON単行) とinstruction部の機械分離 (AC-1相当、#205) は不変であり、`parsePackageStructure` が引き続き成立する。

### Scenario: representativeな外部AIとの往復 (ChatGPT / Gemini)

Given ユーザーがpackageを外部AIへ送信する (Launcher → AIのartifact受け渡しはこの1回)、
When AIが最初の応答を返す、
Then AIはIntentを返さず、2〜4問程度の短い質問 (よく使うアプリ、まとまり、動かしたくないもの、前ページ/片手操作等の好み) をユーザーに投げる、
And ユーザーの回答後、AIは理解した整理方針を短く要約して確認を求め、
And ユーザーが了承した場合のみ、単一fenced `json` block内のaccepted schema (現行 `personalized-intent-v4`) のJSONを最終artifactとして返す (AI → Launcherのartifact受け渡しはこの1回)、
And その返答をimportすると既存framing抽出・#204検証・preview/confirm pathを通る (本flowの観測はdevice evidenceが担う)。

### Scenario: ユーザーが「質問不要 / このまま生成」と明示した場合

Given ユーザーがAIとの会話で「質問は不要、このまま生成してほしい」と明示した、
When AIが応答する、
Then AIは追加の質問をせず、**自らの想定する整理方針を短く要約した文章と、最終Intent (単一fenced `json` block内のJSON) を同一返答内**に返す、
And launcher側は会話上のskipがあったかを検出・検証しない (importは従来どおりframing抽出と#204検証のみで成立する)、
And ユーザーがskipせず通常手順を辿った場合と、import以降のlauncher側の挙動に差はない。

### Scenario: AIがinstructionに従わず初回応答でIntentを返した場合

Given AIがinterview-first指示に従わず、質問なしで最終Intentを返した、
When ユーザーがその返答をimportする、
Then launcherは会話flowの不備を理由にrejectしない (検出不可能であり、安全性の正本はframing/schema/allow-list検証にある)。framing・schemaが正当なら従来どおりvalidated intentとして扱われ、違反なら従来のtyped失敗となる。

### Scenario: canonical example/templateの同梱

Given exchange packageが生成される、
When instruction部のResponse format (Phase 2) 節を確認する、
Then accepted schema (`ContextExportContract.INTENT_SCHEMA_VERSION`、現行 `personalized-intent-v4`) に対応する具体的なJSON example/templateが含まれ、
And exampleは **具体的な整理判断を1つもseedしない**: ID値 (`exportId`・`ref` 等) は実データとして誤認しないplaceholderであり生成されたpackageの実際の `exportId`・`ref` と一致せず、判断を伴うoptional field (`desiredGroup` / `groupSemantic` / `pageAffinity` / `regionAffinity` / `preserve` / `globalPreference`) は含まれず、enum値 (`importance` 等) も実値の代わりに許容値を列挙するplaceholderで示される (ユーザーが表明していないpriority・page・region・movement方針をexampleが正当値として固定しない)、
And exampleはfenced code blockで囲まれておらず、INTENT marker行も含まない (canonical authoring form「返答内のcode blockは1個」の指示とexampleのcode blockが混同される表面を広げない、AIがexampleをそのままechoして既存typed失敗を誘発する表面を広げない)、
And exampleは #330 partial authoring semanticsに整合する (判断したrefのみをauthorし、未判断refの扱いを `unresolvedRefs` の例で示す)、
And exampleのplaceholderをsynthetic export/session fixtureの実値へ置換し単一fenced `json` blockに包んだ場合、`ExchangeImportPipeline.import` を通って `Validated` に到達し (canonical authoring ⊆ production accepted)、補助oracleとして #204 `IntentCodec.decode` の閉schema (allow-list key) と #348 descriptor派生output contractのkey集合への包含にも合格する。

### Scenario: AIがexampleをverbatimでechoした場合

Given AIがexampleを独自の判断で置き換えず、example値のまま最終回答を返した、
When ユーザーがそれをimportする、
Then 実際のexportとexampleのID不一致により既存typed失敗 (`EXPORT_MISMATCH` / `UNKNOWN_REF` 等) としてzero-write rejectされ、失敗説明が再依頼を案内する (既存UX、#348の許容回復文の範囲。新規失敗classは追加しない)、
And exampleはplaceholder構造と「値を自分の判断に置き換えること」の指示により、この経路が発生しにくい設計である。

### Scenario: アプリ内capability説明 (idle entry / run-in entry)

Given ユーザーがexchange導線を表示する (manual run非active時、または #331 選択surface内)、
When 導線の説明文を読む、
Then 「AIでできること」がschema用語 (priority / grouping / page affinity等) を使わず具体例 (よく使うアプリを優先する、関連するアプリをまとめる、動かしたくないアプリを維持する、重要なものを前のページに寄せる、今の配置をできるだけ崩さない等) で示され、
And 「AIがホーム画面を直接変更するのではなく、再整理に使う希望・傾向を作る」旨が明示され、
And 送信後にAIが数問の質問 → 整理方針の確認 → 最終案生成という会話flowが想定される旨、および外部AIへ渡す依頼文と最終案の取り込みがそれぞれ1回ずつである旨が説明され、
And 相談は外部AIアプリ内で行われ、会話内容はNunuLauncherに送信されない旨が示され、
And 説明・CTAはTalkBack読み上げ・large fontで利用できる。

### Scenario: provider中立性の維持

Given exchange packageが任意のmulti-turn chat環境 (ChatGPT / Gemini / その他) へ渡される、
When instruction部を読む、
Then 特定provider固有の機能 (function calling、tool schema、system role、provider memory、UI automation) を要求する文言は存在せず、平文の複数往復会話のみで指示が完結する、
And アプリ内説明は具体provider名を例示でき (既存copyと同様)、特定providerを必須とする意味の文言を含まない。

## Decisions (このrevisionで提案する決定 — owner acceptance待ち)

1. **skip宣言の取り扱い (Issueがspec決定を求めた項目。本revisionで契約として確定)**: ユーザーの「質問不要 / このまま生成」明示は**確認stepの代替**として扱う。AIは追加質問なしで、自らの想定する整理方針の短い要約と最終Intentを同一返答内に返す。理由: (a) 会話の強制はlauncher側から検証できずprompt契約でしか表現できない、(b) ユーザーの明示的意思に反して追加の往復を要求するのはUX上逆行する、(c) launcher側の安全性 (framing・validator・preview/confirm) は会話順序に依存しないため、skipを許容しても安全表面は変わらない。ヒアリングの完全省略 (要約なし即Intent) は指示上認めない — **要約はskip時も必須**とする。evidence (AC-7) で調整できるのはこの文言の強さ・表現のみであり、会話構成の意味を受入後に変更しない。
2. **canonical exampleの構造 (semantic-value-neutral)**: exampleはinstruction部のPhase 2 (Response format) 節にstatic textとして含め、**構造だけを示し、具体的な整理判断を1つもseedしない**。(a) ID値 (`exportId`・`ref` 等) は実データと誤認できない全大文字placeholder (例: `REPLACE_WITH_THE_EXPORT_ID_FROM_THE_CONTEXT_DATA`)、(b) 判断を伴うoptional field (`desiredGroup` / `groupSemantic` / `pageAffinity` / `regionAffinity` / `preserve` / `globalPreference`) はexampleに含めない (#348のdescriptor派生Output contractが型・enum・制約の説明を既に所有するため、example側で全optional fieldの実値を見せる必要がない。「実際に判断した場合だけOutput contractに従ってfieldを追加し、判断していない場合は省略する」旨を指示に含める)、(c) `importance` 等のenumは実値の代わりに許容値を列挙するplaceholder (例: `REPLACE_WITH_HIGH_NORMAL_OR_LOW`)、(d) example自体はfenced code blockで囲まない (` ```json ` fenceは返答要求の说明としてのみ現れる)、(e) exampleはINTENT marker行を含まない (marker形式はaccepted framingのまま要求しない、#348)、(f) exampleのblocking oracleは「synthetic export/session fixtureへの置換 → 単一fenced `json` block → `ExchangeImportPipeline.import` → `Validated`」とし、#348の `canonical authoring ⊆ production accepted` と同型のproduction-truth同期を持つ (`IntentCodec.decode` とdescriptor key包含は補助oracle)、(g) schema version変更 (#330等) とexampleの更新は同一変更で行う。
3. **instruction部の静的合成の維持**: interview-first化に伴いinstructionを動的生成 (会話状態やscope内容への依存) にはしない。#205 Decision 2 (固定長instruction + payload上限でpackage sizeを構造的上限内に収める) を継承し、#348が導入したdescriptor派生section (compose時に静的descriptorから整形) も同様に静的である。#331 run-in entryでも同一instructionを用いる (CANDIDATE subjectの扱いは既存指示が担保)。interview-first差分の配置は: Phase 1の会話構成はGoal直後のinstruction開头部 (`INSTRUCTION_OPEN`) に置き、確認後の最終回答要求とcanonical exampleはResponse format節 (`INSTRUCTION_FOOTER`) に置く。Output contract / You must sectionは文単位で無変更とする。
4. **capability説明の配置**: 導線 (idle entry・run-in entry) の説明領域に具体例リスト + 「直接変更しない」明示 + 期待される会話flow (1往復の受け渡しを含む) + 会話はNunuLauncherを経由しない旨の4要素を置く。送信完了status copyは期待されるflow (AIからの質問) に言及した文言へ更新する。import入力欄のUIは #332 implementedの現状を維持する。最終的な文面 (ja正本・en) の微調整はa11y/device evidenceで行う (構造・必須要素は本specで固定)。

## Data and state

- 読むdata: なし (追加の入力sourceなし。exchange package生成は既存 #204/#331 compositionと #348 descriptorをそのまま使う)。
- exchange packageは引き続き生成時に完了するimmutableな値であり、instruction部の拡張 (interview指針・example) は静的合成textとしてcomposerが埋め込む。package本文・会話内容の永続化は行わない (#205契約の継承)。
- exampleの正本はcomposer側の定義 (composer file内のprivate定数または同package内の内部定義) とし、exampleのschema versionと `ContextExportContract.INTENT_SCHEMA_VERSION` の一致 (現行 `personalized-intent-v4`)、およびexample key集合のdescriptor名前集合への包含をunit testが強制する。
- 新規DB書込・migrationなし。privacy tier・session・transport・import pipelineの状態は不変。
- stringsは `values/` + `values-ja/` の両方へ追加する (spec 123契約)。jaを正本とする (#205 Decision 4の継承)。

## Permissions, privacy, and security

- 追加permission・network・telemetryなし。外部送信は既存Pre-send Disclosure経由のみ。
- instruction部・exampleは静的合成textであり、新たに外部へ出る情報は発生しない。exampleのplaceholder値は実ユーザーの `exportId`・`ref` と一致しない (誤送信・誤認防止)。
- ヒアリング (Phase 1) は外部AIアプリ内の会話であり、その回答はNunuLauncherを経由しない。capability説明に「相談は外部AIアプリ内で行われ、会話内容はNunuLauncherに送信されない」旨を含め、ユーザーの認識を既存privacy契約 (送信はpackageのみ) と揃える。
- 脅威モデルは #205/#204/#348 の継承: interview-first指示に従わない (あるいは悪意ある) 返答であっても、framing抽出・#204 validator・planner制約・preview/confirmが不変であることでunsafe mutationに到達しない。interview指示はsafety mechanismではなくUX品質契約である。

## Accessibility and localization

- capability説明・flow説明・CTAはTalkBack読み上げ (意味単位の読み上げ、具体例リストの箇条書き構造)、Switch Access操作、large font (font scalingによる折返し・省略なし) に対応する。
- 説明文は既存導線と同じくlive region不要の静的textとし、状態変化のannounceは既存status行の規則に従う。
- stringsは `values/` (en) + `values-ja/` (ja正本) の両方へ配置する。

## Acceptance criteria

前提: #204 (accepted・実装済み) / #205 (implemented) / #331 (implemented) / #330 (implemented) / #329 (implemented) / #332 (implemented) / #348 (implemented) の契約・実装は現mainに存在し、本specの実装blockerではない。

- [ ] AC-1: exchange packageのinstruction部が、(a) 初回応答で最終JSON artifact (fenced `json` block内のintent) を含んではならない旨、(b) exportだけで判断可能に見えても原則として短いヒアリングを行う旨、(c) 質問が2〜4問でスマホで回答しやすいboundedな数である旨、(d) ヒアリング後に整理方針を短く要約しユーザーの了承後にのみ最終Intentを生成する旨、を含むことがunit testで検証される。#348がpinningした既存要素 (`Issue348AiFacingContractSyncTest` のassert対象: descriptor派生output contract・You must production-enforced規則・partial authoring規則・finalize前self-check・canonical authoring form要求・repair導線不在) とpackage構造 (CONTEXT marker分離・data単行) が無変更の回帰testで不変であることも検証される。
- [ ] AC-2: instruction部のPhase 2節に、accepted schema (`ContextExportContract.INTENT_SCHEMA_VERSION`、現行 `personalized-intent-v4`) に対応するcanonical JSON example/templateが含まれ、(a) exampleが具体的な整理判断をseedしないこと (ID値は誤認しないplaceholder、判断を伴うoptional fieldは不掲載、enumは許容値を列挙するplaceholder)、(b) exampleがfenced code blockで囲まれずINTENT marker行を含まないこと、(c) placeholderをsynthetic export/session fixtureの実値へ置換し単一fenced `json` blockとして `ExchangeImportPipeline.import` に通すと `Validated` に到達すること (**blocking oracle**。canonical authoring ⊆ production accepted)、(d) 補助oracleとして `IntentCodec.decode` の閉schema合格とdescriptor派生key集合への包含が成立すること、(e) exampleのschema versionが `ContextExportContract.INTENT_SCHEMA_VERSION` と一致することがunit testで検証される。
- [ ] AC-3: ユーザーの「質問不要/このまま生成」明示時の取り扱い (Decision 1) がinstructionに記述されていること (要約+Intentの同一返答、追加質問なし) がunit testで検証される。
- [ ] AC-4: exchange導線 (idle entry・run-in entry両方) に「AIでできること」がschema用語を使わない具体例で表示され、「AIがホーム画面を直接変更しない (再整理に使う希望・傾向を作る)」旨が明示されることがUI test (string存在・構造) で検証される。
- [ ] AC-5: 送信後の期待される会話flow (AIからの質問 → 整理方針確認 → 最終案) と外部AIとの受け渡しが1往復である旨がユーザーに説明される (導線説明または送信完了status) ことが検証される。
- [ ] AC-6: instruction・UI copyが特定provider固有機能に依存しない (依存review: function calling / system role / UI automation等の要求文言の不在、plain multi-turn chatで成立) ことが確認される。
- [ ] AC-7: representativeなChatGPT/Gemini等を用いたdevice evidence (package送信 → AIの質問受領 → 回答 → 整理方針要約の確認 → 了承 → Intent生成 → import) がある。
- [ ] AC-8: capability説明・CTAがTalkBack / large fontで読める・操作できるa11y evidenceがある。ja/en stringsが両方存在する。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `ExchangePackageComposerTest` 拡張 (2-phase指針・bounded質問・要約と了承の各文言の存在、section順序の維持、既存遵守事項の回帰、`parsePackageStructure` 往復) + `Issue348AiFacingContractSyncTest` 無変更成功 (回帰) |
| AC-2 | composer unit test (example存在・semantic-value-neutral構造・fence不在・marker不在・schemaVersion一致) + example contract test (合成fixture置換 → 単一fenced block → `ExchangeImportPipeline.import` → `Validated` をblocking oracle、`IntentCodec.decode` / descriptor key包含を補助oracle) |
| AC-3 | composer unit test (skip宣言の扱いの文言存在) |
| AC-4 | UI test (entry rowのtestTag配下の説明text・具体例・「直接変更しない」文言、idle/scoped両entry) + strings存在test (en/ja) |
| AC-5 | UI test (導線説明またはtransport success copyの文言) |
| AC-6 | 依存review (instruction全文・UI copyの走査) — 実装PRのreview記録 |
| AC-7 | physical-device evidence記録 (docs/assessment/ またはIssue。AC-7用の会話log要約を含む) |
| AC-8 | 手動a11y evidence (TalkBack・large font) — #205/#348系の後続evidence PRと合同で可 |

含める観察: unit/contract (instruction内容・example schema適合・package構造回帰・#348同期test回帰)、UI (説明表示・strings両locale)、手動 (a11y、representative provider会話)。

## Open questions (non-blocking — owner review / evidence中に確定)

1. **capability説明の情報量**: 具体例リスト5項目 + 直接変更しない明示 + flow説明を導線に置くと縦に長くなる。展開形式 (常時表示 vs 折りたたみ) は実装PRのUX判断とする (必須要素は本specで固定)。
2. **instruction最終prose文言**: 構造・必須要素は本specで固定。微調整はAC-7 evidenceでagent遵守率を見て行う (#205 Open question 1、#348 Open question 1の継承)。

## Change history

- 2026-09-16: Draft created for #327。baseline `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b` (origin/main)。interview-first 2-phase instruction・canonical example構造・capability説明・skip取り扱い (Decision 1) を起草。#328/#329/#330/#332との責務境界をNon-goalsへ明記。
- 2026-09-18: **Revision 2**。1st review ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/327#issuecomment-5699803337)、head `5d51346648750ba0d6384824586774831789e4d8` 基準、**Request changes**) とowner scope clarification ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/327#issuecomment-5717034078)) を受け、#348 (accepted・実装済み) merge後のbaseline `8fd05a40d51abd24b40a7b93579bb9b76d046f75` へ再anchor。(1) schemaを `personalized-intent-v2` → `personalized-intent-v3` へ、coverage前提を #330 partial authoring semanticsへ置換。(2) Response format前提を #348 canonical authoring form (単一fenced `json` block、marker要求なし) へ更新し、example同梱の形状契約 (fenceで囲まない) を再定義。(3) one-round-trip前提 (AI内multi-turnは可、Launcher↔AI artifact交換は1往復、import後repair loopは通常flowに含めない) をOutcome・Domain language・scenarioへ反映 (#348が所有するinvariantの継承として)。(4) #348との責務境界sectionを新設し、「#348がpinningしたinstruction要素の無変更回帰 (`Issue348AiFacingContractSyncTest`)」をAC-1の回帰条件へ追加。(5) 依存Issue (#329/#330/#332/#348) のstatusを実装済みへ更新。
- 2026-09-18: **Revision 3 (本revision)**。revision 2へのChatGPT review ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/327#issuecomment-5720675516)、head `24415322078b6b8bebee6ca206543845a1c52dd1` 基準、**Request changes**、高1・中2) に対応。**(高1)** canonical exampleをsemantic-value-neutralへ変更: 判断を伴うoptional field (`desiredGroup` / `groupSemantic` / `pageAffinity` / `regionAffinity` / `preserve` / `globalPreference`) をexampleから除外し、enum値も実値の代わりに許容値列挙placeholderとする (「誤認リスクはID値に限る」というIssue要求の狭め込みを除去)。**(中2)** AC-2のblocking oracleを「synthetic export/session fixture (MOVABLE 3件以上・既知pageCount) 置換 → 単一fenced `json` block → `ExchangeImportPipeline.import` → `Validated`」へ引き上げ、`IntentCodec.decode` / descriptor key包含を補助oracleへ降格 (#348 `canonical authoring ⊆ production accepted` と同型のproduction-truth同期)。**(中3)** skip時の要約添付を契約として確定し (Decision 1)、Open question 1を廃止。
- 2026-09-18: **accepted**。revision 3のChatGPT review **Approve** ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/327#issuecomment-5720837963)、head `c7f3b7000e920cfdfd26ab661d0ebf74234691f9` 基準) を受け、non-blocking nit 2点 (plan.mdのOpen question参照番号 (3→2、2→1) の更新、canonical example説明の「すべての値」を「`schemaVersion` を除く置換対象値」へ精密化) を本commitで反映したうえでstatusをdraft → acceptedへ更新。approving reviewは「前回の高1・中2はすべて解消されており、Issue #327のspec/planとしてimplementationへ進める状態」と判断した。次: plan.mdに従い実装 (Phase 2) へ進む。AC-7 (representative provider device evidence) / AC-8 (a11y evidence) は後続evidence passの対象 (#348 AC-11と同様)。

- 2026-09-18: **implemented**。Phase 2実装 (commit `e535d3c5f5272e1b3ff9fe35e0ccfcda8107dc76`: interview-first instruction・canonical template・capability notes・strings・unit/pipeline test) とreview修正 (commit `f0c176f57152cf0b3784fa5f87df7654fcab246b`: rendered-UI oracle 2件・transport中立copy・KDoc修正) を実施。re-review **Approve** ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/327#issuecomment-5721349944))。AC-7/AC-8のevidenceは後続passへ (本spec Status参照)。

- 2026-09-19: **v4 re-anchor (fact-sync、docs-only・契約変更なし)**。本spec accepted/implemented後に #337 (spec 337、PR #355) がlandし、accepted schemaが `personalization-context-v4` / `personalized-intent-v4` へbumpされたため、本spec内のschema version表記を現行accepted schemaへ追従させた (spec 331の「v3 bump追従」・spec 205の「intent schema v3対応」と同型の追従記録)。production側は自動追従済み: canonical templateはdescriptor派生 (`CANONICAL_INTENT_TEMPLATE` がdescriptorの `schemaVersion` exact値を使用) であり、AC-2(e) の定数一致・`ExchangeImportPipeline.import` 到達oracle (`Issue327InterviewFirstContractTest`) は現main上で無変更のまま成立する。category/groupのauthoring指針 (#337の `groupSemantic` exactly-one-of・`categoryRef` / `proposalLabel`) は本specのtemplateを変更せず、descriptor派生Output contractが所有する (spec 337 R-4/D-8の責務境界)。関連status: #328はPR #353実装merge済み (Issue OPEN)、AC-7/AC-8 evidenceは #351 (OPEN) が追跡、#361 Organizer TO-BE UX決定がaccepted (closed) — exchange導線の将来配置を扱うが内部pipeline・同意gateは現行契約維持 (D-04) で本specのinstruction・copy契約は変更しない。

## References

- [Issue #327](https://github.com/nunu1733/NunuLauncher/issues/327)
- [Spec 205: External Agent Exchange](../205-external-agent-exchange/spec.md) (**implemented**。framing・envelope上限・session置換・送信前確認・instruction静的合成の所有。Response format要求は #348 Decision 6によりcanonical authoring form前提へ更新済み)
- [Spec 348: AI-facing contract同期](../348-exchange-ai-facing-contract/spec.md) (**implemented**。`IntentWireContract` descriptor・Output contract section・finalize前self-check・canonical authoring form・one-round-trip invariant・repair導線禁止・spec 205/329 normative更新の所有)
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md) (**implemented**。run-in entry・CANDIDATE subject・`SCOPE_MISMATCH`)
- [Spec 204: AI personalization context/intent exchange contract](../204-ai-personalization-context-intent-contract/spec.md) (**accepted・実装済み**。`personalized-intent-v4` schema・validator・codec。v3→v4拡張はspec 337所有)
- [Spec 337: exchange category group proposals](../337-exchange-category-group-proposals/spec.md) (**accepted・実装済み (PR #355)、Issue OPEN**。`personalization-context-v4` / `personalized-intent-v4` bumpと `groupSemantic` exactly-one-of (`categoryRef` / `proposalLabel`) の正本。本specのcanonical templateは変更していない — spec 337 R-4/D-8のとおりcategory/group guidanceはdescriptor派生Output contractが所有)
- [Spec 330: partial intent authoring](../330-partial-intent-authoring/spec.md) (**implemented**。v3 partial authoring semanticsの正本)
- [Spec 329: Import Normalizer](../329-import-normalizer/spec.md) (**implemented**。accepted framingの認識層)
- [Spec 123: organizer UI convergence](../123-organizer-ui-convergence/spec.md) (ja/en strings契約)
- Issue #328 (import成功後の状態明示、PR #353実装merge済み・Issue OPEN) — 責務境界
- Issue #351 ([AC-7/AC-8 device evidence pass](https://github.com/nunu1733/NunuLauncher/issues/351)、OPEN) — 本specの残受入条件の追跡
- Issue #361 / [Organizer TO-BE UX](../../docs/product/organizer-to-be-ux.md) (accepted・closed) — exchange導線の将来配置 (idle相談のhub status card化等) を定めるTO-BE決定。内部pipeline・同意gateは現行契約維持 (D-04) であり、本specのinstruction・capability copy契約を変更しない
- [CONTEXT.md](../../CONTEXT.md) (外部エージェント交換・交換パッケージ等の既存用語), [DESIGN.md](../../DESIGN.md) (gate 12/13), [AGENTS.md](../../AGENTS.md)
