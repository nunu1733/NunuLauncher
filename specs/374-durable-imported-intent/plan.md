# Implementation Plan: 取り込み済み提案（pending intent）のdurable化とhub status card接続（D-08）

> Issue: #374
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

以下はmain `a2b6aba318`（2026-09-19時点のorigin/main、disposition着地後）での確認済み
実装事実である。推測は「未検証」に分離する。

- **pending intentはprocess-local・画面state限定**:
  `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` の
  `ExchangeFlowStateHolder` が `private var pendingValidated: ValidatedPersonalizedIntent?`
  （570行付近）と `activeAttempt`（attempt token、563行付近）を保持する。holderは
  `ManualOrganizationPreferences.kt`（113行付近）で `remember { ExchangeFlowStateHolder(...) }`
  により **画面composition単位** で生成され、画面離脱・process死で消失する。成功状態は
  `ExchangeScreen.ImportSuccess(summary, entryKind, attemptToken, continuing)`（130行付近）。
- **import成功時の接続点**: `settleImport()`（651行付近）がvalidation settleを処理し
  `pendingValidated = pipeline.validated` のうえ `ImportSuccess` を採用する。CTAは
  `continueImport()`（745行付近）→ `connectRun()`（715行付近）→ `run.start(intent)` /
  `run.attachIntent(validated)`。破棄は `discardImport()`（819行付近、zero-write）。
  D-2のBack確認dialog・hosting level interceptionは同fileと
  `ManualOrganizationPreferences.kt` に実装済み。
- **durable storeの既存pattern（本計画の雛形）**:
  `lawnchair/src/app/lawnchair/organizer/personalization/ExportSessionStore.kt`（純粋seam。
  purity guard対象package）と
  `lawnchair/src/app/lawnchair/organizer/integration/AndroidExportSessionStore.kt`
  （`noBackupFilesDir` + `AtomicFile` + kotlinx.serialization + `SCHEMA_VERSION` 検査 +
  corruption/未知schema/書込失敗のfail-closed「recordなし」退化。単一active sessionは
  1 file = 1 sessionで自然に成立）。module accessorは
  `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeSessionStoreModule.kt`
  （module-per-concern object、`@Volatile` instance）。
- **session置換の書込点**: `ExchangeFlowController.generate()`（93行付近）が
  `store.save(built.session)` 成功後にpackage composeへ進む。「新session保存 → 旧pending
  無効化」の書込順序はこの直後にpending無効化を置くことで実現できる。
  `cancelDisclosure()`（127行付近）がpre-send cancelのsession invalidate。
- **summary導出の既存純粋関数**: `personalization/exchange/ExchangeImportSummary.kt` の
  `exchangeImportSummary(completed, scopeCount, categoryKindByRef)`。`categoryKindByRef` は
  `validated.export.categories` から作られるが、同一のref→kind対応は
  `ExportSession.categoryRefs`（durable、`BUILT_IN`/`USER_DEFINED` kind付き）から導出できる
  （`AndroidExportSessionStore` のrecord構造で確認）。`scopeCount` は
  `session.scopeCandidates.size`。
- **status card projectionの既存様式**: `LayoutApplicationModule.durableOrganizerStatus()`
  （315行付近、spec 271 implemented）がreadiness gate + run mutex + 閉域enum導出 +
  fail-closed `UNAVAILABLE` の様式を確立している。hub側の読取経路は
  `ManualOrganizationRun.readDurableOrganizerStatus()` → `ManualOrganizationModule.get(context)`
  singleton（cold process到達を `LauncherAppState.getInstance` +
  `ensureOrganizerStartupReconciliation` で保証。164行付近）。
  #366 spec draft（`bf00f96175`）はstatus card第1段階でこのseamの再利用を固定しており、
  提案行は同じstatus card領域への追加になる。
