---
issue: "#376"
status: draft
requirements: [FR-004]
risk: [layout-data]
updated: 2026-09-22
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
hub（[OrganizerHubPreferences.kt][8]）と [ManualOrganizationPreferences.kt][4] の両面で
`ORGANIZED_RESTORABLE` は表示行のみ）。その結果、AS-IS監査のF-10（再発見断絶）と監査D-6が残る:
process死後にdurableな復元可能pointが存在しても、ユーザーはstatus cardで「復元できる」ことを見る
だけで、そこから復元できない。TO-BE D-15はこの追従gapの解消を決定した（spec 271がNon-goalsとし
たcold-process restore follow-upの実現。disposition §4.1のsupersession行「spec 271 Non-goals
cold-process restore・表示のみ契約 → status card復元導線（D-15。新spec）」の所有）。

## Outcome

hub status card（T-01）のdurable status行 `ORGANIZED_RESTORABLE` が **復元CTAと残時間表示を
持つ**。CTAの選択で、アプリケーションmoduleが **自動選択した最新の検証済み1点** に対して
既存のrecovery inspection（spec 84 seam）→ 復元確認（T-14。spec 84/230契約どおりの閉域語彙・
適用履歴行・revision条件）→ 復元実行（spec 13 protocol、無変更）が完結する。**cold process起点
を許可** し、Launcherを開かずに一連のflowが完了する。確認token（one-shot
`RecoveryPreviewConfirmation`）は検査を実行したそのprocess内でfreshに発行され、永続化されず、
二重実行できない。token registryはapplication module（[LayoutApplicationModule.kt][6]）の
instance stateであり、process死とともに消滅する。期限切れ後は現行どおり「restored or expired」
の表示のみであり、CTAは出現しない。

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
- **cancel/backの戻り先契約**: status card起点の復元flowのcancel/back/dismissは、入口である
  hub status card側（coordinator上はpre-entryの表示状態、D5）へ戻す。復元成功後の結果面
  （`State.RecoveryResultState`）から **ユーザーがhubへ戻ることを確定させたとき**
  （system Backに束縛された明示的操作）も、status card起点では同じpre-entry状態へ復帰し、
  status cardのdurable statusが再deriveされる。診断push等の非明示的離脱ではstateは保持される
  （D5/D6）。旧 `State.Applied` 面
  entryの現行挙動（preview cancelで `lastVerifiedApply` へ復帰、結果面離脱後のstate残留）は
  無変更である（D5）。
- **CTAの配置はhub status cardに限定**: `ORGANIZED_RESTORABLE` 行にCTAを付ける面は
  D-15の契約上のentry面であるhub status cardのみとする。settings側run面
  （`ManualOrganizationPreferences`）の同一行は現行どおり表示のみであり、CTAを付けない
  （D6。spec 366 HUB-AC-05の既存導線維持の範囲内）。
- **spec 271とspec 366の改訂**: spec 271のNon-goalsのcold-process restore項を本specへの移行と
  して更新し、DS-AC-07の表示面をstatus card（hub）へ更新する。spec 366（hub、implemented）の
  HUB-AC-03否定的観測のうち復元CTAに係る部分をD-15によりsupersedeする旨を注記する
  （「spec 271 / spec 366 companion revision」節）。実装PRで実施し、owner受入を受ける
  （Issue受入条件）。
- **Localization / a11y**: 新規user-visible文字列はEN（`values/`）とja（`values-ja/`）の両方を
  供給する。status card行のTalkBack読み順はTO-BE §13-5の「状態→残期限→操作」順に固定する
  （spec 366が導入済みの規約に、残期限要素と操作を初めて載せる）。

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
- **`State.Applied` からの既存復元entryの変更**: 成功結果面の復元CTAとそのconfirm可否、
  cancel時の `lastVerifiedApply` 復帰、結果面離脱後のstate挙動は現行どおり
  （spec 230/52契約の維持）。
- **settings側run面の `ORGANIZED_RESTORABLE` 行へのCTA付与**: 同行は現行どおり表示のみ
  で維持する（D6。D-15の操作面はhub status cardに限定）。
- diagnostics field、permission、外部送信、persistent store、backup/restore許可listの変更。
- 進行中AI依頼・取り込み済み提案のstatus card行（D-08、#374/#375が所有）。
- run面の表示統合（#369、実装済み）・確認面の統合・移設。本specの確認flowは既存の確認面に
  到達するだけであり、面の統合・移設を行わない。

## Domain language

- **復元（restore / recovery）**: recovery pointへの復旧（TO-BE §10。「アンドゥ」「バックアップ」
  は避ける語）。既存の `CONTEXT.md` の **recovery point** 定義を変更しない。
- **最新の検証済み1点**: retention内（`createdAt + 24h > now`）の `VERIFIED` かつchecksum有効な
  recordのうち `createdAtMs` が最大の1件。同時刻のtie-breakは決定的である（D1）。
- **復元entry read**: 選択と残時間を返す新設のapplication-owned読み取り（D1/D3）。UI-facingな
  値は閉じ、`pointId` はspec 84と同じ「既存のopaqueなrecovery相関キー」としてのみcoordinatorが
  保持する（描画・log・永続化はしない）。
- **復元entry origin / 戻り先**: coordinatorが復元flowの入口面を識別するprocess-localな区別
  （D5）。旧 `State.Applied` 面 entryとstatus card（hub）entryとでcancel/back/dismissの
  戻り先契約が分かれる。

## Design decisions

### D1: 選択はアプリケーションmodule内部で行う — **UIにpointIdを選択させない**

`RecoveryInspectionSnapshot.Record` は既に `pointId` / `lifecycle` / `createdAtMs` / `updatedAtMs` /
`checksumValid` / `formatVersion` を含み（[RecoveryInspectionSnapshot.kt][5]）、
`durableOrganizerStatus()` は同一snapshotを `ordinaryMutex` の非block取得下で読んでいる
（[LayoutApplicationModule.kt][6] の durable status節、現行main 315–347行）。本specはこの構成を
踏襲し、**module内部で選択を完結させる新読み取り** を1つ追加する（仮称
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
  snapshot unreadable、run mutex競合、読み取り失敗は **fail-closed（null）** であり、
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
(3) D1の別読み取りは既存のstatus読み取りと独立してfail-closedするため、語彙と操作hintの
不整合（statusはrestorableだがentryなし、等）は **CTA非表示** という安全側に倒れる。

