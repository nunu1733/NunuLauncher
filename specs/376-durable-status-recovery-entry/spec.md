---
issue: "#376"
status: draft
requirements: [FR-004]
risk: [layout-data]
updated: 2026-09-19
---

# hub status cardから復元flowへ接続する（cold process起点、D-15）

> 契約の根拠: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-02, D-15, T-01/T-14, §5.1, §5.3, §6.7, §8.1, §8.2, §9, §10, §13-3, §13-5）および
> accepted disposition [docs/product/organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)
> （§2.3, §3.6, §4.1, §5 順10, §7.2(d), §7.3, §8, §11）。
> 本specは[Issue #376][1]の成果物である。statusが `draft` の間はimplementation-readyではない。

## Problem

Organizerの復元（recovery）は、現在は **同一process内で検証済み適用を完了した直後** にしか到達できない。
`ManualOrganizationRun.beginRecoveryPreview()` はprocess-localな `appliedPoint` /
`lastVerifiedApply` を要求し（[ManualOrganizationRun.kt][2] の recovery preview開始節、
spec 230 D2の前提「restart 後に restore 確認へ到達することはない」）、spec 271のdurable status
（`ORGANIZED_RESTORABLE`）は **表示のみ** で操作を持たない（[OrganizerDurableStatus.kt][3]、
[ManualOrganizationPreferences.kt][4] の `durableStatusItems` — `ORGANIZED_RESTORABLE` は
`SummaryText` のみ）。その結果、AS-IS監査のF-10（再発見断絶）と監査D-6が残る: process死後に
durableな復元可能pointが存在しても、ユーザーはstatus cardで「復元できる」ことを見るだけで、
そこから復元できない。TO-BE D-15はこの追従gapの解消を決定した（spec 271がNon-goalsとした
cold-process restore follow-upの実現。disposition §4.1のsupersession行「spec 271 Non-goals
cold-process restore・表示のみ契約 → status card復元導線（D-15。新spec）」の所有）。

## Outcome

hub status card（T-01）のdurable status行 `ORGANIZED_RESTORABLE` が **復元CTAと残時間表示を
持つ**。CTAの選択で、アプリケーションmoduleが **自動選択した最新の検証済み1点** に対して
既存のrecovery inspection（spec 84 seam）→ 復元確認（T-14。spec 84/230契約どおりの閉域語彙・
適用履歴行・revision条件）→ 復元実行（spec 13 protocol、無変更）が完結する。**cold process起点
を許可** し、Launcherを開かずに一連のflowが完了する。確認token（one-shot
`RecoveryPreviewConfirmation`）は検査を実行したそのprocess内でfreshに発行され、永続化されず、
二重実行できない。期限切れ後は現行どおり「restored or expired」の表示のみであり、CTAは出現しない。

選択規則・lease・token・durable status閉域語彙の扱い（disposition §11が本specへ所有を委ねた
未決定事項）は本specのDesign decisions D1–D6で確定する。

## Scope

- **durable statusへの操作の付与**: `ORGANIZED_RESTORABLE` 行に復元CTAを付け、CTAから
  coordinatorの復元flow（検査→確認→実行）へ接続する。行には選択されたpointの **残時間
  （粗粒度・時間単位）** を表示する（D-02「復元できる提案あり（残時間）」、TO-BE §8.2）。
- **最新検証済み1点の選択seam**: アプリケーションmodule内で、recovery storeの検査snapshot
  （#89。`pointId` / `lifecycle` / `createdAtMs` / `checksumValid` / `formatVersion` を含む
  既存のbounded projection）から、retention内の `VERIFIED` かつchecksum有効なrecordのうち
  **`createdAtMs` 最大の1件** を選択するapplication-ownedな読み取りを新設する。UIは
  pointIdを選択・提出しない（選択UIは新設しない）。
- **cold processからの復元flow**: coordinator（`ManualOrganizationRun`）に、process-localな
  `appliedPoint` / `lastVerifiedApply` を要求しない復元開始entryを追加する。検査・確認・実行は
  既存の `State.InspectingRecovery` → `State.RecoveryPreview` → `State.Recovering` →
  `State.RecoveryResultState` を再利用する。`ManualOrganizationModule.get()` の既存の
  cold-process初期化経路（spec 271 DS-AC-10の `LauncherAppState` 構成保証 + 共有のidempotentな
  startup reconciliation trigger）を前提に、Launcherを開かずにflowが完結する。
- **spec 271の改訂**: Non-goalsのcold-process restore項を本specへの移行として更新し、
  DS-AC-07の表示面をstatus card（hub）へ更新する（「spec 271 companion revision」節）。
  実装PRで実施し、owner受入を受ける（Issue受入条件）。
- **Localization / a11y**: 新規user-visible文字列はEN（`values/`）とja（`values-ja/`）の両方を
  供給する。status card行のTalkBack読み順はTO-BE §13-5の「状態→残期限→操作」順に固定する。

## Non-goals

- **復元point一覧UI・複数点選択**: 選択は常に自動（最新検証済み1点）。一覧・選択面は
  新設しない（TO-BE §8.2で非実施、Issue Non-goals）。
- **recovery protocol本体の変更**: spec 13 / ADR-0003の契約（revision-bound、transaction、
  lifecycle、retention 24h・最大3点、tombstone、reconciliation）は無変更。spec 84の
  `inspectRecovery` / `RecoveryPreviewResult` / `RecoveryPreviewConfirmation` /
  `confirmRecoveryPreview` の公開契約も無変更（disposition §3.6「Continue（seam契約不変）」）。
- **durableな結果projectionの新設**: 最近のrun結果・復元結果のdurable履歴は作らない
  （TO-BE §8.2「durableな結果projectionの新設は行わない」）。
- **spec 271の `OrganizerDurableStatus` 型の変更**: 閉域語彙enumはfield-freeのまま拡張しない
  （D2、DS-AC-06の型shape契約の維持）。
- **期限切れ後の新表示**: 「restored or expired」行・`NotRestorable` 系typed文言は現行どおり
  （Issue「期限切れ後は『復元の期限切れ』表示のみ（現行どおり）」）。
- **`State.Applied` からの既存復元entryの変更**: 成功結果面の復元CTAとそのconfirm可否は
  現行どおり（spec 230/52契約の維持）。
- diagnostics field、permission、外部送信、persistent store、backup/restore許可listの変更。
- 進行中AI依頼・取り込み済み提案のstatus card行（D-08、#374/#375が所有）。
- run面の表示統合・T-07〜T-13の再配置（#369が所有）。本specの確認flowは既存の確認面に
  到達するだけであり、面の統合・移設を行わない。

## Domain language

- **復元（restore / recovery）**: recovery pointへの復旧（TO-BE §10。「アンドゥ」「バックアップ」
  は避ける語）。既存の `CONTEXT.md` の **recovery point** 定義を変更しない。
- **最新の検証済み1点**: retention内（`createdAt + 24h > now`）の `VERIFIED` かつchecksum有効な
  recordのうち `createdAtMs` が最大の1件。同時刻のtie-breakは決定的である（D1）。
- **復元entry read**: 選択と残時間を返す新設のapplication-owned読み取り（D1/D3）。UI-facingな
  値は閉じ、`pointId` はspec 84と同じ「既存のopaqueなrecovery相関キー」としてのみcoordinatorが
  保持する（描画・log・永続化はしない）。

## Design decisions

### D1: 選択はアプリケーションmodule内部で行う — **UIにpointIdを選択させない**

`RecoveryInspectionSnapshot.Record` は既に `pointId` / `lifecycle` / `createdAtMs` / `updatedAtMs` /
`checksumValid` / `formatVersion` を含み（[RecoveryInspectionSnapshot.kt][5]）、
`durableOrganizerStatus()` は同一snapshotを `ordinaryMutex` の非block取得下で読んでいる
（[LayoutApplicationModule.kt][6] の durable status節）。本specはこの構成を踏襲し、
**module内部で選択を完結させる新読み取り** を1つ追加する（仮称
`readRestorableRecoveryEntry()`。最終名は実装PRで確定）:

```text
readRestorableRecoveryEntry() -> RestorableRecoveryEntry?   // null = 該当なし / fail-closed

RestorableRecoveryEntry {
    pointId: RecoveryPointId        // opaque handle（描画・log・永続化しない）
    remainingWindow: RemainingWindow // 粗粒度の残時間（D3）
}

RemainingWindow = HoursRemaining(value: Int)   // 1..24（切捨て）
                | LessThanOneHour
```

- 選択基準は `OrganizerDurableStatusDeriver` のrestorable判定と同一の前提
  （`VERIFIED` + `checksumValid` + retention内）に加え、`inspectRecovery` のpreflight
  （spec 84 I2–I3。format version、lifecycle、retentionを再検証）で受理されることである。
  選択readはhintにすぎず、**検査が権威的なgate** である（TOCTOUは既存のtyped結果で扱う）。
- 読み取りは `durableOrganizerStatus()` と同じgate契約に従う: readiness gate未ready、
  snapshotunreadable、run mutex競合、読み取り失敗は **fail-closed（null）** であり、
  書込み・lifecycle遷移・retention cleanup・journal eventは発生しない（spec 271/89の
  読み取り契約と同一様式）。
- tie-break: `createdAtMs` 最大のrecordが複数ある場合の選択は決定的とする
  （例: `pointId` の辞書順最大）。実装detailでありunit testで固定する。

**採用理由**: spec 84がpointIdを「既存のopaqueなrecovery相関キー」としてcoordinator層へ出すこと
は既に許しており、coordinatorは `beginRecoveryPreview()` で既に `appliedPoint` を保持している。
UI層に選択をさせると検査snapshotの内容（どのpointがあるか）がUIの判断に露出するため、
選択規則（D-15）をmodule内部に隠すのが最も浅い境界である。

**却下した代替**: (a) `OrganizerDurableStatus` にhandle/windowを載せる → spec 271のfield-free
型shape契約（DS-AC-06）の改訂が必要になり、表示projectionと操作hintの責務が混在する。
(b) UIがsnapshotからpointIdを取得する → 検査snapshotをUIの判断に露出させ、spec 84/271の
境界に反する。

### D2: 閉域語彙（`OrganizerDurableStatus`）は拡張しない — **残時間は別の閉じた読み取り**

disposition §11が「durable status閉域語彙の拡張要否」を本specへ委ねた。判定: **enumは拡張しない**。
理由: (1) spec 271 DS-AC-06は「projection typeはpayload/identifierを運ばない」ことを型shapeで
強制しており、残時間（時刻導出値）を載せることはこの強制を弱める。(2) 残時間が必要なのは
`ORGANIZED_RESTORABLE` 行のみであり、status語彙（5値）の意味はD-15で変化しない。
(3) D1の別読み取りは既存のstatus読み取りと並列にfail-closedするため、語彙と操作hintの
不整合（statusはrestorableだがentryなし、等）は **CTA非表示** という安全側に倒れる。

### D3: 残時間は粗粒度（時間単位・切捨て・1..24）であり、再読込時に更新する — **live countdownを作らない**

表示は「選択されたpointのretention deadline（`createdAt + 24h`）までの残り」を **時間単位で
切捨て** た値（1..24。1時間未満は `LessThanOneHour`）とする。分・秒・絶対時刻は表示しない。
更新timingは既存のdurable status再読込契約と同一である（surfaceが `Idle`/`Cancelled` に遷移した
時点、readiness gateのterminal遷移時点。spec 271 DS-AC-09の再読込規約）。秒単位のtimerや
自動再読込は契約に含めない。表示中に真の残時間が尽きた場合の正は **検査のtyped結果**
（`NotRestorable(EXPIRED)` → 既存のnot-available文言）であり、行表示の即時差し替えを
要求しない。文言の最終copyは実装PRでEN/jaを同期して確定する（#366と同一の非blocking扱い）。

### D4: token発行とleaseは既存機構の再利用である — **「fresh process発行」は構造的に成立する**

one-shot `RecoveryPreviewConfirmation` は検査（I5）時に `issuePreviewConfirmation` が
process-localなprivate registryへbindして発行される（[LayoutApplicationModule.kt][6] の
`pendingPreviewConfirmations`）。random token・非永続・非serialization・1回消費の契約は
spec 84どおり無変更である。したがって:

- cold processで検査を実行すれば、そのprocess内で **freshなtokenが発行される**。
  死んだprocessが発行したtokenとregistry entryはprocess死と同時に消滅し、復活しない。
  process死後に旧tokenでconfirmを試みても `consumePreviewConfirmation` はnullを返し、
  `NotRestorable(MISSING)` として二重実行は不能である。
- tokenの **永続化・復元・再接続は新設しない**。preview表示中のprocess死後の再開は、
  status cardのCTAからの **再検査**（新token）のみである。
- RECOVERY lease（coordinatorの `OrganizationOperationLease.tryAcquire(RECOVERY)`。
  RUN/AUTHORINGと単一flightのprocess-local排他）とwriter lease
  （`LayoutWriteCoordinator` 経由の `LayoutWriterPort.tryAcquireLease(WriterKind.ORGANIZER, …)`。
  [LauncherLayoutAdapter.kt][7]）の取得位置・非block性は既存の `beginRecoveryPreview()` /
  spec 84 I4 / spec 13 recovery protocolどおり無変更である。

### D5: coordinatorは既存の復元state列を再利用する — **cold entryは `lastVerifiedApply` を要求しない**

`ManualOrganizationRun` に新設するcold entry（仮称 `beginRecoveryPreviewFromDurableEntry()`）は:

1. `operationGate.tryAcquire(RECOVERY)` を取得する（競合時は既存規約どおり静かに不受理）。
2. `activeOperation != null || recoveryLease != null` のとき不受理とする（既存と同一）。
3. `State.InspectingRecovery` へ遷移し、D1のentry readで得たhandleに対して既存の
   `application.inspectRecovery(pointId)` を呼ぶ（**spec 84の操作をそのまま再利用**。
   新しいapplication操作は選択readのみであり、検査・確認のapplication契約は増やさない）。
4. 結果を既存の `State.RecoveryPreview(result, correlated)` へ載せる。`correlated` は
   spec 230 D2の相関gateに **無変更で** 従う: cold processでは `lastVerifiedApply` がnullである
   ため適用履歴行は表示されず、共通の戻り先文のみで確認が機能する（confirm可否は変更しない）。
   同一processで検証済み適用が済んでいる場合は、最新pointが最後の適用のpointと一致するとき
   履歴行が表示される（既存gateの自然な挙動であり、新規の分岐を設けない）。
5. 確認・取消・実行・結果は既存の `confirmRecovery()` / `cancelRecoveryPreview()` /
   `State.RecoveryResultState` を無変更で使う。cold entryは `appliedPoint` /
   `lastVerifiedApply` を **設定も消去もしない**。

`State.Applied` からの既存entry（`beginRecoveryPreview()`）は現行どおり残り、spec 230の
AC-2(e)相当のoracle（restart後は旧entryへ到達しない）は舊pathについて引き続き成立する
（「Relationship to existing specs」節のspec 230項を参照）。

### D6: CTAの出現と受理 — **run稼働中は不出現・不受理、fail-closedはCTAなし**

- **出現条件**: coordinatorが `Idle`/`Cancelled`（既存の `showDurableStatus` 条件）かつ
  durable statusが `ORGANIZED_RESTORABLE` かつ D1のentry readが成功（entry != null）。
  いずれかが満たされないとき、行は現行の表示のみに後退し、CTA・残時間を出現させない
  （「発明した操作」を作らない。spec 271のfail-closed行なし規約と同一の姿勢）。
- **不受理**: 出現後でも、tap時にRECOVERY leaseが取れない（run/authoring/別復元がactive）、
  またはinspectionが `WriterBusy` / `Concurrent` / `Unavailable` を返すとき、既存の不受理・
  typed文言規約に従う（TO-BE §9「CTA処理中の不受理規則も現行契約を維持する」）。
  CTA tap自体の競合不受理は既存の `beginRecoveryPreview()` と同じ **静かな不受理** である。
- **配置**: CTAは `ORGANIZED_RESTORABLE` 行の提示箇所すべてに付く。契約上のentry面は
  hub status card（T-01、D-15）であり、過渡期には既存organizer面（[ManualOrganizationPreferences.kt][4]）
  の同一行も同一描画規約を共有する。#366の実装がstatus card描画をhub側に新規に書く場合は
  同一のseam・resource・条件式を用い、両面で挙動が一致することをinstrumentationで担保する。
- **復元完了後**: 確認面から戻るとcoordinatorは `Idle` に戻り、status cardは既存の再読込契約
  （DS-AC-09）でdurable statusを再deriveする。復元成功後の選択pointは `RESTORED` となり、
  行は「restored or expired」表示へ変わる。他のretention内 `VERIFIED` pointが残る場合、
  行は再びrestorableとなり、CTAは **その時点の最新の検証済み1点** に対して再度機能する
  （D1の選択は毎回の読み取りで行う。選択UIは存在しない）。

## Behavior scenarios

### Scenario: cold processでstatus cardから復元が完結する

Given recovery storeにretention内の `VERIFIED` recordがあり、processは再起動済み（startup
reconciliation完了、run coordinatorは `Idle`）、Launcherは一度も開かれていない
When ユーザーがhubを開き、status cardのrestorable行（残時間付き）の復元CTAを選択し、
確認面で確認する
Then 検査→確認（共通の戻り先文のみ。適用履歴行はない）→復元が完了し、
復元後のlayoutはrecovery protocolの検証を経たpre-stateと一致する
And 確認tokenはこのprocessで発行されたfreshなone-shot tokenである
And 復元完了後にstatus cardへ戻ると、durable statusは「restored or expired」を表示する
And 一連のflowでLauncherは開かれない（DS-AC-10と同型のcold-process evidenceで検証する）

### Scenario: 選択は常に最新の検証済み1点である

Given retention内の `VERIFIED` pointが複数（最大3点の現行契約内）存在する
When status cardのCTAから復元を開始する
Then 検査・確認・復元の対象は `createdAtMs` が最大の1点であり、それ以外のpointは
lifecycle・payloadともに変更されない
And 複数の候補があることを示す一覧・選択UIは存在しない
And 復元完了後にretention内の別の `VERIFIED` pointが残っている場合、行は再びrestorableを示し、
次のCTAでは残存pointのうち最新の1点が対象になる

### Scenario: 適用履歴行は相関gateに従う（cold processでは出ない）

Given cold processでstatus cardからの復元確認面が表示されている
When 確認面を観察する
Then 共通の戻り先文とconfirm/cancelのdecision pairが表示されるが、適用履歴行は表示されない
（spec 230 D2: `lastVerifiedApply` が保持されていないときはいかなるapplyの `Summary` も
表示しない）
And 同一processで検証済み適用が済んだ後にstatus cardから同じentryを使った場合、
previewの `pointId` と `lastVerifiedApply` のpointIdが等価なときに限り履歴行が表示される
（既存gateの再利用であり、新規の表示分岐を作らない）

### Scenario: 表示中の期限切れ（TOCTOU）は既存のtyped文言で扱う

Given status cardにrestorable行とCTAが表示されている間に、選択pointのretentionが尽きた、
または別の要因でpointが復元不可能になった（自動recoveryでの復元を含む）
When ユーザーがCTAを選択する
Then 検査は既存のtyped結果（例: `NotRestorable(EXPIRED)` / `ALREADY_RESTORED`）を返し、
確認面は既存のnot-available文言を表示する
And recovery store・layoutへの書込みは発生せず、cancelで `Idle` に戻る
And status cardへ戻ると、行は期限切れ後の表示（restored or expired等、現行語彙）のみである

### Scenario: run稼働中はCTAが出現せず、競合時は不受理である

Given 同一process内でrun coordinatorが進行中（`Idle`/`Cancelled` 以外）である
When hubのstatus cardを観察する
Then durable status行・残時間・CTAは表示されない（既存の進行中優先規約）
And 行が表示されている状態から別のoperationが先にadmissionを取った場合、CTAのtapは
既存の不受理規約どおり静かに無視され、状態・書込みは変化しない
And 検査が `WriterBusy` / `Concurrent` を返す場合は既存のtyped文言を表示する

### Scenario: one-shot tokenはfresh processで発行され、二重実行できない

Given status cardからの検査で `Restorable` とtokenが発行された
When (a) 同一tokenで2回目のconfirmを試みる、または (b) preview表示中にprocessが死んだ後に
旧tokenでconfirmを試みる
Then いずれも復元は実行されない（(a) は既存のone-shot消費契約、(b) はregistryごと消滅）。
typedな非復元結果として現行契約どおり扱われる
And (b) の後はstatus cardのCTAから再検査を行えば新しいfresh tokenで復元を再開できる

### Scenario: 確認時のrevision不一致は既存契約どおりzero-writeで拒否される

Given `Restorable` の確認面が表示されている間にlayoutが変化した
When ユーザーが確認する
Then 既存のrecovery preflight/transaction再検証が `NotRestorable(STALE_REVISION)` を返し、
layout・recovery storeへの書込みは発生しない（spec 13/84契約の無変更）

### Scenario: 期限切れ・復元済み・unresolved・never organized の行は現行どおり

Given durable statusが `RESTORED_OR_EXPIRED` / `UNRESOLVED` / `NEVER_ORGANIZED` /
`UNAVAILABLE` のいずれかである
When status cardを観察する
Then 各行の表示は現行契約どおりであり（unresolvedのsafe-support導線を含む）、
復元CTA・残時間は表示されない
And fail-closed（`UNAVAILABLE`、entry read失敗）では発明した状態や操作を表示しない

### Scenario: 検査・選択readは書込みを行わない

Given status cardの表示・CTA tap・検査のいずれかが実行される
When 読み取り系の処理（durable status、entry read、inspection）が走る
Then recovery DB / snapshot / layoutへの書込み・lifecycle遷移・retention cleanup・
journal eventは発生しない（spec 271/84の読み取り契約と同一。検査は既存のI0–I5どおり）

## Data and state

- **読む**: 既存の検査snapshot（#89 published snapshot。recordは `pointId` / `lifecycle` /
  `createdAtMs` / `updatedAtMs` / `checksumValid` / `formatVersion` のbounded metadataのみ）、
  `Clock`、既存の `RetentionPolicy`（spec 13の24h定数）、readiness gate状態。
  D1のentry readは `durableOrganizerStatus()` と同一のgate・mutex・fail-closed契約で読む。
- **書く**: 新規の永続化はない。復元実行の書込みは既存のspec 13 recovery protocolだけが所有する
  （本specの新経路は `recoverWithApplicationBehavior` / `RecoveryProtocol.recover` への
  既存の委譲を再利用し、書込み経路を追加しない）。AGENTS.mdのホームレイアウト安全規約
  （revision一致・保持/移動/削除の説明可能性・transaction・recovery point・再検証）は
  既存protocolが引き続き所有し、本specはそれを迂回する経路を新設しない。
- **Identity**: `RecoveryPointId` は既存のopaque相関キーとしてcoordinatorが一時保持するだけ
  である。新識別子・generation追跡・tokenの永続化は導入しない。
- **Migration / backup / restore**: なし。schema変更・新規persisted data・backup許可list変更は
  ない。recovery DBは既存どおりbackup除外（ADR-0003）。PR revertで現行挙動へ戻る
  （entry read・cold entry・CTA描画はadditiveであり、削除時に旧挙動の残骸を残さない）。
- **Downgrade**: 本specの追加はUI/読み取りのみであり、旧版でdurable statusは表示のみに戻る
  （disposition §7.3「復元導線: downgrade 復元は従来どおり適用直後のみ」）。

## Permissions, privacy, and security

- 新しいpermission・外部送信・sensitive dataの扱い追加は **none**。
- `RestorableRecoveryEntry` は閉じた型であり、pointId（opaque handle）と粗粒度残時間以外の
  fieldを持たない。record payload・revision・digest・item identity・絶対時刻・record件数は
  UI-facingな値に現れない。pointIdは描画・log・diagnostics・永続化されない。
- diagnostics: 検査・entry readは無音（spec 84/271どおり）。確認の実行は既存の
  `RECOVERY_REQUESTED` / terminal recovery diagnosticsのみを既存経路で発行し、
  新しいevent種別・fieldは追加しない。
- tokenの取り扱いはspec 84のprivacy契約（非serialization・非log・private registry）を継承する。

## Accessibility and localization

- status card行のTalkBack読み順はTO-BE §13-5の **「状態→残期限→操作」** 順に固定する
  （#366が規約を導入し、本specが初めて残期限要素と操作を同じ行に載せる）。
  CTAは明示的なrole/state（action）として公開し、行全体のstatus textと区別して読める。
- 確認面（T-14相当）のa11yは既存の `FocusTargetText` / `SummaryText` / decision pair /
  liveRegion規約（spec 230/195/209）を無変更で使う。適用履歴行はcold processでは表示されず、
  表示される場合も既存のliveRegion対象外規約に従う。
- 200% font scaleで行・CTAがwrapし、critical action（復元CTA・確認・cancel）へ到達できる。
  状態表示はcolor-onlyにしない。focus restorationは決定的である。
- 新規文字列（残時間表示・CTA label・必要な複合文）はformat resourceで構成し、
  EN（`values/`）とja（`values-ja/`）を同時に供給する（#123契約）。既存status文言の
  resourceは再利用する。TO-BE §10語彙（「復元」。アンドゥ/バックアップ回避）を新規copyで遵守する。

## Compatibility and migration

- additive変更である。既存の復元entry（`State.Applied`）、確認面、recovery protocol、
  durable status表示への後方互換性は保たれ、既存suiteが無編集でgreenであることを要求する
  （spec 230 AC-2(e)相当の旧path oracleを含む。表示文字列の置換を行わない範囲）。
- 依存する面: hub status card（#366）。#366 merge前のmainに対して本specを実装する場合、
  CTAは既存organizer面の同一行に付き、hub status cardの実装（同一seam・resource・条件式）が
  行にCTAを引き継ぐ。#369のrun面統合で確認面のhostが変わっても、本specの契約は
  「CTA→検査→確認→復元の完結性」について不変である。
- process death: 復元確認中のprocess死は既存のrestart reconciliation（spec 13）と
  token消滅（D4）で扱う。durable statusは次回読み取りで再deriveされる。

## Relationship to existing specs

- **spec 84（recovery preview seam）**: 無変更・Continue（disposition §3.6）。
  本specは `inspectRecovery` と `confirmRecoveryPreview` の **呼び出し側を増やすだけで**、
  公開result型・one-shot token契約・I0–I5手順を変更しない。新しいapplication操作は
  選択read（D1）のみである。
- **spec 13 / ADR-0003（recovery protocol・storage）**: 無変更。24h・最大3点・tombstone・
  revision-bound・transaction・restart reconciliationの契約は現行どおり（Issue受入2）。
- **spec 230（restore確認対象の説明）**: 相関gate（D2）と表示契約は **無変更で再利用** する。
  spec 230のD2に「restart 後に restore 確認へ到達することはない」という当時の到達性の記述があるが、
  これはAS-ISの唯一のentry（`State.Applied`）を述べたものであり、D-15（accepted TO-BE）が
  status cardからの第2entryを認める。normativeなAC・display契約の変更は不要であり、
  実装PRは旧到達性記述がD-15によりsupersedeされた旨を記録する
  （disposition §1「旧oracleがなぜobsoleteかを記録」の規約に沿う。AC-2(e)相当の旧path
  unit testは舊entryについて引き続きgreenであり、削除ではなく注記で扱う）。
- **spec 271（durable status projection）**: 本Issueが改訂を所有する（次節）。
- **spec 52（manual run縦切り）**: 成功結果面の復元entryは無変更。本specはT-14相当の
  既存確認面へ接続するだけで、run state machine・表示統合に触れない。
- **spec 123（UI収束）**: 新規row/CTAは既存component・tokenで実装し、inventory evidence文書
  への追記を実装PRで行う（#366と同一の扱い）。

### spec 271 companion revision（実装PRで実施）

本specの実装PRは、[spec 271](../271-organizer-durable-status-projection/spec.md) に対して
次の **最小限の改訂** を含める（disposition §2.3/§4.1/§5 順10の割当）。内容は実装PR時点の
owner reviewに付される:

1. **Non-goals**: 「Enabling the restore preview/confirm flow from a cold process … is a
   separate, future spec (tracked as a follow-up)」の項を、cold-process restore entryの所有が
   本spec（specs/376-durable-status-recovery-entry）へ移行した旨の参照へ置き換える
   （supersession map §4.1行「spec 271 Non-goals cold-process restore・表示のみ契約 →
   status card復元導線（D-15。新spec）」の実行）。
2. **DS-AC-07**: 「The Settings render mapping …」の表示面を、D-02/D-15以降の正位としての
   **status card（hub）** へ更新する。run稼働中の行隠蔽・unresolvedのsafe-support導線・
   fail-closed renderの契約内容は維持する。
3. **Open questions**: follow-up追跡の注記を本specの受入で解決済みとして更新する。
4. **Change history**: 本改訂の記録を1行追加する。
5. `OrganizerDurableStatus` 型・派生規則・DS-AC-01〜DS-AC-06/DS-AC-08〜DS-AC-10の
   契約は変更しない（D2）。

`DESIGN.md` §4.2（module所有のread-only seam列挙）への選択readの追記、および
`CONTEXT.md` への用語追加要否（新ドメイン概念は無し。見込み: 不要）は実装PRで確認・実施する
（spec 271 DS-AC-08と同一の正本更新規約）。

## Dependencies

- **#366（hub、spec draft）**: status cardの恒常的な置き場所。本specのCTA契約上のentry面。
  実装着手は#366のmerge後を原則とする（disposition §8の依存graph:
  `D366 --> D376`）。#366が未mergeの場合、CTAは既存organizer面の同一行に付き、
  hub側は同一seam・resource・条件式で引き継ぐ。
- **#365（正本改訂、docs-only）**: 本specの契約根拠は既にaccepted正本
  （organizer-to-be-ux.md、organizer-disposition-migration.md）に存在するため、執筆は
  blockされない。実装着手は#365のmerge後（正本を先に、AGENTS.md／disposition §5 順1）。
- **#369（run面統合）**: 確認面のhostは変わるが、本specの契約は面の統合に依存しない。
  並行調整のseam衝突（`ManualOrganizationPreferences.kt` のrow構造）に注意する
  （disposition §8: #376は#366後ならc段階と並行可。高リスクpathのため単独のaudit可能な
  小PRを推奨）。
- **前提（implemented/accepted）**: spec 84（implemented）、spec 13（accepted）、
  spec 230（implemented）、spec 271（implemented）、spec 89（implemented）、
  spec 265 / 270（implemented。post-apply reconciliation・typed capture failure）。

## Acceptance criteria

- [ ] **RS-AC-01**: cold process（再訪問時）にhub status cardから復元検査→確認→復元が完結し、
      一連のflowでLauncherが開かれない。（Issue受入1）
- [ ] **RS-AC-02**: 復元対象は常に最新の検証済み1点（retention内の `VERIFIED` +
      checksum有効、`createdAtMs` 最大、決定的tie-break）であり、選択UIは存在しない。
      24h / 最大3点 / tombstone契約（spec 13 / ADR-0003）に変更がない。（Issue受入2）
- [ ] **RS-AC-03**: one-shot確認tokenは検査を実行したprocess内でfreshに発行され、永続化されず、
      二重実行・死後process横断の確認ができない。preview表示中のprocess死後は再検査のみが
      再開手段である。（Issue受入3）
- [ ] **RS-AC-04**: writer lease（`LayoutWriteCoordinator` 経由のORGANIZER kind）・RECOVERY
      lease（`OrganizationOperationLease`）・run admissionの排他が維持され、run稼働中はCTAが
      出現せず、競合時のtapは不受理（静かな不受理）である。（Issue受入4）
- [ ] **RS-AC-05**: 期限切れ・`NotRestorable`・`Unavailable`・`WriterBusy`/`Concurrent` の表示が
      現行のtyped文言契約どおりであり、cancelはzero-write、fail-closedではCTAを出現させない。
      期限切れ後の行は表示のみである。（Issue受入5）
- [ ] **RS-AC-06**: 残時間表示は粗粒度（時間単位・切捨て・1..24 + 1時間未満の区分）であり、
      絶対時刻・identifier・record件数を運ばず、再読込時に更新する（live countdownなし）。
      TalkBack読み順は「状態→残期限→操作」である。（D3 / TO-BE §13-5）
- [ ] **RS-AC-07**: spec 271 companion revision（Non-goals・DS-AC-07・Change history）が
      実装PRに含まれ、owner受入済みである。spec 84/13/230/52の公開契約に変更がなく、
      既存suite（旧entryのrestart oracleを含む）が無編集でgreenである。（Issue受入6）
- [ ] **RS-AC-08**: diagnostics契約の変更がなく、検査・選択readはjournal eventを発行せず、
      確認実行は既存のrecovery diagnosticsのみを既存経路で発行する。新経路からの
      書込み・lifecycle遷移・retention cleanupは発生しない。
- [ ] **RS-AC-09**: すべての新規user-visible文字列がAndroid resource由来でEN/ja両方に存在し、
      複合文はformat resourceで構成される。a11y（role/state、focus、200% reflow、
      non-color-only、traversal）がorganization-run-ux §6基準を満たす。
- [ ] **RS-AC-10**: 実装PRが高リスクPR要件を満たす: 検証対象commit上でCIのmerge gate
      （`CI / final-status`、source job含む）が成功し、
      `docs/assessment/pr-<PR番号>-<slug>.md` の独立audit記録が存在し、
      `High-risk gate / high-risk-evidence` が成功している。（AGENTS.md高リスク契約、
      `risk: layout-data` label）

## Test oracle

| AC | Evidence |
|---|---|
| RS-AC-01 | Cold-process emulator evidence: force-stop → hubを最初の面として起動 → Launcherを開かずにstatus cardのCTA→検査→確認→復元を完了 → 復元後の行が「restored or expired」へ変わる。PR/auditへ記録（spec 271 DS-AC-10と同型） |
| RS-AC-02 | Unit test（純粋selector）: 単一点 / 複数点（最新選択） / retention境界（±1 ms at `createdAt + 24h`） / checksum無効・非VERIFIEDの除外 / 同 `createdAtMs` の決定的tie-break / 空のときのclosed null。既存 `OrganizerDurableStatusDeriverTest` と同一のfixture方式 |
| RS-AC-03 | Unit test: tokenのone-shot消費（2回目は不成立）、registryのprocess-local性（新coordinator instanceで発行tokenを消費できないこと = process死のsurrogate）、confirm連鎖での `RecoveryRequest` 非漏出（既存 `RecoveryPreviewContractTest` の継続成功） |
| RS-AC-04 | Unit test（coordinator）: run active中のcold entry不受理・RECOVERY lease単一flight・`State` 遷移（Inspecting→Preview(correlated=null)→Recovering→ResultState、cancel時のIdle復帰）。Instrumentation: run進行中のstatus cardにCTAが出ない否定的観測 |
| RS-AC-05 | Unit test: TOCTOU expiry / stale / writer busy / unavailable のtyped結果伝播とzero-write counter。Instrumentation: not-available文言・cancel zero-write（既存 `cancellingRecoveryPreviewRestoresVerifiedApplySummaryAndActionSurface` の継続成功 + cold entry版の追加） |
| RS-AC-06 | Unit test: `RemainingWindow` の境界（切捨て・1..24 clamp・1時間未満区分）。Compose semantics test: 読み順「状態→残期限→操作」、CTAのrole/state。200% font scaleのevidence |
| RS-AC-07 | spec 271 spec diff（Non-goals・DS-AC-07・Change history）+ owner受入記録。public-shape契約test（spec 84 RP-AC-01系）の継続成功 + 旧entry restart oracle（`ManualOrganizationRunTest` のrestart case）の無編集green |
| RS-AC-08 | Fake counterによるno-write/no-event assert（既存 `RecoveryPreviewProtocolTest` / `RecoveryPreviewContractTest` の方式をentry readへ拡張）+ diagnostics契約testの無変更green |
| RS-AC-09 | 新規stringの `values/` / `values-ja/` のname集合・placeholder一致の機械確認 + hardcoded literal grep（#366 HUB-AC-08と同一方式） |
| RS-AC-10 | `./gradlew spotlessCheck`、organizer unit gate、organizer instrumentation lane、`ManualOrganizationProductionE2EInstrumentationTest` のrecovery区間継続green、CI `final-status` + `high-risk-evidence` 成功、`docs/assessment/pr-<n>-<slug>.md`（Head SHA・spec受入条件の要件ID参照・test表面・CI run link） |

## Open questions

実装着手前に解消が必須な問いはない。Design decisions D1–D6が、disposition §11が本Issueへ
委ねた未決定事項（選択規則・lease・tokenのfresh process発行・閉域語彙の拡張要否）をすべて
確定した。非blocking事項:

1. 新操作・型の最終名（`readRestorableRecoveryEntry` / `RestorableRecoveryEntry` /
   `RemainingWindow` は仮称）と、残時間表示の最終copy（EN/ja）は実装PRのreviewで確定する。
2. CTA tap後の面遷移の実装形態（確認面へのnavigation timing、#366/#369の実装との整合）は
   既存preference navigation規約の範囲で実装PRのreviewで確定する。契約は「確認flowの完結性」
   のみを要求する。
3. status card行copyのTO-BE §10語彙への追加整合（既存status文言の言い回し変更を行うか）は
   非blockingのstring判断であり、行う場合はEN/ja同期とspec 271表示契約の範囲内で行う。

## Change history

- 2026-09-19: Draft created for #376（spec/plan整備task）。accepted TO-BE契約（D-15、
  organizer-to-be-ux.md @ main `a2b6aba318`）、accepted disposition（§2.3/§3.6/§4.1/§5/§7/§8/§11、
  organizer-disposition-migration.md @ main `a2b6aba318`）、既存実装調査
  （`LayoutApplicationModule.kt`、`RecoveryPreviewProtocol.kt`、`OrganizerDurableStatusDeriver.kt`、
  `ManualOrganizationRun.kt`、`ManualOrganizationPreferences.kt`、`OrganizationOperationLease.kt`、
  strings EN/ja）と先行spec drafts（#366 `bf00f96175`、#374 `4b28b455`、未mergeのdraft）を入力に
  作成。disposition §11の未決定事項（選択規則・lease・token・閉域語彙）をD1–D6で確定。

[1]: https://github.com/nunu1733/NunuLauncher/issues/376
[2]: ../../lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt
[3]: ../../lawnchair/src/app/lawnchair/organizer/application/public/OrganizerDurableStatus.kt
[4]: ../../lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt
[5]: ../../lawnchair/src/app/lawnchair/organizer/application/store/RecoveryInspectionSnapshot.kt
[6]: ../../lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt
[7]: ../../lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt
