---
issue: "#130"
status: accepted
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

canonical orientationを、production authority chain(`DisplayController.Info.getDeviceType()` → `InvariantDeviceProfile` → `DeviceProfile`生成)で実際に構築された現行`DeviceProfile`の`isTwoPanels`と、現在のportrait/landscape orientationの組合せから導く。capture側で判定用flagを独自に再解釈せず、画面サイズからの推測を行わず、第二のdevice-profile sourceを導入しない。two-panel hostでは正確な姿勢がcapture・revision・planner入力へ伝播し、phone/tablet hostのcaptured値は不変である。

### Authority chain(調査済み事実、baseline commit `505dbc40e6154c05158b5d0271c45f6a885a411b`)

- `DisplayController.Info.getDeviceType()`はsupported boundsにphone mode(< 600dp smallest)とtablet mode(>= 600dp)の両方が含まれるとき`TYPE_MULTI_DISPLAY`を返す。fold状態変化でdisplay sizeが変わるとbounds集合が蓄積されるため、両modeの併存は実foldableで発生する。
- `InvariantDeviceProfile.deviceType`がこの値を保持し、`DeviceProfile`生成時に`.setIsMultiDisplay(deviceType == TYPE_MULTI_DISPLAY)`として渡される。
- `DeviceProfile.isTwoPanels = isMultiDisplay`であり、`mTypeIndex`は`isTwoPanels`とwindow boundsの向きで`INDEX_TWO_PANEL_*`へ分岐する。
- したがって`deviceType == TYPE_MULTI_DISPLAY`は`isTwoPanels`の唯一の決定要因であり、`supportedProfiles`はすべて同じ`isMultiDisplay`値で構築される。
- captureは判定を再構築せず、構築済み`DeviceProfile.isTwoPanels`を読む。これによりUIと同一のauthorityを共有し、captureが参照する判定入力だけを単独で書き替えてもcaptured出力は変化しない。

## Scope

- production canonical capture seam(`LayoutWriterPort.captureCurrent` / `recaptureDb`)がtwo-panel hostに対し`TWO_PANEL_PORTRAIT` / `TWO_PANEL_LANDSCAPE`を出力すること。
- phoneおよび非two-panel tablet hostは従来どおり`Configuration.orientation`由来の`PORTRAIT` / `LANDSCAPE`を出力すること。既存portrait/landscape判定のsourceは変更しない。
- `ProductionOrganizationInputComposer`経由のplanner入力がcaptured orientationをexactに保持すること(既存public→planning写像の再確認)。
- `DeviceOrientation`が変化するtwo-panel/ordinary遷移またはportrait/landscape遷移がcanonical revisionへ反映され、変更前planの適用がstale拒否されDB書込みが発生しないこと(既存revision/stale機構経由での確認)。物理postureが変わってもcaptured orientation値が不変なcaseではrevisionは変化しない。

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

### Scenario: orientation値が変わる遷移後のpre-change plan適用はstale拒否される

