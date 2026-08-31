# Implementation Plan: Organizer input-unavailableの診断可能性

> Issue: #172
> Spec: [spec.md](./spec.md)
> Status: implementation（specはaccepted。rev 3指摘対応後、rev 4でerror codeパラメータを削除し実装と一致）

## Current evidence

確認済みの事実（commit `256fb6525d` 時点）:

- `ManualOrganizationRun.start()` は `OrganizationInputComposition.NotReady` を受けると `finish(operation, State.InputUnavailable(reason))` のみを行い、journal eventを発行しない
  （[ManualOrganizationRun.kt:210-213](../../../lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt)）。journalは `RUN_STARTED` のまま終わる。
- `LayoutWriterCanonicalCaptureSource.capture()` は `RuntimeException` を握り潰して `CanonicalCaptureReadResult.Invalid` を返す
  （[OrganizationInputComposer.kt:57-63](../../../lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt)）。例外の痕跡はどこにも残らない。
- composerは `NotReady` ごとに安定したdiagnostic code（kebab-case文字列）を既に生成している
  （`notReady(...)` 呼出し箇所、[OrganizationInputComposer.kt](../../../lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt)）。
- model未読込の経路は [LayoutApplicationModule.kt:111-123](../../../lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt) の `ReadinessGate.runWhenReady` であり、code `"reconciliation-pending"` / `"reconciliation-failed"` を返す。これもjournalに落ちない。
- **journal versioning（review Blocker 2の前提確認済み）**:
  - `RunEventSerializer` はstrict decode（`ignoreUnknownKeys=false`）で、`schemaVersion == 1` のみ受理する
    （[RunEventSerializer.kt:11-32](../../../lawnchair/src/app/lawnchair/organizer/diagnostics/journal/RunEventSerializer.kt)）。
  - `JournalStore` は既存journalを全件decodeし、**1件でもdecode失敗するとjournal全体をresetする**。sequenceは保持される
    （[JournalStore.kt:222-242,316-326](../../../lawnchair/src/app/lawnchair/organizer/diagnostics/journal/JournalStore.kt)）。
  - したがって新enum（`INPUT_NOT_READY` / `INPUT_READINESS`）を含むjournalを旧buildで開くと「読み飛ばし」ではなくjournal resetになる。この挙動をspecはAC-7で受入条件として規定する。既存契約の「新enum定数はjournal schema変更なしに追加できる」は `ErrorEntry.code` のString値の規定であり、serialized enum値の追加には適用されない。
  - [ExportWriter.kt:35](../../../lawnchair/src/app/lawnchair/organizer/diagnostics/export/ExportWriter.kt) も同一のstrict decodeである（exportは自buildのjournal snapshotを読むため、自buildが書いた値は必ずdecodeできる）。
- **logger seam（review Major 1の前提確認済み）**: `DiagnosticsLogger.log(event: RunEvent)` はjournal persist後の呼出しを前提とし、terminal failure集合以外はrelease buildで出力をskipする
  （[DiagnosticsLogger.kt:60-76](../../../lawnchair/src/app/lawnchair/organizer/diagnostics/logger/DiagnosticsLogger.kt)）。capture exceptionはjournal event以前のcapture siteで発生するため、現行APIでは表現できない。`DiagnosticsLogger` 自体の変更が本planのchange setに含まれる。
- 推測（未確認）: #171の一回性episodeの理由。capture側例外（例: CursorWindow超過）か、gate/別sourceの遅延かは、production証拠が無いため特定できない。これがAC-1/AC-2の動機である。

## Design

### Modules and interfaces

