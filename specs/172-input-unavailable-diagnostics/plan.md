# Implementation Plan: Organizer input-unavailableの診断可能性

> Issue: #172
> Spec: [spec.md](./spec.md)
> Status: draft（spec承認待ち）

## Current evidence

確認済みの事実（commit `256fb6525d` 時点）:

- `ManualOrganizationRun.start()` は `OrganizationInputComposition.NotReady` を受けると `finish(operation, State.InputUnavailable(reason))` のみを行い、journal eventを発行しない
  （[ManualOrganizationRun.kt:210-213](../../../lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt)）。journalは `RUN_STARTED` のまま終わる。
- `LayoutWriterCanonicalCaptureSource.capture()` は `RuntimeException` を握り潰して `CanonicalCaptureReadResult.Invalid` を返す
  （[OrganizationInputComposer.kt:57-63](../../../lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt)）。例外の痕跡はどこにも残らない。
- composerは `NotReady` ごとに安定したdiagnostic code（`"capture-invalid"`, `"capture-unknown-lock"`, `"capture-unrepresentable"`, `"bundle-missing"`, `"bundle-corrupt"`, `"bundle-unsupported"`, `"bundle-invalid"`, `"override-unreadable"`, `"override-unsupported-schema"`, `"override-category-invalid"`, `"evidence-unreadable"`, `"signal-contradiction"`, `"target-partition"`, `"dynamic-cut-unstable"`）を既に生成している
  （[OrganizationInputComposer.kt](../../../lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt) の `notReady(...)` 呼出し箇所）。
- model未読込の経路は [LayoutApplicationModule.kt:111-123](../../../lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt) の `ReadinessGate.runWhenReady` であり、`NotReady(ReconciliationPending | ReconciliationFailed)` + code `"reconciliation-pending"` / `"reconciliation-failed"` を返す。これもjournalに落ちない。
- `PhaseCode`（[PhaseCode.kt](../../../lawnchair/src/app/lawnchair/organizer/diagnostics/model/PhaseCode.kt)）と `ErrorFamily`（[ErrorEntry.kt](../../../lawnchair/src/app/lawnchair/organizer/diagnostics/model/ErrorEntry.kt)）はclosed enum。`ErrorEntry.code` の許容集合は `validCodesForFamily` が来源enumから導出する。
- `DiagnosticsLogger` は単一tag `OrganizerDiag` で、terminal failure系phaseをWARN、それ以外をDEBUG、release buildではterminal failure系のみ出力する（[DiagnosticsLogger.kt](../../../lawnchair/src/app/lawnchair/organizer/diagnostics/logger/DiagnosticsLogger.kt)）。terminal failure集合はphase名の明示リスト。
- UIは `State.InputUnavailable` に対して単一の固定copy（`R.string.manual_organization_input_unavailable`）+ retryを表示する
  （[ManualOrganizationPreferences.kt:109-118](../../../lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt)）。文字列は `lawnchair/res/values/strings.xml:1002` と `values-ja/strings.xml:102` にある。
- 推測（未確認）: #171の一回性episodeの理由。capture側例外（例: CursorWindow超過）か、gate/別sourceの遅延かは、production証拠が無いため特定できない。これがAC-1/AC-2の動機である。

## Design

### Modules and interfaces

| Module | 変更 |
|---|---|
| `diagnostics/model` | `PhaseCode` へ `INPUT_NOT_READY`（terminal）を追加。`ErrorFamily` へ `INPUT_READINESS` を追加し、`validCodesForFamily` に新closed集合 `InputCompositionCode` の定数名を返す実装を追加 |
| `integration` | composerのdiagnostic code文字列をclosed sourceへ昇格。`CanonicalCaptureReadResult` の失敗観測用に、capture失敗をobserverへ通知する最小の注入点を `LayoutWriterCanonicalCaptureSource` に追加（production wiringは `LayoutApplicationModule` がdiagnostics loggerへ接続）。**`InputReadinessReason` とcomposerの判定ロジックは変更しない** |
| `diagnostics/projection` | `InputReadinessProjection`（新規）: `OrganizationInputComposition.NotReady` → `ErrorEntry(INPUT_READINESS, code)` への射影。codeはcomposerのclosed集合から対応付け、未知codeは `UNMAPPED` |
| `organizer/ui` | `ManualOrganizationRun.start()` の `NotReady` 分岐で、`finish` 前に `INPUT_NOT_READY` eventをemit。既存の `emit()` のfail-open性を踏襲 |
| `ui/preferences` | `State.InputUnavailable` の表示copyをreason区分で切替（`ReconciliationPending` → 再試行系 / その他 → 報告系）。string resourcesにen/jaを追加 |
| `docs/engineering/organizer-diagnostics.md` | §4.1に `INPUT_NOT_READY` 行、§5にfamily/code来源、§7にcapture側例外の限定例外行、§10にWARN集合とdebug例外行の規則、§13にfixture例とnegative fixture拡張 |

