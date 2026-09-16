---
issue: "#330"
status: accepted
requirements: [FR-017]
risk:
  - privacy
  - layout-data
updated: 2026-09-16
---

# External Agent向けPersonalizedIntent authoring contractの簡素化 (partial authoring契約)

> Status: **accepted** (2026-09-16) — Issue #330 re-review (comment 5698414707) により D-1〜D-6 がowner受入れされた。実装は本specの受入条件に従う (plan.mdのexecution checklist)。本specはimplementedであるspec 204 / 205 / 331と矛盾しない **拡張** として起草しており、実装PRでこれらのChange historyへ拡張記録を追加する。

## Problem

現行intent契約 (spec 204、#331によりschema versionは `personalized-intent-v2` / `personalization-context-v2` へbump済み) は、閉じたschemaとfail-closed検証を安全性の正本としている。その代償として、AI (外部agent / 将来のmanaged AI) に次のauthoring責任を課している。

1. **全ref列挙**: exportされた全 `ref` を `itemIntents` または `unresolvedRefs` のどちらかに必ず1回ずつ列挙させ、1件の欠落でintent全体が `INCOMPLETE_COVERAGE` でrejectされる (`IntentValidator.kt` のcoverage partition検証。実装: covered + unresolved == exportRefs の全数一致検査)。
2. **FIXED itemの出力責任**: dock・lock・folder member等の `mobility: FIXED` なitem (export上限512件のうち大部分を占め得る) も、矛盾しないsemantic output (`preserve` のみ、または `unresolvedRefs` 明示) として出力しなければならない。誤って `importance` 等を付与すると `MOBILITY_CONTRADICTION` で全体rejectされる。
3. **全体reject**: 上記のどれか1件でも違反すると、正当に判断済みの残り全entryごとintentが失われる。

export packageのinstruction部も "Cover every ref exactly once across itemIntents and unresolvedRefs" とこの負担を明示的に要求している (`ExchangePackageComposer.kt`)。実AI (ChatGPT/Gemini等) の返答では、判断していないitemの捏造列挙・FIXED itemへの過剰field付与・単純な数え漏れが起こり得、これらがすべてformat failureになる。これは検証器の安全性とauthoringの実可行性の両立問題であり、strict validatorを単純に緩めるのではなく、**AIが明示した内容だけをauthorし、未言及を安全に「判断なし」と解釈できる契約** が必要である。

## Outcome

- AIは判断したitemと判断できなかったitemだけを記述すればよく、未言及の `ref` はLauncher側で **canonical unresolved (判断なし・no-op)** として一意に解釈される。
- 未言及に対してpriority / grouping / affinity等への **推測補完は一切行われない** (安全要件)。
- FIXED / locked itemはauthoring責任から外れる (省略可)。ただしlock bypassは引き続き構造的に不可能である。
- Plannerへ渡る時点では、全subjectの状態が決定済みの **complete canonical representation** が構成され、partialなauthoring表現がそのまま下流へ流れることはない。
- 既存V1/V2契約の意味はsilentに変更されない。変更はschema version bump (`personalized-intent-v3` / `personalization-context-v3`) として導入し、保存済み旧version文書・#206 managed AI構想との互換性を明文化する。

## Scope

- `PersonalizedIntent` authoring契約のpartial化の意味論 (未言及ref = canonical unresolved) と、validator後のcomplete内部表現への変換契約。
- FIXED / CANDIDATE / MOVABLE / CONDITIONAL各mobilityに対するomissionの意味と、FIXED itemのauthoring責任の縮小。
- Contract versioning (v3 bump、旧version文書のfail-closed拒否、export session・intent identityへの影響、#206 managed AIとの互換)。
- 失敗分類への影響 (`INCOMPLETE_COVERAGE` の条件narrow化。class数・UI失敗表示17種は不変)。
- exchange package instruction部のcoverage要求文言の変更。
- 上記のcontract / property / security test要件。

## Non-goals (Issue本文のNon-goalsを継承・具体化)

- 未言及itemへのAI意図の推測 (priority/group/affinityへの補完。禁止事項であり実装しない)。
- unknown / out-of-scope refの自動許可 (`UNKNOWN_REF` は維持される)。
- lock / fixed制約の緩和。承認済intentがplannerの保持判断 (`determinePreservation`)・lock/bounds/overlap制約を弱めることは従来どおりない。
- malformed JSON・framing揺らぎのnormalization ([#329](https://github.com/nunu1733/NunuLauncher/issues/329) 所有。本specとの責務境界は後述)。
- partial layout apply (intentの受入れは従来どおりall-or-nothingであり、適用はpreview → confirm → apply pathのまま)。
- interview-first prompt / package再設計 (#327)、import UI導線 (#332)。本specはinstructionのcoverage文言と意味論のみを変える。
- `PersonalizedIntent` のfield追加・削除 (schemaのfield集合はv2と同一。変わるのはcoverage要求の意味のみ)。
- #206 managed AI pathの実装 (本specはprovider中立の契約変更のみ。#206は単一契約を引き続き利用する)。

## 固定する比較軸

Design questions 1〜4の比較は、次の軸で評価する。

| 軸 | 内容 |
|---|---|
| (i) 意味の明示性 | partialityがschema versionとして外部に宣言されるか (consumerがomissionの意味を文書から知れるか) |
| (ii) 推測不在の保証 | 未言及が「判断なし」以外に解釈されないことが単一の閉じた規則で強制できるか |
| (iii) versioning整合 | #204のimmutable semantic version規則 (意味変更はbump、旧versionはfail-closed) を守るか |
| (iv) 検証対象の純度 | strict validatorが検証するのが「AIが実際に書いた文書」であるか (境界が補完した後の人工物でないか) |
| (v) consumer統一性 | #205 (外部agent) と #206 (managed AI) が同一authoring契約を使えるか |
| (vi) 実装/seam影響 | 変更が#204/#331の確立したseam (codec → validator → adapter) の内側で閉じるか |

## Design question 1: Missing ref semantics (A / B / C の比較)

| 案 | 内容 | 評価 |
|---|---|---|
| **A. 現行維持 (full coverage必須)** | 全ref列挙を要求し続け、欠落は `INCOMPLETE_COVERAGE` | 問題文のauthoring負担がそのまま残る。本Issueの成果 (Outcome) を達成できない。**不採用** |
| **B. import境界で `unresolved` へcanonicalize (schema無変更)** | 境界層が未言及refを `unresolvedRefs` へ補完してからvalidatorへ渡す | v2文書の意味がsilentに変化する (欠落がreject → 受理) ため (iii) に違反する。bumpを必須にすれば違反は解消するが、その場合でもvalidatorの検証対象が「境界が補完した後の人工物」になり (iv) を崩す (AIが書いた文面の検証と系统の補完が混在し、どちらが悪いのかtyped failureの帰属が曖昧になる)。**不採用** |
| **C. schema自体をpartial authoring型へversion-upし、validator後にcomplete内部表現へ変換** | `personalized-intent-v3` としてomissionを認め、strict validatorはAIが書いた文のまま検証し、受入れ後に純粋なcompleterがcomplete canonical representationを構成する | (i) partialityがversionとして宣言される。(ii) 補完規則が「omission → canonical unresolved のみ」と単一に閉じる。(iii) bumpとして導入。(iv) validatorはauthored文書を検証し、補完は検証後の別段。(v) 契約は単一のままで全consumerが利用可 (full coverage文書はv3でも正当なsuperset)。(vi) codec/validator/adapterの既存seamの内側。**採用 (D-1)** |

**D-1: 案Cを採用する。**

- v3のauthoring意味論: `itemIntents` と `unresolvedRefs` は合わせてexport全refの **部分集合でよく、互いに素** (各refは高々1回、どちらかに出現) でなければならない。全数cover要求は廃止する。
- 未言及ref (両方に現れないref) の意味は **canonical unresolved (判断なし)** のみとし、いかなるfield値も生成しない。
- **superset互換**: v2どおりのfull coverage文書はv3でも正当である。既存のproducer (full coverageを吐くtest・将来のmanaged AI) は修正なしで動作する。

## Design question 2: FIXED / locked item handling

比較対象 (Issue本文の3案):

| 案 | 内容 | 評価 |
|---|---|---|
| **a. 出力必須のまま** | FIXED itemも必ず列挙させる | AIが実質判断できない対象 (dock/lock/folder member) への出力強制が残り、`MOBILITY_CONTRADICTION` によるformat failure源が消えない。**不採用** |
| **b. omitted可とし、Launcher側でpreserve/fixedとしてcanonicalize** | FIXED refの省略を認め、complete表現で明示的な状態へ落とす | authoring負担が消え、export文書・検証規則は現形を維持できる。**採用 (D-2)**。ただしomissionのcanonical値は「preserve」ではなく **uniformなcanonical unresolved** とする (下記) |
| **c. export packageでread-only subjectとして別表現** | FIXED itemをexport文書から分離したread-only領域へ置く | export文書内にsubject表現の二重正本が生じ、Contract 1 (`PersonalizationContextExport`) の構造変更 (coverage対象・privacy tier走査・`SessionExportReconstructor` parity) が更大になる。FIXEDであることの透明性 (`mobility` + `fixReason`) は現行items表現で既に達成されている。**不採用** |

**D-2: 案bを採用する。omissionのcanonical値はmobilityによらず一律 `canonical unresolved` とする。**

- FIXED / CANDIDATE / MOVABLE / CONDITIONALの全mobilityで、未言及は同じ「判断なし」状態へcanonicalizeされる (mobility条件分岐のある補完は、それ自体が一種の推測であり、単一規則でないため)。
- FIXED refを明示出力する場合の規則は **無変更**: 許可されるのは `preserve` と `unresolvedRefs` 明示のみ。`importance` / `pageAffinity` / `regionAffinity` / `desiredGroup` / `groupSemantic` を付けた場合は引き続き `MOBILITY_CONTRADICTION` でrejectする。`CANDIDATE` refへの `preserve` も引き続きreject (spec 331の拡張条件)。
- **lock bypass不可能の保証 (3層、いずれも現行から不変)**:
    1. **validator層**: FIXED refへの移動系semantic fieldは `MOBILITY_CONTRADICTION` でrejectされる (schema内表現に対する最初の関門。維持)。
    2. **planner authority層**: 仮に何らかのpreferenceが受理されても、intentはordering/preference biasにすぎず、`determinePreservation`・lock保持・bounds/overlap制約の正本はplanner/allocatorにある (spec 204「plannerが正本」。維持)。completerはfield値を生成しないため、この層へ新しい入力は増えない。
    3. **completer層**: completerが生成できるのはrefの状態 (unresolved) のみであり、`ItemIntent` のfield値 (importance/group/affinity/preserve) を生成する経路は存在しない。
- FIXED itemはexport文書からは除外しない。AIが「動かせない対象」を `fixReason` 付きで見える透明性は維持する。

## Design question 3: Contract versioning

**D-3: `personalization-context-v3` / `personalized-intent-v3` への同時bumpとする (spec 331 D-1と同じ手順)。**

- export文書の `capabilities.intentSchemaVersion` がintent schema versionを広告するため、intent側だけのbumpはできない。両者を同時にbumpする (intent文書のfield集合は無変更だが、coverage要求という意味が変わるため)。
- **dual-version runtime supportは持たない** (spec 331 D-1の継続)。本appはproducer/consumer同梱であり、単一version (v3) のみ生成・受入れ、v1/v2文書は `SCHEMA_MISMATCH` でfail-closed拒否する。
- **保存済み文書との互換性**: intent本文は永続化されない (spec 204「export/intentの本文は永続化せず」。durableなのはexport sessionのみ)。したがってbumpが既存永続データに与える影響は次に限られる:
    - **export session**: session record (`AndroidExportSessionStore`、record schema v2 / file `organizer_personalization_export_session_v2.json`) はintent schema versionを保持しないため **無影響**。v2 eraに生成されたactivity session宛のv2 intentは、bump後はcodecで `SCHEMA_MISMATCH` となり再exportが必要になる (TTL 24時間・single-active-sessionの運用で影響は一時的。spec 331がv1→v2で辿ったのと同じ扱い)。
    - **intent identity**: `IntentIdentity.schemaVersion` は `personalized-intent-v3` となる。identityはrun内provenance (`InputProvenance` 第7input) にのみ参加し、durable status projectionはdigestを保持しないため (CONTEXT.md)、永続互換性への影響はない。
- **authoring envelope / exchange-specific canonicalization layer (不採用)**: v2 payloadを内包するenvelope形式、または#205経路だけの補完層は、(1) 結局は新しいwire versionの導入が必要でbumpを回避できない、(2) consumer間 (#205/#206) でauthoring契約が分岐し、#206が要求する「#204 schema/validatorを唯一のAI output contractとして利用する」単一契約性を損なう、ため不採用。単一契約をv3 supersetへ更新する方が小さい。
- **#206 managed AI互換**: v3はauthor側から見てv2のsupersetである (full coverage出力は引き続き正当)。managed providerがstructured outputで全列挙を出す実装も、omissionを活用する実装も同一契約で成立する。#206のspecはv3時点の契約を参照して起草される (依存関係の正方向)。

## Design question 4: Canonical internal representation

**D-4: validator通過後に純粋なcompleterがcomplete canonical representationを構成し、これがPlannerへの唯一の入力となる。authored文書は診断用にのみ保持する。**

- complete表現 (`CompletedPersonalIntent`、名称はplanで確定) は「export全refがちょうど1回現れる完全分割」を型/不変条件として持つ。各refのdecisionは次の3種:
    - `Authored(ItemIntent)` — AIが `itemIntents` に書いた内容のうち、**少なくとも1つのsemantic fieldを持つentry** (そのまま)
    - `UnresolvedAuthored` — AIが `unresolvedRefs` に明示した判断なし、および **全semantic fieldがnullのbare entryをcompleterが正規化した判断なし** (D-6)
    - `UnresolvedByOmission` — 未言及をcanonicalizeした判断なし
- completerは **pure・total・deterministic** な単一関数とし、validator seamの内側 (`IntentValidator.validate` の成功path) に置く。呼び出し側とtestは従来どおり単一のvalidator seamを使う (AGENTS.md seam規約)。bare entryの正規化を含め、completerが行うのはrefへの **状態の付与のみ** であり、`ItemIntent` のfield値の生成・書換は行わない。
- `IntentPlannerAdapter` は `Authored` decisionのみを `ItemPreference` へ投影する (`UnresolvedAuthored` / `UnresolvedByOmission` はpreferenceを生成しない)。この結果、**omissionされたrefのplanner効果は、`unresolvedRefs` に明示されたrefと完全に同一** である。
- authored文書 (v3 partial) はplanner・preview・applyへは渡されない。diagnostics・UI表示のためだけに `ValidatedPersonalizedIntent` 上に保持する。
- content limits: authored側の上限 (`MAX_INTENT_ENTRIES` / `MAX_INTENT_UNRESOLVED` / `MAX_INTENT_BYTES`) は無変更。complete表現のunresolved数はexport items数 (≤512) に自然にboundされ、既存の上限不変条件を満たす。

**D-5: intent identity (content digest) はcomplete表現のcanonical byte表現に対して計算する。**

- canonical row grammarは現行 (`item|ref|...` / `unresolved|ref`) を維持し、`UnresolvedAuthored` と `UnresolvedByOmission` は同じ `unresolved|ref` rowを生成する。よって「明示unresolved」と「省略」は **同一のsemantic identity** を持つ (どちらも判断なしであり、区別すべき意味的差異がない)。`UnresolvedByOmission` であることの情報はdiagnostics用の別fieldでありidentityに入らない。
- 同一authored文書 + 同一export refsからは常に同一identityが決定的に得られる (determinism契約の継続)。

**D-6: bare entry (全semantic fieldがnullの `itemIntents` entry、すなわち `ref` のみのentry) はcompletionでcanonical unresolvedへ正規化し、「明示unresolved」「省略」「bare entry」の3表現を同一のsemantic identityとする。**

- bare entryはいかなるsemantic fieldも持たないため、planner効果は実際に存在しない (現行実装の全consumerが `desiredGroup` / `preserve == true` / `regionAffinity` / `pageAffinity` / `importance` 等の個別fieldのみを参照し、all-nullな `ItemPreference` の **出現そのもの** には効果がない。`FullRunExecution.kt` の各preference消費箇所で確認)。よって「bare entryを独立したauthored stateとしてidentity上も区別する」選択は、planner効果が同一であるpayload群に対してidentityだけが分岐する状態を作り、dedupe / replay契約を実装依存にするため **不採用** とする。
- 採用する規則は単一かつ閉じている: **identityはrefごとのsemantic内容のみの関数であり、semantic fieldを1つも持たないrefは表現形式にかかわらず `unresolved|ref` rowになる**。これにより「同じplanner効果 ⇒ 同じidentity」が常に成立する。
- 検証との相互作用は次のとおり (いずれも検証規則は無変更):
    - bare entryは検証段では通常の `itemIntents` entryとして扱われる。存在しないrefへのbare entryは `UNKNOWN_REF` でrejectされる (正規化は検証後のcompletionで行われるため、unknown refの自動許可にはならない)。
    - bare entryはsemantic fieldを持たないため `MOBILITY_CONTRADICTION` の対象にならない (現行と同一)。
    - `preserve: false` 等、**1つでもfield値が存在するentryはbare entryではない** (`Authored` のままitem行でidentityに参加する)。
- bare entryであったことの情報 (表現形式のprovenance) は、diagnostics用に保持するauthored文書からのみ取得可能であり、identity・planner効果・dedupe・replayのいずれにも現れない。

## Contract変更詳細 (v3)

1. `schemaVersion`: intent `personalized-intent-v3`、context `personalization-context-v3`。unknown versionは従来どおり `SCHEMA_MISMATCH`。
2. intentのfield集合・型・content limits・`exportId` echo要求はv2と同一。
3. **coverage規則の変更**: 「`itemIntents` refs ∪ `unresolvedRefs` == 全exported refs (かつ互いに素)」を、「両集合が互いに素 (`unresolvedRefs` 内の重複を含む)」へnarrowする。全数cover要求は廃止。
    - `INCOMPLETE_COVERAGE` classは **維持する** (class数13・UI失敗表示17種は不変)。v3における意味は「分割違反 (同一refの両方出現・unresolved内重複)」へnarrowされ、表示文言はこの条件に合わせて更新する。
4. 未言及ref = canonical unresolved。推測補完の禁止はcompleterの閉じた仕様 (状態の付与のみ・field値の生成禁止) として強制される。
5. **bare entry (全semantic fieldがnullの `itemIntents` entry)**: 検証段では通常の `itemIntents` entryとして扱い (未知ref・重複・forbidden content等の検証対象。規則無変更)、受入れ後のcompletionでcanonical unresolvedへ正規化する (D-6)。3表現 (明示unresolved / 省略 / bare entry) は同一identity・同一planner効果。
6. mobility検証 (`MOBILITY_CONTRADICTION` の条件表) はv2から無変更。omissionに対してmobility検証は適用されない (何も書かれていないため)。bare entryはsemantic fieldを持たないため対象にならない (現行と同一)。
7. `UNKNOWN_REF` / `DUPLICATE_REF` / `FORBIDDEN_CONTENT` / `INVALID_ENUM` / `EXPORT_MISMATCH` / `SESSION_EXPIRED` / `CONTEXT_STALE` / `SCOPE_MISMATCH` (run側gate) はすべて無変更。
8. **完全omission intent** (`itemIntents` 空かつ `unresolvedRefs` 空) は正当である。planner効果はall-unresolved明示と同一 (preferenceなし、`globalPreference` のみ有効)。
9. **exchange package instruction**: "Cover every ref exactly once..." の要求を、部分authoringを認める文言へ置き換える (「判断したitemと判断できなかったitemだけを書くこと。書かなかったrefは判断なしとして扱われ、推測されない」趣旨。最終copyは実装PR/#327との合成で調整)。
10. **UI失敗表示**: 種類数は不変。`INCOMPLETE_COVERAGE` の案内文言を「両方に列挙されたrefがある」条件へ更新する (ja/en)。import成功時にAIが判断済みの件数を表示する導線は本specの対象外 (#332/#205 UI側でcomplete表現の情報から可能。本specはdiagnosticsがauthored/canonical別の計数を保持することのみ要求)。

## #329 Import Normalizerとの責務境界

- **#329所有**: 受け取りtextの外形 (code fence、standalone JSON、BOM/CRLF、前後空白) の認識と正規化。refの追加・削除、field値の意味補正、schema version書換は#329でも禁止。
- **本spec (#330) 所有**: schema-validなintent文書内のomissionの意味とcomplete表現への変換。framing・transport外形には一切触れない。
- **順序**: exchange framing (#205) → [normalizer (#329、実装時)] → strict decode (#204 codec) → strict validation (coverage narrow版) → completion (#330) → identity → planner adapter。
- normalizerがomissionを埋める (refを追加する) ことは禁止する。これを許すとcompleterと二重正本になる。#329は「意味を扱わない」自 issueの要求境界とも整合する。

## Behavior scenarios

### Scenario: 部分authoringの受理とcomplete表現

**Given** exportに40 ref (MOVABLE 30 / FIXED 8 / CANDIDATE 2) があり、
**When** AIが5 refのみ `itemIntents` に記述し (残り35 refは両方のlistに現れない) intent v3としてimportされる、
**Then** intentは受理され、complete表現は40 ref全てをちょうど1回ずつ含む完全分割 (5 authored + 35 `UnresolvedByOmission`) となり、
**And** planner投影は5件のpreferenceのみを生成し (35件のomissionは明示unresolvedと同一効果)、identityは決定的に計算される。

### Scenario: FIXED itemの省略

**Given** exportに `mobility: FIXED` + `fixReason: DOCK` のrefが存在し、
**When** AIが当該refをintentに一切書かない、
**Then** 受理され、当該refは `UnresolvedByOmission` としてcomplete表現に現れ、
**And** plannerは当該itemをlock/dock保持規則により従来どおり保持する (intentの有無は保持判断に影響しない)。

### Scenario: FIXED itemへの悪意あるsemantic output (lock bypass不可能)

**Given** locked item (`mobility: FIXED` + `fixReason: LOCKED`) のrefを含むexportがあり、
**When** AIが当該refに `pageAffinity` または `desiredGroup` を付与したintent v3をimportする、
**Then** `MOBILITY_CONTRADICTION` でrejectされ (partial applyなし)、
**And** 仮に何らかのpreferenceが受理されてもplannerの `determinePreservation` とlock/bounds/overlap制約は不変であるため、lock配置が動く経路は存在しない。

### Scenario: unknown refは省略許容の対象外

**Given** intentがexportに存在しない `ref` を `itemIntents` に含む、
**When** importされる、
**Then** `UNKNOWN_REF` でrejectされる (omission許容は、既知refの「書かない」ことの意味を定めるものであり、未知refを許すものではない)。

### Scenario: 分割違反は引き続きreject

**Given** 同一refが `itemIntents` と `unresolvedRefs` の両方に現れる (または `unresolvedRefs` 内で重複する)、
**When** importされる、
**Then** `INCOMPLETE_COVERAGE` でrejectされる (v2と同じclass・異なる条件。全数coverの欠落はv3では違反ではない)。

### Scenario: full coverage文書の互換

**Given** AIがv2形式どおり全refを過不足なく列挙したintent (schemaVersionのみ `personalized-intent-v3`) を返す、
**When** importされる、
**Then** 従来どおり受理され、complete表現のdecisionはすべてauthored/unresolved-authoredとなり (canonical omission 0件)、planner効果・identity計算はv2と同一semanticである。

### Scenario: 旧version文書の拒否

**Given** `schemaVersion: personalized-intent-v2` (またはv1) の文書がimportされる、
**When** decodeされる、
**Then** `SCHEMA_MISMATCH` でfail-closed rejectされ、再export・再依頼が案内される。

### Scenario: identityの決定性とsemantic同一性

**Given** 同一exportに対し、intent I1がref Rを `unresolvedRefs` に明示し、intent I2がRを省略している (他は同一)、
**When** 両方を取り込む、
**Then** 両者とも受理され、identity (digest) は同一であり (canonical row `unresolved|R` が両者で生成される)、planner効果も同一である。

### Scenario: bare entryの正規化とsemantic同一性 (D-6)

**Given** 同一exportに対し、intent I1がref Rを全semantic fieldがnullの `itemIntents` entry (bare entry) として記述し、intent I2がRを `unresolvedRefs` に明示し、intent I3がRを省略している (他は同一)、
**When** 3件をimportする、
**Then** 3件とも受理され、identity (digest) は3者同一であり (canonical row `unresolved|R` が共通)、planner効果 (preference不生成) も同一である、
**And** bare entryであったことの情報はdiagnostics用のauthored文書にのみ残り、identity・dedupe・replayのいずれにも現れない。

### Scenario: bare entryは検証対象から外れない (D-6)

**Given** intentがexportに存在しない `ref` を全field nullのbare entryとして `itemIntents` に含む、
**When** importされる、
**Then** `UNKNOWN_REF` でrejectされる (bare entryの正規化は検証後のcompletionで行われるため、unknown refが正規化を経て自動受理されることはない)。

### Scenario: #329 normalizerとの境界

**Given** #329実装後、code fenceで囲まれたintent v3がimportされる、
**When** 取り込みが実行される、
**Then** normalizerはfenceの除去のみを行い (refの追加・omissionの埋め込みはしない)、omissionの解釈は本specのcompleterが単独で行う。

## Acceptance criteria

- [ ] AC-1: Design questions 1〜4の各案の比較と採否が本specに固定されている (A/B/C、a/b/c、versioning 3案、complete表現)。draft decision D-1〜D-6はowner受入れ済み (2026-09-16 re-review comment 5698414707)。
- [ ] AC-2: 未言及refの意味が常に `unresolved` / no-op相当であり、semantic inferenceを行わないことが契約・実装・test (property: completerはfield値を生成しない、omission ≡ 明示unresolved ≡ bare entryのplanner効果) で検証される。
- [ ] AC-3: FIXED/locked itemのAI authoring責任を減らしてもlock bypassが不可能であること (FIXED refへのsemantic fieldの `MOBILITY_CONTRADICTION` reject、承認済preference下でもplanner保持判断・lock/bounds制約が不変であること) がtestで検証される。
- [ ] AC-4: Plannerへ渡す前にcomplete canonical representationへ変換されること (全export refがちょうど1回現れる分割のcompleteness property。authored partial文書がplanner/previewへ渡らないこと) がtestで検証される。D-6のstable identity (bare entry / 明示unresolved / 省略の3表現が同一digest・同一projectionとなり、同一semantic内容のreplayが同一identityを返すこと) もtestで検証される。
- [ ] AC-5: unknown / out-of-scope refが従来どおり `UNKNOWN_REF` でrejectされること (省略許容の対象外であること。bare entryであっても対象外であること) がtestで検証される。
- [ ] AC-6: 既存V1/V2互換性とversioning strategy (v3 bump、旧version文書のfail-closed拒否、export session無影響、identity schemaVersion更新、#206 managed AIのsuperset互換) が明文化されtestで検証される。
- [ ] AC-7: coverage omission / fixed omission / explicit unresolved / malicious lock overrideの各testが存在する。
- [ ] AC-8: #329 normalizerとの責務境界 (normalizerは外形のみ・completerは意味のみ、normalizerがrefを追加しない) が明文化され、境界testで検証される。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 本specの比較表とDecisions (review対象。実装不要) |
| AC-2 | completer unit/property test: omission → `UnresolvedByOmission` のみ生成・`ItemIntent` field不生成・bare entry → `UnresolvedAuthored` 正規化、planner投影等価test (omission ≡ explicit unresolved ≡ bare entry)、完全omission intent受理test |
| AC-3 | validator corpus test: FIXED refへの `importance`/`pageAffinity`/`desiredGroup` 等の `MOBILITY_CONTRADICTION` (維持)、CANDIDATE refへの `preserve` reject (維持)、受理済preferenceを含むrunでのplanner保持判断不変の既存property suite回帰 |
| AC-4 | completeness property test (complete表現がexport全refの分割)、authored文書の非流出 (adapter入力の型/契約test)、identity決定性test (同一authored + 同一refs → 同一digest)、D-6 stable identity / replay test (bare entry / 明示unresolved / 省略の3表現が同一digest・同一projection、同一semantic内容の再importが同一identity) |
| AC-5 | validator unit test (`UNKNOWN_REF` corpus: 未選択候補・新規install・完全な虚構ref。bare entryの未知refを含む) |
| AC-6 | codec contract test (v1/v2拒否・v3受入)、session record無影響test (既存 `AndroidExportSessionStoreTest` 回帰)、instruction文言契約test、identity schemaVersion assert |
| AC-7 | validator/completer corpus test (4系統のfixture。Issue ACの直接対応) |
| AC-8 | 境界明文化 (本spec) + #329実装時の境界test (本spec受入れ時点では文言・順序図の固定のみ。#329側specへ相互参照を残す) |

## Decisions

- **D-1: 案C (partial authoring schema v3 + validator後completion) を採用する。** A (現行維持) は問題が解消しない。B (境界補完・schema無変更) は#204のimmutable semantic version規則に違反し、bump前提なら検証対象の純度 (軸iv) で劣る。
- **D-2: FIXED itemは省略可とし、omissionはmobilityによらず一律canonical unresolvedとする。** preserve値の代用にはしない (mobility条件付き補完は推測の一種であり、plannerはpreserveの指定を必要としない — FIXED保持はplanner authorityによる)。FIXED itemをexportから外す (案c) は透明性と単一正本を損なうため不採用。明示出力時の検証規則は無変更で、lock bypassは3層 (validator / planner authority / completer) で不可能なまま。
- **D-3: context/intentを同時にv3 bumpし、dual-version runtimeを持たない。** envelope形式・exchange固有補完層は不採用 (bump回避にならない、#206単一契約性を損なう)。保存済み文書は存在しない (永続化されるのはsessionのみで、sessionはintent schemaを保持しない)。v2 era session宛回答は `SCHEMA_MISMATCH` で再export案内 (spec 331と同じ移行扱い)。
- **D-4: completerをvalidator seam内の純粋関数とし、complete表現をPlannerへの唯一の入力とする。** authored文書は診断用のみ。`IntentPlannerAdapter` の消費semanticは無変更 (authored decisionのみ)。
- **D-5: identityはcomplete表現上で計算し、明示unresolvedとcanonical unresolvedを同一identityとする。** (semantic identity = 判断なし。provenance詳細はidentity外のdiagnostics field。)
- **D-6: bare entry (全semantic fieldがnullのitemIntents entry) はcompletionでcanonical unresolvedへ正規化し、明示unresolved / 省略 / bare entryの3表現を同一semantic identityとする。** identityをrefごとのsemantic内容のみの関数に限定し、「同じplanner効果 ⇒ 同じidentity」(dedupe / replay契約の実装非依存性) を保証する。bare entryを独立したauthored stateとしてidentity上区別する案は、planner効果が同一であるpayload群でidentityだけが分岐するため不採用。表現形式のprovenanceはdiagnostics用のauthored文書にのみ残る。

## Open questions (owner判断事項)

1. ~~**D-1〜D-6の受入れ**~~: **解消済み** — 2026-09-16のre-review (comment 5698414707) によりD-1〜D-6はaccepted。
2. **instruction文言の最終copy**: v3の部分authoringを説明するexchange package文言は、#327 (interview-first) のprompt再設計と合成される際に最終調整が必要である (本specは要求内容のみ固定)。実装PRでは要求内容を満たすcopyを入れる。

(旧Open question 3「bare-ref entryの扱い」は、review Required finding の対応として **D-6 として本specで固定** したため解消済み。)

## Change history

- 2026-09-16: Draft created for Issue #330。baseline `aab0d293d1` (origin/main、#331実装merge後) 上で起草。現行実装 (`IntentValidator.kt` のcoverage partition / mobility検証、`IntentCodec.kt`、`ExchangePackageComposer.kt` instruction、`IntentIdentity.kt`、`IntentPlannerAdapter.kt`、`AndroidExportSessionStore.kt`) とimplemented specs 204/205/331を確認し、Issue本文の4 design questionについて比較軸 (i)〜(vi) を固定してA/B/C・a/b/c比較とdraft decisions D-1〜D-5を起草。statusはdraft (owner受入れ待ち)。
- 2026-09-16: Re-entry (review Required finding対応)。Issue #330 review (comment 5698080251) のRequired「bare `{"ref":"X"}` の semantic identity を Spec で固定する」に対応し、旧Open question 3を **D-6** (bare entryのcanonical unresolved正規化・3表現同一identity。review提示の選択肢1) として確定した。根拠として現行plannerの全preference consumerが個別fieldのみを参照しall-null `ItemPreference` に効果がないことを `FullRunExecution.kt` で再確認。scenario 2件・AC-2/4/5・test oracle・Contract変更詳細を更新。baseline変更なし (`aab0d293d1` のまま)。statusは引き続きdraft。
- 2026-09-16: **Accepted** — re-review (comment 5698414707) によりD-1〜D-6が受入れされた (Required指摘なし)。statusをacceptedへ更新し、実装 (plan.md execution checklist) へ進む。

## References

- [Issue #330](https://github.com/nunu1733/NunuLauncher/issues/330)
- [Spec 204: AI personalization context/intent exchange contract](../204-ai-personalization-context-intent-contract/spec.md) (accepted・implemented。契約の正本)
- [Spec 205: external agent exchange](../205-external-agent-exchange/spec.md) (implemented。framing・失敗表示17種)
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md) (implemented。v2 bump・`SCOPE_MISMATCH`・scope binding gate)
- [Issue #329: Import Normalizer](https://github.com/nunu1733/NunuLauncher/issues/329) (framing正規化の正本。責務境界)
- [Issue #327: interview-first exchange](https://github.com/nunu1733/NunuLauncher/issues/327) (instruction文言の合成先)
- [Issue #206: Managed Grounded AI](https://github.com/nunu1733/NunuLauncher/issues/206) (単一契約consumer)
- [Issue #332: clipboard/file-first import UI](https://github.com/nunu1733/NunuLauncher/issues/332) (import UI)
- [DESIGN.md](../../DESIGN.md) gate 12/13、[CONTEXT.md](../../CONTEXT.md)「パーソナライゼーション意図」
