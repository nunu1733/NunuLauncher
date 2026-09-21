---
issue: "#374"
status: draft
requirements: [FR-017]
risk:
  - privacy
updated: 2026-09-21
---

# 取り込み済み提案（pending intent）をdurable化しhub status cardへ接続する（D-08）

> 契約の根拠: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-02, D-08, D-13, D-17, §5.3 idle AI相談の遷移, §6.5, §8.2, §9 語彙規約,
> §13-2 影響評価項目2, §13-5）。
> 処分の正本: accepted disposition
> [docs/product/organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)
> （§2.1 #328行「#373（表示）→#374（spec rev.2後実装）」、§3.12 spec 205分段改訂、
> §3.14 spec 328分段改訂、§4.1 supersession map、§5 更新順序 #8、§7.1 新規persistent state、
> §7.3 compatibility matrix、§8 依存graph）。
> 本specは[Issue #374][1]の成果物である。statusが `draft` の間はimplementation-readyではない。
> 前提の #365（正本改訂）・#366（hub/status card第1段階）・#372（T-15/T-16）・#373（T-18）は
> すべてmerge済みである（2026-09-21時点のmain `c05435a947`）。本revisionは
> 初回review（2026-09-19、Changes requested 6件）への対応、これら前提merge後のcurrent main
> へのre-entry、および2nd review（2026-09-21、Changes requested 3件）への対応である。

## Problem

現行（main `c05435a947`。#365/#366/#369〜#373実装merge後。spec 205/328/329/330/331/332/337/
contract core/348実装済み、#328 Phase 2実装merge `34ba8ff447`）では、import成功
（validation通過）後のvalidated intentは `ExchangeFlowStateHolder` のprocess-localな
pending slot（`pendingValidated`）と `ImportSuccess` 画面stateのみに存在する。このため:

1. **process死で提案が消える**: 外部AIアプリとの会話中やホーム画面操作中にprocessが破棄されると、
   取り込み済みの提案は失われる。依頼（export session、durable・TTL 24時間、spec 204/205）だけが
   残り、提案が揮発する非対称はTO-BE AS-IS finding「state/lifetimeの理解可能性」（F-03/F-05）の
   原因である。回復は「同じ回答textの再取り込み」のみであり、ユーザーが回答textを保持していない限り
   再依頼になる。
2. **画面離脱で提案が見失われる**: 取り込み成功状態はexchange導線内の中間状態であり、ユーザーが
   設定面を離れると存在ごと見えなくなる。「取り込み済みで未適用の提案がある」ことはどの面にも
   表示されず（F-05: hidden lifetime、F-10: 再発見断絶）、再開導線が存在しない。
3. **消失条件が説明できない**: 現行の消失系は「黙って消える」（process死・画面離脱・attempt失効）
   と「明示的に消える」（破棄）が区別されない。TO-BE §8.2は「保存されたように見えるが消えるもの」と
   「見えないが残っているもの」を作らないことを要求する。
4. **status cardに表示されるdurable事実が不完备**: #366実装のhub status card（T-01第1段階）は
   durable status行のみを持ち、TO-BE D-02が要求する「進行中のAI依頼（24h残時間）」行と
   「取り込み済み提案」行が存在しない。依頼行は#372実装spec（T-15事前表示は#372、
   **status cardへの依頼表示は#374**）から本Issueへ明示的に委譲されている。

accepted disposition（§3.12/§3.14/§4.1）は、spec 328の「取り込み成功状態とpending intentは
process-localのみ」規定とspec 205の「validated intentを保持しない」規定をD-08へ
**Amend-Supersede** することを決定した。spec 328 revision 2とspec 205の該当規定改訂は
本Issueが所有する（§5 更新順序 #8）。

## Outcome

import成功（validation通過）時にvalidated intentの内容が **durableな取り込み済み提案
（pending intent）store** へ保存される。**durable保存の成功が取り込み済み状態の成立条件である**
（保存が失敗した成功状態を採用せず、retry可能なtyped失敗として扱う）。提案は依頼（export session）
と同一の有効期限（24h）を持ち、process死・画面離脱で消えない。提案の消失の系は「破棄」操作・
期限切れ・置換（新しい依頼の生成の承認）のみであり（Issue本文契約どおり。**継続CTA成功でも
recordは保持される** — 「継続成功は提案を消費しない」は#375実装予定specの契約でもある）、
その有効性は対応する依頼sessionの有効性に従属する（単一active session契約と同一のlifecycle。
ownership gapの契約上排除）。

hub status cardに「進行中のAI依頼（残時間）」行と「取り込み済みの提案（残時間）」行が表示され、
取り込み済み提案は `Hub → ImportReview（T-18）` で内容（privacy-safeな件数サマリ）・残時間・破棄を
cold processで開ける。再開の導線はstatus card経由の1経路に集約される（process生存/死を問わない。
TO-BE §5.3/§6.5）。本Issueの再開面で **runへのcontinuation/rebind（「この提案で続ける」の有効化・
fresh run admission・選択復元）は提供しない** — それは[Issue #375][4]が所有する。同一process内の
import成功状態からのCTA従来挙動（既存seam・single-flight・attempt anchor）は本Issueで維持する。

現行spec 328の「process-localのみ」規定はdurable契約へ置換され（revision 2）、import attempt
freeze規則（idle start row等の競合affordance個別規定）はstatus card＋T-18中心の再設計へ
更新される。取り込み破棄の語彙・確認契約はD-2（明示ボタンは追加確認なし）から **D-13（破棄ラベル＋
必須確認、入口ごとに確認1回）** へ更新され（#373が本Issueへ委譲した解消）、CTA single-flight・
attempt anchor契約（spec 328 AC-3）は維持される。

## Scope

- **durable pending intent storeの新設**: app-private・**backup除外**（export session
  （spec 204 `AndroidExportSessionStore`）とrecovery DBと同じclass）。単一active（保存は
  import成功時に1件を上書き保存）。TTLは依頼（export session）と同一であり、**提案の有効性は
  対応する依頼sessionの有効性に従属する**。
- **store契約**: 保存（import成功時）・破棄（tombstone 2段commit。後述）・期限切れ・置換無効化。
  **継続CTA成功時の削除は行わない**（消失系は破棄・期限切れ・置換のみ）。
  intent内容はinternal表現（`CompletedPersonalIntent` 相当のcanonical decisions。
  **export-scoped refと正規化済み `proposalLabel` を含む**）で保存し、ref↔内部ID対応表は
  依頼session側の正本（`ExportSession.itemRefs` / `categoryRefs`）を利用して複製しない。
- **durable recordの内容（privacy境界の確定）**: 保存するのは
  (a) identity anchor（`exportId`）と **intent content identity（`IntentIdentity`
  （schemaVersion・digest）。import時に算出済みの正本。#375のcold rebindでplanner
  provenance（`PersonalizedIntentProjection.identity`）に用いる）**、
  (b) canonical decisions（`RefDecision` 相当:
  決定対象のexport-scoped ref（map key相当）+ Authored場合のsemantic field群
  （importance / desiredGroupRefs（同一export内の他ref） / groupSemantic（**exactly-one-of**
  `categoryRef` または `proposalLabel` text — planner-effectiveなformation key
  （`ItemPreference.groupProposalLabel`）として欠損なく保存） / pageAffinity /
  regionAffinity / preserve））、
  (c) planner-effective `minimizeMovement`（boolean 1値。`GlobalPreference` の全field）、
  (d) 失効時刻（session複製値。表示の正本はsession側）、(e) entry種別（IDLE / RUN_IN。#375用）、
  (f) 破棄mark（tombstone）、(g) 作成時刻・schema version。
  **永続化しない**: `rationale`（AI自由文）・`confidence`（AI自己申告）・
  app label・folder title・category displayName・**ref↔内部ID対応表**（`itemRefs` /
  `categoryRefs` の複製。sessionが正本）。`proposalLabel` は正規化されたrun-scopedの
  提案labelであり **保存する**（model KDocの「never persisted」規定は本Issueが改訂する —
  layout DB・category storeへの永続化ではなく、TTL 24h・app-private・backup除外の提案保持である。
  実装PRでKDocを更新する）。`intentIdentity` のdigestは `rationale` / `confidence` を含む
  canonical byte表現から算出済みの値であり、raw textを永続せずに同一identityを再現する。
- **crash consistency**: session置換とpending破棄の単一atomic commitは要求しない。書込順序を
  「新session保存 → 旧pending無効化」に固定し、**読取時reconcileを正本** とする:
  提案は表示・開封・（#375以降の）続行metadata参照の前に必ず
  (a) `pending.exportId == 現行active sessionのexportId`、(b) TTL（session失効を含む）、
  (c) 破棄mark不在、(d) 未知schema・破損・読取失敗（recordのref集合とsessionのref集合の不一致を
  破損として含む） を検証し、不一致は **fail-closedに無効化・清掃** する（表示も開封も続行もしない。
  破棄相当の取り扱い）。reconcileは **起動時** と **status card読取時・ImportReview読取時** の
  両方に適用する（起動時reconcileはfresh processでhubを開かなくても実行される。
  既存のorganizer startup reconciliation trigger（`LawnchairApp.
  ensureOrganizerStartupReconciliation()`、spec 271 DS-AC-10と同一のidempotent共有入口）に
  接続する。store完結の軽量検証でありreadiness gateを必要としない）。
  **置換途中のprocess death・write failureを再現するoracleを必須とする**（disposition §7.1）。
- **session置換による破棄の契約化**: 新しい依頼の生成（session置換の承認、spec 205 AC-13）は
  既存の取り込み済み提案を破棄する（TO-BE §13-2推奨「新しい依頼が古い取り込みを無効化する」を採用）。
  置換は (1) durable recordの無効化（書込順序固定）、(2) **同一process内の取り込み成功状態と
  process-local pending（`pendingValidated`）の破棄** の双方に適用する（3つの保持場所
  （durable record / process-local pending / 表示中成功状態）すべてが置換で破棄されることで、
  「提案は有効なのに再開metadata（session内ref対応表）が無効化済み」なownership gapを
  in-process含め契約上排除する）。置換確認dialog（現行 `exchange_replacement_warning`。
  #372実装済みのD-13「破棄」語彙を踏襲）の文言に「取り込み済み提案の破棄」を追記する。
- **status card「取り込み済みの提案」行**: 提案の存在（reconcile通過済み）・残時間（依頼sessionの
  失効時刻から導出。表示上の正本はsession側）を閉域語彙で表示し、`Hub → ImportReview` を開く
  操作を提供する。行はspec 271のdurable status行と同一の契約様式（閉じた語彙・fail-closed・
  payload非表示）に従い、#366実装のstatus card（durable status行と開始CTAの間）へ追加される。
- **status card「進行中のAI依頼」行**（#372実装specからの委譲を受領）: activeな依頼sessionの
  存在・残時間を、#372のT-15事前表示と同一の語彙・導出方式（`activeSession()` 読取・
  `requestRemainingDisplay` 相当の残時間表示・面への進入/lifecycle resume/失効時刻にscheduleした
  1回再読取。連続的な時計更新なし）で表示し、T-15（依頼作成面）を開く操作を提供する。
  active session不在時は行を表示しない（欠落状態を発明して表示しない）。
- **ImportReview再開面（cold process到達範囲）**: 再開面で表示するのは **内容（spec 328と同一の
  privacy-safe件数サマリ）・残時間・破棄** までである。件数サマリはdurable recordと依頼session
  （`categoryKindByRef` は `session.categoryRefs` の `CategoryIdentity` 判別から、
  `scopeCount` は `session.scopeCandidates.size` から）から既存の純粋導出と同一入力基準で
  再構成する。runへのcontinuation/rebind（fresh run admission・選択復元・「この提案で続ける」の
  有効化）は **#375が所有** する。再開面の破棄は後述のtombstone契約で **durable recordを破棄** する。
  破棄後も依頼（export session）は失効せず、依頼が有効な間は同じ回答textの再取り込みが可能である。
- **取り込み破棄のtombstone 2段commit**: user-visibleな破棄（取り込み成功状態・再開面の双方）は
  (1) `discarded=true` のtombstoneを **atomicにcommit**（既存recordのatomic書換）し、
  (2) その後 best-effort で物理削除する、の2段とする。**tombstone commitに成功するまで破棄成功と
  して画面を閉じない**（失敗はtyped失敗で観測可能。提案は有効なまま残り再試行できる）。
  明示破棄はsession置換と違いexportId mismatchの救済がないため、tombstone（読取時reconcileの
  破棄mark検証）が「delete失敗・commit直後のprocess death後の再表示」を構造的に防ぐ。
- **同一process内CTA従来挙動の維持（record不変）**: import成功状態が表示されている連続したflow内での
  CTA（`run.start` / `attachIntent`）・single-flight・attempt anchor・`continuing` 中の破棄/Back
  不受理は既存契約（spec 328 AC-3/AC-5）どおり **不変** である。CTAの **成功settle時**
  （`Started` / `Attached`）にもgate拒否・例外failure settle時と同じくdurable recordへの
  write・削除を行わない（消失系は破棄・期限切れ・置換のみ。「継続成功は提案を消費しない」は
  #375実装予定specの契約。Contract notes 6）。
- **取り込み破棄の語彙・確認契約のD-13更新**: 破棄入口（明示ボタン「破棄して閉じる」・system Back）
  はともに **「破棄」ラベル＋確認dialog 1回** を経由する（D-2の「明示ボタンは追加確認なし」から
  D-13 §9「取り込み済み提案の破棄=必須確認」への更新。#373が本Issueへ委譲した解消。
  確認のキャンセルで提案は保持）。CTA処理中（`continuing`）は両入口とも不受理（現行契約維持）。
- **durable保存失敗のtyped扱い**: durable保存の失敗は取り込み済み状態を採用しない（ImportSuccessを
  出さない）。保存のみをretry可能なtyped失敗（既存のimport validation typed分類（20種）とは別の
  persistence step由来の失敗）として表示する。表示は#373のD-11手段別投影様式に従う
  （primary remedy = 保存の再試行。面レベル手段（中断する = 保存せず閉じる・診断を開く）は常設。
  中断した場合、提案は保存されず、依頼が有効な間は同じ回答textの再取り込みが回復pathである）。
- **spec 328 revision 2（本Issueの実装成果。本specの受入後に執筆・受入）**: disposition §3.14の
  処分どおり、(1) Data and stateの「取り込み成功状態とpending intentはprocess-localのみ」を
  durable契約への置換へ改訂、(2) import attempt freeze規則をstatus card＋T-18中心の再設計へ
  改訂（in-flight attemptの競合freeze（idle start row無効化等）は現行契約を維持し、attempt終端後の
  消失保護はdurable契約＋読取時reconcileへ移管。画面離脱＝破棄ではないことを明文化）、
  (3) D-3 CTA copyをTO-BE T-18語彙（「この提案で続ける」）へ統一（#373 Contract notes 3から
  委譲された所有）、(4) 取り込み破棄のzero-write規定をdurable削除（tombstone 2段commit）へ更新、
  (5) D-2確認契約をD-13（破棄ラベル＋必須確認）へ更新。AC-2（未適用表示）/AC-3（single-flight・
  attempt anchor）/AC-4（件数summary）/AC-7（Back interception）の本体は維持。
- **spec 205の改訂（本Issueの実装成果）**: 「activeなrun操作中…validated intentを保持しない」を
  含むpending非保持規定を、durable pending intent契約（本spec）への参照へ改訂する
  （disposition §3.12-4/§3.14/§4.1）。export session契約（TTL・単一active・置換確認・AC-11〜13）は
  不変。置換確認dialogの文言拡張（取り込み済み提案の破棄追記）を含む。
- **spec 366の改訂（本Issueの実装成果）**: HUB-AC-03（否定的観測: hubに進行中AI依頼・取り込み済み
  提案の表示が存在しない）の対象から本Issueが追加する2行（進行中AI依頼・取り込み済み提案）を除外する
  （復元CTA（#376）・最近のrun結果は否定的観測のまま）。status cardのTalkBack読み順契約
  （#366「状態→操作」+「#374/#376が残期限要素を挿入」）は本Issueの行で実現される。
- **migration/compat評価（必須。disposition §7.1/§7.3）**: 新store追加のみで既存データ移行なし。
  downgrade時（本feature以前の版へ戻す場合）は当該storeを無視/清掃（起動時・読取時の寛容読み
  失敗でfail-closed、runに影響しない）。restore/backupは対象外（`noBackupFilesDir`）であり、
  復元後は提案が存在しない状態から始まる。process death後の再開の最終防衛は#375のscope検証である。
- **Localization**: 新規・改訂のuser-visible文字列はAndroid resource由来でEN（`values/`）と
  ja（`values-ja/`、正本）の双方に供給する（spec 123契約）。copyはTO-BE §9/§10語彙
  （取り込み/依頼/提案/破棄）に従う。

## Non-goals

- **runへのcontinuation/rebind**（fresh run admission・選択復元・cold process/再開面での
  「この提案で続ける」の有効化）とscope不一致の原因別remedy（SET_MISMATCH /
  PROJECTION_MISMATCH）: **#375が所有**（disposition §8責務分割）。本Issueの再開面は継続CTAを
  提示しない（Contract notes 2）。
- **run/preview/選択のdurable化**: run/RUN lease・preview・選択はprocess-localのまま
  （TO-BE §8.2）。継続CTA成功後にprocess死でrunが失われる場合もdurable recordは残るため、
  再開は#375のrebind経路（recordから）であり、本Issueはrecordの保持のみを保証する。
- **export session契約の変更**: TTL 24時間・単一active session・置換確認のgate構造・
  pre-send cancel（spec 204/205 AC-11〜AC-13）は不変。session置換確認dialogの文言拡張は行うが、
  gate構造・確認timingは変更しない。
- **T-15/T-16の再構成・依頼語彙の統一**: #372実装済み。T-15事前表示・置換確認のgate構造には
  触れず、status card行の追加と確認文言の追記のみを行う。
- **T-18失敗面の手段別再投影**: #373実装済み。本specは失敗表示契約を変更せず、persistence
  step由来のtyped失敗を同様式で追加するのみである。
- **import検証・normalizer・validator契約の変更**（spec 204/329/330）: durable保存は
  validation通過後のみに行い、検証分類・zero-write性・attempt anchorには触れない。
- **既存失敗表示・生成flow・transportの変更**（spec 205/332/348契約の維持）。
- run state machine・spec 13/52/271の契約・diagnostics event・permission・外部送信経路の変更。
- **status card復元CTA（D-15）**: #376が所有。本Issueの行追加は提案行・依頼行のみである。

## Domain language

用語の正本は `CONTEXT.md`（#365改訂済み）である。本Issueが使用する正本用語:
**Organizer hub（整理ハブ）**・**依頼（AI相談の依頼）**・**取り込み済み提案（取り込み済み・
未適用の提案）**・**中断・破棄・キャンセル（中止語彙規約、D-13/§9）**・**取り込み成功状態**・
**取り込み破棄**。本specは正本の意味を再定義しない。実装PRで正本へ反映する差分は次のとおり
（Implementation時の文書更新。Contract notes 7参照）:

- **取り込み済み提案**: 「durable化は後続実装Issueのspec改訂で行う」の末尾の保留文を
  durable化実現（本spec）へ置換する。
- **取り込み破棄**: 「pendingなvalidated intentを破棄する (zero-write)」をdurable削除
  （tombstone 2段commit）へ、「入口は明示ボタン (追加確認なし) とsystem Back (確認dialog) の2つ」を
  D-13（両入口とも破棄ラベル＋確認dialog 1回）へ更新する（中止語彙規約が「spec 328の確認契約の
  改訂は後続実装Issueが行う」と予告した改訂）。

**読取時reconcile (read-time reconcile)**（本Issueの実装概念。CONTEXT.md追加対象外）:
取り込み済み提案を表示・開封・参照の前に必ず適用する有効性検証（`pending.exportId ==
現行active sessionのexportId`・TTL・破棄mark・構造検証）。不一致はfail-closedに無効化・清掃する。
跨storeのatomic性を要求しない代わりの正本である（disposition §7.1）。
_Avoid_: 書込時の二重commit（要求しない）、読取時のみの検証（起動時にも適用する）

## Behavior scenarios

### Scenario: import成功時に取り込み済み提案がdurable保存される

Given いずれかのentry（idle / run-in）でAI回答がvalidationに通過した、
When validation settleが成功状態を採用する、
Then durable保存が **成功して初めて** 取り込み成功状態（`ImportSuccess`）が採用される
（保存と成功状態の採用は同一settle内で完了する。run state・layout DBへのwriteは発生しない）、
成功状態の表示は現行契約（spec 328 AC-1/AC-2/AC-4）どおりである、
And 保存される内容は「durable recordの内容（privacy境界の確定）」の (a)〜(g) であり、
canonical decisionsはexport-scoped refと正規化済み `proposalLabel` を含む
（refはsessionの対応表を介してのみ内部IDへ解決される。永続化しないのは `rationale` /
`confidence` / app label / folder title / category displayName / ref↔内部ID対応表 —
DI-AC-10と同一の列挙である）、
And 成功状態からのCTAは現行seam（`run.start` / `attachIntent`）を現行契約どおり一度だけ実行し
（spec 328 AC-3の回帰。single-flight・attempt anchor・`continuing` 中の破棄/Back不受理を含む）、
成功settleで成功状態が閉じる。

### Scenario: durable保存の失敗はtyped失敗であり、成功状態を採用しない

Given importがvalidationに通過したが、durable pending intent storeへの保存が書込失敗で
失敗した、
Then 取り込み成功状態（`ImportSuccess`）は **採用しない**（「取り込み済み」を表示しない。
Issue Outcome「消失の系は破棄・期限切れ・置換のみ」への例外を設けない）、
And persistence step由来のtyped失敗を表示する: primary remedyは **保存の再試行**
（validation結果はprocess-localに保持したまま保存のみ再実行。attempt anchorは維持）、
面レベル手段（中断する = 保存せず閉じる・診断を開く）を常設する（#373のD-11手段別投影様式）、
And 中断した場合、提案は保存されずprocess終了で失われる。依頼（export session）が有効な間は
同じ回答textの再取り込みが回復pathである（typed案内に含む。ja正本・en同意味）、
And 保存に失敗した提案はstatus cardへ表示されない（表示の正本はdurable recordのみ。
存在しない提案を偽装しない）。

### Scenario: 同一process内のCTA従来挙動は変化しない（回帰）

Given 本Issueの変更が適用されており、同一process内でimport成功状態が表示されている、
When ユーザーがCTAを押す、
Then 既存seam経由の接続が一度だけ行われ、durable storeへの追加write・状態遷移の変更はない
（durable保存はsettleのanchor契約（attempt token）に手を入れない）、
And CTAのgate拒否（`Busy` / `NotAttachable`）・seam例外時のfailure settle・admission domain
（`AUTHORING` lease）との相互排他はspec 328 AC-3/AC-5の現行契約どおりである。gate拒否・
failure settleではdurable recordを削除しない（成功状態は維持され、提案は有効なまま残る）。

### Scenario: 継続CTA成功後も提案は保持される（消費なし）

Given 同一process内でimport成功状態が表示され、durable recordが存在する、
When CTAが押され、run接続seamが **成功settle**（`Started` / `Attached`）した、
Then durable recordへのwrite・削除を行わない（gate拒否・例外failure settleと同じく
recordは不変。消失系は破棄・期限切れ・置換のみ — Issue本文契約、および#375実装予定specの
「継続成功は提案を消費しない」「成功・失敗・拒否のいずれもdurable recordへwriteしない」契約）、
And status cardの提案行は残り、process死後もrecordは残る（継続済みrunの喪失からの再開は
#375のrebind経路が所有する。本Issueはrecordの保持のみを保証する）、
And 継続済みかどうかの表示上の区別は本Issueの範囲外である（#375）。

### Scenario: 画面離脱で提案は消えず、status cardへ現れる

Given import成功後に提案がdurable保存され、ユーザーが破棄せずに設定面・exchange導線を離れた
（別面への遷移。画面離脱は破棄ではない。system Backは確認dialogを経由）、
When hubを開く、
Then status cardに「取り込み済みの提案」行が表示され、残時間（依頼sessionの失効時刻から導出）が
示される、
And 提案の内容・件数サマリは失われず、行（の操作）から `Hub → ImportReview` で再開面へ到達でき、
And process死後も同一の行が表示される（durable契約。TO-BE §8.2「見えないが残っているもの」を
作らない — 存在・期限・消える条件がstatus cardで可視である）。

### Scenario: status card「進行中のAI依頼」行（#372からの委譲受領）

Given activeな依頼session（`ExportSessionStore.active`）が存在する、
When hubが開かれる、
Then status cardに「進行中のAI依頼」行が存在・残時間とともに表示される
（残時間の導出・表示語彙は#372のT-15事前表示と同一: 読取時点の `activeSession()` に一致し、
残り約N時間/1時間未満。連続的な時計更新はしない）、
And 行（の操作）からT-15（依頼作成面）を開ける（置換確認のgate構造は#372実装どおり不変）、
And hub面への進入・lifecycle resume・表示中sessionの失効時刻にscheduleした1回再読取で
表示が `activeSession()` の値へ一致する（TTL跨ぎ後の最初の再読取で行が消える）、
And active session不在時は行を表示しない（欠落状態を発明して表示しない。fail-closed）。

### Scenario: cold processでstatus cardからImportReview（T-18）を開く

Given process死後にhubが開かれ、status cardに取り込み済み提案の行がある、
When ユーザーが行（の操作）をタップする、
Then 再開面（ImportReview / T-18成功面の再開形態）が開き、次を表示する:
(a) 内容 = spec 328 AC-4と同一のprivacy-safe件数サマリ（認識件数・判断なし合算・内訳4種+
提案グループ件数・group breakdown・全体方針行。durable recordと依頼sessionから同一の
純粋導出で再構成する）、(b) 残時間、(c) 破棄操作、
And **継続CTA（「この提案で続ける」）は提示しない**（#375が有効化するまでの本Issueの到達範囲。
Contract notes 2）、
And 再開面はrun stateを参照せず変更しない（run admission・`start(trigger)` 発行の経路は
存在しない）、
And 再開面を破棄せずに閉じた場合（Back・hubへ戻る）、durable recordは保持され、status card行は
残る（開封だけでは提案は消えない）。

### Scenario: 再開面からの破棄（tombstone 2段commit）

Given 再開面（または同一process内の取り込み成功状態）が表示されている（CTA処理中は存在しない）、
When ユーザーが破棄を確定する（明示ボタン「破棄して閉じる」→ 確認dialog、またはsystem Back →
確認dialog「取り込みを破棄しますか?」での確定。両入口とも確認1回。D-13）、
Then storeは `discarded=true` のtombstoneを **atomicにcommit** する（既存recordのatomic書換）、
tombstone commitに **成功して初めて** 画面を閉じ、status cardの行を消す（fail-closedな消失では
ない明示的破棄）。依頼（export session）は失効せず、依頼が有効な間は同じ回答textを再取り込み
できる（D-13語彙の踏襲）、
And tombstone commit後の物理削除はbest-effortであり、削除失敗・commit直後のprocess deathが
発生しても、次回読取時のreconcile（破棄mark検証）が当該recordを無効化・清掃する
（**破棄済み提案が再表示される経路は存在しない**）、
And tombstone commitが失敗した場合は破棄成功として画面を閉じない: typed失敗を表示し、
提案は有効なまま残る（再試行可能。確認dialogのキャンセルであれば提案は保持される）。

### Scenario: 期限切れ（依頼sessionに従属）

Given 取り込み済み提案の依頼sessionがTTL 24時間で失効した（またはTTL前にsessionが
invalidateされた。pre-send cancel等）、
When status card / ImportReviewが提案を読む、
Then 読取時reconcileが `pending.exportId == 現行active sessionのexportId` とTTLを検証し、
session不在・失効・不一致のいずれかで提案を **fail-closedに無効化・清掃** する
（status cardに行を表示せず、ImportReviewを開けない。「期限切れの提案」を発明して表示しない）、
And 提案の残時間表示の正本は依頼sessionの失効時刻であり、recordに複製された値との不一致は
session側を正として扱う。

### Scenario: 新しい依頼の生成（session置換の承認）が既存の提案を破棄する

Given activeな依頼sessionと取り込み済み提案が存在する、
When ユーザーが新規依頼の生成を開始し、session置換確認（spec 205 AC-13）を承認して
新しいsessionが保存される、
Then 書込順序は「新session保存 → 旧pending無効化」に固定され、既存の取り込み済み提案が
破棄される（提案の有効性は依頼sessionに従属。「提案は有効なのに再開metadata（session内ref
対応表）が無効化済み」なownership gapは契約上発生しない。TO-BE §13-2）、
And 同一process内に取り込み成功状態・process-local pending（`pendingValidated`）が残っている
場合、それも破棄する（置換は3つの保持場所すべてに適用される）、
And 置換確認dialogの文言は「既存依頼宛回答の無効化」（#372実装済みのD-13語彙）に加え
「取り込み済み提案の破棄」を含む（現行 `exchange_replacement_warning` の拡張。en/ja双方）、
And 置換を辞退した場合は既存sessionと提案がともに不変である（現行AC-13の回帰）。

### Scenario: 読取時reconcileのfail-closed（不一致・破損・未知schema）

Given durable recordが存在するが、(a) 対応するsessionが存在しない/不一致の `exportId`、
(b) TTL失効、(c) 破棄mark（tombstone）付き、(d) 未知schema・破損・読取失敗
（recordのref集合とsessionのref集合（`itemRefs.keys`）の不一致を含む）、のいずれかである、
When 起動時・status card読取時・ImportReview読取時のいずれかでreconcileが走る、
Then 該当recordを無効化・清掃し、提案としての表示・開封・続行metadata参照を一切行わない
（fail-closed。発明した状態を表示しない。spec 271の読取失敗契約と同一様式）、
And 清掃以外のwrite（journal event・layout DB・session）は発生させない。

### Scenario: 置換途中のprocess death / write failure（必須oracle）

Given 新しい依頼の生成が承認され、新sessionがdurable保存された直後（旧pending無効化の前に）
processが破棄された、または旧pending無効化の書込がI/O failureで失敗した、
When 再起動後（または次の読取時）にstatus card / ImportReviewが提案を読む、
Then 読取時reconcileが旧pendingの `exportId` と現行active sessionの不一致を検出し、
旧提案を無効化・清掃する。**staleな提案がstatus cardに表示され続けた・開封できた・
続行に使われた経路が存在しない** ことを、process death遷移の再現oracleとwrite failure注入
oracle（store fake / 故障注入）の両方で固定する（disposition §7.1の必須項目）。

### Scenario: 破棄commit直後のprocess death / 物理削除失敗（必須oracle）

Given ユーザーが破棄を確定し、tombstone commit（`discarded=true` のatomic書換）に成功した
直後（best-effort物理削除の前）にprocessが破棄された、または物理削除がI/O failureで失敗した、
When 再起動後にstatus card / ImportReviewが提案を読む、
Then 読取時reconcile（起動時・読取時）が破棄markを検出し、当該recordを無効化・清掃する。
**破棄済みの提案が再表示される経路が存在しない** ことをprocess death遷移の再現oracleと
write failure注入oracleの両方で固定する（明示破棄にはsession置換のようなexportId mismatchの
救済がないため、tombstoneが唯一の防衛である）。

### Scenario: 起動時reconcileはhubを開かなくても実行される

Given staleなdurable record（置換途中・破損等）が存在する状態で、アプリがfresh processで
起動された（hub・status cardを一度も開かない）、
When organizer startup reconciliation（`ensureOrganizerStartupReconciliation()`。
spec 271 DS-AC-10と同一のidempotent共有入口）が実行される、
Then pending storeのreconcile（store完結の軽量検証。readiness gate非依存）が実行され、
stale recordを無効化・清掃する（起動時reconcileは早期清掃。読取時reconcileが正本だが、
起動時にも独立に適用される。「startupのみ → 清掃」を独立oracleとする）。

### Scenario: 単一active — 新しいimport成功が旧recordを置換する

Given 同一の依頼session宛のimportが繰り返し成功する（回答textの再取り込み等）、
When 新しいvalidation settleでdurable保存が成功する、
Then durable storeは新しい提案で旧recordを置換する（単一active。storeに複数の提案は
存在しない。canonical decisionsの内容が同一でも新しいsettleで置換する）、
And 旧attemptの遅延settleがdurable recordを置換・破棄しないことはspec 328のattempt anchor契約
（AC-1/AC-3）の回帰である。

### Scenario: backup / restore / downgradeの安全な扱い

Given durable pending intent storeがbackup対象外（`noBackupFilesDir`）であり、
端末移行（backup → restore）・downgrade（本feature以前の版）・再upgradeが発生した、
When それぞれの状態でアプリが起動する、
Then backup/restore後は提案が存在しない状態から始まる（storeはbackupされない。TO-BE §7.3
どおり端末交換で消えることが契約）、downgrade中の版は当該storeを読む経路を持たず無視し、
再upgrade後の最初の読取でreconcileがstale recordをfail-closed清掃する（寛容読み・
fail-closed。run・layout DBへの影響なし）、
And 既存データのmigrationは存在しない（新store追加のみ。disposition §7.1）。

### Scenario: status cardのTalkBack読み順（状態→残期限→操作）

Given status cardに取り込み済み提案の行（および進行中AI依頼の行）がある、
When TalkBackで行を読み上げる、
Then 読み順はTO-BE §13-5の「状態→残期限→操作」規約に従い、#366が定めたstatus cardの読み順
（第1段階の実効順序「状態→操作」）に **残期限要素が挿入される** 形である
（行単位の順序。行間の順序は#366実装のstatus card構造に従う）、
And 行の操作（ImportReview / T-15を開く）・残時間の意味がTalkBackで識別可能である
（name/role/state）。

### Scenario: 既存flowの回帰（失敗面・生成・freeze・表示）

Given 本Issueの変更が適用されている、
When import失敗経路（validation typed 20種・#373手段別投影）・生成flow（生成 → 置換確認 →
送信前確認 → transport）・pre-send cancel（破棄確認）・in-flight import attempt中の競合freeze
（idle start row / arbiter / Back確認dialog / 破棄ボタン）を実行する、
Then これらはspec 205/328/332/#372/#373の現行契約どおり機能する（in-flight attemptのfreeze規則は
rev.2でも維持される。消失保護の移管はattempt終端後にのみ影響する）、
And #328実装対応の既存test（`ExchangeFlowStateHolderTest` /
`ExchangeImportSuccessInstrumentationTest` 等）が無編集または契約更新に対応した更新のみで
greenである。

## Data and state

- **読むdata**: durable pending intent record（単一。「durable recordの内容」の (a)〜(g)）、
  現行active session（`ExportSessionStore.active(now)`。reconcileと残時間表示とref対応の正本）、
  現在時刻（注入clock）。#204/#330契約を本specは再定義しない。
- **書くdata**: durable pending intent storeのみ（import成功時の保存 / 破棄tombstone+清掃 /
  reconcile清掃 / 置換無効化）。layout DB・`favorites`・recovery store・
  export session・diagnostics journalへのwriteは発生しない。ホームレイアウト安全規約の適用対象外である
  （`favorites` への接触なし。トランザクション要件はstore単体のatomic性（AtomicFile）で充足する）。
- **Identity**: 提案のidentityは `exportId`（対応する依頼sessionと1:1）。単一active契約により
  proposal:session = 1:1であり、`pending.exportId == active session.exportId` が有効性の
  一致条件である。intent内容のidentityは **import時に算出した `IntentIdentity`
  （schemaVersion・digest）をrecordへ保存した正本** を用いる（`rationale` / `confidence` を
  含むcanonical byte表現のdigestであり、raw textを永続せずに同一identity・planner provenance
  （`PersonalizedIntentProjection.identity`）をcold rebindでも再現する。#375のrebind契約への
  接続）。record内のexport-scoped ref（決定対象ref・`desiredGroupRefs`・
  `groupSemantic.categoryRef`）はsessionの対応表（`itemRefs` / `categoryRefs`）を介してのみ
  内部IDへ解決され、対応表自体はrecordへ複製しない（sessionが正本）。
- **lifetime**: 提案のTTL = 依頼sessionの失効時刻と同一（24時間）。有効性の従属により、
  session失効・invalidate・置換のすべてが提案を無効化する。残時間表示はsession側失効時刻から
  導出する。継続CTA成功settleでもrecordは削除されない（消失系は破棄・期限切れ・置換のみ。
  #375「継続成功は提案を消費しない」契約。Contract notes 6）。
- **reconcileの適用点**: 起動時（`LawnchairApp.ensureOrganizerStartupReconciliation()` の
  idempotent共有triggerに接続。fresh processでhubを開かなくても実行。store完結の検証であり
  readiness gate・model loadを待たない）と、status card読取時・ImportReview読取時
  （読み取りのたびに検証する。起動時reconcileは早期清掃であり、読取時reconcileが正本）。
- **永続化の実装様式**: export sessionと同一のclass（app-private `noBackupFilesDir`・
  `AtomicFile`による単一recordのatomic書込・schema version検査・corruption/未知schema/
  書込失敗のfail-closed退化「recordなし」・pure seam + integration実装のprivate
  `@Serializable` record + mapping。`AndroidExportSessionStore` と同一pattern）。詳細は
  [plan.md](./plan.md)に記載する。
- **migration / backup / restore / rollback**: 新規store追加のみでデータ移行なし。backup対象外。
  downgradeは寛容無視、再upgradeはreconcile清掃。rollback（PR revert）時もstore残留は
  次回読取でreconcileされる（schema version検査により旧版の読み取り経路は存在しない）。
  store削除で機能のみ失い、レイアウトへ影響しない。

## Permissions, privacy, and security

- 追加permission・network通信・外部送信経路なし。
- **durable recordのprivacy境界（identity表現の確定）**: canonical decisionsは **opaqueな
  export-scoped refと正規化済み `proposalLabel` を含めて保存する**（refは `RandomIdAllocator`
  由来の乱数値（spec 204契約）でありsession対応表を介さなければitem/categoryを特定できず、
  `proposalLabel` は `IntentPlannerAdapter` が `ItemPreference.groupProposalLabel`（formation
  key）としてplannerへ渡すplanner-effective値であり、Boolean化すると「どのitem同士が同じ提案
  groupか」を復元できなくなるため）。**intent content identity（`IntentIdentity`）も保存する**
  （digestは `rationale` / `confidence` を含むcanonical表現から算出済みの値 — raw textを永続
  せずに#375 rebindのprovenance同一性を保証する）。**禁止するのは**: `rationale`（AI自由文）・
  `confidence`（AI自己申告）・app label・folder title・category displayName・
  **ref↔内部ID対応表の複製**（`itemRefs` / `categoryRefs` はsession側正本）。
  summary表示のprivacy境界（spec 328 AC-4: 件数のみ+全体方針行）はdurable層へそのまま拡張される
  （ref・labelは保存するが **summary・status card・再開面には表示しない**。Contract notes 3）。
- **backup除外**: recordは `noBackupFilesDir` に置き、端末backup/restoreへ含めない
  （export sessionと同じclass。disposition §7.1）。
- status card・再開面に表示するのは件数サマリ・残時間・操作のみであり、ref・label・自由文は
  いずれの層にも表示しない。summary導出は既存の純粋関数と同一入力基準で行い、出力modelへの
  非対象field混入をcontract testで防止する（spec 328 AC-4の回帰）。
- 診断へのrecord内容出力は行わない（organizer-diagnostics.md契約の維持）。

## Accessibility and localization

- organization-run-ux §6のaccessibility受入基準を適用する: status card提案行・依頼行・再開面の
  TalkBack name/role/state、focus restoration、200% font scale（200% reflow）で残時間・
  内容サマリ・破棄操作が画面内で到達可能、non-color-only、Switch Access / keyboardでの
  「status card → 再開面 → 破棄」完結。
- status cardの読み順はTO-BE §13-5「状態→残期限→操作」に従う（#366が定めた読み順への
  挿入形。#366 specのAccessibility節と整合）。
- 破棄はD-13語彙（破棄ラベル＋確認dialog 1回。両入口共通）でTalkBackに意図が読み取れること。
  破棄確認dialogの本文に「依頼が有効な間は再取り込み可能」の案内を含む。
- 新規・改訂stringは `values/` + `values-ja/`（正本）の双方へ供給し、複合文はformat resourceで
  構成する（spec 123 AC-4/AC-5規約）。残時間表示は#372のT-15事前表示と同一の語彙・導出方式
  （session失効時刻からの残時間、`exchange_request_remaining_*` 相当）を用いる。

## Compatibility and migration

- 新規durable store追加のみ。既存store（export session / recovery DB / layout DB / preference）
  のschema変更なし。既存データのmigrationなし。
- 同一process内の既存flow（import成功状態・CTA・freeze・失敗表示・生成flow）は不変であり、
  #366実装のstatus cardへの行追加・#372実装のT-15への接続は後続PRであっても本Issue単独で
  shippableである（実装順序はplan.mdに記載）。
- process death後のrun再構築・rebindは現行どおり非永続であり、#375のscope検証が最終防衛である。
- rollback: PR revertで現行挙動へ戻る。残留recordは旧版では読まれず、再適用後のreconcileで
  清掃される。

## Dependencies

- **#373（CLOSED・implemented。main `c05435a947` 時点でmerge済み）**: T-18表示面（成功面回帰・
  失敗手段別投影）を実装済み。本specは成功面表示を回帰として固定する。取り込み破棄のD-2/D-13
  不整合の解消とCTA copy（D-3→T-18語彙）を#373から委譲受領した（spec 373 Dependencies）。
- **#366（CLOSED・implemented）**: hub/status card第1段階を実装済み（`OrganizerHubPreferences`。
  durable status行・checking行・開始CTA・材料導線）。本specの提案行・依頼行はそのstatus cardへの
  追加であり、挿入点（durable status行と開始CTAの間）・TalkBack順・fail-closed様式は#366実装で
  確認済み。HUB-AC-03（否定的観測）の対象縮小を本Issueの実装PRで行う。
- **#372（CLOSED・implemented）**: T-15/T-16・依頼語彙・pre-send cancel破棄化・置換確認D-13語彙を
  実装済み。status cardへの依頼表示を本Issueへ明示委譲している（spec 372 Non-goals/Dependencies）。
  T-15事前表示の読取契約（entry/resume/expiry再読取）・残時間導出（`requestRemainingDisplay`）を
  依頼行で共有する。置換確認文言（取り込み済み提案の破棄追記）の契約化を本Issueが所有する。
- **#365（CLOSED・merged）**: `CONTEXT.md` の正本用語（取り込み済み提案・依頼・中止語彙規約等）は
  #365改訂済み。本specは正本用語を参照し、実装PRで尾文更新（durable化実現・破棄契約D-13）を反映する。
- **#375（OPEN）**: 再開面からのcontinuation/rebind（「この提案で続ける」の有効化・fresh run
  admission・選択復元・SCOPE_MISMATCH原因別remedy）を所有する。本specはrecordに
  canonical decisions（ref・`proposalLabel` 含む）・`intentIdentity`・entry種別を保存し、
  #375のrebind（planner projection・identity/provenance同一性の再現）に必要な最小集合を
  確定する（#375 review comment `5740062562` のblocking指摘への回答）。rebind契約自体は
  先取りしない。#375実装予定specの「継続成功は提案を消費しない」契約に本specが揃っている。
- **spec改訂の所有**: spec 328 revision 2・spec 205 pending保持規定の改訂・spec 366 HUB-AC-03
  縮小は本Issueの実装成果である（disposition §5 更新順序 #8）。**spec 328 rev.2のowner受入が
  本Issue実装の前提であり、#328（実装Issue）の実装着手は本spec（→ rev.2）の受入後である**
  （disposition §3.12、#328コメント記録済み）。
- **前提（実装済み・accepted）**: specs 204（accepted・implemented）/205（implemented）/
  328（accepted・Phase 2 implemented `34ba8ff447`）/329/330/331/332/337/348の現行契約、
  spec 271（durable status seam・implemented）、spec 123（strings契約）、
  organization-run-ux §6、organizer-to-be-ux.md @ main（accepted、PR #364。#365で正本反映済み）、
  organizer-disposition-migration.md @ main（accepted、PR #378）。

## Acceptance criteria

- [ ] **DI-AC-01**: import成功（validation通過、両entry）時にvalidated intentの内容がdurable
  pending intent storeへ保存され、process死・画面離脱後に残ることがtestされる。
  **durable保存の成功が取り込み成功状態の採用条件であること**（保存失敗で `ImportSuccess` を
  採用しないこと）がtestされる。status cardに「取り込み済みの提案（残時間）」行が表示され、そこから
  ImportReview（T-18）をcold processで開ける（内容 = spec 328 AC-4と同一基準の件数サマリ・
  残時間・破棄。継続CTAは提示しない）。保存はrun state・layout DBに触れない。
  **再構成oracle: record+session（+既存の `SessionExportReconstructor` によるexport view再構築）
  から再構成したplanner projection（`IntentPlannerAdapter.project` 相当: identity・
  itemPreferences・`groupProposalLabel`（複数の提案labelケースを含む）・`globalMinimizeMovement`）
  がimport直後のprojectionと全field同一であること**（件数サマリの全count一致を含む）。
  （Issue受入1）
- [ ] **DI-AC-02**: 提案が依頼と同一のTTL（24h）で期限切れになり、破棄・置換で無効になることが
  testされる。残時間表示の正本が依頼sessionの失効時刻であること。pre-send cancel等の
  session invalidateでも提案が無効化されること。（Issue受入2）
- [ ] **DI-AC-03**: 新しい依頼の生成（session置換の承認）が既存の取り込み済み提案を破棄すること
  （書込順序「新session保存 → 旧pending無効化」の固定、および **同一process内の成功状態・
  process-local pendingの破棄を含む**）、および置換確認dialogの文言に
  「取り込み済み提案の破棄」が含まれることがtestされる。辞退時はsession・提案ともに不変である
  （spec 205 AC-13の回帰）。（Issue受入3）
- [ ] **DI-AC-04**: storeがbackup対象外であること、restore後は提案が存在しないこと、
  downgrade（寛容無視）・再upgrade（reconcile清掃）で安全に扱われることがtestされる
  （寛容読み・fail-closed）。既存データmigrationが存在しないことがdiff reviewで確認される。
  （Issue受入4）
- [ ] **DI-AC-05**: 読取時reconcile（`pending.exportId == 現行active sessionのexportId`・TTL・
  破棄mark・構造検証）が起動時とstatus card/ImportReview読取時に適用され、不一致・破損・未知schemaの
  recordがfail-closedに無効化・清掃されることがtestされる（表示も開封も続行metadata参照も
  しない）。**置換途中のprocess death遷移とwrite failureを再現するoracleが存在する**
  （stale提案の表示・開封・参照経路が存在しないことの固定）。
  **起動時reconcileがhubを開かないfresh processでも実行され、stale recordを清掃する
  （startupのみケースの独立oracle）** こと。
  **破棄tombstone commit直後のprocess death・物理削除失敗後の再起動で、破棄済み提案が
  再表示されないoracleが存在する** こと。（Issue受入7）
- [ ] **DI-AC-06**: spec 328 revision 2が作成され、(1) process-local規定のdurable契約への置換、
  (2) freeze規則のstatus card＋T-18中心の再設計（in-flight attempt freezeの維持・画面離脱＝
  非破棄の明文化）、(3) D-3 CTA copyのT-18語彙（「この提案で続ける」）統一、(4) 破棄の
  durable削除（tombstone 2段commit）への更新、(5) D-2確認契約のD-13（破棄ラベル＋必須確認）への
  更新、(6) CTA single-flight・attempt anchor契約（AC-3）・AC-2/AC-4/AC-7本体の維持
  を含み、**owner受入済み** である。（Issue受入6。受入自体は実装PR前のdocs PR）
- [ ] **DI-AC-07**: 同一process内のCTA従来挙動が維持されることがtestされる: import成功状態からの
  CTA single-flight・attempt anchor（ABA含む）・`continuing` 中の破棄/Back不受理・gate拒否
  typed案内がspec 328 AC-3/AC-5対応の既存oracleでgreenである。durable保存の追加により
  anchor契約・zero-write性（run state）が変化しないこと。**CTAの成功settle・gate拒否・failure
  settleのいずれでもdurable recordへのwrite・削除が行われないこと**（#375「継続成功は提案を
  消費しない」契約との接続）がtestされる。
- [ ] **DI-AC-08**: 破棄（両入口: 明示ボタン・system Back、ともに確認dialog 1回）がtombstone
  2段commit（atomicな `discarded=true` commit → best-effort物理削除）でdurable recordを破棄し、
  **tombstone commit成功前は破棄成功として画面を閉じないこと・commit失敗はtyped失敗で提案が
  保持されること**、依頼sessionを失効させず、依頼有効内の同一回答textの再取り込みが成立する
  ことがtestされる。
- [ ] **DI-AC-09**: status card（提案行・依頼行）のTalkBack読み順が「状態→残期限→操作」である
  こと、破棄と開封がSwitch Access / keyboardで完結すること、200% font scaleで到達可能である
  ことがinstrumentation/manual evidenceで確認される。（Issue受入5の本Issue該当分）
- [ ] **DI-AC-10**: durable recordのfield契約が型/contract testで固定される:
  **保存するfield**（exportId・**`intentIdentity`（schemaVersion+digest）**・canonical decisions
  （export-scoped ref・semantic fields・**`proposalLabel` text**）・minimizeMovement・
  失効時刻・entry種別・破棄mark・作成時刻・schema version）が存在し、**不在field**
  （`rationale` / `confidence` / app label・folder title・category displayName /
  ref↔内部ID対応表）がrecord modelへ存在しないこと。storeがbackup除外
  class（`noBackupFilesDir`）に置かれていることが機械確認される。summary表示が既存の純粋導出と
  同一入力基準であることがcontract testで固定される（spec 328 AC-4の回帰。planner projection
  等価oracleはDI-AC-01）。
- [ ] **DI-AC-11**: spec 205のpending非保持規定がdurable pending intent契約への参照へ改訂され
  （disposition §3.12-4）、export session契約（AC-11〜AC-13）が不変であることがdiff reviewで
  確認される。spec 366 HUB-AC-03の否定的観測から進行中AI依頼・取り込み済み提案が除外され、
  依頼行・提案行の追加がtestされる（存在・残時間・操作。active session不在時の行なし含む）。
  既存失敗表示・生成flow・in-flight freezeのregression実行がgreenである。
- [ ] **DI-AC-12**: 新規・改訂のuser-visible文字列（置換確認文言拡張・persistence失敗typed案内・
  status card行・破棄確認・CTA copy改訂）がEN/ja双方に存在し、format resourceで構成され、
  既存stringの語彙（D-13破棄）と衝突しないことが機械確認される。
- [ ] **DI-AC-13**: durable保存失敗時のtyped失敗（#373 D-11様式: 保存再試行のprimary remedy・
  常設の中断/診断）が表示され、再試行で保存が成功すれば取り込み成功状態へ遷移すること、中断時は
  提案が保存されずstatus cardへ表示されないことがtestされる。

## Test oracle

| AC | Evidence |
|---|---|
| DI-AC-01 | store unit test（import成功時の保存・record内容・単一active置換・run state不変）+ holder unit test（settle時の保存呼出・**保存失敗で成功状態不採用**）+ **planner projection等価unit test**（record+session+`SessionExportReconstructor` 再構成 ≡ import直後の `IntentPlannerAdapter.project` 結果。identity・itemPreferences・`groupProposalLabel`（複数提案labelケース）・`globalMinimizeMovement` 全field同一。件数サマリ全count一致を含む）+ instrumentation（status card行の表示・cold process起動で行→再開面・内容/残時間/破棄の表示・継続CTA不在の否定的観測）。process死相当は冷起動emulator evidence |
| DI-AC-02 | store unit test（TTL判定・session従属・invalidate連動・clock注入）+ instrumentation（残時間表示とsession失効時刻の一致） |
| DI-AC-03 | controller/holder unit test（置換承認での旧pending無効化・書込順序固定・**同一process内成功状態/pending破棄**・store fake）+ 置換確認dialog文言test（en/ja）+ 辞退時不変test（spec 205 AC-13 oracleの回帰） |
| DI-AC-04 | store unit test（backup除外path `noBackupFilesDir`・未知schema/破損のfail-closed・downgrade相当の未知record読み飛ばし）+ instrumentation（restore相当の空状態起動） |
| DI-AC-05 | reconcile純粋関数のtable-driven unit test（exportId不一致 / session不在 / TTL / 破棄mark / 破損 / 未知schema / **ref集合不一致** → 無効化・清掃）+ **process death oracle**（新session保存直後のprocess死遷移をfake clock/storeで再現 → 次読取で清掃・表示なし）+ **write failure注入oracle**（AtomicFile失敗注入でpending無効化が失敗した状態 → 次読取で清掃）+ **起動時reconcileのunit/instrumentation test（hub未開封のstartupのみケース）** + **破棄tombstone oracle**（tombstone commit直後process死・物理削除失敗 → 再起動で再表示なし） |
| DI-AC-06 | spec 328 spec差分review（上記6項目の存在とAC-3維持）+ owner受入記録（Issue #374コメント） |
| DI-AC-07 | 既存 `ExchangeFlowStateHolderTest`（AC-3/AC-5対応oracle一式）がgreenであること + durable保存追加後の新規regression（settle内の保存がanchor/single-flightに影響しない・**CTA成功/gate拒否/failure settleいずれでもrecordへwrite・削除なし**） |
| DI-AC-08 | holder/instrumentation test（破棄確定 → tombstone commit → 画面close・status card行消滅・session生存・再取り込み成立。**tombstone commit失敗注入 → typed失敗・画面維持・提案保持・再試行可能**。両入口の確認dialog 1回） |
| DI-AC-09 | Compose semantics assertion（読み順「状態→残期限→操作」。提案行・依頼行）+ Switch Access/keyboard traversal + 200% font scale test + emulator screenshot（light/dark × ja/default） |
| DI-AC-10 | record modelの型/contract test（保存field存在（`intentIdentity`・`proposalLabel` text含む）+ 非対象field（rationale/confidence/対応表/app label・folder title・category displayName）の不在）+ `noBackupFilesDir` 配置の機械確認 + summary導出contract testの回帰実行 |
| DI-AC-11 | spec 205・spec 366差分review + 依頼行・提案行のinstrumentation test（active session存在/不在・残時間・T-15/ImportReview到達）+ 既存 `ExchangeFlowControllerTest` / `ExchangeImportPipelineTest` / 失敗表示・freeze対応testのregression実行 |
| DI-AC-12 | strings走査（ja/en name集合・placeholder一致。spec 123 AC-5方式）+ hardcoded literal grep |
| DI-AC-13 | holder unit test（保存失敗 → typed失敗表示・`ImportSuccess` 不採用・再試行で成功状態へ・中断で提案非保存・status card行なし）+ strings test（typed案内のen/ja） |

共通gate: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest
--tests 'app.lawnchair.organizer.*'`、organizer instrumentation lane、CI `final-status` green。
本Issueは新規durable store追加でありpersistent state変更を含むが、layout DB・recovery DBには
触れないため `risk: layout-data` は付けない（`risk: privacy` を付ける。高リスクPR要件の適用は
実装PRのlabel決定時に判定する）。

## Contract notes（owner reviewで確認すべき解釈）

1. **durable保存の失敗時の挙動（初回review指摘3で確定方針へ改訂）**: durabilityがIssue Outcomeの
   契約（消失の系は破棄・期限切れ・置換のみ）である以上、保存に失敗した提案をprocess-local
   「成功」として扱う例外は設けない。**保存の成功が取り込み済み状態の成立条件** であり、失敗は
   保存のみをretry可能なtyped失敗（D-11様式）として扱う。中断時の回復は再取り込み
   （session有効期限内）である。この方針は#373の失敗表示契約を変更せず、persistence step由来の
   失敗を追加するのみである。
2. **再開面の継続CTAの不在**: Issue本文の「cold processで開ける範囲は内容・残時間・破棄の
   表示まで」とdisposition §8の責務分割（「この提案で続ける」の有効化は#375）から、本Issueの
   再開面は継続CTAを提示しない（disabled表示・placeholderも見せない。#366 specの
   capability先取り禁止規約と同一の原理）。#375実装までの暫定面として、再取り込みの案内
   （依頼が有効な場合）を表示するかは実装PRで確定する（非blocking）。
3. **durable recordのidentity表現とprivacy境界（初回review指摘1・再review指摘1/3で確定方針へ改訂）**:
   canonical decisionsはexport-scoped ref（乱数値・単独ではitemを特定できないopaque値）と
   **正規化済み `proposalLabel` text** を含めて保存する。決定（何に対する判断か）はrefなしには
   表現できず、`proposalLabel` は `IntentPlannerAdapter` が `ItemPreference.groupProposalLabel`
   （formation key）としてplannerへ渡すplanner-effective値であり（2回目review指摘1）、
   Boolean化は「どのitem同士が同じ提案groupか」の復元を失うため保存する。
   `GroupSemantic.proposalLabel` のmodel KDoc「never persisted」規定は本Issueが改訂する
   （layout DB・category storeへの永続化ではなく、TTL 24h・app-private・backup除外の提案保持。
   実装PRでKDocを更新）。**intent content identity（`IntentIdentity`: schemaVersion+digest）も
   保存する**（2回目review指摘3/#375 review comment `5740062562` の要求への確定回答）: digestは
   `rationale` / `confidence` を含むcanonical byte表現からimport時に算出済みの値であり、raw
   textを永続せずにcold rebindで同一identity・planner provenanceを再現する（#375のrebindは
   保存済みidentityをそのまま用いる）。**禁止するのは** `rationale` / `confidence`・人間可読な
   item情報（app label・folder title・category displayName）・**ref↔内部ID対応表の複製**
   （session正本）である。entry種別の永続は#375のrebind判定（idle由来 = fresh run、
   run-in由来 = 選択復元の対象）への備えである。将来#375が追加fieldを要求した場合は
   spec改訂で拡張する（その時点でprivacy再評価）。
4. **進行中AI依頼のstatus card行の所有（初回review指摘4で解消）**: #372実装specが
   「status cardへの依頼表示は#374」、#366実装specが「進行中AI依頼・取り込み済み提案は#374/#375
   後続」と明示的に委譲しており、他に所有者が存在しないため、**本Issueが依頼行（存在・残時間・
   T-15への操作）を所有する**。TO-BE D-02のstatus card要素から実装ownerが不存在になる状態を
   解消する。owner reviewで別Issueへの移管が選ばれた場合はscope・AC（DI-AC-11）を分割する。
5. **置換確認dialog文言の改訂主体**: spec 205 AC-13の確認dialog文言の拡張は本Issueが所有する
   （disposition §7.1「#374で契約化」）。#372のT-15再構成後の文言（D-13語彙）に
   「取り込み済み提案の破棄」を追記する。gate構造・確認timingは#372実装どおり不変である。
6. **継続CTA成功settleでもrecordを削除しない（再review指摘2で確定方針へ改訂）**: Issue本文は
   提案の消失系を「破棄・期限切れ・置換のみ」と固定しており、#375実装予定spec（snapshot
   `468b29855f`）も「継続成功は提案を消費しない」「成功・失敗・拒否のいずれもdurable recordへ
   writeしない」を契約化している。本specもこれに揃げ、CTA成功settle時のrecord削除（消費）は
   **行わない**。かつて検討した消費削除案は (1) Issue本文・#375契約と正反対になる、
   (2) best-effort deleteでは「消費済み提案が未適用としてstatus cardへ復活する」不整合が
   残る、(3) run適用flowへのcross-store書込を招く、ため不採用とした。継続済み提案の表示・
   消費後lifecycleは#375が所有する。
7. **取り込み破棄の確認契約（D-2 → D-13）**: #373実装specは「取り込み破棄の確認契約（明示
   ボタン=追加確認なし）とD-13 §9（破棄=必須確認）の不整合の解消は#374（spec 328 rev.2）が
   所有」と明記し、CONTEXT.md中止語彙規約も「spec 328の確認契約の改訂は後続実装Issueが行う」と
   予告している。本specは両入口（明示ボタン・system Back）とも **確認dialog 1回** を必須とする
   （D-13）。CTA処理中の不受理（現行契約）は維持する。Issue本文の「D-2 Back語彙（破棄）…は維持」は
   「破棄語彙とBack確認の維持」と解釈し、ボタン経由の確認追加はD-13正本化に従う。
8. **起動時reconcileの接続点（初回review指摘5でnormative化）**: fresh app startupでhubを
   開かなくてもreconcileが必ず走ることを契約とする。接続先は `LawnchairApp.
   ensureOrganizerStartupReconciliation()`（spec 271 DS-AC-10と同一のidempotent共有入口）であり、
   pending storeのreconcileはstore完結（session store・pending store・clockのみ）のため
   readiness gate・model loadを待たず実行する。正確な挿入位置（trigger thread内のどこで実行するか）
   はplan.mdの提案を実装PRで確定するが、「hub未開封の起動で清掃が走る」こと自体は契約である。

## Open questions

実装開始前に解消が必須な問いはない（Contract notes 1〜8の解釈確認をowner reviewが所有し、
確認前は本specは `draft` のままである）。非blocking事項:

1. **status card行の視覚表現**（行 vs card内section）は、spec 271/#366実装のstatus card構造
  （`OrganizerHubPreferences`）の範囲で実装PRのreviewで確定する（TO-BEは視覚designを扱わない）。
2. 残時間表示の更新頻度は#372 T-15と同一（面への進入・lifecycle resume・失効時刻にscheduleした
  1回再読取。連続時計更新なし）を踏襲する。ja訳の最終文言は実装PRのstring diffで確定する
  （spec 123と同一の非blocking扱い）。

## Change history

- 2026-09-21: **Revision 2追補（3rd review comment `5761138332` の中1件対応）**: import成功
  scenarioの旧文言「label/title/free-textは含まない」がrevision 2の `proposalLabel` 保存契約と
  矛盾していたため、DI-AC-10と同一の列挙（保存: export-scoped ref・正規化済み `proposalLabel` /
  禁止: `rationale`・`confidence`・app label・folder title・category displayName・
  ref↔内部ID対応表）へ揃えた。
- 2026-09-19: Draft created for #374（spec/plan整備task）。accepted TO-BE契約
  （organizer-to-be-ux.md、PR #364: D-02/D-08/D-13/D-17/§5.3/§6.5/§8.2/§13-2/§13-5）、
  accepted処分文書（organizer-disposition-migration.md、PR #378:
  §3.12/§3.14/§4.1/§5 更新順序 #8/§7.1/§7.3/§8）、現行実装調査、先行spec draft（#373/#366/#372）、
  Issue #328コメントを入力に作成。
- 2026-09-21: **Re-entry revision 2（2nd review 2026-09-21 Changes requested 3件対応
  〔comment `5760840173`〕。初回6件の解消は確認済み）**。
  **(1) `proposalLabel` のlossless保存（高）**: Boolean flag化を撤回し、正規化済み
  `proposalLabel` textをcanonical decisionsの一部として保存へ変更
  （`IntentPlannerAdapter` が `ItemPreference.groupProposalLabel`（formation key）として
  plannerへ渡すplanner-effective値であり、複数提案groupの復元に文字列が必要なため。
  `GroupSemantic` のmodel KDoc「never persisted」規定を本Issueが改訂）。oracleを
  summary件数一致から **planner projection全field等価**（identity・itemPreferences・
  `groupProposalLabel` 複数labelケース・`globalMinimizeMovement`。`SessionExportReconstructor`
  によるexport view再構築を含む）へ昇格（DI-AC-01/10）。
  **(2) CTA成功settleでのrecord消費を廃止（高）**: Issue本文の消失系契約（破棄・期限切れ・置換
  のみ）と#375実装予定spec（snapshot `468b29855f`）の「継続成功は提案を消費しない」契約に
  揃え、CTA成功settle時の削除を行わない（Outcome/Scope/scenario/Data and state/Contract
  notes 6/DI-AC-07）。
  **(3) `IntentIdentity` の永続（中）**: intent content identity（schemaVersion+digest）を
  recordへ保存し、cold rebindで同一identity・provenanceを再現する契約を確定
  （#375 review comment `5740062562` のblocking指摘への回答。digestはrationale/confidenceを
  含むcanonical表現から算出済みの値であり、raw text永続なしで同一性を保持）（DI-AC-01/10・
  Data and state・Contract notes 3）。
- 2026-09-21: **Re-entry revision（初回review 2026-09-19 Changes requested 6件対応 +
  current main `c05435a947` へのre-entry。snapshot baseline `a2b6aba318` からは
  #365/#366/#369〜#373のmergeを含む）**。
  **(1) identity表現の確定（高）**: 「refを一切永続しない」DI-AC-10/privacy境界を撤廃し、
  canonical decisionsはopaqueなexport-scoped refを含めて保存・禁止対象を
  label/title/free-text（rationale/confidence/proposalLabel text）とref↔内部ID対応表の複製へ
  修正（`CompletedPersonalIntent.decisions` が `Map<String, RefDecision>` でmap key=ref・
  `ItemIntent` が `desiredGroupRefs`/`groupSemantic.categoryRef` を持つ現行実装事実に整合。
  `proposalLabel` は「never persisted」のmodel契約どおり保有flagのみ）。summary round-trip
  oracle・ref集合不一致の破損検出を追加。
  **(2) 破棄のtombstone 2段commit（高）**: 明示破棄を「`discarded=true` tombstoneのatomic
  commit → best-effort物理削除」の2段へ設計変更。tombstone commit成功前に画面を閉じない・
  commit失敗はtyped失敗で提案保持・store APIの失敗観測（`discard()`）・「commit直後process
  death・削除失敗で再表示されない」oracle（DI-AC-05/08）を追加。
  **(3) 保存失敗のtyped扱い（中）**: process-local後退を廃止し、durable保存成功を取り込み済み
  状態の成立条件へ変更（Contract notes 1・DI-AC-01/07/13・scenario差し替え）。persistence失敗は
  #373 D-11様式のtyped失敗（保存再試行）。
  **(4) 進行中AI依頼行の所有確定（中）**: #372実装spec（「status cardへの依頼表示は#374」）・
  #366実装spec（後続委譲）の明示委譲を受領し、依頼行（存在・残時間・T-15操作）を本Issue scopeへ
  追加（DI-AC-11・Contract notes 4）。spec 366 HUB-AC-03縮小を実装成果に追加。
  **(5) 起動時reconcileのnormative化（中）**: 「hub未開封のfresh process起動でもreconcileが必ず
  走る」を契約化（`ensureOrganizerStartupReconciliation()` 接続・store完結でgate非依存）。
  planの「projection初回読取への集約」代替案を廃止。startupのみ独立oracleを追加
  （DI-AC-05・Contract notes 8）。
  **(6) baseline/dependencies/用語のre-entry（低）**: baselineを `c05435a947` へ更新し
  #365/#366/#372/#373をsatisfied（merge済み）へ。Domain languageを「追加用語案」から
  CONTEXT.md正本参照+実装時差分へ変更。Problem記述を#372/#373実装後の現状へ更新。
  **(7) re-entryで確認した追加整合**: 取り込み破棄の確認契約をD-13（両入口確認1回）へ更新
  （#373委譲・CONTEXT.md中止語彙規約の予告どおり。Contract notes 7）。CTA成功settleでのrecord
  消費削除を契約化（Contract notes 6）。session置換時の同一process内成功状態/pending破棄を追加
  （ownership gapのin-process排除）。CTA copyのT-18語彙統一（「この提案で続ける」）を明記。

## References

- [Issue #374][1]
- [organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)（accepted。D-02/D-08/D-13/D-17、§5.3、§6.5、§8.2、§9、§10、§13-2、§13-5）
- [organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)（accepted。§2.1/§3.12/§3.14/§4.1/§5 更新順序 #8/§7.1/§7.3/§8）
- [Spec 328: import success state](../328-exchange-import-success-state/spec.md)（accepted・Phase 2 implemented。revision 2は本Issueが所有）
- [Spec 205: External Agent Exchange](../205-external-agent-exchange/spec.md)（implemented。export session契約・AC-13置換確認の所有。pending保持規定を本Issueが改訂）
- [Spec 204: AI personalization context/intent exchange contract](../204-ai-personalization-context-intent-contract/spec.md)（accepted・implemented。export session store（TTL 24h・単一active・app-private・backup除外）の所有）
- [Spec 366: organizer hub shell](../366-organizer-hub-shell/spec.md)（implemented。status card第1段階の構造・読み順・HUB-AC-03。本Issueが2行を追加）
- [Spec 372: AI consultation request flow](../372-ai-consultation-request-flow/spec.md)（implemented。T-15/T-16・依頼語彙・置換確認D-13語彙。status card依頼表示を本Issueへ委譲）
- [Spec 373: import display reprojection](../373-import-display-reprojection/spec.md)（implemented。T-17/T-18失敗面。D-2/D-13不整合の解決とCTA copyを本Issueへ委譲）
- [Spec 271: organizer durable status projection](../271-organizer-durable-status-projection/spec.md)（implemented。status card projectionの閉域語彙・fail-closed・startup reconciliation様式の正本）
- [Spec 330: partial intent authoring](../330-partial-intent-authoring/spec.md)（implemented。`CompletedPersonalIntent` canonical representationの所有）
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md)（implemented。run-in entry・owning runId。§5 idle entry経路は#374/#375で改訂対象）
- [Spec 332: exchange import input UI](../332-exchange-import-input-ui/spec.md)（implemented。T-17入力面）
- [Spec 123: organizer UI convergence](../123-organizer-ui-convergence/spec.md)（ja/en strings契約）
- [organization-run-ux.md](../../docs/product/organization-run-ux.md)（§6 accessibility受入基準）
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)（record内容出力禁止の正本）
- [CONTEXT.md](../../CONTEXT.md)（#365改訂済み正本用語: 取り込み済み提案・依頼・中止語彙規約等）,
  [DESIGN.md](../../DESIGN.md)（§4.2 projection様式・§7 data ownership）, [AGENTS.md](../../AGENTS.md)

[1]: https://github.com/nunu1733/NunuLauncher/issues/374
[2]: https://github.com/nunu1733/NunuLauncher/issues/373
[3]: https://github.com/nunu1733/NunuLauncher/issues/366
[4]: https://github.com/nunu1733/NunuLauncher/issues/375