### D3: 残時間は粗粒度（時間単位・切捨て・1..24）であり、再読込時に更新する — **live countdownを作らない**

表示は「選択されたpointのretention deadline（`createdAt + 24h`）までの残り」を **時間単位で
切捨て** た値（1..24。1時間未満は `LessThanOneHour`）とする。分・秒・絶対時刻は表示しない。
更新timingは既存のdurable status再読込契約と同一である（surfaceが `Idle`/`Cancelled` に遷移した
時点、readiness gateのterminal遷移時点。spec 271 DS-AC-09の再読込規約）。秒単位のtimerや
自動再読込は契約に含めない。表示中に真の残時間が尽きた場合の正は **検査のtyped結果**
（`NotRestorable(EXPIRED)` → 既存のnot-available文言）であり、行表示の即時差し替えを
要求しない。文言の最終copyは実装PRでEN/jaを同期して確定する（spec 366と同一の非blocking扱い）。

### D4: token発行とleaseは既存機構の再利用である — **「fresh process発行」はregistry所有境界で構造的に成立する**

one-shot `RecoveryPreviewConfirmation` は検査（I5）時に `issuePreviewConfirmation` が
process-localなprivate registryへbindして発行される。**registry
（`pendingPreviewConfirmations`、`IdentityHashMap`）は `LayoutApplicationModule` の
instance fieldである**（[LayoutApplicationModule.kt][6] 現行main 84–85行）。したがって
process境界の構造的な正は **module instance** であり、coordinator（`ManualOrganizationRun`）の
再構築ではregistryは消えない。random token・非永続・非serialization・1回消費の契約は
spec 84どおり無変更である。したがって:

- cold processで検査を実行すれば、そのprocessのmodule instance内で **freshなtokenが発行
  される**。死んだprocessが発行したtokenとregistry entryはprocess死（module instanceの消滅）と
  同時に消滅し、復活しない。process死後に旧tokenでconfirmを試みても
  `consumePreviewConfirmation` はnullを返し、`NotRestorable(MISSING)` として二重実行は不能である。
- **process死の構造的surrogateは「freshな `LayoutApplicationModule`（fresh registry）の構築」**
  であり、coordinatorだけを作り直してもtokenは消費可能なまま残る（unit testはこの所有境界に
  従って構成する。RS-AC-03）。
- tokenの **永続化・復元・再接続は新設しない**。preview表示中のprocess死後の再開は、
  status cardのCTAからの **再検査**（新token）のみである。
- RECOVERY lease（coordinatorの `OrganizationOperationLease.tryAcquire(RECOVERY)`。
  RUN/AUTHORINGと単一flightのprocess-local排他）とwriter lease
  （`LayoutWriteCoordinator` 経由の `LayoutWriterPort.tryAcquireLease(WriterKind.ORGANIZER, …)`。
  [LauncherLayoutAdapter.kt][7]）の取得位置・非block性は既存の `beginRecoveryPreview()` /
  spec 84 I4 / spec 13 recovery protocolどおり無変更である。

### D5: cold entryはentry originを持ち、戻り先をstatus card側に固定する — **cancel/back/dismissは旧 `State.Applied` 面へ復帰しない**

`cancelRecoveryPreview()` とdismiss中のrecovery取消は、現行実装では
`stateHolder.value = lastVerifiedApply ?: State.Idle` という **origin非依存** の戻り先を持つ
（[ManualOrganizationRun.kt][2] 現行main 1254–1262行 / 1296–1310行）。旧 `State.Applied` 面
entryではこれが正であるが、status card起点のflowに無変更で再利用すると、戻り先が入口と無関係な
process state（`lastVerifiedApply`）へ結合する。TO-BEのsurface origin（「durable再入場は
hub status card」「Backは1つ前の面」）に反するため、本specは戻り先をentry originへ束縛する:

- **entry originの導入**: coordinatorは復元flowの入口をprocess-localに区別する
  （旧 `State.Applied` 面 entry / status card entry。仮称 `recoveryEntryOrigin`。
  最終形は実装PRで確定）。旧entryは従来どおり `beginRecoveryPreview()` であり、originは
  `AppliedSurface`。status card entry（仮称 `beginRecoveryPreviewFromDurableEntry()`）は
  originを `HubStatusCard` として記録する。
- **status card entryのadmission**: (1) `operationGate.tryAcquire(RECOVERY)` を取得する
  （競合時は既存規約どおり静かに不受理）。(2) lock下で `activeOperation == null &&
  recoveryLease == null` かつ **表示stateが `Idle` または `Cancelled`**（durable行の可視条件、
  D6出現条件と同一）であることを要求し、満たされなければleaseをcloseして静かに不受理する。
  admission時にentry read（D1）を再度読み、entryがnullなら不受理である。
- **戻り先契約**: status card entryに起因する復元flowのcancel/back/dismissは、
  **pre-entryの表示状態（`Idle`/`Cancelled`）へ復帰** する。`lastVerifiedApply` を読まず、
  いかなる場合も旧 `State.Applied` 面（`State.Applied`）へ復帰しない。旧entryの
  cancel/back/dismissは現行どおり `lastVerifiedApply ?: State.Idle` であり無変更である。
  検査throw時の既存のcancel経路（`beginRecoveryPreview()` 現行main 1222–1224行と同型）も
  status card entryでは同じ戻り先契約に従う。
- **適用履歴行はstatus card entryでは構造的に出ない**: coordinatorの不変条件として、
  `lastVerifiedApply` は適用成功時に設定され（現行main 1188–1191行）、新しいrunの開始で
  消去され（現行main 1406–1407行）、cancelでは消去されない。`lastVerifiedApply != null` の
  あいだ表示stateは `Idle`/`Cancelled` になり得ないため（全state遷移の確認による構造的
  排他）、status card entryのadmissionを通過した時点で `lastVerifiedApply` は常にnullであり、
  spec 230 D2の相関gate（無変更で再利用）は常に「共通の戻り先文のみ」を返す。
  検査windowはRECOVERY leaseが保護し、admission後の競合挿入（新しいrun・別復元）は
  既存のadmission規約で阻断される。
