---
issue: "#130"
status: draft
requirements:
  - NFR-002
  - NFR-007
updated: 2026-08-24
---

# 二panel姿勢をorganizer canonical captureへ保持する

## Problem

canonical captureの`DeviceCapabilities.orientation`は現在`Configuration.orientation`だけから導かれ、すべてのhostを`PORTRAIT`か`LANDSCAPE`へ写像する。採用Lawnchair baselineは`InvariantDeviceProfile.TYPE_MULTI_DISPLAY`、二panel用の`DeviceProfile` index(`INDEX_TWO_PANEL_PORTRAIT/LANDSCAPE`)、multi-display GridOptionを宣言しており、真のmulti-display/two-panel runtimeではworkspaceが二panel構成で動作する。しかしproduction captureが`TWO_PANEL_PORTRAIT` / `TWO_PANEL_LANDSCAPE`を出力できないため、そのようなhostではcanonical revisionとplanner入力が姿勢意味論を失い、通常のphone/tablet向きとして表現される。

[Issue #108](https://github.com/nunu1733/NunuLauncher/issues/108)のdevice evidenceで使ったPixel Tablet / Pixel 9 Pro Fold AVDは`TYPE_PHONE`または`TYPE_TABLET`のみを露出し、`DeviceProfile.isTwoPanels=false`であったため、このcellは埋まっていない。source調査により欠落しているproduction写像は確定済みであり、本Issueが修正と実機相当での検証を担う。

## Outcome

canonical orientationを、`DeviceProfile.isTwoPanels`を決定するのと同じ権威あるLauncher display/device-profile状態と、現在のportrait/landscape orientationの組合せから導く。画面サイズからの推測を行わず、第二のdevice-profile sourceを導入しない。two-panel hostでは正確な姿勢がcapture・revision・planner入力へ伝播し、phone/tablet hostのcaptured値は不変である。

### Authority chain(調査済み事実、baseline commit `505dbc40e6154c05158b5d0271c45f6a885a411b`)

- `DisplayController.Info.getDeviceType()`はsupported boundsにphone mode(< 600dp smallest)とtablet mode(>= 600dp)の両方が含まれるとき`TYPE_MULTI_DISPLAY`を返す。fold状態変化でdisplay sizeが変わるとbounds集合が蓄積されるため、両modeの併存は実foldableで発生する。
- `InvariantDeviceProfile.deviceType`がこの値を保持し、`DeviceProfile`生成時に`.setIsMultiDisplay(deviceType == TYPE_MULTI_DISPLAY)`として渡される。
- `DeviceProfile.isTwoPanels = isMultiDisplay`であり、`mTypeIndex`は`isTwoPanels`とwindow boundsの向きで`INDEX_TWO_PANEL_*`へ分岐する。
- したがって`deviceType == TYPE_MULTI_DISPLAY`は`isTwoPanels`の唯一の決定要因である。これを読むこと自体は第二sourceの導入ではない。

## Scope

- production canonical capture seam(`LayoutWriterPort.captureCurrent` / `recaptureDb`)がtwo-panel hostに対し`TWO_PANEL_PORTRAIT` / `TWO_PANEL_LANDSCAPE`を出力すること。
- phoneおよび非two-panel tablet hostは従来どおり`Configuration.orientation`由来の`PORTRAIT` / `LANDSCAPE`を出力すること。既存portrait/landscape判定のsourceは変更しない。
- `ProductionOrganizationInputComposer`経由のplanner入力がcaptured orientationをexactに保持すること(既存public→planning写像の再確認)。
- 姿勢・orientation変化がcanonical revisionへ反映され、変更前planの適用がstale拒否されDB書込みが発生しないこと(既存revision/stale機構経由での確認)。

## Non-goals

- planner algorithm、planner公開型、application/recovery書込みpath、UIの変更。
- grid寸法(columns/rows/hotseat/folder)取得sourceの変更。真のtwo-panel hostでIDP由来のDB grid数と実際のworkspace gridが一致しない可能性は既知の限界として[Issue #108](https://github.com/nunu1733/NunuLauncher/issues/108) matrixへ記録し、本Issueで扱わない。
- 新しいpublic API、新しいinterfaceやadapter、screen size推測、second device-profile sourceの追加。
- 実emulator以外でのtwo-panel再現設定(desktop mode等)の新規採用。

## Domain language

追加しない。`Orientation.TWO_PANEL_PORTRAIT` / `TWO_PANEL_LANDSCAPE`(planning型およびpublic `DeviceOrientation`)は既存であり、UI summary([ManualOrganizationRun](../../lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt))も既に二panel値をportrait/landscapeへ折り畳むため変更不要である。

## Behavior scenarios

### Scenario: two-panel portrait hostがTWO_PANEL_PORTRAITをcaptureする

Given 権威あるdisplay/device-profile状態が`TYPE_MULTI_DISPLAY`(two-panel)でconfiguration orientationがportraitのhost。
When production capture seamでcanonical captureを行う。
Then captured `DeviceCapabilities.orientation`は`TWO_PANEL_PORTRAIT`である。
And 他のcapability(columns/rows/hotseat/folder)の意味は変わらない。

### Scenario: two-panel landscape hostがTWO_PANEL_LANDSCAPEをcaptureする

Given 権威ある状態が`TYPE_MULTI_DISPLAY`でconfiguration orientationがlandscapeのhost。
When production capture seamでcanonical captureを行う。
Then captured orientationは`TWO_PANEL_LANDSCAPE`である。

### Scenario: phone/tablet hostのcapture値は不変

Given 非two-panel host(`TYPE_PHONE`または`TYPE_TABLET`)。
When canonical captureを行う。
Then orientationは従来どおり`PORTRAIT` / `LANDSCAPE`であり、enum ordinalも不変である。
And 既存hostのrevision計算結果は本修正前後で等しい。

### Scenario: composerがcaptured orientationをexact保持する

Given `TWO_PANEL_*`を含む任意のcaptured snapshot。
When `ProductionOrganizationInputComposer.composeFullOrganization()`が`Ready`を返す。
Then `input.snapshot.device.orientation`はcaptured値と一致する。

### Scenario: 姿勢変化後のpre-change plan適用はstale拒否される

Given posture Aでcaptureしたsnapshotから準備したplan/write-set。
When posture B(captured orientation値が変わる変化)の下で適用を試みる。
Then revision不一致によりstaleとして拒否され、`favorites`行は一切変化しない。

### Scenario: recovery contextのorientation不一致はfail-closed

Given `DEVICE_PROFILE` resourceにposture Aのorientation値を持つrecovery target manifest。
When 異なるorientation値のcurrent contextでrecovery write-set準備を行う。
Then context mismatchとしてfail-closedし、書込みを行わない。resourceはpreserve-onlyである。

## Data and state

- 読むdata: `InvariantDeviceProfile` singleton(device state判別)とapplication contextのconfiguration orientation。layoutの正本がLauncher DBであることは不変。
- 永続化への影響: orientation ordinalはcanonical digest/revision encoding([CanonicalMarshalling](../../lawnchair/src/app/lawnchair/organizer/application/canonical/CanonicalMarshalling.kt))とrecovery manifestの`DEVICE_PROFILE` resource([ContextResourceCodec](../../lawnchair/src/app/lawnchair/organizer/application/adapter/ContextResourceCodec.kt)、format version 1)へ現れる。enum順序を変更しないためphone/tablet hostの既存revision・recovery pointとのbyte互換性は維持され、format version bumpは不要である。
- migration / rollback: schema・rule migrationなし。downgrade時は旧codeが`TWO_PANEL_*`をcaptureしなくなるだけで、永続dataの破壊はなく、context不一致は既存契約どおり保守的に拒否される。

## Permissions, privacy, and security

None。読み取りはlauncher process内の既存system状態のみであり、permission・network・telemetryを追加しない。

## Accessibility and localization

None。UI表示は不変である。

## Acceptance criteria

- [ ] **AC-1:** currentな`TYPE_MULTI_DISPLAY` / two-panel portrait hostのcaptureが`TWO_PANEL_PORTRAIT`を出力する。
- [ ] **AC-2:** currentな`TYPE_MULTI_DISPLAY` / two-panel landscape hostのcaptureが`TWO_PANEL_LANDSCAPE`を出力する。
- [ ] **AC-3:** phoneおよび非two-panel tablet hostは従来どおり`PORTRAIT` / `LANDSCAPE`を出力する。
- [ ] **AC-4:** `ProductionOrganizationInputComposer`がcaptured orientationをexactにplanner入力へ保持する。
- [ ] **AC-5:** two-panel姿勢・orientation変化がcanonical revisionへ反映され、変更前plan適用はstale拒否されLauncher DB書込みが発生しない。
- [ ] **AC-6:** testはproduction capture seamと同じLauncher display/device-profile権威を使う。実multi-display runtimeを優先し、使用できない場合は等価harnessと残余device限界を明示する。
- [ ] **AC-7:** 関連organizer test、API 35/36.1 compatibility lane、formatting、build、CI `final-status`が成功する。
- [ ] **AC-8:** 実装PRは`risk: layout-data`を持ち、独立audit evidence([github-workflow.md](../../docs/project/github-workflow.md)形式)を揃える。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1〜AC-3 | 純粋orientation写像のJVM unit test(4組合せ、決定的)。加えて実emulator上のproduction seam instrumentation: 実`LauncherLayoutAdapter.captureCurrent`経由でdefault host(非two-panel)と権威state値を一時的に`TYPE_MULTI_DISPLAY`へ設定した状態(two-panel相当)の双方をcaptureし、host configurationの向きと組合せて検証する。権威state値の設定はtry/finallyで必ず復元する。landscape cellがemulator操作で不安定な場合は等価証明へ置換し、残余limitationを明示する |
| AC-4 | [ProductionOrganizationInputInstrumentationTest](../../tests/organizer-instrumentation/app/lawnchair/organizer/integration/ProductionOrganizationInputInstrumentationTest.kt)と同一patternで、`deviceCapabilities.orientation`のequalityをtwo-panel相当state含めて検証するinstrumentation |
| AC-5 | instrumentation: posture Aのcaptureからplan/write-setを準備し、posture変更後に適用して`STALE_REVISION`拒否を確認、`favorites`行の事前事後一致を検証する |
| AC-6 | PR audit記録へ#108 AVD evidence(非two-panel)、harness方式、残余limitationを明記する |
| AC-7 | `spotlessCheck`、`testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、`assembleLawnWithQuickstepGithubDebug`、API 35/36.1 lane、CI `final-status` |
| AC-8 | 別sessionによる`docs/assessment/pr-<番号>-<slug>.md` |

## Open questions

- CI emulator上での実rotation制御が安定しない場合、AC-2のlandscape cellを純粋mapping unit test + portrait側authority override captureによる等価証明へ置き換えるかどうか。実装時に決定し、結果と理由をPRへ記録する(non-blocking)。

## Change history

- 2026-08-24: Issue #130のreview用`draft`仕様を作成。権威chain調査と既存test表面調査を反映。

## References

- [Issue #130](https://github.com/nunu1733/NunuLauncher/issues/130) — 本Issue(parent evidence gap: #108)
- [Issue #108](https://github.com/nunu1733/NunuLauncher/issues/108) — organizer MVP compatibility evidence matrix
- [docs/product/requirements.md](../../docs/product/requirements.md) — NFR-002(Integrity)、NFR-007(Compatibility)
- [specs/83-production-organization-input-sources/spec.md](../83-production-organization-input-sources/spec.md) — production capture/composition seam契約
- [LauncherLayoutAdapter](../../lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt) — 現行`capabilities()`実装