| Module | 変更 |
|---|---|
| `diagnostics/model` | `PhaseCode` へ `INPUT_NOT_READY`（terminal）を追加。`ErrorFamily` へ `INPUT_READINESS` を追加し、`validCodesForFamily` が新closed enum `InputCompositionCode` の定数名 + `"UNMAPPED"` を返す実装を追加 |
| `diagnostics/logger/DiagnosticsLogger.kt` | 専用API `logCaptureFailure(exceptionClass: Class<out Throwable>)` を追加。**String型パラメータを持たない**: class名はlogger内部で `simpleName` 化する（platformにtypedな数値error code accessorが存在しないため数値codeは運ばない）。debug buildでのみDEBUG levelで出力し、release buildでは何も出力しない。`terminalFailurePhases` に `INPUT_NOT_READY` を追加しWARN扱いにする（journal由来eventの従来経路） |
| `integration` | 新closed enum `InputCompositionCode`（16値、SCREAMING_SNAKE）を定義。`CompositionDiagnostic.code` の型をこれへ変更（既存kebab文字列との対応はAC-1の対応表testで固定）。`CanonicalCaptureReadResult` 失敗時にobserverへ通知する最小の注入点を `LayoutWriterCanonicalCaptureSource` に追加（production wiringは `LayoutApplicationModule` が `DiagnosticsLogger.logCaptureFailure` へ接続）。**`InputReadinessReason` とcomposerの判定ロジックは変更しない** |
| `diagnostics/projection` | `InputReadinessProjection`（新規）: `OrganizationInputComposition.NotReady` → `ErrorEntry(INPUT_READINESS, code)` への射影。codeは既に `InputCompositionCode` 型であるため対応表は不要。将来の未知code（外部由来）に備え `UNMAPPED` へのfallthroughを検証するtestを持つ |
| `organizer/ui` | `ManualOrganizationRun.start()` の `NotReady` 分岐で、`finish` 前に `INPUT_NOT_READY` eventをemit。既存の `emit()` のfail-open性を踏襲 |
| `ui/preferences` | `State.InputUnavailable` の表示copyをreason区分で切替（`ReconciliationPending` → 再試行系 / その他 → 報告系）。string resourcesにen/jaを追加 |
| `docs/engineering/organizer-diagnostics.md` | §3にserialized enum追加のversioning規定（upgrade可/downgrade時はjournal reset）、§4.1に `INPUT_NOT_READY` 行、§5にfamily/code来源（`InputCompositionCode` + `UNMAPPED`）と16値一覧、§7にcapture側例外の限定例外行（class名+正規化error code、debug build限定、message/stack traceはNeverのまま）、§10にWARN集合とdebug例外行の規則、§13にfixture例とnegative fixture拡張 |

- **journal理由コードの正本は `InputCompositionCode` 一つ**である。`CompositionDiagnostic.code` とjournalの `ErrorEntry.code` が同一集合を参照し、driftは `validCodesForFamily` の実行時validationで検出される。
- capture失敗の観測は `RunEvent` に例外情報を載せない。observer注入点 → `logCaptureFailure` の型境界（`Class<out Throwable>` のみ）で完結させ、`RunEvent` schema・schemaVersionは不変である。文字列型パラメータが存在しないため、呼出側がmessageやlayout由来の文字列を渡せる経路はAPIレベルで存在しない（fixture依存ではなく構造的保証）。
- capture失敗時にjournalへ書く理由コードはcomposerの `NotReady(InvalidCanonicalCapture(CAPTURE_UNAVAILABLE))` が運ぶ `CAPTURE_INVALID` であり、例外情報とjournalが重複しない。

### Closed code set（正式値）

既存kebab-code → `InputCompositionCode`（AC-1の対応表testが固定する）:

| 既存code | 正式値 |
|---|---|
| `reconciliation-pending` | `RECONCILIATION_PENDING` |
| `reconciliation-failed` | `RECONCILIATION_FAILED` |
| `capture-invalid` | `CAPTURE_INVALID` |
| `capture-unknown-lock` | `CAPTURE_UNKNOWN_LOCK` |
| `capture-unrepresentable` | `CAPTURE_UNREPRESENTABLE` |
| `bundle-missing` | `BUNDLE_MISSING` |
| `bundle-corrupt` | `BUNDLE_CORRUPT` |
| `bundle-unsupported` | `BUNDLE_UNSUPPORTED` |
| `bundle-invalid` | `BUNDLE_INVALID` |
| `override-unreadable` | `OVERRIDE_UNREADABLE` |
| `override-unsupported-schema` | `OVERRIDE_UNSUPPORTED_SCHEMA` |
| `override-category-invalid` | `OVERRIDE_CATEGORY_INVALID` |
| `evidence-unreadable` | `EVIDENCE_UNREADABLE` |
| `signal-contradiction` | `SIGNAL_CONTRADICTION` |
| `target-partition` | `TARGET_PARTITION` |
| `dynamic-cut-unstable` | `DYNAMIC_CUT_UNSTABLE` |

### Data flow

```text
start() → RUN_STARTED
  → composeManualFullOrganizationInput()
      gate not READY → NotReady(ReconciliationPending/Failed) ─┐
      gate READY → composer
        capture throws → logCaptureFailure(class, code?) [debug buildのみ]
                       → NotReady(CAPTURE_UNAVAILABLE) ─────────┤
        その他のNotReady ────────────────────────────────────────┤
      → emit INPUT_NOT_READY { error: (INPUT_READINESS, InputCompositionCode) } ←
      → finish(State.InputUnavailable(reason))   // UI state・retryは従来どおり
```