- **確認・実行とresult離脱**: 復元の実行は `confirmRecovery()` / `State.RecoveryResultState` を
  無変更で使う。status card entryは `appliedPoint` / `lastVerifiedApply` を **設定も消去もしない**。
  一方、**結果面からのhub帰還** は現行のままでは契約を満たさない: 現行の `dismiss()` は
  `RecoveryResultState`（`activeOperation == null && recoveryLease == null`）を
  `NoActiveOperation` として扱い **stateを `RecoveryResultState` のまま残す**。hubは
  `Idle`/`Cancelled` のときしかdurable statusを読まないため、このままでは復元直後のstatus cardが
  再deriveされず、RS-AC-01/02の復元後の行更新が成立しない。ただし解消を汎用 `dismiss()` や
  host cleanup（`onDispose`）へ畳み込むことは **しない**: 結果面は
  `RestoreFailed` + safe-supportのとき「診断を開く」で別destinationへpushされ、その遷移でも
  run面compositionはdisposeされるため、`onDispose` 経由でstateを変えると診断からの復帰時に
  result/safe-support面が失われる。そこでcoordinatorは **entry originとpre-entry表示状態を
  result離脱まで保持** し、**ユーザーのhub帰還を確定させた経路（結果面でのsystem Back）だけが
  呼ぶ明示的な新操作**（仮称 `leaveRecoveryResultToHub()`。最終名は実装PRで確定）を設ける:
  `RecoveryResultState && origin == HubStatusCard` のときだけpre-entryの表示状態
  （`Idle`/`Cancelled`）を発行してoriginを解消し `DismissalOutcome.CancelledAndMayNavigate`
  を返す（zero-write）。該当しないときはno-opである。`dismiss()` は **現行mainから無変更**
  とし、host cleanup・再composition・非Backのdisposeではterminal stateを変更しない
  （result/safe-support面の既存の寿命を維持）。旧entry（`AppliedSurface`）・origin無しの挙動は
  すべて現行どおりである。新しいrunの開始（`beginOperation()`）はoriginを解消する
  （recovery flow stateのresetに含める）。run面側の変更はBack経路での当該操作呼出しの
  **最小diff** に限る（plan §5）。

`State.Applied` からの既存entry（`beginRecoveryPreview()`）は現行どおり残り、spec 230の
AC-2(e)相当のoracle（restart後は旧entryへ到達しない）は舊pathについて引き続き成立する
（「Relationship to existing specs」節のspec 230項を参照）。

### D6: CTAの出現と受理 — **run稼働中は不出現・不受理、fail-closedはCTAなし**

- **出現条件**: coordinatorが `Idle`/`Cancelled`（既存の `showDurableStatus` 条件。hubは
  現行main [OrganizerHubPreferences.kt][8] 87行と同一条件）かつdurable statusが
  `ORGANIZED_RESTORABLE` かつ D1のentry readが成功（entry != null）。
  いずれかが満たされないとき、行は現行の表示のみに後退し、CTA・残時間を出現させない
  （「発明した操作」を作らない。spec 271のfail-closed行なし規約と同一の姿勢）。
- **不受理**: 出現後でも、tap時にRECOVERY leaseが取れない（run/authoring/別復元がactive）、
  admission条件（D5。state不正・entry read nullを含む）を満たさない、またはinspectionが
  `WriterBusy` / `Concurrent` / `Unavailable` を返すとき、既存の不受理・typed文言規約に従う
  （TO-BE §9「CTA処理中の不受理規則も現行契約を維持する」）。CTA tap自体の競合不受理は
  既存の `beginRecoveryPreview()` と同じ **静かな不受理** である。
- **表示readの直列化**: status cardの表示は `durableOrganizerStatus()` と
  `readRestorableRecoveryEntry()` を **並列に実行しない**。両readは同一の非block
  `ordinaryMutex` を争うため、並列実行は相互を「競合中」と判定させ、status行の消失や
  entry readのfail-closed（CTAなし）を自作できる。表示側は同一effect内で **status readを先に
  実行し、結果が `ORGANIZED_RESTORABLE` のときに限りentry readを続行する** 直列化を要求する
  （entry readがnullでもstatus表示は変えない。fail-closedは安全側、D1/D2）。再読込契機は
  既存のDS-AC-09規約に従い、一時的な読取失敗の後にstate・gateが変化すれば再読込で回復する
  （永続的なCTA欠落を作らない）。
- **配置**: CTAは **hub status card**（[OrganizerHubPreferences.kt][8] `hubDurableStatusItems`、
  現行main 197–220行）の `ORGANIZED_RESTORABLE` 行にのみ付く。D-15の契約上のentry面は
  hub status cardであり、settings側run面
  （[ManualOrganizationPreferences.kt][4] `durableStatusItems`、現行main 1072行付近）の同一行は
  現行どおり **表示のみ** を維持する（CTAを付けない。spec 366 HUB-AC-05の既存導線維持の
  範囲内であり、origin modelを `AppliedSurface`/`HubStatusCard` の2値に保つ）。
  spec 366 HUB-AC-03の否定的観測（復元CTAなし）は本specの受入により復元CTAの
  部分がsupersedeされる（companion revision節。AI依頼・取り込み済み提案・run結果の部分は
  #374/#375が所有し続ける）。
- **遷移と復帰**: CTAの選択で確認flowのhost面（既存の確認面。T-14相当、settings側run面）へ
  到達し、検査→確認→復元が完結する。cancel/back/dismissはD5の戻り先契約に従い、ユーザーは
  hub status card側へ戻る。**復元成功後の結果面からユーザーがhubへ戻ることを確定させたとき
  （結果面でのsystem Back → D5の明示的操作）のみcoordinatorはpre-entryの表示状態へ戻り**、
  status cardは既存の再読込契約（DS-AC-09）でdurable statusを再deriveする。結果面からの
  診断push等の非明示的離脱ではstateは保持され、戻るとresult/safe-support面が維持される。
  復元成功後の選択pointは `RESTORED` となり、hub帰還時の再deriveで行は
  「restored or expired」表示へ変わる。ただし他のretention内 `VERIFIED` pointが残る場合、
  行は再びrestorableとなり、CTAは **その時点の最新の検証済み1点** に対して再度機能する
  （D1の選択は毎回の読み取りで行う。選択UIは存在しない）。