- composerのdiagnostic codeは `Integration` 側のclosed定義（enum定数または同等のclosed集合）とし、`CompositionDiagnostic.code` とjournal出力が同一の集合を参照する。journal側の検証は `validCodesForFamily(INPUT_READINESS)` が行うため、driftは実行時validationで検出される（§5の規則どおり未知codeは `UNMAPPED`）。
- capture失敗の観測は `RunEvent` に例外情報を載せない。observer注入点（引数付きlog行のみ）で完結させ、`RunEvent` schemaは不変である。
- test用に新interfaceを増やさない: observerは既存production wiringの一部であり、unit testでは既存のcomposer fixture経由で検証する。

### Data flow

```text
start() → RUN_STARTED
  → composeManualFullOrganizationInput()
      gate not READY → NotReady(ReconciliationPending/Failed) ─┐
      gate READY → composer
        capture throws → observer(DEBUG log) → NotReady(CAPTURE_UNAVAILABLE) ─┤
        その他のNotReady ─────────────────────────────────────────────────────┤
      → emit INPUT_NOT_READY { error: (INPUT_READINESS, closed code) } ←──────┘
      → finish(State.InputUnavailable(reason))   // UI state・retryは従来どおり
```

### Alternatives rejected

- **`InputReadinessReason` 系ごとの新ErrorFamily / `additionalCodes` にsource kindを混在**: `ErrorEntry` のvalidation（同一familyのcodeのみ許容）と§5の「codeは来源enum定数名」規則に反する。既存schemaを広げないため、composerの既存code集合を昇格する方式を採る。
- **`CompositionDiagnostic.code` をそのまま（kebab-case文字列のまま）journalに書く**: 契約はclosed集合と `UNMAPPED` fallbackを要求するが、文字列のままでも実現可能である。enum化するか文字列closed集合のままにするかは実装時に既存testとの影響が最小の方を採る（specは性質のみ固定）。
- **capture例外を `CompositionDiagnostic` に載せてrun側からlogする**: 例外情報がUI state経由のsurfaceに触れ、journalへ誤って流れる経路を作る。capture siteでのみ観測する方がseamが小さい。
- **journalに例外class/messageを書く**: §7のNever分類（crash上情報）に当たり、exportにまで流れる。logcat限定の例外承認にとどめる。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `diagnostics/model/PhaseCode.kt` | `INPUT_NOT_READY` 追加（terminal） | 契約§4.1のclosed集合の実装先 |
| `diagnostics/model/ErrorEntry.kt` | `ErrorFamily.INPUT_READINESS` + `validCodesForFamily` 拡張 | 契約§5の実装先 |
| `diagnostics/projection/InputReadinessProjection.kt` | 新規射影 | journalへの射影は既存projection群と同じ層に置く |
| `organizer/ui/ManualOrganizationRun.kt` | `NotReady` 分岐でのevent emit | phase遷移の一部として同期的にjournalへ書く（契約§3） |
| `organizer/integration/OrganizationInputComposer.kt` | capture失敗observer注入（composer判定は不変） | 例外の発生源でのみ観測する |
| `application/protocol/LayoutApplicationModule.kt` | observerのproduction wiring | production adapterの組立て場所 |
| `ui/preferences/destinations/ManualOrganizationPreferences.kt` | copy区分 | FR-015のuser-facing reasonはUIが組み立てる（契約§2） |
| `lawnchair/res/values{,-ja}/strings.xml` | copy追加 | 既存manual organizer文字列と同じ場所 |
| `docs/engineering/organizer-diagnostics.md` | §4.1/§5/§7/§10/§13更新 | 契約の正本 |
| tests（unit: ui, diagnostics, integration） | AC-1/2/5/6のtest | テスト規約（interface経由、failure注入、negative fixture） |

