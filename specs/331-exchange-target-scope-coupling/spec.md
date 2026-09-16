---
issue: "#331"
status: accepted
requirements: [FR-017]
risk:
  - privacy
  - layout-data
updated: 2026-09-16
---

# External Agent Exchangeの対象scopeに未配置アプリ候補を含められる

> Status: **accepted** (2026-09-16) — 起草revision (head `defaf666bc`) へのChatGPT review "Changes requested" (Blocking 2点 + Required 1点、[Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/331#issuecomment-5694723284)) を解消した対応revision (head `41252343d4`) に対し、ChatGPT re-review **Approved (Blocking 0 / Required 0)** ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/331#issuecomment-5695018138)) を受けacceptedへ移行。status更新自体はadministrative変更であり、承認対象headは `41252343d4` のままである。実装はplan.mdのExecution checklistに従う。

## Problem

#228により、ユーザーは現在Homeに存在しないlaunchable appを選択し、既存placementと合わせて1つのOrganizer runの対象 (scope-composed organize) にできる。一方、#205で実装済みのExternal Agent Exchangeのcontext生成は、current workspace由来のfull organization input (`ProductionOrganizationInputComposer.composeFullOrganization()` 経由の `ExportInputs` 構築。`ExchangeInputAdapter.composeForExport`) のみを正本としており、**#228の選択状態はexchange flowに一切関与しない** (実装上も `ContextExportBuilder` は `snapshot.items` のみを対象とし、`TargetSet.additions` はexportに現れない)。

この結果、正規操作が以下の順序の場合:

```text
AIで傾向案を取得 (exchange exportは既存placementのみ)
  ↓
Intent import
  ↓
整理ロジック選択
  ↓
未配置アプリを選択 (#228)
```

後から追加された未配置アプリについてAIは一切判断しておらず、personalization scope (AIが判断した対象集合) と実際のOrganizer target scope (plannerが整理する対象集合) が不一致になる。AIは未配置candidateのpriority/groupingを提案できず、ユーザーはAI相談の価値を「既存配置の並べ替え」だけに限定される。

単純に画面順だけを入れ替えても、未配置candidateは既存workspace ItemIdを持たないため、現行のexchange ref / subject identity契約 (ref ↔ `ItemId` map、placed itemのみを対象とするmobility projection) では表現できない。

## Outcome

**AIへexportする対象集合を、そのrunで実際にorganizeするtarget scopeと一致させる。**

#228でユーザーが選択した未配置アプリも、既存placementと同様にprivacy-safeなexchange subject (export-scoped `ref`) を持ち、External Agentはcandidateを含むscope全体に対してpriority / grouping / page・region親和を判断できる。判断結果は既存のfail-closed import検証を通り、validated intentは #228のscope-composed runのplanner入力として消費される。AI相談を使わないOrganizer pathの操作は一切変わらない。

## Intended flow (基本形)

```text
1. Organizer対象scopeを決定
   - 現在Home上の対象 (既存placement)
   - #228で選択した未配置アプリ
        ↓
2. scope全体からAI exchange contextを生成 (runの選択flow内の導線)
        ↓
3. 外部AIと相談 (package送信 → 外部agent → 返答)
        ↓
4. Intent import (既存framing/validator経路、candidate ref拡張)
        ↓
5. strategy / planner (validated intentをscope-composed compositionへ投影)
        ↓
6. preview → confirm → apply (既存 #194/#195/spec 13 path、無変更)
```

AI相談前にtarget scopeが確定していることを基本形とする。scope確定後の選択変更はfail-closedなscope検証 (§3) で捕捉する。

## Required design

### 1. One canonical organization scope

Planner input、External Agent export、import検証が参照するscopeの正本を1つにする。

- **canonical composition pathの単一化**: export生成は、planner入力と同じcanonical composition経路 (`ProductionOrganizationInputComposer` / `DefaultOrganizationInputComposer` → `FullTargetSetMaterializer` + additions合成) から `ExportInputs` を得る。full organization (既存placementのみ) とscope-composed (既存placement + 選択済みcandidate) の2つのscopeが、**同一composition seamの2つのmode** として表現される。`composeFullOrganization()` 系のworkspace-only compositionと #228 selection stateが別々の正本になる構成は認めない。
- **exchange export生成の2つのentry**:
  - **Idle entry (既存、無変更)**: run非active時に生成。scope = full organization (選択済みcandidateを含まない)。export文書にcandidate itemは現れない。
  - **Run内 entry (新設)**: #228のmissing-app選択flow内でユーザーが明示的に選んだ場合に生成。scope = 現在の既存placement + その時点の選択済みcandidate。生成はread-only compositionで行い、workspace書込みは0件である (既存zero-write契約の継続)。
- 両entryとも、exportの対象集合・分類・mobility projectionの導出は同一のadapterとprojection述語を共有する (`SessionExportReconstructor` とのparity契約も含む)。生成時に選択されたcandidate集合はexport scopeの正本としてexport sessionに記録される (§3)。

### 2. Subject identity for not-yet-placed apps (candidate subject)

未配置candidateはworkspace placement / persistent itemを持たないため、subject identityを次のように確定する。

- **候補例からの選択**: 「stable app/profile identityから生成したprivacy-safe exchange ref」方式を採用する。candidateは既存placed itemと同一の乱数seam (`RandomIdAllocator`) からexport-scopedな `ref` を割り当てられ、sessionのref mapがref ↔ candidate安定identity (`ComponentKey` + `ProfileId`。planning modelの `CandidateTarget.AppKey` と同一規約) を対応付ける。run-scoped固有の新ID体系は導入しない (sessionがすでにref↔内部identity対応の唯一の保持者であるため、placedとcandidateの差は対応先の種別だけにする)。
- **export文書上の表現 (schema version bump)**: candidate itemはplaced itemと同じitems配列に現れ、per-itemの新field `subject` (closed enum `PLACED` / `CANDIDATE`) で区別される。`#204` のimmutable semantic version規則により、field追加・enum拡張は **schema version `personalization-context-v2` / `personalized-intent-v2` へのbump** で行う (D-1)。旧version文書はfail-closedで拒否される (本appはproducer/consumerが同梱のため単一versionのみを生成する。dual-version runtime supportは持たない)。
- **mobility**: mobility enumに `CANDIDATE` を追加する。candidateは「移動できない既存配置」でも「条件付き移動 (widget)」でもない「配置未作成のsubject」であり、`fixReason` を持たない (既存不変条件 `mobility==FIXED ⇔ fixReason!=null` は維持)。
- **candidate itemのprojection**: `kind` は `APP_OR_SHORTCUT`、`category` は既存policy authority (ADR-0007 bundle + user override) による解決済み分類 (#228 AC-13の継承。candidateはcomposition内で分類signal materializationの対象になる)、`usageSignals` は #203 bucket projectionの対象 (installed appであるため)。**現在配置を持たないため `pageAffinity` / `regionAffinity` / `groupSemantic` の現状projectionは含まない** (field省略)。label (app label) はprivacy tier制御に従う (placed itemと同一class扱い)。
- **privacy維持**: raw package名・component名・profile identifier・内部DB ID・candidate planning ID (`candidate-` prefixのsha256導出値) は、いずれのtierのexport文書にも現れない。candidate identity (`ComponentKey`+`ProfileId`) はexport session内 (app-private・backup対象外) のみに存在する。`subject` / `mobility` のenum値自体は個人情報を含まない。
- **決定性**: candidate itemの並び順は決定的である (placed itemの既存順序規則とcandidateの検出順序規則 (`MissingAppDetection` の (profile, label, component) 順) を合わせた全体順)。選択集合が同じなら、`ref` 値を除くexport構造は決定的である (`ref` は生成ごとの乱数であり、byte-determinismは要求しない — #204の既存規則どおり)。

### 3. Import validation / scope binding

AIが返せるrefは、そのrunで確定したscopeのsubjectのみとする。

- **coverage不変条件の継承**: `itemIntents` refs ∪ `unresolvedRefs` = export items全ref (placed + candidateを含む) の分割。candidate refもcoverage対象になる。
- **selected missing app**: valid subject。import検証・planner投影の両方で解決される。
- **detected but user未選択のmissing app**: exportに現れないため、AIがref指定しても **`UNKNOWN_REF` でreject** (既存fail-closed)。out of scopeを表す新失敗classは設けない。
- **scope確定後に新たにinstallされたapp**: out of scope。export/sessionに存在せず `UNKNOWN_REF` になる。**その存在自体はscope無効化の原因にしない** (インストールだけでは配置・分類・選択のいずれも変化しないため。homeへ配置された場合は下記の構造変化検出で捕捉される)。
- **stale scopeの扱い (3段)**:
  1. **配置構造の変化** → 既存structural `sourceContextDigest` 照合 (`CONTEXT_STALE`)。placed item側のdigest定義はv1から変更しない (D-4)。candidateがflow中にhomeへ配置された場合も、配置構造変化としてここで捕捉される。
  2. **candidate scopeのfreshness (新設、D-4のcandidate投影digest)**: export時のcandidate投影 (安定identity + availability + 解決済み分類) に対するcanonical digestをsessionに記録する。binding時 (選択確定後のcomposition) に同一手順で再計算して照合し、不一致 (分類authorityの変化・availability変化を含む) は `SCOPE_MISMATCH` でzero-write rejectする。これにより「AIが見たcandidate分類」と「plannerが使うcandidate分類」の乖離がimport/binding段階で受理されない (#204 source binding不変条件のcandidate側への拡張)。
  3. **選択集合の不一致 / candidate無効化** → **scope binding gate (新設、D-5のtyped失敗 `SCOPE_MISMATCH`)**: validated intentをrunに適用する時点 (scope確定点 = #228選択確定 + composition) で、runの選択集合がsession記録のexport scope candidate集合と **完全一致** し (D-2)、各candidateが依然解決可能 (installed / launchable / AVAILABLE / 未表現) であることを検証する。欠落・追加のいずれも、およびcandidate解決不能 (uninstall / disable等) も `SCOPE_MISMATCH` (cause detail付き) としてzero-write失敗し、再exportを案内する。
- **完全一致 (equality) を採用する理由 (D-2)**: 本Issueの成果は「AIへexportした対象集合 = そのrunで実際にorganizeするtarget scope」である。export後の候補 **追加** を許すと、AIが判断していないcandidateが同一intent下でorganizeされ、問題文のscope不一致を再現するため許容しない。process death後の再選択は、選択surfaceがexport対象件数を案内表示する (自動選択はしない — #228 D-1のunchecked-by-default維持) ことで同一集合の再現を支援し、再現できない場合は再export (run内entry) に戻る。再exportは安価であり、scope不一致の曖昧な許容よりfail-closedを優先する。
- **単一scope正本の検証可能性**: 同一選択集合からの生成は同一のscope内容と投影digestを持ち、選択集合・分類・availabilityのいずれかが変わればsession記録が変わること (staleなscope記録の再利用禁止) をcontract testで固定する。

### 4. Mobility / creation semantics (candidateへのintent適用policy)

未配置appは「現在位置を維持する」対象ではないため、semantic fieldごとの適用policyをtypedに確定する。

| intent field (candidate ref宛) | policy | 根拠 |
|---|---|---|
| `preserve` (per-item保持希望) | **reject** — `MOBILITY_CONTRADICTION` (適用条件の拡張) | candidateは現在配置を持たず、保持対象が存在しない |
| `importance` | 許可 — Add配置の順序付け偏好 | placement非依存signal |
| `desiredGroup` / `groupSemantic` | 許可 — 既存strategyのfolder placement semanticsを通じたcohesion偏好 | placement非依存signal。**intent由来の新folder機構は導入しない** (#204既存規則の継承) |
| `pageAffinity` / `regionAffinity` | 許可 — allocatorの対象page/region選択のsoft ordering hint (target preferenceとして解釈) | candidateは現状affinity projectionを持たないが、intent側の値は配置先への偏好である |
| `unresolvedRefs` への明示 | 許可 | coverage機構 (diagnostic、authority無し) |
| `globalPreference.minimizeMovement` | 従来どおり許可 (placed itemの移動最小化) | global preferenceはplaced item意味論のまま |

- `MOBILITY_CONTRADICTION` の適用条件表を拡張する: `FIXED` なrefへのsemantic field (既存) に加え、`CANDIDATE` なrefへの `preserve` をrejectする。failure class自体は増やさない (schema内semantic fieldとmobilityの矛盾という既存分類の内側)。
- planner投影は既存 `IntentPlannerAdapter` の拡張として行う: candidate refはsession ref map経由でcandidate planning IDへ解決され、`OrganizationInput.intentPreferences` のpreferenceがadditions (`CandidateItem`) 宛に消費される。plannerの保持判断・constraint・run mode・`TargetSet` 意味論は一切弱めない (#204「plannerが正本」の継承)。
- **intent消費runへ未export候補が参加する経路は存在しない**: scope binding gate (§3、完全一致) により、validated intentを消費するrunのcandidate集合は常にexport scopeと同一である。分類変化・availability変化もcandidate投影digest照合で捕捉される。

### 5. Flow ordering / UX

#228 selectionをAI相談より後に置く現行導線を解消する。AI未使用pathに摩擦を追加しない。

- **Idle entryの維持**: run非active時のexchange生成・import導線 (#205既存) は無変更。scope = full organization (candidate集合は空)。import後のfresh run再構築 (再選択を含む) も無変更。ただしidle宛intentを消費するrunでも、選択確定時のscope binding gate (完全一致、candidate集合∅との一致) が適用される: **idle export後にmissing appを選択して確定した場合、`SCOPE_MISMATCH` でzero-write失敗し、選択後のrun内entryで再exportすることを案内する** (AIが見ていないcandidateを同一intent下でorganizeさせない)。候補を選択しない従来flowは従来どおり成立する。
- **Run内 entry (新設)**: missing-app選択surface内に、ユーザーが明示的に開始するexchange導線を置く:
  1. ユーザーが候補を選択し、exchange生成を明示選択する。
  2. **選択の固定 (freeze)**: 生成開始からexchange stepの完了または中止まで、選択状態の編集を無効化する (scopeの確定性を保証する基本形。中止すれば編集可能に戻る)。生成はread-only compositionで行われる。
  3. privacy mode選択・session置換確認・Pre-send Disclosure・transport (#205既存契約、無変更) を経て外部AIへ渡す。
  4. 戻ってきた返答は同じsurfaceからimportし、validated intentを **当該runへ接続** する (fresh runを開始しない。runは依然選択状態を保持しているため)。import失敗時は既存typed失敗表示でzero-write。
  5. exchangeを中止した場合、未送信packageは既存取消規則 (`ExportSessionStore.invalidate`) で失効し、選択編集に戻る (AI未使用pathと同一の継続)。
  6. 選択確定 (confirm) 時にscope binding gateを評価し (完全一致 + candidate投影digest照合)、通過すればcomposition → planning (intent投影込み) へ進む。既存flowからの追加必須stepは存在しない (exchangeを使わない場合はこの導線が現れない)。
- **process deathを跨ぐ場合**: run状態は非永続 (既存不変条件) のため消失する。ユーザーはidle import → fresh run再構築 (既存 #205 semantics) → 再検出・再選択 → confirm時のscope binding gate、の順で復帰する。選択surfaceは **export対象candidateの件数を案内表示** する (自動選択はしない)。再選択がexport集合と完全一致すればintentは有効であり、一致しない (欠落・追加のいずれも) 場合は `SCOPE_MISMATCH` でzero-write失敗し、再exportを案内する。
- **deterministic Organizerへの影響なし**: exchange導線はすべてユーザー明示開始・中止可能であり、scope selection画面の既存機能 (検索・bulk操作・選択数表示) とrunの既存phase遷移は変更しない。

## Non-goals

- 未選択installed appをAI判断だけで自動追加すること (coverage/`UNKNOWN_REF` とscope gateで構造的に禁止され続ける)。
- AIによるHome-worthinessの自動決定、AI発の新規scope追加。
- raw package / component / profile identifierのexport (既存privacy方針の継続)。
- #228のAdd transaction safety実装そのものの変更 (materialize / availability再検証 / recovery契約は無変更)。
- user confirmationなしのscope追加。
- v1/v2のdual-version runtime support (単一version生成・旧version fail-closed拒否のみ)。
- #206 managed AI pathへの展開。
- 既存run modeの追加・既存strategy意味論の変更。

## 正本更新 (同一PR群で実施)

- `specs/204-.../spec.md`: schema version v2 (`subject` field、`Mobility.CANDIDATE`、failure class `SCOPE_MISMATCH` 追加)、session ref mapのcandidate対応をChange historyへ記録 (契約拡張の正本は本spec)。
- `specs/205-.../spec.md`: 「#228 scope selectionはrun内概念でありexchange flowで関与しない」の規定を本specによる意図的変更として更新、run内entry・17種失敗表示へ反映。
- `CONTEXT.md`: 新用語 (候補subject、scope binding gate) を受入時に反映。
- `DESIGN.md`: gate 12/13行へ本specへの参照を追加。

## Domain language

`CONTEXT.md` への追加用語案 (受入時に反映)。

**候補subject (candidate subject)**:
External Agent Exchangeのexportにおいて、現在Homeに配置されていない未配置アプリ候補を表すexchange subject。placed itemと同一の乱数seamによるexport-scoped `ref` を持ち、`subject: CANDIDATE` とmobility `CANDIDATE` で区別される。内部対応先はcandidate安定identity (`ComponentKey` + `ProfileId`) であり、raw identifierはexport文書に現れない。
_Avoid_: 仮配置 (配置の作成を示唆する)、新規アイテム (Add行というplan表現と混同)

**scope binding gate (scope束縛検証)**:
validated intentをorganizer runへ適用する時点で、runの確定した対象scopeのcandidate集合がexchange exportの対象scopeと完全一致し、各候補の投影 (identity + availability + 解決済み分類) がexport時と一致することを検証するfail-closedな検証step。違反はtyped失敗 `SCOPE_MISMATCH` としてzero-write処理される。
_Avoid_: staleチェック (配置構造変化の検出とは別段)、再検証 (availability再検証と混同)

## Behavior scenarios

### Scenario: 選択済み候補を含むscopeのexport生成 (基本形)

Given ユーザーがmissing-app選択surfaceで40候補のうち12件を選択した,
When ユーザーがexchange生成を明示選択する,
Then 選択状態は固定され (編集無効)、read-only compositionにより「既存placement + 選択済み12候補」のscopeからv2 exportが生成され、12候補はそれぞれ `subject: CANDIDATE` / `mobility: CANDIDATE` のexport itemとして現れ、
And 選択されなかった28候補はexportに現れず、生成から送信前確認までの全段階でworkspace書込みは0件である。

### Scenario: candidate宛のintentがplanner・previewまで消費される

Given candidate (Spotify) を含むscopeでexport・相談が行われ、intentがSpotifyのrefに `importance: HIGH` とgrouping希望を返した,
When 返答がimportされ、選択確定・scope binding gate通過後にplan生成が行われる,
Then Spotifyは選択済み候補として既存strategy意味論で配置され、importance/grouping preferenceがAdd配置の順序付けとcohesion偏好として消費され、
And previewはSpotifyを `AddChange` 行として表示し (spec 228契約)、確認・適用pathは既存のままである。

### Scenario: 未選択候補へのref指定はrejectされる

Given 選択surfaceで12件のみ選択してexportした (検出済み40件の残り28件は未選択),
When AIが未選択候補に対応する内容のrefをintentに含めてimportされる,
Then 当該refはexport/sessionに存在しないため `UNKNOWN_REF` でzero-write rejectされ、
And 提案・適用のいずれにも未選択候補のcreate mutationは現れない。

### Scenario: candidateへのpreserve指定はtyped rejectされる

Given exportにcandidate ref (mobility `CANDIDATE`) が含まれる,
When AIが当該refに `preserve` を指定してimportされる,
Then `MOBILITY_CONTRADICTION` でrejectされ (partial applyなし)、
And 同じrefへの `importance` / `desiredGroup` / `pageAffinity` のみの指定 (または `unresolvedRefs` 明示) は受理される。

### Scenario: 選択変更後のscope binding gate

Given export scopeに候補A・B・Cが記録されている,
When process death後にユーザーが再選択でA・Bのみを選んで確定する,
Then scope binding gateが集合不一致を検出し、`SCOPE_MISMATCH` としてzero-write失敗し、再exportが案内される,
And A・B・Cに追加でDを選んで確定した場合も `SCOPE_MISMATCH` で失敗する (AIが判断していない候補を同一intent下でorganizeさせない),
And 再選択がA・B・Cと完全一致した場合のみ、intent preferenceが有効なままplanningへ進む。

### Scenario: 往復中のcandidate分類変化はcandidate投影digestで捕捉される

Given 空workspaceで候補Aを含むscopeがexportされ、Aの解決済み分類はXでsessionに記録されている,
When 外部AI相談の間にAの分類authorityが (override等により) Yへ変化し、配置には一切変化がなく、返答がimportされて再選択・確定される,
Then candidate投影digestの再計算 (identity + availability + 解決済み分類) がsession記録と不一致となり、`SCOPE_MISMATCH` でzero-write rejectされる (placed側のstructural digestは不変であるため `CONTEXT_STALE` は発生しない)。

### Scenario: 往復中のcandidate無効化

Given export scopeに候補Aが含まれる,
When 外部AI相談の間にAがuninstallされ、返答がimportされる,
Then candidate解決検証がAを解決不能と判定し、`SCOPE_MISMATCH` (candidate無効化cause) としてzero-write rejectされる。

### Scenario: 往復中の構造変化は既存検出で捕捉される

Given export生成後にユーザーがhomeで配置変更した,
When candidateを含むsession宛の返答がimportされる,
Then 既存structural `sourceContextDigest` 照合 (`CONTEXT_STALE`) でrejectされる (digest定義はv1から無変更)。

### Scenario: 空workspace + 選択済み候補のみでAI Exchangeが成立する

Given Home上にapp配置が0件で、ユーザーが20件の候補を選択した,
When export生成 → 外部AI相談 → import → 確定 → plan生成 → previewを行う,
Then export itemsは20件のcandidate subjectのみからなり、coverage不変条件がこれらのref分割として成立し、plannerは空 `existing` + 20件の `additions` (intent preference込み) からplanを生成し、previewが表示される。

### Scenario: AI未使用pathの無変更

Given ユーザーがexchange導線を一度も開始しない,
When 選択surfaceで選択・確定し、plan → preview → confirm → applyまで進める,
Then 表示される導線・操作・状態遷移は本機能導入前と同一であり、追加の必須step・確認は存在しない。

### Scenario: idle entryの無変更とgate適用

Given ユーザーがrun非active時にidle entryでexchange生成した (scope = 既存placementのみ、candidate集合∅),
When 返答をidle importしてfresh runを開始し、missing-app候補を選択せずに確定する,
Then export scopeのcandidate集合 (∅) と選択集合が一致するためscope binding gateは通過し、intent preferenceは既存placement宛のみに消費される (既存 #205 fresh run semanticsの継続),
And 同じflowで候補Aを選んで確定した場合は `SCOPE_MISMATCH` でzero-write失敗し、選択後のrun内entryでの再exportが案内される。

## Data and state

- **読むdata**: run内entryは #228の選択状態 (process-local) とcanonical composition (既存placement + additions + 解決済み分類 + lock/availability状態 + #203 signal snapshot)。idle entryは既存full-organization composition。両者ともread-only。
- **export session (v2拡張)**: 既存 #204 session modelに、ref mapの対応先としてcandidate安定identity (`ComponentKey`+`ProfileId`) を追加許容し、export scopeのcandidate投影digest (D-4: 安定identity + availability + 解決済み分類のcanonical serializationに対するdigest) を記録する。sessionのdurable性・TTL 24時間・app-private・backup対象外・single-active-session・user作成自由文を含まない、の各既存契約は無変更。
- **sourceContextDigest (placed側)**: 定義はv1から無変更 (配置構造のみ)。candidate側のfreshnessはcandidate投影digest (session記録) が担い、両者は別契約として分離される (D-4)。
- **選択状態の非永続**: #228 D-1 (process-local非永続) は無変更。freezeはUI状態であり、永続化しない。
- **migration / backup / DB**: 新規DB書込経路・schema migrationなし。既存run・既存exchange flowとの互換性は「v1文書のfail-closed拒否」のみ (session storeの旧recordはTTL内でもv1 schema識別によりimport不可 — 24時間以内の連続利用で影響は一時的)。
- **永続化の分担**: #205既存規則の継承 — package本文・返答text・選択状態は永続しない。durable保持はexport sessionのみ。

## Permissions, privacy, and security

- 追加permission・network・telemetryなし。export文書の外部送信は既存Pre-send Disclosure経由のみ。
- candidate itemが追加されることで新たに外部へ出る情報: candidateのapp label (tier制御対象)、category、usage bucket、`subject`/`mobility` のenum値。label-inclusive mode選択時の明示開示文言はcandidateを含む旨をカバーする。raw identifier・candidate planning IDは含まれない (contract testで検証)。
- 脅威モデルは #204/#205の継承: candidate宛intentもallow-list・closed enum・coverage・mobility検証を通過するまで受理されず、planner制約は不変。injection corpusにcandidate itemを追加する。

## Accessibility and localization

- 選択surface内のexchange導線・freeze状態・import失敗 (`SCOPE_MISMATCH` を含む17種) の表示はTalkBack読み上げ、Switch Access操作、large font対応を必須とする。
- 追加stringsは `values/` + `values-ja/` 両方へ置く (spec 123契約)。freeze中の状態変化はstatus行としてannounceする。

## Acceptance criteria

- [ ] AC-1: run内entryのexport生成が、既存placementと選択済みcandidate全体を対象とし、各選択済みcandidateが `subject: CANDIDATE` / `mobility: CANDIDATE` のexport itemとして含まれる。未選択candidateはexportに現れない。生成・確認の全段階でworkspace書込みが0件である。
- [ ] AC-2: export生成 (idle/run内両entry) とplanner入力が、同一のcanonical composition seamから導出される単一scope正本を持つ。同一選択集合から同一のscope内容が得られ、選択集合の変化がscope内容 (session記録を含む) を変えることがcontract testで検証される。export生成・import再構築・plannerの間でprojection driftが構造的に起こらない (parity test拡張)。
- [ ] AC-3: candidate subject identityがprivacy-safeである: export-scoped乱数 `ref`、session内のcandidate安定identity対応、`subject`/`mobility` enumによる文書上の区別。いずれのprivacy tierのexport文書にもraw package名・component名・profile identifier・内部DB ID・candidate planning IDが現れないことがcontract testで検証される。
- [ ] AC-4: 未選択candidateへのref指定が `UNKNOWN_REF` でzero-write rejectされる。scope確定後の新規install appも同様にout of scopeである (ref不在)。
- [ ] AC-5: candidate宛intent fieldのpolicyがtypedに定義され検証される: `preserve` → `MOBILITY_CONTRADICTION`、`importance` / `desiredGroup` / `groupSemantic` / `pageAffinity` / `regionAffinity` / `unresolvedRefs` は受理。受理されたpreferenceがplannerでadditions宛に消費され (順序付け・cohesion・allocator hint)、plannerの保持判断・constraint・strategy意味論が弱められない。intent由来の新folder機構が存在しないことが検証される。
- [ ] AC-6: scope変更/stale importの検出が定義どおり動作する: 配置構造変化 → `CONTEXT_STALE` (placed digest無変更)、candidate分類・availabilityの変化 → candidate投影digest照合による `SCOPE_MISMATCH` (空workspaceでcandidate分類のみが変化する場合を含む)、選択集合の欠落・追加のいずれも → `SCOPE_MISMATCH` (zero-write、再export案内)、candidate無効化 → `SCOPE_MISMATCH` (candidate無効化cause)。`SCOPE_MISMATCH` が新typed failure classとして #204 taxonomy (13 class) に追加され、UI失敗表示が17種対応する。
- [ ] AC-7: 空workspace + 選択済み候補のみで、export生成からimport・planner・previewまでが成立する。
- [ ] AC-8: mixed existing + missing appsのprompt → import → planner → preview統合testがある (placed itemとcandidateの両方にpreferenceが適用され、previewが `AddChange` / `MoveChange` を区別して表示する)。
- [ ] AC-9: AI未使用pathの操作・状態遷移が本機能導入前と同一である (regression contract)。idle entryの生成・import・fresh run再構築も既存契約どおりであり、candidate集合∅のsession宛idle importにscope binding gate (完全一致) が適用され、idle export後のcandidate選択確定は `SCOPE_MISMATCH` でfail-closedになる。
- [ ] AC-10: schema versionが `personalization-context-v2` / `personalized-intent-v2` へbumpされ、v1文書が `SCHEMA_MISMATCH` でfail-closed拒否される。candidate項目を含むexportが #204 content limits (items上限・byte上限) の内で生成され、超過は生成時typed失敗である。
- [ ] AC-11: exchange生成開始から確定までの選択freeze、中止時の編集復帰と未送信session失効、process death後の再選択 → gate経由の復帰が動作する。
- [ ] AC-12: 新規UI (run内entry・freeze・17種失敗表示) のa11y evidenceとja/en strings解決がある。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | `ContextExportBuilder` 拡張のunit test (candidate item生成・未選択候補の不在・zero-write) + 選択surfaceのinstrumentation test |
| AC-2 | composition/adapterのcontract test (scope内容の同一性・選択変化の伝播・candidate投影digestの変化伝播) + `SessionExportReconstructor` parity test拡張 (candidate entry、placed構造parity) |
| AC-3 | privacy contract test (両tierでのraw identifier不在走査、`subject`/`mobility` 値の検証、session内対応の検証) |
| AC-4 | validator unit test (`UNKNOWN_REF`、未選択候補ref corpus) |
| AC-5 | validator unit test (`MOBILITY_CONTRADICTION` 拡張条件・受理field corpus) + planner unit/property test (additions宛preference消費、既存strategy意味論・保持判断の不変) |
| AC-6 | pipeline unit test (3段stale検出の分類) + scope binding gate unit test (完全一致Pass/欠落/追加/無効化/投影digest不一致・分類変化) + 失敗表示UI test (17種) |
| AC-7 | 空workspace統合test (export → import → plan → preview、決定的) |
| AC-8 | mixed workspace統合test (preference適用・preview区別) |
| AC-9 | 既存 #205/#228 test suiteのregression実行 + idle entry/gate適用のunit test |
| AC-10 | schema version bumpのcontract test (v1拒否・v2受入、content limits) |
| AC-11 | run flow統合test (freeze/中止/失効/process death模擬 → 再選択 → gate) |
| AC-12 | a11y assertion (unit/instrumentation) + ja configurationでのstring解決test |

CI class filter (`ci.yml` connected-test lanes) への新instrumentation test class追加を実装PRで行う。

## Decisions

- **D-1 schema version bump: `personalization-context-v2` / `personalized-intent-v2`** — #204のimmutable semantic version規則 (field変更・意味変更は `-v2`) に従う。`subject` field追加と `Mobility.CANDIDATE` 拡張がv1文書の意味を変えるため。intent文書のfield自体は無変更だが、candidate宛検証semanticsの変更と対にするため両者を同時にbumpする。dual-version runtime supportは持たない。
- **D-2 scope binding規則: 完全一致 (equality)** — validated intentを消費するrunのcandidate選択集合は、export scopeのcandidate集合と **完全一致** しなければならない。欠落・追加のいずれも `SCOPE_MISMATCH` である。AIへexportした対象集合と実際にorganizeするtarget scopeの一致が本Issueの成果であり、export後の候補追加を許す包含規則は (AI未判断のcandidateが同一intent下でorganizeされ) 問題を再現するため採用しない。process death後の再選択は件数案内表示で支援し、再現不能な場合は再exportとする (再exportは安価)。
- **D-3 run内entryの位置: missing-app選択flow内・選択freeze付き** — scopeの確定性を基本形 (AI相談前に確定) として保証し、中止時は既存AI未使用pathへ無摩擦に復帰する。idle entryは無変更のまま両立させる (idle宛intentのrun消費にもgateが適用される)。
- **D-4 placed側digestはv1から無変更 / candidate側はcandidate投影digestで補完** — import時のplaced digest照合はrun無しで成立する必要があるため (idle import・process death後)、配置構造のみのv1定義を維持する。candidateはItemIdを持たずplaced digestに表現されないため、candidate投影 (安定identity + availability + 解決済み分類) に対するsession-localなcanonical digestを新設し、binding時に再計算照合する。これによりcandidate分類authorityの変化 (placed itemのない空workspaceでも) がfail-closedに捕捉される。1つのdigestへの統合は、placed digestのv1互換定義変更を伴うため採用しない。
- **D-5 新typed failure class `SCOPE_MISMATCH` (単一class・cause detail付き)** — 選択集合不一致、candidate無効化 (uninstall/disable等の解決不能)、candidate投影digest不一致 (分類・availability変化) を1つのclassに統合し、内部cause detailとUX文言で区別する。複数classへの分割は失敗表示面の増加に比して救済actionの区別が小さいため採用しない。#204 taxonomyを12 → 13 classへ拡張する (v2)。UI失敗表示は17種 (13 + `FRAMING_*` 3種 + `INPUT_OVERSIZE`)。

## Open questions (non-blocking)

1. **freeze中のUI文言・再export案内の具体copy**: 構造・必須要素は本specで固定。微調整は実装PR / a11y evidenceで行う。
2. **candidate宛preferenceのplanner消費詳細** (importance順序の同点tie-break等): plannerが既に持つpreference消費機構 (`FullRunExecution` / `PlacementAllocator`) の拡張としてplanで確定する。新機構の導入は本specの範囲外。

## Change history

- 2026-09-16: Draft created for Issue #331。baseline `4f555450bd` (origin/main) 上で起草。#228 (implemented)、#204 (accepted・実装済み)、#205 (implemented) の契約と実装 (`ContextExportBuilder` が `snapshot.items` のみ対象、`ExchangeInputAdapter.composeForExport` が `composeFullOrganization()` 経由、exchange導線はIdle/Cancelled時のみ提示、import はfresh run再構築) を確認し、Issue 331の5つのrequired design (canonical scope、candidate subject identity、import validation、mobility/creation semantics、flow ordering) をD-1〜D-5として確定して起草。
- 2026-09-16 (2nd): ChatGPT review "Changes requested" ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/331#issuecomment-5694723284)、head `defaf666bc` 基準、Blocking 2点 + Required 1点) への対応revision。**Blocking 1 (scope binding規則)**: D-2を包含 (⊇) から **完全一致 (equality)** へ変更 — export後の候補追加を許すとAI未判断のcandidateが同一intent下でorganizeされ、本Issueの問題 (export scopeとrun scopeの不一致) を再現するため。idle export後のcandidate選択確定も `SCOPE_MISMATCH` でfail-closedとし (idle entry scenario、AC-9更新)、process death後の再選択は件数案内表示 (自動選択なし) で支援。**Blocking 2 (candidate構造のfreshness)**: candidate分類・availabilityの変化がplaced digest (空workspaceでは不変) で捕捉できない問題に対し、candidate投影digest (安定identity + availability + 解決済み分類のcanonical digest) をsessionに記録しbinding時に再計算照合する設計へ変更 (D-4改訂、新scenario追加)。placed側digestのv1定義は無変更。**Required 3 (taxonomy統一)**: `CandidateUnresolved` の独立class導入を止め、`SCOPE_MISMATCH` 単一class (cause detail付き: 選択集合不一致 / candidate無効化 / 投影digest不一致) に統合し、17種表示計算を13 + 4で整合。AC-6を3段検出の具体条件へ更新。
- 2026-09-16 (3rd): **accepted**。ChatGPT re-review **Approved (Blocking 0 / Required 0)** ([Issueコメント](https://github.com/nunu1733/NunuLauncher/issues/331#issuecomment-5695018138)、head `41252343d4c31791ae70f00832ad36296f15e477` 基準) を受けstatusをdraft → acceptedへ移行。status更新はadministrative変更であり承認対象headを変更しない。実装開始。

## References

- [Issue #331](https://github.com/nunu1733/NunuLauncher/issues/331)
- [Spec 228: organizer missing-app selection](../228-organizer-missing-app-selection/spec.md) (scope-composed run、`CandidateTarget.AppKey`、`CandidatePlanningIds`、選択非永続D-1)
- [Spec 204: AI personalization context/intent exchange contract](../204-ai-personalization-context-intent-contract/spec.md) (schema version規則、mobility projection、12 failure class、export session)
- [Spec 205: external agent exchange](../205-external-agent-exchange/spec.md) (exchange framing、Pre-send Disclosure、session置換、fresh run再構築)
- [Spec 194 / 195: plan preview seam / confirmation change list](../194-plan-preview-seam/spec.md) (preview/confirm path、`AddChange`)
- [Spec 182: layout strategy catalog](../182-layout-strategy-catalog/spec.md) (planner seam)
- [ADR-0007: authoritative organization policy sources](../../docs/adr/0007-authoritative-organization-policy-sources.md)
- [AGENTS.md](../../AGENTS.md), [DESIGN.md](../../DESIGN.md), [CONTEXT.md](../../CONTEXT.md)