## Behavior scenarios

### Scenario: cold processでstatus cardから復元が完結する（有効pointが1点のみ）

Given recovery storeにretention内の `VERIFIED` recordがあり、processは再起動済み（startup
reconciliation完了、run coordinatorは `Idle`）、Launcherは一度も開かれておらず、
**retention内の有効な `VERIFIED` pointはこの1点のみ** である
When ユーザーがhubを開き、status cardのrestorable行（残時間付き）の復元CTAを選択し、
確認面で確認する
Then 検査→確認（共通の戻り先文のみ。適用履歴行はない）→復元が完了し、
復元後のlayoutはrecovery protocolの検証を経たpre-stateと一致する
And 確認tokenはこのprocessで発行されたfreshなone-shot tokenである
And 復元完了後にstatus cardへ戻ると、durable statusは「restored or expired」を表示する
（有効pointが残っていれば、代わりに残存pointに対するrestorable表示とCTAが再提示される。
「選択は常に最新の検証済み1点である」シナリオ）
And 一連のflowでLauncherは開かれない（DS-AC-10と同型のcold-process evidenceで検証する）

### Scenario: 選択は常に最新の検証済み1点であり、残存pointは再提示される

Given retention内の `VERIFIED` pointが複数（最大3点の現行契約内）存在する
When status cardのCTAから復元を開始する
Then 検査・確認・復元の対象は `createdAtMs` が最大の1点であり、それ以外のpointは
lifecycle・payloadともに変更されない
And 複数の候補があることを示す一覧・選択UIは存在しない
And 復元完了後にretention内の別の `VERIFIED` pointが残っている場合、行は再びrestorableを示し、
次のCTAでは残存pointのうち最新の1点が対象になる
（残存pointがなければ行は「restored or expired」へ変わる）

### Scenario: status card起点のcancel/backはhub側へ戻り、旧Applied面へ復帰しない

Given run coordinatorの表示stateが `Cancelled` である（durable行が見える。`Idle` でも同じ）、
status cardのCTAからstatus card entryで復元previewが開かれている
When ユーザーがcancel（またはback/dismiss）する
Then coordinatorはpre-entryの表示状態（`Cancelled`）へ復帰し、`State.Applied` 面・
`lastVerifiedApply` 由来の面へは復帰しない。UIはhub status card側へ戻る
And status card entryは表示stateが `Idle`/`Cancelled` 以外のときに呼ばれると静かに不受理され、
状態・書込みは変化しない
And 旧 `State.Applied` 面 entryのcancel（`cancellingRecoveryPreviewRestoresVerifiedApplySummaryAndActionSurface`
相当）は現行契約どおり `lastVerifiedApply` へ復帰する（無変更の回帰確認）

### Scenario: 復元成功後の結果面からhubへ戻るとstatus cardが更新される

Given status cardのCTAからstatus card entryで復元を実行し、確認が成功して
`State.RecoveryResultState` が表示されている（originとpre-entry表示状態は保持されている）
When ユーザーが結果面でsystem Backする（hub帰還を確定させる明示的経路）
Then coordinatorはD5の明示的操作でpre-entryの表示状態（`Idle`/`Cancelled`）へ復帰し、
`RecoveryResultState` のstate残留は起こらない。UIはhub status card側へ戻る
And status cardは既存の再読込契約でdurable statusを再deriveする: retention内の有効な
`VERIFIED` pointが残っていれば残存pointに対するrestorable行（残時間・CTA）が再提示され、
残っていなければ「restored or expired」行が表示される
And 結果面から「診断を開く」等で別destinationへpushした離脱（非明示的host dispose）では
terminal stateは変化せず、診断からBackするとresult/safe-support面が維持される
And 旧 `State.Applied` 面 entryの結果面挙動（Back含めstate残留・現行契約）は無変更である
（回帰確認）

### Scenario: 適用履歴行は相関gateに従う（status card起点では構造的に出ない）

Given status cardからの復元確認面が表示されている（cold processとwarm processの両方で成立）
When 確認面を観察する
Then 共通の戻り先文とconfirm/cancelのdecision pairが表示されるが、適用履歴行は表示されない
（spec 230 D2の相関gateの再利用。status card entryのadmission条件と `lastVerifiedApply`
の生存条件の構造的排他（D5）により、entry時の `lastVerifiedApply` は常にnullであり
correlatedは常にnullである）
And 旧 `State.Applied` 面 entryの相関gate挙動（pointId一致時のみ履歴行。spec 230 AC-2）は
無変更であり、既存unit oracleが無編集でgreenである

### Scenario: 表示中の期限切れ（TOCTOU）は既存のtyped文言で扱う

Given status cardにrestorable行とCTAが表示されている間に、選択pointのretentionが尽きた、
または別の要因でpointが復元不可能になった（自動recoveryでの復元を含む）
When ユーザーがCTAを選択する
Then 検査は既存のtyped結果（例: `NotRestorable(EXPIRED)` / `ALREADY_RESTORED`）を返し、
確認面は既存のnot-available文言を表示する
And recovery store・layoutへの書込みは発生せず、cancelでD5の戻り先契約どおり
hub側のpre-entry状態へ戻る
And status cardへ戻ると、行は期限切れ後の表示（restored or expired等、現行語彙）のみである

### Scenario: run稼働中はCTAが出現せず、競合時は不受理である

Given 同一process内でrun coordinatorが進行中（`Idle`/`Cancelled` 以外）である
When hubのstatus cardを観察する
Then durable status行・残時間・CTAは表示されない（既存の進行中優先規約）
And 行が表示されている状態から別のoperationが先にadmissionを取った場合、CTAのtapは
既存の不受理規約どおり静かに無視され、状態・書込みは変化しない
And 検査が `WriterBusy` / `Concurrent` を返す場合は既存のtyped文言を表示する

### Scenario: one-shot tokenはfresh processで発行され、二重実行できない