### Alternatives rejected

- **raw `Throwable.message` のlogcat出力**: 任意のmessageがlayout内容・package名等を含まない保証がなく、§7のNever分類（自由形式text）と両立しない。class名+数値error codeで#171種の診断（例: `SQLiteBlobTooBigException`）は十分である。
- **`logCaptureFailure` を文字列パラメータ（`exceptionClassName: String`）で定義する**: 「typed accessorから取得済みだから安全」というcaller convention依存になり、別callerがmessage・package・layout由来の文字列を渡せてしまう。privacy保証をfixtureではなく構造的に成立させるため、`Class<out Throwable>` のみを受ける型境界とする。数値error code（`Int?`）も検討したが、API 36.1のplatform `SQLiteException` はtypedなerror code accessorを持たず（`javap` で確認）、productionで値を埋める手段が存在しないため削除した。
- **schemaVersion 2への引き上げ / 新旧mixed-version journal**: `RunEventSerializer` はv1のみ受理し、JournalStoreは全件decodeする。v2 eventの混在は現行実装でもdecode失敗（journal reset）であり、旧buildとの互換は解決しない。retentionで物理的に短命なdiagnostics journalのdowngrade時resetは、corruption isolation（既存§8）と同じfail-open挙動として受入する方が実装差分が小さい。
- **`InputReadinessReason` 系ごとの新ErrorFamily / `additionalCodes` にsource kindを混在**: `ErrorEntry` のvalidation（同一familyのcodeのみ許容）と§5の「codeは来源enum定数名」規則に反する。
- **kebab-case文字列を正式closed値とする**: serialized diagnostic codeは外部観測可能な契約であり、§5の「来源enumの定数名」規約に沿ってSCREAMING_SNAKEのenum定数名を正式値とする。kebab文字列は内部実装詳細から正式値への対応表testで退避する。
- **capture例外を `CompositionDiagnostic` に載せてrun側からlogする**: 例外情報がUI state経由のsurfaceに触れ、journalへ誤って流れる経路を作る。capture siteでのみ観測する方がseamが小さい。
- **journalに例外class名・error codeを書く**: §7のNever分類（crash上情報）に当たり、exportにまで流れる。debug logcat限定の例外承認にとどめる。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `diagnostics/model/PhaseCode.kt` | `INPUT_NOT_READY` 追加（terminal） | 契約§4.1のclosed集合の実装先 |
| `diagnostics/model/ErrorEntry.kt` | `ErrorFamily.INPUT_READINESS` + `validCodesForFamily` 拡張 | 契約§5の実装先 |
| `diagnostics/logger/DiagnosticsLogger.kt` | `logCaptureFailure` typed API追加、`INPUT_NOT_READY` をterminal failure集合へ追加 | capture siteの観測先。`RunEvent` 経由では表現できない（release gatingもここ） |
| `diagnostics/projection/InputReadinessProjection.kt` | 新規射影 | journalへの射影は既存projection群と同じ層に置く |
| `organizer/ui/ManualOrganizationRun.kt` | `NotReady` 分岐でのevent emit | phase遷移の一部として同期的にjournalへ書く（契約§3） |
| `organizer/integration/OrganizationInputComposer.kt` | `InputCompositionCode` 定義、`CompositionDiagnostic.code` の型変更、capture失敗observer注入（composer判定は不変） | 失敗箇所コードの正本と発生源での観測 |
| `application/protocol/LayoutApplicationModule.kt` | observerのproduction wiring | production adapterの組立て場所 |
| `ui/preferences/destinations/ManualOrganizationPreferences.kt` | copy区分 | FR-015のuser-facing reasonはUIが組み立てる（契約§2） |
| `lawnchair/res/values{,-ja}/strings.xml` | copy追加 | 既存manual organizer文字列と同じ場所 |
| `docs/engineering/organizer-diagnostics.md` | §3/§4.1/§5/§7/§10/§13更新 | 契約の正本 |
| tests（unit: ui, diagnostics model/logger/journal, integration） | AC-1/2/5/6/7のtest | テスト規約（interface経由、failure注入、negative fixture、corruption fixture） |

## Migration and recovery

