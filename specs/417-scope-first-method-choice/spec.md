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
> （D-04, D-05, **D-06**, D-17, §5.1 T-07/T-08, §5.2 選択面からのrun-in AI相談, §5.3 canonical順序。
> D-16は対象外でContinue）、accepted disposition
> [docs/product/organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)
> （§5 supersession mapへの#417行追加）。
> 本specが改訂を要求する既存spec: [spec 369](../369-run-display-integration/spec.md)（RD-3・D-06節・状態対応表・0候補scenarioを本文レベルでAmend）,
> [spec 372](../372-ai-consultation-request-flow/spec.md)（T-07二択・idle相談flowの入口。Amend/Supersede標記）,
> [spec 331](../331-exchange-target-scope-coupling/spec.md) §5（idle entry / run-in entryの二入口構造。
> scope binding gateの契約は不変）,
> [spec 367](../367-organizer-materials-relocation/spec.md)（維持されるsecondary entryの記述。Supersede標記）,
> [spec 204](../204-ai-personalization-context-intent-contract/spec.md)（export sessionへのscope origin追加と、session storeへのfailure-aware invalidation primitive追加。Amend）,
> [spec 374](../374-durable-imported-intent/spec.md)（取り込み済み提案の消失原因へのscope-bound破棄追加。Amend）,
> [spec 375](../375-scope-remedy-rebind/spec.md)（RUN_INを「durable provenance」と「生存runへのdirect attach authority」の2軸へ分離し、scope-bound破棄へexchangeMutationGateを適用。Amend）。
> 維持する契約（1行も緩めない）: [spec 228](../228-organizer-missing-app-selection/spec.md) D-1,
> [spec 331](../331-exchange-target-scope-coupling/spec.md) D-2/D-4/D-5,
> [spec 348](../348-exchange-ai-facing-contract/spec.md), [spec 327](../327-agent-exchange-interview-first/spec.md),
> [spec 53](../53-onboarding-organization-proposal/spec.md) / [spec 370](../370-onboarding-hub-connection/spec.md)（D-16固定経路）,
> [spec 375](../375-scope-remedy-rebind/spec.md) のrebind 1経路・完全一致gate・fail-closed。
> 本specは[Issue #417][1]の成果物である。

## Problem

現行Organizerでは、AI/非AIの方法選択（T-07前置き面）が候補検出・対象選択より先に現れる。このため:

- 未配置アプリをAIの依頼へ含めるには、ユーザーは先に「そのまま整理」でrunへ入り、選択面を経てからrun-in AI相談を開く必要がある。方法を選ぶ前にscopeの形成を強制される。
- 同じ「AIに相談」に、scope確定前のidle相談（配置済みのみexport）とscope確定後のrun-in相談（選択済み候補をexport）の2つの入口が併存し、AIが見る対象集合が入口timingで変わる。
- 選択面の編集可否が、別state machineの隠れ条件（Exchange画面が`Closed`でないこと）だけで凍結され、「なぜ編集できないか」が画面上の作業状態から説明できない。フィールド観測では、候補が見えているのに個別チェック/すべて選択が無効な中間状態が発生した。
- run-in AI入口は選択0件をgateしておらず、未確定の選択のままAI相談へ入れ、その場で選択全体が凍結される。
- import試行の`entryKind`（IDLE/RUN_IN）が「import時点のrun state」から推定されるため、依頼の由来（scope選択ありで作られたか）とrecordが乖離する経路が存在する。

原因は文言ではなく、scope形成・方法選択・RUN lease・Exchange state・selection freezeが別々のstate machineとして同一surfaceで交差する構造にある。

## Outcome

1回の（手動開始の）整理について、**対象scopeを先に確定し、その同一scopeに対してAIを使うか使わないかを選ぶ**単一フローになる。ユーザーは候補検出→対象選択→scope確定を終えてから「このまま整理 / AIに相談」を選び、どちらの肢も同じ凍結scopeを消費する。AIだけが別のscope形成timingを持つことはなく、選択の凍結は「AI依頼が確定scopeを参照している間」というユーザー可視の理由で説明される。依頼の由来provenanceはsession自身が保持し、入口やtimingに依存しない。

## Scope

- 正規journeyの順序変更（**manual triggerのrunに限定**）: 入口（方法選択を含まない）→ run admission → 候補検出 → 対象選択（候補あり時）→ **scope確定（凍結）** → **方法選択面「整理案の作り方」** → 同一scopeでcapture/plan。onboarding提案（D-16固定経路・`ONBOARDING_PROPOSAL`）は現行どおり方法選択を経ずplanningへ直行する。
- 方法選択面の新設: 「このまま整理」（deterministic planner）と「AIに相談」（scope凍結後の依頼作成〜取り込み）を兄弟分岐として同一面に提示する。既存run-in scoped exchange flow（T-15〜T-18の契約）を、この面から開く形へ再配置する。
- idle AI相談（run外・pre-run request）の**新規作成**入口を廃止する（Retire）。entry面のexchange hostingは「既存active依頼の状況表示と回答取り込み」のみを提供するimport-only面となり、既存のdurable依頼・取り込み済み提案のimport・再開契約（#374/#375、IDLE由来のunchecked復元を含む）は維持する。
- **import provenanceとattach authorityの2軸分離**: (軸1) export sessionにscope origin（その依頼がscope選択ありのrun内で作られたか: `IDLE` / `RUN_IN`）をdurableに保持する（additive拡張）。`entryKind` の正本はこのdurable originであり、実行時のrun state推定は廃止する。(軸2) 生存runへのdirect attach authorityは、process-localな「このrunの`ScopeConfirmed`で生成したexact exportId」の束縛でのみ成立し、scope一致だけでは同一runと判定しない。束縛の正本はactive operation（`ScopeConfirmed`）が保持するprocess-localな`boundExportId`であり、生成開始時にrunId＋凍結scope identity＋generation attemptをsnapshotする。bindは二段階commitとする: 生成はsession保存を`ExchangeMutationGate`内で完了してgateを解放した後、run lockを取得し、既存#375と同じ正順（run lock → `ExchangeMutationGate`）でgateを再取得してactive sessionをfresh readし、「同一active operationかつ同一`ScopeConfirmed`かつ同一generation attempt、かつ読み直したsessionのexportIdが生成結果と一致」の場合にのみbindする。保存後〜bind前にreplacement/invalidationが割り込んだ場合、fresh readの結果としてstaleなexportIdはbindされない。全経路でこの正順を固定し、gate保持中にrun lockを取得する経路は存在しない。選択面への復帰・中断・run終了・scope-bound依頼破棄で束縛をclearし（破棄はrun lockを外側に置き、gate内の`invalidateIf`が`Committed`/`NoMatch`の場合だけ同じrun critical sectionでclear）、置換生成では旧束縛を置換する。遅延して到着した古い生成結果（stale settle）が後続runへ束縛を付与することはない。live ownerが存在する場合のみowning-run fence＋`attachIntent`を使い、ownerを欠くRUN_IN originの取り込み（process死後のhub経由等）はRUN_IN pendingとしてdurable保存され、その保存成功後の面はdirect-attach用の取り込み成功状態ではなく既存の取り込み済み提案の確認面（ImportReview、既存reconcile契約で読み直し）へ切り替わる。継続は `Hub → ImportReview` rebind 1経路に限定され、新規のrebind/start seamは作らない。加えて、方法選択面からの取り込みは「この面の確定scopeから作成された依頼」に限定し、同一scopeでも別run由来・legacy IDLE由来の依頼は依頼の作り直し（既存置換確認経由）へ案内する。
- **scope-bound依頼破棄の契約化**: 凍結scopeの再編集のためにactive依頼を捨てる操作を1つの明示operationとして定義し、既存の`ExchangeMutationGate`配下で実行する。session無効化はfailure-awareなprimitive（`invalidateIf(expectedExportId) -> Committed / NoMatch / WriteFailed` 相当。spec 204 Amendで追加）で観測可能にし、`Committed` / `NoMatch` の後のみ従属する取り込み済み提案の処理（既存reconcile契約）と選択面への復帰へ進む。`WriteFailed` ではsession・pending・`ScopeConfirmed`を保持し、typedで再試行可能な失敗を表示する。
- 候補0件時（manual run）: 対象選択面をstate層で介在させず方法選択面へ進む（現行のdisplay層pass-throughと「内部で`Selecting(空)`に入る」契約を置換する）。0件でもAIに相談できる（export subjectsは配置済みのみ）。
- 選択面上で0件のまま続行することを明示操作とし、「未配置アプリを追加せず整理する」旨を提示する。
- 選択編集の凍結条件を「AI依頼が確定scopeを参照している間」に限定し、凍結理由をUI上に表示する。依頼が存在する状態で対象選択へ戻る場合は、scope-bound依頼破棄（D-13「破棄」確認つき）を経る。候補0件のrunでは選択面へ戻る導線そのものを設けない（Back＝中断）。
- Home変化に対するfreshness: 既存のcomposition時fail-closed検証（`CANDIDATE_SELECTION_STALE` / preview stale）とtyped再試行案内を正規の挙動として固定し、検出cutより古いcaptureからscopeを確定させない。
- 上記に伴う `docs/product/organizer-to-be-ux.md`（D-04/D-05/D-06/D-17/§5.1/§5.2/§5.3のRevision追記）・`docs/product/organizer-disposition-migration.md`（#417行追加）・spec 369/372/331/367/204/374/375の改訂（production変更より先に同一PR内で適用する）。

## Non-goals

- onboarding提案のjourney変更（D-16・spec 53/370の固定経路は不変。方法選択面はonboarding runに現れない）。
- AI output schema / validator / framing / instruction契約の変更（spec 204/205/327/329/348が所有。validator・schema・framingは本specで一切緩和しない。spec 204への変更は、session metadataへのadditiveなorigin追加とsession storeへのfailure-aware invalidation primitive追加に限定する）。
- 配置済みitemの部分選択・除外（対象scopeの配置済み部分は常に全体。選択できるのは未配置候補のみ、spec 228どおり）。
- 候補検出アルゴリズム・detection cutの取得方法の変更（admission時のfresh captureを維持）。
- scope binding gate（完全一致・candidate projection digest・typed `SCOPE_MISMATCH`・zero-write）の緩和（spec 331 D-2/D-4/D-5、#375 amendment不変）。
- durable pending intent storeの読み取り契約・rebind 1経路（`Hub → ImportReview`）の変更（spec 374/375の既存読み手を壊さない。変更はsession側のorigin追加と消失原因の追加のみ）。
- External Agentのinterview質問内容の固定。
- preview / explicit confirmation / transactional apply safetyの省略。
- 旧idle入口で作成済みの依頼・提案の無効化（読み取り互換を維持する）。
- 今回観測された1回限りのImport失敗の原因断定（Issue本文のNotesどおりfailure classは未確定）。

## Disposition of existing decisions

Issue #417受入条件「idle AI / run-in AIの二経路についてContinue / Amend-Supersede / Retireのdispositionが明記される」に対する決定:

| 対象 | Disposition | 内容 |
|---|---|---|
| idle AI相談（pre-run request flow、D-04/D-17前段） | **Retire（新規作成入口）** | 同じ「AIに相談」に2つのscope形成timingが併存することが問題の根拠であり、Outcome「AIだけ別のscope形成タイミングを持たせない」に直接反するため。代替案B（idle AIを「追加アプリを含めない別機能」として残す）は却下した。entry面のhostingはimport-onlyとして既存依頼の回収を継続する。 |
| run-in scoped AI相談（T-08選択面のscope凍結entry） | **Amend（正規AI pathへ一般化）** | 「凍結scopeをexportする」契約（spec 331 §5）を、選択面から方法選択面へ移した上でAI pathの唯一の新規作成入口とする。 |
| 検出→選択→凍結→AI相談の順序（代替案Cの実質） | **採用（run内で完結・manualのみ）** | 検出・選択をrun外に二重化する代わりに、既存run state machine内（admission→検出→選択→凍結）で実現する。onboarding（D-16）は対象外。 |
| durable依頼・取り込み済み提案・rebind（spec 374/375） | **Continue + Amend** | `Hub → ImportReview` 1経路・依頼時scope完全一致・選択復元初期値＋明示確認・fail-closedは不変。Amend: (a) export sessionへdurableなentry originを追加（既存recordはIDLE読み替え）、(b) RUN_INを「durable provenance（origin）」と「生存owning runへのdirect attach authority（process-localなrunId↔exportId束縛）」の2軸へ分離 — ownerを欠くRUN_IN originの取り込みはRUN_IN pendingとしてdurable保存されrebindのみへ接続、(c) 取り込み済み提案の消失原因へ「scope-bound依頼破棄」を追加（既存reconcile契約どおり）。`entryKind` の型と読み取り互換は維持し、生成正本をdurable originへ変更する。 |
| export session契約（spec 204） | **Amend（additive）** | session metadataへdurableなentry originを追加し、session storeへfailure-aware invalidation（`invalidateIf(expectedExportId) -> Committed / NoMatch / WriteFailed` 相当）を追加する。schema・validator・framing・privacy tierは不変。 |
| T-07前置き面の方法選択（spec 369/372） | **Amend-Supersede** | 前置き面は方法選択を含まない入口面となり、方法選択はscope確定後の方法選択面へ移る。spec 372 EX-AC-01の「T-07に方法選択が現れる」部分を本specが置換する。 |
| 候補0件時の到達点（TO-BE D-06、spec 369 RD-3・状態対応表） | **Amend** | manual runでは検出後に方法選択面へ進む（display層pass-throughからstate層の`ScopeConfirmed(空)`へ）。onboarding runは現行どおりplanning直行。 |
| 選択面からのrun-in AI相談entry（TO-BE §5.2 secondary entry 3、spec 367） | **Supersede** | 選択面（編集中）にAI相談entryは現れなくなる。AI相談は凍結後の方法選択面から開く。 |
| onboarding固定経路（D-16、spec 53/370） | **Continue** | #417の対象をmanual triggerに限定するため、変更しない。 |

## Domain language

承認時に `CONTEXT.md` へ反映する用語:

- **対象scope凍結 (Frozen Organization Scope)**: 1回の整理runについて、方法選択より前にユーザーが明示確定した対象集合。配置済み対象（常に全体）と、選択済み未配置候補（0件以上）からなる。確定後はAI export・deterministic planner・import検証のすべてがこの同一scopeを参照する。
- **方法選択面 (method choice)**: scope凍結後に現れる「このまま整理 / AIに相談」の選択面。旧T-07前置き面の方法選択（spec 369/372）はここへ移る。
- **scope-bound依頼破棄 (scope-bound request discard)**: 凍結scopeの再編集のために、そのscopeから作成したactive依頼（とその従属物）を`ExchangeMutationGate`配下で順序付きに無効化する明示操作。依頼時scopeを参照する全durable状態の消失原因の1つとなる。

## Behavior scenarios

### Scenario: 候補あり → 選択 → scope確定 → このまま整理

Given 候補が3件検出された状態でmanual runが開始され、対象選択面が表示されている
When ユーザーが1件を選択して「続行」し、方法選択面で「このまま整理」を選ぶ
Then capture/planは選択済み1件をadditionsに含むscopeで実行され、previewにその1件が現れる
And 選択面で選ばなかった2件はplanに現れない

### Scenario: 候補あり → 選択 → scope確定 → AIに相談

Given 同じく1件を選択してscopeを確定し、方法選択面が表示されている
When ユーザーが「AIに相談」を選び、依頼を作成して外部AIの回答を取り込み、提案で続行する
Then export文書のCANDIDATE subjectsは選択済み1件と一致し、import後のplanningも同じ1件をadditionsとして消費する
And 作成された依頼のsessionはscope origin（run内・scope選択あり）を保持し、この依頼から生じるimport recordはどの入口から取り込んでも同じoriginを参照する
And 依頼作成から取り込み完了まで、対象選択面へ戻る導線はscope-bound依頼破棄の確認（D-13）を経由するものであり、選択を黙って変更しない

### Scenario: 候補0件 → 選択面を挟まず方法選択

Given 候補が0件の状態でmanual runが開始された
When 検出が完了する
Then 対象選択面は表示されず（内部stateにも`Selecting(空)`を介在させず）、方法選択面へ進む
And 「AIに相談」を選ぶとexport subjectsは配置済み対象のみを含む（候補subjectは0件）

### Scenario: 選択面上で0件のまま続行

Given 候補が3件表示され、1件も選択していない
When ユーザーが「続行」を選ぶ
Then 「未配置アプリを追加せず整理する」旨が提示された上でscopeが確定し、planningのadditionsは空になる
And これは暗黙の未確定状態ではなく、明示的な0件選択として扱われる

### Scenario: AI依頼が参照するscopeの凍結とscope-bound依頼破棄

Given 方法選択面からAI依頼が作成済みである
When ユーザーが対象選択面へ戻ろうとする（system Back）
Then 「依頼を破棄するか」のscope-bound依頼破棄確認（D-13）が表示される
And 承認すると、`ExchangeMutationGate`配下で (1) session storeのfailure-aware無効化（`invalidateIf(expectedExportId)`）、(2) 無効化が `Committed` / `NoMatch` だった場合のみ、従属する取り込み済み提案の既存reconcile契約に沿った処理、の順に実行され、その後に限り選択面が編集可能な状態で再表示される
And 無効化が `WriteFailed` だった場合はsession・取り込み済み提案・凍結scopeのすべてが保持され、方法選択面にtypedで再試行可能な失敗が表示される
And 破棄が成立した場合はprocess-localなattach authorityの束縛（`boundExportId`）も同時にclearされる

### Scenario: durableなactive依頼はrun admissionを妨げない

Given legacy IDLE依頼（RUN leaseを保持しない）がactiveに存在し、runが存在しない
When hubから整理を開始する
Then RUN leaseは空いているためmanual runはadmissionされる（依頼の存在だけではBusyにならない）
And scope確定後の方法選択面では当該依頼は取り込み不可（作り直し案内）であり、依頼がscope形成を隠れて凍結することはない

### Scenario: 依頼なしの方法選択面からのBack

Given scope確定後の方法選択面にAI依頼が存在しない
When ユーザーがsystem Backで戻る
Then 対象選択面が編集可能な状態で再表示される（layout書込みなし、選択内容は保持される）
And 候補0件で確定したrunの場合は選択面を再表示せず、Back＝中断（zero-write）として入口/hubへ戻る

### Scenario: 方法選択面からの他由来依頼の取り込みは不可

Given hubには旧版で作成されたidle依頼（配置済みのみのexport scope、origin保持なし）がactiveに存在する
When manual runを開始してscopeを確定し、方法選択面からexchange flowを開く
Then その依頼をこの面から取り込む導線はなく、「このscope用に依頼を作り直す」ことが案内される（既存置換確認を経て新依頼を生成できる）
And 作り直しに応じない限り、このrunのimport recordがidle依頼由来のsessionへ紐づくことはない

### Scenario: 同一scopeでも別run由来の依頼はattachできない

Given 過去のrunで同一の対象scopeから作成された依頼（durable originはRUN_IN）がactiveに存在し、その生成元runは存在しない
When 新しいmanual runで同一scopeを確定し、方法選択面からexchange flowを開く
Then この依頼を現在のrunへdirect attachする導線はなく、作り直しへ案内される（scope一致だけでは同一runと判定しない）
And この判定はprocess-localな「run↔exact exportId」束縛に基づき、durableなscope一致のみでは成立しない

### Scenario: ownerを欠くRUN_IN originの取り込みはRUN_IN pendingとしてImportReviewへ接続される

Given 方法選択面から作成したRUN_IN originの依頼へ回答した後、processが死に、liveなowning runが存在しない
When hub（entry面のimport-only hosting）から回答を取り込む
Then 取り込みはdurable pendingとしてRUN_IN origin付きで保存され、owning-run fenceによってdirect attachは行われない
And 保存成功後に表示されるのはdirect-attach用の取り込み成功状態ではなく、既存の取り込み済み提案の確認面（ImportReview）であり、「この提案で続ける」はそこからのみ実行される
And 継続は `Hub → ImportReview` rebind 1経路のみに接続され（既存#375のadmission anchorを再利用）、新しいrebind/start seamは作られない

### Scenario: 置換生成後は新しい依頼だけがattachできる

Given 同一runの方法選択面で依頼E1を作成した後、置換確認を経て依頼E2を生成した
When E1の回答とE2の回答をそれぞれ取り込む
Then direct attach authorityはE2にのみ存在し、E1の回答はattachできない（束縛は置換生成で置換される）

### Scenario: 中断済みrunへの遅延生成完了はauthorityを与えない

Given 方法選択面から依頼生成を開始した後、session保存の完了を待たずにrunを中断した
When 遅延して生成完了（session保存成功）が到着した
Then そのexportIdはどのrunにも束縛されず、後続runの方法選択面からdirect attachできない（durable依頼としてはhub経由で取り込み可能なまま、挙動はimport-only契約に従う）

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

Given 旧版で作成されたidle依頼（配置済みのみのexport scope、origin保持なし）がdurableに残っている
When hub status card（entry面のimport-only hosting）から依頼を開き回答を取り込み、提案で続行する
Then このimport recordの`entryKind`はIDLE（origin保持なしの読み替え）として記録され、#375 の再開契約どおり fresh run admission → 検出 → 選択面（unchecked初期値・件数案内）→ 確認時の完全一致検証を経てplanningへ進む
And 依頼時scopeに候補が含まれないため、候補を選択した状態の確認は `SET_MISMATCH` としてzero-write失敗し、選択修正による継続が案内される

### Scenario: process死後の再開

Given 方法選択面から依頼を作成した後、processが死んだ
When ユーザーが `Hub → ImportReview` から提案を再開する
Then 提案はsession保持のscope origin（RUN_IN相当）を参照し、#375 のrebind契約（選択復元初期値＋明示確認1回）でfresh runがadmissionされ、依頼時scopeとの完全一致検証を経てplanningへ進む
And 方法選択面は再表示されない（方法は既に確定済みであり、intentがboundされたrunは確認へ直行する）

### Scenario: onboarding提案は方法選択面を経ない

Given onboarding提案の「確認」からrunがadmissionされた（`ONBOARDING_PROPOSAL`）
When 検出・対象選択（または0候補のpass-through）が完了する
Then 方法選択面は現れず、現行D-16どおりplanningへ直行する
And この経路のrunがscope-firstの新stateへ到達することはない

## Data and state

- 読むdataと正本: 候補検出cut（admission時のfresh capture、`MissingAppCandidateSource`）、export session（durable、24h）、durable pending intent（durable、依頼と同一TTL）。検出cutとpending intentの契約は既存のまま。
- 永続化するdata: **export sessionへの1 field追加（durableなentry origin: `IDLE` / `RUN_IN`）のみ**。additive拡張であり、既存recordは「origin保持なし＝IDLE読み替え」で読む。新規recordのoriginはsession生成時に1回だけ書かれ、以後不変である。選択状態・確定scope・direct attach authority（run↔exact exportId束縛）はprocess-localなままである（spec 228継続）。`entryKind`（IDLE/RUN_IN）は型と既存recordの読み取り互換を維持し、新規recordはdurable originから導出される（実行時run state推定は廃止）。
- scope-bound依頼破棄はdurable mutationを含む（failure-aware invalidationと既存reconcile primitiveの利用であり、layout/workspace DBへの書込みは生じない）。
- migration、backup/restore、rollbackへの影響: 追加fieldの下位互換は読み側で保証する（origin保持なし→IDLE）。**downgradeは現行codecの実態に従う**: 旧buildのJSON decoderは未知keyでdecode失敗するため、scope-firstで作成されたsessionは旧buildでは「no-session」としてfail-closedになり、従属するpendingもread-time reconcileで失効し得る。これ安全側への倒れ込みであることを明示的に許容する（既存`categoryRefs` fieldと同じ既知の挙動であり、`SET_MISMATCH`等のfail-closed gateは維持される）。schema破壊・data移行は生じない。
- layoutを扱う場合の扱い: 対象scopeは「配置済み全対象＋選択済み未配置候補」。配置済みの部分選択は存在しない（spec 228 D-1: 全候補未選択デフォルト・明示選択のみ追加対象）。

## Permissions, privacy, and security

- None。新規permission、新規外部送信経路、sensitive dataの追加はない。External Agent Exchangeのprivacy契約（送信前確認・privacy tier・export-scoped ref・sessionのbackup対象外）はspec 205/204/331のまま不変である。

## Accessibility and localization

- 方法選択面・凍結理由・scope-bound依頼破棄確認はTalkBackで理解できること（label、focus順、状態変化のlive region告知）。既存のscope mismatch行のassertive live regionの方針を凍結理由表示へも適用する。
- キーボード/switch accessで入口→選択→確定→方法選択→各肢へ到達できること。
- 200% font scaleで方法選択面・選択面・凍結理由が崩れないこと。
- 新設・変更する文言はEN/ja両方を同時に提供する。
- 受入evidenceは自動oracle（semantics/生 resolving、focus traversal・復元、200%でのclipping/overlap不在）と、実機でのTalkBack / キーボード / Switch Access操作evidenceに分けて記録する（spec 228 AC-11の分離規約に倣う）。

## Acceptance criteria

- [ ] AC-1: 候補あり時、manual runが「開始→検出→対象選択→scope確定→方法選択→planning」の正規順序で進み、方法選択面はscope確定後にのみ現れる。方法選択の両肢は同じ確定scope（選択済み候補を含む）を消費する。
- [ ] AC-2: 選択済みmissing appsは、AI exportのCANDIDATE subjectsとdeterministic plannerのadditionsに同一集合として現れる（同一の確定scopeから組成される）。
- [ ] AC-3: 候補0件時（manual run）、対象選択面を表示せず（内部stateにも`Selecting(空)`を介在させずに）方法選択面へ進み、AIに相談できる（export subjectsは配置済みのみ）。
- [ ] AC-4: 選択面上の0件続行は「未配置アプリを追加せず整理する」旨の明示を伴い、暗黙の未確定状態と区別される。
- [ ] AC-5: 選択コントロールが編集不可になるのは「AI依頼が確定scopeを参照している間」のみであり、その理由（依頼の存在と状態）がUI上に表示される。Exchange内部stateのみを理由とする不可解な無効化は発生しない。依頼が存在しない選択面では、編集は常に可能である。凍結解除（選択面への復帰）は、scope-bound依頼破棄のfailure-aware無効化が `Committed` / `NoMatch` になった後にのみ許可され（`WriteFailed` では凍結維持・再試行）、候補0件のrunでは選択面への復帰導線が存在しない。durableなactive依頼はrun admissionを妨げない（Busyは生存runのRUN lease保持に限定される）。
- [ ] AC-6: run外のidle依頼の新規作成入口は撤去され、entry面のexchange hostingはimport-only（既存依頼の状況表示と取り込みのみ）となる。export sessionはdurableなentry originを保持し、import recordの`entryKind`はoriginから導出される（実行時run state推定に依存しない。同一sessionへの再取り込みで`entryKind`が変化することはない）。direct attach authorityはprocess-localな「このrunの`ScopeConfirmed`で生成したexact exportId」の束縛（`boundExportId`）に限定され、束縛は生成開始時のsnapshot→二段階commitのbind（session保存をgate内で完了後、正順run lock → gateで再取得しfresh readして同一operation・同一`ScopeConfirmed`・同一attempt・同一exportIdのときのみbind）→復帰/中断/終了/破棄でclear→置換生成で置換、というlifetimeを持つ。全経路のlock順序は正順（run lock → `ExchangeMutationGate`）に固定され、gate保持中のrun lock取得は存在しない。scope一致だけでは同一runと判定しない。ownerを欠くRUN_IN originの取り込みはdirect attachせずRUN_IN pendingとして保存され、保存成功後はImportReviewへ切り替わり、継続はrebind 1経路のみ（既存#375 anchorの再利用、新seam不設置）である。方法選択面からの取り込みは「この面の確定scopeから作成された依頼」に限定され、同一scopeでも別run由来・legacy IDLE由来の依頼は作り直しへ案内される。既存のdurable依頼・取り込み済み提案は #374/#375 契約（`Hub → ImportReview` 1経路、IDLE由来のunchecked復元を含む）どおりimport・再開できる。
- [ ] AC-7: 検出cutより後のHome変化により候補がstaleになった場合、composition時のfail-closed検証が作動し、zero-writeのtyped失敗と再試行案内が返る。古いcaptureから確定したscopeでplanは作られない。
- [ ] AC-8: instrumentationで次のjourneyが固定される: (a) empty Home → 全候補選択 → AI依頼 → import → preview、(b) empty Home → 全候補選択 → このまま整理 → preview、(c) AI依頼存在下のBack → scope-bound依頼破棄（`Committed`後は選択面へ復帰、`WriteFailed`時は方法選択面に残留・凍結維持・再試行）、(d) Back/中断/process recreation後にscope ownership（どのscopeがどの依頼・runに紐づくか）が曖昧にならず、legacy IDLE依頼がactiveな状態でもmanual runはadmissionされ、その方法選択面からの取り込みは遮断される、(e) 候補0件runで方法選択面からBackすると選択面を経ずに中断される、(f) onboarding提案のrunに方法選択面が現れない、(g) 同一runで生成→import→attachが成功する、(h) RUN_IN依頼作成後にowning runを失ってhub経由で取り込むと、RUN_IN pendingが保存されImportReviewへ切り替わり（direct attach・direct-attach用成功状態とも不発）、rebindへ接続される、(i) 同一scopeでも別run由来のactive依頼は方法選択面からattachできない、(j) 生成のsession保存完了をbarrierで止めたうえでrunを中断/再開し、遅延settleしても旧exportIdにdirect attach authorityが生えない。保存後〜bind前に置換/無効化が割り込んだ場合も、fresh re-readによりstaleなexportIdがbindされない、(k) 同一runでE1→E2の置換生成後、E1の回答はattachできずE2のみattachできる、(l) rebind admissionがrun lockを保持した状態と、生成bind/破棄がgate取得を試行する状態をbarrierで交差させても、lock順序は正順に固定され双方が完了し（deadlock不発生）、runは不変に保たれる。
- [ ] AC-9: 自動oracleとして、新設・変更面のsemantics（name/role/state）、live region告知、focus traversal・復元、200% font scaleでのclipping/overlap不在が検証される。さらに実機evidenceとして、TalkBack / キーボード / Switch Accessでscope確定・方法選択・凍結理由・次操作が理解できることを記録する。
- [ ] AC-10: `docs/product/organizer-to-be-ux.md` へのrevision追記（D-04/D-05/D-06/D-17/§5.1/§5.2/§5.3の改訂、D-16不変の明記）、`docs/product/organizer-disposition-migration.md` のsupersession mapへの#417行追記、spec 369（RD-3・D-06節・状態対応表・0候補scenarioの本文Amend）、spec 372/331/367（Amend/Supersede標記と該当規定の改訂）、spec 204（session origin追加・failure-aware invalidation追加）、spec 374（scope-bound破棄の消失原因追加・「same-processの取り込み成功CTAはlive-owner direct attach時のみ。ownerless RUN_INはImportReview rebind」の明記）、spec 375（attach authority 2軸分離の明記・「同一session再取り込みでentryKindだけflipする」既存scenario/SR-AC-07該当oracleの廃止と「再取り込みはentryKindを保持する」回帰への置換）が、production変更のcommitより先に同一PR内で適用される。
- [ ] AC-11: #331 scope binding gate（完全一致・candidate projection digest・typed `SCOPE_MISMATCH`・zero-write）、spec 348/327のinstruction・interview契約、spec 374/375のdurable・rebind契約、spec 228 D-1、spec 53/370のD-16固定経路（onboardingが方法選択面へ到達しない回帰oracleを含む）の既存test回帰が維持される。加えて新規のfailure注入oracle: scope-bound破棄の`WriteFailed`（session・pending・凍結scopeの不変とtyped再試行）、owning run喪失後のRUN_IN origin取り込み（pending保存・direct attach不発）、process死を挟む同等シナリオがすべて通る。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | unit: run state遷移（確認→`ScopeConfirmed`→各肢、intent bound時・onboarding時は確認→compose直行）。instrumentation: 正規journeyの順序assert |
| AC-2 | unit: export組成とplanner入力が同一scopeから組まれることのcontract test。instrumentation: (a)(b) journey |
| AC-3 | unit: 0候補時に`Selecting`を介さず`ScopeConfirmed(空)`へ到達。instrumentation: 方法選択面へ進むassert |
| AC-4 | unit + instrumentation: 0件続行の表示と空additions組成 |
| AC-5 | unit: 凍結条件（依頼存在との対応）・reopen guard（依頼あり拒否・0候補拒否）・`WriteFailed`時の凍結維持。instrumentation: (c)(e) journey |
| AC-6 | unit: origin書込み・`entryKind`導出（origin保持なし→IDLE・再取り込みで不変）・`boundExportId`のbind/clear/置換lifetime・他由来取り込み遮断・owner欠落RUN_IN pending保存とImportReview遷移・entry面creation不在。instrumentation: hub経由のlegacy import、(g)〜(k) journey |
| AC-7 | unit: composition時stale検出の既存契約 + instrumentation: Home変化後のtyped再試行 |
| AC-8 | instrumentation: (a)〜(l)。失敗注入: scope-bound破棄の`WriteFailed`でsession/pending/凍結scopeが不変であること、owning run喪失後のRUN_IN取り込みでdirect attachとdirect-attach用成功状態が不発であること、遅延settleが後続runへ束縛を付与しないこと、save後〜bind前の割込みでfresh re-readによりstale bindが発生しないこと、lock順序交差でdeadlockしないこと |
| AC-9 | instrumentation: semantics/live region/focus/200%の自動assert + 実機TalkBack・キーボード・Switch Accessの操作evidence（記録をPRへ添付） |
| AC-10 | PR diffのcommit順序（docs commitがproduction commitより先）とreview確認 |
| AC-11 | 既存回帰（scope binding gate・durable import・rebind・selection既定値・onboarding D-16） + 新規failure注入oracle（`WriteFailed`・owning run喪失・process死） |

organizer JVM gate（`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`）とfocused connected test laneを必須evidenceとする（quality-strategyどおり、instrumentationはJVM gateの代替ではない）。

## Open questions

（なし。`accepted` 時点で空。文言の最終copyは実装PRで確定するが、挙動は本specのscenarioに固定済みである。）

## Change history

- 2026-09-24: Draft created for #417.
- 2026-09-24: PR #423 1回目review（Changes requested）対応。blocking指摘3点（legacy import provenance / scope-bound破棄のdurable契約 / onboarding D-16回帰）と非blocking指摘3点（D-06・spec 369本文改訂 / 0候補Back / a11y evidence分離）を反映: session origin追加、scope-bound依頼破棄の契約化、対象をmanual triggerへ限定、改訂対象の列挙拡充、reopen guard、oracle分割。
- 2026-09-24: PR #423 2回目review（Changes requested）対応。RUN_INを「durable provenance（entry origin）」と「生存runへのdirect attach authority（process-localなrunId↔exportId束縛）」の2軸へ分離し、owner欠落RUN_IN取り込みのpending保存とrebind限定、同一scope別run由来のattach不許可を契約化。session storeへfailure-aware invalidation（`invalidateIf` 相当）を追加し、破棄を`Committed`/`NoMatch`/`WriteFailed`で観測可能に。downgrade契約を実装実態（未知keyでdecode失敗→no-session fail-closed＋pending reconcile失効の許容）へ修正。durable active依頼はRUN leaseを保持しないためadmissionを妨げないことを明記。
- 2026-09-24: PR #423 3回目review（Changes requested）対応。`boundExportId`束縛の線形化点とlifetimeを契約化（生成開始snapshot→保存成功後のrun lock下bind→復帰/中断/終了/破棄でclear→置換で置換、stale settleは後続runへ不付与。oracle (j)(k)追加）。ownerless RUN_IN保存成功後の表示遷移をImportReview（既存reconcile読み直し・#375 anchor再利用・新seam不設置）へ固定し、spec 374へのCTA条件明記とspec 375のentryKind-flip scenario/SR-AC-07置換をAC-10へ追加。
- 2026-09-24: PR #423 4回目review（Changes requested）対応。bind/破棄と既存#375のlock順序を正順（run lock → `ExchangeMutationGate`）に固定し、bindを二段階commit（gate内保存→解放→正順で再取得→fresh re-read検証）へ改訂。保存後〜bind前割込みのfresh re-readとlock順序交差のoracle (j)(l)拡充、plan内のImportReview遷移二択の解消、Non-goalsのspec 204変更範囲文面の整合。