- **置換確認文言**: `lawnchair/res/values/strings.xml` 1310行 /
  `values-ja/strings.xml` 398行の `exchange_replacement_warning`
  （「Creating a new request invalidates the previous exchange; its reply can no longer be
  imported. Continue?」）。拡張対象の現行copy。
- **依頼sessionモデル**: `ContextExportModels.kt` の `ExportSession(exportId, itemRefs, ...,
  expiresAtEpochMs)`、`isExpired(nowEpochMs)`。`ValidatedPersonalizedIntent(intent, export,
  session, identity)` と `completed: CompletedPersonalIntent`（`IntentCompletion.kt` 26行、
  `exportId` / `decisions` / `globalPreference` / `rationale` / `confidence` +
  `authoredItemCount` 等の派生property）。
- **intent modelはkotlinx非対応**: `PersonalizedIntentV1` / `ItemIntent` 等に `@Serializable`
  は付かず、wire変換は手書きの `IntentCodec`（encode/decode）である。durable recordは
  `AndroidExportSessionStore` と同様の **専用`@Serializable` record** を新設するのが既存pattern
  に整合する（`CompletedPersonalIntent` を直接直列化しない）。
- **startup reconciliation trigger**: `LawnchairApp.ensureOrganizerStartupReconciliation()`
  （128行）がorganizer startup経路の共有入口であり、#374の起動時reconcile接続候補である。

## Design

### Modules and interfaces

AGENTS.md設計規約（小さなinterfaceの背後へ大きな振る舞いを隠す・既存patternの優先）に従い、
新設は **純粋seam 1 + Android実装 1 + module accessor 1 + projection/reconcile 1** に留める。

1. **`PendingImportedIntentStore`（新規純粋seam、`app.lawnchair.organizer.personalization`）**
   - 役割: 取り込み済み提案のdurable保持。`ExportSessionStore` と同型の小interface。
     例（命名は実装PRで確定。下記はsignatureの意図を示す以外は拘束しない）:
     - `save(proposal): Boolean`（単一active上書き。失敗はfalse）
     - `load(): DurablePendingIntent?`（不在/破損/未知schemaはnull。fail-closed）
     - `delete()`（破棄・置換無効化・reconcile清掃の共通出口）
   - record model（`DurablePendingIntent` 相当）: `exportId`、canonical decisions
     （`RefDecision` 相当のserializable表現）、planner-effective `minimizeMovement`、
     `expiresAtEpochMs`（session複製値。表示の正本はsession側 — spec契約）、
     `entryKind`（IDLE / RUN_IN。#375用）、`discarded: Boolean`（破棄mark。reconcile検証対象）、
     `createdAtEpochMs`。**`rationale`/`confidence`/ref対応表/labelはfieldとして存在させない**
     （DI-AC-10の型による保証。field不在assertionをcontract testにする）。
   - purity: interfaceとrecordは純粋packageに置き、Android依存はintegration側のみ
     （`ExportSessionStore` と同一の純粋性の境界）。
2. **`AndroidPendingImportedIntentStore`（新規、`app.lawnchair.organizer.integration`）**
   - `AndroidExportSessionStore` を雛形にした実装: `noBackupFilesDir`（backup除外）、
     `AtomicFile`（単一recordのatomic書込 — 置換は上書き1回、破棄は `delete()`）、
     `SCHEMA_VERSION` 検査、IOException/decode失敗/未知schema → null退化。
     `DI-AC-04` の寛容読みはこの実装に帰着する。
3. **module accessor（新規、`app.lawnchair.organizer.integration.exchange`）**
   - `ExchangeSessionStoreModule` と同型のmodule-per-concern object
     （仮称 `PendingImportedIntentModule`）。
