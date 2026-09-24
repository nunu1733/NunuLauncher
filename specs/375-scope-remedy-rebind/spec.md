---
issue: "#375"
status: implemented
requirements: [FR-004, FR-006, FR-017]
risk: []
updated: 2026-09-22
---

# scope不一致のremedyを原因別に分割し、取り込み済み提案のprocess死後再開（fresh run rebind）を実現する（D-17）

> 契約の根拠: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-17, §5.1 T-08/T-18, §5.3 `ImportReview → Detect`、§8.1, §9 語彙規約, §10,
> §13-6 影響評価項目6）。
> 処分の正本: accepted disposition
> [docs/product/organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)
> （§2.1 #331行/#228行、§3.13 spec 228復元初期値、§3.16 spec 331改訂、§4.1 supersession
> map「spec 331 D-2単一remedy」「spec 331 §5 idle entry経路」、§5 更新順序 #9、
> §7.1 rebind metadata、§7.3 compatibility matrix、§8 依存graphと#374/#375責務分割、
> §11「選択復元初期値の実装位置は#375のplanが所有」）。
> 本specは[Issue #375][1]の成果物である。Status: **implemented** — Phase 2実装
> [PR #405][14]（merge `f4783d57c0`、独立監査
> [docs/assessment/pr-405-scope-remedy-rebind.md](../../docs/assessment/pr-405-scope-remedy-rebind.md)・
> CI `final-status` green）により受入条件が検証された。accepted移行はPhase1最終reviewの
> **Approve**（[comment `5767085523`][13] @ `74e1fd0fae`、2026-09-22）。
> 前提はすべてmerge済みである（9dc3ec8fed時点のmain。#365正本改訂、#369 T-08/T-13表示統合、
> #373 T-18表示・手段別失敗投影、#374 durable取り込み済み提案 — spec accepted・実装
> [PR #399][4]）。本revisionは初回review（2026-09-19、Changes requested 3件、
> [comment `5740062562`][5]）への対応と、前提merge後のcurrent mainへのre-entryである。
> 実装着手はdisposition §8依存graph（`#369 → … → #374 → #375`）の最後尾として、
> owner指示（Phase 2実装進行）による。

## Problem

現行（baseline `9dc3ec8fed`。#365/#366/#369〜#374実装merge後。spec 331 implemented
`addb25d8181e`、spec 374 implemented [PR #399][4]）では、scope binding gate
（`ScopeBindingGate`）のtyped失敗 `SCOPE_MISMATCH` に対するremedyがspec 331 D-2の単一規定
「再exportに戻る」であり、原因が選択集合の差なのか候補投影の差なのかに関係なく同じ案内をする。
実装上の確認済み事実:

1. **原因と救済actionが対応しない**: gate自体は既に原因を導出している
   （`IntentValidator.ScopeMismatchCause` = `SET_MISMATCH` / `CANDIDATE_UNRESOLVED` /
   `PROJECTION_MISMATCH`）。しかし表示remedyは単一のre-export案内
   （`exchange_failure_scope_mismatch`「選択したアプリが依頼内容と一致していません。同じアプリを
   選び直すか、依頼を再作成してください。」）であり、**選択の修正だけで同じ提案を続行できる場合**
   と**依頼の作り直ししかない場合**が区別されない（TO-BE E-4の残存）。
2. **confirm時gateのcause導出がavailabilityを見ていない**: `ManualOrganizationRun
   .confirmSelection()` の早期gate（set等価比較）は依頼scopeと選択の差をすべて
   `SET_MISMATCH` として報告する。依頼時候補がuninstall/disable等で検出cutから消えている場合、
   「選び直せば続行できる」案内は**実行不可能な救済**を指す（選び直せない候補を要求する）。
3. **差分強調がない**: 選択面（T-08）のbound intent支援は件数案内
   （`exchange_scope_intent_count`）とreject文言のみで、依頼時集合との差（依頼に含まれ未選択の
   候補 / 依頼に含まれない選択）がどの候補かを強調しない。
4. **process死後の継続経路がない**: run・選択はprocess-local（spec 228 D-1、TO-BE §8.2）で
   あり、process死後の回復は「同じ回答textの再取り込み」のみである。実装済みの[Issue #374][2]
   （durable record `DurablePendingIntent`・読取時reconcile `reconcilePendingIntent`・
   status card行・再開面 `Hub → ImportReview`（`ExchangeFlowStateHolder.openPendingImportReview()`））
   により提案はdurable保存・cold processで表示できるが、**再開面に継続CTAは存在しない**
   （#374 spec Contract notes 2が継続/rebindを本Issueへ委譲。再開面は内容・残時間・破棄のみ）。
   process死後も取り込み済み提案が残るのに続行できない状態が、#374適用後も残っている。
5. **継続時点のfail-closed再検証がない**: import時の構造digest検証
   （`ExchangeImportPipeline` の `CONTEXT_STALE`）はimport時点限りである。#374により
   validated intentの内容が最長24時間保存されるため、import後にホーム構造が変わった提案を
   継続できる経路を新設する場合、import時と同等のfail-closed検証を継続時点で再適用しないと、
   gate（候補側の2検証）を素通りしてplanner投影が壊れた入力（消失したItemId参照）に到達しうる。

accepted disposition（§3.16）はspec 331 D-2の単一remedyをD-17の原因別remedyへ **Amend** する
ことを決定した（完全一致gate・zero-write・fail-closedは不変）。spec 331 §5のidle entry経路と
attach契約の生存範囲、およびspec 228 D-1との関係（復元初期値）の改訂も本Issueが所有する
（§4.1 supersession map、§5 更新順序 #9）。

## Outcome

`SCOPE_MISMATCH` のremedyが原因別に分割される。**SET_MISMATCH（選択集合の差）**では選択面が
依頼時集合との差分を強調し、選択を依頼時の集合へ戻せば**同じ提案（bound intent）で続行できる**。
修正しない場合は依頼を作り直す。**依頼時候補の解決不能（CANDIDATE_UNRESOLVED）と投影差
（PROJECTION_MISMATCH、availability/分類の変化）**では、選択修正では依頼時投影と一致させられない
ため**同じ提案での続行を許さず**、依頼の作り直しへ案内する。完全一致gate・zero-write・
fail-closedの契約は1行も緩まない（SET_MISMATCHのremedyは「依頼時の集合へ選択を戻す」操作であり、
gate通過を曖昧にしない）。

取り込み済み提案（#374のdurable record）の再開面に「この提案で続ける」CTAが現れる。
CTAはfresh run admission（RUN lease取得）→ 検出 → 選択面で依頼時scopeとの完全一致検証、
という**既存のscope binding gateそのもの**へ接続する。**run-in相談由来**の提案では、一致する
場合に前回の明示選択（= 依頼時の選択凍結集合）を**初期値として復元**したうえで、ユーザーの
**明示確認を1回**要求する（確認なしの完全自動復元は採らない。spec 228明示選択契約の維持）。
**idle相談由来**の提案には選択復元はなく、unchecked初期値＋件数案内（現行契約）のうえで
同じgateが適用される。再開はprocess生存/死を問わず `Hub → ImportReview` の1経路に集約される
（TO-BE §5.3）。run-in相談の同一run attach契約（admitted run保持・RUN lease継続・選択凍結・
authoring不可・single-shot）は生存範囲を明確化したうえで現行どおりである。

継続（rebind）は**rebuild入力の再構築 → run admission直前のanchor再検証 → admission**
の順で行われ、admissionの直前までに提案が無効化（session置換・破棄・TTL失効）されていた場合は
**run admission自体が発生しない**（typed拒否。Stale state / concurrency節のrebind admission
anchor契約）。rebuildが成功しても、その時点の検証だけをadmissionまで持ち越すことはしない
（review指摘1〔TOCTOU〕の解消。初回draftの「CTA押下からfresh run検出までの間にsessionが
失効した場合はgate・reconcileの既存fail-closed経路で捕捉される」規定は廃止する —
gateはcandidate側の2検証のみでsessionの有効性を再確認せず、捕捉されないため）。

## Scope

- **原因別remedyの導出と表示**: `SET_MISMATCH` → 選択修正で同じ提案を続行可能（差分強調つき）。
  `CANDIDATE_UNRESOLVED` / `PROJECTION_MISMATCH` → 続行不可、依頼の作り直しへ案内。
  confirm時早期gateのcause導出をavailability考慮へ精緻化する（依頼時候補が検出cutに存在しない
  か `AVAILABLE` でない場合は続行不能causeを優先し、「選び直せる」誤った救済を提示しない）。
  gateの判定規則（完全一致 + candidate投影digest、zero-write）と`ScopeMismatchCause` enumは
  変更しない。
- **T-08選択面の差分強調**: bound intentがあるとき、依頼時集合と現在選択の差
  （依頼に含まれ未選択の候補 / 依頼に含まれない選択）を候補row上で強調する。強調は色だけに
  依存しない。
- **選択復元初期値（rebind、run-in由来のみ）**: 再開CTAによるfresh runの選択面で、依頼時の
  明示選択（= 依頼scopeのうち現行検出cutで解決可能なidentity）を**初期選択値**として復元する。
  復元は`state`への初期値であり、確定は選択面の「続行」confirm（明示確認1回）のみが行う。
  復元できない依頼時候補（解決不能）がある場合、confirmは続行不能causeでfail-closedになり、
  選択修正では救済できないことが案内される。
- **再開面CTAの有効化**: #374のImportReview再開面（`ExchangeScreen.ImportReview`）に
  「この提案で続ける」CTAを追加する（語彙はTO-BE T-18 / spec 328 rev.2 D-3の統一copy）。
  reconcile通過済み（#374契約）の提案にのみ表示する。CTAはfresh run admission（既存
  `start(intent)` seamに本Issueが追加する**admission anchor引数**を経由。RUN lease・単一
  active run gate・Busy時typed拒否を含む）へ接続し、成功時にrun面へ遷移する。**CTAの成功は
  提案を消費（削除）しない**（提案の消失系は#374契約どおり破棄・期限切れ・置換のみ）。
- **継続時のfail-closed再検証（admission anchor）**: 再開CTAによる継続（rebind）では、
  (1) rebuild入力再構築の一部として依頼sessionの`sourceContextDigest` と現行構造digestの
  等価検証（import時 `CONTEXT_STALE` と同一意味論）を再適用し、(2) run admissionの直前
  （RUN lease取得と同一排他境界内・**exchange mutation gate保持下**）で、**新鮮に読み直した**
  durable record・active session・clockに対してreconcile条件とrecord完全一致の再検証
  （**rebind admission anchor**）を行う。不一致・無効化はいずれもtyped失敗であり、run
  admission・可観測run state・journal書込は発生しない。構造digest不一致のtyped失敗は提案を
  削除せず依頼の作り直しへ案内する（Contract notes 1。anchor拒否時の提案・面の扱いは
  Stale state / concurrency節）。
- **exchange mutation gateの新設とidentity shape検証**: sessionとdurable recordを変化させうる
  全操作とrebind admissionを直列化するprocess-wideな排他seamを新設する（Stale state /
  concurrency節）。またrecordのidentity値の破損（schema不一致・digest長不正）を
  `reconcilePendingIntent`の破損検証へ追加し、例外化せずtyped fail-closedに扱う
  （#374破損契約へのidentity次元の追加。Data and state節）。
- **spec改訂（本Issueの実装成果。受入後に実装へ）**:
  - spec 331改訂: D-2のremedy文を原因別へ改訂（gate規則・D-5の単一class/cause detail構造は
    不変）、§5のidle entry/process death経路を `Hub → ImportReview` 1経路＋rebind契約へ更新、
    run-in attach契約（同一runId single-shot）の生存範囲（同一processの選択面が開いている間。
    process死後はrebind契約のみ）を明記。
  - spec 228注記: D-1 unchecked初期値への例外として、run-in由来rebindの復元初期値
    （復元値は明示確認1回を経てのみ確定するため明示選択契約を弱めない）を追記する。
- **Localization**: 新規・改訂のuser-visible文字列は `values/`（EN）と `values-ja/`（正本）の
  双方へ供給する（spec 123契約）。語彙はTO-BE §9/§10（依頼/提案/破棄/続行）に従う。

## Non-goals

- **gate自体の緩和**: 部分一致許容・export後候補追加の許容・選択の黙示確定・確認なし自動復元
  （いずれもTO-BE D-17が明示却下）。
- **run/選択stateのdurable化**: run・RUN lease・preview・選択はprocess-localのまま（TO-BE §8.2）。
- **依頼作り直しflowの自動化**: 継続不能時の案内は導線を示すのみで、新依頼の自動生成・自動
  送信を行わない。
- **#374 storeのschema拡張**: 復元材料は依頼session側の正本
  （`ExportSession.scopeCandidates`）から導出でき、#374が既に保存する
  `intentIdentity`（schemaVersion+digest）・canonical decisions・`entryKind` で足りる。
  本Issueでrecord schemaを変更しない。
- **run-in attach契約の変更**: admitted runの保持・RUN lease継続・選択凍結・authoring不可・
  同一runId single-shot attachは現行どおり（本Issueは生存範囲を明文化するのみ）。
- **#372/#373の表示再構成**: T-15/T-16/T-17の再構成、失敗の手段別再投影、D-11には触れない。
  本Issueのcause別文言は#369のT-13統合失敗面・#373の手段別再投影と接続される際、原因文言の
  供給源として整合する（文言契約自体は本specが所有）。
- **journal/diagnostics schemaの変更**: rebind継続も既存event（`RUN_STARTED`以降の既存相関）で
  記録され、新event・新fieldは設けない。
- **既存の同一process内import成功CTA（spec 328 AC-3）の挙動変更**: single-flight・attempt
  anchor・`continuing` 中の不受理・Busy/NotAttachable拒否は#374が維持する契約のまま
  （本Issueは再開面CTAを同一の規律で追加する。Contract notes 4）。
- run state machine・spec 13/52/194/195/210/271契約・permission・外部送信経路の変更。

## Domain language

`CONTEXT.md` への追加用語（本Issueが正本改訂の所有者である。#365は完了済みのため、
受入後の実装PRで正本へ反映する）。

**原因別remedy (cause-specific remedy)**:
`SCOPE_MISMATCH` の原因種別に対応づけられた救済action。選択集合の差（`SET_MISMATCH`）は
「選択を依頼時の集合へ戻して同じ提案で続行」、依頼時候補の解決不能（`CANDIDATE_UNRESOLVED`）と
候補投影の差（`PROJECTION_MISMATCH`）は「同じ提案での続行を打ち切り、依頼を作り直す」。
_Avoid_: 再export（単一remedyの旧語。re-exportは新依頼の作り直しに含まれる操作であり、
remedy全体を指す語としては使わない）、リトライ（検証の再実行と混同）

**rebind（process死後再開 / fresh run rebind）**:
process死後（および同じ1経路に集約される画面離脱後）に、durableな取り込み済み提案から
「この提案で続ける」でfresh run admissionを行い、検出後の選択面で依頼時scopeとの完全一致検証を
経て提案のpreferenceを新しいrunへ結合すること。admission直前のanchor再検証
（rebind admission anchor）と、recordに保存済みの `IntentIdentity` をそのまま用いる
provenance同一性（import時と同一identity）を契約に含む。同一process内の生存runへのattachと
は区別される。
_Avoid_: 復元（durable status/recoveryの復元（D-15）と混同）、再接続（attachの同義語に聞こえる）

**選択復元初期値 (selection restore initial values)**:
rebindの選択面で、依頼時の明示選択と一致する候補を初期選択値として設定すること。復元値は
選択面の明示的confirm（1回）を経由してのみ確定し、confirm前の選択編集を妨げない。
spec 228 D-1（unchecked-by-default）の、依頼時集合を再現する目的に限定した例外である。
_Avoid_: 自動選択（confirmなしの確定を示唆する）、初期化（全解除と混同）

## Behavior scenarios

### Scenario: SET_MISMATCHの差分強調と同じ提案での続行（同一process）

Given run-in exchangeで候補A・B・Cを選択した状態から依頼が作られ、validated intentが
当該runにattachされ、ユーザーがexchange完了後に選択をB・Cへ変更した（Aを解除した）,
When ユーザーが選択面で続行（confirm）する,
Then 選択面が開き直り、依頼時集合との差が候補row上で強調される（Aは「依頼では対象」、
依頼外に選ばれた候補があれば「依頼外」として区別できる。色のみに依存しない）,
And 案内は原因が選択集合の差である旨と「選択を依頼時の集合へ戻せば同じ提案で続行できる」ことを
示す（再取り込み・新依頼の作成を必須にしない）,
And bound intentは破棄されず、ユーザーがA・B・Cへ選択を戻して再度confirmすると早期gateを
通過し、同じ提案のpreferenceが効いたままplanningへ進む（zero-write。gate通過までworkspace
書込みは0件）。

### Scenario: 続行不能causeの優先（解決不能候補に「選び直せ」と案内しない）

Given 依頼scopeに候補A・Bが含まれ、依頼後にAがuninstallされた,
When 選択がBのみ（またはA・B）でconfirmされる,
Then 失敗causeは `SET_MISMATCH` ではなく続行不能（`CANDIDATE_UNRESOLVED`）として導出され、
案内は「選択を修正しても同じ提案では続行できない。依頼を作り直す」ことを示す,
And 依頼外の追加選択が同時に存在する場合も、解決不能候補の存在が優先される
（修正可能な誤った救済を提示しない）。

### Scenario: PROJECTION_MISMATCHは同じ提案での続行を許さない

Given 依頼時に候補Aの解決済み分類がXでsessionに記録され、confirm後にcompositionが
分類Yへ解決した（availability変化・分類authority変化）,
When scope binding gateの投影digest照合が走る,
Then `SCOPE_MISMATCH`（`PROJECTION_MISMATCH`）としてzero-write失敗し、選択面へ復帰する場合は
「依頼を作り直す」案内が表示される（選択修正での続行を案内しない）。選択面が存在しない経路では
既存どおりtyped終端失敗となる,
And bound intentは破棄され、新しい依頼の作成（再exportを含む）のみが継続手段である
（zero-write。workspace書込みは0件）。

### Scenario: process死後に取り込み済み提案からrebindする（run-in由来）

Given process死前にrun-in exchangeの提案がdurable保存されており（#374）、process死後に
hubのstatus cardに提案行が表示されている,
When ユーザーが行から `Hub → ImportReview` で再開面を開き「この提案で続ける」を選ぶ,
Then この時点で初めてfresh run admission（RUN lease取得）が発生し、検出が行われ、選択面が
開く（CTA押下前にrun admission・検出・書込みは発生しない）,
And 選択面の初期選択値は依頼時の明示選択（現行検出cutで解決可能な依頼scope候補）として
復元され、ユーザーが編集せず「続行」を1回confirmすると依頼時scopeとの完全一致検証が行われ、
一致すれば同じ提案のpreferenceが効いたままcapture/plan → 確認面へ進む,
And 復元は初期値であり、confirm前の選択編集・Select all / Clear all・検索は現行契約どおり
機能する（復元値の自動確定・確認なしのplan進行は発生しない）。

### Scenario: idle由来の提案の再開には選択復元がない

Given idle相談の提案（依頼scopeの候補集合∅）がdurable保存されている,
When 再開面から「この提案で続ける」でfresh runを開始する,
Then 選択面はunchecked初期値＋依頼scope件数案内（現行契約）であり、候補を選ばずconfirmすれば
候補∅との完全一致でgateを通過し、候補を選んでconfirmすれば `SET_MISMATCH` として
zero-write失敗する（既存idle gate契約の継続。原因別remedy表示は本specの適用を受ける）。

### Scenario: rebindの選択面で依頼時候補が解決不能

Given run-in由来のrebindで、依頼scopeの候補の1つが再検出時にuninstall・disable・
Home配置済みのいずれかで解決不能である,
When 復元初期値（解決可能な候補のみ）で「続行」をconfirmする,
Then 完全一致検証が続行不能causeで失敗し、zero-writeで選択面に留まったうえで
「依頼を作り直す」案内が表示される（依頼時候補が選択操作で再現できないため、
選択修正での継行は案内しない）,
And 提案は破棄されず、status cardの行は残る（破棄・期限切れ・置換のみが消失系）。

### Scenario: 継続時の構造変化はfail-closedに捕捉される

Given 取り込み済み提案の依頼sessionが記録する構造digestと、rebind時点の現行構造digestが
不一致である（import後にホーム構造が変わった）,
When 再開面で「この提案で続ける」を選ぶ,
Then run admissionの前に（RUN leaseを取る前に）構造digest等価検証がtyped失敗し、
「依頼の内容が古くなりました」相当の原因と「依頼を作り直す」の次の手段が再開面上で
表示される（TO-BE §10の `CONTEXT_STALE` 行と同一のremedy語彙）,
And 提案は削除されず、workspace書込みは0件である。

### Scenario: rebindのadmission直前の無効化競合はrun admissionを発生させない

Given 再開面で「この提案で続ける」が押され、rebuild入力の再構築が成功した直後に、
(i) 新しい依頼の生成によるsession置換（新session保存＋旧record削除）、(ii) 破棄tombstone
commit、(iii) 依頼sessionのTTL失効、のいずれかがadmissionの前に発生した
（事前のreconcile・rebuildはgate外で行われ、(i)は実際の `ExchangeFlowController.generate()`
相当の置換経路による。テストではrebuild完了後に置換を完走させ、その後admissionする順序を
決定的に再現する）,
When fresh run admission（`start`呼出）が行われる,
Then admission直前のanchor再検証（gate保持下での新鮮なrecord・session・clockの読み直し）が
typed拒否し、**run admissionは発生しない**（RUN leaseは取得直後に解放され、`State.Capturing`
を含む可観測run state・journal書込は0件である）,
And 再開面は直ちに読み直され、recordが無効化済みなら#374契約どおりfail-closedに清掃して
面を閉じ、同一session宛の再取り込みでrecordが置換されていたなら新しいrecordの表示へ更新する
（staleな提案の継続・staleな表示の継続はどちらも発生しない）,
And この拒否でworkspace/layout DB・run journalへのwriteは0件である（stale/破損recordの
#374 housekeeping清掃は既存durable mutationとして許容）。

### Scenario: 同一回答の再取り込みでentryKindが変わった場合もanchorは置換として扱う

Given run-in由来の提案を再開面で開き「この提案で続ける」を押してrebuildが成功した直後に、
同一session宛へ同じ回答textが再取り込みされ、内容（canonical decisions・identity）が同一の
まま `entryKind` のみが変わった（RUN_IN → IDLE または IDLE → RUN_IN）新recordで置換された,
When fresh run admissionが行われる,
Then anchorのrecord完全一致比較（`entryKind`を含む）が不一致を検出し、typed拒否して
run admissionを発生させない（旧record由来の復元モードでadmissionする経路は存在しない）,
And 再開面は新recordを読み直してその表示へ更新する。

### Scenario: 再開CTAのsingle-flightとBusy

Given activeなrunが存在する（またはCTA処理中である）,
When 再開面で「この提案で続ける」を選ぶ,
Then typedな拒否（run使用中）が表示され、run admission・提案の状態変化は発生しない,
And CTAの二重押下は1回のみ処理され（single-flight）、処理中は破棄・Backが不受理である
（spec 328 AC-3と同一の規律。Contract notes 4）。

### Scenario: 継続成功は提案を消費しない

Given rebindによるfresh runが開始され、選択・確認が進んでいる（または中断された）,
When hubのstatus cardを読む,
Then 取り込み済み提案の行は残っており（消失系は破棄・期限切れ・置換のみ。#374契約の継続）、
依頼が有効な間は再度「この提案で続ける」で新しいfresh runを開始できる
（単一active run gateにより、runが生きている間はBusy拒否になる）,
And 再開CTAの成功・失敗・拒否のいずれもdurable record・依頼session・layout DBへのwriteを
発生させない（zero-write）。

### Scenario: 生存runへのattach契約は現行どおり（回帰）

Given 同一process内でrunが選択面を開いており、run-in exchangeが進行している,
When exchange処理中・完了直後に選択面とrunの状態を観察する,
Then 選択凍結（exchange step中の選択編集・confirm無効化）・RUN lease継続・authoring不可・
同一runIdへのsingle-shot attach（2回目のattachは`NotAttachable`）・attachの対象は選択面が
開いている生存runのみ、は本spec適用前と同一である,
And gate失敗時にbound intentが破棄され、新しい依頼のre-attachが可能な現行挙動も
続行不能cause経路の契約として維持される。

### Scenario: 完全一致gate・zero-writeの回帰

Given 本specの変更が適用されている,
When spec 331 AC-2/AC-6対応の既存契約test（`ScopeBindingGate`の完全一致・欠落・追加・
無効化・投影digest不一致、選択集合の往復、idle gate適用）を実行する,
Then すべて無編集でgreenである（gate規則・`ScopeMismatchCause` enum・zero-write性・
fail-closed性に変更がないことの証拠）,
And 新規に追加されるのはcause→remedyの対応づけと表示・復元初期値・再開CTA・
継続時再検証のみである。

## Failure behavior

| Condition | Observable outcome |
|---|---|
| confirm時の選択集合差（依頼時候補がすべて解決可能） | `SET_MISMATCH`。差分強調＋「依頼時の集合へ戻せば続行可」案内。intent維持、zero-write |
| confirm時の選択集合差＋解決不能候補 | 続行不能cause（`CANDIDATE_UNRESOLVED`優先）。「依頼を作り直す」案内。zero-write |
| 投影digest不一致（availability/分類の変化） | `PROJECTION_MISMATCH`。「依頼を作り直す」案内。intent破棄（現行契約）。zero-write |
| rebindの選択面で依頼時候補が解決不能 | confirm時に続行不能causeでfail-closed。「依頼を作り直す」案内。提案は残存 |
| rebind時点で構造digest不一致 | 継続前のtyped失敗（`CONTEXT_STALE`意味論）。「依頼を作り直す」案内。run admissionは発生しない。提案は残存 |
| recordのidentity値が破損（schema不一致・digest長不正。JSONとしては読める） | 破損として`reconcilePendingIntent`が`Invalid`へ。例外化せずtyped fail-closedで清掃・継続拒否。run admissionは発生しない |
| 無効化commitのtombstone書込失敗（`WriteFailed`） | typedかつretryableな失敗。無効化は効力を持たず提案は有効・表示のまま（ユーザー破棄失敗と同一様式）。cancel/supersede導線は再試行する |
| admission直前にsession置換・破棄tombstone・TTL失効が発生（anchor拒否） | typed拒否。run admission・可観測run state・journal書込は0件。recordが無効化済みなら#374契約どおり清掃して面を閉じ、同session宛の再取り込みで置換されていれば面を読み直す |
| rebind CTA時にrun使用中 / CTA処理中 | typed拒否（Busy相当）。single-flight。提案・runは不変 |
| 検出が`Unavailable`（rebind含む） | 現行どおり選択面を開かずcomposeへ続行し、gateは既存経路でtyped終端（zero-write） |
| 提案がreconcile不通（session不一致・TTL・破棄mark） | 再開面CTAは表示されない（#374契約。本Issueは検証を追加しない） |

## Stale state / concurrency

- 完全一致gateの評価はconfirm時（選択確定点）とcomposition後（投影digest）の既存2点で行われ、
  選択・検出・compositionの間の状態変化は既存の3段stale構造（spec 331 §3）で捕捉される。
  本Issueはgateの検証点を増やさない（継続時構造digest再検証は「import時検証の再適用」であり、
  gateの新設ではない）。
- **rebind admission anchor（本Issueが新設する検証点。gateとは別の、admission側の契約）**:
  再開CTAの継続では、rebuild入力の再構築が成功した後も、**run admissionの直前まで提案が
  有効であることを再検証してからadmissionする**。anchorの判定条件は、新鮮に読み直した
  (a) durable record・(b) active session・(c) 注入clock に対して、
  `reconcilePendingIntent`（#374正本）が `Valid` であること、recordがrebuild入力の源となった
  recordと**完全一致**すること（`DurablePendingIntent`のdata class同値。`entryKind`を含む
  全field。同一session宛の再取り込みで内容が同一でも `entryKind` のみ変化した場合 —
  復元モードの変化 — も置換として検出する）、sessionがadmission時点で失効していないこと、
  recordが破棄mark（tombstone。flow内部の無効化commitを含む）を持たないこと
  （`reconcilePendingIntent`の既存検証がgate内で再実行される。無効化commitはgate上で
  tombstone commitとして効力を持つ。exchange mutation gate節）,
  のすべてが真であることである。判定とadmission（operation生成・`State.Capturing`発行）は
  **同一の排他境界内**で行われ、拒否時はprovisionalに取得したRUN leaseを即時解放して
  typed拒否として終わる（可観測run state・
  journal書込は0件）。
- **exchange mutation gate（本Issueが新設するprocess-wideな共有排他seam）**:
  anchorの読み取りとadmissionが、**active sessionとdurable recordの両方を変化させうる
  全操作** — session置換（新session保存＋旧record削除。`ExchangeFlowController.generate()`
  経路）、pre-send cancel等のsession invalidate、import成功時のdurable保存、破棄tombstone、
  reconcile清掃（`openPendingImportReview()` / anchor拒否後の `delete()`）**および**
  起動時reconcile清掃（`PendingImportStartupReconcile` の load→reconcile→delete。
  いずれもgate配下へ移す — 7th review指摘3）
  — と混線しないことを、**単一のprocess-wideな直列化点**で構造的に保証する。
  現行mainではsession置換がholder mutex外で実行され、session storeとpending storeが
  別々の内部lockを持つため、holder内の書込mutex拡張だけでは排他として不十分である
  （2nd review指摘1。初回re-entryの「`pendingWriteMutex`拡張」案を廃止して本設計へ改訂）。
  **gateの保持区間**: 事前計算（record/sessionの予備読取・reconcile・rebuild入力の再構築）は
  **gate外**で行い、**admissionの直前のみgateに入る**。gateが保証するatomic区間は
  **新鮮なrecord/session/clockの再読取 → anchor判定 → operation生成・`State.Capturing`発行
  まで**である。RUN leaseの取得は現行 `beginOperation()` 構造どおりの **provisional取得
  （gate外・run lock取得前）**であり、それ自体はrun admissionに数えない
  （anchor拒否時は即時解放される。4th review指摘2の統一）。
  gateを解放してから制御を返し、検出（detection）・composition等の長時間処理は必ず
  gate解放後に行う — 現行 `ManualOrganizationRun.start()` はadmission後も同一同期呼出内で
  検出まで進むため、呼出側がgateを保持したまま `start()` を呼ぶ設計はこの界限に違反し、
  race oracleとも両立しない（3rd review指摘。anchor内部でgateに入りadmission完了まで
  保持する形に固定）。実装形態はplan.mdに記載のとおり **process-wideなblocking lock
  （monitor/`ReentrantLock`等）であり、#374のwrite serializationを完全に包含して
  `pendingWriteMutex`（Coroutine Mutex）を廃止・置換する**。gate保持中にsuspension pointを
  作らない（5th review指摘2で固定。実装PRへ残すのはDI提供位置など正当性に影響しない
  詳細のみである）。
- **gate下のUI待機禁止（4th review指摘1）**: exchange mutation gate保持中は
  `withContext(uiDispatcher)` 等によるMain dispatcherへの切替・完了待機を**絶対に行わない**。
  gate下の処理はIO上で完結する純粋なstore操作と判定のみとし、UI stateへのsettleは
  gate解放後に行う。#374のsave fence
  （`launchDurablePendingIntentSave` がmutex保持下で `withContext(uiDispatcher)` により
  settleする現行構造）は、**IO上の短いcritical sectionでrecord書込・stale判定・条件付き
  cleanupを完結させて純粋なsettle結果を作り、gate解放後にUIへsettleする**形へ
  本Issueがrefactorする対象に含める。#374のsave fence oracle（cancel/supersede中の
  stale record残存なし）は維持される。
- **gate上への線形化統一と純粋投影settle（5th/6th review指摘1）**: durable saveの
  「有効なcommit」とattempt無効化（cancel / supersede / input edit / RUN_IN owning run消失
  fence）の「無効化commit」の**効力発生点をexchange gate上で1つに固定する**。無効化commitは
  **#374のtombstone機構（`discarded=true`へのatomic書換 → best-effort物理削除）を
  gate保持下で実行する**形とする。gate内の条件付き無効化操作（`discardIf(expectedRecord)`
  相当）は結果を**`Committed`（tombstone commit成功）/ `NoMatch`（対象recordが既に
  存在しない・より新しいrecordに置換済み）/ `WriteFailed`（tombstone atomic書込失敗）**
  に区別する（7th review指摘1）:
  (a) `Committed` と `NoMatch` の場合のみ無効化成功としてlinearizeする
  （`NoMatch` は対象が既に存在しないため、復活しうるstale recordは存在しない）,
  (b) `WriteFailed` の場合は**無効化成功として確定しない** — typedかつretryableな失敗として
  扱い、proposalは有効・表示のまま残る（ユーザー破棄のtombstone失敗と同一のfail-closed様式。
  cancel/supersede導線は無効化commitを再試行する。process death後も `discarded=false` の
  recordが残るが、それは「無効化が効力を持っていない」ことの正しい帰結である）,
  (c) 物理削除はgate内の後続処理でbest-effortに行い、完了前のprocess death・
  holder破棄が発生しても次回読取のreconcile（既存の破棄mark検証）が当該recordを
  Invalidとして扱うため、「無効化が効力を持った提案をadmitする」経路がcold rebindを含めて
  存在しない。anchorはgate内でreconcileを再実行するためtombstone検証を自動的に含む
  （#374 Fence 2の線形化をgate解放後の分割でも損なわない。in-memoryなcommit状態は
  持たない — 6th review指摘1のlifetime/bootstrap問題を構造で排除）。
  **gate解放後のUI settleはscreen/stateの投影のみに限定し、storeの `save` / `deleteIf` /
  `discard` / `delete` を一切呼ばない**。durable saveをUI settle直前でbarrier停止させ
  Main側でrebind admissionを開始する決定的oracleで、無効化が先に効力を持った場合は
  `State.Capturing` が0件であり、cleanup完了前でもanchorがAdmitしないことを
  wall-clock非依存で固定する（SR-AC-08）。本設計はrecord model（`DurablePendingIntent`の
  field構成）を変更しない（Non-goalsどおり。既存 `discarded` tombstoneの転用であり、
  ユーザー可視の破棄語彙・D-13確認契約は関与しない）。
- **gate下の処理時間の界限**: gate保持区間はrecord1件・session1件の小さなlocal file読書き
  （`AtomicFile`）とadmission判定・operation生成のみに限り、readiness gate・model load・
  候補検出等の長時間処理をgate下で行わない。他の面でのcancel/confirmがblockされるのは
  これらの短い区間のみである。検出開始時点でgateが解放済みであることを構造的に確認する
  （検出seamへのprobeでgate非保持を観測するtest。SR-AC-08）。
- **TTL失効との競合の決定性**: TTLは時刻のみの競合であるため、anchorはgate保持下の
  admission区間内でclockを新鮮に読み、検証とadmissionが同一時点の値を共有する。
  「rebuild成功 → TTL境界越え → admission」「rebuild成功 → 置換/破棄 → admission」の各競合を、
  決定的なrace oracleとして（fake clock / store注入、かつ置換は実際の`generate()`相当経路と
  の並行で）固定する（SR-AC-07/08）。
- 復元初期値の材料（依頼scope候補identity）と再開面の表示は、#374の読取時reconcile通過後の
  値のみを使う。anchor拒否後の面の扱いは: record無効化済み → #374契約どおりfail-closed清掃・
  面を閉じる。record置換済み（同一session宛の再取り込み）→ 面を読み直して新しいrecordの
  表示へ更新する（stale表示の継続をしない）。
- 単一active run gate（`start()`の`Busy`）がrebindと通常開始・attachの競合を構造的に排他する。
  CTAのsingle-flightはUI層の規律、anchorはadmission側の検証、run gateは単一active runの
  構造的排他であり、役割を混同しない（spec 328の「UI disabledはaffordanceにすぎない」
  原則の継承）。

## Data and state

- **読むdata**: #374のdurable record（`exportId`・`intentIdentity`・canonical decisions・
  `entryKind`。継続可否・identity注入・idle/run-in由来判定）。現行active session
  （`ExportSession.scopeCandidates` = 依頼scope候補identityの正本、`sourceContextDigest`・
  `scopeCandidateDigest`、`itemRefs` / `categoryRefs`、失効時刻）。検出cut（identity +
  availability）。現行構造digest。既存clock注入に従う。anchorはrebind継続のadmission直前に
  record・session・clockを**新鮮に読み直す**（Stale state / concurrency節）。
  #374の読取時reconcileを本specは再定義しない（通過済みの提案のみが継続対象）。
- **書くdata**: なし（**workspace/layout DB・`favorites`・run journal・新規persistent state・
  schemaへの書込みは発生しない**。rebind継続のCTAは成功・失敗・拒否のいずれでもlayout DB /
  journal / export sessionへのwriteを行わない）。復元初期値はprocess-localな選択stateの
  初期値であり、persistしない（spec 228 D-1の継続）。anchor拒否後のstale/破損recordに対する
  #374 reconcile経由の清掃（`delete()`）は、**#374が既に持つdurable housekeeping**であり
  本契約の「書くdata: なし」には含まない（2nd review指摘4。受入testではlayout/journalの
  zero-write否定的観測と、pending store cleanupの件数を分離して観測する）。
- **Identity**: 依頼scopeの正本は依頼sessionの`scopeCandidates`（`CandidateTarget.AppKey` =
  `ComponentKey` + `ProfileId`）。復元初期値はこの集合と現行検出cutの積集合であり、
  ref値は復元に不要である（ref対応表の正本はsession側。TO-BE §13-6「refs→candidate identityの
  復元はexport session内の対応表で可能」）。rebindでplannerへ渡す入力は既存
  `ValidatedPersonalizedIntent`契約（session + 提案内容）を満たすようにrebind seamで再構築され、
  validation意味論を迂回しない（構築方法は[plan.md](./plan.md)が所有。disposition §11）。
  **identity値のshape検証**: recordの `intentIdentitySchemaVersion` / `intentIdentityDigest` は
  #374保存時にはnon-empty検査のみであり、破損（schema不一致・digest長不正）でも
  読み得る可能性がある。rebindはidentity値を `IntentIdentity` へ構築する前にschema/digest
  不変条件（current schema一致・digest長64）を**純粋に検証**し、不成立は破損として
  `reconcilePendingIntent` を `Invalid` へ落とす検証として扱う（例外経路を生まない。
  fail-closed清掃・継続拒否。#374の破損契約へのidentity次元の追加で、本Issueが
  reconcile純粋関数へ追加する検証。2nd review指摘3）。
  **rebindのintent content identityは、#374 recordがimport時に保存した `IntentIdentity`
  （schemaVersion+digest。`rationale`/`confidence` を含むcanonical表現から算出済みの正本）を
  そのまま注入する。rebind seamがidentityを再導出することはない**（canonical decisionsからは
  `rationale`/`confidence` が復元できないため再導出値はimport時identityと一致する保証がなく、
  保存済みidentityの注入により `PersonalizedIntentProjection.identity` → policy provenanceの
  同一性がimport直後とcold rebind後で完全一致する。#374 spec Contract notes 3の確定回答の
  受領。review指摘2の解消）。planner投影（`IntentPlannerAdapter.project`の全field:
  identity・itemPreferences・`groupProposalLabel`・`globalMinimizeMovement`）がimport直後と
  cold rebind後で全field同一であることをoracleで固定する（#374 DI-AC-01の再構成等価oracleと
  同一基準をrebind経路へも適用）。
- **migration / backup / DB**: 新規persistent state・migration・backup影響なし。 downgrade /
  rollback（PR revert）で#374のstoreが無い環境では、本機能の再開面CTAは対象recordが存在しない
  ため自然に無効になる（表示経路も存在しない）。
- **ホームレイアウト安全規約**: 本機能は`favorites`へ接触しない（zero-write gateの継続）ため
  適用対象外である。

## Permissions, privacy, and security

- None — 新規permission・network・外部送信経路はない。既存Pre-send Disclosure契約は不変。
- 差分強調・復元初期値で表示するのは、選択面が既に表示している候補のapp label / iconと同一の
  情報classのみである。依頼scopeのidentity・ref・`rationale`・`confidence`はUIへ現れず、
  #374のprivacy境界（recordに永続化しない）を本specも維持する。
- rebindのplanner入力再構築はvalidation済み内容（#374 record）とsession正本のみから行われ、
  回答textの再解釈・再validation緩和は発生しない。fail-closed境界（完全一致gate・zero-write・
  coverage・allow-list）はすべて維持される。

## Accessibility and localization

- 差分強調は色のみに依存しない（icon/text/state descriptionの併用）。TalkBackで強調の意味
  （依頼では対象 / 依頼外）と選択状態が読み取れること（name/role/state）。
- reject・継続不能・構造変化の案内は既存のlive region機構でannounceする（現行
  `missing-app-selection-scope-mismatch`の様式継承）。復元初期値の適用時、選択数の変化は
  status行としてannounceする（spec 228契約の継承）。
- 再開面CTA・選択面・確認面を通じて、「status card → 再開面 → CTA → 選択面 → confirm」が
  TalkBack / Switch Access / keyboardで完結し、200% font scaleで到達可能である
  （organization-run-ux §6受入基準）。
- 新規・改訂stringは`values/` + `values-ja/`（正本）の双方へ供給し、複合文はformat resourceで
  構成する（spec 123 AC-4/AC-5）。CTA labelはTO-BE T-18語彙「この提案で続ける」
  （spec 328 rev.2 D-3統一copyと同一）。破棄・依頼・提案の語彙はTO-BE §9（D-13）に従う。

## Acceptance criteria

- [ ] **SR-AC-01**: `SET_MISMATCH` で選択面が依頼時集合との差分を強調し（依頼に含まれ未選択 /
      依頼外の選択を区別でき、色のみに依存しない）、選択を依頼時の集合へ修正してconfirmすると
      **同じ提案（bound intent）で続行できる**ことがtestされる。修正しない場合は依頼の作り直しを
      案内する。gate通過までzero-writeである。（Issue受入1）
- [ ] **SR-AC-02**: 続行不能cause（`PROJECTION_MISMATCH`・`CANDIDATE_UNRESOLVED`）では同じ提案で
      の続行ができず、依頼作り直しへ案内されることがtestされる。confirm時早期gateのcause導出が
      availability を考慮し、解決不能候補がある場合に「選び直せば続行できる」誤った案内を
      表示しないことがtestされる。（Issue受入2）
- [ ] **SR-AC-03**: process死後に取り込み済み提案（run-in由来）から再開面CTAでfresh runを開始し、
      scope完全一致検証 → 選択復元初期値 → 明示確認1回 → 続行できることがtestされる
      （冷起動emulator evidenceを含む）。CTA押下までrun admission・書込みが発生しないこと。
      idle由来には復元がなくunchecked初期値・件数案内であること。rebind経路のplanner入力は
      record保存の `IntentIdentity` を用い、rebind後のplanner projection（identity・
      itemPreferences・`groupProposalLabel`・`globalMinimizeMovement`）がimport直後と
      **全field同一**であることがunit oracleで固定される（identity再導出を行わないことの
      証拠）。（Issue受入3）
- [ ] **SR-AC-04**: 完全一致gate・zero-write・fail-closedに変更がないことが、spec 331
      AC-2/AC-6対応の既存契約testの無編集greenで証拠づけられる。（Issue受入4）
- [ ] **SR-AC-05**: 明示選択契約（spec 228 D-1: unchecked初期値・明示的選択）が弱められていない
      ことがtestされる。復元は初期値であり明示confirm（1回）を経てのみ確定する。非rebind経路
      （通常run・idle継続）の初期値はuncheckedのままである。（Issue受入5）
- [ ] **SR-AC-06**: run-in相談のattach契約（同一runId single-shot・選択凍結・RUN lease継続・
      authoring不可・生存run限定）が現行どおりであることが既存回帰testで証拠づけられる。
      （Issue受入6）
- [ ] **SR-AC-07**: 再開CTAがreconcile通過済み提案のみに表示され、single-flight・Busy時typed
      拒否・処理中の破棄/Back不受理を持ち、**成功時も提案を削除しない**（消失系は#374契約どおり
      破棄・期限切れ・置換のみ）ことがtestされる。**「rebuild成功 → session置換（record無効化）
      → admission」「rebuild成功 → 破棄tombstone commit → admission」の決定的race oracleが
      存在し**（置換は実際の `ExchangeFlowController.generate()` 相当経路との並行で再現）、
      いずれもanchorがtyped拒否してrun admission・可観測run state・journal書込を発生させない
      ことがtestされる。**同一回答の再取り込みで `entryKind` のみ変化したrecord置換もanchorが
      検出して拒否する**（新recordへの読み直しを含む）ことがtestされる。否定的観測は
      layout/journal 0件とpending store cleanup件数を分離して固定する。
- [ ] **SR-AC-08**: rebind継続の前に構造digest等価検証（`CONTEXT_STALE`意味論）が適用され、
      不一致がtyped失敗（依頼作り直し案内・run admissionなし・提案残存）として扱われることが
      testされる。**exchange mutation gateがsession置換・pre-send cancel等のsession変化操作・
      record変化操作の全て（通常のreconcile清掃〔`openPendingImportReview()` /
      anchor拒否後〕**および**起動時reconcile清掃〔`PendingImportStartupReconcile`〕の
      両経路を含む）とrebind admissionを直列化すること**（gate不在時に入る競合経路が
      存在しないことのdiff review＋gate下の処理時間界限のtest）が確認される。
      **rebind admission anchorがgate保持下のadmission直前に新鮮な読み直しで判定され、
      Admit時はoperation生成・`State.Capturing`発行までをgate内で完結すること、
      「rebuild成功 → TTL境界越え → admission」の決定的race oracleが存在して**、anchorが
      typed拒否し（lease解放・状態発行なし）admissionが発生しないことがtestされる
      （fake clockで再現）。**検出（detection）がgate解放後に開始されることが検出seamへの
      probeで構造的に確認される**こと（wall-clock依存でない）。
      **identity値の破損（schema不一致・digest長不正のvalid JSON）が
      例外化せずtyped fail-closed（Invalid清掃・継続拒否）として扱われること**が
      fixture付きでtestされる。anchor拒否後の面の扱い（無効化済み→清掃・クローズ、
      record置換済み→読み直し）がtestされる。
      **gate下のUI待機禁止のoracle**: durable saveをUI settle直前でbarrier停止させ
      Main側でrebind admissionを開始する順を決定的に構成し、gate保持中にMain dispatcherへの
      待機が発生しないこと（双方が進行可能であること）をwall-clock非依存でtestされる
      （#374 save fence refactor後の回帰を含む）。
      **線形化oracle（tombstone正本）**: save完了・gate解放後、UI settle直前で停止した状態で
      cancel/supersede（RUN_IN owning run消失を含む）を実行 → 無効化commit（gate上の
      tombstone commit）が先に効力を持った場合は `State.Capturing` が0件であり、物理削除
      完了前でもanchorがAdmitしないことがtestされる。**Holder Aでsave commit → UI settle前に
      無効化 → 物理削除をbarrier停止 → Aを破棄して別holder（およびprocess recreation相当の
      in-memory全破棄）からrebindした場合も `State.Capturing` 0件であること**、および
      **対照ケースとして正常commit済みrecordは新holder / cold processからAdmitできること**
      をtestされる（crash-safeな正本としてのtombstone検証）。
      **write-failure oracle（7th review指摘1）**: 対象record一致の状態でtombstone atomic
      writeを故障注入で失敗させ（`WriteFailed`）、holder破棄 / process recreation後も
      「成功扱いされた無効化」が存在しないこと（無効化は効力を持たずproposalが有効のまま、
      失敗がtyped/retryableに観測されること）をtestされる。
- [ ] **SR-AC-09**: spec 331改訂（D-2 remedy分割・§5経路更新・attach生存範囲明確化）と
      spec 228注記（復元初期値とD-1の関係）が作成され、**owner受入済み**である
      （受入自体は実装PR前のdocs変更）。
- [ ] **SR-AC-10**: 新規・改訂のuser-visible文字列がEN/ja双方に存在し、format resource規約に
      従い、TO-BE §9/§10語彙と衝突しないことが機械確認される。差分強調・復元・CTA・案内の
      a11y evidence（TalkBack / Switch Access / keyboard / 200% font）がある。

## Test oracle

| AC | Evidence |
|---|---|
| SR-AC-01 | unit: `ManualOrganizationRunTest`拡張（早期gateの`SET_MISMATCH`でintent維持・修正後confirm通過・zero-write）。instrumentation: `MissingAppSelectionInstrumentationTest`拡張（差分強調の表示・依頼外/依頼内の区別・非色依存のsemantics） |
| SR-AC-02 | unit: 早期gateのcause導出（解決不能候補の優先。fixture: uninstall / disable / 配置済み）＋投影digest不一致の案内区分。既存 `ExchangeTargetScopeCouplingTest`（gate判定の回帰）は無編集green |
| SR-AC-03 | instrumentation: 冷起動 → status card行 → 再開面 → CTA → 検出 → 復元初期値の選択面 → confirm → 確認面到達の統合test＋冷起動emulator evidence。unit: 復元導出の純粋関数（依頼scope ∩ 検出cut、決定性）、**rebind再構築seamのprojection全field等価oracle（identity=record保存値。import直後の`IntentPlannerAdapter.project`結果との同一）＋record decisions→再構築→completedの往復property test**。idle由来の非復元test |
| SR-AC-04 | 既存 `ManualOrganizationRunTest` / `ExchangeTargetScopeCouplingTest` のscope gate系test群の無編集green + diff review（gate判定・enum・zero-write無変更） |
| SR-AC-05 | instrumentation: 通常run・idle継続の初期値unchecked回帰 + rebind復元の「編集可・confirm必須」test（confirmなしでplanへ進まない否定的観測） |
| SR-AC-06 | 既存attach/freeze系oracle（`ManualOrganizationRunTest`のattach回帰、`ExchangeImportSuccessInstrumentationTest`のCTA契約）の無編集green |
| SR-AC-07 | holder/instrumentation test（reconcile不通でCTA非表示、single-flight、Busy拒否、成功後のstatus card行残存・再継続可）+ **race oracle（fake store/clock＋実際の`generate()`相当置換経路との並行）: rebuild成功後のsession置換・破棄tombstone → admission anchorがtyped拒否・run不在（`State.Capturing`非発行・journal書込0件。layout/journal 0件とcleanup件数を分離観測）** + **entryKindフリップ再取り込みoracle（置換検出→拒否→新record読み直し）** |
| SR-AC-08 | unit: 構造digest再検証の純粋判定（一致/不一致/session不在）+ **anchor判定のtable test（Valid/record不一致〔entryKind含む〕/TTL越え/identity shape不正）＋「rebuild成功 → TTL境界越え → admission」race oracle（fake clock）** + **identity破損fixture（wrong schema・digest長不正のvalid JSON）で例外なし・typed fail-closed・run不在** + instrumentation（CTA押下でtyped失敗・run不在の否定的観測。anchor拒否後の面の扱い：清掃クローズ・読み直し更新） |
| SR-AC-09 | spec 331/228 diff review（改訂内容がScope節と一致し、gate規則を変えないこと）+ owner受入記録（Issue #375コメント） |
| SR-AC-10 | strings走査（ja/en name集合・placeholder一致。spec 123 AC-5方式）+ hardcoded literal grep + a11y assertion（semantics/live region/traversal/200%）+ light/dark × ja/default screenshot |

共通gate: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest
--tests 'app.lawnchair.organizer.*'`、対象classのorganizer instrumentation lane
（`MissingAppSelectionInstrumentationTest`、`ManualOrganizationPreferencesInstrumentationTest`、
exchange系）、CI `final-status` green。本Issueはpersistent state変更・DB書込み経路の変更を
含まないため `risk: layout-data` / `risk: migration` は付けない（label決定は実装PRで行う）。

## Contract notes（owner reviewで確認すべき解釈）

1. **継続時の構造digest再検証**: Issue本文は「scope完全一致検証」を規定するが、依頼sessionの
   placed側構造digest（`sourceContextDigest`）の再検証は明示していない。本specは **rebind継続の
   前提検証として必須**（typed失敗・run admission前・提案残存）とする。根拠: #374により
   validated内容が最長24時間存続し、import時の`CONTEXT_STALE`検証が参照する構造が保証されなく
   なる。再検証が無いと候補側2検証を素通りした入力がplanner投影（消失ItemId参照）に到達しうる
   （fail-closedではなくfail-openの経路になる）。同一process内CTA（spec 328 AC-3経路）へも同一検証を
   適用するかは、同一seam上の整合から適用を推奨するが、owner reviewの確認対象とする
   （適用しない場合は現行の短時間窓のままの既存挙動であることを記録）。
2. **継続成功は提案を消費しない**: 再開CTA成功後も提案はstatus cardに残る（#374の消失系契約
   「破棄・期限切れ・置換のみ」を維持）。run中断後に再継続できる利便性と契約整合を優先した。
   継続成功時にrecordを削除する案は、消失系の増加として#374契約に抵触するため不採用。
3. **#374 storeのschema拡張は不要**: 復元材料は依頼sessionの`scopeCandidates`から導出でき
   （#374のlifetime規約により提案が有効な間はsessionも有効）、由来判定は#374が既に保存する
   `entryKind`で足りる。**rebindのidentity注入材料も、#374が既にrecordへ保存する
   `intentIdentity`（schemaVersion+digest。Contract notes 3は#374 spec側で確定済み —
   「#375のrebindは保存済みidentityをそのまま用いる」）であり、本Issueでrecord schemaを
   変更しない**（review指摘2のidentity契約は#374 revision 2との契約連携で解消済み）。
4. **再開面CTAの規律はspec 328 AC-3と同一**: single-flight・attempt的同一性（再開面の表示対象
   recordとの一致）・処理中の不受理・Busy typed拒否を既存CTA契約と同じ規則で適用する。
   既存の同一process内CTA（#374維持）の挙動は変更しない。
5. **同一process内`SET_MISMATCH`の「同じ提案で続行」**: 現行実装でも早期gate失敗後にintentが
   維持され、選択修正→再confirmで続行できる（構造としては既に存在）。本specはこれを
   契約として明文化し、差分強調と原因別案内を追加する。既存test
   （早期gate `SET_MISMATCH` + `intentScopeCount` 維持）は回帰として維持される。

## Open questions

実装開始前に解消が必須な問いはない（Contract notes 1〜5の解釈確認をowner reviewが所有し、
確認前は本specは`draft`のままである）。非blocking事項:

1. 差分強調の視覚表現（row badge / 選択状態の語彙 / 強調区間）はTO-BEが視覚designを扱わないため
   実装PRのreviewで確定する（非色依存・a11y契約のみ拘束）。
2. 継続不能・構造変化・差分強調の最終copy（ja/en）は実装PRのstring diffで確定する
   （語彙規約への従属のみ拘束）。
3. #369のT-13統合失敗面・#373の手段別再投影は実装済みである。本Issueのcause別文言は
   現行の統合失敗面・手段別再投影の文言供給源として接続する（`ScopeMismatch` のremedy
   区分は#373実装の3分類（RETRY_IMPORT/REPASTE/RECREATE_REQUEST）を変更せず、選択面の
   SET_MISMATCH修正案内を本Issueが追加する。表示面の抽象は実装PRで確定）。

## Change history

- 2026-09-22: **Re-entry revision 8（8th review 2026-09-22 Changes requested 1件対応、
  [comment `5766986330`][12]）**。
  **(1) gate対象一覧の正本間一致（中）**: 7th対応でspecのgate対象列挙から通常のreconcile
  清掃（`openPendingImportReview()` / anchor拒否後の `delete()`）が落ちてplanと不一致に
  なっていたため、**通常reconcile清掃と起動時reconcile清掃の両経路**を明示して一致させた。
  SR-AC-08のgate契約確認も2経路を両方gate配下とする形へ揃えた。
- 2026-09-22: **Re-entry revision 7（7th review 2026-09-22 Changes requested 3件対応、
  [comment `5766867716`][11]）**。
  **(1) tombstone書込失敗時の無効化commit意味論の契約化（高 — blocking）**: 現行
  `PendingImportedIntentStore.discard()` はatomic書換失敗時に `false` を返しrecordを
  有効のまま残すため、gate内条件付き無効化の `WriteFailed` 時の遷移が未規定だと
  「Main側では無効化済みだがtombstoneだけ失敗しprocess death後にrecordが復活する」経路が
  残る。条件付き無効化操作（`discardIf(expectedRecord)` 相当）の結果を
  `Committed` / `NoMatch` / `WriteFailed` に区別し、**`Committed` と `NoMatch` のみ無効化
  成功としてlinearize**、`WriteFailed` はtypedかつretryableな失敗（無効化は効力を持たず
  proposal有効・再試行）へ契約化。write-failure oracle（故障注入 → holder破棄 /
  process recreation → 成功扱いされた無効化が存在しないこと）をSR-AC-08へ追加。
  **(2) 廃止済み選択肢のplan内残存を削除（中）**: Explicitly unverified areas に残っていた
  「record commit状態の保持形態（holder内field / gate object内）」の選択肢を削除し、
  「無効化の正本はdurable tombstoneのみ。in-memory commit stateは導入しない」を明記。
  **(3) startup reconcileのgate配下化（中）**: `PendingImportStartupReconcile` の
  load→reconcile→delete（専用thread起動）をgate対象操作へ追加し（gate注入）、「全
  record/session mutationが同一gateを通る」前提をコード構造で成立させる。Design 7対象一覧・
  Execution checklistへ追加。
- 2026-09-22: **Re-entry revision 6（6th review 2026-09-22 Changes requested 2件対応、
  [comment `5766679997`][10]）**。
  **(1) 無効化commitの正本をdurable tombstoneへ固定（高 — blocking）**: 「in-memoryな
  record commit状態は寿命・初期化契約がcold rebindと両立せず（holderはroute change /
  recreationで破棄、cold resumeはdurable record/sessionのみから再構成）、正当なrecordを
  拒否するかcleanup待ちの無効recordを再admitするかの二択になる」指摘に対し、in-memory
  commit状態を廃止し、**attempt無効化commitをgate保持下でのtombstone commit
  （既存 `discarded=true` atomic書換 → best-effort物理削除）として正本化**。tombstoneは
  durable・crash-safeであり、物理削除完了前のprocess death / holder破棄でも既存reconcileの
  破棄mark検証がInvalidとして扱うため、cold rebindを含めて「無効化済み提案のadmit」経路が
  存在しない。reviewer要求のoracle（Holder A→B横断・process recreation相当・対照ケース）を
  SR-AC-08へ追加。record model不変（Non-goals維持）。
  **(2) spec側のlock primitive契約の一意化（中）**: spec gate節に残っていた
  「`pendingWriteMutex`包含可否は実装PRで確定」の文言を削除し、**blocking gateによる
  write serialization包含・mutex廃止・gate内suspension禁止**をspec本文へ明記
  （実装PRへ残すのはDI提供位置等のみ）。
- 2026-09-22: **Re-entry revision 5（5th review 2026-09-22 Changes requested 2件対応、
  [comment `5766543051`][9]）**。
  **(1) save commitとattempt無効化のgate上への線形化統一（高 — blocking）**: 「gate解放後の
  UI settleまでにattempt無効化が起きると、cleanupより先にrebind anchorが保存済みrecordを
  admitでき、#374 Fence 2の線形化が失われる」指摘に対し、durable saveの「有効なcommit」と
  attempt無効化の「無効化commit」の効力発生点をexchange gate上で1つに固定
  （gate保護下のrecord commit状態〔committed-valid / invalidation-pending〕をanchor判定に
  追加。cleanup待ちrecordをValidとしてadmitしない）。**gate解放後のUI settleは
  screen/state投影のみに限定しstore書込を一切呼ばない**ことを契約化。
  「save完了 → gate解放 → UI settle直前で停止 → cancel/supersede → rebind admission競合」の
  決定的oracleをSR-AC-08へ追加。
  **(2) lock primitiveの固定（中）**: blocking gate保持下でのkotlinx Coroutine Mutex
  取得（suspendし得る）を禁止し、**exchange gateが#374 write serializationを完全に包含し
  `pendingWriteMutex`を廃止・置換する**案へ固定（gate内にsuspension pointを作らない）。
- 2026-09-22: **Re-entry revision 4（4th review 2026-09-22 Changes requested 3件対応、
  [comment `5766390303`][8]）**。
  **(1) gate下のUI待機禁止とsave fence refactor（高）**: 「`launchDurablePendingIntentSave`
  がmutex保持下で `withContext(uiDispatcher)` によりsettleする現行構造の下で
  gate配下化すると、Main: run lock → gate待ち / IO: gate → mutex → Main待ち の循環が
  作れる」指摘に対し、**gate保持中のMain dispatcher切替・待機を禁止する不変条件**を新設し、
  save fenceを「IO上の短いcritical sectionでrecord書込・stale判定・条件付きcleanupを
  完結しgate/mutex解放後にUI settleする」形へrefactorする契約を追加（後段cleanup再取得の
  `mutex → gate` 逆順も禁止。#374 save fence oracle維持）。barrier停止による決定的
  deadlock oracleをSR-AC-08へ追加。
  **(2) RUN leaseのprovisional位置づけ統一（中）**: gate内必須とする記述と
  plan/現行構造（lease取得はgate外・run lock前）の割れを解消し、**lease取得はgate外の
  provisional取得でありrun admissionに数えない**。gateのatomic区間は「新鮮読取 → 判定 →
  operation生成・`State.Capturing`発行まで」に統一（Refuse時の即時解放oracleを含む）。
  **(3) anchor引数のnullable明示（低）**: `admissionAnchor` を既定値nullのnullable引数と
  することを明記。
- 2026-09-22: **Re-entry revision 3（3rd review 2026-09-22 Changes requested 1件対応、
  [comment `5766214632`][7]）**。
  **(1) gate保持区間の契約化（中）**: 「呼出側がgateを保持したまま `start()` を呼ぶ設計は、
  `start()` がadmission後も同一同期呼出内で候補検出まで進む現行構造と矛盾し
  （gate長時間保持 → 「短い区間のみ」契約違反）、かつ「rebuild成功 → generate完走 →
  admission → anchor拒否」のrace oracleを構造的に再現できない」指摘に対し、
  **事前計算（予備読取・reconcile・rebuild）はgate外で行い、admission直前のみanchor内部で
  gateに入る**構造へ改訂。gate内で新鮮読取 → anchor判定 → operation生成・`State.Capturing`
  発行までを1つのatomic admission seamとして完結させ、gate解放後に検出へ進むことを契約化。
  検出開始時点でgateが解放済みであることの構造的確認（検出seamへのprobe）をSR-AC-08へ追加。
  race oracleの決定的再現手順（rebuild完了後に置換を完走→admission）をscenarioへ明記。
- 2026-09-22: **Re-entry revision 2（2nd review 2026-09-22 Changes requested 4件対応、
  [comment `5765970137`][6]）**。
  **(1) 排他対象の不足解消（高 — blocking）**: 「`pendingWriteMutex`拡張では排他できず
  TOCTOUが残る」指摘に対し、session置換（`generate()`の新session保存＋旧record削除）が
  mutex外であること・session/pending両storeが別内部lockであることを認め、**active sessionと
  durable recordを変化させうる全操作とrebind admissionを直列化するprocess-wideな
  exchange mutation gate**の新設へ改訂（Stale state / concurrency節・Scope）。gate下の
  処理時間界限（UI block防止）を明記。race oracleを実際の`generate()`相当経路との並行再現へ
  強化（SR-AC-08にgate契約の確認を追加）。
  **(2) record同一性の完全一致化（中）**: anchorの同一性比較を`exportId`+identity+decisionsの
  同値から **`entryKind`を含むfull record equality**へ改訂（同一回答のRUN_IN⇄IDLE再取り込み
  置換を見逃さない）。entryKindフリップoracleをSR-AC-07へ追加（新scenario）。
  **(3) identity破損のtyped扱い（中）**: valid JSONでもidentity値が破損している場合に
  `IntentIdentity`構築時の`require`で例外化される経路を塞ぎ、**schema/digest不変条件の純粋
  検証をreconcile破損検証へ追加**（Invalid清掃・継続拒否。Data and state・Failure behavior、
  SR-AC-08 fixture）。
  **(4) zero-write文言の明確化（低）**: 「書くdata: なし」の対象をworkspace/layout DB・journal・
  新規persistent stateへ限定し、stale/破損recordの#374 housekeeping清掃を既存durable
  mutationとして分離。SR-AC-07/08の否定的観測もlayout/journal 0件とcleanup件数に分離。
- 2026-09-22: **Re-entry revision（初回review 2026-09-19 Changes requested 3件対応
  [comment `5740062562`][5] + 前提merge後のcurrent main `9dc3ec8fed`へのre-entry）**。
  **(1) rebind admission anchor（高 — blocking指摘の解消）**: 「rebuildの有効性確認と
  fresh run admissionの間のTOCTOUで無効化済み提案を開始できる」指摘に対し、rebind継続を
  「rebuild → **admission直前のanchor再検証**（新鮮なrecord/session/clockの読み直しと
  `reconcilePendingIntent` Valid + record同一性 + TTL判定）→ admission」の順へ改訂し、
  判定とadmissionを同一排他境界内に固定、record書込との直列化をholderの書込mutex拡張で
  契約化。初回draftの「既存fail-closed経路で捕捉される（検証の二重化はしない）」規定を廃止。
  「rebuild成功 → session置換/破棄 → admission」「rebuild成功 → TTL境界越え → admission」の
  決定的race oracleをSR-AC-07/08へ追加（新scenario・Failure behavior行・Stale state節の
  全面改訂）。
  **(2) identity契約の確定（中）**: 「#374 recordから現行 `IntentIdentity` を再現できず
  rebind後のprovenance identity契約が未決定」の指摘に対し、**rebindはrecord保存の
  `IntentIdentity` をそのまま注入し再導出しない**ことを契約化（#374 revision 2の
  `intentIdentity` 保存契約〔本spec初回reviewのblocking指摘への#374側の確定回答〕を受領）。
  import直後とcold rebind後でplanner projection全field（identity含む）が同一であるoracleを
  SR-AC-03へ追加し、planの再構築案 (i)/(ii) の未決定を解消（Data and state Identity節、
  Contract notes 3）。
  **(3) 正本追従とbaseline更新（中）**: snapshot作成後にmergeした#365（正本改訂完了）・
  #369・#373・#374（[PR #399][4]実装）を前提へ繰り込み、`CONTEXT.md` 3用語の反映を
  **本Issue自身の成果物**へ変更（完了済み#365への依存を解消。Domain language節、plan
  Documentation updates）。Problem・前提・参照の実名（`DurablePendingIntent`・
  `reconcilePendingIntent`・`ExchangeScreen.ImportReview`等）を実装着地後の形へ更新。
- 2026-09-19: Draft created for #375（spec/plan整備task）。accepted TO-BE契約
  （organizer-to-be-ux.md @ main `a2b6aba318`: D-17、§5.1 T-08/T-18、§5.3、§8.1、§9、§10、
  §13-6項目6）、accepted処分文書（organizer-disposition-migration.md @ main `a2b6aba318`:
  §2.1、§3.13、§3.16、§4.1、§5 更新順序 #9、§7.1、§7.3、§8、§11）、先行spec draft
  （#374 `4b28b45543`・#369 `3c39ceb2f8`・#373 `859e51fe83`・#366 `bf00f96175`）、現行実装調査
  （`ScopeBindingGate.kt`、`IntentValidator.kt`の`ScopeMismatchCause`、
  `ManualOrganizationRun.kt`の`confirmSelection`早期gate / `attachIntent` /
  `evaluateScopeBinding` / 後段gate失敗時のintent破棄と選択面復帰 / `ScopeMismatchFailed`、
  `MissingAppSelectionScreen.kt`の`intentScopeCount`案内、`ManualOrganizationPreferences.kt`の
  scopeRejection描画、`ExchangeFlowUi.kt`の`pendingValidated` / `connectRun` /
  `continueImport` / `exchangeContractFailureText`、`ExchangeImportPipeline.kt`の構造digest
  検証、`SessionExportReconstructor.kt`、`IntentPlannerAdapter.kt`のref解決、
  `ContextExportModels.kt`の`ExportSession`、unit/instrumentation test群）を入力に作成。

## References

- [Issue #375][1]
- [organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)（accepted。D-17、§5.1 T-08/T-18、§5.3、§8.1、§9、§10、§13-6）
- [organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)（accepted。§2.1、§3.13、§3.16、§4.1、§5 更新順序 #9、§7.1、§7.3、§8、§11）
- [Spec 374: durable imported intent](../374-durable-imported-intent/spec.md)（accepted・implemented [PR #399][4]。durable record `DurablePendingIntent`・`intentIdentity` 保存・読取時reconcile・ImportReview再開面の正本。継続CTA不在・「継続成功は提案を消費しない」契約）
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md)（implemented。D-2/D-4/D-5、§5。本Issueが改訂）
- [Spec 228: organizer missing-app selection](../228-organizer-missing-app-selection/spec.md)（implemented。D-1 unchecked初期値。本Issueが復元初期値の注記を改訂）
- [Spec 369: run display integration](../369-run-display-integration/spec.md)（implemented。T-08選択面・T-13統合失敗面・canonical順序）
- [Spec 373: import display reprojection](../373-import-display-reprojection/spec.md)（implemented。T-17/T-18・手段別失敗投影。本Issueのcause別文言はその供給源として接続）
- [Spec 328: import success state](../328-exchange-import-success-state/spec.md)（accepted・revision 2 implemented。CTA single-flight / D-2破棄語彙の正本）
- [Spec 204: AI personalization context/intent exchange contract](../204-ai-personalization-context-intent-contract/spec.md)（accepted・implemented。export session契約・failure taxonomy）
- [Spec 205: External Agent Exchange](../205-external-agent-exchange/spec.md)（implemented。run-in/idle entry・session契約）
- [Spec 271: organizer durable status projection](../271-organizer-durable-status-projection/spec.md)（implemented。status card projection様式）
- [Spec 123: organizer UI convergence](../123-organizer-ui-convergence/spec.md)（ja/en strings契約）
- [organization-run-ux.md](../../docs/product/organization-run-ux.md)（§6 accessibility受入基準）
- [CONTEXT.md](../../CONTEXT.md), [DESIGN.md](../../DESIGN.md)（gate 12/13行）, [AGENTS.md](../../AGENTS.md)

[1]: https://github.com/nunu1733/NunuLauncher/issues/375
[2]: https://github.com/nunu1733/NunuLauncher/issues/374
[3]: https://github.com/nunu1733/NunuLauncher/issues/369
[4]: https://github.com/nunu1733/NunuLauncher/pull/399
[5]: https://github.com/nunu1733/NunuLauncher/issues/375#issuecomment-5740062562
[6]: https://github.com/nunu1733/NunuLauncher/pull/402#issuecomment-5765970137
[7]: https://github.com/nunu1733/NunuLauncher/pull/402#issuecomment-5766214632
[8]: https://github.com/nunu1733/NunuLauncher/pull/402#issuecomment-5766390303
[9]: https://github.com/nunu1733/NunuLauncher/pull/402#issuecomment-5766543051
[10]: https://github.com/nunu1733/NunuLauncher/pull/402#issuecomment-5766679997
[11]: https://github.com/nunu1733/NunuLauncher/pull/402#issuecomment-5766867716
[12]: https://github.com/nunu1733/NunuLauncher/pull/402#issuecomment-5766986330
[13]: https://github.com/nunu1733/NunuLauncher/pull/402#issuecomment-5767085523
[14]: https://github.com/nunu1733/NunuLauncher/pull/405
