# Implementation Plan: Preserve two-panel orientation in organizer canonical capture

> Issue: #130
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

- [LauncherLayoutAdapter.capabilities()](../../lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt)は`Configuration.orientation`のみを見て`PORTRAIT` / `LANDSCAPE`へ写像し、`TWO_PANEL_*`を出力しない。
- 権威chain(確認済み、baseline `505dbc40e6154c05158b5d0271c45f6a885a411b`): `DisplayController.Info.getDeviceType()`がsupported boundsのphone/tablet両mode併存で`TYPE_MULTI_DISPLAY` → `InvariantDeviceProfile.deviceType`(static) → 全`supportedProfiles`が`.setIsMultiDisplay(deviceType == TYPE_MULTI_DISPLAY)`で構築 → `DeviceProfile.isTwoPanels = isMultiDisplay`。`mTypeIndex`は`isTwoPanels`とwindow bounds向きで`INDEX_TWO_PANEL_*`へ分岐。
- 下流は既存で対応済み: composerのpublic→planning写像、UI summary折り畳み、revision encoding(`CanonicalMarshalling`がorientation ordinalをdigestへ)、recovery `DEVICE_PROFILE` resource照合(`ContextResourceCodec`)。
- #108 device evidence: Pixel Tablet / Pixel 9 Pro Fold AVDは`TYPE_PHONE` / `TYPE_TABLET`のみ、`isTwoPanels=false`。実two-panel runtimeは未確保。
- 既存test表面: production seam instrumentationは[ProductionPublicSeamInstrumentationTest](../../tests/organizer-instrumentation/app/lawnchair/organizer/application/ProductionPublicSeamInstrumentationTest.kt)(実Launcher + 実adapter + favorites退避復元)と[ProductionOrganizationInputInstrumentationTest](../../tests/organizer-instrumentation/app/lawnchair/organizer/integration/ProductionOrganizationInputInstrumentationTest.kt)(composer)が雛形。CI API 35 lane(#83)は明示クラスリストで実行。

## Design

### Modules and interfaces

- 公開interface・portの変更なし。変更はproduction capture adapter内部にとどめる。
- `LauncherLayoutAdapter.capabilities()`を修正し、二panel判定を構築済み現行`DeviceProfile.isTwoPanels`(`InvariantDeviceProfile.getDeviceProfile(context)`)から取得する。`deviceType`の再解釈や画面サイズ推測を行わない。これによりcaptureが参照する判定入力だけの書き換えでは出力を変えられない(spec P1条件)。
- orientation組合せ写像を同file内のinternal純粋関数へ抽出する(isTwoPanels + configurationOrientation → DeviceOrientation)。Android framework状態に触れず、JVM unit test対象となる。

### Data flow

`capture()` → `capabilities()` → `RowManifestCodec.capture` → canonical state(deviceCapabilities)/`ContextResourceCodec`の`DEVICE_PROFILE` payload/`CanonicalMarshalling` digest → revision。orientation値変化時は既存機構によりA2再capture比較でstale拒否、recovery context不一致はfail-closed。新規の永続化・migrationはない。

### Alternatives rejected

| Alternative | Reason |
|---|---|
| `InvariantDeviceProfile.deviceType == TYPE_MULTI_DISPLAY`を直接読む | 値は等価だが、テストでflag単独書き換えにより`TWO_PANEL_*`を出力できてしまい、spec AC-6の証明条件を満たさない(review P1) |
| 画面サイズ/smallest widthからの推測 | Issue outcomeが明示的に禁止 |
| orientation取得用の注入可能port追加 | 必要になるまで仮想interfaceを増やさない規約に反する。production seam経由の検証で十分 |

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt` | `capabilities()`: 構築済み`DeviceProfile.isTwoPanels`参照へ変更。internal純粋写像関数を追加 | production capture seamの権威統一 |
| `tests/unit/app/lawnchair/organizer/application/adapter/` | 純粋写像のJVM unit test(4組合せ) | 決定的な分岐網羅 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/application/` | 新規instrumentation: authority一致性検証+通常host capture(AC-3)、composer保持(AC-4)、orientation変化後stale拒否+無書込み(AC-5) | production seam evidence |
| `.github/workflows/ci.yml` | API 35 laneのclass listへ新instrumentation classを追加 | final-status required evidenceへ組み込む |

## Migration and recovery

- schema/rule migrationなし。enum順序不変のため既存revision・recovery pointとbyte互換。
- downgrade時は旧codeが`TWO_PANEL_*`をcaptureしなくなるだけで、永続data破壊なし。context不一致は既存契約どおり保守的拒否。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 / AC-2 | 実multi-display runtimeでのcapture(foldable AVD等)。成立しない場合はspec Test oracle(2): 一致性検証+純粋mapping proofに限定し残余limitationを記録 | emulator + connectedAndroidTest |
| AC-3 | instrumentation: 非two-panel hostのcaptured orientationが`Configuration.orientation`由来の`PORTRAIT` / `LANDSCAPE`と一致し、authority一致も検証 | API 35 lane |
| AC-4 | instrumentation: `ProductionOrganizationInputComposer`出力のplanner orientation == captured値 | API 35 lane |
| AC-5 | instrumentation: orientation O_A→O_B変化後の`apply`が`STALE_REVISION`拒否、`favorites`事前事後一致 | API 35 lane(emulator rotation制御) |
| AC-6 | PR audit記録にruntime/harness方式・一致性検証結果・残余limitationを明記 | docs/assessment |
| AC-7 | `spotlessCheck`、organizer JVM tests、debug build、API 35/36.1 lanes、CI `final-status` | 各command / GitHub Actions |
| AC-8 | 別sessionによる独立audit記録 | `docs/assessment/pr-<番号>-<slug>.md` |

含めるべき観点: unit(contract相当)、integration(production adapter + 実DB + rotation failure path)、failure injection(stale拒否時の無書込み確認)。

## Documentation updates

- [x] spec status/history
- [ ] CONTEXT.md(用語変更なし)
- [ ] DESIGN.md(structure変更なし)
- [ ] ADR(ADR要件3条件を満たす判断なし)
- [ ] AGENTS.md(command変更なし)

## Execution checklist

- [x] Current behavior reproduced(capabilities()が`TWO_PANEL_*`を出力しないことの確認)
- [x] 純粋写像unit test先行
- [x] production修正
- [x] instrumentation追加とCI lane登録
- [x] local検証(spotlessCheck / organizer JVM tests / assembleLawnWithQuickstepGithubDebug 成功)
- [x] emulator検証: API 35 phone emulator(`api35-test`)で3 test成功(AC-3/AC-4/AC-5)。API 36 Pixel 9 Pro Fold AVDでも3 test成功。実two-panel runtime試行は否定的結果(DisplayControllerのposture変化でbounds cache差し替え、両mode併存せず)→ Test oracle(2)のlimitation記録へ
- [ ] PR(`Closes #130`, `risk: layout-data`)
- [ ] CI `final-status`確認
- [ ] 独立audit
