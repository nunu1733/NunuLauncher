---
issue: "#374"
status: draft
requirements: [FR-017]
risk:
  - privacy
updated: 2026-09-19
---

# 取り込み済み提案（pending intent）をdurable化しhub status cardへ接続する（D-08）

> 契約の根拠: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-02, D-08, D-17, §5.3 idle AI相談の遷移, §6.5, §8.2, §9 語彙規約, §13-2 影響評価項目2, §13-5）。
> 処分の正本: accepted disposition
> [docs/product/organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)
> （§2.1 #328行「#373（表示）→#374（spec rev.2後実装）」、§3.12 spec 328分段改訂、
> §3.14 spec 205「pending intent保持」分段改訂、§4.1 supersession map、
> §5 更新順序 #8、§7.1 新規persistent state、§7.3 compatibility matrix、§8 依存graph）。
> 本specは[Issue #374][1]の成果物である。statusが `draft` の間はimplementation-readyではない。
> 前提: [Issue #373][2]のT-18表示面（spec draft `859e51fe`、branch `issue-373-spec-plan`）と
> [Issue #366][3]のhub/status card第1段階（spec draft `bf00f96175`、branch `issue-366-spec-plan`）。
> 本specの執筆はこれらのdraftとの整合で可能である（現時点でそうしている）。実装着手は
> disposition §8 依存graph（`#372 → #373 → #374`、`#366 → … → #374`）と
> 「正本を先に」（#365 merge後）に従う。

## Problem

現行（baseline `a2b6aba318`、spec 205/328/329/330/331/332/337 contract core/348実装済み、
#328 Phase 2実装merge `34ba8ff447`）では、import成功（validation通過）後のvalidated intentは
`ExchangeFlowStateHolder` のprocess-localなpending slot（`pendingValidated`）と
`ImportSuccess` 画面stateのみに存在する。このため:

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
4. **status cardに表示されるdurable事実が不完备**: TO-BE D-02はstatus cardを「durable事実と進行中
   状態の単一閲覧面」と定め、D-08は取り込み済み提案のdurable化を決定したが、現行実装には
   status cardへ接続するdurableな提案状態が存在しない。

accepted disposition（§3.12/§3.14/§4.1）は、spec 328の「取り込み成功状態とpending intentは
process-localのみ」規定とspec 205の「validated intentを保持しない」規定をD-08へ
**Amend-Supersede** することを決定した。spec 328 revision 2とspec 205の該当規定改訂は
本Issueが所有する（§5 更新順序 #8）。

## Outcome

import成功（validation通過）時にvalidated intentの内容が **durableな取り込み済み提案
（pending intent）store** へ保存される。提案は依頼（export session）と同一の有効期限（24h）を持ち、
process死・画面離脱で消えない。消失の系は「破棄」操作・期限切れ・置換（新しい依頼の生成の承認）
のみであり、その有効性は対応する依頼sessionの有効性に従属する（単一active session契約と同一の
lifecycle。ownership gapの契約上排除）。

hub status cardに「取り込み済みの提案（残時間）」が表示され、`Hub → ImportReview（T-18）` で
提案の内容（privacy-safeな件数サマリ）・残時間・破棄をcold processで開ける。再開の導線は
status card経由の1経路に集約される（process生存/死を問わない。TO-BE §5.3/§6.5）。
本Issueの再開面で **runへのcontinuation/rebind（「この提案で続ける」の有効化・fresh run
admission・選択復元）は提供しない** — それは[Issue #375][4]が所有する。同一process内の
import成功状態からのCTA従来挙動（既存seam・single-flight・attempt anchor）は本Issueで維持する。

現行spec 328の「process-localのみ」規定はdurable契約へ置換され（revision 2）、import attempt
freeze規則（idle start row / strategy picker / Back / discardの4箇所個別規定）は
status card＋T-18中心の再設計へ更新される。CTA single-flight・attempt anchor契約（spec 328
AC-3）・D-2破棄語彙は維持される。

## Scope

- **durable pending intent storeの新設**: app-private・**backup除外**（export session
  （spec 204 `AndroidExportSessionStore`）とrecovery DBと同じclass）。単一active（保存は
  import成功時に1件を上書き保存）。TTLは依頼（export session）と同一であり、**提案の有効性は
  対応する依頼sessionの有効性に従属する**。
- **store契約**: 保存（import成功時）・破棄・期限切れ・置換無効化。intent内容はinternal表現
  （`CompletedPersonalIntent` 相当。canonical decisions + planner-effectiveな全体方針）で保存し、
  export-scoped ref↔内部ID対応表は依頼session側の正本（`ExportSession.itemRefs` /
  `categoryRefs`）を利用して複製しない。
- **crash consistency**: session置換とpending破棄の単一atomic commitは要求しない。書込順序を
  「新session保存 → 旧pending無効化」に固定し、**読取時reconcileを正本** とする:
  提案は表示・開封・（#375以降の）続行metadata参照の前に必ず
  (a) `pending.exportId == 現行active sessionのexportId`、(b) TTL（session失効を含む）、
  (c) 破棄mark不在 を検証し、不一致は **fail-closedに無効化・清掃** する（表示も開封も続行もしない。
  破棄相当の取り扱い）。reconcileは **起動時** と **status card読取時・ImportReview読取時** の
  両方に適用する。**置換途中のprocess death・write failureを再現するoracleを必須とする**
  （disposition §7.1）。
- **session置換による破棄の契約化**: 新しい依頼の生成（session置換の承認、spec 205 AC-13）は
  既存の取り込み済み提案を破棄する（TO-BE §13-2推奨「新しい依頼が古い取り込みを無効化する」を採用）。
  置換確認dialog（現行 `exchange_replacement_warning`）の文言を「既存依頼宛回答の無効化」に加え
  「取り込み済み提案の破棄」を含むよう拡張する（D-13の「破棄」語彙）。
- **status card「取り込み済みの提案」行**: 提案の存在（reconcile通過済み）・残時間（依頼sessionの
  失効時刻から導出。表示上の正本はsession側）を閉域語彙で表示し、`Hub → ImportReview` を開く
  操作を提供する。行はspec 271のdurable status行と同一の契約様式（閉じた語彙・fail-closed・
  payload非表示）に従う。status card自体の構造（第1段階）は[Issue #366][3]の所有であり、
  本Issueは**提案行とその開封操作の接続**を所有する。
- **ImportReview再開面（cold process到達範囲）**: 再開面で表示するのは **内容（spec 328と同一の
  privacy-safe件数サマリ）・残時間・破棄** までである。runへのcontinuation/rebind
  （fresh run admission・選択復元・「この提案で続ける」の有効化）は **#375が所有** する。
  再開面の破棄はspec 328 D-2の語彙と保護（明示ボタンは追加確認なし / system Backは確認dialog /
  CTA処理中は不受理 — 継続CTAが存在しないため処理中不受理は構造的に発生しない）を踏襲し、
  **durable recordを削除する** よう更新する（現行のprocess-local破棄（zero-write）からの変更点）。
  破棄後も依頼（export session）は失効せず、依頼が有効な間は同じ回答textの再取り込みが可能である。
- **同一process内CTA従来挙動の維持**: import成功状態が表示されている連続したflow内でのCTA
  （`run.start` / `attachIntent`）・single-flight・attempt anchor・`continuing` 中の破棄/Back
  不受理は既存契約（spec 328 AC-3/AC-5）どおり **不変** である。成功settle時のdurable保存は
  この契約に手を入れない。
- **spec 328 revision 2（本Issueの実装成果。本specの受入後に執筆・受入）**: disposition §3.12の
  処分どおり、(1) Data and stateの「取り込み成功状態とpending intentはprocess-localのみ」を
  durable契約への置換へ改訂、(2) import attempt freeze規則（idle start row / strategy picker /
  Back / discardの4箇所個別規定）をstatus card＋T-18中心の再設計へ改訂（in-flight attemptの
  競合freezeは現行契約を維持し、attempt終端後の消失保護はdurable契約＋読取時reconcileへ移管。
  画面離脱＝破棄ではないことを明文化）、(3) D-3 CTA copyをTO-BE T-18語彙（「この提案で続ける」）へ
  統一（#373 Contract notes 3から委譲された所有）、(4) 破棄のzero-write規定をdurable削除へ更新。
  AC-2（未適用表示）/AC-3（single-flight・attempt anchor）/AC-4（件数summary）/AC-7（Back
  interception）の本体は維持。
- **spec 205の改訂（本Issueの実装成果）**: 「activeなrun操作中…validated intentを保持しない」を
  含むpending非保持規定を、durable pending intent契約（本spec）への参照へ改訂する
  （disposition §3.14/§4.1）。export session契約（TTL・単一active・置換確認・AC-11〜13）は不変。
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
  （TO-BE §8.2）。
- **export session契約の変更**: TTL 24時間・単一active session・置換確認のgate構造・
  pre-send cancel（spec 204/205 AC-11〜AC-13）は不変。session置換確認dialogの文言拡張は行うが、
  gate構造・確認timingは変更しない。
- **進行中AI依頼（in-flight request）のstatus card行**: TO-BE D-02の要素であるが、本Issueの
  scope本文は取り込み済み提案行のみを列挙する。T-15事前表示は#372が所有する（Open questions 1）。
- **T-15/T-16の再構成・依頼語彙の統一**: #372。**T-18失敗面の手段別再投影**: #373。
  本specは#372/#373の契約を先取り・先実装しない。
- **import検証・normalizer・validator契約の変更**（spec 204/329/330）: durable保存は
  validation通過後のみに行い、検証分類・zero-write性・attempt anchorには触れない。
- **既存失敗表示・生成flow・transportの変更**（spec 205/332/348契約の維持）。
- run state machine・spec 13/52/271の契約・diagnostics event・permission・外部送信経路の変更。

## Domain language

`CONTEXT.md` への追加用語案（受入時に反映。#365が正本改訂を所有するため、本specでは案の記載に
留める）。

**取り込み済み提案 (Imported Proposal / durable pending intent)**:
import成功（validation通過）後にdurable storeへ保存されたvalidated intentの内容。
依頼（export session）と同一の有効期限を持ち、その有効性は依頼sessionの有効性に従属する。
process死・画面離脱で消えない。消失の系は破棄・期限切れ・置換のみである。
_Avoid_: pending intent（実装語。user-facing copyでは「取り込み済みの提案」を使う）、
下書き（依頼sessionが正本であり提案は従属物であることの混同）

**durable pending intent store (取り込み済み提案store)**:
取り込み済み提案を1件だけ保持するapp-private・backup除外のdurable store。単一activeであり、
新しいimport成功が旧recordを置換し、session置換の承認がrecordを破棄する。ref対応表は保持せず
依頼session側の正本を利用する。
_Avoid_: intentの永続化全般（run/preview/選択は永続化しない。本storeは提案のみ）

**読取時reconcile (read-time reconcile)**:
取り込み済み提案を表示・開封・参照の前に必ず適用する有効性検証（`pending.exportId ==
現行active sessionのexportId`・TTL・破棄mark）。不一致はfail-closedに無効化・清掃する。
跨storeのatomic性を要求しない代わりの正本である（disposition §7.1）。
_Avoid_: 書込時の二重commit（要求しない）、起動時のみの検証（読取時にも適用する）

## Behavior scenarios

### Scenario: import成功時に取り込み済み提案がdurable保存される

Given いずれかのentry（idle / run-in）でAI回答がvalidationに通過した、
When validation settleが成功状態（`ImportSuccess`）を採用する、
Then validated intentの内容がdurable pending intent storeへ **同時に** 保存され
（保存は成功状態の採用と同一settle内で完了する。run state・layout DBへのwriteは発生しない）、
成功状態の表示は現行契約（spec 328 AC-1/AC-2/AC-4）どおりである、
And 保存される内容はcanonical decisions（`CompletedPersonalIntent` 相当）とplanner-effectiveな
全体方針（`minimizeMovement`）であり、`exportId`・依頼sessionの失効時刻（同一TTL）・
attempt由来のentry種別（#375のrebind判定用）を含む。**ref↔内部ID対応表・app label・folder
title・AI自由文（`rationale`）・`confidence` は保存しない**（対応表の正本は依頼session。
表示・privacy境界はContract notes 3）、
And 成功状態からのCTAは現行seam（`run.start` / `attachIntent`）を現行契約どおり一度だけ実行し
（spec 328 AC-3の回帰。single-flight・attempt anchor・`continuing` 中の破棄/Back不受理を含む）、
成功settleで成功状態が閉じる。

### Scenario: 同一process内のCTA従来挙動は変化しない（回帰）

Given 本Issueの変更が適用されており、同一process内でimport成功状態が表示されている、
When ユーザーがCTAを押す、
Then 既存seam経由の接続が一度だけ行われ、durable storeへの追加write・状態遷移の変更はない
（成功settle時の挙動は現行と同一。durable保存はsettleのanchor契約（attempt token）に手を入れない）、
And CTAのgate拒否（`Busy` / `NotAttachable`）・seam例外時のfailure settle・strategy書込との
相互排他（arbiter）はspec 328 AC-3/AC-5の現行契約どおりである。

### Scenario: durable保存の失敗は成功状態を無効化しない（process-local後退）

Given importがvalidationに通過したが、durable pending intent storeへの保存が書込失敗で
失敗した、
When 成功状態が表示される、
Then 成功状態は現行契約どおり表示され、同一process内のCTA・破棄は現行どおり機能する
（process-localの提案として扱う）、
And **durable保存が失敗した提案はstatus cardへ表示されない**（表示の正本はdurable recordの
み。存在しない提案を偽装しない）、
And typedな案内（提案が保存されず、process終了で失われる旨。ja正本・en同意味）が表示される
（Contract notes 1）。

### Scenario: 画面離脱で提案は消えず、status cardへ現れる

Given import成功後に提案がdurable保存され、ユーザーが破棄せずに設定面・exchange導線を離れた
（system Backの確認dialogでキャンセル、または別面への遷移。画面離脱は破棄ではない）、
When hubを開く、
Then status cardに「取り込み済みの提案」行が表示され、残時間（依頼sessionの失効時刻から導出）が
示される、
And 提案の内容・件数サマリは失われず、`Hub → ImportReview` で再開面へ到達できる、
And process死後も同一の行が表示される（durable契約。TO-BE §8.2「見えないが残っているもの」を
作らない — 存在・期限・消える条件がstatus cardで可視である）。

### Scenario: cold processでstatus cardからImportReview（T-18）を開く

Given process死後にhubが開かれ、status cardに取り込み済み提案の行がある、
When ユーザーが行（の操作）をタップする、
Then 再開面（ImportReview / T-18成功面の再開形態）が開き、次を表示する:
(a) 内容 = spec 328 AC-4と同一のprivacy-safe件数サマリ（認識件数・判断なし合算・内訳4種・
group breakdown・全体方針行。durable recordと依頼sessionから同一の純粋導出で再構成する）、
(b) 残時間、(c) 破棄操作、
And **継続CTA（「この提案で続ける」）は提示しない**（#375が有効化するまでの本Issueの到達範囲。
Contract notes 2）、
And 再開面はrun stateを参照せず変更しない（run admission・`start(trigger)` 発行の経路は
存在しない）。

### Scenario: 再開面からの破棄（durable削除）

Given 再開面が表示されている（cold process / 画面離脱後の再開。CTA処理中は存在しない）、
When ユーザーが破棄する（明示ボタン「破棄して閉じる」、またはsystem Back → 確認dialog
「取り込みを破棄しますか?」での確定）、
Then durable recordが削除され、status cardの行は消える（fail-closedな消失ではなく明示的破棄）。
依頼（export session）は失効せず、依頼が有効な間は同じ回答textを再取り込みできる（D-2語彙の踏襲）,
And キャンセルした場合は提案は保持される（確認dialogのキャンセルでrecordを削除しない）。

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
And 置換確認dialogの文言は「既存依頼宛回答の無効化」に加え「取り込み済み提案の破棄」を含む
（D-13語彙。現行 `exchange_replacement_warning` の拡張。en/ja双方）、
And 置換を辞退した場合は既存sessionと提案がともに不変である（現行AC-13の回帰）。

### Scenario: 読取時reconcileのfail-closed（不一致・破損・未知schema）

Given durable recordが存在するが、(a) 対応するsessionが存在しない/不一致の `exportId`、
(b) TTL失効、(c) 破棄mark付き、(d) 未知schema・破損・読取失敗、のいずれかである、
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

### Scenario: 単一active — 新しいimport成功が旧recordを置換する

Given 同一の依頼session宛のimportが繰り返し成功する（回答textの再取り込み等）、
When 新しいvalidation settleが成功状態を採用する、
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

Given status cardに取り込み済み提案の行がある、
When TalkBackで行を読み上げる、
Then 読み順はTO-BE §13-5の「状態→残期限→操作」規約に従い、#366が定めたstatus cardの読み順
（本段階の実効順序「状態→操作」）に **残期限要素が挿入される** 形である、
And 行の操作（ImportReviewを開く）・残時間の意味がTalkBackで識別可能である（name/role/state）。

### Scenario: 既存flowの回帰（失敗面・生成・freeze・表示）

Given 本Issueの変更が適用されている、
When import失敗経路・生成flow（生成 → 送信前確認 → transport）・pre-send cancel・
in-flight import attempt中の競合freeze（idle start row / strategy picker / arbiter /
Back確認dialog / 破棄ボタン）を実行する、
Then これらはspec 205/328/332/#373の現行契約どおり機能する（in-flight attemptのfreeze規則は
rev.2でも維持される。消失保護の移管はattempt終端後にのみ影響する）、
And #328実装対応の既存test（`ExchangeFlowStateHolderTest` /
`ExchangeImportSuccessInstrumentationTest` 等）が無編集または契約更新に対応した更新のみで
greenである。

## Data and state

- **読むdata**: durable pending intent record（単一。`exportId`・canonical decisions・
  planner-effective `minimizeMovement`・失効時刻（session複製値）・entry種別・破棄mark）、
  現行active session（`ExportSessionStore.active(now)`。reconcileと残時間表示の正本）、
  現在時刻（注入clock）。#204/#330契約を本specは再定義しない。
- **書くdata**: durable pending intent storeのみ（import成功時の保存 / 破棄・reconcileによる
  削除 / 置換無効化）。layout DB・`favorites`・recovery store・export session・diagnostics
  journalへのwriteは発生しない。ホームレイアウト安全規約の適用対象外である
  （`favorites` への接触なし。トランザクション要件はstore単体のatomic性で充足する）。
- **Identity**: 提案のidentityは `exportId`（対応する依頼sessionと1:1）。単一active契約により
  proposal:session = 1:1であり、`pending.exportId == active session.exportId` が有効性の
  一致条件である。ref対応表は複製しない（session側正本。`ExportSession.itemRefs` /
  `categoryRefs`）。
- **lifetime**: 提案のTTL = 依頼sessionの失効時刻と同一（24時間）。有効性の従属により、
  session失効・invalidate・置換のすべてが提案を無効化する。残時間表示はsession側失効時刻から
  導出する。
- **reconcileの適用点**: 起動時（既存のorganizer startup reconciliation triggerと同じ
  idempotent経路に接続。Launcher未起動のfresh processでも到達可能。spec 271 DS-AC-10と同一の
  初期化経路）と、status card読取時・ImportReview読取時（読み取りのたびに検証する。起動時
  reconcileは早期清掃であり、読取時reconcileが正本）。
- **永続化の実装様式**: export sessionと同一のclass（app-private `noBackupFilesDir`・
  `AtomicFile`による単一recordのatomic書込・schema version検査・corruption/未知schema/
  書込失敗のfail-closed退化「recordなし」）。詳細は[plan.md](./plan.md)に記載する。
- **migration / backup / restore / rollback**: 新規store追加のみでデータ移行なし。backup対象外。
  downgradeは寛容無視、再upgradeはreconcile清掃。rollback（PR revert）時もstore残留は
  次回読取でreconcileされる（schema version检查により旧版の読み取り経路は存在しない）。
  store削除で機能のみ失い、レイアウトへ影響しない。

## Permissions, privacy, and security

- 追加permission・network通信・外部送信経路なし。
- **durable recordのprivacy境界**: 保存するのは件数サマリの導出に必要なcanonical decisions
  （importance / group希望 / 配置先希望 / 保持希望のsemantic field）とplanner-effectiveな
  boolean 1値、identity anchor（`exportId`）・失効時刻・entry種別のみである。
  **app label・folder title・export-scoped ref・AI自由文（`rationale`）・`confidence` は
  永続化しない**（spec 328 summary規約と同一のprivacy境界をdurable層へ拡張。
  Contract notes 3）。
- **backup除外**: recordは `noBackupFilesDir` に置き、端末backup/restoreへ含めない
  （export sessionと同じclass。disposition §7.1）。
- status card・再開面に表示するのは件数サマリ・残時間・操作のみであり、ref・label・自由文は
  いずれの層にも表示しない。summary導出は既存の純粋関数と同一入力基準で行い、出力modelへの
  非対象field混入をcontract testで防止する（spec 328 AC-4の回帰）。
- 診断へのrecord内容出力は行わない（organizer-diagnostics.md契約の維持）。

## Accessibility and localization

- organization-run-ux §6のaccessibility受入基準を適用する: status card提案行・再開面の
  TalkBack name/role/state、focus restoration、200% font scale（200% reflow）で残時間・
  内容サマリ・破棄操作が画面内で到達可能、non-color-only、Switch Access / keyboardでの
  「status card → 再開面 → 破棄」完結。
- status cardの読み順はTO-BE §13-5「状態→残期限→操作」に従う（#366が定めた読み順への
  挿入形。#366 specのAccessibility節と整合）。
- 破棄はD-2語彙（明示ボタンは追加確認なし / system Backは確認dialog）でTalkBackに意図が
  読み取れること。破棄確認dialogの本文に「依頼が有効な間は再取り込み可能」の案内を含む
  （spec 328 D-2と同一様式）。
- 新規・改訂stringは `values/` + `values-ja/`（正本）の双方へ供給し、複合文はformat resourceで
  構成する（spec 123 AC-4/AC-5規約）。残時間表示は#372のT-15事前表示と同一の語彙・導出方式
  （session失効時刻からの残時間）とすることを原則とし、文言確定は実装PRで行う（非blocking）。

## Compatibility and migration

- 新規durable store追加のみ。既存store（export session / recovery DB / layout DB / preference）
  のschema変更なし。既存データのmigrationなし。
- 同一process内の既存flow（import成功状態・CTA・freeze・失敗表示・生成flow）は不変であり、
  本Issue単独でshippableである（#366/#373未merge環境ではstatus card接続面のみが後続PRで
  有効化される構成を許容する。実装順序はplan.mdに記載）。
- process death後のrun再構築・rebindは現行どおり非永続であり、#375のscope検証が最終防衛である。
- rollback: PR revertで現行挙動へ戻る。残留recordは旧版では読まれず、再適用後のreconcileで
  清掃される。

## Dependencies

- **#373（OPEN、spec draft `859e51fe`）**: Issue本文 `Depends on`。T-18表示面の構造（成功面の
  表示model・破棄語彙の確認）を所有する。本specは成功面表示を回帰として固定するのみであり、
  執筆は#373 draftとの整合で可能（現時点でそうしている）。**実装着手は#373 merge後**
  （disposition §8: `#372 → #373 → #374`）。
- **#366（OPEN、spec draft `bf00f96175`）**: Issue本文 `Depends on`。hub/status card第1段階の
  構造・読み順契約を所有する。本specの提案行はそのstatus cardへの追加であり、#366の
  「status cardの拡張は後続Issue」のNon-goalsと相互排他を確認済み。実装着手は#366 merge後。
- **#365（OPEN、正本改訂）**: `CONTEXT.md` への取り込み済み提案等の用語追加は#365が所有する。
  実装着手は#365 merge後（AGENTS.md「正本を先に」）。
- **#375（OPEN）**: 再開面からのcontinuation/rebind（「この提案で続ける」の有効化・fresh run
  admission・選択復元・SCOPE_MISMATCH原因別remedy）を所有する。本specはrecordにentry種別を
  保存するだけでrebind契約を先取りしない。
- **spec改訂の所有**: spec 328 revision 2とspec 205 pending保持規定の改訂は本Issueの実装成果
  である（disposition §5 更新順序 #8）。**spec 328 rev.2のowner受入が本Issue実装の前提であり、
  #328（実装Issue）の実装着手は本spec（→ rev.2）の受入後である**（disposition §3.12、
  #328コメント記録済み）。
- **前提（実装済み・accepted）**: specs 204（accepted・implemented）/205（implemented）/
  328（accepted・Phase 2 implemented `34ba8ff447`）/329/330/331/332/337/348の現行契約、
  spec 271（durable status seam・implemented）、spec 123（strings契約）、
  organization-run-ux §6、organizer-to-be-ux.md @ main `a2b6aba318`（accepted、PR #364）、
  organizer-disposition-migration.md @ main `a2b6aba318`（accepted、PR #378）。

## Acceptance criteria

- [ ] **DI-AC-01**: import成功（validation通過、両entry）時にvalidated intentの内容がdurable
  pending intent storeへ保存され、process死・画面離脱後に残ることがtestされる。status cardに
  「取り込み済みの提案（残時間）」行が表示され、そこからImportReview（T-18）をcold processで
  開ける（内容 = spec 328 AC-4と同一基準の件数サマリ・残時間・破棄。継続CTAは提示しない）。
  保存はrun state・layout DBに触れない。（Issue受入1）
- [ ] **DI-AC-02**: 提案が依頼と同一のTTL（24h）で期限切れになり、破棄・置換で無効になることが
  testされる。残時間表示の正本が依頼sessionの失効時刻であること。pre-send cancel等の
  session invalidateでも提案が無効化されること。（Issue受入2）
- [ ] **DI-AC-03**: 新しい依頼の生成（session置換の承認）が既存の取り込み済み提案を破棄すること
  （書込順序「新session保存 → 旧pending無効化」の固定を含む）、および置換確認dialogの文言に
  「取り込み済み提案の破棄」が含まれることがtestされる。辞退時はsession・提案ともに不変である
  （spec 205 AC-13の回帰）。（Issue受入3）
- [ ] **DI-AC-04**: storeがbackup対象外であること、restore後は提案が存在しないこと、
  downgrade（寛容無視）・再upgrade（reconcile清掃）で安全に扱われることがtestされる
  （寛容読み・fail-closed）。既存データmigrationが存在しないことがdiff reviewで確認される。
  （Issue受入4）
- [ ] **DI-AC-05**: 読取時reconcile（`pending.exportId == 現行active sessionのexportId`・TTL・
  破棄mark）が起動時とstatus card/ImportReview読取時に適用され、不一致・破損・未知schemaの
  recordがfail-closedに無効化・清掃されることがtestされる（表示も開封も続行metadata参照も
  しない）。**置換途中のprocess death遷移とwrite failureを再現するoracleが存在する**
  （stale提案の表示・開封・参照経路が存在しないことの固定）。（Issue受入7）
- [ ] **DI-AC-06**: spec 328 revision 2が作成され、(1) process-local規定のdurable契約への置換、
  (2) freeze規則のstatus card＋T-18中心の再設計（in-flight attempt freezeの維持・画面離脱＝
  非破棄の明文化）、(3) D-3 CTA copyのT-18語彙統一、(4) 破棄のdurable削除への更新、
  (5) CTA single-flight・attempt anchor契約（AC-3）・D-2破棄語彙・AC-2/AC-4/AC-7本体の維持
  を含み、**owner受入済み** である。（Issue受入6。受入自体は実装PR前のdocs PR）
- [ ] **DI-AC-07**: 同一process内のCTA従来挙動が維持されることがtestされる: import成功状態からの
  CTA single-flight・attempt anchor（ABA含む）・`continuing` 中の破棄/Back不受理・gate拒否
  typed案内がspec 328 AC-3/AC-5対応の既存oracleでgreenである。durable保存の追加により
  anchor契約・zero-write性（run state）が変化しないこと。
- [ ] **DI-AC-08**: 再開面からの破棄がD-2語彙（明示ボタン追加確認なし / Back確認dialog /
  キャンセルで保持）でdurable recordを削除し、依頼sessionを失効させず、依頼有効内の
  同一回答textの再取り込みが成立することがtestされる。
- [ ] **DI-AC-09**: status cardのTalkBack読み順が「状態→残期限→操作」であること、破棄と開封が
  Switch Access / keyboardで完結すること、200% font scaleで到達可能であることが
  instrumentation/manual evidenceで確認される。（Issue受入5の本Issue該当分）
- [ ] **DI-AC-10**: durable recordにlabel/ref/`rationale`/`confidence` が含まれないこと
  （永続化record modelの型/contract test）、storeがbackup除外classに置かれていることが
  機械確認される。summary表示が既存の純粋導出と同一入力基準であることがcontract testで
  固定される（spec 328 AC-4の回帰）。
- [ ] **DI-AC-11**: spec 205のpending非保持規定がdurable pending intent契約への参照へ改訂され
  （disposition §3.14）、export session契約（AC-11〜AC-13）が不変であることがdiff reviewで
  確認される。既存失敗表示・生成flow・in-flight freezeのregression実行がgreenである。
- [ ] **DI-AC-12**: 新規・改訂のuser-visible文字列がEN/ja双方に存在し、format resourceで構成され、
  既存stringの語彙（D-13破棄）と衝突しないことが機械確認される。

## Test oracle

| AC | Evidence |
|---|---|
| DI-AC-01 | store unit test（import成功時の保存・record内容・単一active置換・run state不変）+ holder unit test（settle時の保存呼出・保存失敗時の後退）+ instrumentation（status card行の表示・cold process起動で行→再開面・内容/残時間/破棄の表示・継続CTA不在の否定的観測）。process死相当は冷起動emulator evidence |
| DI-AC-02 | store unit test（TTL判定・session従属・invalidate連動・clock注入）+ instrumentation（残時間表示とsession失効時刻の一致） |
| DI-AC-03 | controller/holder unit test（置換承認での旧pending無効化・書込順序固定・store fake）+ 置換確認dialog文言test（en/ja）+ 辞退時不変test（spec 205 AC-13 oracleの回帰） |
| DI-AC-04 | store unit test（backup除外path `noBackupFilesDir`・未知schema/破損のfail-closed・downgrade相当の未知record読み飛ばし）+ instrumentation（restore相当の空状態起動） |
| DI-AC-05 | reconcile純粋関数のtable-driven unit test（exportId不一致 / session不在 / TTL / 破棄mark / 破損 / 未知schema → 無効化・清掃）+ **process death oracle**（新session保存直後のprocess死遷移をfake clock/storeで再現 → 次読取で清掃・表示なし）+ **write failure注入oracle**（AtomicFile失敗注入でpending無効化が失敗した状態 → 次読取で清掃）+ 起動時reconcileのunit test |
| DI-AC-06 | spec 328 spec差分review（上記5項目の存在とAC-3維持）+ owner受入記録（Issue #374コメント） |
| DI-AC-07 | 既存 `ExchangeFlowStateHolderTest`（AC-3/AC-5対応oracle一式）がgreenであること + durable保存追加後の新規regression（settle内の保存がanchor/single-flightに影響しない） |
| DI-AC-08 | holder/instrumentation test（再開面の破棄 → record削除・status card行消滅・session生存・再取り込み成立） |
| DI-AC-09 | Compose semantics assertion（読み順「状態→残期限→操作」）+ Switch Access/keyboard traversal + 200% font scale test + emulator screenshot（light/dark × ja/default） |
| DI-AC-10 | record modelの型/contract test（非対象fieldの不在。kotlinx serialization modelへのfield不在assertion）+ `noBackupFilesDir` 配置の機械確認 + summary導出contract testの回帰実行 |
| DI-AC-11 | spec 205 spec差分review + 既存 `ExchangeFlowControllerTest` / `ExchangeImportPipelineTest` / 失敗表示・freeze対応testのregression実行 |
| DI-AC-12 | strings走査（ja/en name集合・placeholder一致。spec 123 AC-5方式）+ hardcoded literal grep |

共通gate: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest
--tests 'app.lawnchair.organizer.*'`、organizer instrumentation lane、CI `final-status` green。
本Issueは新規durable store追加でありpersistent state変更を含むが、layout DB・recovery DBには
触れないため `risk: layout-data` は付けない（`risk: privacy` を付ける。高リスクPR要件の適用は
実装PRのlabel決定時に判定する）。

## Contract notes（owner reviewで確認すべき解釈）

1. **durable保存の失敗時の挙動**: Issue本文はimport成功時の保存をstore契約として定めるが、
   保存失敗時の成功状態の扱いは規定していない。本specは **成功状態をprocess-local後退で継続**
   （同一process内のCTA・破棄は現行どおり機能し、durable保存に失敗した提案はstatus cardに
   表示されない。typed案内を表示）を提案する。fail-closedにimport自体を失敗させる案
   （validation通過後にtyped失敗へ変換）は、既存失敗経路の無変更（#373回帰）・同一process内の
   実用性の点で不採用とした。owner reviewで別案が選ばれた場合は本scenarioとDI-AC-01/07を修正する。
2. **再開面の継続CTAの不在**: Issue本文の「cold processで開ける範囲は内容・残時間・破棄の
   表示まで」とdisposition §8の責務分割（「この提案で続ける」の有効化は#375）から、本Issueの
   再開面は継続CTAを提示しない（disabled表示・placeholderも見せない。#366 specの
   capability先取り禁止規約と同一の原理）。#375実装までの暫定面として、再取り込みの案内
   （依頼が有効な場合）を表示するかは実装PRで確定する（非blocking）。
3. **durable recordへの `rationale` / `confidence` 非永続**: Issue本文は「intent内容はinternal
   表現（`CompletedPersonalIntent` 相当）」とするが、`CompletedPersonalIntent` は表示・privacyの
   対象外である `rationale`（AI自由文）と `confidence`（AI自己申告）を含む。本specは
   **canonical decisions + planner-effective `minimizeMovement` + identity anchor（`exportId`）+
   失効時刻 + entry種別のみを永続し、`rationale`/`confidence` は永続しない**（untrusted自由文の
   durable化を避ける。summary導出と#375までの契約に不要）。#375以降でrebindに追加fieldが必要に
   なった場合はspec改訂で拡張する。entry種別の永続は#375のrebind判定（idle由来 = fresh run、
   run-in由来 = 選択復元の対象）への最小限の備えであり、rebind契約自体は先取りしない。
4. **進行中AI依頼のstatus card行の所有**: TO-BE D-02はstatus cardに「進行中のAI依頼（24h残時間）」
   も要求するが、Issue #374本文のscopeは取り込み済み提案行のみを列挙し、#372 spec draftは
   「status cardへの依頼表示は#374」と記している。本specは提案行のみを所有する（Open questions 1）。
   owner reviewで進行中依頼行を本Issueへ含める判断がされた場合はscope・ACへ追加する。
5. **置換確認dialog文言の改訂主体**: spec 205 AC-13の確認dialog文言の拡張は本Issueが所有する
   （disposition §7.1「#374で契約化」）。#372のT-15再構成は確認dialogをT-15面内へ移設するが、
   文言契約（破棄対象に提案を含む旨）は本specが正本であり、#372の移設後も継承される。

## Open questions

実装開始前に解消が必須な問いはない（Contract notes 1〜5の解釈確認をowner reviewが所有し、
確認前は本specは `draft` のままである）。非blocking事項:

1. **進行中AI依頼のstatus card行の所有**（Contract notes 4）: 本Issueのscope（提案行のみ）を
   維持するか、D-02の依頼行を本Issueへ含めるか。含める場合は#372のT-15事前表示との
   語彙・導出方式の共有契約を本specへ追加する。
2. **status card提案行の視覚表現**（行 vs card内section）と、残時間表示の更新頻度（面表示中の
   経時更新の要否）は、spec 271/#366のstatus card実装規約の範囲で実装PRのreviewで確定する
   （TO-BEは視覚designを扱わない）。
3. **reconcileの起動時適用の接続点**（既存startup reconciliation triggerへの接続 vs
   projection初回読取への集約）はplan.mdの提案を基準に実装PRで確定する（どちらも本specの
   契約「起動時と読取時に適用」を満たす。plan.md「Migration and recovery」参照）。
4. ja訳の最終文言は実装PRのstring diffで確定する（spec 123と同一の非blocking扱い）。

## Change history

- 2026-09-19: Draft created for #374（spec/plan整備task）。accepted TO-BE契約
  （organizer-to-be-ux.md @ main `a2b6aba318`、PR #364: D-02/D-08/D-17/§5.3/§6.5/§8.2/§13-2/§13-5）、
  accepted処分文書（organizer-disposition-migration.md @ main `a2b6aba318`、PR #378:
  §3.12/§3.14/§4.1/§5 更新順序 #8/§7.1/§7.3/§8）、現行実装調査
  （`ExchangeFlowUi.kt` の `ExchangeFlowStateHolder`（`pendingValidated` / attempt token /
  `continueImport` / `discardImport`）、`ExchangeFlowController.kt`（session save順序・
  `importReply`）、`AndroidExportSessionStore.kt` / `ExchangeSessionStoreModule.kt`
  （durable store pattern・`noBackupFilesDir`・AtomicFile・schema version）、
  `LayoutApplicationModule.durableOrganizerStatus()`（spec 271 projection様式）、
  `ManualOrganizationPreferences.kt`（holder hosting・durable status読取）、
  `SessionExportReconstructor.kt`（session側正本からのexport再構築）、
  `IntentCompletion.kt`（`CompletedPersonalIntent`）、`exchange_replacement_warning` strings、
  exchange系unit/instrumentation test群）、先行spec draft（#373 `859e51fe`・#366 `bf00f96175`・
  #372 `6ba84fc4`）、Issue #328コメント（Phase 2 implementation merged・disposition適用提案）を
  入力に作成。

## References

- [Issue #374][1]
- [organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)（accepted。D-02/D-08/D-13/D-17、§5.3、§6.5、§8.2、§9、§10、§13-2、§13-5）
- [organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)（accepted。§2.1/§3.12/§3.14/§4.1/§5 更新順序 #8/§7.1/§7.3/§8）
- [Spec 328: import success state](../328-exchange-import-success-state/spec.md)（accepted・Phase 2 implemented。revision 2は本Issueが所有）
- [Spec 205: External Agent Exchange](../205-external-agent-exchange/spec.md)（implemented。export session契約・AC-13置換確認の所有。pending保持規定を本Issueが改訂）
- [Spec 204: AI personalization context/intent exchange contract](../204-ai-personalization-context-intent-contract/spec.md)（accepted・implemented。export session store（TTL 24h・単一active・app-private・backup除外）の所有）
- [Spec 271: organizer durable status projection](../271-organizer-durable-status-projection/spec.md)（implemented。status card projectionの閉域語彙・fail-closed・startup reconciliation様式の正本）
- [Spec 330: partial intent authoring](../330-partial-intent-authoring/spec.md)（implemented。`CompletedPersonalIntent` canonical representationの所有）
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md)（implemented。run-in entry・owning runId。§5 idle entry経路は#374/#375で改訂対象）
- [Spec 332: exchange import input UI](../332-exchange-import-input-ui/spec.md)（implemented。T-17入力面）
- [Spec 123: organizer UI convergence](../123-organizer-ui-convergence/spec.md)（ja/en strings契約）
- [organization-run-ux.md](../../docs/product/organization-run-ux.md)（§6 accessibility受入基準）
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)（record内容出力禁止の正本）
- 先行spec draft: [Issue #373][2]（T-18表示面）、[Issue #366][3]（hub/status card第1段階）、
  Issue #372（T-15/T-16・依頼語彙。branch `issue-372-spec-plan`）
- [CONTEXT.md](../../CONTEXT.md), [DESIGN.md](../../DESIGN.md)（§4.2 projection様式・§7 data ownership）, [AGENTS.md](../../AGENTS.md)

[1]: https://github.com/nunu1733/NunuLauncher/issues/374
[2]: https://github.com/nunu1733/NunuLauncher/issues/373
[3]: https://github.com/nunu1733/NunuLauncher/issues/366
[4]: https://github.com/nunu1733/NunuLauncher/issues/375
