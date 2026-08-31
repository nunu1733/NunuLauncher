# Organizer Diagnostics and Observability Contract

> Status: Accepted (research output of [Issue #16](https://github.com/nunu1733/NunuLauncher/issues/16))
> Updated: 2026-08-15
> Baseline: Lawnchair `v15.0.0-beta3.0` / commit `505dbc40e6154c05158b5d0271c45f6a885a411b`
> Requirements: FR-015, NFR-008, NFR-011
> Related specs: [spec 10](../../specs/10-pure-organization-planning/spec.md) (planning diagnostics),
> [spec 13](../../specs/13-safe-layout-application/spec.md) (apply/recovery results),
> [organization-run-ux](../product/organization-run-ux.md) §7 (UX-side summary)

## 1. Problem and scope

外部 telemetry を前提にせず、organizer run の phase、error、plan/apply summary を
個人情報なしで診断する contract を定義する。対象は本repositoryのorganizer全体
（planning、application、recovery、UI run flow）が生成する実行時diagnosticであり、
その収集・保持・出力の境界を固定する。

**In scope**

- run event のtyped field とclosedな code 集合（§3–§6）。
- 取得データのredaction/classification 規則（§7）。
- local retention、user-visible export、logcat behavior（§8–§10）。
- crash/recovery/process-death 後のrun相関（§11）。
- network/telemetry default-off boundary（§12）。
- 代表的なdiagnostic fixture とnegative fixture（§13）。
- 実装Issueへのhandoff要件（§14）。

**Out of scope（本契約が意図的に扱わないもの）**

- telemetry SDK、network transport、その権限。default off かつ実装しない（§12）。
- planner/apply の公開type 変更。[spec 10](../../specs/10-pure-organization-planning/spec.md)
  と[spec 13](../../specs/13-safe-layout-application/spec.md) のresult type は
  diagnostic の入力としてそのまま消費する。
- user-facing reason の文言とlocalized表現。[organization-run-ux](../product/organization-run-ux.md)
  がUX契約の正本である（§2）。
- lock authoring、rule import/export 等のrun以外の操作のdiagnostic。run journal は
  organization run / recovery 操作だけを記録する。
- performance 計測protocol本体（[Issue #15](https://github.com/nunu1733/NunuLauncher/issues/15)）。
  ただしphase timestamp は計測に再利用できる（§14）。

## 2. Two layers: user-facing reason and developer diagnostic

診断には性質の異なる2層があり、互いに代替しない。

| Layer | 正本 | 内容 | 本契約の役割 |
|---|---|---|---|
| User-facing reason (FR-015) | planner/apply の公開result type（`PreserveReason`、`PlacementCode`、`WarningCode`、`RejectionReason`、`PreWriteRejection` 等）と[organization-run-ux](../product/organization-run-ux.md) §4 | 各移動・folder化・未配置・fallback の理由をUIがtyped code から表示する | 消費方法の定義のみ。diagnostic がUI表示を置き換えない |
| Developer diagnostic (NFR-011) | 本書 §3 以降のrun event | run ID、phase、error category、plan/apply summary のみのstructured record | field・redaction・retention・出力の契約 |

分離の規則:

- UI はuser-facing reason をresult type から直接組み立てる。journal を経由しない。
- journal はdeveloper diagnostic のみを含み、user-facing 文言・localized string を持たない。
- 「詳細はdiagnostic を見てほしい」はuser-facing reason の欠落の言い訳にしない。

## 3. Run event model

diagnostic の単位は **run event** である。1 event は1つのphase 遷移に対応し、
append-only の **run journal** へ書かれる。journal はapp-private な永続store に
保持され（§8）、process restart をまたいで読める。

```text
RunEvent {
    schemaVersion:  Int                  // journal format version; 本契約は 1
    journalSequence: Long                // journal 全体で単調増加。restart 後も継続
    recordedAtWallMillis: Long           // device wall clock。順序判定はjournalSequence を使う
    correlation:     RunCorrelation
    phase:           PhaseCode           // §4
    applyStage:      ApplyStage?         // A0–A8。apply系event のみ（§4.2）
    error:           ErrorEntry?         // §5。terminal failure 系event のみ
    planSummary:     PlanSummary?        // §6.1。PLANNED / PLANNING_IMPOSSIBLE のみ
    applySummary:    ApplySummary?       // §6.2。APPLY_VERIFIED のみ
    recovery:        RecoveryContext?    // recovery 操作系event のみ（§4.3）
    reconciliation:  ReconciliationContext?  // RESTART_RECONCILED のみ（§11）
    versions:        RunVersions?        // RUN_STARTED のみ
    deviceProfile:   DeviceProfileSummary?   // RUN_STARTED のみ
}

RunCorrelation {
    runId:   RunId?        // organization run。spec 13 RunId（32 lowercase hex、random）
    trigger: Trigger?      // RUN_STARTED で確定し以降のrun event にecho する
    runMode: RunMode?      // spec 10 RunMode と同じ2値
    pointId: RecoveryPointId?   // checkpoint 以降のrun event とrecovery 操作event
}

Trigger = MANUAL_FULL | ONBOARDING_PROPOSAL | INCREMENTAL_PROPOSAL
RunMode = FULL_ORGANIZATION | INCREMENTAL_PLACEMENT

RunVersions { ruleVersion, taxonomyVersion, recoveryFormatVersion }   // 識別子のみ。内容なし
DeviceProfileSummary { columns, rows, hotseatSlots, orientation }     // 寸法のみ。座標なし
```

設計規則:

- **自由形式text field を持たない。** `schemaVersion` 以外の全field は数値、
  closed enum、またはrandom なopaque ID である。error code はenum 定数名のみ
  （§5）。この「表現不可能にする」構造がredaction の第一強制手段になる。
- `RunId` / `RecoveryPointId` はrandom 生成のopaque 値であり、layout 内容から
  導出されない。内容由来の識別子（`RevisionId` 等）はjournal へ書かない（§7）。
- event のjournal 追記はphase 遷移の一部として同期的に完了し、次の危険な段階
  （例: layout transaction）へ進む前にpersist する。これによりcrash 直前の
  最終event がcrash時点のphase を特定する（§11）。
- planner/apply の公開type は変更しない。journal はresult type を読んで
  event へ射影する専用のwriter を持ち、`DiagnosticParam` の値
  （`ItemParam`、`SpanParam`、`PageParam` 等）はjournal へコピーしない。

## 4. Phase taxonomy

### 4.1 PhaseCode（closed set）

[DESIGN.md](../../DESIGN.md) §6 のrun 状態、[organization-run-ux](../product/organization-run-ux.md)
§2 のstate machine、[spec 13](../../specs/13-safe-layout-application/spec.md) の
apply protocol に対応する。terminal はrun の終了を意味する。

| PhaseCode | 対応する観測点 | Terminal |
|---|---|---|
| `RUN_STARTED` | capture 開始。trigger/runMode/versions/deviceProfile を確定 | |
| `CAPTURED` | snapshot 取得完了 | |
| `PREVIEWED` | preview をuser に表示 | |
| `PLANNED` | plan 生成（planSummary 付き） | |
| `PLANNING_REJECTED` | planner `Rejected.Invalid`（error 付き） | ✓ |
| `PLANNING_IMPOSSIBLE` | planner `Rejected.Impossible`（planSummary 付き） | ✓ |
| `USER_CONFIRMED` | 明示的confirm（spec 13 A0 直前） | |
| `USER_CANCELLED` | preview/confirm でのcancel。書き込みなし | ✓ |
| `CHECKPOINTED` | recovery record が`READY`（pointId 付き） | |
| `CHECKPOINT_REJECTED` | `CHECKPOINT_CREATE_FAILED` / `CHECKPOINT_VALIDATE_FAILED` / `RECOVERY_POINT_ADMISSION_BLOCKED` | ✓ |
| `APPLY_NO_CHANGES` | apply結果 `NoChanges`。両DB無変更 | ✓ |
| `APPLY_REJECTED` | apply結果 `Rejected`（checkpoint 系以外）。stage はA0–A5 | ✓ |
| `CONCURRENT_RUN_REJECTED` | apply結果 `ConcurrentRun` | ✓ |
| `APPLY_COMMITTED` | layout transaction commit（`COMMITTED_UNVERIFIED`） | |
| `APPLY_VERIFIED` | apply結果 `Applied`（`VERIFIED`、applySummary 付き） | ✓ |
| `APPLY_ROLLED_BACK` | apply結果 `RolledBack` | ✓ |
| `APPLY_RECOVERED` | apply結果 `Recovered` | ✓ |
| `APPLY_UNRESOLVED` | apply結果 `Unresolved` | ✓ |
| `APPLY_RECOVERY_FAILED` | apply結果 `RecoveryFailed` | ✓ |
| `RECOVERY_REQUESTED` | 明示的recovery 開始（recovery context 付き） | |
| `RECOVERY_REJECTED` | recovery結果 `NotRestorable` | ✓ |
| `RECOVERY_RESTORED` | recovery結果 `Restored` | ✓ |
| `RECOVERY_FAILED` | recovery結果 `RestoreFailed` | ✓ |
| `RECOVERY_WRITER_BUSY` | recovery結果 `WriterBusy` | ✓ |
| `RECOVERY_CONCURRENT` | recovery結果 `ConcurrentRun` | ✓ |
| `RESTART_RECONCILED` | process 再起動後のreconciliation（§11） | |

`STALE_REVISION` は独立phase にしない。stale は`APPLY_REJECTED`
（applyStage=A2 またはA5）もしくは`RECOVERY_REJECTED` のerror code として
表現し、検出段階を`applyStage` で区別する。

### 4.2 ApplyStage

[spec 13](../../specs/13-safe-layout-application/spec.md) apply protocol の
`A0`–`A8` をそのまま使う。checkpoint 以降のapply 系event は発生時点のstage を
持つ。これにより「A2 のstale」と「A5 in-transaction recheck のstale」など、
同じerror code の検出位置が区別できる。

### 4.3 RecoveryContext

```text
RecoveryContext {
    pointId:          RecoveryPointId
    pointOriginRunId: RunId?      // recovery record が保持する作成run ID（存在すれば）
}
```

明示的recovery は新しいorganization run ではないため、独自のrunId を振らない。
`pointId` を相関key とし、`pointOriginRunId` で作成run へ遡る。spec 13 の
`RecoveryResult` はrunId を持たないが、journal 側はこの規則で足りるため
type 変更を要求しない。

## 5. Error categories

error は **family × code** の2 level で表現する。family はjournal 独自の区分で、
planning rejection とapplication failure を必ず区別する（Issue #16 受入条件）。

```text
ErrorEntry {
    family:          ErrorFamily
    code:            String        // 来源enum の定数名。closed set のみ（下表）
    reasonTotal:     Int?          // planner Invalid の複数reason の総数
    additionalCodes: [String]      // 同一family の残code。最大8件、超過分は切り捨て
}

ErrorFamily =
    | PLANNING_INVALID      // planner Rejected.Invalid（spec 10 V-01–V-20）
    | PLANNING_IMPOSSIBLE   // planner Rejected.Impossible（spec 10 V-21–V-22）
    | PRE_WRITE_REJECTED    // spec 13 PreWriteRejection（checkpoint 系を含む）
    | APPLY_FAILURE         // spec 13 ApplyFailure
    | RECOVERY_REJECTION    // spec 13 RecoveryRejection
    | RECOVERY_FAILURE      // spec 13 RecoveryFailure
    | CONCURRENT            // 同一module 内の操作競合
    | WRITER_BUSY           // 外部layout writer 競合
```

| Family | code の来源（enum 定数名をそのまま使用） |
|---|---|
| `PLANNING_INVALID` | spec 10 `RejectionCode`（`UNKNOWN_ITEM_KIND` … `UNKNOWN_CATEGORY`） |
| `PLANNING_IMPOSSIBLE` | spec 10 `UnplacedReason`（`EXCEEDS_GRID_DIMENSIONS` / `TARGET_UNAVAILABLE`） |
| `PRE_WRITE_REJECTED` | spec 13 `PreWriteRejection`（`INVALID_PLAN` … `WRITER_BUSY`） |
| `APPLY_FAILURE` | spec 13 `ApplyFailure`（`WRITE_FAILED` … `RECOVERY_STORE_FAILED`） |
| `RECOVERY_REJECTION` | spec 13 `RecoveryRejection`（`MISSING` … `ALREADY_RESTORED`） |
| `RECOVERY_FAILURE` | spec 13 `RecoveryFailure` |
| `CONCURRENT` / `WRITER_BUSY` | 固定code（`CONCURRENT_RUN` / `WRITER_BUSY`）。phase と重複するがerror としての検索性のために持つ |

規則:

- code は来源enum の定数名のみを許可し、値の検証はwriter 側で行う。
  未知のcode はjournal に書かず、`code="UNMAPPED"` + family のみ書く。
  新enum 定数はjournal schema 変更なしに追加できる。
- warning はerror ではない。`WarningCode` はplanSummary の件数集計のみに現れる（§6.1）。
- raw exception のmessage、class 名、stack trace はjournal に書かない。
  crash 自体はOS crash buffer と§11 の相関で扱う。

## 6. Summary counts

summary は count のみで構成する。項目別の内訳はclosed enum をkey とする件数map
のみ許可する。

### 6.1 PlanSummary（`PLANNED` / `PLANNING_IMPOSSIBLE`）

```text
PlanSummary {
    capturedItemCount, candidateItemCount: Int
    movedCount, preservedCount: Int
    preservedByReason:  Map<PreserveReason, Int>     // spec 10 PreserveReason
    newFolderCount, newPageCount: Int
    unplacedCount:      Int
    unplacedByReason:   Map<UnplacedReason, Int>
    warningByCode:      Map<WarningCode, Int>
    confidenceCounts:   Map<Confidence, Int>         // EXPLICIT / RULE / FALLBACK
}
```

`CategoryId` や項目単位の分類結果は含めない（§7）。fallback は
`confidenceCounts.FALLBACK` と`warningByCode.FALLBACK_CATEGORY` の件数で観測する。

### 6.2 ApplySummary（`APPLY_VERIFIED`）

```text
ApplySummary {
    preserveActionCount, updateActionCount, insertActionCount: Int
}
```

行内容、座標、folder 構造は含めない。`NoChanges` はapplySummary なしで
`APPLY_NO_CHANGES` phase のみで表現する。

## 7. Data classification and redaction

各data class のdefault 取り扱い。**Allowed** はjournal・logcat・export の全出力面で
既定で含めてよいもの、**Never** は既定では一切の出力面に含めないものである。
「既定」にはdebug build・release build の区別を含む。例外は本表を更新した上で
ADR またはspec の承認を必要とする。

| Data class | 例 | 取り扱い | 出力面での代替表現 |
|---|---|---|---|
| package 名 | `com.example.app` | **Never** | 件数のみ |
| component 名 / widget provider | `ComponentName` 文字列 | **Never** | 件数のみ |
| shortcut id、app pair snap token | `ShortcutId` 等 | **Never** | 件数のみ |
| item / folder / page タイトル、icon | user 由来text | **Never** | — |
| user / profile 識別子 | `UserHandle` serial、`ProfileId` 値 | **Never** | profile は件数（`profileCount` 相当はdeviceProfile に含めない。run単位の集計のみ） |
| layout 座標 | cell、span、rank、page order、folder rank | **Never** | 件数のみ |
| folder / app pair 構造 | membership、親子関係 | **Never** | `newFolderCount` 等の件数 |
| rule 内容 | `RuleSemantics` の値、rule file 中身 | **Never** | `ruleVersion` 識別子のみ |
| 項目単位の分類結果 | `CategoryDecision`、`CategoryId` | **Never** | `confidenceCounts` の件数 |
| planner 診断param | `DiagnosticParam`（`ItemParam`、`SpanParam`、`PageParam` 等） | **Never** | error code と件数のみ |
| 内容由来識別子 | `RevisionId`、`ItemId`、`PageId`、`FolderId`、digest | **Never** | 一致/不一致の結果（phase とerror code）のみ |
| crash 上情報 | exception message、stack trace | **Never**（journal） | OS crash buffer と§11 で相関 |
| random opaque ID | `RunId`、`RecoveryPointId` | **Allowed** | 相関key |
| 相関・状態 | trigger、runMode、phase、applyStage、lifecycle/authoritative state | **Allowed** | — |
| error / warning code | enum 定数名 | **Allowed** | — |
| 件数 | summary 全体 | **Allowed** | — |
| device 寸法 | columns、rows、hotseatSlots、orientation | **Allowed** | — |
| version 識別子 | app、journal schema、rule、taxonomy、recovery format | **Allowed** | — |
| 時刻 | `recordedAtWallMillis` | **Allowed** | 順序は`journalSequence` |
| 自由形式text | message、note、log 行 | **Never** | — |

強制手段:

1. **Schema による強制**: `RunEvent` に上記Never 分類を格納できるfield が存在しない
   （§3）。writer はresult type から§5–§6 の射影のみ行う。
2. **負のtest corpus**: §13 のnegative fixture を実装test で自動検証する
   （禁止文字列のnon-containment、field 閉包性）。
3. **recovery point / DB 内容の不複製**: journal はrecovery record のmanifest、
   digest、favorites 行、raw column 値を一切コピーしない。journal が持つのは
   `pointId`、lifecycle 遷移、typed error のみである（Issue #16 受入条件）。

## 8. Local retention

- journal はapp-private storage の専用store に保存する。recovery DB
  （[ADR-0003](../adr/0003-organizer-recovery-point-storage.md)）とは別resource であり、
  journal 爆発や破損がrecovery に影響しない。逆も同様である。
- 保持期間: **直近10 run 分のevent かつ7日**。両方の上限を超えた分から
  run 単位でFIFO 削除する。追加でjournal 全体の上限を**512 KiB** とし、
  超過時は最古のrun から削除する。
- **未解決run の保護**: 対応するrecovery record が`APPLYING` /
  `COMMITTED_UNVERIFIED` / `RESTORING` のrun のevent は、解決
  （terminal event、または**解決済み** `resultingLifecycle` を持つ
  `RESTART_RECONCILED` の追記）まで削除しない。`RESTART_RECONCILED` が
  in-flight lifecycleを報告する場合は保護を解除しない。recovery操作の
  pointId単位の保護も同じ条件で、同一pointIdのterminal recovery eventまたは
  解決済みreconciliationによってのみ解除する。これはspec 13 の「cleanup は
  unresolved point を消さない」規則との整合である。
- 削除は追記時のlazy 実行とし、alarm や常駐thread を使わない。
- journal store の破損はjournal を初期化して新規作成する。layout、recovery、
  設定へ影響しない。journal はfail-open（診断不能になってもrun を止めない）とし、
  journal 書き込み失敗はrun 自体の失敗にしない。
- backup: journal store は新規file であり、baseline のbackup 経路
  （Lawnchair ZIP の`getFiles()` allowlist、`res/xml/backupscheme.xml`）に含まれない。
  spec 13 AC-11 と同様に「backup/restore 周期にjournal が含まれない」ことを
  contract test で証明する。

## 9. User-visible export

- export は **user が明示的に開始する操作のみ**。設定UI の「診断データを書き出す」
  等から、SAF でuser が選択した保存先、またはshare sheet へ出力する。
- 出力内容はjournal の`RunEvent` 列にheader（app version、journal schemaVersion、
  export 時刻、`DeviceProfileSummary`）を付けたもののみ。**journal に存在しない
  追加field をexport 時に付与しない**。verbose mode やdebug-only 拡張は作らない。
  export はlive journalの安定snapshotを読むだけで、reset・prune・appendを実行しない。
  journal破損が初期化時に検出された場合は§8の隔離・新規journal作成が先に完了するため、
  exportはその空のpost-reset snapshotを出力する。この隔離はexport固有の副作用ではなく、
  diagnostics storeのfail-openな破損処理である。
- 形式はmachine-readable な行区切りJSON（§13 のfixture 形式）とし、
  §7 の分類をそのまま満たす。user が第三者へ共有する選択をした場合も、
  含まれる情報は本契約のfield 集合に等しい。
- export の書き出しにnetwork を使わない。user 以外の宛先への送信経路を持たない。

## 10. Logcat behavior

- organizer 由来の実行時log はdiagnostics logger を経由し、単一tag（例:
  `OrganizerDiag`）で出力する。既存のproduction logging は本契約の実装時に
  このwrapper へ統合する（本Issueではcode を変更しない）。
- 出力内容は`RunEvent` のsubset 射影のみ: `run=<runId> phase=<phase>
  [stage=<A0–A8>] [err=<family>.<code>] [counts...]`。
- level: phase 遷移は`DEBUG`、terminal failure 系（`*_REJECTED`、`*_FAILED`、
  `*_ROLLED_BACK`、`*_UNRESOLVED`）は`WARN`。release build ではterminal failure 系のみ。
- §7 の**Never 分類はbuild variant にかかわらず一切出力しない**。
  一時的なデバッグ用途でもlogcat へのraw 値出力は禁止する。検討が必要な場合は
  journal に許可field を追加する手続き（本表更新）を通す。
- logcat はjournal の代替ではなく、journal への追記を成功させた後に同一内容を
  出力する。logcat の欠落がjournal に影響しない。

## 11. Crash, recovery, and process-death correlation

同じrun のphase をprocess restart 後も必要範囲で相関できる仕組み（Issue #16 受入条件）:

1. **相関key の永続性**: `RunId` はrun 開始時にrandom 生成され、checkpoint 以降は
   recovery record 自体が保持する（spec 13 recovery record 構成）。
   journal event とrecovery record が同じ`runId` を参照できる。
2. **journal の生存**: journal はapp-private な永続store に追記されるため、
   process death で失われない。`journalSequence` がrestart 前後の順序を保持する。
3. **遷移前の同期的追記**: 危険操作の直前にphase event をpersist する
   （例: `APPLY_COMMITTED` は`COMMITTED_UNVERIFIED` mark の後に、検証前に書く）。
   crash 時のjournal の末尾event がcrash 時点のphase となる。
4. **RESTART_RECONCILED**: 再起動後のreconciliation は対象run ごとに次を記録する。

```text
ReconciliationContext {
    subjectRunId:     RunId
    priorLifecycle:   RecoveryLifecycle     // CREATING/READY/APPLYING/COMMITTED_UNVERIFIED/RESTORING
    classification:   ReconciliationClassification
    resultingLifecycle: RecoveryLifecycle   // reconciliation 後のstate
}

ReconciliationClassification =
    | PRE_STATE          // 未commit と判定
    | INTENDED_POST_STATE // commit 済みと判定
    | RECOVERY_TARGET_STATE // RESTORING 中のrecovery target 到達
    | NEITHER_RECOGNIZED  // 認識不能。 unresolved として保存
```

crash とrun の対応づけは、OS crash buffer の時刻とjournal の
`recordedAtWallMillis` / 末尾event の一致で行う。journal にstack trace や
crash 情報を複製しない。

## 12. Network and telemetry boundary

- organizer diagnostics は**外部transport を持たずdefault off**である（NFR-008）。
  telemetry SDK dependency、`INTERNET` 権限、送信interface を追加しない。
- diagnostics module はsink としてjournal とlogcat とexport のみを公開し、
  送信API を持たない。上記を満たす限り、誤ってtransport が生える場所がない。
- user-visible export（§9）はuser が開始しuser が保存先を選ぶ操作であり、
  telemetry ではない。それ以外のegress は存在しない。
- 将来telemetry を導入する場合は、新ADR と追跡Issue、明示的opt-in、
  default off の証明を要求する。その際も送信できるのは§7 でAllowed と
  されるfield 集合のみを上限とする。

## 13. Representative diagnostic fixtures

実装test とreview の基準に使う代表例。形式はexport と同じ行区切りJSON の要約
（省略記法）。§7 のNever 分類が一切現れないこと、phase/error/summary の
射影が正しいことを検証する。ID は全て例示用の合成値である。

### D-01: 手動全体整理の成功

```json
{"schemaVersion":1,"journalSequence":41,"phase":"RUN_STARTED","runId":"5f0a…","trigger":"MANUAL_FULL","runMode":"FULL_ORGANIZATION","versions":{"ruleVersion":"1","taxonomyVersion":"1"},"deviceProfile":{"columns":5,"rows":6,"hotseatSlots":5,"orientation":"PORTRAIT"}}
{"journalSequence":42,"phase":"CAPTURED"}
{"journalSequence":43,"phase":"PLANNED","planSummary":{"capturedItemCount":84,"candidateItemCount":0,"movedCount":61,"preservedCount":23,"preservedByReason":{"DOCK":5,"WIDGET":4,"LOCKED":3,"NON_TARGET":11},"newFolderCount":9,"newPageCount":1,"unplacedCount":0,"warningByCode":{},"confidenceCounts":{"EXPLICIT":2,"RULE":55,"FALLBACK":4}}}
{"journalSequence":44,"phase":"PREVIEWED"}
{"journalSequence":45,"phase":"USER_CONFIRMED"}
{"journalSequence":46,"phase":"CHECKPOINTED","pointId":"9c2e…"}
{"journalSequence":47,"phase":"APPLY_COMMITTED","applyStage":"A6"}
{"journalSequence":48,"phase":"APPLY_VERIFIED","applyStage":"A8","applySummary":{"preserveActionCount":23,"updateActionCount":61,"insertActionCount":0}}
```

### D-02: planning rejection（Invalid / V-04）

```json
{"journalSequence":51,"phase":"PLANNING_REJECTED","error":{"family":"PLANNING_INVALID","code":"BOUNDS_VIOLATION","reasonTotal":1}}
```

planner が返した`SpanParam` 等のparam 値はjournal に現れない。

### D-03: planning impossible（V-21）

```json
{"journalSequence":52,"phase":"PLANNING_IMPOSSIBLE","planSummary":{"capturedItemCount":10,"candidateItemCount":1,"movedCount":0,"preservedCount":10,"preservedByReason":{"STRUCTURAL":10},"newFolderCount":0,"newPageCount":0,"unplacedCount":1,"unplacedByReason":{"EXCEEDS_GRID_DIMENSIONS":1},"warningByCode":{},"confidenceCounts":{"RULE":10}}}
```

`PLANNING_IMPOSSIBLE` と`APPLY_*` の区別がplanning 系失敗とapplication 系失敗の
分離（§5）を示す。

### D-04: stale の検出段階の区別

```json
{"journalSequence":61,"phase":"APPLY_REJECTED","applyStage":"A2","error":{"family":"PRE_WRITE_REJECTED","code":"STALE_REVISION"}}
{"journalSequence":71,"phase":"APPLY_REJECTED","applyStage":"A5","error":{"family":"PRE_WRITE_REJECTED","code":"EXACT_PRECONDITION_FAILED"}}
```

### D-05: checkpoint 失敗

```json
{"journalSequence":81,"phase":"CHECKPOINT_REJECTED","applyStage":"A4","error":{"family":"PRE_WRITE_REJECTED","code":"CHECKPOINT_VALIDATE_FAILED"}}
```

### D-06: N番目write 失敗によるrollback

```json
{"journalSequence":91,"phase":"APPLY_ROLLED_BACK","applyStage":"A6","error":{"family":"APPLY_FAILURE","code":"WRITE_FAILED"}}
```

### D-07: commit 後process death とrestart reconciliation

```json
{"journalSequence":101,"phase":"APPLY_COMMITTED","applyStage":"A6","runId":"ab31…"}
{"journalSequence":102,"phase":"RESTART_RECONCILED","reconciliation":{"subjectRunId":"ab31…","priorLifecycle":"COMMITTED_UNVERIFIED","classification":"INTENDED_POST_STATE","resultingLifecycle":"VERIFIED"}}
```

### D-08: 明示的recovery の成功

```json
{"journalSequence":111,"phase":"RECOVERY_REQUESTED","correlation":{"pointId":"9c2e…"},"recovery":{"pointId":"9c2e…","pointOriginRunId":"5f0a…"}}
{"journalSequence":112,"phase":"RECOVERY_RESTORED","recovery":{"pointId":"9c2e…","pointOriginRunId":"5f0a…"}}
```

### D-09: negative fixture（出現してはならない内容）

次はjournal・logcat・export のいずれにも現れてはならない。test は
non-containment を検証する。

```text
"packageName":"com.example.social"      # package 名
"component":"com.example/.MainActivity" # component 名
"profileSerial":11                      # user/profile 識別子
"cell":{"x":2,"y":3}                    # layout 座標
"folderTitle":"Work"                    # タイトル
"rules":{"minGroupSize":2}              # rule 内容
"category":"TOOLS"                      # 項目単位の分類結果
"revision":"r-8f3c…"                    # 内容由来識別子
"message":"SQLException: near \"FROM\"" # 自由形式text / exception
"items":[{"id":"item-7"}]               # item 単位の列挙
```

### D-10: export file の形状

```json
{"header":{"appVersion":"…","journalSchemaVersion":1,"exportedAtWallMillis":…,"deviceProfile":{…}}}
{"schemaVersion":1,"journalSequence":41,"phase":"RUN_STARTED", …}
{"schemaVersion":1,"journalSequence":42,"phase":"CAPTURED", …}
```

## 14. Downstream handoff

本契約を実装する変更と担当。planner/apply の公開type 変更は要求しない。

| 変更 | 内容 | 実装先 |
|---|---|---|
| diagnostics module 新設 | `RunEvent` 型群、journal store（retention §8）、logger port、export writer、§13 fixture test | 新規Feature Issue（本Issueのmerge後に起票する） |
| run flow への組込み | 各phase 遷移でのevent 発行。§4 のphase とUX state machine の対応 | [#52](https://github.com/nunu1733/NunuLauncher/issues/52)（manual full）、[#53](https://github.com/nunu1733/NunuLauncher/issues/53)（onboarding）。package-event incrementalは[Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85)によりMVP外であり、Later capabilityを再開する新しいfeature Issueが必要である。 |
| applyStage の記録 | A0–A8 の各段階でstage を付けたevent | [#60](https://github.com/nunu1733/NunuLauncher/issues/60)（executor audit follow-up） |
| restart reconciliation event | `RESTART_RECONCILED` の発行 | #60 およびdiagnostics 実装Issue |
| export UI | 「診断データを書き出す」操作とSAF 出力 | diagnostics 実装Issue |
| logcat 統合 | organizer 実行時log の単一tag 化（§10） | diagnostics 実装Issue |
| 性能計測との共用 | phase event の`recordedAtWallMillis` をp50/p95 計測のdata source として再利用 | [#15](https://github.com/nunu1733/NunuLauncher/issues/15) |

注意:

- lock authoring（[#38](https://github.com/nunu1733/NunuLauncher/issues/38)）は
  organization run ではないためjournal 対象外。lock 由来のrun 結果のみ
  `LOCK_STATE_UNAVAILABLE` 等のcode として現れる。
- `RecoveryResult` にrunId がないことは§4.3 の規則で吸収するため、type 変更不要。

## 15. Acceptance criteria mapping

[Issue #16](https://github.com/nunu1733/NunuLauncher/issues/16) の受入条件と本書の対応。

| Issue #16 受入条件 | 本書の根拠 |
|---|---|
| package/component 名、user/profile identifier、layout 座標、rule 内容をdefault で生ログしない | §7 分類表、§3 のschema 強制、§10 logcat 規則、§13 D-09 |
| planning rejection とapplication failure を区別できる | §5 ErrorFamily（`PLANNING_*` vs `APPLY_*` / `RECOVERY_*`）、§4 phase、§13 D-02/D-03/D-06 |
| recovery point や DB 内容をdiagnostic へ複製しない | §7 強制手段3、§3 のfield 集合 |
| 同じ run の phase を process restart 後も必要範囲で相関できる | §11（runId 永続、journal 生存、同期追記、`RESTART_RECONCILED`）、§13 D-07 |
| user-facing reason と developer diagnostic を分離する | §2 の二層モデル |
| external transport はscope 外・default off | §12 |

Deliverable 対応: typed field（§3–§6）、redaction/classification table（§7）、
local retention / user-visible export / logcat behavior（§8–§10）、
crash/recovery/process-death correlation（§11）、network/telemetry default-off
boundary（§12）、representative diagnostic fixtures（§13）。

## 16. Verification of this contract

本Issue（research）の検証方法:

- **data-flow review**: planner/apply のresult type からjournal への射影
  （§5–§6）が全ての公開enum を漏れなく対応付けていることの確認。
- **redaction examples**: §7 の表と§13 D-01–D-08 の内容突き合わせ。
- **negative fixtures**: §13 D-09 を実装時のtest corpus とし、non-containment
  検証をdiagnostics 実装Issueの受入条件に含める。

実装時の検証はdiagnostics 実装Issueが持つ。本Issueではcode を変更しない。

## Change history

- 2026-08-15: Issue #16 のresearch成果物として初版。typed run event model、
  redaction/classification、retention/export/logcat、restart 相関、telemetry
  default-off 境界、fixtures を定義した。