Given status cardからの検査で `Restorable` とtokenが発行されている
When (a) 同一process内で同一tokenによる2回目のconfirmを試みる、
または (b) preview表示中にprocessが死んだ後に、新processで旧tokenによるconfirmを試みる
Then いずれも復元は実行されない（(a) は既存のone-shot消費契約、(b) はtoken registryが
`LayoutApplicationModule` instance stateであるためprocess死とともに消滅。D4）。
typedな非復元結果として現行契約どおり扱われる
And (b) の後はstatus cardのCTAから再検査を行えば新しいfresh tokenで復元を再開できる
（process死後の再開手段は再検査のみであり、旧confirmの再開は存在しない）

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
  `Clock`、既存の `RetentionPolicy`（spec 13の24h定数。`RetentionPolicy.kt` 現行main 24行）、
  readiness gate状態。D1のentry readは `durableOrganizerStatus()` と同一のgate・mutex・
  fail-closed契約で読む。
- **書く**: 新規の永続化はない。復元実行の書込みは既存のspec 13 recovery protocolだけが所有する
  （本specの新経路は `recoverWithApplicationBehavior` / `RecoveryProtocol.recover` への
  既存の委譲を再利用し、書込み経路を追加しない）。AGENTS.mdのホームレイアウト安全規約
  （revision一致・保持/移動/削除の説明可能性・transaction・recovery point・再検証）は
  既存protocolが引き続き所有し、本specはそれを迂回する経路を新設しない。
- **Identity**: `RecoveryPointId` は既存のopaque相関キーとしてcoordinatorが一時保持するだけ
  である。新識別子・generation追跡・tokenの永続化は導入しない。entry origin
  （D5）はprocess-localなcoordinator stateであり、永続化しない。
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
  registryがapplication module instance stateであることはspec 84 RP-AC-05の実装前提であり、
  本specはそれを変更しない。

## Accessibility and localization

- status card行のTalkBack読み順はTO-BE §13-5の **「状態→残期限→操作」** 順に固定する。
  spec 366が読み順規約とcompose順の実装（hub 現行main 126–128行の規約コメント、
  「状態→操作」の実効順序）を導入済みであり、本specが残期限要素と操作をその規約へ
  初めて載せる。CTAは明示的なrole/state（action）として公開し、行全体のstatus textと
  区別して読める。
- 確認面（T-14相当）のa11yは既存の `FocusTargetText` / `SummaryText` / decision pair /
  liveRegion規約（spec 230/195/209）を無変更で使う。適用履歴行はstatus card起点では
  表示されず、表示される場合（旧entry）も既存のliveRegion対象外規約に従う。
- 200% font scaleで行・CTAがwrapし、critical action（復元CTA・確認・cancel）へ到達できる。
  状態表示はcolor-onlyにしない。focus restorationは決定的である
  （spec 366の `hubFocusRestoresToStartAfterBackFromRunSurface` 相当の規約を復元flowにも
  適用する）。
- 新規文字列（残時間表示・CTA label・必要な複合文）はformat resourceで構成し、
  EN（`values/`）とja（`values-ja/`）を同時に供給する（#123契約）。既存status文言の
  resourceは再利用する。TO-BE §10語彙（「復元」。アンドゥ/バックアップ回避）を新規copyで遵守する。

## Compatibility and migration

- additive変更である。既存の復元entry（`State.Applied`）、確認面、recovery protocol、
  durable status表示への後方互換性は保たれ、既存suiteが無編集でgreenであることを要求する
  （spec 230 AC-2(e)相当の旧path oracleを含む。表示文字列の置換を行わない範囲）。
  例外はspec 366 HUB-AC-03の否定的観測のうち復元CTAに係るoracleのみであり、companion
  revision（次節）でsupersedeを記録して更新する。
- **依存する面は実装済みである**: hub status card（#366、PR #380 merge済み）がdurable status行を
  描画し（`OrganizerHubPreferences.kt` `hubDurableStatusItems`）、settings側run面
  （`ManualOrganizationPreferences.kt` `durableStatusItems`）も同一行を表示のみで維持する
  （spec 366 HUB-AC-05）。本specのCTAはhub status cardの行にのみ付き（D6）、settings側run面の
  行・確認面は無変更である（hub起点の確認flowは既存のhost面を共有する。run面側の変更は
  結果面のsystem Back経路での明示的hub帰還操作呼出しの最小diffに限る — D5）。
  #369のrun面統合（実装済み）で確認面のhostは既存のrun面に留まっており、本specの契約は
  「CTA→検査→確認→復元の完結性」について不変である。
- process death: 復元確認中のprocess死は既存のrestart reconciliation（spec 13）と
  token消滅（D4。registryはmodule instance state）で扱う。durable statusは次回読み取りで
  再deriveされる。

## Relationship to existing specs

- **spec 84（recovery preview seam）**: 無変更・Continue（disposition §3.6）。
  本specは `inspectRecovery` と `confirmRecoveryPreview` の **呼び出し側を増やすだけで**、
  公開result型・one-shot token契約・I0–I5手順を変更しない。新しいapplication操作は
  選択read（D1）のみである。RP-AC-05（token→{pointId, revision}のprivate registry bind）と
  RP-AC-06（confirm時の再検証）は現行実装のまま本flowへ再利用される。
- **spec 13 / ADR-0003（recovery protocol・storage）**: 無変更。24h・最大3点・tombstone・
  revision-bound・transaction・restart reconciliationの契約は現行どおり（Issue受入2）。
- **spec 230（restore確認対象の説明）**: 相関gate（D2）と表示契約は **無変更で再利用** する。
  spec 230のD2に「restart 後に restore 確認へ到達することはない」という当時の到達性の記述があるが、
  これはAS-ISの唯一のentry（`State.Applied`）を述べたものであり、D-15（accepted TO-BE）が
  status cardからの第2entryを認める。normativeなAC・display契約の変更は不要であり、
  実装PRは旧到達性記述がD-15によりsupersedeされた旨を記録する
  （disposition §1「旧oracleがなぜobsoleteかを記録」の規約に沿う。AC-2(e)相当の旧path
  unit test `freshRunInstanceDoesNotReachRecoveryPreview` は舊entryについて引き続きgreenであり、
  削除ではなく注記で扱う）。status card entryではadmission条件と `lastVerifiedApply`
  の生存条件の構造的排他により履歴行が常に出ないことはD5に記録した。