## Migration and recovery

- schema/rule migration: none。journalはappend-onlyのenum定数追加であり、schemaVersion不変。旧journalの読取・exportに影響しない。
- failure中のrollback: 該当なし（write系変更なし）。`INPUT_NOT_READY` emitは既存 `emit()` と同様にfail-openであり、journal書込失敗がrunを失敗させない。
- release rollback/downgrade: 旧buildは未知phase `INPUT_NOT_READY` を持つjournalを読まない（exportも自buildのjournal snapshotのみ）。既存のjournal読取が未知enum値に遭遇した場合の扱いは既存robustness testに従う。
- backup/restore compatibility: journalはbackup対象外（既存契約）のまま。変更なし。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | `InputReadinessReason`→closed codeの全variant unit test、`ManualOrganizationRunTest` 追加（`INPUT_NOT_READY` event、terminal性、理由コード、fail-open）、journal/validation系test | `./gradlew :lawnchair:testLawnWithQuickstepGithubDebugUnitTest` |
| AC-2 | capture failure注入test: 例外→`Invalid` + DEBUG log行（exception class/messageのみ、layout内容non-containment）、composer返却不変のassert | 同上（unit） |
| AC-3 | エミュレータ `nunu_qpr2_api36_1`: #168/#171手順でNova restore→同一processでorganizer起動→`INPUT_NOT_READY` 理由コード・capture例外の取得。`docs/assessment/issue-172-input-unavailable-diagnostics.md` に記録 | 手動（エミュレータ）。debug build |
| AC-4 | UI compose test（`ReconciliationPending`→再試行系copy、他理由→報告系copy）、en/ja string確認 | unit test + resource review |
| AC-5 | journal/export negative fixture test拡張（例外text・digest・package名等のnon-containment、`INPUT_NOT_READY` event含む） | 同unit test |
| AC-6 | 既存organizer test群の無変更通過、`spotlessCheck`、debug build | `./gradlew spotlessCheck` `./gradlew assembleLawnWithQuickstepGithubDebug` |

含める観点: unit/contract（上表）、failure injection（AC-2の例外注入）、UI（AC-4のcopyとfocus/liveRegion維持）、privacy negative fixture（AC-5）。エミュレータでのAC-3は#171のassessment手順を再利用する。

リスク評価: layout DB・recovery・migrationへの変更はなく、diagnostics modelのenum追加とUI copy追加が中心。`risk: layout-data` / `risk: migration` には該当しない見込み。ただし `ManualOrganizationRun` は高リスクpath近傍のため、PRでは該当labelの判断を明示する。

## Documentation updates

- [ ] spec status/history（承認時に `accepted`、実装完了時に `implemented`）
- [ ] CONTEXT.md — 不要（新domain語はdiagnostics契約側）
- [ ] DESIGN.md — 不要（§4.5/§163は契約文書へ委譲済み）
- [ ] docs/engineering/organizer-diagnostics.md — §4.1/§5/§7/§10/§13を同じPRで更新
- [ ] ADR — 不要と判断（契約表更新は既定の手続きであり、変更困難な設計判断に該当しない）
- [ ] AGENTS.md — 検証command追加なし

## Execution checklist

- [ ] Spec承認（statusを `accepted` へ更新）。
- [ ] 現行挙動の再現: `NotReady` でjournalが `RUN_STARTED` のまま終わることをtestで固定（失敗するtestを先に追加）。
- [ ] closed code集合・`INPUT_NOT_READY`・射影・run emitを最小縦切りで実装。
- [ ] capture失敗observerとdebug logを実装し、failure注入testを通す。
- [ ] copy区分とstring resources（en/ja）を実装し、UI testを通す。
- [ ] organizer-diagnostics.md更新を同じPRで行う。
- [ ] エミュレータでのAC-3実施とassessment文書作成。必要ならfocused fix Issueを起票。
- [ ] 全検証結果をPRへ記録し、残存リスクを明記。