Given captured orientationがO_Aであるときにcaptureしたsnapshotから準備したplan/write-set。
When captured orientationがO_B(O_A ≠ O_B)となる遷移の下で適用を試みる。
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
- [ ] **AC-5:** captured `DeviceOrientation`が変化するtwo-panel/ordinary遷移またはportrait/landscape遷移はcanonical revisionへ反映され、変更前plan適用はstale拒否されLauncher DB書込みが発生しない。
- [ ] **AC-6:** testはproduction capture seamと同じLauncher display/device-profile権威を使う。実multi-display runtimeを優先し、使用できない場合は等価harnessと残余device限界を明示する。ただし等価harnessは、captureが参照するflagだけを直接書き換えてはならない。`DisplayController.Info.getDeviceType()` → IDP → `DeviceProfile.isTwoPanels`と同じproduction authority chainを成立させるか、少なくともharness上でproduction captureのtwo-panel判定と実際に構築された`DeviceProfile.isTwoPanels`が一致することを検証する。これを実現できない場合、そのharnessは純粋mapping proofに限定し、実two-panel Launcher-host evidenceの代替とは扱わない。
- [ ] **AC-7:** 関連organizer test、API 35/36.1 compatibility lane、formatting、build、CI `final-status`が成功する。
- [ ] **AC-8:** 実装PRは`risk: layout-data`を持ち、独立audit evidence([github-workflow.md](../../docs/project/github-workflow.md)形式)を揃える。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1〜AC-3 | 純粋orientation写像のJVM unit test(4組合せ、決定的)。host evidenceは次の順で取得する。(1) 最優先: foldable AVD等の実runtimeでfold状態遷移により`TYPE_MULTI_DISPLAY`(実構築済み`DeviceProfile.isTwoPanels == true`)を成立させ、実`LauncherLayoutAdapter.captureCurrent`経由で`TWO_PANEL_PORTRAIT` / `TWO_PANEL_LANDSCAPE`を取得する。(2) 成立しない場合: captureが参照する判定入力を単独で書き替える方式は採用せず、テストhost上で「captured orientationのtwo-panel判定 == 実際に構築された`DeviceProfile.isTwoPanels`」の一致検証と純粋mapping proofのみを行い、`TWO_PANEL_*`のhost evidenceが未取得であることを残余limitationとして[Issue #108](https://github.com/nunu1733/NunuLauncher/issues/108) matrixへ明示する |
| AC-4 | [ProductionOrganizationInputInstrumentationTest](../../tests/organizer-instrumentation/app/lawnchair/organizer/integration/ProductionOrganizationInputInstrumentationTest.kt)と同一patternで、`deviceCapabilities.orientation`のequalityを利用可能なhost(可能ならAC-1のruntime)で検証するinstrumentation |
| AC-5 | instrumentation: captured orientation O_Aのcaptureからplan/write-setを準備し、O_B(O_A ≠ O_B)へ変化後に適用して`STALE_REVISION`拒否を確認、`favorites`行の事前事後一致を検証する |
| AC-6 | PR audit記録へ、使用したruntime/harness方式、authority chain成立または一致性検証の結果、残余limitationを明記する |
| AC-7 | `spotlessCheck`、`testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`、`assembleLawnWithQuickstepGithubDebug`、API 35/36.1 lane、CI `final-status` |
| AC-8 | 別sessionによる`docs/assessment/pr-<番号>-<slug>.md` |

## Open questions

- ~~実multi-display runtime(foldable AVDのfold状態遷移等)で`TYPE_MULTI_DISPLAY`かつ実構築済み`DeviceProfile.isTwoPanels == true`を成立させ、AC-1/AC-2のhost evidenceを取得できるか。~~ **解消済(2026-08-24、非該当確認)。** Pixel 9 Pro Fold AVD(API 36、`CLOSED` 1080x2424@390dpi ≈ 443dp / `OPENED` 2076x2152@390dpi ≈ 852dp)でfold状態遷移を実行した結果、本baselineの`DisplayController`はposture変化時に`perDisplayBounds` cacheを差し替え(FileLog `(Invalid Cache)` + `CHANGE_SUPPORTED_BOUNDS`に単一エントリ)、phone/tablet両modeの併存が発生しないため`TYPE_MULTI_DISPLAY` / `isTwoPanels == true`は実emulatorで成立しない。spec Test oracle(2)に従い、実hostでのauthority一致性検証 + 純粋mapping proofをもって証拠とし、`TWO_PANEL_*`の実host capture evidenceは未取得であることを残余limitationとして[Issue #108](https://github.com/nunu1733/NunuLauncher/issues/108) matrixへ記録する。
- CI emulator上でのlandscape cell取得方法 → 実rotation制御(`user_rotation` + 上流`forceAllowRotationForTesting`相当hook + launcher foreground)で解消済。API 35 phone emulatorとAPI 36 foldable emulatorの両方で`PORTRAIT`→`LANDSCAPE`遷移後のstale拒否を確認した。

## Change history

- 2026-08-24: Issue #130のreview用`draft`仕様を作成。権威chain調査と既存test表面調査を反映。
- 2026-08-24: Spec review(Issue #130コメント)の指摘を反映。P1: capture対象をproduction authority chainで構築された現行`DeviceProfile.isTwoPanels`へ明確化し、等価harnessの成立条件(flagの单独書き換え禁止、chain成立または一致性検証、不成立時は純粋mapping proofに限定)をAC-6/Test oracleへ追加。P2: posture change表現をcaptured `DeviceOrientation`値が変化する遷移へ限定(Outcome/Scope/AC-5/Scenario)。
- 2026-08-24: Maintainer acceptance。statusを`accepted`へ更新。Open questionsはnon-blockingとして実装時に解消しPRへ記録する。

## References

- [Issue #130](https://github.com/nunu1733/NunuLauncher/issues/130) — 本Issue(parent evidence gap: #108)
- [Issue #108](https://github.com/nunu1733/NunuLauncher/issues/108) — organizer MVP compatibility evidence matrix
- [docs/product/requirements.md](../../docs/product/requirements.md) — NFR-002(Integrity)、NFR-007(Compatibility)
- [specs/83-production-organization-input-sources/spec.md](../83-production-organization-input-sources/spec.md) — production capture/composition seam契約
- [LauncherLayoutAdapter](../../lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt) — 現行`capabilities()`実装