- **spec 271（durable status projection）**: 本Issueが改訂を所有する（次節）。
- **spec 366（hub、implemented）**: status cardの語彙・string resource・run稼働中隠蔽・
  checking行・fail-closedの契約（HUB-AC-02）は無変更で維持する。読み順規約（「状態→残期限→
  操作」）とa11y基準を継承する。HUB-AC-03の否定的観測のうち「復元CTAが存在しない」部分のみ、
  本specの受入によりsupersedeされる（companion revision節）。設定側導線の維持（HUB-AC-05）
  に従い、settings側の同一行も同一描画規約を共有する。
- **spec 52（manual run縦切り）**: 成功結果面の復元entryは無変更。本specはT-14相当の
  既存確認面へ接続するだけであり、run state machine・表示統合に触れない。
- **spec 123（UI収束）**: 新規row/CTAは既存component・tokenで実装し、inventory evidence文書
  への追記を実装PRで行う（spec 366と同一の扱い）。

## spec 271 / spec 366 companion revision（実装PRで実施）

本specの実装PRは、[spec 271](../271-organizer-durable-status-projection/spec.md) と
[spec 366](../366-organizer-hub-shell/spec.md) に対して次の **最小限の改訂** を含める
（disposition §2.3/§4.1/§5 順10の割当）。内容は実装PR時点のowner reviewに付される:

1. **spec 271 Non-goals**: 「Enabling the restore preview/confirm flow from a cold process … is a
   separate, future spec (tracked as a follow-up)」の項を、cold-process restore entryの所有が
   本spec（specs/376-durable-status-recovery-entry）へ移行した旨の参照へ置き換える
   （supersession map §4.1行「spec 271 Non-goals cold-process restore・表示のみ契約 →
   status card復元導線（D-15。新spec）」の実行）。
2. **spec 271 DS-AC-07**: 「The Settings render mapping …」の表示面を、D-02/D-15以降の正位としての
   **status card（hub）** へ更新する。run稼働中の行隠蔽・unresolvedのsafe-support導線・
   fail-closed renderの契約内容は維持する。
3. **spec 271 Open questions / Change history**: cold-process restore follow-upの追跡注記を
   本specの受入で解決済みとして更新し、本改訂の記録を1行追加する。
4. **spec 366 HUB-AC-03**: 「hubに復元CTA、進行中AI依頼、取り込み済み提案、最近のrun結果の
   表示・操作が存在しない」の否定的観測のうち **復元CTAに係る部分** を、D-15（本spec）の
   受入によりsupersedeした旨を注記する。AI依頼・取り込み済み提案・run結果の部分は
   引き続き存在しないこと（#374/#375が所有）と、対応する否定的instrumentation oracle
   （`hubExposesNoRestoreOrRunResultAffordancesAndStartsNothing`）を「復元CTAは存在する /
   それ以外の操作は存在しない」へ更新する旨を記録し、Change historyへ1行追加する。
   HUB-AC-01/02/04〜の契約は変更しない。
5. `OrganizerDurableStatus` 型・派生規則・spec 271のDS-AC-01〜DS-AC-06/DS-AC-08〜DS-AC-10の
   契約は変更しない（D2）。

`DESIGN.md` §4.2（module所有のread-only seam列挙）への選択readの追記、および
`CONTEXT.md` への用語追加要否（新ドメイン概念は無し。見込み: 不要）は実装PRで確認・実施する
（spec 271 DS-AC-08と同一の正本更新規約）。

## Dependencies

- **#366（hub、T-01）**: **merge済み**（PR #380、2026-09-19）。status cardの恒常的な置き場所と
  CTAの契約上のentry面は実装済みであり、本specの実装はその上にadditiveに行う
  （disposition §8の依存graph `D366 --> D376` は解消済み）。
- **#365（正本改訂、docs-only）**: **merge済み**（PR #379、2026-09-19）。本specの契約根拠
  （organizer-to-be-ux.md D-15、organizer-disposition-migration.md）は正本としてmainに存在する。
- **#369/#371/#372/#373**: 実装済み（PR #387/#391/#393/#396）。確認面のhost・run面統合は
  現行mainの実装として再検証済みであり、本specの契約はこれらに依存しない（plan §1参照）。
- **本specのowner受入**: 実装着手の残りのblockerである（`status: draft` → accepted）。
- **前提（implemented/accepted）**: spec 84（implemented）、spec 13（accepted）、
  spec 230（implemented）、spec 271（implemented）、spec 89（implemented）、
  spec 265 / 270（implemented。post-apply reconciliation・typed capture failure）。

## Acceptance criteria

- [ ] **RS-AC-01**: cold process（再訪問時）にhub status cardから復元検査→確認→復元が完結し、
      一連のflowでLauncherが開かれない。evidence時点でretention内の有効な `VERIFIED` pointは
      対象1点のみである（複数点の場合の再提示はRS-AC-02）。（Issue受入1）
- [ ] **RS-AC-02**: 復元対象は常に最新の検証済み1点（retention内の `VERIFIED` +
      checksum有効、`createdAtMs` 最大、決定的tie-break）であり、選択UIは存在しない。
      複数pointが存在するとき最新を復元した場合、残存する有効pointに対して行が再び
      restorableとして再提示され、次のCTAの対象が残存pointの最新になる。再提示は
      復元成功後の結果面からの明示的hub帰還（D5の `leaveRecoveryResultToHub()` 相当）と
      status cardの再読込を経て起きる（state-machine unitで直接証明し、manual evidenceだけに
      依存しない）。24h / 最大3点 / tombstone契約（spec 13 / ADR-0003）に変更がない。（Issue受入2）
- [ ] **RS-AC-03**: one-shot確認tokenは検査を実行したprocess内でfreshに発行され、永続化されず、
      二重実行・死後process横断の確認ができない。token registryの所有境界は
      application module instanceであり、process死の構造的surrogate（fresh module構築）で
      旧tokenが消費不能であることをunitが固定し、preview表示中のprocess死後は再検査のみが
      再開手段であることをinstrumentation/cold-process evidenceが示す。（Issue受入3）