4. **reconcile / projection（既存controllerまたは新規純粋object）**
   - **reconcile純粋関数**: 入力 `(durable record, active session | null, nowEpochMs)`、
     出力 `有効 / 無効（削除対象）`。判定条件はspec契約どおり
     `record.exportId == session.exportId`（session不在も無効）、session TTL、
     `discarded` mark、record TTL（session複製値とsession値の不一致はsession側を正とする）。
     table-driven unit test（DI-AC-05）の対象。
   - **status card projection（閉域語彙）**: spec 271の `OrganizerDurableStatus` と同一様式の
     閉じた読み取り専用model（仮称 `PendingProposalCard`）:
     `EFFECTIVE(remainingMs導出用のexpiresAt) / NONE / UNAVAILABLE` 相当。
     件数サマリの再構成（内容表示）はImportReview側の導出であり、status card行は
     存在・残時間・開封のみを運ぶ（payload非表示）。
     owner/reconcile失敗は `NONE`（fail-closed。発明した状態を作らない）。UNAVAILABLEとNONEの
     区別（checking様式を踏襲するか）は実装PRで確定（非blocking）。
   - 配置: reconcileはexchange契約側（`personalization/exchange`、純粋）に置き、
     呼出点（controller・status card読取・起動時）から使う。`LayoutApplicationModule`
     （application/recovery所有）へは置かない — 提案storeはexchange/personalization契約の
     所有物であり、recovery storeと正本が異なるため（DESIGN §4.2のprojection所有原則の適用）。

### Data flow

- **保存（import成功）**: `settleImport()` の成功分岐内（`ImportSuccess` 採用と同一settle）で
  `controller` 経由または注入seam経由で `store.save(...)`。recordは `validated.completed`
  （canonical decisions）+ `validated.intent.globalPreference.minimizeMovement` +
  `validated.session.exportId/expiresAtEpochMs` + entry種別から構築する。保存は
  `Dispatchers.IO`（既存のsettle hopと同一coroutine構造）。anchor契約（attempt token・
  single-flight・`continuing` guard）は一切変更しない（DI-AC-07）。
  保存失敗時はContract notes 1のprocess-local後退（typed案内、status card行なし）。
- **置換無効化**: `ExchangeFlowController.generate()` の `store.save(newSession)` 成功直後に
  旧pendingの `delete()`。書込順序固定（DI-AC-03）。cancelDisclosure（pre-send cancel）は
  session invalidateのみでpendingには触れない — session不在が次のreconcileで無効化する
  （単一の正本経路を維持）。
- **読取（status card / ImportReview）**: 読取のたびに reconcile → 有効ならprojectionを
  導出、無効なら `delete()` して `NONE`。ImportReview再開面はrecord + active session +
  `exchangeImportSummary` と同一入力基準の純粋導出で件数サマリを再構成する
  （`categoryKindByRef` は `session.categoryRefs` から、`scopeCount` は
  `session.scopeCandidates.size` から。validated intentの全量再構築はしない — #375の領域）。
- **起動時reconcile**: 既存の `ensureOrganizerStartupReconciliation()` 経路に接続する
  （Open questions 3。代替: projection初回読取への集約。どちらもspec契約を満たす）。
  実装は既存triggerのidempotent性を壊さない最小hookとする。

### Alternatives rejected

- **session storeへのpending併記（1 fileにsession + intent）**: spec 204 storeのschema変更と
  downgrade時の寛容読みを複雑化する。disposition §7.1は「新store」を規定しており、分離が正本。
- **`PersonalizedIntentV1` wire JSONの保存**: authored文書（`rationale` 等）をdurable化する
  privacy境界の拡大と、#330 D-4「authored文書はdiagnostics用」規約との不整合。canonical
  decisionsの専用recordを採用（spec契約どおり）。
- **session置換とpending破棄のatomic commit**: disposition §7.1が「要求しない」と明示。
  読取時reconcileを正本とする。