- schema/rule migration: none。journalは `RunEvent` field集合・schemaVersion 1不変のままのenum定数追加である。
- **upgrade（旧journal → 新build）**: 既存eventのみで構成されるjournalは新buildでdecode可能。fixture testで検証する。
- **downgrade（新journal → 旧build）**: 未知のserialized enum値によりdecodeが失敗し、既存のcorruption-isolation（journal全体reset、sequence保持、layout DB/recovery store/設定は無影響）でjournalが初期化される。仕様どおりの挙動であり、`JournalStore` の変更は不要である。失うのはdiagnostics journalのみ（retention上、直近10 run・7日以内）である。
- failure中のrollback: 該当なし（write系変更なし）。`INPUT_NOT_READY` emitは既存 `emit()` と同様にfail-openであり、journal書込失敗がrunを失敗させない。
- backup/restore compatibility: journalはbackup対象外（既存契約）のまま。変更なし。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | `InputCompositionCode` 対応表unit test（16値×既存code×`InputReadinessReason` 系）、`ManualOrganizationRunTest` 追加（`INPUT_NOT_READY` event、terminal性、理由コード、fail-open）、`ModelValidationTest` のclosed集合検証 | `./gradlew :lawnchair:testLawnWithQuickstepGithubDebugUnitTest` |
| AC-2 | capture failure注入test: 例外→`Invalid` + `logCaptureFailure(class)` 呼出し、class名のみのassert、messageのnon-containment、release buildでskipされるassert、composer返却不変のassert | 同上（unit） |
| AC-3 | エミュレータ `nunu_qpr2_api36_1`: #168/#171手順でNova restore→同一processでorganizer起動→`INPUT_NOT_READY` 理由コード・capture例外の取得。`docs/assessment/issue-172-input-unavailable-diagnostics.md` に記録 | 手動（エミュレータ）。debug build |
| AC-4 | UI compose test（`RECONCILIATION_PENDING`→再試行系copy、他理由→報告系copy）、en/ja string確認 | unit test + resource review |
| AC-5 | journal/export negative fixture test拡張（例外message・stack trace・digest・package名等のnon-containment、`INPUT_NOT_READY` event含む） | 同unit test |
| AC-6 | 既存organizer test群の無変更通過、`spotlessCheck`、debug build | `./gradlew spotlessCheck` `./gradlew assembleLawnWithQuickstepGithubDebug` |
| AC-7 | fixture test: 未知enum値を含むevent行でjournal全体reset・sequence保持・他store無影響を検証（既存corruption系testの拡張、`JournalStoreTest`）。upgrade方向は既存fixtureのdecode成功で検証 | 同unit test |

含める観点: unit/contract（上表）、failure injection（AC-2の例外注入）、UI（AC-4のcopyとfocus/liveRegion維持）、privacy negative fixture（AC-5）、corruption/versioning fixture（AC-7）。エミュレータでのAC-3は#171のassessment手順を再利用する。

リスク評価: layout DB・recovery・migrationへの変更はなく、diagnostics modelのenum追加・logger API追加・UI copy追加が中心。`risk: layout-data` / `risk: migration` には該当しない見込み。ただし `ManualOrganizationRun` は高リスクpath近傍のため、PRでは該当labelの判断を明示する。

## Documentation updates

- [ ] spec status/history（承認時に `accepted`、実装完了時に `implemented`）
- [ ] CONTEXT.md — 不要（新domain語はdiagnostics契約側）
- [ ] DESIGN.md — 不要（§4.5/§163は契約文書へ委譲済み）
- [ ] docs/engineering/organizer-diagnostics.md — §3/§4.1/§5/§7/§10/§13を同じPRで更新（versioning規定を含む）
- [ ] ADR — 不要と判断（契約表更新は既定の手続きであり、変更困難な設計判断に該当しない）
- [ ] AGENTS.md — 検証command追加なし

## Execution checklist

- [ ] Spec再レビュー・承認（statusを `accepted` へ更新）。
- [x] 現行挙動の再現: `NotReady` でjournalが `RUN_STARTED` のまま終わることをtestで固定（失敗するtestを先に追加）。
- [x] `InputCompositionCode`（16値）・`INPUT_NOT_READY`・`INPUT_READINESS`・射影・run emitを最小縦切りで実装し、対応表testを通す。
- [x] `DiagnosticsLogger.logCaptureFailure` とcapture失敗observerのwiringを実装し、failure注入test（debug/release差込み含む）を通す。
- [x] copy区分とstring resources（en/ja）を実装し、UI testを通す（instrumentation `ManualOrganizationPreferencesInstrumentationTest` にcopy区分test追加。実行はconnected lane）。
- [x] organizer-diagnostics.md更新（versioning規定を含む）を同じPRで行う。
- [x] AC-7のcorruption/versioning fixture testを通す。
- [ ] エミュレータでのAC-3実施とassessment文書作成。必要ならfocused fix Issueを起票。
- [ ] 全検証結果をPRへ記録し、残存リスクを明記。