- [ ] **RS-AC-04**: writer lease（`LayoutWriteCoordinator` 経由のORGANIZER kind）・RECOVERY
      lease（`OrganizationOperationLease`）・run admissionの排他が維持され、run稼働中はCTAが
      出現せず、競合時のtapは不受理（静かな不受理）である。status card entryは表示stateが
      `Idle`/`Cancelled` 以外では不受理であり、そのcancel/back/dismissはpre-entryの表示状態
      （`Idle`/`Cancelled`）へ復帰して旧 `State.Applied` 面へ復帰しない。**復元成功後の
      hub帰還は結果面でのsystem Backに束縛された明示的操作でのみpre-entry状態へ復帰し、
      `dismiss()`/host cleanup（`onDispose`・診断push等の非明示的離脱）では
      `RecoveryResultState` を変更しない**。旧entryのpreview cancel契約・結果面での現行挙動は
      無変更である。（Issue受入4＋D5）
- [ ] **RS-AC-05**: 期限切れ・`NotRestorable`・`Unavailable`・`WriterBusy`/`Concurrent` の表示が
      現行のtyped文言契約どおりであり、cancelはzero-write、fail-closedではCTAを出現させない。
      期限切れ後の行は表示のみである。（Issue受入5）
- [ ] **RS-AC-06**: 残時間表示は粗粒度（時間単位・切捨て・1..24 + 1時間未満の区分）であり、
      絶対時刻・identifier・record件数を運ばず、再読込時に更新する（live countdownなし）。
      status card表示のstatus readとentry readは並列に競合せず（同一effect内での直列化。
      `ORGANIZED_RESTORABLE` のときに限りentry readを実行）、一時的な読取競合の後も
      再読込契機で回復し、永続的なCTA欠落を作らない。TalkBack読み順は「状態→残期限→操作」
      である。（D3 / D6 / TO-BE §13-5）
- [ ] **RS-AC-07**: spec 271 / spec 366 companion revision（spec 271 Non-goals・DS-AC-07・
      Open questions・Change history。spec 366 HUB-AC-03の復元CTA部分のsupersede注記と
      oracle更新記録）が実装PRに含まれ、owner受入済みである。spec 84/13/230/52の公開契約に
      変更がなく、既存suite（旧entryのrestart oracleを含む）が無編集でgreenである。
      （Issue受入6）
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
| RS-AC-01 | Cold-process emulator evidence: force-stop → hubを最初の面として起動 → Launcherを開かずにstatus cardのCTA→検査→確認→復元を完了 → 結果面でBackしてhubへ戻る → 復元後の行が「restored or expired」へ変わる（有効point 1点のみのpreconditionを明記）。PR/auditへ記録（spec 271 DS-AC-10と同型）。`OrganizerDurableStatusInstrumentationTest` 系はCI lane外のため、PR時の手元emulator実行を証拠として記録する。加えてstate-machine unit: hub originの confirm→ResultState→明示的hub帰還→pre-entry状態復帰を直接証明 |
| RS-AC-02 | Unit test（純粋selector）: 単一点 / 複数点（最新選択） / retention境界（±1 ms at `createdAt + 24h`） / checksum無効・非VERIFIEDの除外 / 同 `createdAtMs` の決定的tie-break / 空のときのclosed null。既存 `OrganizerDurableStatusDeriverTest` と同一のfixture方式。coordinator unit oracle: 複数pointで最新を復元→ResultState→明示的hub帰還→pre-entry状態復帰→entry re-readが残存pointの最新を返すこと（再提示のstate-machine直接証明）。Instrumentation: 復元成功→hub復帰→status再読込→複数点なら次点restorable行 |
| RS-AC-03 | Unit test: tokenのone-shot消費（2回目は不成立）、**fresh `LayoutApplicationModule`（fresh registry）構築で旧tokenが消費不能**（registry所有境界の構造的固定。coordinator再構築ではregistryが残るためsurrogateにならないことをtestコメントで明記）、同一module instanceでのtoken消費成功（対比）。confirm連鎖での `RecoveryRequest` 非漏出（既存 `RecoveryPreviewContractTest` の継続成功）。Instrumentation/cold-process evidence: preview表示後にprocessを落とす→再入場では旧confirmを再開せずstatus CTAからの再検査のみ |
| RS-AC-04 | Unit test（coordinator）: run active中のcold entry不受理・RECOVERY lease単一flight・`State` 遷移（Inspecting→Preview(correlated=null)→Recovering→ResultState）。**status card entryのadmission state検査（`Idle`/`Cancelled` 以外は静かに不受理）とcancel/back/dismissのpre-entry状態復帰（`Cancelled` から開いた場合は `Cancelled` へ復帰し `State.Applied` へ戻らない）**。**結果面のhub帰還: hub originの `RecoveryResultState` で明示的操作（system Back経路）のみがpre-entry状態へ復帰し、`dismiss()`/host cleanup（`onDispose`・診断push相当のdispose）ではstate不変。旧Applied origin・origin無しでは明示的操作も含め現行挙動のまま**。旧entryのcancel契約（`cancellingRecoveryPreviewRestoresVerifiedApplySummaryAndActionSurface`）とrestart oracle（`freshRunInstanceDoesNotReachRecoveryPreview`）の無編集green。Instrumentation: run進行中のstatus cardにCTAが出ない否定的観測（既存 `hubHidesStatusRowsWhileRunIsActiveAndReshowsAfterCancel` の継続）＋ RestoreFailed→診断を開く→診断Back→result/safe-support面が維持される観測 |
| RS-AC-05 | Unit test: TOCTOU expiry / stale / writer busy / unavailable のtyped結果伝播とzero-write counter。Instrumentation: not-available文言・cancel zero-write（既存 `cancellingRecoveryPreviewRestoresVerifiedApplySummaryAndActionSurface` / `verifiedSuccessKeepsAppliedWordingAfterRecoveryPreviewCancel` の継続成功 + status card entry版の追加） |
| RS-AC-06 | Unit test: `RemainingWindow` の境界（切捨て・1..24 clamp・1時間未満区分）。**status card表示のread直列化oracle: hub Compose testでfake coordinatorがstatus readとentry readの呼出し重複を記録し、並列実行（重複）が起きないこと、status readが `ORGANIZED_RESTORABLE` 以外のときentry readが呼ばれないこと、一時競合（fail-closed read）後に再読込契機でCTAが回復すること**。Compose semantics test: 読み順「状態→残期限→操作」、CTAのrole/state（既存 `hubStatusRowsPrecedeTheActionsInReadingOrder` / `hubRowsExposeNameRoleAndStateToAssistiveTechnology` の規約継承）。200% font scaleのevidence（既存 `hubStatusCardStaysReachableAtTwoHundredPercentFontScale` の規約継承） |
| RS-AC-07 | spec 271 / spec 366 spec diff（companion revision節）+ owner受入記録。public-shape契約test（spec 84 RP-AC-01系）の継続成功 + 旧entry restart oracle（`freshRunInstanceDoesNotReachRecoveryPreview`）の無編集green |
| RS-AC-08 | Fake counterによるno-write/no-event assert（既存 `RecoveryPreviewProtocolTest` / `RecoveryPreviewContractTest` の方式をentry readへ拡張）+ diagnostics契約testの無変更green |
| RS-AC-09 | 新規stringの `values/` / `values-ja/` のname集合・placeholder一致の機械確認 + hardcoded literal grep（spec 366の同一方式） |
| RS-AC-10 | `./gradlew spotlessCheck`、organizer unit gate、organizer instrumentation lane（`organizer-instrumentation-issue52-tests` の対象class群）、`ManualOrganizationProductionE2EInstrumentationTest` のrecovery区間継続green、CI `final-status` + `high-risk-evidence` 成功、`docs/assessment/pr-<n>-<slug>.md`（Head SHA・spec受入条件の要件ID参照・test表面・CI run link） |