- **status card行への件数サマリ表示**: D-02は「durable事実と進行中状態の単一閲覧面」を要求するが、
  行の構造（存在・残時間・開封）は#366のstatus card契約（payload非表示・閉域語彙）と
  spec 271の様式に揃える。内容はImportReviewで見せる（TO-BE §5.3の再開1経路と一致）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/personalization/` | 新規: `PendingImportedIntentStore` seam + record model + reconcile純粋関数 | 純粋seamはpersonalization package（`ExportSessionStore` と同一の純粋性の境界） |
| `lawnchair/src/app/lawnchair/organizer/integration/` | 新規: `AndroidPendingImportedIntentStore`（`noBackupFilesDir` + AtomicFile + schema version） | Android/storage境界はintegration（`AndroidExportSessionStore` と同一配置） |
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/` | 新規: module accessor（`@Volatile` singleton）。既存 `ExchangeFlowModule` へstore注入の追加 | module-per-concern規約。controllerは生成・import orchestrationの所有者であり書込順序契約（置換無効化）をここに置く |
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | `settleImport()` へのdurable保存呼出追加・保存失敗時のtyped案内・破棄/成功settle時のrecord削除 | import lifecycleの唯一のUI持到手。anchor/single-flight契約は無変更 |
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowController.kt` | `generate()` のsession保存直後に旧pending無効化。reconcile呼出の提供 | 書込順序固定の唯一の位置 |
| status card（#366実装の `OrganizerHubPreferences` 相当。未mergeのため本Issue実装時に接続） | 提案行（存在・残時間・開封）+ TalkBack読み順への挿入 | #366 spec draftがstatus card拡張を後続（本Issue）に委譲済み。#366未merge環境では接続を後続PRへ分離（後述の実装順序） |
| ImportReview再開面（`ExchangeFlowUi.kt` の再開形態 or 新規composable） | record+sessionからの件数サマリ再構成・残時間・破棄（durable削除）。継続CTAなし | spec DI-AC-01/08。表示modelは既存summary純粋関数の再利用 |
| `ManualOrganizationRun.kt` / hub読取経路 | 提案projectionの読取seam追加（`ManualOrganizationModule` 経由の既存様式） | cold process到達保証（spec 271 DS-AC-10と同一の初期化経路）を共有 |
| `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` | `exchange_replacement_warning` 拡張・typed案内・status card行・破棄確認dialog（ja正本） | spec 123契約 |
| `LawnchairApp.kt`（起動経路） | 起動時reconcileの最小hook（Open questions 3の接続点） | 既存共有triggerの再利用 |
| specs 328 / 205 | rev.2 / pending規定の改訂（実装PR内または直前のdocs PR） | disposition §5 更新順序 #8 |

## Migration and recovery

- **migration**: なし（新store追加のみ。既存dataの変換は存在しない）。
- **置換のfailure**: 書込順序「新session保存 → 旧pending無効化」の途中でprocess death /
  I/O failureが発生した場合、stale recordは次の読取時reconcileでfail-closed清掃される
  （DI-AC-05の必須oracle: fake clock/storeによるprocess death遷移の再現 +
  `AtomicFile` 相当の書込失敗注入）。
- **downgrade**: 旧版は本storeを読む経路を持たない（file名・schemaが独立のため自然的に無視）。
  再upgrade後の最初の読取でreconcileがstale recordを清掃する。run・layout DBへの影響なし。
- **restore**: `noBackupFilesDir` のためbackupされない。restore後は提案なしの状態から始まる
  （TO-BE §7.3どおり明示）。
- **rollback**: PR revertで機能のみ消失。残留recordは旧版で読まれず、再適用後のreconcileで
  清掃される。recovery point・`favorites` への接触は一切ないため、ホームレイアウト安全規約の
  transaction/recovery point要件は本store単体のatomic書込で充足する。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| DI-AC-01 | store unit test + holder unit test + instrumentation（cold processのstatus card行 → 再開面）+ emulator cold-start evidence | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、organizer instrumentation lane、API 36 AVD |
| DI-AC-02 | store unit test（clock注入・TTL/session従属/invalidate連動） | unit gate |
| DI-AC-03 | controller unit test（置換承認 → 旧pending削除・書込順序）+ dialog文言test（en/ja） | unit gate + strings走査 |
| DI-AC-04 | store unit test（backup除外path・未知schema/破損fail-closed・downgrade相当） | unit gate |
| DI-AC-05 | reconcile table-driven unit test + process death oracle + write failure注入oracle + 起動時reconcile test | unit gate |
| DI-AC-06 | spec 328 rev.2差分review + owner受入記録 | docs PR review（Issue #374コメント） |
| DI-AC-07 | 既存 `ExchangeFlowStateHolderTest` のgreen（無編集またはanchor契約非依存の更新のみ）+ 保存追加後regression | unit gate |
| DI-AC-08 | holder/instrumentation test（破棄 → record削除・行消滅・session生存・再取り込み成立） | unit + instrumentation |
| DI-AC-09 | Compose semantics assertion（読み順「状態→残期限→操作」）+ 200% font scale + Switch Access traversal + screenshot evidence | instrumentation lane + emulator |
| DI-AC-10 | record model field不在contract test + `noBackupFilesDir` 機械確認 + summary導出contract test回帰 | unit gate |
| DI-AC-11 | spec 205差分review + 既存exchange系regression | unit gate + diff review |
| DI-AC-12 | ja/en strings走査（spec 123 AC-5方式）+ hardcoded literal grep | 機械確認 |

含めるべき観点: unit/contract（store・reconcile・summary導出）、failure injection
（書込失敗・置換途中のprocess death）、integration（import → 保存 → cold process読取 →
破棄 → 再取り込み）、UI/accessibility（status card読み順・再開面・200%）、regression
（spec 328 anchor/single-flight・spec 205 AC-13・#373失敗面）。

## Incremental implementation order

1. **spec受入**: 本specのowner受入（`draft` → `accepted`）。Contract notes 1〜5の解釈確認を含む。
2. **spec 328 revision 2 + spec 205改訂（docs PR）**: 本specの契約をspec 328/205へ反映し、
   owner受入を得る（disposition §5 更新順序 #8。DI-AC-06/11。**#328実装Issueの着手はこの受入後**）。
   このPRが #365/#366/#373 merge後であること（正本を先に・依存graph）を確認する。
3. **store縦切り（source PR群の最初）**: 純粋seam + Android実装 + module accessor +
   reconcile純粋関数 + table-driven unit test（DI-AC-02/04/05のstore部分）。UI変更なし。
4. **import lifecycle接続**: `settleImport` 保存・保存失敗typed案内・置換無効化・破棄の
   durable削除 + 置換確認文言拡張（DI-AC-01/03/07/08のcontroller/holder部分）。
5. **status card接続・ImportReview再開面**: #366実装のstatus cardへの提案行追加 +
   読取seam + 再開面（DI-AC-01/08/09）。#366/#373が未mergeの場合は本段のみ後続PRとして
   分離する（本Issue単独shippable構成。spec「Compatibility and migration」）。
6. **a11y・strings・evidence**: DI-AC-09/12 + device evidence。
7. **spec status更新 + 関連正本の更新**（`CONTEXT.md` 用語は#365経由、FR-017 status表記は
   実装完了時 — disposition §5 #11）。

## Dependencies / blockers

- **spec 328 rev.2の所有**: 本Issue（disposition §3.12/§5 #8）。rev.2執筆は上記順序2であり、
  本task（spec/plan整備）では行わない。**実装着手の前提**: #365（正本改訂）・#366（hub
  status card）・#373（T-18表示面）merge後、かつ本specとrev.2のowner受入後。
- **#375**: 再開面の継続CTA有効化・rebind・SCOPE_MISMATCH原因別remedyの所有。本planは
  recordにentry種別を残す以外のrebind実装を含まない。
- **#372**: T-15/T-16再構成。置換確認dialogの移設先（T-15面内）を変更するが文言契約は
  本spec正本（Contract notes 5）。
- **並行作業のseam調整**: `ExchangeFlowUi.kt` / `ExchangeFlowController.kt` は #373（失敗面
  projection）と同じfile群に触れる。#373の変更（表示model）と本Issueの変更（lifecycle接続・
  store）は関数単位で重ならないが、merge順（#373先）を守りrebase時の競合を最小化する。

## Risks

- **durable化によるanchor契約の破壊リスク**: 保存をsettle経路へ入れる際、attempt token
  anchor・single-flight・`continuing` guardの既存oracleが壊れないことをDI-AC-07で先に固定する
  （store縦切りとUI接続を分離するのはこのため）。
- **stale提案の表示残存リスク**: reconcile漏れ（新規読取経路の追加時にreconcileを素通りする
  実装）がstale表示を再生する。読取seamを1箇所に集約し、新規読取経路がreconcileを必ず通る
  構造（projection関数経由のみ）にする。
- **#366/#373とのmerge順序**: status card接続は#366実装の構造に依存する。#366 draftの
  plan（status cardはhub側composable、seamは既存のみ）を前提にし、#366実装との接続点は
  そのmerge後に本Issue実装PRで確定させる。
- **`risk: privacy`**: 新規durable store（intent内容）を追加するため。実装PRのlabel決定時に
  高リスクPR要件（CI merge gate + 独立audit記録）の適用可否を判定する
  （`risk: layout-data`/`risk: migration` には該当しない見込み — layout DB/recovery DBへ
  触れないため。ただし最終判断は実装PRのdiffで行う）。

## Explicitly unverified areas

- **#366実装のstatus card構造**: 本plan時点で#366は未実装（spec draft `bf00f96175`）のため、
  提案行の接続点（composable構造・読取seam名・checking様式の有無）はdraft契約からの推定であり、
  #366実装merge後に確定する。
- **#375のrebindがrecordへ要求するfield集合**: 本planはContract notes 3の最小集合（entry種別
  含む）までしか根拠を持たない。#375 specが追加fieldを要求した場合はspec改訂で拡張する。
- **reconcile起動時hookの厳密な接続点**: `ensureOrganizerStartupReconciliation()` の内部構造
  （readiness gate経由の非同期実行）への追加がtriggerのidempotent性・timeout契約へ与える影響は
  実装時に確認する（Open questions 3）。
- **残時間表示の経時更新**: 面表示中の残時間の再計算要否（Open questions 2）は実装PRで確定する。

## Documentation updates

- [ ] spec status/history（本spec、実装完了時に `implemented` 化 — owner受入フローに従う）
- [ ] spec 328 rev.2（順序2のdocs PR）
- [ ] spec 205 pending規定改訂（同上）
- [ ] CONTEXT.md（取り込み済み提案等の用語 — #365が正本所有。本Issueは参照のみ）
- [ ] DESIGN.md（data ownership §7への新store記載 — 実装PRで要否判断。§4.2のprojection所有
  原則との整合を確認）
- [ ] ADR（不要と判断: store様式は既存spec 204/271の延長であり、判断の新規性はdisposition §7.1が
  既に保持する）

## Execution checklist

- [ ] Spec accepted（owner review、Contract notes 1〜5確認）。
- [ ] spec 328 rev.2 + spec 205改訂がowner受入済み（#328実装着手の前提）。
- [ ] Current behavior reproduced（process死・画面離脱で提案消失、`pendingValidated` の
      画面state限定）。
- [ ] Tests fail for the missing behavior（store/reconcile/instrumentation）。
- [ ] Minimal implementation completed（store縦切り → lifecycle接続 → status card接続）。
- [ ] Migration/recovery verified（置換途中process death・write failure oracle、downgrade/
      restore相当）。
- [ ] Full relevant verification completed（unit gate・instrumentation lane・spotless・CI
      `final-status`）。
- [ ] PR evidence and remaining risks recorded（未検証area・#375引き継ぎ事項）。