## Open questions

実装着手前に解消が必須な問いはない。Design decisions D1–D6が、disposition §11が本Issueへ
委ねた未決定事項（選択規則・lease・tokenのfresh process発行・閉域語彙の拡張要否）に加え、
review指摘で明確化した戻り先契約（D5）とregistry所有境界（D4）を確定した。非blocking事項:

1. 新操作・型の最終名（`readRestorableRecoveryEntry` / `RestorableRecoveryEntry` /
   `RemainingWindow` / entry origin表現は仮称）と、残時間表示の最終copy（EN/ja）は
   実装PRのreviewで確定する。
2. CTA tap後の面遷移の実装形態（確認面hostへのnavigation timing・route指定。hubの既存
   `HomeScreenOrganizer` → run面 `HomeScreenManualOrganization` のnavigation構造の範囲内）
   は実装PRのreviewで確定する。契約は「確認flowの完結性」と「cancel/backでhub側へ戻る」
   のみを要求する。
3. status card行copyのTO-BE §10語彙への追加整合（既存status文言の言い回し変更を行うか）は
   非blockingのstring判断であり、行う場合はEN/ja同期とspec 271表示契約の範囲内で行う。

## Change history

- 2026-09-22（r4）: 3rd review指摘1点対応（@5764326404）。結果面からのhub帰還を汎用
  `dismiss()`/`onDispose` に畳み込んだrev.3の設計を修正 — 診断push等の非明示的host離脱でも
  `onDispose` 経由でpre-entry状態へ巻き戻り、result/safe-support面が失われる欠落。
  hub帰還を **結果面でのsystem Backに束縛された明示的な新操作**
  （`leaveRecoveryResultToHub()` 仮称）へ分離し、`dismiss()` は現行mainから無変更へ戻した。
  `beginOperation()` によるorigin解消、run面Back経路の最小diff許可（plan §5/§6）、
  診断push→Backでresult面が維持されるoracleを追加。
- 2026-09-22（r3）: 2nd review指摘3点対応（@5764069572）。(1) status card表示のstatus readと
  entry readの **直列化** をD6/RS-AC-06へ新設（同一の非block `ordinaryMutex` を2本のreadが
  並列に争う自己競合によるCTA欠落を防ぐ。直列化oracleを追加）。(2) 復元成功後の結果面離脱の
  戻り先契約をD5へ追加（現行 `dismiss()` は `RecoveryResultState` を `NoActiveOperation`
  でstate残留させるため、hub起点ではdurable statusが再deriveされない。
  `dismiss()` の `RecoveryResultState && HubStatusCard origin` 拡張でpre-entry状態へ復帰。
  旧Applied originは現行維持。unit oracle「confirm→ResultState→dismiss→pre-entry状態」を
  RS-AC-01/02/04へ追加しmanual evidence依存を解消）。(3) CTAの配置を **hub status cardのみ**
  に限定し（D6、Non-goals、Scope）、settings側run面のdurable行は表示のみ維持へ変更
  （origin modelの2値性を保持し、settings側CTAの戻り先未定義問題を解消）。
- 2026-09-22: Re-entry revision（review指摘4点対応 + main再基準化）。baseを現行main
  `c05435a947`（PR #379（#365）・#380（#366）・#387（#369）・#391/#393/#396 merge後）へ更新。
  (1) D5を「entry originに束縛された戻り先契約」へ改訂（status card起点のcancel/back/dismissは
  pre-entry状態へ復帰し旧 `State.Applied` 面へ戻らない。現行 `cancelRecoveryPreview()` の
  `lastVerifiedApply ?: Idle` がorigin非依存であることを根拠に特定）。(2) RS-AC-01/cold
  scenarioに有効point 1点のみのpreconditionを追加し、RS-AC-02に複数点復元後の再提示oracleを
  明記（最大3点契約との矛盾解消）。(3) D4/RS-AC-03をtoken registryの所有境界
  （`LayoutApplicationModule` instance）へ沿って書き直し（coordinator再構築はsurrogateに
  ならない）。(4) #366/#365をmerge済みへ更新しpre-merge fallback記述を削除。併せて
  spec 366を関係specへ追加（HUB-AC-03復元CTA部分のsupersedeとcompanion revision）、
  status card entryのadmission state検査（Idle/Cancelled）と「status card起点では適用履歴行が
  構造的に出ない」不変条件を明記、現行mainの行番号・test名・CI lane構成を実態へ更新。
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
[8]: ../../lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt
